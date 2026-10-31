package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.attendance.ScanPayload
import com.ucas.qingxin.signin.attendance.ScanTimestampPolicy
import com.ucas.qingxin.signin.attendance.ScanTimestampPolicy.Decision.Confidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「提交哪个 `timestamp`」的判定测试。
 *
 * 用例的锚点是一次**真机失败**（`docs/sign-records.txt`，2026-10-14 明德讲堂班牌）：
 * 二维码原文里带着 `timestamp=1791973800896`（＝ 18:30:00.896），
 * 而用户是在 18:33:45 扫的 —— 那个值已经过去 **224 秒**，
 * 且连续三帧字节完全相同（班牌没有在重新生成二维码）。
 * 学校回的是「签到码已失效」。
 *
 * 因此本文件的第一组用例是「必须不照抄」（否则就是把那个已知会失败的值再发一遍），
 * 第二组是「必须照抄」（否则就是把一个本来能成的签到改坏）。
 */
class ScanTimestampPolicyTest {

    /** 真机那条二维码自带的值。 */
    private val realQrTs = 1791973800896L

    /** 真机第一次扫码对应的本校校时（已过去 224 秒）。 */
    private val realSchoolNow = 1791973800896L + 224_000L

    private fun resolve(
        qrTs: Long?,
        schoolNow: Long?,
        localNow: Long = schoolNow ?: (qrTs ?: 0L),
    ) = ScanTimestampPolicy.resolve(qrTs, schoolNow)

    /** 校准推算路径的便捷入口：`estimated` = 本机时间 + 校准偏差。 */
    private fun resolveWithEstimate(qrTs: Long?, estimated: Long?) =
        ScanTimestampPolicy.resolve(qrTs, schoolNowMs = null, estimatedSchoolNowMs = estimated)

    // ------------------------------------------------- 真机案例：必须不照抄

    @Test
    fun `真机案例_班牌时间戳已过去 224 秒_改用本校校时`() {
        val d = resolve(realQrTs, realSchoolNow)!!
        assertTrue("已陈旧的班牌时间戳必须被覆盖", d.qrStale)
        assertEquals(realSchoolNow, d.valueMs)
        assertTrue(d.fromSchoolClock)
        assertEquals(224_000L, d.qrAgeMs)
        assertEquals("3 分 44 秒", d.humanQrAge())
    }

    @Test
    fun `真机案例_覆盖后请求里那个旧值一个字符都不该留下`() {
        // 端到端地把「判定」接到「请求」上：这一条才是真正防止回归的断言。
        val payload = ScanPayload.parse(
            "http://124.16.75.106:8081/app/course/stu_scan_sign.action" +
                "?timeTableId=79B6E9E491A34BCD9121FDDDCA91F9A0&timestamp=$realQrTs",
        ) as ScanPayload.DirectSign
        assertEquals(realQrTs, payload.qrTimestampMs)

        val decision = resolve(payload.qrTimestampMs, realSchoolNow)!!
        val query = payload.withTimestampOf(decision.valueMs)

        assertFalse("旧时间戳不该出现在提交的 query 里", query.contains(realQrTs.toString()))
        assertTrue(query.contains("timestamp=$realSchoolNow"))
        // 标识与参数名一律照抄：这条修复只动时间戳，不碰任何别的东西。
        assertTrue(query.contains("timeTableId=79B6E9E491A34BCD9121FDDDCA91F9A0"))
    }

    // ------------------------------------------------- 新鲜时必须一个字都不改

    @Test
    fun `二维码刚生成_原样照抄`() {
        val qrTs = 1_791_973_800_896L
        val d = resolve(qrTs, qrTs + 1_200L)!!
        assertEquals(qrTs, d.valueMs)
        assertFalse(d.fromSchoolClock)
        assertFalse(d.qrStale)
        assertEquals(Confidence.SCHOOL_CLOCK, d.confidence)
    }

    @Test
    fun `恰好等于容差边界_仍算新鲜`() {
        val qrTs = 1_000_000L
        val d = resolve(qrTs, qrTs + ScanTimestampPolicy.REPLAY_TOLERANCE_MS)!!
        assertFalse("等于容差不算陈旧", d.qrStale)
        assertEquals(qrTs, d.valueMs)
    }

    @Test
    fun `刚过容差一毫秒_就改用校时`() {
        val qrTs = 1_000_000L
        val d = resolve(qrTs, qrTs + ScanTimestampPolicy.REPLAY_TOLERANCE_MS + 1L)!!
        assertTrue(d.qrStale)
        assertTrue(d.fromSchoolClock)
    }

    @Test
    fun `二维码时间戳比校时还晚_同样按不新鲜处理`() {
        // 班牌时钟快了也是一种坏：照抄一个未来的值同样可能被判无效。
        val qrTs = 1_000_000L
        val d = resolve(qrTs, 1_000_000L - 9_000L)!!
        assertTrue(d.qrStale)
        assertEquals(1_000_000L - 9_000L, d.valueMs)
        assertEquals("9 秒（比校时还晚）", d.humanQrAge())
    }

    // --------------------------------------------------------- 校时不可用时

