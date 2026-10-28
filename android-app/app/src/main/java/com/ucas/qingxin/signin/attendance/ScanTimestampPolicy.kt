package com.ucas.qingxin.signin.attendance

import kotlin.math.abs

/**
 * 扫码提交时该用哪个 `timestamp`。
 *
 * ## 为什么需要一条策略，而不是「照抄二维码」或「一律用校时」
 *
 * `stu_scan_sign.action` 的 `timestamp` 是**新鲜度证明**，学校拿它跟自己的时钟比：
 *
 * - 学校自己的客户端每 **5 秒**重新生成一次二维码
 *   （[com.ucas.qingxin.signin.network.QingxinApiService.QR_REFRESH_CAP_MS]），
 *   换的就是这个值 —— 它不参与身份，只证明「这一帧是刚出来的」；
 * - 本项目一键签到（同一条 action）发的是**本校校时**（`get_timestamp.do`）
 *   的即时读数，学校同样接受 —— 说明学校收的是「够新的时间」，
 *   而不是「它自己发过的那个值」。
 *
 * 由此，照抄二维码里的值**只在二维码还新鲜时**成立。轻新课堂小程序总是当场读屏，
 * 所以它照抄没有问题；而我们的用户可能面对的是一块**不再刷新**的班牌
 * （实测记录：连续三帧字节完全相同，时间戳已过去 224 秒），
 * 照抄一个旧值必然被学校判「已失效」。
 *
 * ## 两条不变量
 *
 * 1. **二维码新鲜时一个字都不改** —— 与唯一已知可用的客户端逐字一致，
 *    不为一个已经成立的情形引入新变量。
 * 2. **绝不提交未经校准的本机时间** —— 2026-10-28 之前是「绝不提交本机时间」，
 *    校准机制（`ClockCalibrationStore`）落地后放宽半步：本机时间**加上最近一次
 *    校时标定的偏差**后，它就是学校时间的一份推算 —— 在「现场校时不可用」时，
 *    它仍优于「提交一个已知陈旧的学校原值」（必被拒）与「编一个裸本机时间」
 *    （从不可接受）。校准缺失时本条回到原样：退回二维码原值。
 */
object ScanTimestampPolicy {

    /**
     * 二维码时间戳与「现在」相差超过多少，就不再认为它是活的那一帧。
     *
     * 取学校自己的轮换周期（5 秒）：它是「一帧的有效寿命」唯一已知的刻度。
     * 取小了会把正在轮换的活码误判成过期（白改一次参数）；取大了会漏掉
     * 「班牌刷得很慢」这一类 —— 而那正是这条策略要修的。
     */
    const val REPLAY_TOLERANCE_MS = 5_000L

    /**
     * 只需要判断「值不值得为它取一次校时」时的廉价筛查（**只看本机时间**）。
     *
     * 它只用来决定要不要多发一个 `get_timestamp.do`，**不参与最终判定** ——
     * 最终判定一律以校时为准（见 [resolve]）。因此本机时钟不准只会让它
     * 多取一次校时，不会让结论出错。
     */
    fun looksStaleByLocalClock(qrTsMs: Long?, localNowMs: Long): Boolean {
        if (qrTsMs == null) return true
        return abs(localNowMs - qrTsMs) > REPLAY_TOLERANCE_MS
    }

