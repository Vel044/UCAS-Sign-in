package com.ucas.qingxin.signin.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.google.zxing.Result
import com.king.camera.scan.AnalyzeResult
import com.king.camera.scan.BaseCameraScan
import com.king.camera.scan.CameraScan
import kotlinx.coroutines.delay

private val SCAN_ACCENT = Color(0xFF0F6B4C)
private val SCAN_TEXT_SUB = Color(0xFF5B6B63)
private val SCAN_WARN_BG = Color(0xFFFFF6E5)

/**
 * 「扫码打卡」页面。
 *
 * ## 与「手动打卡」的分工
 * 两页解决同一件事的两半：本页负责**读**（相机 → [ScanPayload] 分类），
 * [ManualSignScreen] 负责**录**（键盘 → [com.ucas.qingxin.signin.attendance.SignCode]）。
 * 相机不可用（未授权、机型异常）时本页会明确指回手动那一路，
 * 而不是让用户以为功能坏了。
 *
 * ## 为什么不申请定位权限
 * 轻新课堂的**课表自动签到**（`app/course/stu_auto_sign.action`）确实要 GPS + WiFi BSSID，
 * 但现场扫码这条通道（`stu_scan_sign.action`）**完全不需要** ——
 * 小程序扫码后也是直接把二维码里的参数 POST 上去，没有任何位置信息。
 * 因此本页只申请相机。
 *
 * ## 取景区的两条硬约束
 * 1. **必须显式释放相机**：本页会被反复进出，漏一次就残留一路占用摄像头的会话，
 *    之后其它应用申请相机会失败，而用户只会觉得「扫码用一段时间就不灵了」。
 * 2. **扫到结果就停取景**（见 [ScanUiState.finished]）：现场场景下用户会盯着结果看很久，
 *    让相机一直开着纯属耗电，而且对着同一张码继续分析也没有意义。
 */
