package com.ucas.qingxin.signin.history

import com.ucas.qingxin.signin.logging.SignLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 一次签到的来源。界面直接展示这些名字，因此是中文。 */
enum class SignSource(val label: String) {
    SCAN("扫码"),
    MANUAL("手动录入"),
    ONE_CLICK("一键签到"),
    AUTO("自动签到"),
    WIDGET("桌面小部件"),
}

/**
 * 「我签到过什么」的一条记录。
 *
 * ## 与 [SignLog] 的分工（这是本类存在的理由）
 * 两者都在本机、都由用户主动分享，但**读者不同**：
 * - [SignLog] 是**诊断日志**，写给维护者：二维码原文、发出去的地址、学校响应正文，
 *   目的是回答「这次为什么失败」；
 * - 本类是你**自己的签到历史**，写给用户：什么时候、从哪来、哪个编号、成没成，
 *   目的是回答「我到底签上了没有」。
 *
 * 为什么这件事在我们这儿格外重要：讲座**不在课表里**，而学校的系统里也没有
 * 「我的讲座签到」可查（见 `docs/lecture-signin-research.md` §10.18）——
 * 于是本机这份记录成了唯一能回看讲座签到历史的地方。
 *
 * ## 刻意不记的东西
 * - **不记登录凭据**：`sessionId` 连进入这里的通道都没有（签到结果里不含它）。
 * - **不记学号**：编号是节次标识，不是身份；`id` 参数由 API 层追加，永远不进记录。
 *   [raw] 仍会过一道 [SignLog.maskOwnId]，因为二维码原文里理论上可能带 `id=`。
 * - **不记请求头**：这里没有任何位置可以放它。
 *
 * 纯数据 + 纯函数（[SignRecordStore.format] / [SignRecordStore.parseAll]），
 * 因此解析与格式化可以在 JVM 单测里离线断言（见 `SignRecordStoreTest`）。
 */
data class SignRecord(
    /** 发生时刻（epoch 毫秒）。写进文件的是这个值，展示时再本地化。 */
    val atMs: Long,
    /** 这一次是「谁」发起的。 */
    val source: SignSource,
    /** 提交用的标识（7 位节次编号 / 32 位标识）；扫码走 query 时可能为空。 */
    val code: String,
    /** 标识的形态说明，如「7 位节次编号」。 */
    val kind: String,
    /** 服务端是否接受了这次签到。 */
    val ok: Boolean,
    /** 学校原话，或网络层的失败原因。 */
    val message: String,
    /** 二维码原文（仅扫码时有）；用于事后核对是哪个码。 */
    val raw: String = "",
    /**
     * 机器可读的判定结果（如 `QR_EXPIRED` / `OUTSIDE_SIGN_WINDOW`），仅失败时展示。
     *
     * ## 为什么单列一个字段（2026-10-14 补记）
     * 学校对不同的拒绝原因会给**同一句**「签到码已失效」，而我们自己的解析会把它们
     * 分成 `QR_EXPIRED` 与 `SIGN_RESULT_UNKNOWN` 两种 —— 这两者指向的下一步动作
     * 完全不同（前者是码的问题，后者要回首页核对状态）。第一份真机失败记录
     * （`docs/sign-records.txt`）里恰好只剩学校那句原话，判定结果无从追溯，
     * 因此把它一并存下来。
     *
     * 空串 = 该条记录产生于本次升级之前（旧文件仍能正常读回，见 [parseAll]）。
     */
    val outcome: String = "",
    /**
     * 本次提交的**参数来源说明**（人话，如「已改用本校校时提交」）。
     *
     * 与 [outcome] 一起，让一份分享出来的记录**自己就能说明白**
     * 我们发出去的到底是什么，不必再让用户去翻调试日志。
     */
    val note: String = "",
)

/**
 * 签到历史的本地存档。
 *
 * ## 为什么是追加型文件而不是数据库
 * 与 [SignLog] 同因：这份数据量级极小（一学期几十到几百条），而代价是
 * 「引入 Room 就要处理迁移、schema、更多单元测试」—— 为了一个列表页不值得。
 * 记录之间用 [RECORD_MARK] 切开，读取时从**记录边界**开始截取，
 * 因此永远读不到半条记录（半条比没有更容易误导，见 [SignLog] 的同款说明）。
 *
 * ## 线程模型
 * 写入走单线程执行器（调用方永不阻塞，界面线程可以放心调用），读取是同步的。
 * 任何一步失败都**不抛异常**：记录是辅助功能，它坏掉绝不能把签到本身带崩。
 */
