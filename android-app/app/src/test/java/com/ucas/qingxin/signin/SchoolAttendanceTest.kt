package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.course.AttendanceCatalogCourse
import com.ucas.qingxin.signin.course.SchoolAttendance
import com.ucas.qingxin.signin.course.SchoolSignState
import com.ucas.qingxin.signin.data.SchoolSession
import com.ucas.qingxin.signin.network.ApiException
import com.ucas.qingxin.signin.network.QingxinApiService
import kotlinx.coroutines.test.runTest
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class SchoolAttendanceTest {
    private val session = SchoolSession("student", "synthetic-session", "00010000")
    private val monday = LocalDate.parse("2026-11-09")
    private val dates = monday..monday.plusDays(6)

    @Test
    fun verifiedAttendanceOverridesWeeklyFlagsAndCountsCoTeachersOnce() = runTest {
        val api = api { request ->
            assertEquals(session.sessionId, request.header("sessionId"))
            assertEquals("POST", request.method)
            when (request.url.encodedPath.substringAfterLast('/')) {
                "get_stu_course_sched_week.action" -> {
                    assertEquals("20261109", form(request)["dateStr"])
                    WEEK
                }
                "get_base_school_year.action" -> {
                    assertEquals(mapOf("userId" to "student", "type" to "2"), form(request))
                    TERMS
                }
                "get_myall_course.action" -> {
                    assertEquals("1", request.url.queryParameter("user_type"))
                    assertEquals("fall", form(request)["xq_code"])
                    CATALOG
                }
                "get_my_course_sign_detail.action" -> {
                    assertEquals(mapOf("id" to "student", "courseId" to "official-course"), form(request))
                    ATTENDANCE
                }
                else -> error("Unexpected endpoint")
            }
        }
        val schedule = api.getAttendanceWeek(session, monday)
        assertTrue(schedule.all { it.status == SchoolSignState.Unknown })
        assertEquals(setOf(LocalDate.parse("2026-11-11")), SchoolAttendance.catalogDates(schedule))
        val term = api.getAttendanceTerms(session).single()
        val catalog = api.getAttendanceCatalog(session, term)
        val linked = SchoolAttendance.linkCoTeachers(schedule, listOf(term), mapOf(term to catalog))
        val report = api.getCourseAttendance(session, "official-course")
        val results = SchoolAttendance.courses(linked, mapOf(report.courseId to report), dates,
            Instant.parse("2026-11-13T05:00:00Z"))
        val course = results.single { it.id == "id:official-course" }
        assertEquals(4, course.schoolSignedTotal)
        assertEquals(1, course.signedCount)
        assertEquals(setOf("赵老师", "钱老师"), course.teachers.toSet())
        assertEquals(listOf("102", "104", "105"), course.lessons.map { it.course.id })
        assertEquals(listOf(SchoolSignState.Signed, SchoolSignState.Unknown, SchoolSignState.Upcoming),
            course.lessons.map { it.status })
        assertEquals(1, results.single { it.id == "id:lab" }.pendingCount)

        val ambiguous = catalog + AttendanceCatalogCourse("another-class", "CS601", "系统设计")
        val unchanged = SchoolAttendance.linkCoTeachers(schedule, listOf(term), mapOf(term to ambiguous))
        assertEquals(schedule, unchanged)
    }

    @Test
    fun unrelatedWeekAndForeignCourseAreRejected() = runTest {
        val api = api { request ->
            when (request.url.encodedPath.substringAfterLast('/')) {
                "get_stu_course_sched_week.action" -> WEEK
                else -> ATTENDANCE
            }
        }
        val otherWeek = runCatching { api.getAttendanceWeek(session, monday.minusWeeks(1)) }.exceptionOrNull()
        val otherCourse = runCatching { api.getCourseAttendance(session, "other") }.exceptionOrNull()
        assertEquals("ATTENDANCE_DATA", (otherWeek as ApiException).code)
        assertEquals("ATTENDANCE_DATA", (otherCourse as ApiException).code)
    }

    private fun form(request: Request): Map<String, String> {
        val form = request.body as FormBody
        return (0 until form.size).associate { form.name(it) to form.value(it) }
    }

    private fun api(fixture: (Request) -> String): QingxinApiService = QingxinApiService(
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(fixture(request).toResponseBody("application/json".toMediaType())).build()
        }.build(),
    )

    private companion object {
        const val WEEK = """{"STATUS":0,"result":[
            {"dateStr":"2026-11-11","schedData":[
                {"id":"101","courseId":"official-course","courseNum":"CS601","courseName":"系统设计","teacherName":"赵老师","classroomName":"B301","classBeginTime":"08:00","classEndTime":"09:40","signStatus":0},
                {"id":"102","courseId":"teacher-b","courseNum":"CS601","courseName":"系统设计","teacherName":"钱老师","classroomName":"B301","classBeginTime":"0800","classEndTime":"09:40:00","signStatus":0},
                {"id":"103","courseId":"lab","courseNum":"CS601","courseName":"系统设计","teacherName":"孙老师","classroomName":"B302","classBeginTime":"08:00","classEndTime":"09:40","signStatus":1}
            ]},
            {"dateStr":"20261112","schedData":[
                {"id":"104","courseId":"official-course","courseNum":"CS601","courseName":"系统设计","teacherName":"赵老师","classroomName":"B301","classBeginTime":"08:00","classEndTime":"09:40","signStatus":0}
            ]},
            {"dateStr":"20261115","schedData":[
                {"id":"105","courseId":"official-course","courseNum":"CS601","courseName":"系统设计","teacherName":"赵老师","classroomName":"B301","classBeginTime":"08:00","classEndTime":"09:40","signStatus":0}
            ]}
        ]}"""
        const val TERMS = """{"STATUS":"0","result":[{"code":"fall","beginDate":"2026-08-31","endDate":"2027-01-31"}]}"""
        const val CATALOG = """{"STATUS":0,"total":1,"result":[{"course_id":"official-course","courseNum":"CS601","course_name":"系统设计","semesterId":"fall"}]}"""
        const val ATTENDANCE = """{"STATUS":0,"mySignNum":"4","result":[{"courseId":"official-course","courseSchedId":"102","teachTime":"2026-11-11","signStatus":1}]}"""
    }
}