@Composable
fun ScanScreen(
    state: ScanUiState,
    vm: ScanViewModel,
    onBack: () -> Unit,
    onOpenManual: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    val askCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }
    // 相册选图：系统 Photo Picker（PickVisualMedia）**不需要任何存储权限** ——
    // 用户在系统选择器里选哪张，应用只能拿到哪张，是 Scoped 授权；旧设备自动
    // 回退到文档选择器（同样免权限）。比申请「照片和视频」全库权限在兼容性与
    // 隐私上都更好，这正是选它而不是 READ_MEDIA_IMAGES 的原因。
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> vm.onImageSelected(uri) }

    // 进页即申请：在班牌前多一次「先进页面、再点授权」的往返是纯粹的浪费。
    LaunchedEffect(Unit) {
        if (!granted) askCamera.launch(Manifest.permission.CAMERA)
    }
    // 用户可能跳去系统设置里授权再回来：每次回到前台都以系统里的**真实**权限为准，
    // 否则「去设置里开了权限」这一趟会白跑（页面仍停在「需要相机权限」）。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = hasCameraPermission(context)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("返回") }
            Text(
                "扫码打卡",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
            )
        }

        // 连扫开关 + 手动录入出口。
        //
        // 放在取景区**上方**、标题行下方：两者都是「取景这条路走不通或不够用」时的
        // 出口，而用户需要它们的时刻正是刚失败、目光还停在屏幕中上部的时候。
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = state.continuous,
                onClick = { vm.setContinuous(!state.continuous) },
                label = { Text("连扫", fontSize = 13.sp) },
            )
            Text(
                if (state.continuous) {
                    "失败自动重扫；成功后停在结果卡片。同一张码 3 秒内只提交一次"
                } else {
                    "班牌签到码约每 5 秒轮换，连扫可免去反复点「继续扫码」"
                },
                fontSize = 11.sp,
                color = SCAN_TEXT_SUB,
                lineHeight = 15.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    pickImage.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                        ),
                    )
                },
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text("相册选图", fontSize = 13.sp) }
            TextButton(
                onClick = onOpenManual,
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text("输入编号", fontSize = 13.sp) }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            when {
                !granted -> CameraPermissionCard(
                    onRequest = { askCamera.launch(Manifest.permission.CAMERA) },
                    onBack = onBack,
                    onOpenManual = onOpenManual,
                )

                state.finished -> ScanResultCard(state = state, onRescan = vm::reset, onBack = onBack)

                activity == null -> Text(
                    "当前环境无法启动相机，请用「输入编号」或回首页「手动打卡」录入",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                )

                else -> CameraScanner(
                    activity = activity,
                    continuous = state.continuous,
                    onDecoded = vm::onDecoded,
                )
            }

            // 连扫模式不出现全屏遮罩：那里的「正在提交」写在底部那行瞬时回显里，
            // 否则每次请求都会把取景画面糊掉一次（见 ScanViewModel.markSubmitting）。
            if (state.submitting && !state.continuous) {
                SubmittingOverlay()
            }

            // 连扫模式的瞬时回显：压在取景区底部，不遮挡取景框。
            // 它是这个模式下唯一的结果反馈 —— 结果卡片不许出现（那会拆掉取景）。
            if (state.continuous && state.flash.isNotBlank()) {
                ScanFlash(
                    text = state.flash,
                    ok = state.flashOk,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                )
            }
        }

        // 连扫的会话结果：连扫时不看结果卡片，这些就是「刚才那几次成没成」的唯一依据。
        if (state.continuous && state.session.isNotEmpty()) {
            ScanSession(session = state.session)
        }

        if (!state.finished && state.recognized.isNotBlank()) {
            Text(
                state.recognized,
                fontSize = 12.sp,
                color = SCAN_ACCENT,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.hostNote.isNotBlank()) {
            Text(
                state.hostNote,
                fontSize = 12.sp,
                color = SCAN_TEXT_SUB,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (!granted || !state.finished) {
            Text(
                "对准班牌上的签到二维码，识别成功后自动提交",
                fontSize = 12.sp,
                color = SCAN_TEXT_SUB,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 连扫模式下的一条瞬时回显。
 *
 * 半透明深底 + 白字，而不是与结果卡片同一套浅色卡片：它在取景画面上，
 * 必须是「浮在上面的一句话」而不是「盖住画面的一块板」。
 */
@Composable
private fun ScanFlash(text: String, ok: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth(0.92f)
            .background(
                if (ok) Color(0xE60F6B4C) else Color(0xE6B42318),
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text,
            color = Color.White,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            maxLines = 3,
        )
    }
}

/** 连扫的本次会话结果（新的在前，最多几条）。 */
@Composable
private fun ScanSession(session: List<ScanAttempt>) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(SCAN_WARN_BG, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("本次连扫", fontSize = 11.sp, color = SCAN_TEXT_SUB)
        session.forEach { attempt ->
            Text(
                (if (attempt.ok) "✓ " else "✗ ") + attempt.summary,
                fontSize = 12.sp,
                color = if (attempt.ok) SCAN_ACCENT else Color(0xFFB42318),
                maxLines = 1,
            )
        }
    }
}

/**
 * 取景区：CameraX 预览 + 相机分析。
 *
 * 用 zxing-lite 的 [BaseCameraScan] 而不是自己串 CameraX：它把旋转、对焦、裁剪、
 * 预览缩放这些最容易在个别机型上翻车的地方都封好了（选型理由见 `app/build.gradle.kts`）。
 * 它的入口签名是 `(ComponentActivity, PreviewView)`，因此可以直接嵌进 Compose 的
 * [AndroidView]，主题不必改成 AppCompat。
 *
 * ## 分析器：收窄到二维码 + 实测耗时
 * 用 [FastScanAnalyzer] 而不是 `MultiFormatAnalyzer()`：后者默认会把 11 种一维码
 * 加 5 种二维码制逐帧全试一遍（原因与实测数字见 [FastScanAnalyzer]）。
 * 顺带把每帧的真实耗时带回去，让「识别过慢」这件事在下一份现场记录里可归因。
 *
 * ## 手电筒
 * 教室后排 / 光线偏暗时，班牌在画面里又小又糊，自动曝光与对焦都不容易收敛 ——
 * 这正是「扫半天扫不上」的常见现场。补光按钮只在**真的有闪光灯**的机器上出现。
 *
 * ## 连扫模式下必须重新打开分析（本次修复的一个真 bug）
 * 扫码库的 `setAutoStopAnalyze(true)` 不是「停下这一帧」这么轻：它在
 * `handleAnalyzeResult` 里把内部开关 `isAnalyze` 置为 **false**，而库自己**没有任何**
 * 地方再把它置回 true（`setAnalyzeImage(boolean)` 是唯一的入口，由字节码确认）。
 * 于是 pin 上 `true` 之后，**第一帧结果之后就再也不会有任何一帧被分析**。
 *
 * 单发模式看不出来：结果一到就出结果卡片，`CameraScanner` 整个离开组合、相机被释放，
 * 用户点「继续扫码」时是一套全新的扫描器（`isAnalyze` 自然为 true）。
 * 但连扫模式恰恰**不许**停取景 —— 结果卡片不出现、扫描器一直活着 ——
 * 于是「自动重新开始取景」只是 ViewModel 打开了自己的闸门，相机那头早就聋了：
 * 用户看到的是「第一次之后就一直扫不动了」。这正好落在本次要修的痛点（码过期 → 要重扫）上。
 *
 * 因此连扫时 `setAutoStopAnalyze(false)`（不自动停），并在 [continuous] 变化时
 * 显式把 `isAnalyze` 打开一次兜底。连扫模式下重复帧由 ViewModel 的原子闸门
 * 与 3 秒去重窗口挡住，不需要相机层再停一次。
 */
@Composable
private fun CameraScanner(
    activity: ComponentActivity,
    continuous: Boolean,
    onDecoded: (String, ScanPerf?) -> Unit,
) {
    val holder = remember { ScanHolder() }
    val analyzer = remember { FastScanAnalyzer() }
    // 用 rememberUpdatedState 把回调「钉」在最新一版上：AndroidView 的 factory 只在
    // 首次组合时执行一次，若直接闭包捕获 onDecoded，重组之后它会一直调用旧的引用。
    val decoded by rememberUpdatedState(onDecoded)
    var torchOn by remember { mutableStateOf(false) }
    var hasTorch by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .border(1.dp, Color(0x33000000), RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    // COMPATIBLE（内部是 TextureView）而不是 PERFORMANCE（SurfaceView）：
                    // SurfaceView 在 Compose 里被裁剪或叠加时，有一个已知的
                    // 「画面盖到其它控件之上」的问题，而本页要在取景区上叠取景框与结果卡片。
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val scan = BaseCameraScan<Result>(activity, previewView)
                scan.setAnalyzer(analyzer)
                // 单发模式扫到一次就停止分析：避免同一张码在签到请求往返期间被反复触发。
                // （注意它管的是**后续帧**，管不住已经在飞行中的回调，
                //   因此 ViewModel 里另有一道原子闸门。）
                // 连扫模式**不能**停，理由见本函数的说明：库里那个开关一旦置 false 就不会自己回来。
                scan.setAutoStopAnalyze(!continuous)
                scan.setVibrate(true)
                scan.setOnScanResultCallback(object : CameraScan.OnScanResultCallback<Result> {
                    override fun onScanResultCallback(result: AnalyzeResult<Result>) {
                        val text = result.result?.text
                        if (!text.isNullOrBlank()) decoded(text, analyzer.latest)
                    }
                })
                holder.scan = scan
                scan.startCamera()
                previewView
            },
        )

        // 取景框：只是给用户一个「把码放这里」的视觉锚点，不参与裁剪分析区域。
        Box(
            Modifier
                .size(240.dp)
                .border(2.dp, Color(0xCCFFFFFF), RoundedCornerShape(16.dp)),
        )

        if (hasTorch) {
            TorchToggle(
                on = torchOn,
                onToggle = {
                    val next = !torchOn
                    // 相机已释放时点了不会有反应，但那时按钮本来也不该在屏上。
                    holder.scan?.enableTorch(next)
                    torchOn = next
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp),
            )
        }
    }

    // 连扫开关有变化时，把「扫到一次就停」这个开关重新对齐一遍，并把分析显式打开：
    // 库里 setAutoStopAnalyze 一旦把它关掉（见函数的说明），只有 setAnalyzeImage 能开回来。
    LaunchedEffect(continuous) {
        holder.scan?.run {
            setAutoStopAnalyze(!continuous)
            setAnalyzeImage(true)
        }
    }

    // 有无闪光灯要等相机**绑定完成**才知道：startCamera() 内部的 bindToLifecycle 是异步的，
    // 组合刚结束时 getCamera() 还是 null，hasFlashUnit() 会一律回 false —— 那会让按钮
    // 永远不出现（而且是「悄悄没有」：用户根本不知道有补光这个功能）。
    // 因此有限次地探一下。20 × 150ms = 3 秒，而绑定通常只要几百毫秒。
    LaunchedEffect(Unit) {
        repeat(20) {
            if (holder.scan?.hasFlashUnit() == true) {
                hasTorch = true
                return@LaunchedEffect
            }
            delay(150)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // 相机必须显式释放，见 ScanScreen 的说明。
            holder.scan?.run {
                stopCamera()
                release()
            }
            holder.scan = null
        }
    }
}

/**
 * 补光开关。
 *
 * 做成压在取景画面上的一个小圆钮（而不是排进顶部那行说明）：它是**随时可能要按**的东西
 * —— 用户正是在画面太暗、对不上焦的那一刻才需要它，此时目光在取景框上。
 * 用半透明深底 + 白图标，避免在明亮画面里消失。
 */
@Composable
private fun TorchToggle(on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(44.dp)
            .background(Color(0x66000000), CircleShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (on) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
            contentDescription = if (on) "关闭补光" else "打开补光",
            tint = if (on) Color(0xFFFFE082) else Color.White,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** 持有本次取景的相机会话，供 [DisposableEffect] 释放。 */
private class ScanHolder {
    var scan: BaseCameraScan<Result>? = null
}

/** 提交中：取景仍在，但结果马上就来，用一层轻遮罩说明「已经在办」。 */
@Composable
private fun SubmittingOverlay() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x66FFFFFF)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(color = SCAN_ACCENT)
            Text("正在提交签到…", fontSize = 13.sp, color = SCAN_ACCENT, fontWeight = FontWeight.Medium)
        }
    }
}

