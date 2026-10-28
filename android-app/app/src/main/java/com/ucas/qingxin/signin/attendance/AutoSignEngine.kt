package com.ucas.qingxin.signin.attendance

import android.content.Context
import android.os.PowerManager
import com.ucas.qingxin.signin.QingxinApp
import com.ucas.qingxin.signin.R
import com.ucas.qingxin.signin.data.Course
import com.ucas.qingxin.signin.data.SignOutcome
import com.ucas.qingxin.signin.network.ApiException
import com.ucas.qingxin.signin.widget.TodayCourseWidgetReceiver
import com.ucas.qingxin.signin.widget.WidgetRefreshScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** 自动签到单轮执行结果。 */
internal enum class EngineOutcome {
    /** 今日无课 / 都已签到 / 还没到随机时刻 —— 无事可做。 */
    IDLE,

    /** 签到成功（含「核对后发现已被签到」）。 */
    SIGNED,

    /** 非终态失败，窗口内还可以重试。 */
    RETRY,

    /** 终态失败（超过 deadline 或用尽预算），已发强制结果通知。 */
    FAILED,

    /** 用户正在手动签到，自动路径主动让路（不发通知）。 */
    YIELDED,

    /** 未开启自动签到 / 应用未就绪。 */
    DISABLED,

    /** 未登录。 */
    NEED_LOGIN,
}

/** 一节待签到的课及其冻结的随机时刻。 */
internal data class DueCourse(
    val course: Course,
    val beginMs: Long,
    val targetMs: Long,
) {
    val deadlineMs: Long get() = AutoSignRandomizer.deadlineMs(beginMs)
}

/**
 * 最近一次自动签到终态（进程内）。
 *
 * 通知是「应用不在前台」时的触达渠道；[AutoSignEngine.lastOutcome] 这条 StateFlow
 * 是「应用正开着」时的触达渠道 —— 主页收集它，把「自动签到成功 / 失败」直接写在
 * 课程卡片上。没有它，用户开着应用时只能盯着纹丝不动的课表猜后台发生了什么。
 *
 * 顶层声明（而非嵌套在 [AutoSignEngine] 里）：主页的 `AppUiState`（public）以它为
 * 属性类型，而 internal 容器里的 public 嵌套类有效可见性仍是 internal，会编译失败。
 */
data class AutoSignOutcomeEvent(
    val success: Boolean,
    val courseName: String,
    val atMs: Long,
)

/**
 * 自动签到执行引擎 —— 闹钟、守护服务、WorkManager 三条路径共用的**唯一**执行体。
 *
 * 省电约束：
 * - **取数闸门**：本地课程数据 10 分钟内新鲜就零网络，直接复用内存 / 本地缓存；
 * - **校时复用**：优先用 [com.ucas.qingxin.signin.qr.QrTimelineManager] 的 30 秒 TTL 样本，
 *   仅在其为 null 时才真正请求学校时间（对比 1.1.11 每次唤醒必发 2 个 HTTP）；
 * - **重试预算按课次计数**（[attempts]），不论由哪条路径驱动，一节课的请求次数都有上限；
 * - **重试不排新闹钟**，只发生在当次唤醒的电量豁免窗口与守护进程内。
 */
internal object AutoSignEngine {

    /** 本地课程数据在这个时长内视为新鲜，直接复用、不发网络。 */
    private const val CACHE_FRESH_MS = 10L * 60L * 1000L

    /** 每个课次允许的签到请求总次数上限（普通档）。档位解析见 [PowerProfiles]。 */
    const val MAX_ATTEMPTS = 8

    /** 前置提醒的提前量：**上课前 15 分钟**（与签到窗口开启时刻的关系见 [remindAtFor]）。 */
    const val UPCOMING_LEAD_MS = 15L * 60L * 1000L

    /**
     * 提醒与「实际签到时刻」之间必须保留的最小间隔。
     *
     * 见 [remindAtFor] 的边界说明：随机签到时刻的下界正好是「上课前 15 分钟」，
     * 因此提醒有可能和签到撞在同一瞬间。撞上时提醒已失去意义（用户看到
     * 「即将自动签到」的同时它已经签完了），所以宁可把提醒往前挪一点。
     */
    private const val MIN_REMIND_GAP_MS = 60L * 1000L

    /**
     * 未取得电池豁免时，Doze 会把 `setAndAllowWhileIdle` 推迟最多约 15 分钟。
     * 与其死等随机时刻，不如「进程既然醒着、且已进入容差范围就先签」——
     * 既不越出签到窗口，也不会因系统推迟而错过。
     */
    const val DOZE_SLACK_MS = 5L * 60L * 1000L

    /** `课次@日期` → 已发过终态通知（成功 / 失败），保证每节课只推一条。 */
    private val notified = ConcurrentHashMap<String, Boolean>()

    /** `课次@日期` → 已消耗的签到请求次数。 */
    private val attempts = ConcurrentHashMap<String, Int>()

    private val _lastOutcome = MutableStateFlow<AutoSignOutcomeEvent?>(null)

    /** 主页订阅：展示最近一次自动签到的结果（详见 [AutoSignOutcomeEvent]）。 */
    val lastOutcome: StateFlow<AutoSignOutcomeEvent?> = _lastOutcome.asStateFlow()

    /** 记录一次终态。与结果通知同一批调用点，两者互为镜像、互不替代。 */
    private fun emitOutcome(success: Boolean, course: Course) {
        _lastOutcome.value = AutoSignOutcomeEvent(success, course.name, System.currentTimeMillis())
    }

    /**
     * 串行化 [runOnce]：闹钟、守护服务、WorkManager 可能在同一瞬间醒来，
     * 用 `tryLock` 让后来者直接返回，避免对后端发出重复签到请求。
     */
    private val runMutex = Mutex()

    /** 用户关闭自动签到 / 退出登录时清空运行态。 */
    fun resetRuntime() {
        notified.clear()
        attempts.clear()
        AutoSignRandomizer.clearAll()
        _lastOutcome.value = null
    }

    // ------------------------------------------------------------------ 主流程

