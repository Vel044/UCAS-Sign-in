package com.ucas.qingxin.signin.widget

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 宿主环境变化的统一入口。
 *
 * 小部件最容易「看起来坏了」的场景，都是系统事件后没有重绘：
 * - 重启 / 快速开机 → 桌面重新拉取小部件，但本地缓存时间是旧的；
 * - 覆盖安装（升级）→ 部分 ROM 会保留旧 RemoteViews 且不再调用 onUpdate；
 * - 改时间 / 换时区 / 换语言 → 「当前课」「下一节」判断失效。
 *
 * 这些广播都列在 Android 隐式广播豁免名单里，可以安全地静态注册。
 */
class WidgetHostEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
            WidgetHostCompat.ACTION_QUICKBOOT_POWERON,
            WidgetHostCompat.ACTION_HTC_QUICKBOOT_POWERON,
            "android.intent.action.REBOOT",
            -> {
                runCatching { WidgetRefreshScheduler.sync(context) }
                runCatching { WidgetRefreshScheduler.rescheduleAlarm(context) }
                runCatching { TodayCourseWidgetReceiver.requestUpdate(context) }
            }

            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
                // 用户撤销精确闹钟权限（API 31+）时，系统会**同时取消**该应用全部
                // 已排的精确闹钟并发送本广播。若此刻进程还活着（典型：用户在前台
                // 改系统设置），QingxinApp.onCreate 不会重跑、闹钟链就此断掉 ——
                // 必须立刻按「当前权限」重排：已无精确权限时 scheduleAlarm 会自动
                // 降级为非精确（Doze 下最多推迟约 15 分钟，SWEEP 槽兜底）。
                runCatching {
                    com.ucas.qingxin.signin.attendance.AttendanceScheduler
                        .ensureSchedule(context)
                }
                runCatching { WidgetRefreshScheduler.rescheduleAlarm(context) }
            }
        }
    }
}
