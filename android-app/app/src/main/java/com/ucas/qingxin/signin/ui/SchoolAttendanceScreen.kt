package com.ucas.qingxin.signin.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ucas.qingxin.signin.course.CourseAttendanceHistory
import com.ucas.qingxin.signin.course.CourseRepository
import com.ucas.qingxin.signin.course.SchoolAttendance
import com.ucas.qingxin.signin.course.SchoolAttendanceResult
import com.ucas.qingxin.signin.course.SchoolSignState
import com.ucas.qingxin.signin.util.CourseTimeDisplay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
internal fun SchoolAttendanceScreen(
    studentNo: String,
    vm: SchoolAttendanceViewModel,
    onBack: () -> Unit,
) {
    val defaultDates = SchoolAttendance.semesterWindow(LocalDate.now(CourseRepository.ZONE))
    var start by rememberSaveable(studentNo) { mutableStateOf(defaultDates.start.toString()) }
    var end by rememberSaveable(studentNo) { mutableStateOf(defaultDates.endInclusive.toString()) }
    var showingStartPicker by rememberSaveable(studentNo) { mutableStateOf<Boolean?>(null) }
    var selectedCourse by rememberSaveable(studentNo) { mutableStateOf<String?>(null) }
    val state by vm.ui.collectAsStateWithLifecycle()
    val visible = state.takeIf { it.studentNo == studentNo }
    val result = visible?.result
    val startDate = LocalDate.parse(start)
    val endDate = LocalDate.parse(end)
    val course = result?.courses?.firstOrNull { it.id == selectedCourse }

    LaunchedEffect(vm, studentNo) {
        vm.load(studentNo, if (startDate <= endDate) startDate..endDate else defaultDates)
    }
    DisposableEffect(vm, studentNo) { onDispose(vm::leave) }
    BackHandler(enabled = course != null) { selectedCourse = null }

    if (course != null) {
        SchoolAttendanceDetail(course, result, onBack = { selectedCourse = null })
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { HistoryTitle("学校签到历史", onBack) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ showingStartPicker = true }, Modifier.fillMaxWidth()) { Text("开始日期  $start") }
                    OutlinedButton({ showingStartPicker = false }, Modifier.fillMaxWidth()) { Text("结束日期  $end") }
                    if (startDate > endDate) {
                        Text("开始日期必须早于或等于结束日期", color = MaterialTheme.colorScheme.error)
                    }
                    Button(
                        onClick = { vm.load(studentNo, startDate..endDate) },
                        enabled = startDate <= endDate,
                    ) { Text("查询") }
                }
            }
        }
        val progress = visible?.progress
        if (progress != null || visible == null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(progress ?: "正在查询学校签到历史")
                }
            }
        }
        visible?.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
        if (result != null) {
            item { HistorySummary(result) }
            items(result.courses, key = { it.id }) { group ->
                Card(onClick = { selectedCourse = group.id }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(group.course.name, style = MaterialTheme.typography.titleMedium)
                        val description = (group.teachers + group.course.courseNum).filter { it.isNotBlank() }
                        if (description.isNotEmpty()) Text(description.joinToString(" · "))
                        Text(if (group.hasVerifiedAttendance) "共 ${group.lessons.size} 节 · 已签到 ${group.signedCount} 次"
                            else "共 ${group.lessons.size} 节")
                        if (group.pendingCount > 0) Text("${group.pendingCount} 节签到状态待核实")
                    }
                }
            }
        }
    }

    showingStartPicker?.let { isStart ->
        HistoryDatePicker(if (isStart) startDate else endDate, onDismiss = { showingStartPicker = null }) { date ->
            if (isStart) start = date.toString() else end = date.toString()
            showingStartPicker = null
        }
    }
}

@Composable
private fun HistoryTitle(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("返回") }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryDatePicker(date: LocalDate, onDismiss: () -> Unit, onSelected: (LocalDate) -> Unit) {
    val picker = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                picker.selectedDateMillis?.let { onSelected(Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate()) }
            }, enabled = picker.selectedDateMillis != null) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    ) { DatePicker(state = picker) }
}

@Composable
private fun HistorySummary(result: SchoolAttendanceResult) {
    var showUnavailable by rememberSaveable(result.dates.start.toString(), result.dates.endInclusive.toString()) {
        mutableStateOf(false)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${result.dates.start} 至 ${result.dates.endInclusive}", style = MaterialTheme.typography.titleSmall)
        if (result.weeksRead < result.totalWeeks) Text("已取得 ${result.weeksRead} / ${result.totalWeeks} 周课表")
        if (result.unavailableWeeks.isNotEmpty()) {
            TextButton(onClick = { showUnavailable = !showUnavailable }) {
                Text(if (showUnavailable) "收起未取得的课表" else "${result.unavailableWeeks.size} 周课表未取得")
            }
            if (showUnavailable) result.unavailableWeeks.forEach { Text("$it 至 ${it.plusDays(6)}") }
        }
        result.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (result.courses.isEmpty()) {
            if (result.weeksRead > 0) Text("已取得的课表暂无课次")
        } else {
            Text("${result.courses.size} 门课程")
            if (result.courses.any { it.hasVerifiedAttendance }) {
                Text("合计已签到 ${result.courses.sumOf { it.signedCount }} 次")
            }
            val pending = result.courses.sumOf { it.pendingCount }
            if (pending > 0) Text("$pending 节签到状态待核实")
        }
    }
}

@Composable
private fun SchoolAttendanceDetail(
    group: CourseAttendanceHistory,
    result: SchoolAttendanceResult,
    onBack: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { HistoryTitle(group.course.name, onBack) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${result.dates.start} 至 ${result.dates.endInclusive}")
                Text("共 ${group.lessons.size} 节")
                if (group.hasVerifiedAttendance) Text("已签到 ${group.signedCount} 次")
                if (group.pendingCount > 0) Text("${group.pendingCount} 节签到状态待核实")
                group.schoolSignedTotal?.let { Text("学校整门课统计已签到 $it 次") }
                if (result.weeksRead < result.totalWeeks) Text("部分周课表未取得，课次可能不完整。")
            }
        }
        item { Text("全部课次", style = MaterialTheme.typography.titleMedium) }
        items(group.lessons, key = { it.key }) { lesson ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${lesson.date}  ${CourseTimeDisplay.range(lesson.course.beginTime, lesson.course.endTime)}")
                    if (lesson.teachers.isNotEmpty()) Text(lesson.teachers.sorted().joinToString("、"))
                    Text(when (lesson.status) {
                        SchoolSignState.Signed -> "已签到"
                        SchoolSignState.Unsigned -> "学校未标记签到"
                        SchoolSignState.Unknown -> "签到状态待核实"
                        SchoolSignState.Upcoming -> "未开始"
                        SchoolSignState.InProgress -> "上课中"
                    })
                }
            }
        }
    }
}
