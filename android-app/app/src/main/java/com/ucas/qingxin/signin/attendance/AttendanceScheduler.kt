package com.ucas.qingxin.signin.attendance

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ucas.qingxin.signin.QingxinApp
import com.ucas.qingxin.signin.data.Course
import com.ucas.qingxin.signin.data.KeepAliveState
import com.ucas.qingxin.signin.data.UserSettings
import com.ucas.qingxin.signin.widget.WidgetRefreshScheduler
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * 自动签到的调度中枢。
 *
 * ## 唤醒预算（一等约束）
 *
 * Happy path 仍是**每节课 2 次唤醒**（提醒槽 + 签到槽），无课/已签完的日子只留
 * 1 次「跨天翻页」。1.3.7 起新增的三条兜底全部是「**失败才付费**」：
 * SWEEP 槽与 post-mortem 任务在签成那一刻就会被取消（一次都不触发），
 * 安全网周期任务只在「自动签到开着且今日可能还有待签课程」时存在。
 *
 * ## 两条互斥的调度路径
 *
 * | 条件 | 闹钟 | WorkManager 备份 |
 * | --- | --- | --- |
 * | 已取得电池豁免 **且** 允许精确闹钟 | `setExactAndAllowWhileIdle`（精确） | **不排**（避免重复唤醒） |
 * | 其他情况 | `setAndAllowWhileIdle` | 排一个 `OneTimeWorkRequest`（交由 JobScheduler 与其他任务批量合并，比 wakeup 闹钟省电） |
 *
 * ## 刻意不做的三件事
 *
 * 1. **不再使用 15 分钟 `PeriodicWorkRequest`**：那会在一天内反复唤醒并抓取网络；
 * 2. **秒级重试不排新闹钟**：Doze 下 `setAndAllowWhileIdle` 最快也要约 15 分钟才放行一次，
 *    用它做秒级重试是错的，秒级节奏只跑在进程内（见 [AutoSignWindowRunner]）。
 *    进程死掉之后的**分钟级**补签由 SWEEP 槽接管（3 分钟一拍、确定性触发点，
 *    受尝试预算与窗口双重封顶）—— 这是对 1.3.6「一次瞬时失败 = 整窗静默丢失」的修正；
 * 3. **不做轮询**：每个槽各自只排一个闹钟（见 [AlarmSlot]）。
 *
 * ## 三条闹钟槽
 *
 * | 槽 | 时刻 | 做什么 | 代价 |
 * | --- | --- | --- | --- |
 * | [AlarmSlot.REMIND] | **上课前 15 分钟** | 只发一条「即将开始签到」提醒 | 不联网、不启服务 |
 * | [AlarmSlot.SIGN] | 随机签到时刻 | 拉起守护服务（普通档）并执行签到 | 一次网络 |
 * | [AlarmSlot.SWEEP] | `随机时刻 + k×3min` | 窗口内补签：上一拍没签成就在这一拍再试 | 仅失败路径付费 |
 *
 * SWEEP 必须是独立槽：SIGN 槽在「今日无事」分支被翻页闹钟复用，复用会互相覆盖；
 * 且两节课的窗口可能重叠（`[开课前15分钟, 开课后20分钟]` 覆盖很宽），
 * 「下一节课的 SIGN」与「本节的 SWEEP」可以同时成立。
 *
 * 提醒时刻定在「上课前 15 分钟」而不是「随机签到时刻前 N 分钟」：随机时刻每节课都变，
 * 以它为基准的提醒让用户无法预知；相对上课时刻则一眼可算（见 [AutoSignEngine.remindAtFor]）。
 *
 * ## WorkManager 侧的三条兜底（1.3.7 起）
 *
 * | 任务 | 类型 | 使命 |
 * | --- | --- | --- |
 * | `auto_sign_postmortem` | 一次性，`deadline + 5min` | 兜最底：补终态通知 + 重排后续闹钟。成功即取消 |
 * | `auto_sign_safety` | 周期（随档位 60/120 分钟），**无网络约束** | 闹钟被 ROM / 电池策略掐掉后的自愈通道；gate 进退见 [updateSafetyPeriodic] |
 * | `auto_sign_recovery` | 一次性 | 接收器在容器未就绪时丢弃唤醒前的最后退路 |
 */