    suspend fun runOnce(context: Context, app: QingxinApp): EngineOutcome {
        if (!app.isReady) return EngineOutcome.DISABLED
        if (!app.attendanceScheduler.getSettings().autoSignEnabled) return EngineOutcome.DISABLED
        // 已有一次执行在进行中：直接返回，绝不并发重复请求后端。
        if (!runMutex.tryLock()) return EngineOutcome.IDLE
        return try {
            runLocked(context, app)
        } finally {
            runMutex.unlock()
        }
    }

    private suspend fun runLocked(context: Context, app: QingxinApp): EngineOutcome {
        val today = AutoSignRandomizer.todayKey()
        AutoSignRandomizer.pruneBefore(today)
        pruneRuntime(today)

        if (!runCatching { app.authRepository.isLoggedIn() }.getOrDefault(false)) {
            refreshDaemonStatus(context, app, emptyList())
            return EngineOutcome.NEED_LOGIN
        }

        val courses = loadCourses(context, app) ?: return EngineOutcome.RETRY
        if (courses.isEmpty()) {
            refreshDaemonStatus(context, app, courses)
            return EngineOutcome.IDLE
        }

        val parseBegin: (Course) -> Long? = { c ->
            runCatching { app.courseRepository.parseBeginMs(c) }.getOrNull()
        }
        val targetOf: (Course, Long) -> Long = { c, begin ->
            AutoSignRandomizer.targetMsFor(courseIdOf(c), begin, System.currentTimeMillis(), today)
        }

        val open = pending(courses, today)
        val schoolNow = schoolNowMs(app)
        val due = AutoSignSelector.selectDue(
            open,
            schoolNow,
            parseBegin,
            targetOf,
            toleranceMs = earlyToleranceMs(context),
        )
        if (due == null) {
            expireStale(context, app, open, schoolNow, today, parseBegin)
            refreshDaemonStatus(context, app, courses)
            return EngineOutcome.IDLE
        }

        val begin = parseBegin(due) ?: run {
            refreshDaemonStatus(context, app, courses)
            return EngineOutcome.IDLE
        }
        val key = keyOf(courseIdOf(due), today)
        val targetMs = targetOf(due, begin)
        announceUpcoming(app, due, begin, targetMs)

        val cap = attemptCap(context)
        val used = attempts[key] ?: 0

        // 预算已用尽：先核对最新状态（可能已被手动签掉），再决定发不发失败通知。
        if (used >= cap) {
            if (settleAlreadySigned(context, app, due, key, today)) return EngineOutcome.SIGNED
            finishFailed(context, app, due, key, null)
            return EngineOutcome.FAILED
        }
        attempts[key] = used + 1

        val result = try {
            app.attendanceRepository.trySignOneClick(due)
        } catch (e: ApiException) {
            if (e.code == "LOGIN_EXPIRED" || e.code == "LOGIN_REJECTED") {
                runCatching {
                    app.notifier.notifyAutoSignResult(false, due.id, due.name, e.message ?: e.code)
                    app.notifier.clearUpcoming(due.id)
                }
                runCatching { app.attendanceScheduler.stop() }
                return EngineOutcome.NEED_LOGIN
            }
            return EngineOutcome.RETRY
        } catch (e: CancellationException) {
            // 看门狗超时 / 守护服务退出的取消必须穿透：吞掉它会让已取消的协程
            // 继续走 RETRY 节奏（下一次 delay 立刻再抛），还把一次「中断」误记成
            // 「可重试的失败」。取消的语义交给上层（withTimeoutOrNull / 作用域）。
            throw e
        } catch (_: Exception) {
            return EngineOutcome.RETRY
        }

        // null = 用户此刻正在手动签到，自动路径让路并保持安静。
        if (result == null) {
            refreshDaemonStatus(context, app, courses)
            return EngineOutcome.YIELDED
        }

        if (result.outcome != SignOutcome.SIGNED) {
            val withinWindow = schoolNowMs(app) <= AutoSignRandomizer.deadlineMs(begin)
            if (withinWindow && (attempts[key] ?: 0) < cap) return EngineOutcome.RETRY
            // 终结之前再核对一次：用户可能刚好手动签到成功，此时绝不能报「自动签到失败」。
            if (settleAlreadySigned(context, app, due, key, today)) return EngineOutcome.SIGNED
            finishFailed(context, app, due, key, result.message)
            return EngineOutcome.FAILED
        }

        return settleSigned(context, app, due, key, today, result.message)
    }

    // ------------------------------------------------------------------ 查询 / 状态

    /** 此刻「已经到达随机时刻、可以立刻尝试」的课（不产生任何网络请求）。 */
    fun dueNow(context: Context, app: QingxinApp, courses: List<Course>? = null): Course? {
        if (!app.isReady) return null
        val list = effectiveCourses(app, courses) ?: return null
        if (list.isEmpty()) return null
        val today = AutoSignRandomizer.todayKey()
        val now = System.currentTimeMillis()
        val parseBegin: (Course) -> Long? = { c ->
            runCatching { app.courseRepository.parseBeginMs(c) }.getOrNull()
        }
        val cap = attemptCap(context)
        val candidates = pending(list, today)
            .filter { (attempts[keyOf(courseIdOf(it), today)] ?: 0) < cap }
        return AutoSignSelector.selectDue(
            candidates,
            now,
            parseBegin,
            { c, begin -> AutoSignRandomizer.targetMsFor(c.id, begin, now, today) },
            toleranceMs = earlyToleranceMs(context),
        )
    }

    /** 今日下一个待签课次（含已冻结的随机时刻）。用于排闹钟与守护服务计时。 */
    fun nextDue(app: QingxinApp, courses: List<Course>? = null): DueCourse? {        if (!app.isReady) return null
        val list = effectiveCourses(app, courses) ?: return null
        if (list.isEmpty()) return null
        val today = AutoSignRandomizer.todayKey()
        val now = System.currentTimeMillis()
        val parseBegin: (Course) -> Long? = { c ->
            runCatching { app.courseRepository.parseBeginMs(c) }.getOrNull()
        }
        val picked = AutoSignSelector.selectNext(list, now, parseBegin) { c, begin ->
            AutoSignRandomizer.targetMsFor(courseIdOf(c), begin, now, today)
        } ?: return null
        val begin = parseBegin(picked.first) ?: return null
        return DueCourse(picked.first, begin, picked.second)
    }

