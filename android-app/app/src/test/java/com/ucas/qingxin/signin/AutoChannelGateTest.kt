package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.history.SignRecord
import com.ucas.qingxin.signin.history.SignRecordStore
import com.ucas.qingxin.signin.history.SignSource
import com.ucas.qingxin.signin.network.QingxinApiService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备选通道（`stu_auto_sign`）的门控与毫秒精度日志（2026-10-24，用户授权轮）。
 *
 * 门控规则：该通道只吃 7 位节次编号；32 位标识、混合形态一律不进。
 * 毫秒精度：签到记录的时间格式必须带 `.SSS`，否则同一秒内的连续尝试
 * 无法与提交的 timestamp 对齐（时间轴实验的前提）。
 */
class AutoChannelGateTest {

    @Test
    fun `七位节次编号可用备选通道`() {
        assertTrue(QingxinApiService.isSevenDigitId("1306103"))
        assertTrue(QingxinApiService.isSevenDigitId(" 1245268 "))
    }

    @Test
    fun `三十二位标识不进备选通道`() {
        // 该通道的表单只有 courseSchedId 一个标识参数，32 位标识无参数名可用。
        assertFalse(QingxinApiService.isSevenDigitId("79B6E9E491A34BCD9121FDDDCA91F9A0"))
        assertFalse(QingxinApiService.isSevenDigitId("79b6e9e4-91a3-4bcd-9121-fdddca91f9a0"))
    }

    @Test
    fun `位数不对或非数字不进备选通道`() {
        assertFalse(QingxinApiService.isSevenDigitId("130610"))
        assertFalse(QingxinApiService.isSevenDigitId("13061033"))
        assertFalse(QingxinApiService.isSevenDigitId("130610a"))
        assertFalse(QingxinApiService.isSevenDigitId(""))
    }

    @Test
    fun `签到记录时间带毫秒`() {
        // 18:36:15 内两条记录只有靠毫秒才能区分先后（2026-10-22 现场实录）。
        val atMs = 1_792_659_375_123L
        val stamp = SignRecordStore.stamp(atMs)
        assertTrue("应含毫秒段: $stamp", stamp.endsWith(".123"))
    }

    @Test
    fun `毫秒精度往返不丢失`() {
        val record = SignRecord(
            atMs = 1_792_659_375_987L,
            source = SignSource.ONE_CLICK,
            code = "1306103",
            kind = "7 位节次编号",
            ok = false,
            message = "x",
        )
        val text = SignRecordStore.format(record)
        val parsed = SignRecordStore.parseAll(text).single()
        assertEquals(record.atMs, parsed.atMs)
    }
}
