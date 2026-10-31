package com.ucas.qingxin.signin.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.mutableIntStateOf
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
import com.ucas.qingxin.signin.history.SignRecord
import com.ucas.qingxin.signin.history.SignRecordStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val REC_ACCENT = Color(0xFF0F6B4C)
private val REC_SUB = Color(0xFF5B6B63)
private val REC_WARN_BG = Color(0xFFFFF6E5)
private val REC_FAIL = Color(0xFFB42318)

/**
 * 「签到记录」页面。
 *
 * ## 与「签到日志」的分工（这是它值得单独一页的理由）
 * 两页都在设置里、都能分享，但回答的问题不同：
 * - **签到日志**答「这次为什么失败」—— 二维码原文、发出去的地址、学校响应正文，
 *   面向维护者；
 * - **本页**答「我到底签上了没有」—— 什么时候、从哪来、哪个编号、成没成，面向用户自己。
 *
 * 为什么后者在我们这儿格外必要：讲座**不在课表里**，学校的系统里也没有
 * 「我的讲座签到」可查（见 `docs/lecture-signin-research.md` §10.18）。
 * 于是本机这份记录成了唯一能回看「我这学期签到了哪些讲座」的地方 ——
 * 它不是一个调试功能的副产品，而是这条产品路线的**必备件**。
 *
 * ## 为什么按天分组而不只是一串列表
 * 「这学期的讲座我签了几场」这种问题只能按天看才读得出来；
 * 一串等距的时间戳读起来是流水账，看不出「哪天去了、哪天没去」。
 */
@Composable
fun RecordScreen(store: SignRecordStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var records by remember { mutableStateOf<List<SignRecord>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }

    suspend fun reload() {
        val list = withContext(Dispatchers.IO) {
            store.flush()
            store.readLatest()
        }
        records = list
        loaded = true
    }

    LaunchedEffect(reloadKey) { reload() }

    // 导出内容在**导出那一刻**生成：分享出去的必须是屏上这批记录的快照，
    // 而不是「点分享之后又多出来的那几条」。
    fun exportText(): String = SignRecordStore.exportText(records, System.currentTimeMillis())

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val target = uri ?: return@rememberLauncherForActivityResult
        val text = exportText()
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(target)?.use { out ->
                        out.write(text.toByteArray())
                    }
                }
            }
        }
    }

    val okCount = records.count { it.ok }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("返回") }
                Text(
                    "签到记录",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp),
                )
                Text("${records.size} 条", fontSize = 12.sp, color = REC_SUB)
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("记录了什么", fontWeight = FontWeight.Bold)
                    Text(
                        "每次签到都会留一条：时间、来源（扫码 / 手动 / 一键 / 自动 / 小部件）、" +
                            "提交的编号、学校给的结果。失败也记 —— 「没签上」正是最需要回看的。",
                        fontSize = 12.sp,
                        color = REC_SUB,
                        lineHeight = 18.sp,
                    )
                    Text(
                        "记录只存在本机，不会自动上传。里面不含登录凭据，也不含学号。",
                        fontSize = 12.sp,
                        color = Color(0xFF8A5A00),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(REC_WARN_BG, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                    )
                    if (records.isNotEmpty()) {
                        Text(
                            "成功 $okCount · 失败 ${records.size - okCount}",
                            fontSize = 13.sp,
                            color = REC_ACCENT,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (records.isEmpty()) return@Button
                                val text = exportText()
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        // 分享走 content:// 而不是文件路径：不需要任何存储权限，
                                        // 也不会因为 Android 版本对 file:// 的限制在某些机型上失败。
                                        // 目标目录必须在 file_paths.xml 里放行，否则这里会抛异常。
                                        val file = runCatching {
                                            val dir = java.io.File(context.filesDir, EXPORT_DIR)
                                                .apply { mkdirs() }
                                            java.io.File(dir, EXPORT_FILE_NAME).apply { writeText(text) }
                                        }.getOrNull() ?: return@withContext
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            file,
                                        )
                                        val send = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_SUBJECT, "轻新签到 · 签到记录")
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(
                                            Intent.createChooser(send, "分享签到记录"),
                                        )
                                    }
                                }
                            },
                            enabled = records.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = REC_ACCENT),
                        ) { Text("分享记录", fontSize = 14.sp) }

                        TextButton(
                            onClick = { saveLauncher.launch(EXPORT_DISPLAY_NAME) },
                            enabled = records.isNotEmpty(),
                        ) { Text("保存到文件") }

                        TextButton(
                            onClick = { confirmClear = true },
                            enabled = records.isNotEmpty(),
                        ) { Text("清空") }
                    }
                }
            }
        }

        if (confirmClear) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = REC_WARN_BG),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("清空签到记录？", fontWeight = FontWeight.Bold)
                        Text(
                            "讲座不在课表里，学校系统也查不到「我签过哪些讲座」—— " +
                                "清掉之后这份记录无法恢复。",
                            fontSize = 12.sp,
                            color = REC_SUB,
                            lineHeight = 18.sp,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    store.clear()
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
        }

        if (loaded && records.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("还没有记录", fontWeight = FontWeight.SemiBold)
                        Text(
                            "去扫一次班牌、用一次「输入编号」，或用一次「一键签到」，这里就会出现。",
                            fontSize = 12.sp,
                            color = REC_SUB,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }
        }

        // 按天分组。records 已是「新的在前」，因此这里用 fold 顺序扫一遍即可，
        // 既保留顺序又不需要任何排序（同一天自然连续）。
        val groups = groupByDay(records)
        groups.forEach { (dayLabel, dayRecords) ->
            item(key = "day-$dayLabel") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(dayLabel, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        "${dayRecords.count { it.ok }} / ${dayRecords.size} 次成功",
                        fontSize = 11.sp,
                        color = REC_SUB,
                    )
                }
            }
            itemsIndexed(
                items = dayRecords,
                // key 必须**全局唯一**（LazyColumn 的所有条目共用一个命名空间），
                // 而且要落在条目位置上 —— 用「日期 + 组内序号」而不是记录内容：
                // 同一毫秒、同一来源、同一编号的两条记录（连扫时完全可能）会让
                // 内容派生的 key 撞车，而撞 key 是**直接崩**，不是显示错乱。
                key = { index, _ -> "rec-$dayLabel-$index" },
            ) { _, record -> RecordRow(record) }
        }
    }
}