    /**
     * 守护服务现在是否「值得」活着。
     *
     * 三个条件缺一不可：自动签到开着、当前档位允许常驻服务（省电档不允许）、
     * 且「下一节课的提醒时刻已进入 [DAEMON_LOOKAHEAD_MS]」**或**
     * 「此刻有课正处在签到窗口内」。
     *
     * 最后两条是耗电治理的核心：1.2.0 里每次打开 App 都会拉起这个前台服务，
     * 它带着常驻通知活一整天，系统因此**既不冻结也不回收**进程，
     * 与前台无关的循环就能整夜满速运行。现在它只在签到窗口前后存在。
     *
     * 「窗口内」这一条是 1.3.7 补上的：进程在窗口中段被杀再重启时，
     * `nextDue`（只看未来时刻）是 null，旧逻辑会据此判定「不值得活」并把
     * 整条重试链丢给 SWEEP。窗口内的进程内重试（60–90 秒一拍）远比
     * SWEEP（3 分钟一拍）及时，因此窗口未关就值得回窗。
     */
    fun shouldRunDaemon(app: QingxinApp, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!app.isReady) return false
        if (!app.attendanceScheduler.getSettings().autoSignEnabled) return false
        if (!app.powerProfile().daemonEnabled) return false
        val due = runCatching { nextDue(app) }.getOrNull()
        if (due != null) return remindAtFor(due) - nowMs <= DAEMON_LOOKAHEAD_MS
        // 随机时刻已过但窗口未关：回窗继续补签（nextDue == null 时 alarm 链已停）。
        return runCatching { windowNow(app) }.getOrNull() != null
    }

    /** 守护服务提前多久进入「值得存活」的状态。 */
    private const val DAEMON_LOOKAHEAD_MS = 10L * 60L * 1000L

    /** 常驻通知文案。唤醒成本为零，只在进程本来就醒着时调用。 */
    fun daemonStatusText(context: Context, app: QingxinApp, courses: List<Course>? = null): String {
        if (!runCatching { app.authRepository.isLoggedIn() }.getOrDefault(false)) {
            return context.getString(R.string.auto_sign_status_need_login)
        }
        val list = effectiveCourses(app, courses)
            ?: return context.getString(R.string.auto_sign_status_waiting)
        if (list.isEmpty()) return context.getString(R.string.auto_sign_status_no_course)
        val next = nextDue(app, list)
            ?: return context.getString(R.string.auto_sign_status_all_done)
        return context.getString(
            R.string.auto_sign_status_next,
            AttendanceNotifier.formatClock(next.targetMs),
            next.course.name,
        )
    }

    fun refreshDaemonStatus(context: Context, app: QingxinApp, courses: List<Course>?) {
        val text = runCatching { daemonStatusText(context, app, courses) }.getOrNull() ?: return
        runCatching { app.notifier.updateDaemonStatus(text) }
    }

    // ------------------------------------------------------------------ 内部

    private fun keyOf(courseId: String, today: String): String =
        AutoSignRandomizer.key(courseId, today)

    /** 课程标识：`id` 为空时退回 `uuid`，避免不同课次共用同一个 key。 */
    private fun courseIdOf(course: Course): String = course.id.ifBlank { course.uuid }

    private fun upcomingKeyOf(courseId: String, today: String): String =
        "upcoming:" + AutoSignRandomizer.key(courseId, today)

    private fun pruneRuntime(today: String) {
        notified.keys.filterNot { it.endsWith("@$today") }.forEach { notified.remove(it) }
        attempts.keys.filterNot { it.endsWith("@$today") }.forEach { attempts.remove(it) }
    }

    /** 还没出终态结果、也没签到的课次。 */
    private fun pending(courses: List<Course>, today: String): List<Course> =
        courses.filterNot { it.signed || notified.containsKey(keyOf(courseIdOf(it), today)) }

    /** 返回 true 表示本次是第一次标记（调用方据此决定是否发通知）。 */
    private fun markNotified(key: String): Boolean = notified.putIfAbsent(key, true) == null

    /**
     * 「上课前提醒」的时刻：**上课前 15 分钟**（对应设置页与通知里的「上课前提醒」）。
     *
     * ## 为什么是「相对上课时刻」而不是「相对随机签到时刻」
     * 随机签到时刻每节课都不同（`[上课前15分钟, 上课前30秒]` 内均匀随机），
     * 以它为基准的提醒（1.2.0 是「随机时刻前 2 分钟」）意味着用户**无法预知**
     * 什么时候会收到通知，也就无法据此安排自己的动作。改成固定相对上课时刻，
     * 用户看到通知就知道「还有 15 分钟上课，签到即将开始」。
     *
     * ## 与签到窗口的关系
     * 签到窗口从**上课前 25 分钟**开启，因此「上课前 15 分钟」落在窗口内部，
     * 提醒发出时窗口已经开放 —— 用户收到通知后立刻手动签到也是有效的。
     *
     * ## 边界钳制
     * [AutoSignRandomizer] 的随机下界正好是 `上课前 15 分钟`，所以随机时刻有可能
     * 落在提醒点上（甚至更早，当随机值被 `nowMs + 30 秒` 的下界推后、
     * 而进程直到临近上课才算出来时）。此时提醒与签到会撞在同一瞬间，
     * 而「即将自动签到」的通知在签完之后才看到是没有意义的，
     * 因此这里保证提醒至少比签到早 [MIN_REMIND_GAP_MS]。
     *
     * 钳制只会让提醒**更早**，永远不会更晚；并且因为签到窗口 25 分钟才开启，
     * 即便提前到 16 分钟前，仍处在窗口内。
     */
    fun remindAtFor(beginMs: Long, targetMs: Long): Long =
        minOf(beginMs - UPCOMING_LEAD_MS, targetMs - MIN_REMIND_GAP_MS)

    /** [remindAtFor] 的 `DueCourse` 语法糖。 */
    fun remindAtFor(due: DueCourse): Long = remindAtFor(due.beginMs, due.targetMs)

    /**
     * 由提醒闹钟调用：找出此刻该提醒的课次并发出提醒。
     *
     * ## 为什么提醒要有独立闹钟
     * 1.2.0 里提醒是守护服务 `delay` 睡到点时**顺带发出**的
     * （见 `AttendanceDaemonService.arm()`），而省电档根本没有守护服务，
     * 于是省电档**完全收不到提醒**。拆成独立闹钟后两档都能收到，
     * 且这条路径**不联网、不启服务**，唤醒成本接近 0。
     *
     * ## 容差
     * 部分 ROM 的非精确闹钟会提前几秒到几十秒触发。若窗口卡死在提醒时刻上，
     * 提前触发就会让提醒**整体丢失**，因此下界留 [REMIND_TOLERANCE_MS] 的余量。
     *
     * @return 是否真的发出了（便于单测断言）。
     */
    fun remindNow(app: QingxinApp, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!app.isReady) return false
        if (!app.attendanceScheduler.getSettings().autoSignEnabled) return false
        runCatching { nextDue(app) }.getOrNull()?.let { due ->
            return notifyUpcoming(app, due.course, due.beginMs, due.targetMs, nowMs)
        }
        // 提醒闹钟被 ROM / Doze 推迟、醒来时随机时刻已过的情形：只要窗口未关、
        // 且刚过不久（[REMIND_LATE_GRACE_MS] 内），仍补发提醒 —— 否则这类设备
        // 会「整窗一条提醒都收不到」（提醒与签到两个非精确闹钟常被 Doze 合并到
        // 同一时刻放行，上面的 nextDue 因 `target > now` 不成立而整体落空）。
        // 超过宽限就不补了：那时结果通知马上就到，迟到的提醒只会造成噪音。
        val late = runCatching { windowNow(app) }.getOrNull() ?: return false
        val begin = runCatching { app.courseRepository.parseBeginMs(late) }.getOrNull() ?: return false
        val target = AutoSignRandomizer.targetMsFor(
            courseIdOf(late),
            begin,
            nowMs,
            AutoSignRandomizer.todayKey(),
        )
        return notifyUpcoming(app, late, begin, target, nowMs, lateGraceMs = REMIND_LATE_GRACE_MS)
    }

    /**
     * 发出「即将开始签到」的前置提醒。
     *
     * 两条调用路径共用：提醒闹钟（[remindNow]）与守护服务进程内定时器
     * （[announceUpcoming]）。去重沿用 [notified]，且键带 `upcoming:` 前缀，
     * 与终态结果通知互不影响，因此两者在同一分钟触发也只会发一条。
     *
     * @return 本次是否真的发出了（便于单测断言）。
     */
    fun notifyUpcoming(
        app: QingxinApp,
        course: Course,
        beginMs: Long,
        targetMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        lateGraceMs: Long = 0L,
    ): Boolean {
        if (course.signed) return false
        // 有效窗口 = 「提醒时刻（含容差）」到「签到时刻（含迟到宽限）」之间。
        // 上界必须是 targetMs 而不是「固定 15 分钟」：随机时刻可能比提醒点晚十几分钟
        // （最晚到上课前 30 秒），用固定上界会把这些课次的提醒整体丢掉。
        // [lateGraceMs] 只有提醒闹钟的迟到兜底（[remindNow]）会传：执行路径
        // （[announceUpcoming]）保持 0 —— 签到都已经开始了再喊「即将签到」纯属噪音。
        val remindAt = remindAtFor(beginMs, targetMs)
        if (!AutoSignSelector.remindDue(
                remindAtMs = remindAt,
                targetMs = targetMs,
                nowMs = nowMs,
                toleranceMs = REMIND_TOLERANCE_MS,
                lateGraceMs = lateGraceMs,
            )
        ) {
            return false
        }
        if (!app.attendanceScheduler.getSettings().autoSignEnabled) return false
        val key = upcomingKeyOf(courseIdOf(course), AutoSignRandomizer.todayKey())
        if (!markNotified(key)) return false
        runCatching { app.notifier.notifyAutoSignUpcoming(course.id, course.name, targetMs) }
        return true
    }

    /** 提醒闹钟可能被 ROM 提前触发，留出余量避免提醒整体丢失。 */
    private const val REMIND_TOLERANCE_MS = 60L * 1000L

    /** 随机时刻刚过这么久之内，迟到的提醒闹钟仍补发提醒（见 [remindNow]）。 */
    private const val REMIND_LATE_GRACE_MS = 2L * 60L * 1000L

    private fun attemptCap(context: Context): Int {
        val app = context.applicationContext as? QingxinApp ?: return MAX_ATTEMPTS
        return app.powerProfile().maxAttempts
    }

    /** 未取得电池豁免时允许「提前一点签」，以对冲 Doze 对非精确闹钟的推迟。 */
    private fun earlyToleranceMs(context: Context): Long =
        if (KeepAliveHelper.isBatteryExempt(context)) 0L else DOZE_SLACK_MS

    /** 签到成功后的统一收尾：清随机值、发强制成功通知、刷新课表与常驻通知。 */
    private suspend fun settleSigned(
        context: Context,
        app: QingxinApp,
        course: Course,
        key: String,
        today: String,
        detail: String,
    ): EngineOutcome {
        attempts.remove(key)
        markNotified(key)
        AutoSignRandomizer.forget(courseIdOf(course), today)
        runCatching {
            app.notifier.clearUpcoming(course.id)
            app.notifier.notifyAutoSignResult(true, course.id, course.name, detail)
        }
        emitOutcome(success = true, course)
        runCatching { app.courseRepository.loadToday() }
        runCatching { WidgetRefreshScheduler.markDataFetched(context) }
        runCatching { TodayCourseWidgetReceiver.requestUpdate(context) }
        // startDaemon = false：本调用点可能在 worker / 接收器的短时窗里，从那里
        // 拉前台服务在 API 31+ 会被拒绝并把「守护被拦」记成假阳性。守护是否该活
        // 由它自己的循环在 75 秒内自检收场（见 AttendanceDaemonService.arm）。
        runCatching { AttendanceScheduler.ensureSchedule(context, startDaemon = false) }
        refreshDaemonStatus(context, app, null)
        return EngineOutcome.SIGNED
    }

    /**
     * 终结失败之前核对一次服务端的真实状态。
     *
     * 典型场景：用户在我们重试的空档自己手动点了「一键签到」——
     * 这时**绝不能**再推一条「自动签到失败」，否则会让用户以为签到没成功。
     *
     * @return true 表示该课其实已签到（已按成功收尾，调用方不要再发失败通知）。
     */
    private suspend fun settleAlreadySigned(
        context: Context,
        app: QingxinApp,
        course: Course,
        key: String,
        today: String,
    ): Boolean {
        val refreshed = runCatching { app.courseRepository.loadToday().courses }.getOrNull()
            ?: return false
        if (refreshed.firstOrNull { courseIdOf(it) == courseIdOf(course) }?.signed != true) return false
        attempts.remove(key)
        // 首次出终态也必须发成功通知：典型成因是「签到请求已到校方、响应却超时」，
        // 引擎按失败重试、后续尝试又被学校以「已签到」拒绝，最后从这条路收场 ——
        // 不在这里补一条，用户手里的记录写着「失败」，通知栏却一片安静
        //（1.3.6 的「有记录、无通知」反馈正来自这里）。markNotified 保证只发一次。
        if (markNotified(key)) {
            runCatching { app.notifier.notifyAutoSignResult(true, course.id, course.name, "") }
        }
        AutoSignRandomizer.forget(courseIdOf(course), today)
        runCatching { app.notifier.clearUpcoming(course.id) }
        runCatching { TodayCourseWidgetReceiver.requestUpdate(context) }
        emitOutcome(success = true, course)
        refreshDaemonStatus(context, app, refreshed)
        return true
    }

    private fun finishFailed(
        context: Context,
        app: QingxinApp,
        course: Course,
        key: String,
        detail: String?,
    ) {
        if (markNotified(key)) {
            runCatching {
                app.notifier.notifyAutoSignResult(
                    false,
                    course.id,
                    course.name,
                    detail ?: context.getString(R.string.auto_sign_fail_generic),
                )
                app.notifier.clearUpcoming(course.id)
            }
            emitOutcome(success = false, course)
        }
        AutoSignRandomizer.forget(courseIdOf(course), AutoSignRandomizer.todayKey())
        refreshDaemonStatus(context, app, null)
    }

    /**
     * 签到前提醒（守护服务路径）。
     *
     * 只在进程**本就醒着**时顺带发出，绝不因此排专用闹钟 ——
     * 专用提醒闹钟由 [AttendanceScheduler] 负责，两档都有。
     */
    private fun announceUpcoming(app: QingxinApp, course: Course, beginMs: Long, targetMs: Long) {
        notifyUpcoming(app, course, beginMs, targetMs)
    }

    /**
     * 窗口已过、却既没签到成功也没通知过的课次 → 补一条终态失败通知。
     * 覆盖「设备长时间 Doze / 被省电策略掐掉，我们根本没醒过」的情形。
     *
     * 同样会先核对服务端状态，避免对「其实已经签到」的课次误报失败。
     * 1.3.7 起遍历**全部**过期课次（[notified] 天然去重，重复调用幂等），
     * 不再只处理最早的一节。
     */
    private suspend fun expireStale(
        context: Context,
        app: QingxinApp,
        courses: List<Course>,
        nowMs: Long,
        today: String,
        parseBegin: (Course) -> Long?,
    ) {
        courses.forEach { course ->
            runCatching { expireOne(context, app, course, today, parseBegin, nowMs) }
        }
    }

    /**
     * 单个课次的「窗口错过」收尾。供 [expireStale]（引擎例行检查）与
     * [finalizeStale]（post-mortem 任务）共用 —— 两条路径的通知语义必须完全一致。
     *
     * @return true 表示本次真的做了收尾（确认已签或发了失败通知）。
     */
    private suspend fun expireOne(
        context: Context,
        app: QingxinApp,
        course: Course,
        today: String,
        parseBegin: (Course) -> Long?,
        nowMs: Long,
    ): Boolean {
        if (course.signed) return false
        val begin = parseBegin(course) ?: return false
        if (nowMs <= AutoSignRandomizer.deadlineMs(begin)) return false
        val key = keyOf(courseIdOf(course), today)
        if (notified.containsKey(key)) return false
        if (settleAlreadySigned(context, app, course, key, today)) return true
        if (!markNotified(key)) return false
        runCatching {
            app.notifier.notifyAutoSignResult(
                false,
                course.id,
                course.name,
                context.getString(R.string.auto_sign_fail_expired),
            )
            app.notifier.clearUpcoming(course.id)
        }
        emitOutcome(success = false, course)
        AutoSignRandomizer.forget(courseIdOf(course), today)
        return true
    }

    /**
     * 取数闸门：本地数据够新就零网络。
     * @return null 表示「取不到数据」（网络失败且无缓存），调用方应重试。
     *
     * 三条返回路径都必须过 [exclude]：这是**唯一**同时覆盖实签（`runLocked`）
     * 与闹钟排程（`nextDue`）的注入点。
     */
    private suspend fun loadCourses(context: Context, app: QingxinApp): List<Course>? {
        val today = AutoSignRandomizer.todayKey()
        val fresh = WidgetRefreshScheduler.lastDataDay(context) == today &&
            System.currentTimeMillis() - WidgetRefreshScheduler.lastDataAt(context) < CACHE_FRESH_MS
        if (fresh) {
            app.courseRepository.courses.value?.let { return exclude(app, it.courses) }
        }
        val studentNo = runCatching { app.authRepository.session.value?.studentNo }.getOrNull()
        app.courseRepository.cachedToday(studentNo.orEmpty())?.let { return exclude(app, it.courses) }
        return runCatching { app.courseRepository.loadToday().courses }.getOrNull()?.let {
            exclude(app, it)
        }
    }

    /**
     * 同步版本的只读读取（内存 → 本地缓存），绝不发网络。
     *
     * **刻意不过 [exclude]**：这是「原始课程」的取数函数，唯一的调用方是
     * [effectiveCourses]，由后者统一过滤。若将来直接调用它，请自己补上排除判定，
     * 否则被用户排除的课会重新出现在结果里。
     */
    private fun loadCoursesSync(app: QingxinApp): List<Course>? {
        app.courseRepository.courses.value?.let { return it.courses }
        val studentNo = runCatching { app.authRepository.session.value?.studentNo }.getOrNull()
        return app.courseRepository.cachedToday(studentNo.orEmpty())?.courses
    }

    // -------------------------------------------------- 今日全盘计划（供调度器）

    /**
     * 调度需要的「今日全盘」一次算全。
     *
     * 全部字段都来自**离线**取数链（内存 → 本地缓存，见 [loadCoursesSync]），
     * 因此 `ensureSchedule` 无论被谁、以什么频率调用，都不会产生网络请求。
     */
    internal data class TodayPlan(
        /** 此刻正在窗口内、可补签的课（SWEEP 槽的武装依据）。 */
        val window: Course?,
        /** 下一个将要签到的课次（REMIND / SIGN 槽的武装依据）。 */
        val next: DueCourse?,
        /** 最早的一个尚未到期的课次 deadline（post-mortem 任务的排程依据）。 */
        val nextDeadlineMs: Long?,
        /** 今日是否还有任何未出终态、未签到的课次（安全网 gate 的依据）。 */
        val hasPending: Boolean,
    )

    /** [TodayPlan] 的计算入口。容器未就绪 / 未开启自动签到时返回 null。 */
    fun planToday(app: QingxinApp, nowMs: Long = System.currentTimeMillis()): TodayPlan? {
        if (!app.isReady) return null
        if (!app.attendanceScheduler.getSettings().autoSignEnabled) return null
        val today = AutoSignRandomizer.todayKey()
        val list = effectiveCourses(app, null) ?: return null
        if (list.isEmpty()) return TodayPlan(null, null, null, hasPending = false)

        val parseBegin: (Course) -> Long? = { c ->
            runCatching { app.courseRepository.parseBeginMs(c) }.getOrNull()
        }
        val cap = attemptCap(app.applicationContext)
        val open = pending(list, today)
        val targetOf: (Course, Long) -> Long = { c, begin ->
            AutoSignRandomizer.targetMsFor(courseIdOf(c), begin, nowMs, today)
        }

        val window = open
            .filter { (attempts[keyOf(courseIdOf(it), today)] ?: 0) < cap }
            .let { candidates ->
                AutoSignSelector.selectWindow(candidates, nowMs, parseBegin, targetOf)
            }
        val next = AutoSignSelector.selectNext(list, nowMs, parseBegin, targetOf)?.let { picked ->
            val begin = parseBegin(picked.first) ?: return@let null
            DueCourse(picked.first, begin, picked.second)
        }
        val nextDeadline = AutoSignSelector.nextPostmortemAt(open, nowMs, parseBegin)
        return TodayPlan(
            window = window,
            next = next,
            nextDeadlineMs = nextDeadline,
            hasPending = open.isNotEmpty(),
        )
    }

    /** 「此刻正在窗口内、可补签」的课。守护服务与调度器的 SWEEP 判定共用。 */
    fun windowNow(app: QingxinApp, courses: List<Course>? = null): Course? {
        if (!app.isReady) return null
        if (!app.attendanceScheduler.getSettings().autoSignEnabled) return null
        val list = effectiveCourses(app, courses) ?: return null
        if (list.isEmpty()) return null
        val today = AutoSignRandomizer.todayKey()
        val now = System.currentTimeMillis()
        val parseBegin: (Course) -> Long? = { c ->
            runCatching { app.courseRepository.parseBeginMs(c) }.getOrNull()
        }
        val cap = attemptCap(app.applicationContext)
        val candidates = pending(list, today)
            .filter { (attempts[keyOf(courseIdOf(it), today)] ?: 0) < cap }
        return AutoSignSelector.selectWindow(
            candidates,
            now,
            parseBegin,
            { c, begin -> AutoSignRandomizer.targetMsFor(courseIdOf(c), begin, now, today) },
        )
    }

    /**
     * post-mortem 专用收尾：**不做**签到动作，只把「窗口已彻底错过」的课次补上终态通知，
     * 并重排后续调度。
     *
     * ## 为什么不直接调 [runOnce]
     * `runOnce` 全程持有 `runMutex`，抢不到锁（守护进程 / 其他执行体正在签）时返回 IDLE
     * 且**不会**执行 `expireStale` —— 一次性任务被消费、终态通知却丢了。post-mortem 的
     * 使命是「兜最底」，绝不能被并发挤掉，因此这里完全绕开 runMutex，只读运行态做收尾。
     *
     * ## 窗口其实还没关的情形
     * 校时偏差 / SWEEP 还在重试时 `windowNow` 非 null：此时什么通知都不发
     * （发失败通知就是误报），只重排调度 —— SWEEP 会把这一课签完。
     */
    suspend fun finalizeStale(context: Context, app: QingxinApp) {
        if (!app.isReady) return
        if (!app.attendanceScheduler.getSettings().autoSignEnabled) return
        if (runCatching { windowNow(app) }.getOrNull() != null) {
            runCatching { AttendanceScheduler.ensureSchedule(context, startDaemon = false) }
            return
        }
        val today = AutoSignRandomizer.todayKey()
        val courses = effectiveCourses(app, null) ?: return
        val parseBegin: (Course) -> Long? = { c ->
            runCatching { app.courseRepository.parseBeginMs(c) }.getOrNull()
        }
        val now = System.currentTimeMillis()
        courses.forEach { course ->
            runCatching { expireOne(context, app, course, today, parseBegin, now) }
        }
        runCatching { AttendanceScheduler.ensureSchedule(context, startDaemon = false) }
    }

    /**
     * 解析课程来源并统一剔除「不打卡」的课程。
     *
     * 之所以把它单独抽成一个入口，而不是只在 [loadCourses] / [loadCoursesSync] 里过滤：
     * `dueNow` / `nextDue` / `daemonStatusText` 都允许调用方**直接传入**一个课程列表
     * （守护服务与设置页就是这么做的），那条路径会绕过取数函数。
     * 过滤放在这里，三条查询入口与两条执行入口就都覆盖到了。
     *
     * 过滤失败（尚未初始化、prefs 异常）时返回**原始列表**：
     * 宁可多签一节，也不能因为读排除表失败就整个自动签到罢工。
     */
    private fun effectiveCourses(app: QingxinApp, courses: List<Course>?): List<Course>? =
        (courses ?: loadCoursesSync(app))?.let { exclude(app, it) }

    /** 剔除用户标记为「今天不打卡 / 长期不打卡」的课程。见 [AutoSignExclusions]。 */
    private fun exclude(app: QingxinApp, courses: List<Course>): List<Course> {
        if (courses.isEmpty()) return courses
        return runCatching {
            val store = app.autoSignExclusionStore
            AutoSignExclusions.filter(
                courses = courses,
                permanent = store.permanentKeys(),
                todayOnly = store.todayKeys(AutoSignRandomizer.todayKey()),
            )
        }.getOrDefault(courses)
    }

    /** 学校时间：优先复用 30 秒 TTL 的校时样本，其次才真正请求。 */
    private suspend fun schoolNowMs(app: QingxinApp): Long =
        app.qrTimeline.currentSchoolTimeOrNull()
            ?: runCatching { app.qrTimeline.syncClock().schoolNowMs }
                .getOrDefault(System.currentTimeMillis())
}

