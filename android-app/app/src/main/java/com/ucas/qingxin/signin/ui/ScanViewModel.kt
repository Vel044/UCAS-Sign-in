package com.ucas.qingxin.signin.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ucas.qingxin.signin.QingxinApp
import com.ucas.qingxin.signin.attendance.ScanPayload
import com.ucas.qingxin.signin.data.SignOutcome
import com.ucas.qingxin.signin.data.SignResult
import com.ucas.qingxin.signin.logging.LogKind
import com.ucas.qingxin.signin.network.ApiException
import com.ucas.qingxin.signin.network.QingxinApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「扫码打卡」页面的界面状态。
 *
 * 与 [ManualSignUiState] 一样是不可变快照：界面只读，变更只走 [ScanViewModel]。
 */
data class ScanUiState(
    /** 是否正在提交签到请求（此时相机取景仍在，但已经不再接受新的帧）。 */
    val submitting: Boolean = false,
    /** 识别结果回显（浅色提示，如「已识别：7 位节次编号」）。 */
    val recognized: String = "",
    /**
     * 二维码域名与本项目通道不一致时的说明。
     *
     * 这条提示存在的意义是**可解释性**：用户拿到的二维码指向 A，而我们实际请求的是 B，
     * 一旦失败，没有这句话就无法判断「到底是学校不认，还是应用把地址搞错了」。
     */
    val hostNote: String = "",
    /** 成功信息。 */
    val success: String = "",
    /** 失败原因。 */
    val error: String = "",
    /**
     * 二维码原文（原样，一个字符都没改）。
     *
     * 为什么要在界面上留一份：现场打卡只有一次机会 —— 用户站在班牌前，而码本身还在轮换。
     * 一旦失败，能解释「学校为什么拒」的原始证据就只有这段原文；没有它，
     * 用户和维护者都只能猜。1.3.1 的一次真机失败正是卡在这里（见
     * `docs/lecture-signin-research.md` §10.16）。
     */
    val rawText: String = "",
    /**
     * 本次**实际提交**到的地址（`query` 原样，`id` 只写参数名、不写用户编号）。
     *
     * 与 [rawText] 成对使用：前者是学校发出来的东西，这一条是我们发出去的东西。
     * 两者一比，就能看出参数名与时间戳有没有被我们动过 —— 这正是「已失效」
     * 这类含糊拒绝最需要的分辨依据。
     */
    val submittedUrl: String = "",
    /** `timestamp` 的来源说明（二维码自带 / 本校校时补入）。 */
    val timestampNote: String = "",
) {
    /**
     * 是否已有终态（成功或失败）。
     *
     * 界面据此**停掉取景**并换成结果卡片：继续对着同一张二维码取景没有意义，
     * 而且相机会一直吃电 —— 现场场景下用户可能盯着结果看很久。
     */
    val finished: Boolean get() = success.isNotEmpty() || error.isNotEmpty()
}

/**
 * 「扫码打卡」页面的 ViewModel。
 *
 * ## 职责边界
 * 它只做三件事：把二维码原文**分类**（[ScanPayload]）、决定**发不发请求**、
 * 把结果翻译成一句人能读懂的话。真正的提交在
 * [com.ucas.qingxin.signin.attendance.AttendanceRepository.signScanTarget]，
 * 与手动 / 自动签到共用同一把锁 —— 因此这一页**不需要**、也不该碰课程状态。
 *
 * ## 为什么不做「可信域名白名单」
 * 因为压根不发往别的域名：载荷解析出来的域名只用于提示，请求地址一律由
 * [QingxinApiService.buildScanUrl] 用本项目自己的 [QingxinApiService.BASE_URL] 拼出来。
 * 详见 [ScanPayload.DirectSign.qrHost] 的说明。
 */
