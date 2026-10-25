package com.ucas.qingxin.signin.attendance

import com.ucas.qingxin.signin.auth.AuthRepository
import com.ucas.qingxin.signin.course.CourseRepository
import com.ucas.qingxin.signin.data.AttendanceUiStatus
import com.ucas.qingxin.signin.data.Course
import com.ucas.qingxin.signin.data.SignOutcome
import com.ucas.qingxin.signin.data.SignResult
import com.ucas.qingxin.signin.history.SignRecord
import com.ucas.qingxin.signin.history.SignRecordStore
import com.ucas.qingxin.signin.history.SignSource
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
    /**
     * 本机签到历史。
     *
     * 记在这里而不是各个界面里，是因为**这里是所有签到路径的唯一收口**：
     * 一键、扫码、手动、自动、小部件全都经过本类。放在调用点上就要写五遍，
     * 而「五处里漏了一处」的表现正是这个功能最不该有的样子 ——
     * 历史里少一条，用户不会发现，只会以为那次没签上。
     */
    private val records: SignRecordStore,
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

    suspend fun signOneClick(course: Course, source: SignSource = SignSource.ONE_CLICK): SignResult =
        signMutex.withLock {
            val code = course.id.ifBlank { course.uuid }
            record(source, code, kindOf(course), "") { performSign(course) }
        }

    /**
     * 扫码签到：目标地址由 [ScanPayload] 与
     * [QingxinApiService.buildScanUrl] 决定。
     *
     * 复用手动签到那把锁是**必需**的，不只是「讲礼貌」：
     * [inFlightCourseId] 会让自动路径让路（见 [trySignOneClick]）。否则用户刚扫完码，
     * 后台巡检又对同一节课发一发，学校会回「已签到」，
     * 界面就会把刚刚的「签到成功」刷成失败 —— 用户看到的是功能坏了。
     *
     * [code] / [kind] / [raw] 只用于**记账**（见 [records]），不参与请求本身：
     * 请求要发成什么样，完全由 [path] 与 [query] 决定。
     */
    suspend fun signScanTarget(
        path: String,
        query: String,
        code: String = "",
        kind: String = "",
        raw: String = "",
        source: SignSource = SignSource.SCAN,
        /**
         * 这次提交的**参数来源说明**（如「timestamp 已改用本校校时」），只用于记账。
         *
         * 存在意义是让下一次现场失败可以离线归因：历史里同时留下「我们发了什么」
         * （[code] + [query]）与「我们为什么这么发」，就不必再让用户去翻调试日志。
         */
        note: String = "",
    ): SignResult = signMutex.withLock {
        record(source, code, kind, raw, note) { performScanSign(path, query) }
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

    /**
     * 记录里那行「说明」：我们的判定 + **学校的原话**。
     *
     * ## 为什么原话必须进记录（2026-10-19 补记）
     * 现场失败时用户唯一会分享出来的东西就是这份记录，而在此之前它只写我们
     * 转述过的那句「请确认上课时间和课程状态」—— 那是**我们自己编的**，
     * 学校的真实措辞（以及 `STATUS` / `ERRCODE`）全被丢掉了。
     * 结果就是：拿到记录也只能猜学校在拒绝什么。
     *
     * 单行输出（用 `；` 连接）：记录文件是「一行一条」的，
     * 换行会被 [SignRecordStore.escape] 转义成字面 `\n`，反而更难读。
     */
    private fun recordedMessage(result: SignResult): String {
        val base = result.message.ifBlank { OUTCOME_FALLBACK[result.outcome] ?: "" }
        // 成功不附：学校的「签到成功」对我们没有增量信息。
        if (result.outcome == SignOutcome.SIGNED) return base
        val voice = result.schoolMessage
        if (voice.isBlank() || base.contains(voice)) return base
        val codes = listOfNotNull(
            result.status.takeIf { it.isNotBlank() }?.let { "STATUS=$it" },
            result.errCode.takeIf { it.isNotBlank() }?.let { "ERRCODE=$it" },
        ).joinToString("，")
        return if (codes.isEmpty()) "$base；学校原话：$voice" else "$base；学校原话：$voice（$codes）"
    }

    /** 是否有签到请求正在飞行中（自动路径据此让路）。 */
    fun isSigningNow(): Boolean = inFlightCourseId != null

    /**
     * 跑一次签到并**在账上留一条**（成功、被拒、网络失败都要留）。
     *
     * ## 为什么异常也要记
     * 「签到失败了」正是用户回头查历史时最想看到的 —— 尤其是那种
     * 「学校提示签到码已失效」的现场失败。只记成功的话，历史会变成一份
     * 只会说好话的账，而那恰恰没有用。
     *
     * ## 为什么不让 [records] 的故障影响签到
     * `append` 本身永不抛出（见 [SignRecordStore]），这里也不做任何可能失败的加工。
     * 记账坏了就是少一条记录，绝不能把一次本来能成的签到变成异常。
     */
    private suspend fun record(
        source: SignSource,
        code: String,
        kind: String,
        raw: String,
        note: String = "",
        block: suspend () -> SignResult,
    ): SignResult {
        try {
            val result = block()
            records.append(
                SignRecord(
                    atMs = System.currentTimeMillis(),
                    source = source,
                    code = code,
                    kind = kind,
                    ok = result.outcome == SignOutcome.SIGNED,
                    message = recordedMessage(result),
                    raw = raw,
                    outcome = result.outcome.name,
                    note = note,
                ),
            )
            return result
        } catch (e: ApiException) {
            records.append(
                SignRecord(
                    atMs = System.currentTimeMillis(),
                    source = source,
                    code = code,
                    kind = kind,
                    ok = false,
                    message = listOfNotNull(e.code, e.message).filter { it.isNotBlank() }
                        .joinToString(": "),
                    raw = raw,
                    outcome = e.code,
                    note = note,
                ),
            )
            throw e
        } catch (e: Exception) {
            // 非 ApiException（如取消）：也留一条，否则历史里会出现「点了一次但什么都没发生」。
            records.append(
                SignRecord(
                    atMs = System.currentTimeMillis(),
                    source = source,
                    code = code,
                    kind = kind,
                    ok = false,
                    message = "${e::class.java.simpleName}: ${e.message ?: ""}",
                    raw = raw,
                    outcome = e::class.java.simpleName,
                    note = note,
                ),
            )
            throw e
        }
    }

    /** 标识形态说明：与 [SignCode.label] 同一套口径，界面直接展示。 */
    private fun kindOf(course: Course): String = when {
        course.id.isNotBlank() -> "7 位节次编号"
        course.uuid.isNotBlank() -> "32 位二维码标识"
        else -> ""
    }

    /**
     * 自动签到专用入口：**绝不阻塞、绝不打断手动签到**。
     *
     * 抢不到锁说明用户此刻正在手动签到 —— 自动路径主动让路并返回 null
     * （调用方不发通知，用户看到的是自己手动签到的结果）。
     * 这样用户永远不会因为后台任务而看到 `SIGN_IN_PROGRESS`。
     */
    suspend fun trySignOneClick(
        course: Course,
        waitMs: Long = 3_000L,
        source: SignSource = SignSource.AUTO,
    ): SignResult? {
        if (!acquireOrGiveUp(waitMs)) return null
        return try {
            record(source, course.id.ifBlank { course.uuid }, kindOf(course), "") { performSign(course) }
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
            val first = try {
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
            // 学校判「时间戳传入异常」：两段式自愈（2026-10-24 扩展）。
            //
            // 第一段（§10.23）：多半是校时样本偏了（高 RTT / 非对称链路把校时推到未来，
            // 或 get_timestamp 节点时钟有偏）→ **强制丢弃样本重取**并立刻重试一次。
            // 2026-10-22 的现场记录里，用户手速复现过这个自愈（连按 8 次、第 8 次成功）。
            //
            // 第二段（用户授权新增）：重校时后**仍**被判时间戳异常 → 换 `stu_auto_sign`
            // 备选通道 —— 那条通道没有 timestamp 参数，窗口由服务端自己的钟判定，
            // 问题在构造上不存在。仅 7 位节次编号可用（该通道只有这一个标识参数），
            // 坐标为项目所有者授权的校区固定值（见 QingxinApiService.submitAutoSign）。
            // 任一失败都如实返回最接近的一次结果：它带着学校的原话，最能说明问题。
            if (first.outcome == SignOutcome.TIMESTAMP_REJECTED) {
                val retried = runCatching {
                    val fresh = qrTimeline.syncClock(force = true).schoolNowMs
                    api.submitAttendance(session, courseKey, fresh)
                }.getOrNull()
                when {
                    retried != null && retried.outcome != SignOutcome.TIMESTAMP_REJECTED ->
                        return retried
                    QingxinApiService.isSevenDigitId(courseKey) -> runCatching {
                        api.submitAutoSign(session, courseKey)
                    }.getOrNull()?.let { return it }
                }
                return retried ?: first
            }
            return first
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

        /**
         * 学校没给话时，记进历史里的兜底文案。
         *
         * 不能留空串：历史里一条「判定：成功 / 说明：（空）」的记录跟没有一样，
         * 用户下次看到只会更困惑。
         */
        val OUTCOME_FALLBACK = mapOf(
            SignOutcome.SIGNED to "签到成功",
            SignOutcome.QR_EXPIRED to "签到码已失效",
            SignOutcome.OUTSIDE_SIGN_WINDOW to "不在签到时间内",
            SignOutcome.SIGN_RESULT_UNKNOWN to "签到结果未知",
            SignOutcome.TIMESTAMP_REJECTED to "学校判定时间戳异常（已自动重新校时重试）",
        )
    }
}