/** 一天一行记录。 */
@Composable
private fun RecordRow(record: SignRecord) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    // 只显示到分钟：秒对「我什么时候签的」没有增益，却让每行都更长。
                    SignRecordStore.stamp(record.atMs).dropLast(3),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF3E5249),
                )
                Text(
                    if (record.ok) "成功" else "失败",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (record.ok) REC_ACCENT else REC_FAIL,
                )
            }
            Text(
                record.source.label,
                fontSize = 11.sp,
                color = REC_SUB,
            )
            if (record.code.isNotEmpty()) {
                Text(
                    record.code + if (record.kind.isNotEmpty()) "（${record.kind}）" else "",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF16362B),
                )
            }
            if (record.message.isNotEmpty()) {
                Text(
                    record.message,
                    fontSize = 12.sp,
                    color = if (record.ok) REC_SUB else REC_FAIL,
                    lineHeight = 17.sp,
                )
            }
            if (record.note.isNotEmpty()) {
                // 参数来源说明紧跟在学校原话之后：现场失败时，
                // 「学校说了什么」与「我们发的是什么」必须一起看才有意义。
                Text(
                    "↳ ${record.note}",
                    fontSize = 11.sp,
                    color = REC_SUB,
                    lineHeight = 15.sp,
                )
            }
            if (record.raw.isNotEmpty()) {
                // 二维码原文可选中：现场核对「是不是这张码」时需要整段复制出去比对。
                SelectionContainer {
                    Text(
                        "码：${record.raw}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = REC_SUB,
                        lineHeight = 15.sp,
                    )
                }
            }
        }
    }
}

/**
 * 按「本地日期」切段，**保持原有顺序**。
 *
 * 用 `LinkedHashMap` 而不是 `groupBy`：后者返回的也是 `LinkedHashMap`，
 * 但显式写出来的意图更清楚 —— 这里的顺序是有意义的（新的在前），
 * 谁把它换成 `HashMap` 都会让分组顺序变成随机的。
 */
private fun groupByDay(records: List<SignRecord>): Map<String, List<SignRecord>> {
    val out = LinkedHashMap<String, MutableList<SignRecord>>()
    for (record in records) {
        out.getOrPut(SignRecordStore.dayLabel(record.atMs)) { mutableListOf() }.add(record)
    }
    return out
}

/** 导出文件名。SAF 选择器上显示的名字可以带中文，文件名本身保持 ASCII。 */
private const val EXPORT_DISPLAY_NAME = "签到记录.txt"
private const val EXPORT_FILE_NAME = "sign-records.txt"

/** 分享用的导出目录（与 `file_paths.xml` 里放行的目录必须一致）。 */
private const val EXPORT_DIR = "records-export"