    @Test
    fun `校时不可用_绝不改用本机时间_而是退回二维码原值`() {
        val qrTs = 1_000_000L
        val d = resolve(qrTs, schoolNow = null)!!
        assertEquals("宁可提交一个可能过期的学校值，也不能提交本机时间", qrTs, d.valueMs)
        assertFalse(d.fromSchoolClock)
        assertFalse(d.qrStale)
        assertEquals(Confidence.NO_SCHOOL_CLOCK, d.confidence)
        // 年龄算不出来就如实返回 null，不编一个本机算出来的数字。
        assertNull(d.qrAgeMs)
        assertNull(d.humanQrAge())
    }

    // ------------------------------------------------ 二维码根本没带时间戳

    @Test
    fun `二维码没带时间戳_用校时补`() {
        val d = resolve(qrTs = null, schoolNow = 42L)!!
        assertEquals(42L, d.valueMs)
        assertTrue(d.fromSchoolClock)
        assertFalse(d.qrStale)
        assertNull(d.qrAgeMs)
        assertEquals(Confidence.NO_QR_TIMESTAMP, d.confidence)
    }

    @Test
    fun `两个取值都没有_判定为无解而不是编一个`() {
        assertNull(resolve(qrTs = null, schoolNow = null))
    }

    // ------------------------------------------------------------ 廉价筛查

    @Test
    fun `廉价筛查只用来决定要不要取校时`() {
        val now = 1_000_000L
        // 没带时间戳 ⇒ 必须取校时（请求要靠它拼完整）。
        assertTrue(ScanTimestampPolicy.looksStaleByLocalClock(null, now))
        // 刚生成的码 ⇒ 不必取校时，省掉一次现场往返。
        assertFalse(ScanTimestampPolicy.looksStaleByLocalClock(now - 1_000L, now))
        // 已陈旧 ⇒ 值得取一次校时做精确判断。
        assertTrue(ScanTimestampPolicy.looksStaleByLocalClock(now - 224_000L, now))
    }

    @Test
    fun `廉价筛查把本机时钟不准也算成可疑_结论交给校时`() {
        // 本机时钟快了 20 秒时，一张刚生成的码会被判成可疑 —— 这只是多取一次校时，
        // 真正的判定仍由 resolve 用校时给出「新鲜」，因此结论不受影响。
        val realNow = 1_000_000L
        val skewedLocalNow = realNow + 20_000L
        assertTrue(ScanTimestampPolicy.looksStaleByLocalClock(realNow - 500L, skewedLocalNow))
        val d = resolve(qrTs = realNow - 500L, schoolNow = realNow)!!
        assertFalse(d.qrStale)
        assertEquals(realNow - 500L, d.valueMs)
    }

    // ------------------------------------------------------------ 校准推算（CALIBRATED_CLOCK）

    @Test
    fun `现场校时不可用_推算判定码已陈旧_提交推算值`() {
        val qrTs = 1_000_000L
        val estimated = qrTs + 224_000L
        val d = resolveWithEstimate(qrTs, estimated)!!
        assertTrue(d.qrStale)
        assertEquals(estimated, d.valueMs)
        assertTrue(d.fromSchoolClock)
        assertEquals(224_000L, d.qrAgeMs)
        assertEquals(Confidence.CALIBRATED_CLOCK, d.confidence)
    }

    @Test
    fun `现场校时不可用_推算判定码还新鲜_照抄原值`() {
        // 与小程序逐字一致的分支不能因为推算介入而改变：码是活的就一个字不改。
        val qrTs = 1_000_000L
        val d = resolveWithEstimate(qrTs, qrTs + 1_000L)!!
        assertFalse(d.qrStale)
        assertEquals(qrTs, d.valueMs)
        assertEquals(Confidence.CALIBRATED_CLOCK, d.confidence)
    }

    @Test
    fun `裸编号班牌_无现场校时但有校准_用推算值补入`() {
        // 修复前的行为：这条路上 resolve 返回 null → 直接失败发不出请求。
        val estimated = 2_000_000L
        val d = resolveWithEstimate(qrTs = null, estimated = estimated)!!
        assertEquals(estimated, d.valueMs)
        assertEquals(Confidence.CALIBRATED_CLOCK, d.confidence)
    }

    @Test
    fun `有权威校时的时候推算值绝不参与`() {
        val qrTs = 1_000_000L
        val schoolNow = qrTs + 224_000L
        val d = ScanTimestampPolicy.resolve(
            qrTs,
            schoolNowMs = schoolNow,
            estimatedSchoolNowMs = schoolNow + 999_999L,
        )!!
        assertEquals(schoolNow, d.valueMs)
        assertEquals(Confidence.SCHOOL_CLOCK, d.confidence)
    }

    @Test
    fun `校时与校准都不可用时维持原样_照抄二维码原值`() {
        val d = resolveWithEstimate(qrTs = 1_000_000L, estimated = null)!!
        assertEquals(1_000_000L, d.valueMs)
        assertEquals(Confidence.NO_SCHOOL_CLOCK, d.confidence)
    }

    @Test
    fun `裸编号班牌_校时与校准都不可用才失败`() {
        assertNull(resolveWithEstimate(qrTs = null, estimated = null))
    }

    // ------------------------------------------------------------ 措辞

    @Test
    fun `年龄的话术覆盖秒_分_小时`() {
        assertEquals("12 秒", resolve(0L, 12_000L)!!.humanQrAge())
        assertEquals("3 分 44 秒", resolve(0L, 224_000L)!!.humanQrAge())
        assertEquals("1 小时 0 分", resolve(0L, 3_600_000L)!!.humanQrAge())
    }
}