/**
 * 「该不该现在签到」的纯逻辑，抽出来便于单元测试。
 */
internal object AutoSignSelector {

    /**
     * 挑出此刻应当自动签到的课。
     *
     * 条件：未签到、开始时间可解析、随机时刻已到（允许 [toleranceMs] 的提前量）、
     * 且未超过 deadline。多个同时满足时取随机时刻最早的一节（只签一节，避免并发请求）。
     */
    fun selectDue(
        courses: List<Course>,
        nowMs: Long,
        parseBegin: (Course) -> Long?,
        targetOf: (Course, Long) -> Long,
        toleranceMs: Long = 0L,
    ): Course? = courses.asSequence()
        .filter { !it.signed }
        .mapNotNull { c -> parseBegin(c)?.let { b -> Triple(c, b, targetOf(c, b)) } }
        .filter { (_, begin, target) ->
            nowMs >= target - toleranceMs && nowMs <= AutoSignRandomizer.deadlineMs(begin)
        }
        .minByOrNull { it.third }
        ?.first

    /**
     * 今日下一个「将要签到」的课次：随机时刻**还没到**、且窗口尚未彻底结束。
     *
     * 刻意要求 `target > nowMs`：随机时刻已过的课次说明已经由当次唤醒处理过，
     * 不应再为它排新的闹钟。
     */
    fun selectNext(
        courses: List<Course>,
        nowMs: Long,
        parseBegin: (Course) -> Long?,
        targetOf: (Course, Long) -> Long,
    ): Pair<Course, Long>? = courses.asSequence()
        .filter { !it.signed }
        .mapNotNull { c -> parseBegin(c)?.let { b -> Triple(c, b, targetOf(c, b)) } }
        .filter { (_, begin, target) ->
            target > nowMs && nowMs <= AutoSignRandomizer.deadlineMs(begin)
        }
        .minByOrNull { it.third }
        ?.let { it.first to it.third }