/** 相机权限未授予时的说明与出口。 */
@Composable
private fun CameraPermissionCard(
    onRequest: () -> Unit,
    onBack: () -> Unit,
    onOpenManual: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("需要相机权限", fontWeight = FontWeight.Bold)
            Text(
                "扫码要用相机读班牌上的二维码。相机只在打卡时使用，识别完即释放；" +
                    "本应用不会因为打卡申请定位权限。",
                fontSize = 13.sp,
                color = SCAN_TEXT_SUB,
                lineHeight = 20.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRequest,
                    colors = ButtonDefaults.buttonColors(containerColor = SCAN_ACCENT),
                ) { Text("去授权", fontSize = 14.sp) }
                TextButton(onClick = onBack) { Text("返回首页") }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "也可以直接用「输入编号」录入现场读到的 7 位编号，不需要相机。",
                fontSize = 12.sp,
                color = SCAN_TEXT_SUB,
            )
            TextButton(
                onClick = onOpenManual,
                contentPadding = PaddingValues(horizontal = 0.dp),
            ) { Text("输入编号", fontSize = 13.sp) }
        }
    }
}

/** 结果卡片：成功与失败共用，只换标题与配色。 */
@Composable
private fun ScanResultCard(state: ScanUiState, onRescan: () -> Unit, onBack: () -> Unit) {
    val ok = state.success.isNotEmpty()
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (ok) "签到成功" else "没能完成签到",
                fontWeight = FontWeight.Bold,
                color = if (ok) SCAN_ACCENT else MaterialTheme.colorScheme.error,
            )
            if (state.recognized.isNotBlank()) {
                Text(state.recognized, fontSize = 12.sp, color = SCAN_TEXT_SUB)
            }
            Text(if (ok) state.success else state.error, fontSize = 14.sp, lineHeight = 21.sp)
            if (state.hostNote.isNotBlank()) {
                Text(state.hostNote, fontSize = 12.sp, color = SCAN_TEXT_SUB, lineHeight = 18.sp)
            }
            if (state.timestampNote.isNotBlank()) {
                Text(state.timestampNote, fontSize = 12.sp, color = SCAN_TEXT_SUB, lineHeight = 18.sp)
            }
            // 识别耗时：现场反馈「扫得太慢」时，这一行是唯一能把「解码慢」与
            // 「没对准 / 没对焦上」分开的东西（判读方法见 ScanPerf.hitFrame）。
            if (state.scanPerf.isNotBlank()) {
                Text(state.scanPerf, fontSize = 12.sp, color = SCAN_TEXT_SUB, lineHeight = 18.sp)
            }
            if (state.rawText.isNotBlank()) ScanTrace(label = "二维码原文", value = state.rawText)
            if (state.submittedUrl.isNotBlank()) ScanTrace(label = "本次提交", value = state.submittedUrl)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRescan,
                    colors = ButtonDefaults.buttonColors(containerColor = SCAN_ACCENT),
                ) { Text("继续扫码", fontSize = 14.sp) }
                TextButton(onClick = onBack) { Text("返回首页") }
            }
        }
    }
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/**
 * 一条现场诊断信息：浅底、等宽、可选中。
 *
 * ## 为什么值得占结果卡片的地方
 * 现场打卡只有一次机会，而学校的拒绝理由常常是**含糊**的（「签到码已失效」既可能是
 * 码真的轮换过期了，也可能是我们发出去的参数名不对）。这两种原因在界面上必须可分，
 * 否则每次都要靠重新抓包去猜。
 *
 * 等宽 + 可选中是有意的：失败后用户要做的事就是把这串东西整段复制出来核对或转发，
 * 比例字体与不可选会让这件事很难受。它排在「成功了没有」之后，不抢主信息的位置。
 */
@Composable
private fun ScanTrace(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SCAN_WARN_BG, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, fontSize = 11.sp, color = SCAN_TEXT_SUB)
        SelectionContainer {
            Text(
                value,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 16.sp,
            )
        }
    }
}