class AttendanceScheduler(context: Context) {

    /**
     * 闹钟槽。
     *
     * 每个槽有独立的 `request code`（否则 `PendingIntent` 会互相覆盖）与
     * 独立的状态键（否则 `ensureSchedule` 的「已排过就不重排」会误判）。
     */
    private enum class AlarmSlot(
        val reqCode: Int,
        val action: String,
        val keyAt: String,
        val keyExact: String,
    ) {
        REMIND(
            reqCode = REQ_CODE_REMIND,
            action = AutoSignAlarmReceiver.ACTION_AUTO_SIGN_REMIND,
            keyAt = K_REMIND_AT,
            keyExact = K_REMIND_EXACT,
        ),
        SIGN(
            reqCode = REQ_CODE_ALARM,
            action = AutoSignAlarmReceiver.ACTION_AUTO_SIGN_ALARM,
            keyAt = K_ALARM_AT,
            keyExact = K_ALARM_EXACT,
        ),
        SWEEP(
            reqCode = REQ_CODE_SWEEP,
            action = AutoSignAlarmReceiver.ACTION_AUTO_SIGN_SWEEP,
            keyAt = K_SWEEP_AT,
            keyExact = K_SWEEP_EXACT,
        ),
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val state = appContext.getSharedPreferences("auto_sign_state", Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ 设置

    fun getSettings(): UserSettings = readSettings(appContext)

    fun saveSettings(settings: UserSettings) {
        prefs.edit()
            .putBoolean(KEY_AUTO, settings.autoSignEnabled)
            .putBoolean(KEY_NOTIFY, settings.notifyEnabled)
            .putBoolean(KEY_LOW_POWER, settings.lowPowerMode)
            .apply()
        if (settings.autoSignEnabled) start() else stop()
    }

    // ------------------------------------------------------------------ 生命周期

    /** 开启自动签到：排闹钟 + 按档位与「是否临近签到」决定守护服务去留。 */
    fun start() {
        val settings = getSettings()
        if (!settings.autoSignEnabled) {
            stop()
            return
        }
        AttendanceNotifier.ensureChannels(appContext)
        ensureSchedule()
        syncDaemon()
    }

    /**
     * 按档位与「是否临近签到」决定守护服务的去留。
     *
     * 两个约束共同作用：
     * 1. 省电档永远不启守护服务（[PowerProfile.daemonEnabled]）；
     * 2. 普通档也**只在临近签到窗口时**才启 —— 否则「打开一次 App」就会留下一个
     *    带常驻通知的前台服务（`START_STICKY`），进程整天整夜不被系统冻结，
     *    与前台无关的循环就能一直跑到天亮。这正是 1.2.0 里「权限给得越全、反而越耗电」
     *    的直接原因。
     */
    fun syncDaemon() {
        val app = appContext as? QingxinApp ?: return
        if (!app.isReady) return
        if (AutoSignEngine.shouldRunDaemon(app)) {
            AttendanceDaemonService.start(appContext)
        } else {
            AttendanceDaemonService.stop(appContext)
        }
    }

    /** 关闭自动签到：清空闹钟、任务、服务、内存态随机值与常驻通知。 */
    fun stop() {
        cancelAlarms()
        cancelWork()
        cancelUniqueWorks()
        AttendanceDaemonService.stop(appContext)
        WidgetRefreshScheduler.setDaemonActive(appContext, false)
        AutoSignRandomizer.clearAll()
        AutoSignEngine.resetRuntime()
        runCatching {
            (appContext as? QingxinApp)?.takeIf { it.isReady }?.notifier?.clearDaemonStatus()
        }
    }

    // ------------------------------------------------------------------ 排程

    /**
     * 重新计算并对齐「下一次该唤醒的时刻」。
     *
     * 幂等：随机时刻一旦冻结（见 [AutoSignRandomizer]），重复调用得到同一个目标，
     * 闹钟不会被反复顺延。SWEEP 槽的幂等由 [armSweep] 的 state-guard 保证。
     *
     * @param startDaemon 是否允许本调用拉起 / 停掉守护服务。**worker 路径必须传
     *   false**：普通 worker 没有 FGS 临时豁免，API 31+ 上启动会被拒绝并写下
     *   假的 `markDaemonBlocked(true)`（设置页会误报「后台服务被拦」）。
     *   FGS 启动只保留给闹钟接收器（精确闹钟豁免窗）、前台与开机广播。
     */
    fun ensureSchedule(startDaemon: Boolean = true) {
        val app = appContext as? QingxinApp ?: return
        if (!app.isReady) return
        if (!getSettings().autoSignEnabled) {
            cancelAlarms()
            cancelWork()
            cancelUniqueWorks()
            updateSafetyPeriodic(app, null)
            return
        }

        val plan = runCatching { AutoSignEngine.planToday(app) }.getOrNull()
        val exact = KeepAliveHelper.isBatteryExempt(appContext) &&
            KeepAliveHelper.canScheduleExactAlarms(appContext)
        val now = System.currentTimeMillis()

        val due = plan?.next
        // if / else-if 链（而不是 when）：编译器只在链式 null 检查的 else 里
        // 对 `due` 做智能转换，`when` 分支各自独立判断做不到这一点。
        if (due == null && plan?.window == null) {
            // 今日无事可做（无课 / 全部签完 / 全部出终态）：
            // 只留一个跨天翻页闹钟，空闲日唤醒 1 次。
            cancelAlarms()
            cancelWork()
            scheduleAlarm(AlarmSlot.SIGN, dayRolloverAt(), exact = false)
        } else if (due == null) {
            // 随机时刻已过但窗口未关 —— 1.3.6 在这里「全清 + 只留翻页」，
            // 是「一次瞬时失败 = 整窗静默丢失」的直接成因。现在窗口内补签
            // 由下方统一武装的 SWEEP 接管，post-mortem 负责终局通知。
            cancelAlarm(AlarmSlot.REMIND)
            cancelAlarm(AlarmSlot.SIGN)
            cancelWork()
        } else {
            // 提醒槽：只在「还来得及提前提醒」时才排。已经过了提醒时刻就不再补排，
            // 否则每一次 ensureSchedule（每次闹钟、每次开 App）都会立刻触发一次无用唤醒。
            val remindAt = AutoSignEngine.remindAtFor(due)
            if (remindAt > now + MIN_ALARM_LEAD_MS) {
                scheduleAlarm(AlarmSlot.REMIND, remindAt, exact)
            } else {
                cancelAlarm(AlarmSlot.REMIND)
            }
            scheduleAlarm(AlarmSlot.SIGN, due.targetMs, exact)
            // 两条路径互斥：能精确唤醒时不再排 WorkManager 备份，避免同一次签到被唤醒两遍。
            if (exact) cancelWork() else scheduleWork(due.targetMs)
        }

        // SWEEP 独立于上面的分支：两节课的窗口可能重叠（窗口 = 开课前 15 分钟至
        // 开课后 20 分钟），「下一节课的 SIGN」与「本节的 SWEEP」可以同时成立。
        val inWindow = plan?.window
        if (inWindow != null) {
            armSweep(app, inWindow, now, exact)
        } else {
            cancelAlarm(AlarmSlot.SWEEP)
        }

        armPostmortem(plan)
        updateSafetyPeriodic(app, plan)
        if (startDaemon) syncDaemon()
    }

    /**
     * 武装 SWEEP 槽（窗口内补签闹钟）。
     *
     * 幂等分两层：
     * 1. state 里已有**未来**的 SWEEP → 一律不动。[AutoSignSelector.sweepAtFor] 是
     *    (target, now) 的纯函数，但「进程重启回窗」等场景下它的返回值会被
     *    `leadMs` 钳制到「now + 最小提前量」—— 若每次 `ensureSchedule` 都重算，
     *    就会重蹈 [scheduleAlarm] 注释里警告过的「每次顺延、永不触发」覆辙。
     *    state-guard 才是真正的幂等保证，公式只负责「每拍推进一个槽」；
     * 2. 没有未来 SWEEP 时才排下一拍；公式返回 null（窗口将尽）则撤销，
     *    终局交给 post-mortem。
     *
     * SWEEP 不指向具体课程：它触发后只是跑一遍引擎，由 [AutoSignEngine] 挑
     * 「此刻该签的课」。因此为上一节武装、尚未触发的 SWEEP 留着无害。
     */
    private fun armSweep(app: QingxinApp, course: Course, now: Long, exact: Boolean) {
        if (state.getLong(AlarmSlot.SWEEP.keyAt, 0L) > now) return
        val begin = runCatching { app.courseRepository.parseBeginMs(course) }.getOrNull() ?: return
        val today = AutoSignRandomizer.todayKey()
        val target = AutoSignRandomizer.targetMsFor(course.id.ifBlank { course.uuid }, begin, now, today)
        val at = AutoSignSelector.sweepAtFor(
            targetMs = target,
            deadlineMs = AutoSignRandomizer.deadlineMs(begin),
            nowMs = now,
            leadMs = MIN_ALARM_LEAD_MS,
        )
        if (at == null) {
            cancelAlarm(AlarmSlot.SWEEP)
            return
        }
        scheduleAlarm(AlarmSlot.SWEEP, at, exact)
    }

    /**
     * 排 / 撤 post-mortem 一次性任务（`最早 deadline + 5min`）。
     *
     * 幂等沿用闹钟的 state-key 模式而不是 WorkManager 的 KEEP：KEEP 在「最早 deadline
     * 前移」时拿到陈旧目标，REPLACE 又会取消正在执行的任务 —— 只有「目标真变了才
     * REPLACE」两头都对。**刻意不加网络约束**：它的另一职责是重排下一节课的闹钟，
     * 而 `planToday` / 闹钟重排走的是离线取数链；加了 CONNECTED 会在离线时把它
     * 无限期推迟 —— 那正是它要兜底的场景。
     */
    private fun armPostmortem(plan: AutoSignEngine.TodayPlan?) {
        if (plan == null) return // 取不到课表数据：不动已有任务。宁可多醒一次，不可丢终态。
        if (!plan.hasPending) {
            cancelPostmortem()
            return
        }
        val deadline = plan.nextDeadlineMs
        // 全部 pending 的 deadline 都已过期 → 立即收尾（补终态通知 + 重排）。
        val fireAt = if (deadline == null) {
            System.currentTimeMillis()
        } else {
            deadline + POSTMORTEM_LAG_MS
        }
        if (state.getLong(K_POSTMORTEM_AT, 0L) == fireAt) return
        runCatching {
            val request = OneTimeWorkRequestBuilder<AutoSignPostmortemWorker>()
                .setInitialDelay(
                    (fireAt - System.currentTimeMillis()).coerceAtLeast(0L),
                    TimeUnit.MILLISECONDS,
                )
                .build()
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                POSTMORTEM_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
            state.edit().putLong(K_POSTMORTEM_AT, fireAt).apply()
        }
    }

    private fun cancelPostmortem() {
        if (state.getLong(K_POSTMORTEM_AT, 0L) == 0L) return // 没排过，省一次 IPC
        runCatching { WorkManager.getInstance(appContext).cancelUniqueWork(POSTMORTEM_WORK_NAME) }
        state.edit().putLong(K_POSTMORTEM_AT, 0L).apply()
    }

    /**
     * 安全网周期任务的进退（gate）。
     *
     * 取消条件必须是「**确认**今日无事」而不是「现在看不到事」：翻页闹钟（00:02）
     * 触发时本地缓存还是昨天的，`cachedToday` 按日期取不到新数据，若此刻按
     * 「无 pending」撤掉周期任务，就再也没有执行体去刷新课表了 —— gate 自杀死锁。
     * 因此**数据不是今天的 → 一律保留**；它的 body（刷新数据 → 引擎 → 重排）
     * 正是翻页的执行者。判断细节见 [AutoSignSelector.needsSafetyPeriodic]。
     */
    private fun updateSafetyPeriodic(app: QingxinApp, plan: AutoSignEngine.TodayPlan?) {
        runCatching {
            val wm = WorkManager.getInstance(appContext)
            val keep = when {
                !getSettings().autoSignEnabled -> false
                // 取不到任何课表数据（内存与缓存皆空）：保留执行体，它的 body 会
                // 刷新数据并重算 gate，而不是让整条链失去最后的执行者。
                plan == null -> true
                else -> AutoSignSelector.needsSafetyPeriodic(
                    dataIsToday = WidgetRefreshScheduler.lastDataDay(appContext) ==
                        AutoSignRandomizer.todayKey(),
                    hasPending = plan.hasPending,
                    hasWindow = plan.window != null,
                    hasNext = plan.next != null,
                )
            }
            if (!keep) {
                wm.cancelUniqueWork(SAFETY_WORK_NAME)
                return
            }
            val minutes = PowerProfiles.forContext(appContext).safetyCheckMinutes
            val request = PeriodicWorkRequestBuilder<AutoSignSafetyWorker>(minutes, TimeUnit.MINUTES)
                .build()
            // UPDATE 而不是 KEEP：切档必须能改写已排好的间隔（与 widget 周期任务同一条理由）。
            wm.enqueueUniquePeriodicWork(SAFETY_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }

    private fun dayRolloverAt(): Long =
        LocalDate.now(ZONE).plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli() + 2 * 60_000L

    /**
     * 排一个槽上的闹钟。
     *
     * 幂等：目标时刻与「精确与否」都没变就直接返回，避免每次调用都把闹钟顺延到
     * `now + X`（那会让它永远不触发）。
     */
    private fun scheduleAlarm(slot: AlarmSlot, target: Long, exact: Boolean) {
        val fireAt = target.coerceAtLeast(System.currentTimeMillis() + MIN_ALARM_LEAD_MS)
        if (state.getLong(slot.keyAt, 0L) == fireAt &&
            state.getBoolean(slot.keyExact, false) == exact
        ) {
            return
        }
        val manager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pending = alarmIntent(slot)
        val exactOk = exact && runCatching {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pending)
        }.isSuccess
        if (exactOk) {
            state.edit().putLong(slot.keyAt, fireAt).putBoolean(slot.keyExact, true).apply()
            return
        }
        // 精确闹钟被系统拒绝（用户撤销权限 / 厂商限制）→ 降级为非精确，绝不静默丢失唤醒。
        runCatching { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pending) }
        state.edit().putLong(slot.keyAt, fireAt).putBoolean(slot.keyExact, false).apply()
    }

    private fun cancelAlarm(slot: AlarmSlot) {
        runCatching {
            val manager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            manager?.cancel(alarmIntent(slot))
        }
        state.edit().putLong(slot.keyAt, 0L).putBoolean(slot.keyExact, false).apply()
    }

    fun cancelAlarms() {
        AlarmSlot.entries.forEach { cancelAlarm(it) }
    }

    /** 已排定的签到槽时刻（0 = 未排）。调试与设置页展示用。 */
    fun alarmAt(): Long = state.getLong(AlarmSlot.SIGN.keyAt, 0L)

    /** 已排定的提醒槽时刻（0 = 未排）。 */
    fun remindAt(): Long = state.getLong(AlarmSlot.REMIND.keyAt, 0L)

    private fun alarmIntent(slot: AlarmSlot): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        slot.reqCode,
        Intent(appContext, AutoSignAlarmReceiver::class.java).setAction(slot.action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun scheduleWork(atMs: Long) {
        runCatching {
            val delayMs = (atMs - System.currentTimeMillis()).coerceAtLeast(0L)
            val request = OneTimeWorkRequestBuilder<AutoSignWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            // KEEP：已在排队/执行中的同一任务不重复入队，避免「任务自己把自己 REPLACE 掉」。
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }

    fun cancelWork() {
        runCatching { WorkManager.getInstance(appContext).cancelUniqueWork(WORK_NAME) }
    }

    /**
     * 取消 1.3.7 起的三条 WorkManager 兜底（post-mortem / 安全网 / 恢复）。
     *
     * 与 [cancelWork]（签到槽备份，按目标时刻反复重排）不同，这三条的进退
     * 全部由 [ensureSchedule] 单点决定 —— 关闭自动签到或退出登录时从这里撤销。
     */
    fun cancelUniqueWorks() {
        cancelPostmortem()
        runCatching { WorkManager.getInstance(appContext).cancelUniqueWork(SAFETY_WORK_NAME) }
        runCatching { WorkManager.getInstance(appContext).cancelUniqueWork(RECOVERY_WORK_NAME) }
    }

    // ------------------------------------------------------------------ 保活状态

    fun markDaemonBlocked(blocked: Boolean) {
        state.edit().putBoolean(K_DAEMON_BLOCKED, blocked).apply()
    }

    fun isDaemonBlocked(): Boolean = state.getBoolean(K_DAEMON_BLOCKED, false)

    /** `BOOT_COMPLETED` 收到过一次即证明自启动已允许（Android 无公开查询 API）。 */
    fun markBootSeen() {
        state.edit().putBoolean(K_BOOT_SEEN, true).apply()
    }

    fun isBootSeen(): Boolean = state.getBoolean(K_BOOT_SEEN, false)

    fun setLockConfirmed(confirmed: Boolean) {
        state.edit().putBoolean(K_LOCK_CONFIRMED, confirmed).apply()
    }

    fun isLockConfirmed(): Boolean = state.getBoolean(K_LOCK_CONFIRMED, false)

    fun readKeepAliveState(): KeepAliveState {
        val settings = getSettings()
        val profile = resolveProfile()
        return KeepAliveState(
            autoSignEnabled = settings.autoSignEnabled,
            lowPowerMode = settings.lowPowerMode,
            batteryExempt = KeepAliveHelper.isBatteryExempt(appContext),
            exactAlarmAllowed = KeepAliveHelper.canScheduleExactAlarms(appContext),
            notificationsAllowed = KeepAliveHelper.notificationsEnabled(appContext),
            // 「后台服务」这一行展示的是**通道是否可用**：普通档可用（按需拉起、
            // 签完即退），省电档按设计不使用。是否「此刻正在跑」在界面上没有意义 ——
            // 现在它绝大多数时间本来就不该在跑。
            daemonRunning = profile.daemonEnabled && !isDaemonBlocked(),
            autoStartConfirmed = isBootSeen(),
            lockConfirmed = isLockConfirmed(),
            daemonBlocked = isDaemonBlocked(),
            estimatedWakeupsToday = estimateWakeupsToday(),
            systemPowerSave = profile.systemPowerSave,
        )
    }

    /** 读取当前生效的档位；应用未就绪时退化为「只读设置 + 系统省电」。 */
    private fun resolveProfile(): ResolvedPowerProfile {
        val app = appContext as? QingxinApp
        return if (app != null && app.isReady) {
            app.powerProfile()
        } else {
            PowerProfiles.resolve(getSettings(), PowerProfiles.isSystemPowerSave(appContext))
        }
    }

    /**
     * 「今日预计唤醒次数」= 每节课 2 次（提醒槽 + 签到槽）+ 1 次跨天翻页。
     *
     * 1.2.0 的估算写的是「每节课 1 次」，但那时提醒是顺带发出的、省电档根本没有提醒；
     * 现在提醒有独立闹钟，所以诚实地算 2 次 —— 让用户在选择档位时看到真实代价，
     * 而不是一个好看的假数字。
     */
    private fun estimateWakeupsToday(): Int {
        val app = appContext as? QingxinApp ?: return 0
        if (!app.isReady) return 0
        val courses = runCatching { app.courseRepository.courses.value?.courses }.getOrNull()
            ?: return 0
        val remaining = courses.count { !it.signed }
        return remaining * WAKEUPS_PER_COURSE + 1
    }

    companion object {
        /** 用户设置所在的 prefs 名。静态读取与小部件宿主冷启动都依赖它。 */
        const val PREFS_NAME = "user_settings"

        private const val KEY_AUTO = "auto_sign"
        private const val KEY_NOTIFY = "notify"
        private const val KEY_LOW_POWER = "low_power_mode"

        private const val K_ALARM_AT = "alarm_at"
        private const val K_ALARM_EXACT = "alarm_exact"
        private const val K_REMIND_AT = "remind_alarm_at"
        private const val K_REMIND_EXACT = "remind_alarm_exact"
        private const val K_SWEEP_AT = "sweep_alarm_at"
        private const val K_SWEEP_EXACT = "sweep_alarm_exact"
        private const val K_POSTMORTEM_AT = "postmortem_at"
        private const val K_DAEMON_BLOCKED = "daemon_blocked"
        private const val K_BOOT_SEEN = "boot_seen"
        private const val K_LOCK_CONFIRMED = "lock_confirmed"

        /** 闹钟最早不能早于「现在 + 这个值」：避免已过时刻的闹钟被立刻触发成一次空唤醒。 */
        private const val MIN_ALARM_LEAD_MS = 5_000L

        /** 每节课的唤醒预算：提醒槽 + 签到槽（SWEEP 与 post-mortem 仅失败路径出现，不计入）。 */
        private const val WAKEUPS_PER_COURSE = 2

        private const val REQ_CODE_ALARM = 9101

        /** 提醒槽的 `request code`：必须与签到槽不同，否则两个 `PendingIntent` 会互相覆盖。 */
        private const val REQ_CODE_REMIND = 9102

        /** SWEEP 槽（窗口内补签）。必须独立：SIGN 槽在「今日无事」分支被翻页闹钟复用。 */
        private const val REQ_CODE_SWEEP = 9103

        const val WORK_NAME = "auto_sign_once"

        /** post-mortem：`deadline + [POSTMORTEM_LAG_MS]` 的一次性收尾任务。 */
        private const val POSTMORTEM_WORK_NAME = "auto_sign_postmortem"

        /** 安全网：随档位周期的自愈任务，闹钟链被掐断后的最后一条通道。 */
        private const val SAFETY_WORK_NAME = "auto_sign_safety"

        /** 恢复：接收器容器未就绪时的一次性退路。 */
        private const val RECOVERY_WORK_NAME = "auto_sign_recovery"

        /** post-mortem 在 deadline 之后留的余量：确保窗口已彻底关闭后再收尾。 */
        private const val POSTMORTEM_LAG_MS = 5L * 60L * 1000L

        /** 恢复任务的延迟：给容器初始化（或其故障）一点自愈时间，又不至于错过窗口太久。 */
        private const val RECOVERY_DELAY_MS = 30L * 1000L

        private val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

        /**
         * 只读用户设置，**不依赖容器就绪、不依赖实例**。
         *
         * 小部件宿主会冷启动本进程，此时 `QingxinApp.isReady` 可能仍是 false；
         * 耗电档位的解析必须在这种情况下也读得到真实设置，否则省电档用户会在
         * 冷启动那一小段时间里拿到「普通档」的刷新间隔。
         */
        fun readSettings(context: Context): UserSettings {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return UserSettings(
                autoSignEnabled = prefs.getBoolean(KEY_AUTO, false),
                notifyEnabled = prefs.getBoolean(KEY_NOTIFY, true),
                lowPowerMode = prefs.getBoolean(KEY_LOW_POWER, false),
            )
        }

        /** 供接收器 / 守护服务等非 Activity 位置调用的静态入口。 */
        fun ensureSchedule(context: Context, startDaemon: Boolean = true) {
            runCatching {
                val app = context.applicationContext as? QingxinApp ?: return
                if (!app.isReady) return
                app.attendanceScheduler.ensureSchedule(startDaemon)
            }
        }

        /**
         * 接收器在容器未就绪时的最后退路：交给 WorkManager 稍后把进程再拉起来重试。
         *
         * 刻意放在 companion 并且**只依赖 WorkManager**：容器初始化失败时
         * `app.attendanceScheduler` 可能根本没构造出来（lateinit），实例方法一条都不能碰。
         * WorkManager 走自己的初始化，这条路总能走通。
         */
        fun enqueueRecovery(context: Context) {
            val appContext = context.applicationContext
            runCatching {
                val request = OneTimeWorkRequestBuilder<AutoSignRecoveryWorker>()
                    .setInitialDelay(RECOVERY_DELAY_MS, TimeUnit.MILLISECONDS)
                    .build()
                WorkManager.getInstance(appContext).enqueueUniqueWork(
                    RECOVERY_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    request,
                )
            }
        }
    }
}

/**
 * 自动签到的 WorkManager 执行体（**一次性**任务，不是周期任务）。
 *
 * 定位是「闹钟被 ROM 掐掉 / Doze 深度休眠时的兜底」，因此只在未取得电池豁免时排程。
 * `AutoSignWindowRunner` 与闹钟、守护服务共用同一个 [AutoSignEngine]，
 * 行为完全一致（含取数闸门与校时复用）。
 */
class AutoSignWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? QingxinApp ?: return Result.success()
        // 容器没就绪就 Retry：签到槽被消费掉却什么都不做，等于白丢一次兜底机会。
        if (!app.isReady) return Result.retry()
        if (!app.attendanceScheduler.getSettings().autoSignEnabled) return Result.success()

        // WorkManager 的协程有约 10 分钟上限，因此这里收敛重试轮数，剩余交给闹钟。
        runCatching { AutoSignWindowRunner.run(applicationContext, maxRounds = 3) }

        // 只重排**闹钟**：WorkManager 的 KEEP 语义会让「任务内部自我重排」静默失效，
        // 所以下一次的一次性任务由闹钟触发时再入队。
        // startDaemon = false：worker 没有前台服务的临时豁免，从这里拉守护服务
        // 在 API 31+ 必被拒绝，还会把「守护被拦」记成假阳性（见 [AttendanceScheduler.ensureSchedule]）。
        runCatching { app.attendanceScheduler.ensureSchedule(startDaemon = false) }
        return Result.success()
    }
}

/**
 * post-mortem：每课次窗口关闭 5 分钟后的一次性收尾。
 *
 * 职责是「兜最底」：如果连 SWEEP 都没能把课签成（或整条闹钟链被 ROM 掐掉），
 * 由它补上终态通知（用户至少知道发生了什么）并重排下一节课的闹钟（链不能断）。
 * 签成的那一刻 [AttendanceScheduler.armPostmortem] 会把它取消，happy path 一次都不触发。
 *
 * 走 [AutoSignEngine.finalizeStale] 而不是 `runOnce`：后者抢不到执行锁时会静默返回，
 * 一次性任务被消费、终态通知却丢了 —— 收尾绝不能被并发挤掉。
 */
class AutoSignPostmortemWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? QingxinApp ?: return Result.success()
        if (!app.isReady) return Result.retry()
        runCatching { AutoSignEngine.finalizeStale(applicationContext, app) }
        return Result.success()
    }
}

