package com.ucas.qingxin.signin.ui

import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.google.zxing.Result
import com.king.camera.scan.AnalyzeResult
import com.king.camera.scan.analyze.Analyzer
import com.king.zxing.DecodeConfig
import com.king.zxing.analyze.MultiFormatAnalyzer

/**
 * 一次取景会话里解码器的实测表现。
 *
 * ## 为什么要把这些数字带到界面上
 * 现场反馈是「识别过慢，二维码过期后才扫完」，而这条描述**没法直接归因**：
 * 慢可能来自解码本身（每帧要几十毫秒），也可能来自别处 —— 相机刚开、自动曝光 / 对焦
 * 还没收敛、用户还没对准。三者的处置完全不同（前者要改解码配置，后两者只能等 / 提示），
 * 但没有数字时它们长得一模一样。
 *
 * 于是这里记下三件可分辨的事：
 * - [wallMs]：从取景开始到**首次**解出为止的墙钟耗时 —— 这就是用户说的「慢」；
 * - [hitFrame]：第几帧才解出。**这一条是分水岭**：若为 1~3 帧，说明解码本身极快，
 *   时间花在「还没对准 / 还没对焦上」；若为几十帧，才是解码真的慢。
 * - [avgMs] / [maxMs]：单帧解码成本，用来判断是否该继续收紧解码配置。
 *
 * 单帧成本的度量口径：**从相机分析线程拿到一帧到它给出结果**，含 YUV 转换、旋转、
 * 裁剪、二值化与 ZXing 解码 —— 即这帧图真正占掉的全部 CPU 时间（见 [FastScanAnalyzer]）。
 */
data class ScanPerf(
    /**
     * 取景开始 → 首次解出的墙钟耗时（毫秒）。
     *
     * 「开始」= 这一段取景的起点：本次进入取景页，**或上一次解出**（连扫模式下）。
     * 因此连扫时它会含上一次的回显与提交时间，判读请以 [hitFrame] 为准 ——
     * 那一列才是「解码本身快不快」，本列是「用户等了多久」。
     */
    val wallMs: Long,
    /** 本会话分析过的帧数（含没解出码的帧）。 */
    val frames: Int,
    /** 其中没解出任何码的帧数。 */
    val misses: Int,
    /** 首次解出发生在第几帧（从 1 起）。 */
    val hitFrame: Int,
    /** 单帧解码最短 / 平均 / 最长耗时（毫秒）。 */
    val minMs: Long,
    val avgMs: Long,
    val maxMs: Long,
    /** 是否已经放宽到全部码制（说明前若干帧没认出二维码）。 */
    val widened: Boolean,
) {
    /**
     * 一行给人看的摘要。
     *
     * 写成「秒 + 帧序号 + 单帧耗时」三段而不是一句自然语言：现场要看的是**三个可比较的数字**，
     * 而失败时用户会把这一行连同二维码原文一起复制出来。
     */
    fun human(): String = buildString {
        append("取景 ").append(seconds(wallMs)).append(" 秒后第 ").append(hitFrame).append(" 帧解出")
        append(" · 共分析 ").append(frames).append(" 帧（").append(misses).append(" 帧未识别）")
        append(" · 单帧 ").append(minMs).append('~').append(maxMs).append(" 毫秒（均 ").append(avgMs).append('）')
        if (widened) append(" · 已放宽到全部码制")
    }

    private fun seconds(ms: Long): String = String.format(java.util.Locale.US, "%.1f", ms / 1000.0)
}

/**
 * 「只认二维码」什么时候放宽成「认全部码制」。
 *
 * 纯函数，因此放宽时机可以在 JVM 单测里离线断言（见 `ScanPerfTest`）。
 */
object ScanFormatPolicy {

