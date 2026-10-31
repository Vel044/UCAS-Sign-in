package com.ucas.qingxin.signin.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 签到记录的格式化 / 解析契约。
 *
 * ## 为什么这些断言值得写
 * 这份文件是**用户唯一能回看讲座签到的地方**（讲座不在课表里，学校系统也查不到），
 * 而它同时要满足两个互相拉扯的要求：
 * - 写入的每一条都必须能**原样读回来**（少一条、错一条都没有别的补救渠道）；
 * - 学校那句话里可能有换行（我们的解释文案就带换行），
 *   而记录是「一行一条」的格式 —— 转义错了，后面所有记录都会跟着错位。
 *
 * 因此这里盯的是三件事：**往返无损**、**坏记录只跳自己不伤别人**、**导出可读**。
 */
class SignRecordStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sample(
        atMs: Long = 1_760_000_000_000L,
        source: SignSource = SignSource.SCAN,
        code: String = "1234567",
        kind: String = "7 位节次编号",
        ok: Boolean = true,
        message: String = "签到成功",
        raw: String = "https://iclass.ucas.edu.cn/app/course/stu_scan_sign.action?courseSchedId=1234567",
        outcome: String = "",
        note: String = "",
    ) = SignRecord(atMs, source, code, kind, ok, message, raw, outcome, note)

    @Test
    fun `单条记录往返无损`() {
        val record = sample()
        val parsed = SignRecordStore.parseAll(SignRecordStore.format(record))

        assertEquals(1, parsed.size)
        assertEquals(record, parsed[0])
    }

    @Test
    fun `换行与反斜杠经转义后仍能原样读回`() {
        // 这条文案是本应用自己拼出来的（explain 会给「已失效」补一句），确实带换行。
        val message = "签到码已失效，请对准班牌上最新的二维码重扫\n班牌的签到码大约每 5 秒轮换一次。"
        val raw = "C:\\temp\nline2"
        val parsed = SignRecordStore.parseAll(
            SignRecordStore.format(sample(message = message, raw = raw)),
        )

        assertEquals(1, parsed.size)
        assertEquals(message, parsed[0].message)
        assertEquals(raw, parsed[0].raw)
    }

    @Test
    fun `转义不会把转义后的反斜杠误读成换行`() {
        // 输入是「反斜杠 + n」两个字符（不是换行）。转义后是 `\\n`，
        // 逆运算必须还原成两个字符，而不是一个换行 —— 这是最容易被写错的一处。
        val source = "a\\nb"
        val round = SignRecordStore.unescape(SignRecordStore.escape(source))
        assertEquals(source, round)
        assertEquals(4, round.length)
    }

    @Test
    fun `多条记录保持写入顺序`() {
        val a = sample(atMs = 1_000L, code = "1111111")
        val b = sample(atMs = 2_000L, code = "2222222")
        val c = sample(atMs = 3_000L, code = "3333333")

        val parsed = SignRecordStore.parseAll(
            SignRecordStore.format(a) + SignRecordStore.format(b) + SignRecordStore.format(c),
        )

        assertEquals(listOf("1111111", "2222222", "3333333"), parsed.map { it.code })
    }

    @Test
    fun `坏记录只跳过自己不伤别人`() {
        // 中间那条的头被截断（模拟写盘中途断电 / 手工编辑过文件）。
        val good = SignRecordStore.format(sample(code = "1111111"))
        val broken = "\n=== 这不是一个合法的头 ===" + "\ncode\u0001123\n"
        val alsoGood = SignRecordStore.format(sample(code = "3333333"))

        val parsed = SignRecordStore.parseAll(good + broken + alsoGood)

        assertEquals(listOf("1111111", "3333333"), parsed.map { it.code })
    }

    @Test
    fun `失败记录也会保留下来`() {
        val record = sample(ok = false, message = "二维码已失效", code = "7654321")
        val parsed = SignRecordStore.parseAll(SignRecordStore.format(record))

        assertEquals(1, parsed.size)
        assertFalse(parsed[0].ok)
        assertEquals("二维码已失效", parsed[0].message)
    }

    @Test
    fun `五种来源都能往返`() {
        for (source in SignSource.entries) {
            val parsed = SignRecordStore.parseAll(SignRecordStore.format(sample(source = source)))
            assertEquals(source, parsed.single().source)
        }
    }

    @Test
    fun `二维码原文里的学号被脱敏而参数名保留`() {
        // 这条日志可能被整份转发出去，而 `id` 就是本人学号。
        // 要留的是「有没有带这个参数」（学校判定的依据），不是它的值。
        val raw = "https://iclass.ucas.edu.cn/app/course/stu_scan_sign.action?id=2026123456&courseSchedId=1234567"
        val parsed = SignRecordStore.parseAll(SignRecordStore.format(sample(raw = raw))).single()

        assertFalse(parsed.raw.contains("2026123456"))
        assertTrue(parsed.raw.contains("id=<本人学号>"))
        assertTrue(parsed.raw.contains("courseSchedId=1234567"))
    }

    @Test
    fun `导出文本包含总量与每条的关键信息`() {
        val records = listOf(
            sample(atMs = 1_760_000_000_000L, code = "1111111", ok = true),
            sample(
                atMs = 1_760_000_060_000L,
                source = SignSource.MANUAL,
                code = "2222222",
                ok = false,
                message = "签到码已失效",
            ),
        )

        val text = SignRecordStore.exportText(records, exportedAtMs = 1_760_000_120_000L)

        assertTrue(text.contains("共 2 条 · 成功 1 · 失败 1"))
        assertTrue(text.contains("扫码"))
        assertTrue(text.contains("手动录入"))
        assertTrue(text.contains("1111111"))
        assertTrue(text.contains("签到码已失效"))
    }

    @Test
    fun `判定与参数来源往返无损_且只出现在失败条目里`() {
        val failed = sample(
            ok = false,
            message = "签到码已失效，请刷新后重试",
            outcome = "QR_EXPIRED",
            note = "二维码自带的时间戳已过去 3 分 44 秒，已改用本校校时提交",
        )
        val parsed = SignRecordStore.parseAll(SignRecordStore.format(failed))
        assertEquals(1, parsed.size)
        assertEquals("QR_EXPIRED", parsed[0].outcome)
        assertEquals(failed.note, parsed[0].note)

        val text = SignRecordStore.exportText(listOf(failed), exportedAtMs = 0L)
        assertTrue("失败条目必须能看出判定结果", text.contains("判定：QR_EXPIRED"))
        assertTrue(text.contains("参数来源：二维码自带的时间戳已过去 3 分 44 秒"))

        // 成功条目挂一个 `SIGNED` 是纯噪声：导出里不出现「判定」这一行。
        val okText = SignRecordStore.exportText(
            listOf(sample(ok = true, outcome = "SIGNED")),
            exportedAtMs = 0L,
        )
        assertFalse(okText.contains("判定："))
    }

    @Test
    fun `没有新字段的旧记录仍能正常读回`() {
        // 升级前落盘的文件里没有 outcome / note 两行。反过来也成立：
        // 这两个字段为空时 format 必须**不写**它们，否则文件里全是空行。
        val block = SignRecordStore.format(sample())
        assertFalse(block.contains("outcome"))
        assertFalse(block.contains("note"))

        // 手工拼一段 1.3.3 及之前版本写出来的正文（只有四个字段），必须照样读得回来。
        val legacy = "\n=== 1760000000000|SCAN|0 ===\n" +
            "code\u0001" + "79B6E9E491A34BCD9121FDDDCA91F9A0" + "\n" +
            "kind\u000132 位二维码标识\n" +
            "message\u0001签到码已失效，请刷新后重试\n" +
            "raw\u0001app/course/stu_scan_sign.action?timeTableId=79B6E9E491A34BCD9121FDDDCA91F9A0\n"
        val parsed = SignRecordStore.parseAll(legacy)
        assertEquals(1, parsed.size)
        assertEquals("79B6E9E491A34BCD9121FDDDCA91F9A0", parsed[0].code)
        assertEquals("签到码已失效，请刷新后重试", parsed[0].message)
        assertFalse(parsed[0].ok)
        assertEquals("", parsed[0].outcome)
        assertEquals("", parsed[0].note)
    }

    @Test
    fun `日期标签是中文可读形态且不带前导零`() {
        // 2026-10-13 12:00:00 UTC+8 → 用 2026-10-13 的本地零点构造，避免时区带来的抖动。
        val atMs = java.time.LocalDate.of(2026, 10, 13)
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        assertEquals("2026年10月13日", SignRecordStore.dayLabel(atMs))
    }

    @Test
    fun `空文本解析成空列表而不是抛异常`() {
        assertEquals(0, SignRecordStore.parseAll("").size)
        assertEquals(0, SignRecordStore.parseAll("\n\n").size)
    }

    @Test
    fun `落盘后能读回来且新的在前`() {
        val store = SignRecordStore(tmp.newFolder("records"))
        store.append(sample(atMs = 1_000L, code = "1111111"))
        store.append(sample(atMs = 2_000L, code = "2222222"))
        store.flush()

        val latest = store.readLatest()

        assertEquals(2, store.count())
        assertEquals(listOf("2222222", "1111111"), latest.map { it.code })
    }
}