    /**
     * 「此刻正在窗口里」的课次：随机时刻已过、但 deadline 未到。
     *
     * 这是 `selectNext` 的补集：`selectDue`（该立刻签的）与 `selectWindow`
     * （窗口还没关、随时可以补签的）合起来覆盖「随机时刻已过」的全部情形，
     * 而这个区间恰恰是 1.3.6 里「一次尝试失败 → 整窗静默丢失」的漏洞地带
     * （守护进程拉不起来时，没有任何机制会再回来试它）。
     *
     * 1.3.7 起由 SWEEP 槽闹钟消费：窗口每过 [SWEEP_SLOT_MS] 补签一次，
     * 直到成功 / 预算耗尽 / deadline。
     */
    fun selectWindow(
        courses: List<Course>,
        nowMs: Long,
        parseBegin: (Course) -> Long?,
        targetOf: (Course, Long) -> Long,
    ): Course? = courses.asSequence()
        .filter { !it.signed }
        .mapNotNull { c -> parseBegin(c)?.let { b -> Triple(c, b, targetOf(c, b)) } }
        .filter { (_, begin, target) ->
            target <= nowMs && nowMs < AutoSignRandomizer.deadlineMs(begin)
        }
        .minByOrNull { it.third }
        ?.first

    /** 最早的一个「尚未到期」的待签课 deadline；全部已过期返回 null。 */
    fun nextPostmortemAt(
        courses: List<Course>,
        nowMs: Long,
        parseBegin: (Course) -> Long?,
    ): Long? = courses.asSequence()
        .filter { !it.signed }
        .mapNotNull { c -> parseBegin(c)?.let(AutoSignRandomizer::deadlineMs) }
        .filter { it > nowMs }
        .minOrNull()