class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val app: QingxinApp get() = getApplication()

    private val _ui = MutableStateFlow(ScanUiState())
    val ui: StateFlow<ScanUiState> = _ui.asStateFlow()

    /**
     * 取景闸门。
     *
     * 相机分析器从「解出一帧」到「回调处理完」之间没有任何同步，因此同一张码完全可能
     * 在一次签到请求还没回来时又触发一次。用原子闸门保证：一旦进入提交或已有终态，
     * 后续帧一律丢弃。**不能**只靠相机库的「扫到一次就停」——
     * 那个开关管的是后续帧的分析，管不住已经在飞行中的回调。
     */
    private val gate = AtomicBoolean(false)

    /**
     * 重新开始扫码。
     *
     * 同时清掉闸门：闸门是「本次取景」的状态，不是「本页面」的状态，
     * 否则用户点一次「重新扫码」之后相机就再也不响应了。
     */
    fun reset() {
        gate.set(false)
        _ui.value = ScanUiState()
    }

    /** 相机解出一段文本（可能来自任意二维码，因此必须先分类再决定动作）。 */
    fun onDecoded(raw: String) {
        if (!gate.compareAndSet(false, true)) return
        if (!app.isReady) {
            fail("应用尚未初始化完成，请稍后重试")
            return
        }
        // 原文先落界面：下面每一条分支（含 Unknown 与 NotSign）都要能回看它。
        _ui.update { it.copy(rawText = raw) }
        // 现场诊断：原文与分类都落盘。分类决定「发不发请求」，因此两者必须成组出现 ——
        // 只记原文会看不出「为什么没发请求」，只记分类则丢了原始证据。
        app.signLog.append(LogKind.SCAN, "原文" to raw)
        // 分类顺序见 ScanPayload 的说明，与小程序逐条对应。
        when (val payload = ScanPayload.parse(raw)) {
            is ScanPayload.NotSign -> {
                app.signLog.append(
                    LogKind.CLASSIFY,
                    "判定" to "NotSign / ${payload.kind.label}",
                    "动作" to "不发请求",
                )
                fail("扫到的是${payload.kind.label}，不是签到码。${payload.kind.hint}")
            }

            is ScanPayload.Unknown -> {
                app.signLog.append(LogKind.CLASSIFY, "判定" to "Unknown", "动作" to "不发请求")
                fail("这张二维码不是轻新课堂的签到码，请对准班牌上的签到码再扫一次")
            }

            is ScanPayload.DirectSign -> {
                _ui.update {
                    it.copy(
                        recognized = "已识别：${payload.label}",
                        hostNote = hostNoteFor(payload.qrHost),
                    )
                }
                app.signLog.append(
                    LogKind.CLASSIFY,
                    "判定" to "DirectSign / ${payload.label}",
                    "是否原样重放" to if (payload.isReplay) "是（照抄二维码 query）" else "否（由本应用拼请求）",
                    "二维码自带时间戳" to (payload.qrTimestamp ?: "无"),
                    "动作" to "发请求",
                )
                viewModelScope.launch { submitDirect(payload) }
            }

            is ScanPayload.PlatformSign -> {
                _ui.update {
                    it.copy(
                        recognized = "已识别：7.0 平台签到码",
                        hostNote = hostNoteFor(payload.qrHost),
                        submittedUrl = previewOf(payload.path, payload.query),
                        timestampNote = "7.0 通道按原样重放，本项目不追加任何参数",
                    )
                }
                app.signLog.append(
                    LogKind.CLASSIFY,
                    "判定" to "PlatformSign / 7.0 平台签到码",
                    "动作" to "发请求",
                )
                viewModelScope.launch { runSign(payload.path, payload.query) }
            }
        }
    }

    /**
     * 旧通道：必要时先补一个学校校时，再提交。
     *
     * 只有二维码**没带** `timestamp` 时才去校时（见 [ScanPayload.DirectSign.withTimestamp]）：
     * 校时是一次额外的网络往返，二维码自己带了时间戳时它纯属白等。
     */
    private suspend fun submitDirect(payload: ScanPayload.DirectSign) {
        _ui.update { it.copy(submitting = true) }
        val query = if (payload.needsTimestamp) {
            val schoolNowMs = try {
                app.qrTimeline.syncClock().schoolNowMs
            } catch (e: ApiException) {
                fail(e.message ?: "学校校时失败，请重试")
                return
            }
            payload.withTimestamp(schoolNowMs)
        } else {
            payload.submitQuery
        }
        _ui.update {
            it.copy(
                submittedUrl = previewOf(payload.path, query),
                timestampNote = timestampNoteFor(payload),
            )
        }
        runSign(payload.path, query)
    }

    /**
     * `timestamp` 的来源说明 —— 扫码现场最值得交代的一句话。
     *
     * 三种情形必须能分开看：码里本来就有（原样带着）、码里没有但我们照抄重放（**不补**）、
     * 请求由我们拼所以要自己补（补）。学校那句「签到码已失效」与这条线的相关性最高，
     * 因此它必须出现在结果卡片上，而不是只写在注释里。
     */
    private fun timestampNoteFor(payload: ScanPayload.DirectSign): String = when {
        payload.isReplay && payload.qrTimestamp != null ->
            "timestamp 取自二维码，原样重放，未增删任何参数"
        payload.isReplay ->
            "二维码未带 timestamp，按原样重放（不代学校补）"
        else ->
            "二维码只是一个裸编号，请求由本应用拼出，已用本校校时补入 timestamp"
    }

    private suspend fun runSign(path: String, query: String) {
        _ui.update { it.copy(submitting = true) }
        try {
            val result = app.attendanceRepository.signScanTarget(path, query)
            app.signLog.append(
                LogKind.OUTCOME,
                "判定" to result.outcome.name,
                "STATUS" to result.status,
                "ERRCODE" to result.errCode,
                "提示" to result.message,
            )
            if (result.outcome == SignOutcome.SIGNED) {
                _ui.update {
                    it.copy(
                        submitting = false,
                        success = result.message.ifBlank { "签到成功" },
                    )
                }
            } else {
                fail(explain(result))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            val loginExpired = e.code == "LOGIN_EXPIRED" || e.code == "LOGIN_REJECTED"
            app.signLog.append(LogKind.OUTCOME, "异常" to "${e.code}: ${e.message ?: ""}")
            fail(if (loginExpired) "登录已过期，请返回首页重新登录" else e.message ?: e.code)
        } catch (e: Exception) {
            app.signLog.append(LogKind.OUTCOME, "异常" to "${e::class.java.simpleName}: ${e.message ?: ""}")
            fail(e.message ?: "签到失败")
        }
    }

    private fun fail(message: String) {
        _ui.update { it.copy(submitting = false, success = "", error = message) }
    }

    /**
     * 失败原因：能给可执行动作的就给，给不出才只回显学校的原话。
     *
     * 「签到码已失效」必须由我们补一句：学校的签到码是**短命**的 —— 原版实现里
     * 二维码刷新上限就是 5 秒（[QingxinApiService.QR_REFRESH_CAP_MS]，
     * 由原版反编译确认），所以它那句「请刷新后重试」对学生等于没说。
     * 现场真正该做的是：别停下来看提示，直接对准班牌最新一屏重扫。
     */
    private fun explain(result: SignResult): String {
        val base = result.message.ifBlank { outcomeMessage(result.outcome) }
        if (result.outcome != SignOutcome.QR_EXPIRED) return base
        return "$base\n班牌的签到码大约每 5 秒轮换一次，请对准最新一屏立刻重扫。"
    }

    /**
     * 预览本次提交到的地址。
     *
     * 这里**重新拼一次**而不是把 URL 从 API 层回传：目的在于这一页不依赖任何请求结果
     * 也能说清「发到哪」，且失败时（拿不到响应）也能说清。
     * `id` 只写参数名、不写用户编号 —— 这段文字是给人看甚至给人转发的，
     * 没必要把身份一起送出去，而诊断需要的信息（参数名、时间戳）一个都不少。
     */
    private fun previewOf(path: String, query: String): String {
        val url = app.api.buildScanUrl(path, query)
        val sep = if ('?' in url) "&" else "?"
        return "$url${sep}id=（本人学号）"
    }

    /** 二维码域名与本项目通道不一致时给一句人话；一致或没写域名时返回空串。 */
    private fun hostNoteFor(qrHost: String?): String {
        if (qrHost.isNullOrBlank()) return ""
        val ours = QingxinApiService.BASE_URL.toHttpUrlOrNull()?.host ?: return ""
        if (qrHost.equals(ours, ignoreCase = true)) return ""
        return "二维码指向 $qrHost，已按轻新课堂的行为改用本校通道（$ours）"
    }

    private fun outcomeMessage(outcome: SignOutcome): String = when (outcome) {
        SignOutcome.SIGNED -> "签到成功"
        SignOutcome.QR_EXPIRED -> "签到码已失效，请对准班牌上最新的二维码重扫"
        SignOutcome.OUTSIDE_SIGN_WINDOW -> "当前不在签到时间内"
        SignOutcome.SIGN_RESULT_UNKNOWN -> "签到结果未知，请回首页刷新课程状态后再确认"
    }
}
