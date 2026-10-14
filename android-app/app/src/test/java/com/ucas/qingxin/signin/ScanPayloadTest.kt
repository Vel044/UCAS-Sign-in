package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.attendance.NotSignKind
import com.ucas.qingxin.signin.attendance.ScanPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「二维码原文 → 载荷类别」的单元测试。
 *
 * 用例围绕**现场真实会出现的二维码形态**来写，而不是围绕正则本身：
 * 班牌实现不统一，扫码这条链路上任何一次误判都会直接变成用户面前的
 * 「签到失败」或（更糟的）**往错误路径发出一发请求**。
 * 因此这里刻意把「不是签到码的东西绝不被判成签到码」单独列为一组用例。
 */
class ScanPayloadTest {

    private val hex32 = "aabbccddeeff00112233445566778899"
    private val hex32Upper = "AABBCCDDEEFF00112233445566778899"

    private fun direct(raw: String): ScanPayload.DirectSign =
        ScanPayload.parse(raw) as? ScanPayload.DirectSign
            ?: error("应判为 DirectSign，实际为 ${ScanPayload.parse(raw)}")

    private fun platform(raw: String): ScanPayload.PlatformSign =
        ScanPayload.parse(raw) as? ScanPayload.PlatformSign
            ?: error("应判为 PlatformSign，实际为 ${ScanPayload.parse(raw)}")

    private fun notSign(raw: String): NotSignKind =
        (ScanPayload.parse(raw) as? ScanPayload.NotSign)?.kind
            ?: error("应判为 NotSign，实际为 ${ScanPayload.parse(raw)}")

    // ------------------------------------------------- 旧 iClass 通道（主力）

    @Test
    fun `完整网址的签到码_取 query 并记下域名`() {
        val payload = direct(
            "https://classroom.example.edu/app/course/stu_scan_sign.action" +
                "?courseSchedId=1234567&timestamp=1756000000000",
        )
        assertEquals(ScanPayload.SIGN_PATH, payload.path)
        assertEquals("courseSchedId=1234567&timestamp=1756000000000", payload.query)
        assertEquals("1234567", payload.signCode.courseSchedId)
        assertEquals("1756000000000", payload.qrTimestamp)
        assertEquals("classroom.example.edu", payload.qrHost)
        // 域名**只用于提示**：它不参与任何地址拼装（见 DirectSign.qrHost 的说明）。
        // 注意这里是裸标签，「已识别：」这个前缀由 ViewModel 拼（见 ScanViewModel.onDecoded）。
        assertEquals("7 位节次编号", payload.label)
    }

    @Test
    fun `相对路径的签到码_没有域名`() {
        val payload = direct("app/course/stu_scan_sign.action?courseSchedId=1234567")
        assertNull(payload.qrHost)
        assertEquals("courseSchedId=1234567", payload.query)
    }

    @Test
    fun `二维码自带 checkId 时原样透传`() {
        val payload = direct(
            "app/course/stu_scan_sign.action?timeTableId=$hex32&timestamp=99",
        )
        assertEquals("timeTableId=$hex32&timestamp=99", payload.query)
        // 学校自己发出来的写法不动它：透传比「规范化」更能保证它认。
        assertEquals(payload.query, payload.submitQuery)
        assertEquals(hex32, payload.signCode.timeTableId)
    }

    @Test
    fun `编号裸拼在路径后面时_不把它当成 query 再拼回去`() {
        // 有些班牌直接写成 `…action?1234567`（`?` 后面根本不是参数）。
        val payload = direct("https://classroom.example.edu/app/course/stu_scan_sign.action?1234567")
        assertEquals("1234567", payload.signCode.courseSchedId)
        // 不能留下 `1234567&courseSchedId=1234567` 这种四不像的请求。
        assertEquals("courseSchedId=1234567", payload.submitQuery)
    }

    @Test
    fun `纯 7 位编号的二维码_退化成直接签到`() {
        val payload = direct("1234567")
        assertEquals("", payload.query)
        assertEquals("1234567", payload.signCode.courseSchedId)
        assertNull(payload.qrHost)
        // 没有 timestamp 可沿用，因此需要调用方补一个校时。
        assertTrue(payload.needsTimestamp)
        assertEquals("courseSchedId=1234567", payload.submitQuery)
    }

    @Test
    fun `纯 32 位标识的二维码_补参数时统一成大写`() {
        val payload = direct(hex32)
        assertEquals(hex32, payload.signCode.timeTableId)
        assertEquals("timeTableId=$hex32Upper", payload.submitQuery)
    }

    @Test
    fun `带连字符的 32 位标识也认`() {
        val dashed = "aabbccdd-eeff-0011-2233-445566778899"
        assertEquals(hex32, direct(dashed).signCode.timeTableId)
    }

    // ------------------------------------------------------------------ 校时

    @Test
    fun `二维码带 timestamp 时_不用本机校时覆盖它`() {
        val payload = direct("app/course/stu_scan_sign.action?courseSchedId=1234567&timestamp=1756000000000")
        assertFalse(payload.needsTimestamp)
        // 即使传进来一个完全不同的校时值，也必须保持二维码原值：这条通道
        // 因此**不依赖本机时钟**，与小程序一致。
        assertEquals(payload.submitQuery, payload.withTimestamp(1L))
        assertTrue(payload.withTimestamp(1L).contains("timestamp=1756000000000"))
    }

