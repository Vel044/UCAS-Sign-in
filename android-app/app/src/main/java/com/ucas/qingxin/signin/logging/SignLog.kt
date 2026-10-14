package com.ucas.qingxin.signin.logging

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 扫码 / 签到的本地诊断日志。
 *
 * ## 它要解决的问题
 * 现场打卡**只有一次机会**：用户站在班牌前，而学校的签到码大约每 5 秒轮换一次。
 * 一旦失败，能回答「到底发生了什么」的东西就只剩这一次交互的原始数据 ——
 * 二维码原文、我们发出去的地址、学校回的正文。这些东西**错过了就没了**，
 * 而「签到码已失效」这类拒绝本身并不区分「码过期了」与「我们的参数不对」。
 * 1.3.1 的一次真机失败正是卡在这里（`docs/lecture-signin-research.md` §10.16）。
 *
 * ## 只记本地，不自动外发
 * 日志写在应用私有目录里，**不会**自动上传到任何地方。只有用户主动点「分享 / 保存」
 * 才会离开设备 —— 因此界面必须把这件事说清楚，而不是让它默默攒数据。
 *
 * ## 不记什么（比「记什么」更重要）
 * - **绝不记请求头**：`sessionId` 就在里面，它是能直接冒充用户的凭据。
 *   本类的接口只接受「键值对」，压根没有位置放头信息。
 * - **`id` 参数只记参数名**：它就是本人学号，对诊断没增益（身份由登录态决定），
 *   而日志可能被转发。参与者见 [maskOwnId]。
 * - **密码 / 验证码**：这些从来不经过这里。
 *
 * ## 线程模型
 * 写入走**单线程执行器**，因此：调用方永不阻塞（可以放心在界面线程调用）、
 * 记录顺序与发生顺序一致。读取是同步的，调用方自行放到 IO 线程。
 *
 * 解析与格式化是纯函数（见 [format]），文件操作只碰 [dir] 一个目录，
 * 因此这一个类可以在 JVM 单测里跑完（见 `SignLogTest`）。
 */
