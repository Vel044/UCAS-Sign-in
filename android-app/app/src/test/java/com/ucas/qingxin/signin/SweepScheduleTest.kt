package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.attendance.AutoSignRandomizer
import com.ucas.qingxin.signin.attendance.AutoSignSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SWEEP 槽（窗口内补签闹钟）的触发点计算。
 *
 * 这组用例锁住三件事：
 * 1. **确定性**：同一拍之内无论重排多少次，得到的都是同一个触发点 ——
 *    这是「不会因反复 `ensureSchedule` 而永不触发」的根基；
 * 2. **推进**：真触发过一次之后 `now` 越过当前槽，下一拍自动 +3 分钟；
 * 3. **封顶**：窗口将尽时返回 null，调用方不得武装（终局交给 post-mortem）。
 */
class SweepScheduleTest {

    private val minute = 60_000L
    private val begin = 1_700_000_000_000L
    private val deadline = AutoSignRandomizer.deadlineMs(begin)

    /** 随机时刻取窗口正中，让各拍的相对位置一目了然。 */
    private val target = begin - 8L * minute
    private val lead = 5_000L

    private fun sweepAt(nowMs: Long) =
        AutoSignSelector.sweepAtFor(target, deadline, nowMs, lead)

    @Test
    fun `第一拍是随机时刻后 3 分钟`() {
        // 随机时刻刚过：k = 1。
        val at = sweepAt(target + 10_000L)!!
        assertEquals(target + AutoSignSelector.SWEEP_SLOT_MS, at)
    }

    @Test
    fun `now 早于随机时刻时 k 钳为 1`() {
        // 正常流程里 SWEEP 在 target 之前不会武装，但防御性钳制必须有据可查。
        assertEquals(target + AutoSignSelector.SWEEP_SLOT_MS, sweepAt(target - minute)!!)
    }

    @Test
    fun `每过一拍推进一个槽`() {
        // 第一拍（target + 3min）触发过、现在处于第二拍内：下一个触发点是 +6min。
        val at = sweepAt(target + 3L * minute + 10_000L)!!
        assertEquals(target + 2 * AutoSignSelector.SWEEP_SLOT_MS, at)
    }

    @Test
    fun `同一时刻重复计算结果一致`() {
        // 幂等的根基：守护循环每 60–90 秒调一次 ensureSchedule，
        // 若两次调用结果不同，触发点就会被反复顺延、永不触发。
        val now = target + 10_000L
        val first = sweepAt(now)
        repeat(5) {
            assertEquals(first, sweepAt(now))
        }
    }

    @Test
    fun `槽顶不超过 deadline 前 1 分钟`() {
        // 贴着 deadline 的尝试必失败（selectDue 要求 now <= deadline）。
        val now = deadline - 2L * minute
        val at = sweepAt(now)!!
        assertTrue("槽顶必须早于 deadline 前 1 分钟", at <= deadline - AutoSignSelector.SWEEP_DEADLINE_GUARD_MS)
    }

    @Test
    fun `窗口将尽时返回 null 不武装`() {
        val now = deadline - AutoSignSelector.SWEEP_DEADLINE_GUARD_MS
        assertNull(sweepAt(now))
    }

    @Test
    fun `槽被钳到 lead 之内时放弃这一拍`() {
        // 触发点若落在 lead 之内（等于立刻触发），宁可放弃 ——
        // 「now + 5 秒」级别的闹钟既无意义，还会在 state-guard 下变成一次空唤醒。
        // 构造：距「最后机会」只剩 2 秒，槽被 deadline 钳到 now + 2s。
        val now = deadline - AutoSignSelector.SWEEP_DEADLINE_GUARD_MS - 2_000L
        val at = AutoSignSelector.sweepAtFor(
            targetMs = target,
            deadlineMs = deadline,
            nowMs = now,
            leadMs = 5_000L,
        )
        assertNull(at)
    }

    @Test
    fun `从任意窗口中段重启都能落到下一拍`() {
        // 进程死亡后 12 分钟才恢复：k = floor(12/3) + 1 = 5 → 触发点是 +15min。
        val now = target + 12L * minute
        assertEquals(
            target + 5 * AutoSignSelector.SWEEP_SLOT_MS,
            sweepAt(now)!!,
        )
    }
}