/**
 * 安全网：随耗电档位的周期任务（普通 60 分钟 / 省电 120 分钟），**无网络约束**。
 *
 * 它是闹钟链被 ROM / 电池策略整条掐掉后的最后一条自愈通道：JobScheduler 的任务
 * 持久化在系统侧、跨进程死亡与重启存活。代价可控 —— 可延迟、与其他应用的批处理
 * 合并、gate（见 [AttendanceScheduler.updateSafetyPeriodic]）保证无课日为 0。
 *
 * ## 执行顺序刻意是「先刷数据、再签到、最后重排」
 * 它常常是白天唯一醒着的执行体，翻页后的第一次课表刷新就靠它；
 * 先刷数据也让引擎的取数闸门拿到新鲜课表，而不是拿昨天的缓存空转。
 */
class AutoSignSafetyWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? QingxinApp ?: return Result.success()
        if (!app.isReady) return Result.retry()
        if (!app.attendanceScheduler.getSettings().autoSignEnabled) return Result.success()

        val loggedIn = runCatching { app.authRepository.isLoggedIn() }.getOrDefault(false)
        if (loggedIn) {
            // 只在成功时记账（与 [WidgetRefreshWorker] 同一套语义）：
            // 失败要让 gate 保持「数据陈旧 → 保留」，尽快再试。
            val ok = runCatching { app.courseRepository.loadToday() }.isSuccess
            if (ok) runCatching { WidgetRefreshScheduler.markDataFetched(applicationContext) }
        } else {
            // 未登录也记「已核对」：没登录时安全网无事可做，gate 可以收摊；
            // 重新登录后的第一次 ensureSchedule 会把它再登记回来。
            runCatching { WidgetRefreshScheduler.markDataFetched(applicationContext) }
        }

        runCatching { AutoSignEngine.runOnce(applicationContext, app) }
        runCatching { app.attendanceScheduler.ensureSchedule(startDaemon = false) }
        return Result.success()
    }
}

/**
 * 恢复：接收器在容器未就绪时（依赖初始化失败）丢弃唤醒前的最后退路。
 *
 * WorkManager 的入队不依赖应用容器，因此接收器自身总是能完成这次转交；
 * 30 秒后由它重排调度并尝试签到，容器仍没好就 `retry`（退避重试）。
 */
class AutoSignRecoveryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? QingxinApp ?: return Result.success()
        if (!app.isReady) return Result.retry()
        if (!app.attendanceScheduler.getSettings().autoSignEnabled) return Result.success()

        // 先重排（把被丢弃的唤醒重新接上），再尝试签到（此刻可能已在窗口内）。
        runCatching { app.attendanceScheduler.ensureSchedule(startDaemon = false) }
        runCatching { AutoSignEngine.runOnce(applicationContext, app) }
        return Result.success()
    }
}
