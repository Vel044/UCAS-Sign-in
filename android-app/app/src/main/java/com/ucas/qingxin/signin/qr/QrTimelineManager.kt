package com.ucas.qingxin.signin.qr

import com.ucas.qingxin.signin.data.QrSnapshot
import com.ucas.qingxin.signin.network.ApiException
import com.ucas.qingxin.signin.network.QingxinApiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * QR timeline follows original RememberSignIn / iClass backend:
 * 1) POST common/get_timestamp.do?id=0 for server time
 * 2) NTP-style offset using RTT/2
 * 3) Sample TTL = 30_000ms (TIME_SYNC_TTL_MS)
 * 4) QR refresh at min(5_000ms, remaining sync TTL)
 *
 * Server Timeline > Local Assumption. Do not hardcode an independent 5s clock.
 */
class QrTimelineManager(
    private val api: QingxinApiService,
) {
    data class ClockReading(
        val schoolNowMs: Long,
        val ageMs: Long,
        val rttMs: Long,
        val sampledAtLocalMs: Long,
    )

    private val mutex = Mutex()
    private var sampleSchoolMs: Long? = null
    private var sampleLocalMs: Long? = null
    private var sampleRttMs: Long = 0

    private val _snapshot = MutableStateFlow<QrSnapshot?>(null)
    val snapshot: StateFlow<QrSnapshot?> = _snapshot.asStateFlow()

    suspend fun syncClock(force: Boolean = false): ClockReading = mutex.withLock {
        val now = System.currentTimeMillis()
        val existingSchool = sampleSchoolMs
        val existingLocal = sampleLocalMs
        if (!force && existingSchool != null && existingLocal != null) {
            val age = now - existingLocal
            if (age in 0 until QingxinApiService.TIME_SYNC_TTL_MS) {
                return ClockReading(
                    schoolNowMs = existingSchool + age,
                    ageMs = age,
                    rttMs = sampleRttMs,
                    sampledAtLocalMs = existingLocal,
                )
            }
        }
        // 取样最多 3 次并保留 RTT 最低的一次；RTT 低说明链路对称性假设成立，
        // NTP 式 `+ rtt/2` 的修正才可信。2026-10-22 的现场记录（连按 8 次、
        // 前 7 次被学校判「时间戳传入异常」、第 8 次成功）证明坏样本确实存在：
        // 高 RTT / 非对称链路会把校时推到「未来」，学校只认它自己钟附近的一个窗口。
        var bestServerTs = 0L
        var bestRtt = Long.MAX_VALUE
        var bestAt = 0L
        for (attempt in 0 until SAMPLE_ATTEMPTS) {
            val t0 = System.currentTimeMillis()
            val serverTs = api.getServerTimestampMs()
            val t1 = System.currentTimeMillis()
            if (t1 < t0) throw ApiException("TIME_SYNC_EXPIRED", "学校校时已过期，请重试")
            val rtt = t1 - t0
            if (rtt < bestRtt) {
                bestRtt = rtt
                bestServerTs = serverTs
                bestAt = t1
            }
            // RTT 已经够低：再取样只会平添延迟，不会显著提高可信度。
            if (rtt <= GOOD_RTT_MS) break
        }
        if (bestRtt == Long.MAX_VALUE) throw ApiException("TIME_SYNC_EXPIRED", "学校校时已过期，请重试")
        val schoolAtReceive = correctedSchoolMs(bestServerTs, bestRtt)
        sampleSchoolMs = schoolAtReceive
        sampleLocalMs = bestAt
        sampleRttMs = bestRtt
        ClockReading(
            schoolNowMs = schoolAtReceive,
            ageMs = 0,
            rttMs = bestRtt,
            sampledAtLocalMs = bestAt,
        )
    }

    fun currentSchoolTimeOrNull(): Long? {
        val school = sampleSchoolMs ?: return null
        val local = sampleLocalMs ?: return null
        val age = System.currentTimeMillis() - local
        if (age < 0 || age >= QingxinApiService.TIME_SYNC_TTL_MS) return null
        return school + age
    }

    /**
     * Build QR for a course id/uuid using aligned school timestamp.
     * expiresAtLocalMs = nowLocal + min(5000, 30000 - age) — confirmed from original td1.java.
     */
    suspend fun refreshQr(courseIdOrUuid: String): QrSnapshot {
        val reading = syncClock(force = false)
        val localNow = System.currentTimeMillis()
        val age = reading.ageMs
        val ttlRemain = QingxinApiService.TIME_SYNC_TTL_MS - age
        if (ttlRemain <= 0) {
            throw ApiException("TIME_SYNC_EXPIRED", "学校校时已过期，请重试")
        }
        val refreshIn = minOf(QingxinApiService.QR_REFRESH_CAP_MS, ttlRemain)
        val expiresAt = localNow + refreshIn
        val url = api.buildSignQrUrl(courseIdOrUuid, reading.schoolNowMs)
        val remaining = ((expiresAt - System.currentTimeMillis()).coerceAtLeast(0)).toDouble() / 1000.0
        val snap = QrSnapshot(
            url = url,
            schoolTimestampMs = reading.schoolNowMs,
            expiresAtLocalMs = expiresAt,
            validityDurationMs = refreshIn,
            remainingSeconds = remaining,
        )
        _snapshot.value = snap
        return snap
    }

    fun remainingSeconds(nowLocalMs: Long = System.currentTimeMillis()): Double {
        val snap = _snapshot.value ?: return 0.0
        return ((snap.expiresAtLocalMs - nowLocalMs).coerceAtLeast(0)).toDouble() / 1000.0
    }

    fun isExpired(nowLocalMs: Long = System.currentTimeMillis()): Boolean {
        val snap = _snapshot.value ?: return true
        return nowLocalMs >= snap.expiresAtLocalMs
    }

    fun clear() {
        _snapshot.value = null
        sampleSchoolMs = null
        sampleLocalMs = null
    }

    companion object {
        /** 校时取样次数上限：RTT 一直很差时也只补两次，避免把签到拖成校时循环。 */
        const val SAMPLE_ATTEMPTS = 3

        /** RTT 低于它就不再补样：校园网正常时 RTT 通常在几百毫秒以内。 */
        const val GOOD_RTT_MS = 1_200L

        /**
         * `+ rtt/2` 修正量的上限。
         *
         * 非对称链路（请求快、响应慢）会让 `rtt/2` 系统性高估、把校时推到**未来**，
         * 而学校对「未来」的容忍显然比对「过去」严（过去的值有真实流量验证过：
         * 照抄 4 秒前的二维码时间戳能通过）。封顶宁可偏过去 1~2 秒，也不冒
         * 「时间戳传入异常」的险。2026-10-22 现场记录的完整分析见
         * `docs/lecture-signin-research.md` §10.23。
         */
        const val HALF_RTT_CAP_MS = 1_500L

        /**
         * 服务器时间 + NTP 式修正（封顶）：`serverTs + min(rtt/2, [HALF_RTT_CAP_MS])`。
         * 纯函数，供单测钉住封顶行为。
         */
        fun correctedSchoolMs(serverTsMs: Long, rttMs: Long): Long =
            serverTsMs + minOf(rttMs / 2, HALF_RTT_CAP_MS)
    }
}