class SignRecordStore(private val dir: File) {

    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sign-record").apply { isDaemon = true }
    }

    /** 正在写的文件。分享 / 保存时直接指向它。 */
    val file: File get() = File(dir, FILE_NAME)

    /** 追加一条记录。永不抛出（理由见类注释）。 */
    fun append(record: SignRecord) {
        val block = runCatching { format(record) }.getOrNull() ?: return
        runCatching { writer.execute { runCatching { appendBlocking(block) } } }
    }

    /** 读最近的记录，**新的在前**（界面只需要这个顺序）。 */
    fun readLatest(maxBytes: Int = READ_BYTES): List<SignRecord> {
        val text = runCatching { if (file.exists()) file.readText() else "" }.getOrDefault("")
        if (text.isEmpty()) return emptyList()
        val tail = runCatching { tailFromBoundary(text, maxBytes) }.getOrDefault(text)
        return runCatching { parseAll(tail) }.getOrDefault(emptyList()).asReversed()
    }

    /** 全部记录的条数（用于设置页显示的摘要）。 */
    fun count(): Int {
        val text = runCatching { if (file.exists()) file.readText() else "" }.getOrDefault("")
        if (text.isEmpty()) return 0
        return runCatching { parseAll(text).size }.getOrDefault(0)
    }

    fun sizeBytes(): Long = runCatching { if (file.exists()) file.length() else 0L }.getOrDefault(0L)

    fun clear() {
        runCatching { writer.execute { runCatching { file.delete() } } }
    }

    /** 确保文件存在（分享前用：接收方打不开一个不存在的文件）。 */
    fun ensureFile(): File {
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            if (!file.exists()) file.createNewFile()
        }
        return file
    }

    /**
     * 等已入队的写入落盘。
     *
     * 分享 / 保存前必须调用，否则可能导出一份**还没写完**的记录 ——
     * 而用户通常正是在刚签完（或刚失败）的那一刻来导出的。
     */
    fun flush() {
        runCatching { writer.submit { }.get(5, TimeUnit.SECONDS) }
    }

    // ------------------------------------------------------------------ 文件

    private fun appendBlocking(block: String) {
        if (!dir.exists()) dir.mkdirs()
        file.appendText(block)
        if (file.length() > MAX_BYTES) trimBlocking()
    }

    private fun trimBlocking() {
        val text = file.readText()
        file.writeText(tailFromBoundary(text, KEEP_BYTES))
    }

    companion object {
        const val FILE_NAME = "sign-records.txt"

        /** 界面一次读多少字节。 */
        const val READ_BYTES = 256 * 1024

        /** 文件超过它就裁剪。比 [SignLog] 小一号：记录条数本就少，不必留太厚。 */
        const val MAX_BYTES = 256L * 1024

        /** 裁剪后保留多少字节。 */
        const val KEEP_BYTES = 128 * 1024

        /** 记录分隔标记，同时也是「记录边界」的判据。 */
        const val RECORD_MARK = "\n=== "

        private const val FIELD_SEP = '\u0001'

        // 毫秒精度：现场实验要对齐「本地时刻 ↔ 提交的 timestamp ↔ 学校判定」三条时间轴，
        // 秒级格式会让同一秒内的连续尝试无法配对（2026-10-22 的记录里 18:36:15 就有两条）。
        private val STAMP = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        }

        private val DAY = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd", Locale.US)
        }

        // ---------------------------------------------------------- 纯函数

        /** 一条记录 → 一段文本。时间戳写成 epoch 毫秒，展示时才本地化。 */
        fun format(record: SignRecord): String = buildString {
            append(RECORD_MARK)
            append(record.atMs)
            append('|')
            append(record.source.name)
            append('|')
            append(if (record.ok) "1" else "0")
            append(" ===")
            append('\n').append(KEY_CODE).append(FIELD_SEP).append(escape(record.code))
            append('\n').append(KEY_KIND).append(FIELD_SEP).append(escape(record.kind))
            append('\n').append(KEY_MESSAGE).append(FIELD_SEP).append(escape(record.message))
            // 两个新字段都只在有内容时写：旧版本读到的记录、以及绝大多数正常签到
            // 都不需要它们，写一堆空行只会让这份文件更难读。
            if (record.outcome.isNotEmpty()) {
                append('\n').append(KEY_OUTCOME).append(FIELD_SEP).append(escape(record.outcome))
            }
            if (record.note.isNotEmpty()) {
                append('\n').append(KEY_NOTE).append(FIELD_SEP).append(escape(record.note))
            }
            if (record.raw.isNotEmpty()) {
                // 二维码原文仍过一道学号脱敏：这段文本会被分享出去。
                append('\n').append(KEY_RAW).append(FIELD_SEP)
                    .append(escape(runCatching { SignLog.maskOwnId(record.raw) }.getOrDefault(record.raw)))
            }
            append('\n')
        }

        /**
         * 文本 → 记录列表，**按写入顺序**（最旧在前）。
         *
         * 容错是有意的：单条记录格式不对劲时只跳过它，而不是让整份历史都读不出来 ——
         * 历史的价值在于「大部分还在」，一条坏记录不该把整页清空。
         */
        fun parseAll(text: String): List<SignRecord> {
            if (text.isBlank()) return emptyList()
            return text.split(RECORD_MARK)
                .asSequence()
                .mapNotNull { parseBlock(it) }
                .toList()
        }

        private fun parseBlock(block: String): SignRecord? {
            val lines = block.lineSequence().toList()
            val header = lines.firstOrNull()?.trim().orEmpty()
                .removeSuffix(HEADER_TAIL)
                .trim()
            val parts = header.split('|')
            if (parts.size < 3) return null
            val atMs = parts[0].toLongOrNull() ?: return null
            val source = SignSource.entries.firstOrNull { it.name == parts[1] } ?: return null
            val ok = parts[2].trim() == "1"

            val fields = HashMap<String, String>(6)
            for (line in lines.drop(1)) {
                val at = line.indexOf(FIELD_SEP)
                if (at <= 0) continue
                fields[line.substring(0, at)] = unescape(line.substring(at + 1))
            }
            return SignRecord(
                atMs = atMs,
                source = source,
                code = fields[KEY_CODE].orEmpty(),
                kind = fields[KEY_KIND].orEmpty(),
                ok = ok,
                message = fields[KEY_MESSAGE].orEmpty(),
                raw = fields[KEY_RAW].orEmpty(),
                outcome = fields[KEY_OUTCOME].orEmpty(),
                note = fields[KEY_NOTE].orEmpty(),
            )
        }

        /** 时刻 → 本地时间文本，界面与导出都用它。 */
        fun stamp(atMs: Long): String = STAMP.get()?.format(Date(atMs)) ?: atMs.toString()

        /**
         * 记录 → **可分享的**文本。
         *
         * 刻意与写进文件的机器格式分开：文件是给本程序读回来的（epoch 毫秒、字段名），
         * 而导出的这份是给人看的 —— 用户会把它贴进聊天框，或存下来日后翻。
         * 两者混用会让分享出去的东西变成一串机器字段，等于让用户替我们做解析。
         *
         * [records] 按给定顺序输出（调用方传「新的在前」，与界面一致）。
         */
        fun exportText(records: List<SignRecord>, exportedAtMs: Long): String = buildString {
            val ok = records.count { it.ok }
            append("轻新签到 · 签到记录\n")
            append("共 ").append(records.size).append(" 条 · 成功 ").append(ok)
                .append(" · 失败 ").append(records.size - ok).append('\n')
            append("导出时间：").append(stamp(exportedAtMs)).append('\n')
            for (record in records) {
                append('\n')
                append(stamp(record.atMs)).append(" · ").append(record.source.label)
                    .append(" · ").append(if (record.ok) "成功" else "失败").append('\n')
                if (record.code.isNotEmpty()) {
                    append("  编号：").append(record.code)
                    if (record.kind.isNotEmpty()) append("（").append(record.kind).append("）")
                    append('\n')
                }
                append("  说明：").append(record.message).append('\n')
                if (record.note.isNotEmpty()) {
                    append("  参数来源：").append(record.note).append('\n')
                }
                // 判定只在失败时给：成功的记录再挂一个 `SIGNED` 是纯噪声，
                // 而失败时它是**唯一**能把「码失效」与「结果未知」分开的东西。
                if (record.outcome.isNotEmpty() && !record.ok) {
                    append("  判定：").append(record.outcome).append('\n')
                }
                if (record.raw.isNotEmpty()) {
                    append("  二维码原文：").append(record.raw).append('\n')
                }
            }
        }

        /** 时刻 → 本地日期文本（同一天归一组）。 */
        fun day(atMs: Long): String = DAY.get()?.format(Date(atMs)) ?: ""

        /** [day] 的人类可读形态（`2026-10-13` → `2026年10月13日`）。 */
        fun dayLabel(atMs: Long): String {
            val parts = day(atMs).split('-')
            if (parts.size != 3) return day(atMs)
            return "${parts[0]}年${parts[1].trimStart('0')}月${parts[2].trimStart('0')}日"
        }

        /** 一段文本，里面的换行 / 反斜杠做转义（记录是「一行一条」的）。 */
        fun escape(value: String): String = value
            .replace("\\", "\\\\")
            .replace("\r", "")
            .replace("\n", "\\n")

        /** [escape] 的逆运算。单次扫描，因此 `\\n`（转义的反斜杠 + n）不会被误读成换行。 */
        fun unescape(value: String): String {
            val out = StringBuilder(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '\\' && i + 1 < value.length) {
                    when (value[i + 1]) {
                        'n' -> { out.append('\n'); i += 2; continue }
                        '\\' -> { out.append('\\'); i += 2; continue }
                    }
                }
                out.append(c)
                i++
            }
            return out.toString()
        }

        private const val HEADER_TAIL = " ==="
        private const val KEY_CODE = "code"
        private const val KEY_KIND = "kind"
        private const val KEY_MESSAGE = "message"
        private const val KEY_RAW = "raw"
        private const val KEY_OUTCOME = "outcome"
        private const val KEY_NOTE = "note"

        /** 从 [text] 的**记录边界**起取最后 [maxBytes] 字节。 */
        private fun tailFromBoundary(text: String, maxBytes: Int): String {
            if (text.length <= maxBytes) return text
            val start = text.length - maxBytes
            val boundary = text.lastIndexOf(RECORD_MARK, start)
            return if (boundary >= 0) text.substring(boundary + 1) else text.substring(start)
        }
    }
}