    @Test
    fun `二维码没带 timestamp 时_原样重放而不代学校补`() {
        // 这是 1.3.1 的实测失败点：班牌给的 query 里没有 timestamp，
        // 而轻新课堂只做 `二维码query + "&id=" + uid`，从不新增时间戳。
        // 多补一个值是**本项目独有的**改动，必须去掉（见 DirectSign.needsTimestamp）。
        val payload = direct("app/course/stu_scan_sign.action?courseSchedId=1234567")
        assertTrue(payload.isReplay)
        assertFalse(payload.needsTimestamp)
        assertEquals("courseSchedId=1234567", payload.submitQuery)
        // 即使调用方硬塞一个校时值，也必须原样不改。
        assertEquals(payload.submitQuery, payload.withTimestamp(42L))
    }

    @Test
    fun `讲座班牌的 32 位标识_同样是原样重放`() {
        // 讲座就是一个课程，班牌给的标识走的是同一条通道；学生是否选课由服务器判，
        // 因此这里**不需要**任何本地课表信息。
        val payload = direct("app/course/stu_scan_sign.action?timeTableId=$hex32")
        assertEquals("32 位二维码标识", payload.label)
        assertFalse(payload.needsTimestamp)
        assertEquals("timeTableId=$hex32", payload.submitQuery)
    }

    @Test
    fun `裸编号才由我们拼请求_这时才补时间戳`() {
        // 没有 query 可重放 ⇒ 请求只能由我们拼完整 ⇒ 必须带新鲜度证明。
        val payload = direct("1234567")
        assertFalse(payload.isReplay)
        assertTrue(payload.needsTimestamp)
        assertEquals("courseSchedId=1234567&timestamp=42", payload.withTimestamp(42L))
    }

    @Test
    fun `片段标识不会把追加的参数吞进去`() {
        // `#` 之后的内容若留着，后面追加的 `&id=` 会落进片段里，请求就丢了身份。
        val payload = direct(
            "https://classroom.example.edu/app/course/stu_scan_sign.action" +
                "?courseSchedId=1234567&timestamp=99#someAnchor",
        )
        assertEquals("courseSchedId=1234567&timestamp=99", payload.query)
    }

    // ------------------------------------------------------------- 7_0 平台通道

    @Test
    fun `7_0 平台签到码_路径去掉 app 前缀`() {
        val payload = platform("https://classroom.example.edu/app/common/stuScanSign.action?id=1&timestamp=2")
        // BASE_URL 已含 `/app/`，因此这里必须去掉，否则会拼成 `…/app/app/common/…`。
        assertEquals("common/stuScanSign.action", payload.path)
        assertEquals("id=1&timestamp=2", payload.query)
        assertEquals("classroom.example.edu", payload.qrHost)
    }

    @Test
    fun `7_0 平台签到码_没有 query 也认`() {
        val payload = platform("https://classroom.example.edu/app/common/stuScanSign")
        assertEquals("common/stuScanSign", payload.path)
        assertEquals("", payload.query)
    }

    // ------------------------------------------------------- 不是签到码的那几类

    @Test
    fun `桌面端扫码登录码_不是签到码`() {
        assertEquals(NotSignKind.IDESKTOP_LOGIN, notSign("https://classroom.example.edu/idesktop?ticket=abc"))
    }

    @Test
    fun `加入课程码_不是签到码`() {
        assertEquals(
            NotSignKind.JOIN_COURSE,
            notSign("https://classroom.example.edu/#/schoolCourse/stuJoinCourse/abc123"),
        )
    }

    @Test
    fun `课堂答题码_不是签到码`() {
        assertEquals(
            NotSignKind.EXAM_ANSWER,
            notSign("https://classroom.example.edu/app/course/saomadati.action?examId=1"),
        )
    }

    @Test
    fun `分类顺序_签到路径优先于 idesktop`() {
        // 文本里同时出现 `idesktop` 与签到路径时，必须仍按签到处理 ——
        // 这正是小程序把签到分支放在最前面的原因。
        val payload = direct("https://classroom.example.edu/idesktop/app/course/stu_scan_sign.action?courseSchedId=1234567")
        assertEquals("1234567", payload.signCode.courseSchedId)
    }

    @Test
    fun `不是签到码的类别_不产出任何请求载荷`() {
        // 这是本层最重要的约束：认不出 / 认成别的用途时，一个请求都不该发出去。
        val raws = listOf(
            "https://classroom.example.edu/idesktop",
            "https://classroom.example.edu/#/schoolCourse/stuJoinCourse/abc",
            "https://classroom.example.edu/app/course/saomadati.action?examId=1",
            "https://example.com/whatever",
            "随便一段文本",
            "",
            "   ",
        )
        for (raw in raws) {
            assertFalse("「$raw」不该产出请求载荷", ScanPayload.parse(raw) is ScanPayload.SignRequest)
        }
    }

    // ---------------------------------------------------------------- 认不出来

    @Test
    fun `签到路径但没有任何编号_仍判为认不出`() {
        // 没有编号就没有可提交的目标：宁可提示用户重扫，也不能发一发必然失败的请求。
        val payload = ScanPayload.parse("app/course/stu_scan_sign.action")
        assertTrue(payload is ScanPayload.Unknown)
    }

    @Test
    fun `签到路径只有无编号的参数_仍判为认不出`() {
        val payload = ScanPayload.parse("app/course/stu_scan_sign.action?timestamp=1756000000000")
        assertTrue(payload is ScanPayload.Unknown)
    }

    @Test
    fun `空白被容忍`() {
        assertEquals("1234567", direct("  \n 1234567 \t ").signCode.courseSchedId)
    }

    @Test
    fun `六位数字不算编号`() {
        assertTrue(ScanPayload.parse("123456") is ScanPayload.Unknown)
    }
}
