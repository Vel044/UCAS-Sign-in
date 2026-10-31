package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.attendance.AutoSignSelector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 安全网周期任务的 gate。
 *
 * 核心承诺：**gate 只在「确认今日无事」时收摊，绝不在「现在看不到事」时收摊**。
 * 翻页时刻（00:02）本地缓存还是昨天的，此刻若按「无 pending」撤销周期任务，
 * 就再也没有执行体去刷新课表，gate 从此永远看到「无 pending」—— 自杀死锁。
 * 「数据不是今天的 → 一律保留」这条就是为它加的。
 */
class SafetyNetGateTest {

    @Test
    fun `数据是今天且无事可做时收摊`() {
        // 无课日 / 全部签完：安全网没有存在意义，撤销。
        assertFalse(
            AutoSignSelector.needsSafetyPeriodic(
                dataIsToday = true,
                hasPending = false,
                hasWindow = false,
                hasNext = false,
            ),
        )
    }

    @Test
    fun `今日还有待签课时保留`() {
        assertTrue(
            AutoSignSelector.needsSafetyPeriodic(
                dataIsToday = true,
                hasPending = true,
                hasWindow = false,
                hasNext = false,
            ),
        )
    }

    @Test
    fun `今日还有未来课次时保留`() {
        assertTrue(
            AutoSignSelector.needsSafetyPeriodic(
                dataIsToday = true,
                hasPending = false,
                hasWindow = false,
                hasNext = true,
            ),
        )
    }

    @Test
    fun `数据陈旧时一律保留——即使此刻看不到任何事`() {
        // R4 的核心场景：翻页闹钟（00:02）触发时缓存还是昨天的，
        // pending/window/next 全空 —— 但这正是最不能撤的时刻。
        assertTrue(
            AutoSignSelector.needsSafetyPeriodic(
                dataIsToday = false,
                hasPending = false,
                hasWindow = false,
                hasNext = false,
            ),
        )
    }

    @Test
    fun `数据陈旧且有待签课时保留`() {
        assertTrue(
            AutoSignSelector.needsSafetyPeriodic(
                dataIsToday = false,
                hasPending = true,
                hasWindow = true,
                hasNext = true,
            ),
        )
    }
}
