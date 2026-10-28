package com.ucas.qingxin.signin.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ucas.qingxin.signin.QingxinApp
import com.ucas.qingxin.signin.attendance.ScanIdMapping
import com.ucas.qingxin.signin.attendance.ScanPayload
import com.ucas.qingxin.signin.attendance.ScanTimestampPolicy
import com.ucas.qingxin.signin.data.SignOutcome
import com.ucas.qingxin.signin.data.SignResult
import com.ucas.qingxin.signin.logging.LogKind
import com.ucas.qingxin.signin.network.ApiException
import com.ucas.qingxin.signin.network.QingxinApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** 连扫模式下一次尝试的简短结果（连扫时最有用的就是「刚才那几次成没成」）。 */
data class ScanAttempt(val ok: Boolean, val summary: String)

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
     * 本次**实际提交**到的地址与请求体（`query` 原样，`id` 只写参数名、不写用户编号）。
     *
     * 与 [rawText] 成对使用：前者是学校发出来的东西，这一条是我们发出去的东西。
     * 两者一比，就能看出参数名与时间戳有没有被我们动过 —— 这正是「已失效」
     * 这类含糊拒绝最需要的分辨依据。`id` 在表单体里（不在 URL 上，见
     * [QingxinApiService.submitScanSign]），因此预览把体也单独写出来。
     */
    val submittedUrl: String = "",
    /**
     * 本次取景的**实测**识别耗时（人话，如「取景 0.6 秒后第 3 帧解出 · …」）。
     *
     * ## 为什么这件事要摊给用户看
     * 现场反馈只有「识别过慢」四个字，而这四个字对应两条完全不同的处置：解码本身慢
     * （要改解码配置）还是根本没对准 / 没对焦上（只能等或提示）。没有数字时两者无法区分，
     * 于是每一次「慢」都会变成一次盲猜。这一行连同 [com.ucas.qingxin.signin.history.SignRecord]
     * 一起，让下一份现场记录自己就能分辨（判读方法见 [ScanPerf.hitFrame]）。
     */
    val scanPerf: String = "",
    /** `timestamp` 的来源说明（二维码自带 / 本校校时补入）。 */
    val timestampNote: String = "",
    /**
     * 连扫模式（**默认开**）。
     *
     * ## 为什么需要它
     * 学校的班牌签到码**大约每 5 秒轮换一次**（[QingxinApiService.QR_REFRESH_CAP_MS]，
     * 由原版反编译确认），而用户常常是在码刚过期的那一刻扫上的 —— 学校只会回
     * 「请刷新后重试」。此时真正该做的动作是「什么都别动，继续对着班牌」，
     * 而不是「看提示 → 点继续扫码 → 重新对焦」。连扫模式就是把后三步省掉：
     * 失败 → 短暂回显 → **自动重新开始取景**。
     *
     * ## 为什么默认开（2026-10-14 起）
     * 现场连续三次失败记录（`docs/sign-records.txt`）与用户的复述
     * （「识别过慢，码过期后才扫完」）指向同一件事：**第一次就扫上的概率不够高**，
     * 而每一次失败都要用户手动重来一遍，一次手动重来又要重新对焦、
     * 又要再赌一次码还没轮换。默认关闭等于把这个代价固定收取。
     *
     * ## 成功为什么不重扫（本次同时改的）
     * 连扫只在**失败**后自动重开取景；一旦成功就停在结果卡片上。
     * 原来的语义是「成功也重新取景」，那样用户签完还站在那里，班牌下一帧新码会被
     * 再提交一次（去重窗口只有 3 秒，挡不住 5 秒后的新码）—— 同一场课重复签到。
     * 见 [settle]。
     */
    val continuous: Boolean = true,
    /** 连扫模式下的瞬时回显（成功 / 失败 / 「同一张码，已跳过」都写这里）。 */
    val flash: String = "",
    /** [flash] 是成功还是失败（只影响配色）。 */
    val flashOk: Boolean = false,
    /** 连扫模式下的本次会话结果，新的在前。 */
    val session: List<ScanAttempt> = emptyList(),
) {
    /**
     * 是否已有终态（成功或失败），界面据此**停掉取景**并换成结果卡片。
     *
     * 判据只有「有没有终态」一条，不再看连扫开关：连扫模式下**失败**没有终态
     * （失败进 [flash] / [session]，取景必须留着），而**成功**有终态（见 [continuous]：
     * 成功就该收工，继续扫只会重复签到）。因此这一条同时满足两种模式。
     *
     * 继续对着同一张二维码取景没有意义，而且相机会一直吃电 ——
     * 现场场景下用户可能盯着结果看很久。
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
     *
     * 连扫模式下这个闸门会被**主动打开**（见 [settle]）：那正是连扫的定义。
     */
    private val gate = AtomicBoolean(false)

    /**
     * 连扫模式的去重表：原文 → 上次提交时刻。
     *
     * ## 为什么去重是连扫模式的**必需**项，不是优化
     * 连扫会在 ~2 秒后自动重新开始取景。若班牌此刻还没刷新，我们会立刻又解出**同一张码**、
     * 又发一发同样的请求 —— 每 2 秒一次，一直打到班牌刷新或用户移开镜头。
     * 学校的签到接口对重复提交并不友好，而且这纯属白烧请求额度。
     *
     * ## 窗口为什么取 3 秒
     * 只比 [FLASH_MS] 略长、明确短于签到码的 5 秒轮换周期。取长了会误伤
     * 「班牌就是给了一张静态码」的情况（讲座班牌疑似如此），那等于把功能关掉。
     *
     * 用 [ConcurrentHashMap]：相机回调的线程由扫码库决定，不该假设它在主线程。
     */
    private val submitted = ConcurrentHashMap<String, Long>()

    /**
     * 重新开始扫码。
     *
     * 同时清掉闸门：闸门是「本次取景」的状态，不是「本页面」的状态，
     * 否则用户点一次「重新扫码」之后相机就再也不响应了。
     *
     * **不清** [submitted]：去重窗口是按时长算的，跨过这一次重置仍然有效 ——
     * 否则「失败 → 继续扫码 → 对着同一张码再扫」会绕过它，而那正是要挡的行为。
     */
    fun reset() {
        gate.set(false)
        _ui.value = ScanUiState(continuous = _ui.value.continuous)
    }

    /**
     * 切换连扫模式。
     *
     * 打开时若已经有一张结果卡片在屏上，就顺手回到取景 ——
     * 用户点「连扫」的意图是「继续扫」，不是「留着这张卡片」。
     */
    fun setContinuous(on: Boolean) {
        val wasFinished = _ui.value.finished
        _ui.update {
            if (on && wasFinished) {
                ScanUiState(continuous = true)
            } else {
                it.copy(continuous = on, flash = if (on) it.flash else "", session = if (on) it.session else emptyList())
            }
        }
        if (on) gate.set(false)
    }

    /**
     * 相机解出一段文本（可能来自任意二维码，因此必须先分类再决定动作）。
     *
     * [perf] 是这一帧的实测识别耗时（由 [FastScanAnalyzer] 计量）。它**必须在这里落盘**：
     * 现场「扫得慢」是一句主观描述，而帧数 / 单帧毫秒是唯一能复核的证据。
     * 三条分支（含不发请求的 [ScanPayload.NotSign]）都要留下它 ——
     * 「扫了半天，扫出来是别的东西」同样是需要解释的现场。
     */
    /**
     * 相册选图入口：装载 → 解码 → 走与相机**完全相同**的 [onDecoded] 管线。
     *
     * 「相同」是刻意的：分类（[ScanPayload.parse]）、时间戳策略、标识补入、
     * 提交、记录与日志，一条分支都不该有第二份实现 —— 相册里的图与取景框里的
     * 码，对后端是同一个东西。
     *
     * 反馈按模式分流：连扫模式失败只写瞬时回显（不打断取景）；单发模式用
     * 结果卡片。识别不出二维码时同样落日志 —— 「选了图但没识别出」也是现场。
     */
    fun onImageSelected(uri: Uri?) {
        if (uri == null) return
        if (_ui.value.submitting) return
        viewModelScope.launch {
            _ui.update { it.copy(flash = "正在识别图片…", flashOk = true) }
            val raw = withContext(Dispatchers.IO) {
                val bitmap = ScanImageQr.load(app, uri) ?: return@withContext null
                ScanImageQr.decode(bitmap)
            }
            if (raw == null) {
                app.signLog.append(
                    LogKind.SCAN,
                    "来源" to "相册图片",
                    "判定" to "未识别出二维码",
                )
                if (_ui.value.continuous) {
                    _ui.update {
                        it.copy(flash = "图片里没有识别出二维码，请换一张或直接对准班牌", flashOk = false)
                    }
                } else {
                    fail("图片里没有识别出二维码。请确认图片里有清晰的签到码，或直接对准班牌扫码")
                }
                return@launch
            }
            app.signLog.append(LogKind.SCAN, "来源" to "相册图片")
            onDecoded(raw)
        }
    }

    fun onDecoded(raw: String, perf: ScanPerf? = null) {
        // 连扫去重：同一张码在窗口内只提交一次（理由见 submitted）。
        // 必须在取闸门**之前**做，且跳过时**不占闸门** —— 这一帧应当被当作没看见。
        //
        // 正在提交时不写提示：那一行此刻显示的是「正在提交签到…」，
        // 用「已跳过」把它盖掉会让用户以为请求根本没发出去。
        if (_ui.value.continuous && isDuplicate(raw)) {
            if (!_ui.value.submitting) {
                _ui.update { it.copy(flash = SKIP_NOTE, flashOk = false) }
            }
            return
        }
        if (!gate.compareAndSet(false, true)) return
        if (!app.isReady) {
            fail("应用尚未初始化完成，请稍后重试")
            return
        }
        // 原文先落界面：下面每一条分支（含 Unknown 与 NotSign）都要能回看它。
        _ui.update { it.copy(rawText = raw) }
        // 识别耗时与原文一起落界面 / 落盘：它是对「这张码怎么来的」的补充说明。
        if (perf != null) {
            val human = perf.human()
            _ui.update { it.copy(scanPerf = human) }
            app.signLog.append(LogKind.SCAN, "识别耗时" to human)
        }
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
                viewModelScope.launch { submitDirect(payload, raw) }
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
                viewModelScope.launch {
                    runSign(payload.path, payload.query, "", "7.0 平台签到码", raw, "", decision = null)
                }
            }
        }
    }

    /**
     * 旧通道：先按 [ScanTimestampPolicy] 决定提交哪个 `timestamp`，再提交。
     *
     * 校时不是白花的往返：二维码自带的时间戳够了就直接照抄（不取校时），
     * 只有当它**看起来已经陈旧**时才去取一次校时做精确判断（见
     * [ScanTimestampPolicy.looksStaleByLocalClock]）。
     */
    private suspend fun submitDirect(payload: ScanPayload.DirectSign, raw: String) {
        markSubmitting()
        val qrTsMs = payload.qrTimestampMs

        // 廉价筛查：按**本机**时钟看这张码还很新，就照抄 —— 与小程序逐字一致，
        // 也不为一次现场签到多花一个校时往返（绝大多数现场扫码都属于这种）。
        // 只有「看起来已经旧了」才去取校时做精确判断；本机时钟不准只会让我们
        // 多取一次校时，不会让结论出错（最终判定一律以校时为准）。
        val schoolNowMs = if (ScanTimestampPolicy.looksStaleByLocalClock(qrTsMs, System.currentTimeMillis())) {
            try {
                app.qrTimeline.syncClock().schoolNowMs
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                // 校时拿不到不等于签到失败：二维码自带时间戳时我们仍有一个
                // 「学校自己发出来的」值可以照抄（见策略的不变量 2）；
                // 1.3.9 起还有更下一级的校准推算（见下）。
                null
            }
        } else {
            null
        }

        // 现场校时不可用时的最后一档：按最近一次校准偏差（学校钟 ↔ 本机钟）
        // 从本机时间推算学校时间 —— 零网络、毫秒级，失败形态从「必被拒的旧值」
        // 变成「推算的新值」。只在上面拿不到权威校时时才取。
        val estimatedSchoolNowMs = if (schoolNowMs == null) {
            runCatching { app.qrTimeline.calibratedSchoolNowOrNull() }.getOrNull()
        } else {
            null
        }

        val decision = ScanTimestampPolicy.resolve(qrTsMs, schoolNowMs, estimatedSchoolNowMs)
        if (decision == null) {
            fail("学校校时失败，请重试")
            return
        }
        // 32 位标识 → 7 位节次号的补入（见 ScanIdMapping 的说明）。
        // 只在**本机课表能证明是同一节课**时才补；证明不了就一个字节都不改。
        val mappedSchedId = resolveSchedIdFor(payload)
        var query = payload.withTimestampOf(decision.valueMs)
        if (mappedSchedId != null) query = ScanIdMapping.withCourseSchedId(query, mappedSchedId)

        val note = timestampNoteFor(payload, decision) + schedIdNote(payload, mappedSchedId)
        _ui.update {
            it.copy(
                submittedUrl = previewOf(payload.path, query),
                timestampNote = note,
            )
        }
        runSign(payload.path, query, payload.identifier, payload.label, raw, note, decision)
    }

    /**
     * 二维码的 32 位标识 → 本机课表里同一节课的 7 位节次号（找不到为 `null`）。
     *
     * ## 为什么查「今天的课表」就够
     * 班牌上的签到码只会出现在**正在上的那节课**，而它的节次必然在今天课表里
     * （一键签到也依赖同一份数据）。用 [com.ucas.qingxin.signin.course.CourseRepository.courses]
     * 的当前快照，读的是内存值、不触网 —— 现场扫码这条路的延迟必须保持在毫秒级。
     *
     * ## 快照为空时要**先取一次**，否则会说出假话
     * 用户若从通知/快捷方式直接进扫码页，课表可能一次都没加载过（`courses.value == null`）。
     * 此时「没找到」并不等于「课表里没有」，若照旧写进记录，会把归因引向完全错误的方向
     * （让人去查班牌属于哪门课，而真相只是本地还没拉课表）。
     * 因此只在快照为空时才补一次 `loadToday()`：这是唯一会为此多花一个请求的情形，
     * 且只发生在「本来就注定失败」的 32 位标识上。取不到就照实返回 `null`，
     * 记录里那句话仍然成立（我们确实无法证明）。
     *
     * ## 为什么只认 `uuid`
     * `Course` 同时带着节次层 `id`（7 位）与课表层 `uuid`（32 位），二维码里的
     * 32 位标识与后者同源（见 `Course.uuid` 的说明）。只有 `uuid` 能证明
     * 「二维码指的就是这一节课」—— 用别的字段猜会把别的课签掉。
     */
    private suspend fun resolveSchedIdFor(payload: ScanPayload.DirectSign): String? {
        val uuid = payload.signCode.timeTableId
        if (uuid.isEmpty()) return null
        val known = app.courseRepository.courses.value
        val courses = if (known == null) {
            try {
                app.courseRepository.loadToday().courses
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                emptyList()
            }
        } else {
            known.courses
        }
        return ScanIdMapping.resolveSchedId(uuid, courses.map { it.uuid to it.id })
    }

    /**
     * 「标识补入」这一句说明 —— 与 `timestamp` 的来源说明并列，因为两者都是
     * 我们对学校所发参数做过的改动，而现场归因恰恰要靠「我们到底动了什么」。
     *
     * 三种情形分开写：补成功了 / 想补但课表里没有 / 根本不需要补。
     * 中间那条尤其要写 —— 它解释了为什么这次请求与二维码原文一模一样，
     * 而这句话正是「该去核对课表还是该去核对班牌」的分岔点。
     */
    private fun schedIdNote(payload: ScanPayload.DirectSign, mappedSchedId: String?): String = when {
        mappedSchedId != null ->
            "；二维码是 32 位标识，已按今天课表补入 7 位节次号 $mappedSchedId 一并提交" +
                "（二维码原值保留）"
        payload.signCode.timeTableId.isNotEmpty() ->
            "；二维码是 32 位标识，但今天课表里找不到对应节次，只能原样重放"
        else -> ""
    }

    /**
     * `timestamp` 的来源说明 —— 扫码现场最值得交代的一句话。
     *
     * 四种情形必须能分开看，因为它们对「学校为什么拒」指向完全不同的结论：
     *
     * | 情形 | 说明 | 指向 |
     * | --- | --- | --- |
     * | 码里本来就有、且够新 | **原样重放**，未增删任何参数 | 参数不可能是原因 |
     * | 码里本来就有、但已陈旧 | 已改用本校校时提交 | 班牌不再刷新，或班牌时钟偏了 |
     * | 码里没有 | 校时不可用，沿用原值 | 我们没动过它 |
     * | 请求由我们拼（裸编号） | 已用本校校时补入 | —— |
     */
    private fun timestampNoteFor(
        payload: ScanPayload.DirectSign,
        decision: ScanTimestampPolicy.Decision,
    ): String = when {
        decision.confidence == ScanTimestampPolicy.Decision.Confidence.NO_QR_TIMESTAMP ->
            "二维码只是一个裸编号，请求由本应用拼出，已用本校校时补入 timestamp"
        decision.confidence == ScanTimestampPolicy.Decision.Confidence.CALIBRATED_CLOCK ->
            // 校准推算必须如实交代：它的时基是「本机钟 + 校准偏差」，
            // 设备时钟在校准之后被改过的话这个值会整体偏移。
            when {
                decision.qrStale ->
                    "现场校时不可用，已按本机时钟与最近校准偏差推算学校时间提交" +
                        "（二维码时间戳已过去 ${decision.humanQrAge()}）"
                decision.qrAgeMs != null ->
                    "现场校时不可用，已按本机时钟与最近校准偏差推算学校时间核对" +
                        "（距推算校时 ${decision.humanQrAge()}），原样重放，未增删任何参数"
                else ->
                    "现场校时不可用，已按本机时钟与最近校准偏差推算学校时间补入 timestamp"
            }
        decision.confidence == ScanTimestampPolicy.Decision.Confidence.NO_SCHOOL_CLOCK ->
            "校时不可用，timestamp 沿用二维码原值（不使用本机时间）"
        decision.qrStale ->
            "二维码自带的时间戳已过去 ${decision.humanQrAge()}，" +
                "超过班牌 ${ScanTimestampPolicy.REPLAY_TOLERANCE_MS / 1000} 秒的生成周期，" +
                "已改用本校校时提交"
        else ->
            "timestamp 取自二维码（距本校校时 ${decision.humanQrAge()}），原样重放，未增删任何参数"
    }

    private suspend fun runSign(
        path: String,
        query: String,
        code: String,
        kind: String,
        raw: String,
        note: String,
        decision: ScanTimestampPolicy.Decision?,
    ) {
        markSubmitting()
        try {
            val result = app.attendanceRepository.signScanTarget(
                path = path,
                query = query,
                code = code,
                kind = kind,
                raw = raw,
                note = note,
            )
            app.signLog.append(
                LogKind.OUTCOME,
                "判定" to result.outcome.name,
                "STATUS" to result.status,
                "ERRCODE" to result.errCode,
                "提示" to result.message,
                // 学校原话与我们的转述分开记：现场归因靠的是前者。
                "学校原话" to result.schoolMessage,
            )
            if (result.outcome == SignOutcome.SIGNED) {
                settle(ok = true, text = result.message.ifBlank { "签到成功" })
            } else {
                settle(ok = false, text = explain(result, decision))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            val loginExpired = e.code == "LOGIN_EXPIRED" || e.code == "LOGIN_REJECTED"
            app.signLog.append(LogKind.OUTCOME, "异常" to "${e.code}: ${e.message ?: ""}")
            settle(
                ok = false,
                text = if (loginExpired) "登录已过期，请返回首页重新登录" else e.message ?: e.code,
            )
        } catch (e: Exception) {
            app.signLog.append(LogKind.OUTCOME, "异常" to "${e::class.java.simpleName}: ${e.message ?: ""}")
            settle(ok = false, text = e.message ?: "签到失败")
        }
    }

    /**
     * 落定一次尝试。
     *
     * ## 三句话
     * 1. **成功 = 收工**：不分成败模式，一律写进结果卡片。成功了还继续对着班牌扫，
     *    只会把班牌 5 秒后的新码再提交一遍 —— 同一场课重复签到，纯属白烧请求。
     * 2. **连扫失败 = 不停取景**：失败只写瞬时回显与会话列表，取景留着，
     *    短暂停留后自动重开（连扫的全部意义）。
     * 3. **单发失败 = 结果卡片**：停取景，等用户自己决定要不要再来一次。
     *
     * ## 为什么成功这条不能塞进 flash
     * [ScanUiState.finished] 只看 [ScanUiState.success] / [ScanUiState.error]。
     * 若成功也走 flash，连扫模式下成功就**不会**收起取景，
     * 于是「签上了却还在扫」—— 见第 1 条。
     */
    private fun settle(ok: Boolean, text: String) {
        val attempt = ScanAttempt(ok = ok, summary = text.lineSequence().first().take(48))
        if (ok) {
            // flash 也要一并清掉：连扫模式下它此刻写的正是「正在提交签到…」，
            // 而结果卡片与它在同一个 Box 里，不清掉就会有一行字压在卡片上。
            _ui.update { it.copy(submitting = false, success = text, error = "", flash = "") }
            return
        }
        _ui.update { s ->
            if (s.continuous) {
                s.copy(
                    submitting = false,
                    flash = text,
                    flashOk = false,
                    session = (listOf(attempt) + s.session).take(MAX_SESSION),
                )
            } else {
                s.copy(submitting = false, success = "", error = text)
            }
        }
        if (!_ui.value.continuous) return
        viewModelScope.launch {
            delay(FLASH_MS)
            // 用户可能在这两秒里关掉了连扫：那就把结果补写进结果卡片，
            // 否则这次尝试在界面上会凭空消失（用闭包里的 text，不读已被清掉的 flash）。
            if (!_ui.value.continuous) {
                _ui.update { it.copy(success = "", error = text) }
                return@launch
            }
            // 放闸门并开始下一轮 —— 这就是「连扫」的全部动作。
            _ui.update { it.copy(flash = "") }
            gate.set(false)
        }
    }

    private fun fail(message: String) = settle(ok = false, text = message)

    /**
     * 记下「已经在提交」。
     *
     * 连扫模式下**顺带**把这句话写进瞬时回显，因为那种模式不许出现全屏遮罩
     * （见 [ScanScreen] 的 `SubmittingOverlay`）：每次请求都把取景画面糊掉一次，
     * 在现场对着班牌连扫时是纯粹的干扰，而用户需要知道的只是「这次发出去了没有」。
     */
    private fun markSubmitting() {
        _ui.update {
            it.copy(
                submitting = true,
                flash = if (it.continuous) SUBMITTING_NOTE else it.flash,
            )
        }
    }

    /** 连扫去重：窗口内出现过的同一段原文算重复。 */
    private fun isDuplicate(raw: String): Boolean {
        val now = System.currentTimeMillis()
        val previous = submitted[raw]
        if (previous != null && now - previous < DEDUPE_MS) return true
        submitted[raw] = now
        return false
    }

    /**
     * 失败原因：能给可执行动作的就给，给不出才只回显学校的原话。
     *
     * 「已失效」这条必须分岔，因为**我们是否已经为它改过参数**决定了下一句话：
     *
     * - **改过**（二维码自带的时间戳明显陈旧，[ScanTimestampPolicy] 已改用本校校时）→
     *   参数取值已经试过更好的那个，问题不在我们发出去的东西上，而在这张码本身。
     *   这时如实告诉用户它有多旧，别再让他对着同一块不再刷新的班牌反复重扫。
     * - **没改过**（码本来就很新）→ 学校那句「请刷新后重试」确实是字面意思：
     *   二维码刷新上限就是 5 秒（[QingxinApiService.QR_REFRESH_CAP_MS]，
     *   由原版反编译确认），别停下来看提示，直接对准最新一屏重扫。
     */
    private fun explain(result: SignResult, decision: ScanTimestampPolicy.Decision?): String {
        val base = result.message.ifBlank { outcomeMessage(result.outcome) }
        // 学校原话必须附上（2026-10-19 补记）。
        //
        // 现场记录里只剩我们那句「请确认上课时间和课程状态」—— 那是**我们编的**转述，
        // 而真正能解释「它到底在拒绝什么」的是学校自己那几个字。两者一起给出，
        // 一次现场失败才能被离线读懂，不必再让用户去翻调试日志。
        // `base` 里已经包含原话时不重复（`message` 有时就是原样引用）。
        val withSchoolVoice =
            if (result.schoolMessage.isBlank() || base.contains(result.schoolMessage)) base
            else "$base\n学校原话：${result.schoolMessage}"

        if (result.outcome != SignOutcome.QR_EXPIRED) return withSchoolVoice
        if (decision?.qrStale == true) {
            val age = decision.humanQrAge()
            return "$withSchoolVoice\n这张码上的时间戳已经过去 $age（班牌大约每 5 秒就该换一帧），" +
                "应用已改用本校校时重发，仍被学校判为失效 —— 麻烦换一块正在刷新的班牌，" +
                "或请老师重新开一次签到。"
        }
        return "$withSchoolVoice\n班牌的签到码大约每 5 秒轮换一次，请对准最新一屏立刻重扫。"
    }

    /**
     * 预览本次提交到的地址与请求体。
     *
     * 这里**重新拼一次**而不是把 URL 从 API 层回传：目的在于这一页不依赖任何请求结果
     * 也能说清「发到哪」，且失败时（拿不到响应）也能说清。
     * `id` 在表单体里（不在 URL 上，见 [QingxinApiService.submitScanSign]），预览把它
     * 连同经纬度占位一起单独写一行；编号只写参数名、不写本人值 —— 这段文字是给人看
     * 甚至给人转发的，没必要把身份一起送出去，而诊断需要的信息（参数名、时间戳）一个都不少。
     */
    private fun previewOf(path: String, query: String): String =
        app.api.buildScanUrl(path, query) + "\n表单体：id=（本人学号）&longitude=0&latitude=0"

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
        SignOutcome.TIMESTAMP_REJECTED -> "学校判定时间戳异常，请稍后重扫（下次会重新校时）"
    }

    private companion object {
        /** 连扫模式下每次结果在屏上停留多久；到点自动重新开始取景。 */
        const val FLASH_MS = 2_200L

        /** 连扫去重窗口：略长于 [FLASH_MS]，明显短于签到码 5 秒的轮换周期。 */
        const val DEDUPE_MS = 3_000L

        /** 连扫会话里最多留几条结果。 */
        const val MAX_SESSION = 8

        /** 同一张码被跳过时的提示。它本身是「没发请求」的证据，必须说出来。 */
        const val SKIP_NOTE = "同一张码，本次跳过（等班牌刷新）"

        /** 连扫模式下「正在提交」的瞬时回显（那种模式不出现全屏遮罩）。 */
        const val SUBMITTING_NOTE = "正在提交签到…"
    }
}

/** 扫码载荷里用于记账的标识：具名参数优先，退化到裸编号。 */
private val ScanPayload.DirectSign.identifier: String
    get() = signCode.courseSchedId.ifEmpty { signCode.timeTableId }
