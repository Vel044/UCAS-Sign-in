package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.ui.ScanFormatPolicy
import com.ucas.qingxin.signin.ui.ScanPerf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫码解码的两条纯逻辑：码制放宽时机与「识别耗时」摘要的文案。
 *
 * 为什么要为一段文案写用例：它是**现场唯一的度量读数**（用户会把这一行连同二维码原文
 * 一起复制出来，见 `docs/lecture-signin-research.md` §10.20）。格式漂了不会有人发现，
 * 但会让下一次「还是慢」的反馈无法与这一次比较 —— 那正是这一行存在的全部意义。
 */
class ScanPerfTest {

    // ------------------------------------------------------- 码制放宽时机

    @Test
    fun `未到阈值不放宽`() {
        assertFalse(ScanFormatPolicy.shouldWiden(0))
        assertFalse(ScanFormatPolicy.shouldWiden(ScanFormatPolicy.MISSES_BEFORE_WIDENING - 1))
    }

    @Test
    fun `到达阈值就放宽`() {
        assertTrue(ScanFormatPolicy.shouldWiden(ScanFormatPolicy.MISSES_BEFORE_WIDENING))
        assertTrue(ScanFormatPolicy.shouldWiden(ScanFormatPolicy.MISSES_BEFORE_WIDENING + 100))
    }

    /** 阈值本身也是契约的一部分：它决定「非二维码班牌」要白等多久。 */
    @Test
    fun `阈值按 20fps 折算在一到两秒之间`() {
        val seconds = ScanFormatPolicy.MISSES_BEFORE_WIDENING / 20.0
        assertTrue("阈值折合约 $seconds 秒，太短会误伤没对准的瞬间", seconds >= 1.0)
        assertTrue("阈值折合约 $seconds 秒，太长会让非二维码班牌白等", seconds <= 2.0)
    }

    // ------------------------------------------------------- 耗时摘要文案

    @Test
    fun `识别很快的会话_摘要逐字可比`() {
        val perf = ScanPerf(
            wallMs = 640,
            frames = 3,
            misses = 2,
            hitFrame = 3,
            minMs = 2,
            avgMs = 5,
            maxMs = 9,
            widened = false,
        )
        assertEquals(
            "取景 0.6 秒后第 3 帧解出 · 共分析 3 帧（2 帧未识别） · 单帧 2~9 毫秒（均 5）",
            perf.human(),
        )
    }

    @Test
    fun `放宽过码制的会话_摘要要写明`() {
        val perf = ScanPerf(
            wallMs = 2_480,
            frames = 41,
            misses = 34,
            hitFrame = 41,
            minMs = 3,
            avgMs = 12,
            maxMs = 210,
            widened = true,
        )
        val text = perf.human()
        assertTrue("秒数要保留一位小数：$text", text.startsWith("取景 2.5 秒后第 41 帧解出"))
        assertTrue("未识别帧数必须写出来：$text", text.contains("共分析 41 帧（34 帧未识别）"))
        // 这一条是「解码慢」与「没对准」的分水岭：放宽过就说明前 30 帧一个码都没认出来。
        assertTrue("放宽过必须写明：$text", text.endsWith(" · 已放宽到全部码制"))
    }

    @Test
    fun `不足一秒也能显示`() {
        val perf = ScanPerf(320, 1, 0, 1, 4, 4, 4, widened = false)
        assertTrue(perf.human().startsWith("取景 0.3 秒后第 1 帧解出"))
        assertTrue(perf.human().contains("共分析 1 帧（0 帧未识别）"))
    }
}
