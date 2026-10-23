package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.attendance.AutoSignRandomizer
import com.ucas.qingxin.signin.attendance.AutoSignSelector
import com.ucas.qingxin.signin.data.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「该不该现在签」的纯逻辑取舍。
 *
 * 覆盖四种输入：窗口内 / 窗口外 / 已签到 / 时间不可解析。
 */
class AutoSignSelectorTest {

    private val begin = 1_700_000_000_000L
    private val minute = 60L * 1000L

    private fun course(id: String, signed: Boolean = false) = Course(
        id = id,
        uuid = id,
        name = "课程$id",
        teacher = "教师",
        beginTime = "08:00:00",
        endTime = "09:40:00",
        day = "20260916",
        signed = signed,
    )

    private val parseBegin: (Course) -> Long? = { begin }
    private val targetAt: (Long) -> ((Course, Long) -> Long) = { t -> { _, _ -> t } }

    @Test
    fun `随机时刻已到则选出该课`() {
        val c = course("A")
        val now = begin - 5 * minute
        assertEquals(c, AutoSignSelector.selectDue(listOf(c), now, parseBegin, targetAt(begin - 10 * minute)))
    }

    @Test
    fun `随机时刻未到则不签`() {
        val c = course("A")
        val now = begin - 5 * minute
        assertNull(AutoSignSelector.selectDue(listOf(c), now, parseBegin, targetAt(begin - minute)))
    }

    @Test
    fun `已签到的课不参与`() {
        val c = course("A", signed = true)
        val now = begin - 5 * minute
        assertNull(AutoSignSelector.selectDue(listOf(c), now, parseBegin, targetAt(begin - 10 * minute)))
    }

    @Test
    fun `开始时间不可解析的课被跳过`() {
        val c = course("A")
        val now = begin - 5 * minute
        assertNull(
            AutoSignSelector.selectDue(listOf(c), now, { null }, targetAt(begin - 10 * minute)),
        )
    }

    @Test
    fun `超过 deadline 之后不再签`() {
        val c = course("A")
        val now = begin + AutoSignRandomizer.GRACE_MS + minute
        assertNull(AutoSignSelector.selectDue(listOf(c), now, parseBegin, targetAt(begin)))
    }

    @Test
    fun `多节同时可签时取随机时刻最早的一节`() {
        val a = course("A")
        val b = course("B")
        val now = begin - 5 * minute
        val result = AutoSignSelector.selectDue(
            listOf(a, b),
            now,
            parseBegin,
            { c, _ -> if (c.id == "A") begin - 12 * minute else begin - 6 * minute },
        )
        assertEquals("A", result?.id)
    }

    @Test
    fun `容差范围内允许提前签到`() {
        val c = course("A")
        val now = begin - 20 * minute
        val target = now + 2 * minute
        assertNull(AutoSignSelector.selectDue(listOf(c), now, parseBegin, targetAt(target)))
        assertEquals(
            c,
            AutoSignSelector.selectDue(
                listOf(c),
                now,
                parseBegin,
                targetAt(target),
                toleranceMs = 5 * minute,
            ),
        )
    }

    @Test
    fun `selectNext 只挑随机时刻仍在未来的课`() {
        val a = course("A")
        val b = course("B")
        val now = begin - 5 * minute
        val picked = AutoSignSelector.selectNext(
            listOf(a, b),
            now,
            parseBegin,
            { c, _ -> if (c.id == "A") begin - 10 * minute else begin - minute },
        )
        assertEquals("B", picked?.first?.id)
        assertEquals(begin - minute, picked?.second)
    }

    @Test
    fun `selectNext 跳过已签到的课`() {
        val a = course("A")
        val b = course("B", signed = true)
        val now = begin - 5 * minute
        val picked = AutoSignSelector.selectNext(listOf(a, b), now, parseBegin, targetAt(begin - minute))
        assertEquals("A", picked?.first?.id)
    }

    // -------------------------------------------------- selectWindow（窗口内补签）

    @Test
    fun `随机时刻已过且窗口未关时选出该课`() {
        val c = course("A")
        val now = begin + 2 * minute
        assertEquals(c, AutoSignSelector.selectWindow(listOf(c), now, parseBegin, targetAt(begin - 10 * minute)))
    }

    @Test
    fun `随机时刻未到不进补签窗口`() {
        val c = course("A")
        val now = begin - 5 * minute
        assertNull(AutoSignSelector.selectWindow(listOf(c), now, parseBegin, targetAt(begin - minute)))
    }

