package com.ucas.qingxin.signin.course

import com.ucas.qingxin.signin.auth.AuthRepository
import com.ucas.qingxin.signin.data.SchoolSession
import com.ucas.qingxin.signin.network.ApiException
import com.ucas.qingxin.signin.network.QingxinApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Instant
import java.time.LocalDate

internal data class SchoolAttendanceResult(
    val dates: ClosedRange<LocalDate>,
    val courses: List<CourseAttendanceHistory>,
    val weeksRead: Int,
    val totalWeeks: Int,
    val unavailableWeeks: List<LocalDate>,
    val error: String?,
)

internal class SchoolAttendanceRepository(
    private val api: QingxinApiService,
    private val auth: AuthRepository,
) {
    suspend fun load(
        studentNo: String,
        dates: ClosedRange<LocalDate>,
        onProgress: (String) -> Unit,
    ): SchoolAttendanceResult {
        val now = Instant.now()
        val today = now.atZone(CourseRepository.ZONE).toLocalDate()
        val weeks = SchoolAttendance.weeks(dates)
        val (past, future) = weeks.partition { it <= today }
        val reports = mutableMapOf<String, SchoolAttendanceReport>()
        val unavailableWeeks = mutableListOf<LocalDate>()
        var weeksRead = 0
        var schedule = emptyList<AttendanceLesson>()
        var error: String? = null
        var attemptedRefresh = false

        suspend fun account(): SchoolSession {
            currentCoroutineContext().ensureActive()
            return auth.session.value?.takeIf { it.studentNo == studentNo }
                ?: throw CancellationException("当前账号已切换")
        }

        suspend fun <T> read(fetch: suspend (SchoolSession) -> T): T {
            val session = account()
            val result = try {
                fetch(session)
            } catch (e: ApiException) {
                if (!e.isSessionExpired() || attemptedRefresh) throw e
                attemptedRefresh = true
                val current = account()
                if (current.sessionId == session.sessionId) auth.refreshSession()
                fetch(account())
            }
            account()
            return result
        }

        try {
            val retrieved = mutableListOf<AttendanceLesson>()
            for ((index, monday) in (past.asReversed() + future).withIndex()) {
                onProgress("正在读取第 ${index + 1} / ${weeks.size} 周课表")
                try {
                    retrieved += read { api.getAttendanceWeek(it, monday) }.filter { it.date in dates }
                    schedule = retrieved
                    weeksRead++
                } catch (e: ApiException) {
                    if (e.code != "ATTENDANCE_DATA") throw e
                    unavailableWeeks += monday
                }
            }

            val candidateDates = SchoolAttendance.catalogDates(schedule)
            if (candidateDates.isNotEmpty()) {
                onProgress("正在核对选课目录")
                val terms = optionalCatalogRead { read(api::getAttendanceTerms) }.orEmpty().distinct()
                val catalogs = mutableMapOf<AttendanceTerm, List<AttendanceCatalogCourse>>()
                for (term in terms.filter { term -> candidateDates.any { it in term.dates } }) {
                    optionalCatalogRead { read { api.getAttendanceCatalog(it, term) } }?.let { catalogs[term] = it }
                }
                schedule = SchoolAttendance.linkCoTeachers(schedule, terms, catalogs)
            }

            val courseIds = schedule.map { it.attendanceCourseId }.filter { it.isNotBlank() }.distinct()
            for ((index, courseId) in courseIds.withIndex()) {
                onProgress("正在核对第 ${index + 1} / ${courseIds.size} 门课的学校考勤")
                try {
                    reports[courseId] = read { api.getCourseAttendance(it, courseId) }
                } catch (e: ApiException) {
                    if (e.isSessionExpired() || e.code == "LOGIN_REJECTED") throw e
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            error = e.message ?: "学校签到历史读取失败"
        } catch (_: Exception) {
            error = "学校签到历史查询中断，请检查网络后再试"
        }
        account()
        return SchoolAttendanceResult(dates, SchoolAttendance.courses(schedule, reports, dates, now),
            weeksRead, weeks.size, unavailableWeeks.sorted(), error)
    }

    private suspend fun <T> optionalCatalogRead(fetch: suspend () -> T): T? = try {
        fetch()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        if (e.isSessionExpired() || e.code == "LOGIN_REJECTED") throw e
        null
    }
}

private fun ApiException.isSessionExpired(): Boolean =
    code == "LOGIN_EXPIRED" || code == "HTTP_401" || code == "HTTP_403"
