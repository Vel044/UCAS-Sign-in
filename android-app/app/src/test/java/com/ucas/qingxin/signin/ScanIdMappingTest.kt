package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.attendance.ScanIdMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ScanIdMapping` 的离线断言。
 *
 * 这一层出现的起因是一次真机失败（2026-10-19）：同一场课，扫码用 32 位标识
 * 连续失败两次，7 秒后一键签到用 7 位节次号成功。映射一旦错，代价是**签到别的课**，
 * 因此这里的每条断言都围绕「什么情况下**不**该映射」写。
 */
class ScanIdMappingTest {

    private val uuid = "2025CF660AFA41389D7FA5DB9134CB48"

    @Test
    fun `规范化吃掉大小写与连字符`() {
        assertEquals(
            uuid,
            ScanIdMapping.normalizeUuid("2025cf660afa-4138-9d7f-a5db9134cb48"),
        )
        assertEquals(uuid, ScanIdMapping.normalizeUuid("  $uuid  "))
    }

    @Test
    fun `同一节课的两种写法都算匹配`() {
        assertTrue(ScanIdMapping.matches(uuid, uuid.lowercase()))
        assertTrue(
            ScanIdMapping.matches(
                "2025CF660AFA41389D7FA5DB9134CB48",
                "2025cf66-0afa-4138-9d7f-a5db9134cb48",
            ),
        )
        assertFalse(ScanIdMapping.matches(uuid, "79B6E9E491A34BCD9121FDDDCA91F9A0"))
    }

    @Test
    fun `空标识永远不匹配 —— 空串相等会把别的课签掉`() {
        assertFalse(ScanIdMapping.matches("", ""))
        assertFalse(ScanIdMapping.matches(uuid, ""))
        assertFalse(ScanIdMapping.matches("", uuid))
    }

    @Test
    fun `课表里能对上就直接给出 7 位节次号`() {
        val candidates = listOf(
            "79B6E9E491A34BCD9121FDDDCA91F9A0" to "1234567",
            uuid.lowercase() to "1241918",
        )
        assertEquals("1241918", ScanIdMapping.resolveSchedId(uuid, candidates))
    }

    @Test
    fun `课表里对不上就返回 null，交给原样重放`() {
        val candidates = listOf("79B6E9E491A34BCD9121FDDDCA91F9A0" to "1234567")
        assertNull(ScanIdMapping.resolveSchedId(uuid, candidates))
        assertNull(ScanIdMapping.resolveSchedId(uuid, emptyList()))
    }

    @Test
    fun `uuid 或节次号缺失的候选必须跳过`() {
        // 学校对少数条目不给 uuid。若拿空串互相比较，就会命中一条毫不相干的课。
        val candidates = listOf(
            "" to "1111111",
            uuid to "",
            "   " to "2222222",
        )
        assertNull(ScanIdMapping.resolveSchedId(uuid, candidates))
    }

    @Test
    fun `补入节次号时保留二维码原值`() {
        val query = "timeTableId=$uuid&timestamp=1792405360241"
        assertEquals(
            "courseSchedId=1241918&timeTableId=$uuid&timestamp=1792405360241",
            ScanIdMapping.withCourseSchedId(query, "1241918"),
        )
    }

    @Test
    fun `节次号放在最前 —— 服务端若取第一个 id 参数，选中的是已验证可用的那个`() {
        val out = ScanIdMapping.withCourseSchedId("timeTableId=$uuid&timestamp=1", "1241918")
        assertEquals("courseSchedId", out.substringBefore('='))
    }

    @Test
    fun `已有 courseSchedId 时替换而不是发两个`() {
        val query = "courseSchedId=0000001&timeTableId=$uuid"
        assertEquals(
            "courseSchedId=1241918&timeTableId=$uuid",
            ScanIdMapping.withCourseSchedId(query, "1241918"),
        )
    }

    @Test
    fun `空 query 也能补`() {
        assertEquals("courseSchedId=1241918", ScanIdMapping.withCourseSchedId("", "1241918"))
    }

    @Test
    fun `看不懂的片段原样留着`() {
        val query = "timeTableId=$uuid&weirdFlag&timestamp=1"
        assertEquals(
            "courseSchedId=1241918&timeTableId=$uuid&weirdFlag&timestamp=1",
            ScanIdMapping.withCourseSchedId(query, "1241918"),
        )
    }
}
