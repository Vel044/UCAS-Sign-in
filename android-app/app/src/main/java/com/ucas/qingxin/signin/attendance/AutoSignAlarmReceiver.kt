package com.ucas.qingxin.signin.attendance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ucas.qingxin.signin.QingxinApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 自动签到闹钟接收器，处理三个槽（见 [AttendanceScheduler.AlarmSlot]）。
 *
 * ## [ACTION_AUTO_SIGN_REMIND]（提醒槽）
 *
 * 只做一件事：发一条「即将开始签到」的提醒。**不联网、不启服务，
 * 连协程都不需要**，因此这条唤醒的成本接近 0。
 *
 * 它是 1.2.1 新增的：1.2.0 的提醒是守护服务 `delay` 睡到点时顺带发出的，
 * 而省电档没有守护服务 —— 于是省电档**完全收不到提醒**。
 *
 * ## [ACTION_AUTO_SIGN_ALARM]（签到槽）与 [ACTION_AUTO_SIGN_SWEEP]（补签槽）
 *
 * 职责很窄：把进程叫醒，然后把活儿交给「守护服务 + 执行引擎」。
 * 之所以不在这里做重试，是因为 `goAsync()` 只适合几秒级的短任务
 * （`setExactAndAllowWhileIdle` 给的 partial wakelock 也只有 10 秒量级）。
 * 本次唤醒的 18 秒看门狗内，执行引擎自带「先快后慢」的重试
 * （前两拍 3–5 秒，见 [AutoSignWindowRunner.retryDelayMs]），能消化瞬时失败；
 * 更长的间隔交给守护服务与 SWEEP 槽。
 *
 * SWEEP 槽（1.3.7 起）与签到槽走完全相同的处理：它是「随机时刻已过、窗口未关」
 * 期间每 3 分钟一拍的补签闹钟 —— 1.3.6 里这个区间没有任何机制会再回来，
 * 是「一次瞬时失败 = 整窗静默丢失」的直接成因。两槽共用一个执行体，
 * 并发由引擎的执行锁与按课次预算天然串行。
 *
 * 守护服务**只在需要时**才拉起（[AutoSignEngine.shouldRunDaemon]）：
 * 省电档不启，普通档也只在临近 / 处于签到窗口时才启 —— 否则一个 `START_STICKY`
 * 的前台服务会带着常驻通知活一整天，进程整夜不被系统冻结。
 */
class AutoSignAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val appContext = context.applicationContext
        val app = appContext as? QingxinApp ?: return
        if (!app.isReady) {
            // 容器初始化失败时绝不能把这次唤醒直接丢掉：转交给 WorkManager 稍后再试。
            // 入队不依赖应用容器（WorkManager 走自己的初始化），因此这条路总能走通。
            if (AttendanceScheduler.readSettings(appContext).autoSignEnabled) {
                runCatching { AttendanceScheduler.enqueueRecovery(appContext) }
            }
            return
        }
        if (!app.attendanceScheduler.getSettings().autoSignEnabled) return

        when (action) {
            ACTION_AUTO_SIGN_REMIND -> onRemind(app)
            ACTION_AUTO_SIGN_ALARM, ACTION_AUTO_SIGN_SWEEP -> onSignWindow(appContext, app)
        }
    }

    /** 提醒槽：纯本地通知，零网络、零服务。 */
    private fun onRemind(app: QingxinApp) {
        runCatching { AutoSignEngine.remindNow(app) }
    }

    /** 签到槽：拉起（按需的）守护服务，并在本次唤醒的时间片内先尝试一次。 */
    private fun onSignWindow(appContext: Context, app: QingxinApp) {
        val pendingResult = goAsync()
        scope.launch {
            try {
                if (AutoSignEngine.shouldRunDaemon(app)) {
                    // 由守护服务在进程内精确定时并负责窗口内的重试。
                    AttendanceDaemonService.start(appContext)
                }
                withTimeoutOrNull(WATCHDOG_MS) {
                    AutoSignWindowRunner.run(appContext, maxRounds = Int.MAX_VALUE)
                }
            } catch (_: Throwable) {
                // 闹钟回调绝不允许抛出：否则会被系统记为「糟糕的 App」。
            } finally {
                runCatching { AttendanceScheduler.ensureSchedule(appContext) }
                runCatching { pendingResult.finish() }
            }
        }
    }

    companion object {
        const val ACTION_AUTO_SIGN_ALARM = "com.ucas.qingxin.signin.action.AUTO_SIGN_ALARM"

        /** 「上课前 15 分钟」提醒槽的动作。与签到槽分开，才能用不同的 `PendingIntent`。 */
        const val ACTION_AUTO_SIGN_REMIND = "com.ucas.qingxin.signin.action.AUTO_SIGN_REMIND"

        /** 窗口内补签槽（1.3.7 起）的动作。与签到槽同走 [onSignWindow]。 */
        const val ACTION_AUTO_SIGN_SWEEP = "com.ucas.qingxin.signin.action.AUTO_SIGN_SWEEP"

        /** 硬超时：`goAsync()` 不能长时间占用，超时后立刻归还控制权。 */
        private const val WATCHDOG_MS = 18_000L

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
