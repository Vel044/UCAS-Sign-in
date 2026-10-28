package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.data.Course
import com.ucas.qingxin.signin.ui.courseListKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课程列表 item key 的防撞规则。
 *
 * 学校课表在个别数据形态下会返回「多门 id 为空、同一时段」的课 ——
 * 旧的 `id+beginTime` 键直接重复，LazyColumn 抛异常，整页 / 弹窗打不开。
 */
class CourseListKeysTest {

    private fun course(id: String, begin: String, name: String = "课$id") = Course(
        id = id,
        uuid = id,
        name = name,
        teacher = "",
        beginTime = begin,
        endTime = "",
        day = "20261028",
        signed = false,
    )

    @Test
    fun `常规课表键与旧规则一致`() {
        val courses = listOf(course("1110001", "08:00:00"), course("1110002", "10:00:00"))
        assertEquals(listOf("111000108:00:00", "111000210:00:00"), courseListKeys(courses))
    }

    @Test
    fun `id 缺失且同一时段不撞车`() {
        // 讲座占位节次 / 合并班的真实形态：id 为空、同一时刻两行。
        val courses = listOf(course("", "08:00:00", "甲"), course("", "08:00:00", "乙"))
        val keys = courseListKeys(courses)
        assertEquals(2, keys.toSet().size)
    }

    @Test
    fun `重复键按出现顺序追加序号`() {
        val courses = listOf(
            course("", "08:00:00", "甲"),
            course("", "08:00:00", "乙"),
            course("", "08:00:00", "丙"),
        )
        val keys = courseListKeys(courses)
        assertEquals("08:00:00", keys[0])
        assertEquals("08:00:00#2", keys[1])
        assertEquals("08:00:00#3", keys[2])
    }

    @Test
    fun `同一列表反复生成结果稳定`() {
        val courses = listOf(course("", "08:00:00"), course("", "08:00:00"))
        assertEquals(courseListKeys(courses), courseListKeys(courses))
    }

    @Test
    fun `空列表返回空键集`() {
        assertTrue(courseListKeys(emptyList()).isEmpty())
    }
}