    /**
     * 「此刻是否该发前置提醒」的纯判定。
     *
     * 有效窗口 = 「提醒时刻（含 [toleranceMs] 的提前容差）」到
     * 「签到时刻（含 [lateGraceMs] 的迟到宽限）」。
     *
     * - 提前容差：部分 ROM 的非精确闹钟会提前几秒到几十秒触发；
     * - 迟到宽限：提醒闹钟被 Doze 推迟、醒来时随机时刻刚过的情形 —— 只要刚过不久，
     *   补发仍比整体丢失好（`remindNow` 传 2 分钟）；执行路径传 0，保持「开了签再喊
     *   即将签到就是噪音」的原语义。
     */
    fun remindDue(
        remindAtMs: Long,
        targetMs: Long,
        nowMs: Long,
        toleranceMs: Long,
        lateGraceMs: Long = 0L,
    ): Boolean = nowMs >= remindAtMs - toleranceMs && nowMs < targetMs + lateGraceMs

    /** SWEEP 槽的节拍：3 分钟。 */
    const val SWEEP_SLOT_MS = 3L * 60L * 1000L

    /** SWEEP 槽离 deadline 的最小余量：贴着 deadline 的尝试必失败（`nowMs <= deadline` 是硬条件）。 */
    const val SWEEP_DEADLINE_GUARD_MS = 60L * 1000L