    /**
     * 连续多少帧一个码都没解出来，就放宽码制。
     *
     * ## 为什么必须有这个兜底
     * 把码制收窄到只有二维码（见 [FastScanAnalyzer]）是拿「认得出其它码制」换速度。
     * 本项目的载荷分类（[com.ucas.qingxin.signin.attendance.ScanPayload]）只处理
     * 二维码内容的四种形态，但**没有证据**说明所有班牌都只贴二维码 ——
     * 万一某块班牌贴的是 Code128，收窄之后就永远认不出来了，那是功能坏掉，比慢严重得多。
     *
     * ## 为什么是 30 帧
     * 按实测的分析帧率（约 20~30fps）折合 1~1.5 秒：对准一块二维码班牌，正常情况下一两帧
     * 内就该解出来（[ScanPerf.hitFrame] 会证明这一点），因此 1 秒还没解出基本只可能是
     * 「这不是二维码」。取更小会误伤「手抖了一下、还没对焦上」的瞬间；取更大则让非二维码
     * 班牌多等好几个「二维码轮换周期」。
     */
    const val MISSES_BEFORE_WIDENING = 30

    fun shouldWiden(consecutiveMisses: Int): Boolean = consecutiveMisses >= MISSES_BEFORE_WIDENING
}

/**
 * 扫码分析器：**先只认二维码，认不出来再放宽**，并顺带把每帧的真实耗时记下来。
 *
 * ## 为什么必须收窄码制（本次修复的核心）
 * 原来用的是 `MultiFormatAnalyzer()` 的默认配置。它的 `hints` 来自
 * `DecodeFormatManager.DEFAULT_HINTS` —— 一个**空**的 `EnumMap`，而
 * `MultiFormatReader.setHints` 在拿不到 `POSSIBLE_FORMATS` 时会走「全试一遍」的分支：
 *
 * ```
 * MultiFormatOneDReader（一维码全家桶） → QRCode → DataMatrix → Aztec → PDF417 → MaxiCode
 * ```
 *
 * 即**每一帧**都要把 11 种一维码按行扫一遍、再试 5 种二维码制，而且一维码排在**最前面**。
 * 班牌上放的是二维码，于是这一长串里除 QRCode 之外的每一次尝试都注定失败、纯烧 CPU。
 * 用同一份 ZXing（`core-3.5.3`，与依赖一致）在 864×864 的裁剪区上实测
 * （`android-app/tools/scan-bench/QrBench.java`，复刻的就是上面这条解码通路）：
 *
 * | 配置 | 有码且解出 | **没解出（现场大多数帧）** |
 * | --- | --- | --- |
 * | 空 hints（1.3.4 的行为） | 2.4 ms | **6.5 ms** |
 * | 只认 QR（1.3.5 的默认） | 1.3 ms | **1.3 ms** |
 * | 17 种码制（1.3.5 的兜底） | —— | 6.3 ms |
 *
 * 也就是说**失败帧便宜了约 5 倍**，而「对着班牌还没解出」的帧恰恰占了每一次取景的绝大多数。
 * 这也正是微信小程序侧的等价做法：`wx.scanCode` 走的是系统扫码器，**只认二维码**。
 *
 * ## 为什么不是「只认二维码」了事
 * 见 [ScanFormatPolicy.MISSES_BEFORE_WIDENING]：认不出时放宽成
 * `MultiFormatAnalyzer()`（与 1.3.4 **逐字一致**的行为，实测兜底那条路 6.3 ms，
 * 与 1.3.4 的 6.5 ms 相同），因此最坏情况只是慢，
 * 不会出现「某块班牌永远扫不出来」。
 *
 * ## 度量口径
 * 计时包住整次 `inner.analyze(...)`。该库的 `ImageAnalyzer.analyze` 是**同步**的：
 * 它在调用线程上完成 YUV 转换、旋转、裁剪、二值化与解码，然后才回调
 * `onSuccess` / `onFailure`（由其字节码确认：`analyze(byte[],int,int)` 直接内联调用，
 * 没有线程池、没有队列积压）。因此这段墙钟就是这一帧真实占掉的 CPU 时间，
 * 也是「分析帧率」的直接倒数。
 *
 * 注意它**不含**排队等待：库里帧是「取到就分析、分析完才关」，不存在越积越旧的帧堆，
 * 所以解出来的码不会因为排队而变陈旧。
 */
class FastScanAnalyzer : Analyzer<Result> {

    /**
     * 最近一帧的结果快照，供 [ScanScreen] 在拿到识别结果时一并取走。
     *
     * `volatile`：写入发生在相机分析线程，读取可能发生在回调链的稍后一刻。
     * 不担心被覆盖 —— 同一个时刻只可能有一次「解出」（库里有 `isAnalyzeResult` 与
     * `joinQueue` 两道串行化），而每次解出都会在同一帧内被读走。
     */
    @Volatile
    var latest: ScanPerf? = null
        private set