    /**
     * 决定提交哪个时间戳；所有可用取值一个都没有时返回 null（调用方只能如实失败）。
     *
     * 取值优先级：**现场校时（权威）> 校准推算（低置信）> 二维码原值（不动）**。
     *
     * @param qrTsMs 二维码自带的 `timestamp`（缺失 / 不是数字时为 null）。
     * @param schoolNowMs 本校校时读数；null = 不可用。
     * @param estimatedSchoolNowMs 按最近校准偏差推算的学校时间
     *   （本机当前时间 + 偏差，见 `ClockCalibrationStore`）；null = 无校准可用。
     *   只在 `schoolNowMs` 为 null 时才参与判定。
     */
    fun resolve(
        qrTsMs: Long?,
        schoolNowMs: Long?,
        estimatedSchoolNowMs: Long? = null,
    ): Decision? {
        // 没有可照抄的值：请求只能由我们拼完整 ⇒ 必须带一个新鲜度证明。
        // 现场校时不可用时退到校准推算；连校准也没有时返回 null
        // （裸本机时间从不可提交 —— 见不变量 2 的现行版本）。
        if (qrTsMs == null) {
            val schoolNow = schoolNowMs ?: estimatedSchoolNowMs ?: return null
            val calibrated = schoolNowMs == null
            return Decision(
                valueMs = schoolNow,
                fromSchoolClock = !calibrated,
                qrAgeMs = null,
                qrStale = false,
                confidence = if (calibrated) {
                    Decision.Confidence.CALIBRATED_CLOCK
                } else {
                    Decision.Confidence.NO_QR_TIMESTAMP
                },
            )
        }
        // 现场校时不可用：先试校准推算 —— 推算的学校时间足以判「新旧」，
        // 陈旧就提交推算值，新鲜就照抄二维码原值（与权威路径同构）。
        if (schoolNowMs == null) {
            val estimated = estimatedSchoolNowMs ?: return Decision(
                valueMs = qrTsMs,
                fromSchoolClock = false,
                qrAgeMs = null,
                qrStale = false,
                confidence = Decision.Confidence.NO_SCHOOL_CLOCK,
            )
            val ageMs = estimated - qrTsMs
            val fresh = abs(ageMs) <= REPLAY_TOLERANCE_MS
            return Decision(
                valueMs = if (fresh) qrTsMs else estimated,
                fromSchoolClock = !fresh,
                qrAgeMs = ageMs,
                qrStale = !fresh,
                confidence = Decision.Confidence.CALIBRATED_CLOCK,
            )
        }
        val ageMs = schoolNowMs - qrTsMs
        val fresh = abs(ageMs) <= REPLAY_TOLERANCE_MS
        return Decision(
            valueMs = if (fresh) qrTsMs else schoolNowMs,
            fromSchoolClock = !fresh,
            qrAgeMs = ageMs,
            qrStale = !fresh,
            confidence = Decision.Confidence.SCHOOL_CLOCK,
        )
    }

    /**
     * 一次判定结果：提交什么、为什么、以及界面该怎么措辞。
     *
     * @param valueMs 实际提交的 `timestamp` 值。
     * @param fromSchoolClock 该值是否来自本校校时（false = 二维码原值）。
     * @param qrAgeMs 二维码时间戳相对**校时**的年龄（毫秒）：正数 = 已经过去这么久，
     *   负数 = 比学校时间还晚。null = 二维码没带时间戳，或校时不可用 ——
     *   后一种情况**不能**编一个本机算出来的数字充数。
     * @param qrStale 是否因为二维码时间戳陈旧而改用了校时值。
     * @param confidence 判定所依据的时基，界面据此决定话说多满。
     */
    data class Decision(
        val valueMs: Long,
        val fromSchoolClock: Boolean,
        val qrAgeMs: Long?,
        val qrStale: Boolean,
        val confidence: Confidence,
    ) {
        enum class Confidence {
            /** 拿本校校时判定的，结论可信。 */
            SCHOOL_CLOCK,

            /**
             * 现场校时不可用，按「本机时钟 + 最近校准偏差」推算的学校时间判定。
             * 低置信：设备时钟在校准之后被改过的话，推算值会整体偏移 ——
             * 界面的措辞必须如实交代这一点（见 `timestampNoteFor`）。
             */
            CALIBRATED_CLOCK,

            /** 校时不可用，按「不动二维码原值」处理。 */
            NO_SCHOOL_CLOCK,

            /** 二维码没带时间戳，请求本来就由我们拼完整。 */
            NO_QR_TIMESTAMP,
        }

        /** 「3 分 44 秒」这种给人看的说法；算不出来时返回 null。 */
        fun humanQrAge(): String? {
            val ms = qrAgeMs ?: return null
            val total = abs(ms) / 1000
            val text = when {
                total < 60 -> "$total 秒"
                total < 3600 -> "${total / 60} 分 ${total % 60} 秒"
                else -> "${total / 3600} 小时 ${(total % 3600) / 60} 分"
            }
            return if (ms >= 0) text else "$text（比校时还晚）"
        }
    }
}