    /**
     * 确定性 SWEEP 触发点：`min(target + k×3min, deadline - 1min)`，
     * 其中 `k = max(1, floor((nowMs - target) / 3min) + 1)`。
     *
     * ## 为什么必须是 (target, now) 的纯函数
     * 同一拍之内无论被重排多少次（守护循环每次重试后都会调 `ensureSchedule`），
     * 得到的都是**同一个**触发点 —— 不存在「每次调用都顺延到 now+3min、
     * 结果永不触发」的滑动陷阱（见 [AttendanceScheduler.scheduleAlarm] 的注释）。
     * SWEEP 真正触发过一次之后，`nowMs` 越过了当前槽，k 自然 +1，推进到下一拍。
     *
     * @return null 表示已无未来槽（窗口将尽）：调用方不得武装，终局交给 post-mortem。
     */
    fun sweepAtFor(targetMs: Long, deadlineMs: Long, nowMs: Long, leadMs: Long): Long? {
        val lastChance = deadlineMs - SWEEP_DEADLINE_GUARD_MS
        if (nowMs >= lastChance) return null
        val passed = ((nowMs - targetMs).coerceAtLeast(0L)) / SWEEP_SLOT_MS
        val at = minOf(targetMs + (passed + 1) * SWEEP_SLOT_MS, lastChance)
        return if (at <= nowMs + leadMs) null else at
    }

