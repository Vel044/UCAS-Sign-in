package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.network.QingxinApiService
import com.ucas.qingxin.signin.qr.QrTimelineManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「时间戳传入异常」（2026-10-22 现场记录）的两个客户端防线：判据与校时修正封顶。
 *
 * 现场模式：同一会话几秒内成败交错（连按 8 次、前 7 次被拒、第 8 次成功），
 * 根因是校时样本偶发偏移。两道防线分别是「识别学校的专项拒绝」与
 * 「不让高 RTT 取样把校时推到未来」，各自用纯函数钉死行为。
 */
class TimestampHardeningTest {

    // -------------------------------------------------- 判据：学校原话识别

    @Test
    fun `识别时间戳专项拒绝的原话`() {
        assertTrue(QingxinApiService.isTimestampRejected("时间戳传入异常!"))
        assertTrue(QingxinApiService.isTimestampRejected("时间戳格式错误"))
    }

    @Test
    fun `其它已知拒绝不误判为时间戳问题`() {
        // 这些原话各有各的桶（实测见 docs/lecture-signin-research.md §10.21 / §10.23）。
        assertFalse(QingxinApiService.isTimestampRejected("用户不存在!"))
        assertFalse(QingxinApiService.isTimestampRejected("请使用轻新课堂扫码签到！"))
        assertFalse(QingxinApiService.isTimestampRejected("当前时间不是上课时间！"))
        assertFalse(QingxinApiService.isTimestampRejected("二维码已失效！"))
        assertFalse(QingxinApiService.isTimestampRejected(""))
    }

    // -------------------------------------------------- 校时修正封顶

    @Test
    fun `低 RTT 时照常取半程修正`() {
        val serverTs = 1_792_000_000_000L
        assertEquals(serverTs + 100L, QrTimelineManager.correctedSchoolMs(serverTs, rttMs = 200L))
    }

    @Test
    fun `高 RTT 的修正被封顶`() {
        // 8 秒 RTT：未封顶会 +4000ms（非对称链路下足以把时间推到「未来」、
        // 被学校判「时间戳传入异常」）；封顶后最多 +1500ms，宁可稍偏过去。
        val serverTs = 1_792_000_000_000L
        assertEquals(
            serverTs + QrTimelineManager.HALF_RTT_CAP_MS,
            QrTimelineManager.correctedSchoolMs(serverTs, rttMs = 8_000L),
        )
    }

    @Test
    fun `封顶边界两侧连续`() {
        val serverTs = 1_792_000_000_000L
        // rtt = 2×cap 处恰好用满封顶；两侧差 1ms、结果也差 1ms。
        val atCap = QrTimelineManager.correctedSchoolMs(serverTs, 2 * QrTimelineManager.HALF_RTT_CAP_MS)
        val belowCap = QrTimelineManager.correctedSchoolMs(serverTs, 2 * QrTimelineManager.HALF_RTT_CAP_MS - 2)
        assertEquals(QrTimelineManager.HALF_RTT_CAP_MS, atCap - serverTs)
        assertEquals(QrTimelineManager.HALF_RTT_CAP_MS - 1, belowCap - serverTs)
    }
}
