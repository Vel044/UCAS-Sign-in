package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.attendance.AutoSignWindowRunner
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 窗口内重试的「先快后慢」节奏（1.3.7）。
 *
 * 锁住两件事：
 * 1. **快**：首次失败后的前几拍是 3–5 秒级 —— 用户反馈「实际签到时间比随机时刻
 *    晚一两分钟」，成因就是旧版统一等 60–90 秒才补第一拍，而绝大多数失败
 *    （网络抖动、取码超时）本可在几秒内补成；
 * 2. **慢**：快速拍用完之后回到 60–90 秒 —— 不形成可识别的固定间隔，
 *    也不在窗口中后段空烧请求预算。
 */
class RetryRhythmTest {

    @Test
    fun `首次失败后的重试在五秒内`() {
        // round = 1 表示「已失败一次」：下一次尝试必须紧跟（3–5 秒）。
        repeat(20) {
            val delay = AutoSignWindowRunner.retryDelayMs(1)
            assertTrue("快速拍应 ≥3 秒", delay >= 3_000L)
            assertTrue("快速拍应 <5 秒", delay < 5_000L)
        }
    }

    @Test
    fun `快速拍覆盖前两轮`() {
        repeat(20) {
            val delay = AutoSignWindowRunner.retryDelayMs(AutoSignWindowRunner.FAST_RETRY_ROUNDS)
            assertTrue(delay < 5_000L)
        }
    }

    @Test
    fun `快速拍用完回到慢节奏`() {
        repeat(20) {
            val delay = AutoSignWindowRunner.retryDelayMs(AutoSignWindowRunner.FAST_RETRY_ROUNDS + 1)
            assertTrue("慢拍应 ≥60 秒", delay >= 60_000L)
            assertTrue("慢拍应 <90 秒", delay < 90_000L)
        }
    }
}