    /**
     * 安全网周期任务该不该保留（调用方已先判过「自动签到是否开启」）。
     *
     * 核心是第一条：**数据不是今天的 → 一律保留**。翻页时刻（00:02）本地缓存还是
     * 昨天的，「今天无待签」的判定不可信；此刻撤掉安全网，就再也没有执行体去刷新
     * 课表，gate 从此永远看到「无 pending」—— 自杀死锁。保留的代价只是一次
     * 可延迟的批处理任务，它自己会把数据刷新、再让 gate 收摊。
     */
    fun needsSafetyPeriodic(
        dataIsToday: Boolean,
        hasPending: Boolean,
        hasWindow: Boolean,
        hasNext: Boolean,
    ): Boolean = !dataIsToday || hasPending || hasWindow || hasNext
}

/**
 * 窗口内的有预算重试。
 *
 * 省电约定（1.3.7 起）：**秒级节奏**的重试不排任何新闹钟，只在「本次唤醒」已经拿到
 * 的电量豁免窗口（约 10 秒）以及守护服务进程内进行；进程被销毁则自然停止。
 * 1.3.7 之前，这条约定意味着「闹钟路径的一次瞬时失败 = 整窗静默丢失」——
 * 现在由 SWEEP 槽（[AutoSignSelector.sweepAtFor]，3 分钟一拍、确定性触发点）接管
 * 进程死掉之后的窗口内补签，受同一份 [AutoSignEngine.attempts] 预算约束，
 * 因此两条通道叠加也不会放大请求量。
 */
internal object AutoSignWindowRunner {

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 重试节奏：**先快后慢**。
     *
     * 前 [FAST_RETRY_ROUNDS] 拍是 3–5 秒的快速重试：绝大多数失败是瞬时的
     * （网络抖动、取码超时），几秒内补一拍几乎必成 —— 1.3.6 及之前统一等
     * 60–90 秒，正是用户反馈「实际签到时间比随机时刻晚一两分钟」的直接成因。
     * 之后回到 [slowRetryDelayMs] 的慢节奏：不再形成可识别的固定间隔。
     * 所有轮次共用同一份 [AutoSignEngine.attempts] 预算，请求总量有硬上限，
     * 快节奏不会放大总请求量，只是把预算花在成功率最高的头几分钟里。
     */
    fun retryDelayMs(round: Int): Long =
        if (round <= FAST_RETRY_ROUNDS) 3_000L + Random.nextLong(2_000L) else slowRetryDelayMs()

    /** 快速重试的拍数：第 2、3 次尝试紧跟首次失败，之后退回慢节奏。 */
    const val FAST_RETRY_ROUNDS = 2

    /** 慢节奏：60–90 秒，避免形成可识别的固定间隔。 */
    fun slowRetryDelayMs(): Long = Random.nextLong(60_000L, 90_000L)

    /** 进入重试循环并**等待它结束**（供守护服务这类有长生命周期协程的位置使用）。 */
    suspend fun run(context: Context, maxRounds: Int = AutoSignEngine.MAX_ATTEMPTS) {
        if (!mutex.tryLock()) return
        try {
            var round = 0
            while (round < maxRounds) {
                val app = context as? QingxinApp ?: return
                when (AutoSignEngine.runOnce(context, app)) {
                    EngineOutcome.RETRY -> {
                        round++
                        if (round >= maxRounds) return
                        delay(retryDelayMs(round))
                    }
                    else -> return
                }
            }
        } finally {
            mutex.unlock()
        }
    }

    /**
     * 在独立作用域里跑，并设**硬超时**。
     * 供 `BroadcastReceiver` 等无法长时间停留的位置使用：绝不超过 goAsync 的预算。
     */
    fun launch(context: Context, timeBudgetMs: Long = 20_000L) {
        val appContext = context.applicationContext
        scope.launch {
            withTimeoutOrNull(timeBudgetMs) {
                run(appContext, maxRounds = Int.MAX_VALUE)
            }
        }
    }
}