class SignLog(private val dir: File) {

    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sign-log").apply { isDaemon = true }
    }

    /** 当前正在写的文件。分享 / 保存时直接指向它。 */
    val file: File get() = File(dir, FILE_NAME)

    /**
     * 追加一条记录。
     *
     * 永不抛出：日志是**辅助**功能，它坏掉绝不能把签到本身带崩 ——
     * 现场打卡失败的代价远大于「这次没记上日志」。
     */
    fun append(kind: String, vararg fields: Pair<String, String>) {
        val record = runCatching { format(nowMs(), kind, fields.toList()) }.getOrNull() ?: return
        runCatching {
            writer.execute { runCatching { appendBlocking(record) } }
        }
    }

    /** 读最近一段（按字节上限，且从记录边界开始），供界面展示与分享。 */
    fun readTail(maxBytes: Int = DISPLAY_BYTES): String {
        val text = runCatching { if (file.exists()) file.readText() else "" }.getOrDefault("")
        if (text.isEmpty()) return ""
        return runCatching { tailFromBoundary(text, maxBytes) }.getOrDefault(text)
    }

    /** 当前日志占用字节数（界面用来显示，也用于判断是否值得清理）。 */
    fun sizeBytes(): Long = runCatching { if (file.exists()) file.length() else 0L }.getOrDefault(0L)

    /** 清空。界面必须二次确认后再调用 —— 这是用户唯一的现场证据。 */
    fun clear() {
        runCatching { writer.execute { runCatching { file.delete() } } }
    }

    /** 确保日志文件存在（分享前用：接收方打不开一个不存在的文件）。 */
    fun ensureFile(): File {
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            if (!file.exists()) file.createNewFile()
        }
        return file
    }

    /**
     * 等所有已入队的写入落盘。
     *
     * 分享 / 保存前必须调用，否则可能导出到一份**还没写完**的日志 ——
     * 而用户通常正是在失败之后立刻来导日志的。
     * 它会阻塞到写完为止（一次几百字节的追加，通常是微秒级），调用方放 IO 线程即可。
     */
    fun flush() {
        runCatching { writer.submit { }.get(5, TimeUnit.SECONDS) }
    }

    /** 仅测试用：与 [flush] 同义，保留旧名字以免测试读起来像在等线程池。 */
    internal fun awaitIdle() = flush()

    // ------------------------------------------------------------------ 文件

    private fun appendBlocking(record: String) {
        if (!dir.exists()) dir.mkdirs()
        file.appendText(record)
        if (file.length() > MAX_BYTES) trimBlocking()
    }

    /**
     * 超过上限时，只保留最近的 [KEEP_BYTES]，且**从记录边界切开**。
     *
     * 从边界切是有意的：半条记录对诊断是净损失 —— 它看起来像一条完整记录，
     * 却缺了最关键的字段，比没有更容易误导。
     */
    private fun trimBlocking() {
        val text = file.readText()
        file.writeText(tailFromBoundary(text, KEEP_BYTES))
    }

    companion object {
        const val FILE_NAME = "sign-log.txt"

        /** 单条字段值的截断长度：响应正文可能很长，但诊断只需要头部。 */
        const val MAX_FIELD_CHARS = 2000

        /** 展示时读多少字节（约合几十条记录，够贴一次现场）。 */
        const val DISPLAY_BYTES = 64 * 1024

        /** 文件超过它就裁剪。 */
        const val MAX_BYTES = 512L * 1024

        /** 裁剪后保留多少字节。 */
        const val KEEP_BYTES = 256 * 1024

        /** 记录之间的分隔标记，同时也是「记录边界」的判据。 */
        const val RECORD_MARK = "\n=== "

        private val STAMP = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        }

        /**
         * 把一条记录格式化成文本。
         *
         * 纯函数：时间戳由调用方传入，因此可以离线断言输出（见 `SignLogTest`）。
         * 用**人能直接读**的形态而不是 JSON —— 这份日志的主要读者是
         * 「把结果卡片贴给维护者的人」，不是解析器。
         */
        fun format(atMs: Long, kind: String, fields: List<Pair<String, String>>): String = buildString {
            append(RECORD_MARK)
            append(STAMP.get()?.format(Date(atMs)) ?: atMs.toString())
            append(" · ")
            append(kind)
            append(" ===")
            for ((key, value) in fields) {
                append('\n')
                append(key)
                append(": ")
                append(clip(value))
            }
            append('\n')
        }

        /**
         * 把本人学号换成占位符，保留参数名。
         *
         * 该留的是「**有没有**带这个参数」—— 那才是学校判定的依据；
         * 至于值是多少，它由登录态决定，写进日志只有泄露风险、没有诊断价值。
         */
        fun maskOwnId(url: String): String =
            url.replace(Regex("""([?&]id=)[^&#]*"""), "$1<本人学号>")

        private fun clip(value: String): String =
            if (value.length <= MAX_FIELD_CHARS) value
            else value.take(MAX_FIELD_CHARS) + "…（已截断，共 ${value.length} 字符）"

        /** 从 [text] 的**记录边界**起取最后 [maxBytes] 字节。 */
        private fun tailFromBoundary(text: String, maxBytes: Int): String {
            if (text.length <= maxBytes) return text
            val start = text.length - maxBytes
            val boundary = text.lastIndexOf(RECORD_MARK, start)
            return if (boundary >= 0) text.substring(boundary + 1) else text.substring(start)
        }

        private fun nowMs(): Long = System.currentTimeMillis()
    }
}

/** 日志条目的类别。用中文常量，因为界面直接展示它们。 */
object LogKind {
    /** 相机解出一段文本（分类之前）。 */
    const val SCAN = "扫码"

    /** 分类结果，以及由此决定发不发请求。 */
    const val CLASSIFY = "分类"

    /** 真正发出去的请求。 */
    const val REQUEST = "签到请求"

    /** 学校回的正文（或网络层失败原因）。 */
    const val RESPONSE = "签到响应"

    /** 最终判定的结果。 */
    const val OUTCOME = "结果"
}
