package com.ucas.qingxin.signin.qr

import android.content.Context
import android.content.SharedPreferences

/**
 * 「学校钟 ↔ 本机钟」偏差的**持久化校准**。
 *
 * ## 它解决什么问题
 * 扫码现场的 timestamp 判定依赖 30 秒 TTL 的校时样本（[QrTimelineManager]）。
 * 样本一过期，两条路都是坏的：现场再取一次校时（多一个网络往返，教室信号差时
 * 能卡好几秒）或原样重放陈旧时间戳（被学校拒「二维码已失效」）。裸编号班牌
 * （码里没有 timestamp）更是在无样本时**连请求都拼不出来**。
 *
 * 而本机钟与学校钟的偏差（offset = 学校时刻 − 本机时刻）在设备时钟稳定时
 * **可以跨小时复用**：任意一次成功校时都能把它标定出来。持久化之后，
 * 「本机当前时间 + offset」就是一份零网络、毫秒级的学校时间推算 ——
 * 这就是扫码时间戳与本机时间校正的依据（推算口径见
 * [ScanTimestampPolicy] 的 `CALIBRATED_CLOCK` 置信级）。
 *
 * ## 信任边界
 * 只保留 [HORIZON_MS] 内的校准：更老的记录在设备时钟被改过（手动调时 /
 * 双时钟 ROM 切换）后偏差已失效，宁可不用也不能给一个看起来很准的错值。
 * 每次成功的 `syncClock` 都会刷新记录，正常使用下这份校准永远是新的。
 */
class ClockCalibrationStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 记录一次校准。`schoolMs` 是接收响应时刻的学校时间，`localMs` 是同瞬间的本机时间。 */
    fun save(schoolMs: Long, localMs: Long) {
        prefs.edit()
            .putLong(K_SCHOOL_MS, schoolMs)
            .putLong(K_LOCAL_MS, localMs)
            .apply()
    }

    /**
     * 读取最近一次校准；超过 [HORIZON_MS] 或不存在时返回 null。
     *
     * @return `(schoolMs, localMs)` —— 采样瞬间的学校时间与本机时间。
     */
    fun load(): Pair<Long, Long>? {
        val school = prefs.getLong(K_SCHOOL_MS, Long.MIN_VALUE)
        val local = prefs.getLong(K_LOCAL_MS, Long.MIN_VALUE)
        if (school == Long.MIN_VALUE || local == Long.MIN_VALUE) return null
        if (Math.abs(System.currentTimeMillis() - local) > HORIZON_MS) return null
        return school to local
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "clock_calibration_v1"
        private const val K_SCHOOL_MS = "school_ms"
        private const val K_LOCAL_MS = "local_ms"

        /** 校准的信任时长：设备时钟稳定时偏差可跨天复用，24 小时是保守取值。 */
        const val HORIZON_MS: Long = 24L * 60L * 60L * 1000L
    }
}
