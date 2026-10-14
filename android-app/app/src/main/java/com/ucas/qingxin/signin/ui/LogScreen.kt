package com.ucas.qingxin.signin.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.ucas.qingxin.signin.logging.SignLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Intent

private val LOG_ACCENT = Color(0xFF0F6B4C)
private val LOG_SUB = Color(0xFF5B6B63)
private val LOG_WARN_BG = Color(0xFFFFF6E5)

/**
 * 「签到日志」页面。
 *
 * ## 为什么值得一个独立页面
 * 现场打卡失败后，用户手上唯一的证据就是这一次交互的原始数据（见 [SignLog]）。
 * 把它藏在设置深处、只能看不能带走，等于没有 —— 用户需要的是**能贴给维护者**的东西，
 * 因此这个页面必须做到两件事：看得见，且拿得走（分享 / 存文件）。
 *
 * ## 为什么把「记了什么、没记什么」也写在界面上
 * 这份日志里有二维码原文和学校响应正文，后者可能包含个人信息。
 * 让它默默攒着、不解释，是不诚实的 —— 用户有权在点「分享」**之前**知道
 * 自己将要发出什么。因此隐私说明放在按钮上方，而不是藏在某个括号里。
 */
@Composable
fun LogScreen(log: SignLog, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var text by remember { mutableStateOf("") }
    var sizeBytes by remember { mutableLongStateOf(0L) }
    var confirmClear by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }

    suspend fun reload() {
        val (t, s) = withContext(Dispatchers.IO) {
            log.flush()
            log.readTail() to log.sizeBytes()
        }
        text = t
        sizeBytes = s
    }

    LaunchedEffect(reloadKey) { reload() }

    // 「保存到文件」：走系统文件选择器（SAF），因此**不需要任何存储权限**。
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val target = uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    log.flush()
                    context.contentResolver.openOutputStream(target)?.use { out ->
                        out.write(log.readTail(SignLog.MAX_BYTES.toInt()).toByteArray())
                    }
                }
            }
        }
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
                "签到日志",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(formatSize(sizeBytes), fontSize = 12.sp, color = LOG_SUB)
        }

        Text(
            "每次扫码与签到都会记在这里：二维码原文、我们发出的地址、学校回的正文、最终判定。" +
                "日志只存在本机，不会自动上传。",
            fontSize = 12.sp,
            color = LOG_SUB,
            lineHeight = 18.sp,
        )

        Text(
            "分享前请注意：日志含二维码原文与学校响应正文，可能有个人信息。" +
                "其中的 sessionId 等登录凭据**从不记录**，本人学号也只保留参数名。",
            fontSize = 12.sp,
            color = Color(0xFF8A5A00),
            lineHeight = 18.sp,
            modifier = Modifier
                .fillMaxWidth()
                .background(LOG_WARN_BG, RoundedCornerShape(10.dp))
                .padding(10.dp),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            log.flush()
                            val file = log.ensureFile()
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "轻新签到 · 签到日志")
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, "分享日志"))
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = LOG_ACCENT),
            ) { Text("分享日志", fontSize = 14.sp) }

            TextButton(onClick = { saveLauncher.launch(SignLog.FILE_NAME) }) {
                Text("保存到文件")
            }

            TextButton(onClick = { confirmClear = true }) { Text("清空") }
        }

        if (confirmClear) {
            Card(
                colors = CardDefaults.cardColors(containerColor = LOG_WARN_BG),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("清空签到日志？", fontWeight = FontWeight.Bold)
                    Text(
                        "这是现场失败时唯一的原始证据，清掉之后无法恢复。",
                        fontSize = 12.sp,
                        color = LOG_SUB,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                log.clear()
                                confirmClear = false
                                reloadKey++
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                            ),
                        ) { Text("确认清空", fontSize = 14.sp) }
                        TextButton(onClick = { confirmClear = false }) { Text("取消") }
                    }
                }
            }
        }

        val shown = text.ifBlank { "（还没有记录。去扫一次班牌，或打卡一次，就会出现在这里。）" }
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(16.dp),
            // 日志占满剩余高度：它是本页的主体内容，按钮只是它的附属操作。
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
            ) {
                // 等宽 + 可选中：日志是给人整段复制走的，比例字体或不可选都很别扭。
                SelectionContainer {
                    Text(
                        shown,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
}