    /** 当前生效的分析器。「收窄」与「放宽」两个实例只换这一个引用。 */
    @Volatile
    private var inner: MultiFormatAnalyzer = narrowAnalyzer()

    /** 当前这一段的起点：每次解出后重置（见 [record]）。 */
    private var segmentStartMs = SystemClock.elapsedRealtime()

    // 以下计数只在相机分析线程上读写（该库逐帧串行回调），因此无需加锁。
    private var frames = 0
    private var misses = 0
    private var consecutiveMisses = 0
    private var minMs = Long.MAX_VALUE
    private var totalMs = 0L
    private var maxMs = 0L
    private var widened = false

    override fun analyze(imageProxy: ImageProxy, listener: Analyzer.OnAnalyzeListener<Result>) {
        val t0 = SystemClock.elapsedRealtime()
        inner.analyze(
            imageProxy,
            object : Analyzer.OnAnalyzeListener<Result> {
                override fun onSuccess(result: AnalyzeResult<Result>) {
                    record(hit = true, costMs = SystemClock.elapsedRealtime() - t0)
                    listener.onSuccess(result)
                }

                override fun onFailure(e: Exception?) {
                    record(hit = false, costMs = SystemClock.elapsedRealtime() - t0)
                    listener.onFailure(e)
                }
            },
        )
    }

    private fun record(hit: Boolean, costMs: Long) {
        val now = SystemClock.elapsedRealtime()
        frames++
        if (costMs < minMs) minMs = costMs
        if (costMs > maxMs) maxMs = costMs
        totalMs += costMs

        if (hit) {
            latest = ScanPerf(
                wallMs = now - segmentStartMs,
                frames = frames,
                misses = misses,
                hitFrame = frames,
                minMs = minMs,
                avgMs = totalMs / frames,
                maxMs = maxMs,
                widened = widened,
            )
            // 解出即开启新的一段：否则连扫模式下这一段会一直累积下去，
            // 在页面上待十分钟就会读出「取景 600 秒后第 900 帧解出」这种毫无意义的数字。
            // 注意 consecutiveMisses 也一起清零：既然刚解出过，说明「只认二维码」是有效的，
            // 不该因为之前那段没认出就永远放宽（widened 本身是永久的，见 [ScanFormatPolicy]）。
            frames = 0
            misses = 0
            consecutiveMisses = 0
            minMs = Long.MAX_VALUE
            totalMs = 0
            maxMs = 0
            segmentStartMs = now
            return
        }

        misses++
        consecutiveMisses++
        if (!widened && ScanFormatPolicy.shouldWiden(consecutiveMisses)) {
            widened = true
            // 与 1.3.4 的行为逐字一致：默认 hints（空）会让 MultiFormatReader
            // 把 6 组 reader 全试一遍，因此即使班牌上是二维码之外的码制也仍能认出来。
            inner = MultiFormatAnalyzer()
        }
    }

    private companion object {
        /**
         * 只认二维码的 hints。
         *
         * `CHARACTER_SET` 跟着一起给：二维码的字节模式默认按 ISO-8859-1 解释 payload。
         * 本项目的载荷都是 ASCII（URL / 7 位数字 / 32 位十六进制），因此这个 hint
         * **不改变现有解析结果**；它是为了班牌实现不统一的情形兜底 ——
         * 万一某块班牌把中文写进码里，UTF-8 才是与小程序侧一致的口径。
         *
         * 其余 `DecodeConfig` 项一律保持库默认：`areaRectRatio = 0.8` 的居中裁剪
         * （1080p 分析时等于只看 864×864 = 全部像素的 36%）与
         * `isSupportLuminanceInvert = false`（班牌是白底黑码，反色再解一遍纯属翻倍成本）
         * 都已经是我们想要的方向，不必再动。
         */
        val QR_HINTS: Map<DecodeHintType, Any> = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.CHARACTER_SET to "UTF-8",
        )

        fun narrowAnalyzer(): MultiFormatAnalyzer = MultiFormatAnalyzer(DecodeConfig().setHints(QR_HINTS))
    }
}
