package com.ucas.qingxin.signin.attendance

import com.ucas.qingxin.signin.auth.AuthRepository
import com.ucas.qingxin.signin.course.CourseRepository
import com.ucas.qingxin.signin.data.AttendanceUiStatus
import com.ucas.qingxin.signin.data.Course
import com.ucas.qingxin.signin.data.SignOutcome
import com.ucas.qingxin.signin.data.SignResult
import com.ucas.qingxin.signin.network.ApiException
import com.ucas.qingxin.signin.network.QingxinApiService
import com.ucas.qingxin.signin.qr.QrTimelineManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AttendanceRepository(
    private val api: QingxinApiService,
    private val auth: AuthRepository,
    private val courses: CourseRepository,
    private val qrTimeline: QrTimelineManager,
) {
    private val signMutex = Mutex()
    @Volatile private var inFlightCourseId: String? = null

    fun uiStatus(course: Course?, schoolNowMs: Long?): AttendanceUiStatus {
        if (course == null) return AttendanceUiStatus.NOT_STARTED
        if (course.signed) return AttendanceUiStatus.SIGNED
        if (inFlightCourseId == course.id) return AttendanceUiStatus.SIGNING
        val now = schoolNowMs ?: System.currentTimeMillis()
        val begin = courses.parseBeginMs(course)
        val end = courses.parseEndMs(course)
        if (begin == null || end == null) return AttendanceUiStatus.WAITING
        return when {
            now < begin - QingxinApiService.SIGN_WINDOW_LEAD_MS -> AttendanceUiStatus.NOT_STARTED
            now >= end -> AttendanceUiStatus.NOT_STARTED
            courses.isInSignWindow(course, now) -> AttendanceUiStatus.READY
            else -> AttendanceUiStatus.WAITING
        }
    }

    suspend fun signOneClick(course: Course): SignResult = signMutex.withLock {
        performSign(course)
    }

    /**
     * 扫码签到：目标地址由 [ScanPayload] 与
     * [QingxinApiService.buildScanUrl] 决定。
     *
     * 复用手动签到那把锁是**必需**的，不只是「讲礼貌」：
     * [inFlightCourseId] 会让自动路径让路（见 [trySignOneClick]）。否则用户刚扫完码，
     * 后台巡检又对同一节课发一发，学校会回「已签到」，
     * 界面就会把刚刚的「签到成功」刷成失败 —— 用户看到的是功能坏了。
     */
    suspend fun signScanTarget(path: String, query: String): SignResult = signMutex.withLock {
        performScanSign(path, query)
    }

    private suspend fun performScanSign(path: String, query: String): SignResult {
        if (inFlightCourseId != null) {
            throw ApiException("SIGN_IN_PROGRESS", "签到进行中，请勿重复点击")
        }
        // 扫码目标可能对应任何一节课（甚至课表里还没有的课），拿不到 Course 就也拿不到
        // course.id。用一个**不可能等于任何真实 id** 的常量占位：
        // uiStatus 是按 `inFlightCourseId == course.id` 比的，因此不会有任何课程被显示成
        // 「签到中」；但 isSigningNow() 为真，自动路径会正确让路。
        inFlightCourseId = SCAN_IN_FLIGHT
        try {
            var session = try {
                auth.requireSession()
            } catch (e: ApiException) {
                if (e.code == "LOGIN_EXPIRED") {
                    throw e
                }
                auth.refreshSession()
            }
            return try {
                api.submitScanSign(session, path, query)
            } catch (e: ApiException) {
                if (e.code.startsWith("HTTP_") || e.message?.contains("登录") == true) {
                    session = auth.refreshSession()
                    api.submitScanSign(session, path, query)
                } else {
                    throw e
                }
            }
        } finally {
            inFlightCourseId = null
        }
    }

    /** 是否有签到请求正在飞行中（自动路径据此让路）。 */
    fun isSigningNow(): Boolean = inFlightCourseId != null

    /**
     * 自动签到专用入口：**绝不阻塞、绝不打断手动签到**。
     *
     * 抢不到锁说明用户此刻正在手动签到 —— 自动路径主动让路并返回 null
     * （调用方不发通知，用户看到的是自己手动签到的结果）。
     * 这样用户永远不会因为后台任务而看到 `SIGN_IN_PROGRESS`。
     */
    suspend fun trySignOneClick(course: Course, waitMs: Long = 3_000L): SignResult? {
        if (!acquireOrGiveUp(waitMs)) return null
        return try {
            performSign(course)
        } finally {
            signMutex.unlock()
        }
    }

    private suspend fun acquireOrGiveUp(waitMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + waitMs
        while (true) {
            if (signMutex.tryLock()) return true
            if (System.currentTimeMillis() >= deadline) return false
            delay(500)
        }
    }

    /** 手动与自动两条路径共用的签到实现，保证行为完全一致。 */
    private suspend fun performSign(course: Course): SignResult {
        if (inFlightCourseId != null) {
            throw ApiException("SIGN_IN_PROGRESS", "签到进行中，请勿重复点击")
        }
        if (course.signed) {
            return SignResult(SignOutcome.OUTSIDE_SIGN_WINDOW, "学校提示已签到，请刷新课程状态", "0", "0", "")
        }
        // 标识可以只有 UUID（课表里部分课程没有 7 位节次 ID；手动录入 UUID 亦然），
        // 因此「本门课的唯一键」也要能退到 uuid —— 否则 inFlightCourseId 会落到空串，
        // 而空串在 `!= null` 判定下同样算「占用中」，后续所有课程的签到都会被误拦。
        val courseKey = course.id.ifBlank { course.uuid }
        inFlightCourseId = courseKey
        try {
            var session = try {
                auth.requireSession()
            } catch (e: ApiException) {
                if (e.code == "LOGIN_EXPIRED") {
                    throw e
                }
                auth.refreshSession()
            }
            // Manual one-click: do not hard-block on local window guess; backend is authority.
            // submitAttendance 会按标识形态选中 courseSchedId / timeTableId 参数。
            val qr = qrTimeline.refreshQr(courseKey)
            return try {
                api.submitAttendance(session, courseKey, qr.schoolTimestampMs)
            } catch (e: ApiException) {
                if (e.code.startsWith("HTTP_") || e.message?.contains("登录") == true) {
                    session = auth.refreshSession()
                    val qr2 = qrTimeline.refreshQr(courseKey)
                    api.submitAttendance(session, courseKey, qr2.schoolTimestampMs)
                } else {
                    throw e
                }
            }
        } finally {
            inFlightCourseId = null
        }
    }

    private companion object {
        /**
         * 扫码签到时的「飞行中」占位键。
         *
         * 刻意不是一个看起来像 id 的值：课程 id 是数字串、uuid 是 32 位十六进制，
         * 双下划线开头的东西两样都撞不上，因此 [uiStatus] 绝不会把它算到某节课头上。
         */
        const val SCAN_IN_FLIGHT = "__scan__"
    }
}
