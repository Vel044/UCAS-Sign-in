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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import com.king.zxing.analyze.MultiFormatAnalyzer

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
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    val askCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }

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
                )

                state.finished -> ScanResultCard(state = state, onRescan = vm::reset, onBack = onBack)

                activity == null -> Text(
                    "当前环境无法启动相机，请回首页用「手动打卡」录入编号",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                )

                else -> CameraScanner(onDecoded = vm::onDecoded, activity = activity)
            }

            if (state.submitting) {
                SubmittingOverlay()
            }
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
 * 取景区：CameraX 预览 + 相机分析。
 *
 * 用 zxing-lite 的 [BaseCameraScan] 而不是自己串 CameraX：它把旋转、对焦、裁剪、
 * 预览缩放这些最容易在个别机型上翻车的地方都封好了（选型理由见 `app/build.gradle.kts`）。
 * 它的入口签名是 `(ComponentActivity, PreviewView)`，因此可以直接嵌进 Compose 的
 * [AndroidView]，主题不必改成 AppCompat。
 */
@Composable
private fun CameraScanner(activity: ComponentActivity, onDecoded: (String) -> Unit) {
    val holder = remember { ScanHolder() }
    // 用 rememberUpdatedState 把回调「钉」在最新一版上：AndroidView 的 factory 只在
    // 首次组合时执行一次，若直接闭包捕获 onDecoded，重组之后它会一直调用旧的引用。
    val decoded by rememberUpdatedState(onDecoded)

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
                // 多格式而不是只认 QR：班牌实现不统一，能把 QR 之外的编码一并认下来，
                // 认不认得出「是不是签到码」交给 ScanPayload 判断，不在这里一刀切。
                scan.setAnalyzer(MultiFormatAnalyzer())
                // 扫到一次就停止分析：避免同一张码在签到请求往返期间被反复触发。
                // （注意它管的是**后续帧**，管不住已经在飞行中的回调，
                //   因此 ViewModel 里另有一道原子闸门。）
                scan.setAutoStopAnalyze(true)
                scan.setVibrate(true)
                scan.setOnScanResultCallback(object : CameraScan.OnScanResultCallback<Result> {
                    override fun onScanResultCallback(result: AnalyzeResult<Result>) {
                        val text = result.result?.text
                        if (!text.isNullOrBlank()) decoded(text)
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
private fun CameraPermissionCard(onRequest: () -> Unit, onBack: () -> Unit) {
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
                "也可以回首页用「手动打卡」，录入现场读到的 7 位编号。",
                fontSize = 12.sp,
                color = SCAN_TEXT_SUB,
            )
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
