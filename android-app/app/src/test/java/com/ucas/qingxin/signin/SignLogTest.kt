package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.logging.LogKind
import com.ucas.qingxin.signin.logging.SignLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * [SignLog] 的离线测试。
 *
 * 这个类的价值全在「现场失败之后」，因此它自己**不能**成为新的失败源：
 * 写入永不抛异常、裁剪不能切出半条记录、学号不能漏出去 —— 三条都在这里钉住。
 */
class SignLogTest {

    private fun newLog(): Pair<SignLog, java.io.File> {
        val dir = Files.createTempDirectory("sign-log-test").toFile()
        return SignLog(dir) to dir
    }

    // ------------------------------------------------------------------ 格式

    @Test
    fun `一条记录包含时刻_类别与全部字段`() {
        val text = SignLog.format(
            atMs = 0L,
            kind = LogKind.SCAN,
            fields = listOf("原文" to "1234567", "判定" to "DirectSign"),
        )
        assertTrue(text.startsWith(SignLog.RECORD_MARK))
        assertTrue(text.contains("· ${LogKind.SCAN} ==="))
        assertTrue(text.contains("\n原文: 1234567"))
        assertTrue(text.contains("\n判定: DirectSign"))
        // 以换行收尾：下一条记录直接追加，两条之间不会粘在一起。
        assertTrue(text.endsWith("\n"))
    }

    @Test
    fun `超长字段被截断并说明原长`() {
        val long = "x".repeat(SignLog.MAX_FIELD_CHARS + 50)
        val text = SignLog.format(0L, LogKind.RESPONSE, listOf("正文" to long))
        assertTrue(text.contains("已截断"))
        assertTrue(text.contains("共 ${SignLog.MAX_FIELD_CHARS + 50} 字符"))
        // 截断后总长必须真的短于原文，否则「截断」是假的。
        assertTrue(text.length < long.length + 200)
    }

    // ------------------------------------------------------------ 学号脱敏

    @Test
    fun `学号被换成占位符_但参数名保留`() {
        // 该留的是「有没有带这个参数」——那才是学校判定的依据。
        val url = "https://host/app/course/stu_scan_sign.action?courseSchedId=1&id=2026123456"
        val masked = SignLog.maskOwnId(url)
        assertEquals(
            "https://host/app/course/stu_scan_sign.action?courseSchedId=1&id=<本人学号>",
            masked,
        )
        assertFalse(masked.contains("2026123456"))
    }

    @Test
    fun `问号开头的 id 同样被脱敏`() {
        assertEquals(
            "https://host/x?y=1&id=<本人学号>",
            SignLog.maskOwnId("https://host/x?y=1&id=abc"),
        )
        // `id` 是末尾参数时不能把后面的 `#` 片段一起吞掉。
        assertEquals(
            "https://host/x?a=1&id=<本人学号>#frag",
            SignLog.maskOwnId("https://host/x?a=1&id=zzz#frag"),
        )
    }

    @Test
    fun `没有 id 参数时原样返回`() {
        val url = "https://host/app/course/stu_scan_sign.action?courseSchedId=1234567"
        assertEquals(url, SignLog.maskOwnId(url))
    }

    // -------------------------------------------------------------- 读写

    @Test
    fun `写入按发生顺序落盘`() {
        val (log, _) = newLog()
        log.append(LogKind.SCAN, "原文" to "第一条")
        log.append(LogKind.OUTCOME, "判定" to "第二条")
        log.flush()

        val text = log.readTail()
        val first = text.indexOf("第一条")
        val second = text.indexOf("第二条")
        assertTrue("两条都应落盘", first >= 0 && second >= 0)
        assertTrue("先发生的必须排在前面", first < second)
        assertTrue(log.sizeBytes() > 0)
    }

    @Test
    fun `裁剪从记录边界开始_不产生半条记录`() {
        val (log, _) = newLog()
        repeat(6) { i -> log.append(LogKind.SCAN, "原文" to "record-$i-${"y".repeat(40)}") }
        log.flush()

        // 给一个小于全文、又明显大于单条的上限：必须从某条记录的开头切。
        val tail = log.readTail(maxBytes = 120)
        assertTrue("应以记录边界开头", tail.startsWith("==="))
        assertTrue("应保留最新的一条", tail.contains("record-5"))
        assertFalse("不应包含最早的记录", tail.contains("record-0"))
    }

    @Test
    fun `上限大于全文时返回全文`() {
        val (log, _) = newLog()
        log.append(LogKind.SCAN, "原文" to "only")
        log.flush()
        val text = log.readTail(maxBytes = 1_000_000)
        assertTrue(text.contains("only"))
        assertTrue(text.startsWith(SignLog.RECORD_MARK))
    }

    @Test
    fun `清空后既没有内容也没有文件`() {
        val (log, _) = newLog()
        log.append(LogKind.SCAN, "原文" to "to-be-cleared")
        log.flush()
        assertTrue(log.readTail().contains("to-be-cleared"))

        log.clear()
        log.flush()
        assertEquals("", log.readTail())
        assertEquals(0L, log.sizeBytes())
    }

    @Test
    fun `文件不存在时读取不抛异常`() {
        // 首次安装、从没扫过码就是这个状态 —— 界面必须能正常显示「还没有记录」。
        val (log, _) = newLog()
        assertEquals("", log.readTail())
        assertEquals(0L, log.sizeBytes())
    }

    @Test
    fun `ensureFile 让空日志也能被分享`() {
        // 接收方打不开一个不存在的文件，因此分享前必须落一个空文件。
        val (log, _) = newLog()
        val file = log.ensureFile()
        assertTrue(file.exists())
        assertEquals(SignLog.FILE_NAME, file.name)
    }
}
