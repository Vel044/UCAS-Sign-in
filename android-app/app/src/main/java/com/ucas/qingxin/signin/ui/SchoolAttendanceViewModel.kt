package com.ucas.qingxin.signin.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ucas.qingxin.signin.QingxinApp
import com.ucas.qingxin.signin.course.SchoolAttendanceRepository
import com.ucas.qingxin.signin.course.SchoolAttendanceResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

internal data class SchoolAttendanceUiState(
    val studentNo: String = "",
    val progress: String? = null,
    val result: SchoolAttendanceResult? = null,
    val error: String? = null,
)

internal class SchoolAttendanceViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as QingxinApp
    private val _ui = MutableStateFlow(SchoolAttendanceUiState())
    val ui = _ui.asStateFlow()
    private var loadJob: Job? = null
    private var activeDates: ClosedRange<LocalDate>? = null

    fun load(studentNo: String, dates: ClosedRange<LocalDate>) {
        if (loadJob?.isActive == true && activeDates == dates && _ui.value.studentNo == studentNo) return
        loadJob?.cancel()
        if (!app.isReady) {
            _ui.value = SchoolAttendanceUiState(studentNo, error = "应用初始化失败，请重启后重试")
            return
        }
        activeDates = dates
        _ui.value = SchoolAttendanceUiState(studentNo, progress = "正在查询学校签到历史")
        loadJob = viewModelScope.launch {
            val repository = SchoolAttendanceRepository(app.api, app.authRepository)
            try {
                val result = repository.load(studentNo, dates) { progress ->
                    _ui.value = SchoolAttendanceUiState(studentNo, progress = progress)
                }
                _ui.value = SchoolAttendanceUiState(studentNo, result = result)
            } catch (_: CancellationException) {
                coroutineContext.ensureActive()
                _ui.value = SchoolAttendanceUiState(studentNo, error = "登录状态已变化，请重新打开本页")
            }
        }
    }

    fun leave() {
        loadJob?.cancel()
        activeDates = null
        _ui.value = SchoolAttendanceUiState()
    }
}
