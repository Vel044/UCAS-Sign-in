package com.ucas.qingxin.signin.network

import com.ucas.qingxin.signin.BuildConfig
import com.ucas.qingxin.signin.data.Course
import com.ucas.qingxin.signin.data.CourseQueryResult
import com.ucas.qingxin.signin.data.SchoolSession
import com.ucas.qingxin.signin.data.SignOutcome
import com.ucas.qingxin.signin.data.SignResult
import com.ucas.qingxin.signin.logging.LogKind
import com.ucas.qingxin.signin.logging.SignLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Confirmed UCAS iClass / 轻新课堂 endpoints from original RememberSignIn APK.
 * Do not invent additional APIs.
 */
class QingxinApiService(
    private val client: OkHttpClient = defaultClient(),
    /**
     * 签到相关的诊断日志；`null` 表示不记（单测与不关心日志的调用方）。
     *
     * 放在 API 层而不是界面层，是因为**最有诊断价值的东西只有这里有**：
     * 学校响应正文。界面能拿到的是已经翻译过的 [SignResult]，
     * 一旦「已失效」这类含糊拒绝出现，没有正文就无法分辨是码过期还是参数不对。
     */
    private val log: SignLog? = null,
) {
    companion object {
        /**
         * 后端端点一律来自 [BuildConfig]（由本地 local.properties 注入），
         * **不得在此硬编码主机或端口**：仓库为公开仓库，端点不应入库。
         */
        val BASE_URL: String = BuildConfig.API_BASE_URL

        /** 登录请求体内嵌入的校方校验地址模板（由本地配置注入）。 */
        val MOBILE_CHECK_TEMPLATE: String = BuildConfig.VERIFY_URL_TEMPLATE

        /** 7 位节次 ID（`courseSchedId`）。 */
        private val SEVEN_DIGIT_ID = Regex("""\d{7}""")

        /** 32 位课表 UUID（`timeTableId`，比较前会先去掉连字符）。 */
        private val HEX32_ID = Regex("""[0-9a-fA-F]{32}""")

        const val USER_AGENT = "student_5.0.1.2_android_12_20_100000000000000_110000"
        const val USER_AGENT_LOGIN = "student_5.0.1.2_android_12_20__110000"

        /** Confirmed in original app: school-clock sample valid for 30s. */
        const val TIME_SYNC_TTL_MS = 30_000L
        /** Confirmed in original app: QR refresh uses min(5000, remaining sync TTL). */
        const val QR_REFRESH_CAP_MS = 5_000L
        /** 签到二维码自动锁定：开课前 25 分钟至下课前。 */
        const val SIGN_WINDOW_LEAD_MS = 25L * 60L * 1000L

        /** `yyyyMMdd` 的长度，用于判定日期字段是否可用。 */
        private const val DAY_KEY_LENGTH = 8

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
    }

    suspend fun login(email: String, password: String): SchoolSession = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("phone", email.trim())
            .add("password", password)
            .add("verificationType", "1")
            .add("verificationUrl", MOBILE_CHECK_TEMPLATE)
            .add("userLevel", "1")
            .build()
        val json = executeJson(
            requestBuilder("user/login.action", session = null)
                .header("User-Agent", USER_AGENT_LOGIN)
                .post(body)
                .build(),
        )
        parseSession(json)
    }

    /** Resume with identity only (verificationType=2), as in original app. */
    suspend fun resumeSession(identity: String): SchoolSession = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("phone", identity.trim())
            .add("password", "")
            .add("verificationType", "2")
            .add("verificationUrl", "")
            .add("userLevel", "1")
            .build()
        val json = executeJson(
            requestBuilder("user/login.action", session = null)
                .header("User-Agent", USER_AGENT_LOGIN)
                .post(body)
                .build(),
        )
        parseSession(json)
    }

    /** Confirmed: POST common/get_timestamp.do?id=0 → {STATUS, timestamp} */
    suspend fun getServerTimestampMs(): Long = withContext(Dispatchers.IO) {
        val json = executeJson(
            requestBuilder("common/get_timestamp.do?id=0", session = null)
                .post(FormBody.Builder().build())
                .build(),
            timeoutSec = 6,
        )
        if (json.optString("STATUS") != "0") {
            throw ApiException("TIME_SYNC_BAD_RESPONSE", "学校校时数据异常，请重试")
        }
        val ts = json.opt("timestamp")
        if (ts !is Number) {
            throw ApiException("TIME_SYNC_BAD_RESPONSE", "学校校时数据异常，请重试")
        }
        val value = ts.toLong()
        if (value !in 1_000_000_000_000L until 8_640_000_000_000_001L) {
            throw ApiException("TIME_SYNC_BAD_RESPONSE", "学校校时数据异常，请重试")
        }
        value
    }

    suspend fun getTodayCourses(session: SchoolSession, dateStr: String): CourseQueryResult =
        withContext(Dispatchers.IO) {
            val form = FormBody.Builder()
                .add("id", session.userId)
                .add("dateStr", dateStr)
                .build()
            val dayJson = executeJson(
                requestBuilder("course/get_stu_course_sched.action", session)
                    .post(form)
                    .build(),
            )
            val dayCourses = parseSchedArray(dayJson.optJSONArray("result"), dateStr)
            if (dayJson.optString("STATUS") == "0" && dayCourses.isNotEmpty()) {
                return@withContext CourseQueryResult(dayCourses, false, "已更新当天课程")
            }

            val weekJson = executeJson(
                requestBuilder("course/get_stu_course_sched_week.action", session)
                    .post(form)
                    .build(),
            )
            if (weekJson.optString("STATUS") == "0") {
                throw ApiException("SCHEDULE_REJECTED", "学校暂未返回可用课表，请重新查询")
            }
            val result = weekJson.optJSONArray("result") ?: JSONArray()
            val all = mutableListOf<Course>()
            val today = mutableListOf<Course>()
            for (i in 0 until result.length()) {
                val dayObj = result.optJSONObject(i) ?: continue
                val rawDate = dayObj.optString("dateStr").replace("-", "")
                val list = parseSchedArray(dayObj.optJSONArray("schedData"), rawDate)
                all += list
                if (rawDate == dateStr) today += list
            }
            when {
                today.isNotEmpty() -> CourseQueryResult(today, false, "已从周课表更新当天课程")
                all.isNotEmpty() -> CourseQueryResult(all, true, "当天没有课程，已显示本周课程")
                else -> CourseQueryResult(emptyList(), false, "当天及本周暂无课程，可检查日期或切换学号")
            }
        }

    /**
     * 抓取 [anchorDateStr] 所在周的**全部**课程（所有日期），用于讲座的多日扫描。
     *
     * 讲座散布在多个日期上，逐日查询代价过高；周课表一次请求覆盖整周，
     * 调用方再按日期范围自行裁剪（见 `LectureRepository.loadRange`）。
     * [Course.day] 取各天的 `dateStr` 并统一成 `yyyyMMdd`，
     * 使调用方不必再关心学校返回的是 `2026-09-15` 还是 `20260915`。
     */
    suspend fun getWeekCourses(session: SchoolSession, anchorDateStr: String): List<Course> =
        withContext(Dispatchers.IO) {
            val form = FormBody.Builder()
                .add("id", session.userId)
                .add("dateStr", anchorDateStr)
                .build()
            val json = executeJson(
                requestBuilder("course/get_stu_course_sched_week.action", session)
                    .post(form)
                    .build(),
            )
            val result = json.optJSONArray("result")
            // 本接口的 STATUS 约定在既有代码里并不一致（getTodayCourses 的分支把 STATUS=="0"
            // 当作「无课表」而抛错）。这里改以「是否真的拿到 result 数组」为准：
            // 只要有数据就解析，确实没有数据才按失败上报，
            // 免得因状态码约定不清而丢掉本该展示的讲座或误报登录失效。
            if (result == null) {
                if (json.optString("STATUS") == "0") return@withContext emptyList()
                throw ApiException("SCHEDULE_REJECTED", "学校暂未返回可用课表，请重新查询")
            }
            val out = ArrayList<Course>()
            for (i in 0 until result.length()) {
                val dayObj = result.optJSONObject(i) ?: continue
                val dayKey = normalizeDayKey(dayObj.optString("dateStr"))
                // 日期缺失或异常的整天都无法参与范围过滤，直接跳过而不是猜一个日期。
                if (dayKey.length != DAY_KEY_LENGTH) continue
                out += parseSchedArray(dayObj.optJSONArray("schedData"), dayKey)
            }
            out
        }

    /**
     * Confirmed one-click sign:
     * GET course/stu_scan_sign.action?<idParam>=&timestamp=&id=
     * Header sessionId required.
     *
     * @param courseIdOrUuid 7 位节次 ID（`courseSchedId`）**或** 32 位课表 UUID
     *   （`timeTableId`）。学校这个接口对两种标识各有一个查询参数，参数名取决于
     *   标识形态，因此这里统一接收「二选一」的原始输入，再按形态派发 ——
     *   若只接受 `courseSchedId`，那些只有 UUID 的课程（以及手动录入 UUID 的讲座，
     *   见 [com.ucas.qingxin.signin.ui.LectureViewModel.signManual]）会带着空参数
     *   发出必然失败的请求。
     */
    suspend fun submitAttendance(
        session: SchoolSession,
        courseIdOrUuid: String,
        schoolTimestampMs: Long,
    ): SignResult = withContext(Dispatchers.IO) {
        val (idParam, idValue) = signIdParameter(courseIdOrUuid)
        val url = (BASE_URL + "course/stu_scan_sign.action").toHttpUrl().newBuilder()
            .addQueryParameter(idParam, idValue)
            .addQueryParameter("timestamp", schoolTimestampMs.toString())
            .addQueryParameter("id", session.userId)
            .build()
        val json = executeJsonLogged(
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Cache-Control", "no-store")
                .header("sessionId", session.sessionId)
                .get()
                .build(),
            timeoutSec = 10,
            source = "一键 / 手动打卡（GET）",
        )
        parseSignResult(json)
    }

    /**
     * 发一次请求并记日志，返回解析后的 JSON。
     *
     * 日志写在**这里**而不是各个调用点：这样「请求地址 + 响应正文 + 判定结果」
     * 一定成组出现，不会出现只记了一半、事后无法配对的日志。
     */
    private fun executeJsonLogged(request: Request, timeoutSec: Long, source: String): JSONObject {
        val logger = log
        if (logger == null) return executeJson(request, timeoutSec)

        logger.append(
            LogKind.REQUEST,
            "来源" to source,
            "方法" to request.method,
            "地址" to SignLog.maskOwnId(request.url.toString()),
        )
        return try {
            val (json, body) = executeJsonWithBody(request, timeoutSec)
            logger.append(LogKind.RESPONSE, "HTTP" to "200", "正文" to body)
            json
        } catch (e: Exception) {
            // 失败也要记：现场最常见的失败就是网络层直接抛。
            logger.append(
                LogKind.RESPONSE,
                "异常" to (e::class.java.simpleName + ": " + (e.message ?: "")),
            )
            throw e
        }
    }

    /** Build QR content URL exactly as original ez0.d(). */
    fun buildSignQrUrl(courseIdOrUuid: String, schoolTimestampMs: Long): String {
        val (param, value) = signIdParameter(courseIdOrUuid)
        return (BASE_URL + "course/stu_scan_sign.action").toHttpUrl().newBuilder()
            .addQueryParameter(param, value)
            .addQueryParameter("timestamp", schoolTimestampMs.toString())
            .build()
            .toString()
    }

    /**
     * 组装扫码通道的签到地址：把二维码里的 query 拼到**本项目自己的**学校域名上。
     *
     * 对应小程序的 `o.data.schoolHttpsUrl + n`。本项目的 [BASE_URL] 已经含 `/app/`
     * 前缀（与小程序配置里的 host 少一段 `app/` 正好互补），所以这里只再补
     * `ScanPayload.SIGN_PATH` 即可。
     *
     * @param path 相对 [BASE_URL] 的接口路径，取自
     *   `com.ucas.qingxin.signin.attendance.ScanPayload.SignRequest.path`。
     */
    fun buildScanUrl(path: String, query: String): String {
        val base = BASE_URL + path
        if (query.isBlank()) return base
        // 用 encodedQuery 而不是 addQueryParameter：二维码里的 query 本来就是编码过的，
        // 再走一遍参数编码会把 `%2F` 之类变成 `%252F`，学校那边就认不出了。
        return runCatching { base.toHttpUrl().newBuilder().encodedQuery(query).build().toString() }
            .getOrElse {
                // 二维码里带了 HttpUrl 认为非法的字符（极罕见）。不静默丢参数，
                // 原样拼上去交给服务器判 —— 我们这边只保证不因一个畸形二维码崩掉。
                "$base?$query"
            }
    }

    /**
     * 扫码签到：把二维码里读到的参数**原样**提交。
     *
     * ## 与小程序逐字对齐
     * `cliclScanCode` 命中 `app/course/stu_scan_sign.action` 之后做的是：
     * 取 `?` 之后的 query → 拼到自己学校的域名下 → 追加 `&id=<uid>` → **POST**
     * （空表单体）→ 以 `STATUS == "0"` 判成功。这里三点全部照做：
     *
     * 1. **POST 而不是 GET**。一键签到（[submitAttendance]）走 GET，那是原版
     *    RememberSignIn 的形态；扫码这条通道小程序用的是 POST。两者在本项目里
     *    各自保留，谁也不要「统一」成对方 —— 同一个学校对不同历史实现的兼容程度不同，
     *    把已验证过的请求形态改掉是在拿唯一稳定的参照物冒险。
     * 2. **`timestamp` 不新增**：二维码带了就原样带着，二维码没带就**不补**
     *    （见 `ScanPayload.DirectSign.needsTimestamp`）。小程序这一步只有
     *    `二维码query + "&id=" + uid`，从不写时间戳；1.3.1 曾在这里自行补一个，
     *    已按「不偏离唯一已知可用的客户端」改掉（`docs/lecture-signin-research.md` §10.16）。
     * 3. **`id` 用本次登录用户自己的** —— 小程序的 `userInfo.uid` 与我们的
     *    `session.userId` 是同一个字段。
     *
     * ## 与小程序唯一的**故意**差异：域名
     * 二维码若带完整 url，小程序会丢掉它的域名、只用自己配置的学校域名；这里同样丢掉，
     * 理由不止「保持一致」——`sessionId` 是 [BASE_URL] 这台服务器签发的，
     * 发到别的域名上必然认证失败，而且等于把会话凭据主动送给二维码里随便写的域名。
     * 因此传进来的地址必须是**已经落在 [BASE_URL] 上**的（见 [buildScanUrl]）。
     */
    suspend fun submitScanSign(session: SchoolSession, path: String, query: String): SignResult =
        withContext(Dispatchers.IO) {
            val url = withOwnUserId(buildScanUrl(path, query), session.userId)
            val json = executeJsonLogged(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Cache-Control", "no-store")
                    .header("sessionId", session.sessionId)
                    // 空表单体 + `application/x-www-form-urlencoded`：这正是小程序的
                    // `requestPost(url, null, {})`（缺省 content-type 同为表单）。
                    .post(FormBody.Builder().build())
                    .build(),
                timeoutSec = 10,
                source = "扫码打卡（POST 空表单体）",
            )
            parseSignResult(json, requireStuSignStatus = false)
        }

    /**
     * 追加本次登录用户自己的 `id`。
     *
     * 与小程序一样是「在末尾追加 `&id=`」，但**先清掉二维码里可能自带的 `id`**：
     * 小程序的 `url + "&id=" + uid` 在遇到恶意二维码（`…&id=<别人的 uid>`）时，
     * 服务端取到哪一个取决于它自己的解析实现 —— 这属于用户**完全无法察觉**的错签。
     * 用 [okhttp3.HttpUrl.Builder.setQueryParameter] 是同名覆盖，正常二维码
     * （本来就不带 id）的行为与小程序的字符串拼接完全一致。
     */
    private fun withOwnUserId(rawUrl: String, userId: String): String {
        val parsed = rawUrl.toHttpUrlOrNull()
        if (parsed != null) {
            return parsed.newBuilder().setQueryParameter("id", userId).build().toString()
        }
        // 退化路径：地址不合法到连 HttpUrl 都解析不出来（几乎不会发生，
        // 因为传来的地址是我们自己用 BASE_URL 拼的）。仍然尽力剔除自带的 id。
        val cleaned = rawUrl.replace(Regex("""[?&]id=[^&#]*"""), "").trimEnd('&', '?')
        val sep = if (cleaned.contains('?')) "&" else "?"
        return cleaned + sep + "id=" + userId
    }

    /**
     * 「7 位节次 ID / 32 位课表 UUID」→ 学校接口的查询参数名与取值。
     *
     * 这是全项目**唯一**的标识形态判定点：一键签到（[submitAttendance]）与
     * 二维码内容构造（[buildSignQrUrl]）都走这里，避免两处规则漂移 ——
     * 一旦漂移，就会出现「扫同一个码能签、点按钮不能签」这类极难排查的问题。
     */
    private fun signIdParameter(courseIdOrUuid: String): Pair<String, String> {
        val trimmed = courseIdOrUuid.trim()
        val compact = trimmed.replace("-", "")
        return when {
            SEVEN_DIGIT_ID.matches(trimmed) -> "courseSchedId" to trimmed
            HEX32_ID.matches(compact) -> "timeTableId" to compact.uppercase(Locale.ROOT)
            else -> throw ApiException("COURSE_ID_INVALID", "请输入 7 位课程 ID 或 32 位 UUID")
        }
    }

    private fun parseSession(json: JSONObject): SchoolSession {
        if (json.optString("STATUS") != "0") {
            throw ApiException("LOGIN_REJECTED", "学校账号验证失败，请重新输入密码")
        }
        val result = json.optJSONObject("result")
            ?: throw ApiException("LOGIN_BAD_RESPONSE", "学校登录返回不完整")
        val id = result.optString("id").trim()
        val sessionId = result.optString("sessionId").trim()
        val studentNo = result.optString("studentNo").trim()
        if (id.isEmpty() || sessionId.isEmpty() || studentNo.isEmpty()) {
            throw ApiException("LOGIN_BAD_RESPONSE", "学校登录未返回完整身份，已停止操作")
        }
        return SchoolSession(id, sessionId, studentNo)
    }

    private fun parseSchedArray(array: JSONArray?, day: String): List<Course> {
        if (array == null) return emptyList()
        val out = ArrayList<Course>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            var name = o.optString("courseName").trim()
            if (name.isEmpty()) name = "未命名课程"
            out += Course(
                id = o.optString("id").trim(),
                uuid = o.optString("uuid").trim(),
                name = name,
                teacher = o.optString("teacherName").trim(),
                beginTime = o.optString("classBeginTime").trim(),
                endTime = o.optString("classEndTime").trim(),
                day = day,
                signed = o.optString("signStatus") == "1",
                // 课程层标识：讲座发现的回退阶梯与课程注册表桥接都依赖它们。
                // 学校返回的可能是数字也可能是字符串，optString 一并接受。
                courseId = o.optText("courseId"),
                courseNum = o.optText("courseNum"),
            )
        }
        return out
    }

    /**
     * 读取字符串字段，并把「显式 null / 缺失」统一成空串。
     *
     * 为什么不用裸 `optString`：学校偶尔把 `courseId` 这类字段发成 JSON `null`，
     * 而 `optString` 会把它变成字面量 `"null"`，下游拿去做 id 匹配就会莫名其妙地对不上。
     */
    private fun JSONObject.optText(key: String): String {
        val raw = optString(key).trim()
        return if (raw == "null") "" else raw
    }

    /** `dateStr` 可能是 `yyyy-MM-dd` 或 `yyyyMMdd`，统一成 `yyyyMMdd` 供比较。 */
    private fun normalizeDayKey(raw: String): String {
        val d = raw.trim().replace("-", "").replace("/", "")
        return if (d.length >= DAY_KEY_LENGTH) d.take(DAY_KEY_LENGTH) else d
    }

    /**
     * 解析签到结果。
     *
     * @param requireStuSignStatus 是否必须看到 `result.stuSignStatus == "1"` 才算签到成功。
     *
     *   一键签到（GET）的响应里带这个字段，所以要求它 —— 它能挡掉
     *   「`STATUS=0` 但其实不在签到时间」这种假成功。
     *
     *   扫码通道（POST，与小程序一致）则**只认 `STATUS == "0"`**：小程序的
     *   `gotoSignWithUrl` 就是这么判的。我校这两条通道的响应形态由学校那侧决定，
     *   我们无法穷举；若在扫码通道上强求一个可能根本不存在的字段，
     *   真签到成功了也会被我们显示成「不在签到时间」。
     */
    private fun parseSignResult(json: JSONObject, requireStuSignStatus: Boolean = true): SignResult {
        // 7.0 平台（app/common/stuScanSign）把结果包在 data 里 —— 小程序的
        // gotoNewSignWithUrl 读的正是 o.data.STATUS；旧通道则在顶层。
        val root = if (json.has("STATUS") || json.has("result")) {
            json
        } else {
            json.optJSONObject("data") ?: json
        }
        val result = root.optJSONObject("result") ?: JSONObject()
        val status = root.optString("STATUS")
        val errCode = root.optString("ERRCODE").take(64)
        var msg = result.optString("msg")
        if (msg.isBlank()) msg = root.optString("ERRMSG")
        if (msg.isBlank()) msg = root.optString("msg")
        if (msg.isBlank()) msg = root.optString("message")
        val stuSignStatus = result.optString("stuSignStatus")
        val expired = Pattern.compile(
            "(二维码|签到码).*(失效|过期)|timestamp.*(invalid|expired)",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL,
        ).matcher(msg).find()
        val outside = Pattern.compile("未在上课时间|不在.*签到时间|不是上课时间|未选.*课|不属于.*课|已签到|重复签到")
            .matcher(msg).find()
        // 「学校明确拒绝了这次签到」的形态：STATUS 说成功，但它自己给的签到状态不是 1。
        // 仅在要求该字段的通道上有意义（见 requireStuSignStatus 的说明）。
        val rejectedBySignStatus = status == "0" && stuSignStatus.isNotBlank() && stuSignStatus != "1"

        val outcome = when {
            expired -> SignOutcome.QR_EXPIRED
            status == "0" && (errCode.isBlank() || errCode == "0") &&
                (!requireStuSignStatus || stuSignStatus == "1") &&
                (!root.has("success") || root.optBoolean("success", true)) -> SignOutcome.SIGNED
            outside || !(requireStuSignStatus && rejectedBySignStatus) ->
                SignOutcome.OUTSIDE_SIGN_WINDOW
            else -> SignOutcome.SIGN_RESULT_UNKNOWN
        }
        val message = when (outcome) {
            SignOutcome.SIGNED -> "签到成功"
            SignOutcome.QR_EXPIRED -> "学校提示签到码已失效，请刷新后重试"
            SignOutcome.OUTSIDE_SIGN_WINDOW -> when {
                msg.contains("已签到") || msg.contains("重复签到") -> "学校提示已签到，请刷新课程状态"
                msg.contains("未选") || msg.contains("不属于") -> "学校提示当前身份未选此课程"
                else -> "学校未完成签到，请确认上课时间和课程状态"
            }
            SignOutcome.SIGN_RESULT_UNKNOWN ->
                "学校未明确确认签到完成，请查询课程状态后再决定是否重试"
        }
        return SignResult(
            outcome = outcome,
            message = message,
            status = status,
            errCode = errCode,
            stuSignId = result.optString("stuSignId"),
        )
    }

    private fun requestBuilder(path: String, session: SchoolSession?): Request.Builder {
        val builder = Request.Builder()
            .url(BASE_URL + path)
            .header("User-Agent", USER_AGENT)
            .header("Cache-Control", "no-store")
        if (session != null) {
            builder.header("sessionId", session.sessionId)
        }
        return builder
    }

    private fun executeJson(request: Request, timeoutSec: Long = 15): JSONObject =
        executeJsonWithBody(request, timeoutSec).first

    /**
     * 与 [executeJson] 相同，但**同时**把响应正文回传。
     *
     * 只有需要写诊断日志的调用方用它（见 [log] 的说明）：正文是分辨
     * 「学校不认这个标识」与「我们的参数不对」的唯一原始依据。
     */
    private fun executeJsonWithBody(request: Request, timeoutSec: Long = 15): Pair<JSONObject, String> {
        val callClient = if (timeoutSec == 15L) {
            client
        } else {
            client.newBuilder()
                .callTimeout(timeoutSec, TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .build()
        }
        callClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiException("HTTP_${response.code}", "网络请求失败 (${response.code})")
            }
            return JSONObject(body.ifBlank { "{}" }) to body
        }
    }
}

class ApiException(val code: String, message: String) : Exception(message)