    @Test
    fun `补签窗口在 deadline 关闭`() {
        val c = course("A")
        val now = begin + AutoSignRandomizer.GRACE_MS
        assertNull(AutoSignSelector.selectWindow(listOf(c), now, parseBegin, targetAt(begin - 10 * minute)))
    }

    @Test
    fun `已签到的课不参与补签`() {
        val c = course("A", signed = true)
        val now = begin + 2 * minute
        assertNull(AutoSignSelector.selectWindow(listOf(c), now, parseBegin, targetAt(begin - 10 * minute)))
    }

    @Test
    fun `开始时间不可解析的课不参与补签`() {
        val c = course("A")
        val now = begin + 2 * minute
        assertNull(AutoSignSelector.selectWindow(listOf(c), now, { null }, targetAt(begin - 10 * minute)))
    }

    @Test
    fun `多节都在窗口内时取随机时刻最早的一节`() {
        val a = course("A")
        val b = course("B")
        val now = begin + 2 * minute
        val result = AutoSignSelector.selectWindow(
            listOf(a, b),
            now,
            parseBegin,
            { c, _ -> if (c.id == "A") begin - 12 * minute else begin - 6 * minute },
        )
        assertEquals("A", result?.id)
    }

    // -------------------------------------------------- nextPostmortemAt（post-mortem 排程）

    @Test
    fun `postmortem 取最早的未来 deadline`() {
        val a = course("A")
        val b = course("B")
        val now = begin - 5 * minute
        val at = AutoSignSelector.nextPostmortemAt(listOf(a, b), now, parseBegin)
        // 两节课 begin 相同 → deadline 相同
        assertEquals(begin + AutoSignRandomizer.GRACE_MS, at)
    }

    @Test
    fun `全部 deadline 已过期时 postmortem 返回 null`() {
        val c = course("A")
        val now = begin + AutoSignRandomizer.GRACE_MS + minute
        assertNull(AutoSignSelector.nextPostmortemAt(listOf(c), now, parseBegin))
    }

    @Test
    fun `postmortem 忽略已签到的课`() {
        val c = course("A", signed = true)
        val now = begin - 5 * minute
        assertNull(AutoSignSelector.nextPostmortemAt(listOf(c), now, parseBegin))
    }

    @Test
    fun `postmortem 跳过时间不可解析的课`() {
        val c = course("A")
        val now = begin - 5 * minute
        assertNull(AutoSignSelector.nextPostmortemAt(listOf(c), now) { null })
    }

    // -------------------------------------------------- remindDue（前置提醒窗口）

    private val remindAt = begin - 15 * minute
    private val tolerance = 60_000L

    @Test
    fun `提醒时刻附近应当提醒`() {
        assertTrue(
            AutoSignSelector.remindDue(remindAt, begin - 5 * minute, remindAt, tolerance),
        )
    }

    @Test
    fun `提前超过容差不提醒`() {
        assertFalse(
            AutoSignSelector.remindDue(remindAt, begin - 5 * minute, remindAt - tolerance - 1, tolerance),
        )
    }

    @Test
    fun `容差之内的提前触发仍提醒`() {
        // 部分 ROM 的非精确闹钟会提前几十秒触发：不能因此把提醒整体丢掉。
        assertTrue(
            AutoSignSelector.remindDue(remindAt, begin - 5 * minute, remindAt - tolerance, tolerance),
        )
    }

    @Test
    fun `到达随机时刻之后不再提醒（执行路径）`() {
        // 执行路径（announceUpcoming）传 0 宽限：签到都开始了再喊「即将签到」是噪音。
        val target = begin - 5 * minute
        assertFalse(AutoSignSelector.remindDue(remindAt, target, target, tolerance))
    }

    @Test
    fun `随机时刻刚过且在宽限内仍补发提醒（迟到兜底）`() {
        // 提醒闹钟被 Doze 推迟、醒来时随机时刻刚过：补发比整体丢失好。
        val target = begin - 5 * minute
        assertTrue(
            AutoSignSelector.remindDue(
                remindAt,
                target,
                target + 90_000L,
                tolerance,
                lateGraceMs = 2 * minute,
            ),
        )
    }

    @Test
    fun `超过迟到宽限不再补发`() {
        val target = begin - 5 * minute
        assertFalse(
            AutoSignSelector.remindDue(
                remindAt,
                target,
                target + 2 * minute,
                tolerance,
                lateGraceMs = 2 * minute,
            ),
        )
    }
}
