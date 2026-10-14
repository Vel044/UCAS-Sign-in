package com.ucas.qingxin.signin.attendance

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/**
 * 「扫到的二维码原文」被解析成了什么。
 *
 * ## 为什么必须有这一层
 * 二维码里装的东西**不止签到一种**。轻新课堂在同一个扫码入口里分流的事情至少有四件：
 * 现场签到、桌面端扫码登录、加入课程、课堂答题。若写成「扫到什么就往签到接口发」，
 * 用户扫到桌面登录码时也会往签到接口打一发，学校只会回一个含糊的失败，
 * 而用户看到的是「签到失败」—— 归因从一开始就是错的。
 * 因此先分类，再决定**发不发请求**、发到哪个路径。
 *
 * ## 分类顺序照抄小程序，一环都不能挪
 * 顺序取自 `pages/ALightClassesPro/ALightClassesProIndex.js` 的 `cliclScanCode`：
 *
 * 1. 含 `app/course/stu_scan_sign.action` → 现场签到（旧 iClass 通道）
 * 2. 含 `app/common/stuScanSign` → 现场签到（7.0 平台通道）
 * 3. 含 `idesktop` → 桌面端扫码登录（**不是签到**）
 * 4. 含 `/schoolCourse/stuJoinCourse/` → 加入课程（**不是签到**）
 * 5. 含 `app/course/saomadati.action` → 课堂答题（小程序里这条分支是空的，等于不管）
 * 6. 都不含 → 退化成「纯编号」
 *
 * 第 1、2 条必须排在 3/4/5 前面：桌面登录码与加入课程码的文本里不会出现签到路径，
 * 但反过来，只要某个签到码的文本里恰好含 `idesktop`（例如页面路径里带这个词），
 * 把 NotSign 判在前面就会把本该签到的码判成「不是签到」。小程序用的正是这个顺序。
 *
 * ## 第 6 条是本项目唯一的额外宽容
 * 学校不同楼栋的班牌实现并不统一，有的把 7 位节次编号直接编成二维码。
 * 小程序对这种码只会提示「请提供有效的二维码」，但我们的用户**正站在班牌前面**，
 * 因此这里退化到 [SignCode]（与手动录入同一套规则、同一套格式校验）。
 * 注意这并没有放宽「什么会被发给服务器」——[SignCode.parse] 不通过就仍归入 [Unknown]，
 * 一个请求都不会发出去。
 *
 * ### 纯数字这一条的边界：它同时是「加入课程邀请码」的形态
 *
 * 补记于 2026-10-13（依据见 `docs/lecture-signin-research.md` §10.15）：
 * 轻新课堂对**纯数字**二维码的处理在两个入口**并不一致** —— 首页扫码判为
 * 「请提供有效的二维码！」，而「我的」页扫码判为**加入课程邀请码**，并提交
 * `{method:"stuJoinCourse", userId, cId:<同一个数字>}`。也就是说，同一串数字
 * 在学校那边有可能被解释成「课程编号」而不是「节次编号」。
 *
 * 实测 `cId` 现在是 5 位（注册表 `64241`、`57000+`、`6960–6962`），离 7 位尚远，
 * 因此**当前不会碰撞**。但若将来 `cId` 涨到 7 位，[SignCode.parse] 就会把一个
 * 「加入课程码」认成「节次编号」并发出一发注定被拒的签到请求。
 * 到那时**不能**靠数字位数去区分（纯数字本身没有足够信息），只能回到
 * 「是否含 [SIGN_PATH_IN_QR]」这个判据上。
 */
sealed interface ScanPayload {

    /**
     * 需要真的发一次签到请求的载荷。
     *
     * 两个通道（旧 iClass / 7.0 平台）的差别**只在于端点路径**，请求形态完全相同，
     * 因此共用这一个接口，而不是在调用点上写两套几乎一样的代码。
     */
    sealed interface SignRequest : ScanPayload {
        /** 相对 [com.ucas.qingxin.signin.network.QingxinApiService.BASE_URL] 的接口路径。 */
        val path: String

        /** 提交时附带的 query（**不含**用户身份 `id`，那个由 API 层统一追加）。 */
        val query: String

        /** 二维码里写的域名，仅用于向用户提示；不会拿它发请求（理由见 [DirectSign.qrHost]）。 */
        val qrHost: String?
    }

    /**
     * 旧 iClass 通道：`app/course/stu_scan_sign.action?<query>`。
     *
     * 这是本项目的主力通道 —— 一键签到走的也是它（见
     * `QingxinApiService.submitAttendance`），因此它也是唯一有大量真实流量验证过的那条。
     */
    data class DirectSign(
        override val path: String,
        override val query: String,
        /** 从 query（必要时退化到整段原文）里认出来的签到编号。 */
        val signCode: SignCode,
        /** 二维码自带的 `timestamp`；null 表示没带，提交时由校时补一个。 */
        val qrTimestamp: String?,
        /**
         * 二维码指向的域名。
         *
         * **只用于提示，绝不用于发请求**：我们的 `sessionId` 是
         * [com.ucas.qingxin.signin.network.QingxinApiService.BASE_URL] 这台服务器签发的，
         * 发到别的域名上必然认证失败，而且等于把会话凭据主动送给二维码里随便写的一个域名。
         *
         * 小程序的做法与此一致：`cliclScanCode` 把二维码里的域名**整个丢掉**，
         * 只取 `?` 之后的 query 拼回自己配置的学校域名
         * （`schoolHttpsUrl = schoolInfo.coursePlatformHttpsUrl`）。
         */
        override val qrHost: String?,
    ) : SignRequest {
        /**
         * 真正提交时使用的 query：保证一定带上学校认识的那个标识参数。
         *
         * 二维码自带 `courseSchedId` / `timeTableId` 时**原样透传**（与小程序一致，
         * 也给学校留了「它自己发出去的参数就按它自己的写法认」的余地）；
         * 两个都没有时才用识别出的编号补一个 —— 补的是 [signCode] 的规范形态
         * （32 位标识会大写，与 [SignCode] 的口径一致）。
         */
        val submitQuery: String
            get() = if (hasSignIdParam) {
                query
            } else {
                ScanQuery.upsert(query, canonicalIdName, canonicalIdValue)
            }

        /**
         * 二维码里是否带着一段可原样重放的 query。
         *
         * 空串只可能来自「整段原文就是一个裸编号」的班牌（见 [parse] 的兜底分支）——
         * 那种码没有 query 可重放，请求只能由我们自己拼完整。
         */
        val isReplay: Boolean get() = query.isNotEmpty()

        /**
         * 是否需要调用方补一个学校校时。
         *
         * ## 为什么**只有**自己拼请求时才补（2026-10-13 修）
         * 轻新课堂的扫码通道是**纯重放**：`requestPost(二维码query + "&id=" + uid, …)`，
         * 它**从不新增 `timestamp`**。因此二维码里已经有 query 时照抄即可 ——
         * 多塞一个 `timestamp` 是**本项目独有的**改动，而学校那句「签到码已失效」
         * 既可能真是码轮换过期，也可能正是被这个多出来的值判掉的
         * （真机实测见 `docs/lecture-signin-research.md` §10.16）。
         * 在拿到反证之前，**不偏离唯一已知可用的客户端**。
         *
         * 反过来，整段原文只有一个裸编号时没有 query 可重放，请求必须由我们拼完整 ——
         * 一个不含任何新鲜度证明的请求没有任何被接受的可能，所以这时才补时间戳。
         */
        val needsTimestamp: Boolean get() = !isReplay

        /**
         * 补上学校校时（只在 [needsTimestamp] 为真时生效，即请求由我们自己拼的那一种）。
         *
         * ## 为什么不无条件用校时**覆盖**二维码里的 timestamp
         * 那样会把「学校自己发出来的一个值」换成「我们本地推算出来的一个值」，
         * 一旦校时样本偏了（RTT 抖动、校时接口被劫持），失败原因会变成一个
         * 用户完全无法理解、我们也无法复现的「签到码失效」。
         * 保留二维码原值则让这条通道**不依赖本机时钟**，与小程序完全一致。
         */
        fun withTimestamp(schoolTimestampMs: Long): String = if (needsTimestamp) {
            ScanQuery.upsert(submitQuery, TIMESTAMP, schoolTimestampMs.toString())
        } else {
            submitQuery
        }

        /** 界面回显用的人类可读名称。 */
        val label: String get() = signCode.label

        private val hasSignIdParam: Boolean
            get() = ScanQuery.param(query, COURSE_SCHED_ID) != null ||
                ScanQuery.param(query, TIME_TABLE_ID) != null

        private val canonicalIdName: String
            get() = if (signCode.courseSchedId.isNotEmpty()) COURSE_SCHED_ID else TIME_TABLE_ID

        private val canonicalIdValue: String
            get() = if (signCode.courseSchedId.isNotEmpty()) {
                signCode.courseSchedId
            } else {
                signCode.timeTableId.uppercase(Locale.ROOT)
            }
    }

    /**
     * 7.0 平台通道：`app/common/stuScanSign…`。
     *
     * 小程序对这条分支是**把整段原文直接 POST**（`gotoNewSignWithUrl(t)`，读 `o.data.STATUS`），
     * 我们会把域名换成 [com.ucas.qingxin.signin.network.QingxinApiService.BASE_URL]
     * —— 理由与 [DirectSign.qrHost] 相同：会话凭据不能外发。
     *
     * 需要如实说明的是：我校（UCAS）实测走的是旧 iClass 通道，这条 7.0 通道
     * **没有可验证的环境**。这里只做到「识别得出来 + 按与旧通道相同的形态提交」，
     * 真伪由服务器回答；识别不出来时它会归入 [Unknown]，不会误当成签到码发出去。
     */
    data class PlatformSign(
        override val path: String,
        override val query: String,
        override val qrHost: String?,
    ) : SignRequest

    /** 认得出「是什么」，但它不是签到码 —— 这种情况下**不发任何请求**。 */
    data class NotSign(val kind: NotSignKind) : ScanPayload

    /** 完全认不出 —— 同样一个请求都不发。 */
    data class Unknown(val raw: String) : ScanPayload

    companion object {
        /**
         * 旧 iClass 签到接口的路径（相对
         * [com.ucas.qingxin.signin.network.QingxinApiService.BASE_URL]，后者已含 `/app/`）。
         */
        const val SIGN_PATH = "course/stu_scan_sign.action"

        private const val COURSE_SCHED_ID = "courseSchedId"
        private const val TIME_TABLE_ID = "timeTableId"
        private const val TIMESTAMP = "timestamp"

        private const val SIGN_PATH_IN_QR = "app/course/stu_scan_sign.action"
        private const val PLATFORM_PATH_IN_QR = "app/common/stuScanSign"
        private const val DESKTOP_MARKER = "idesktop"
        private const val JOIN_COURSE_MARKER = "/schoolCourse/stuJoinCourse/"
        private const val EXAM_ANSWER_MARKER = "app/course/saomadati.action"

        /** 二维码原文 → 载荷类别。纯函数、不触网，因此可以离线穷尽单测。 */
        fun parse(raw: String): ScanPayload {
            val text = raw.trim()
            if (text.isEmpty()) return Unknown(raw)

            val signIndex = text.indexOf(SIGN_PATH_IN_QR)
            if (signIndex >= 0) return parseDirectSign(text, signIndex)

            val platformIndex = text.indexOf(PLATFORM_PATH_IN_QR)
            if (platformIndex >= 0) return parsePlatformSign(text, platformIndex)

            if (text.contains(DESKTOP_MARKER)) return NotSign(NotSignKind.IDESKTOP_LOGIN)
            if (text.contains(JOIN_COURSE_MARKER)) return NotSign(NotSignKind.JOIN_COURSE)
            if (text.contains(EXAM_ANSWER_MARKER)) return NotSign(NotSignKind.EXAM_ANSWER)

            // 兜底：整段原文就是一个纯编号（现场班牌直接把编号编成二维码）。
            val code = SignCode.parse(text) ?: return Unknown(raw)
            return DirectSign(
                path = SIGN_PATH,
                query = "",
                signCode = code,
                qrTimestamp = null,
                qrHost = null,
            )
        }

        private fun parseDirectSign(text: String, signIndex: Int): ScanPayload {
            val rawQuery = queryAfter(text, signIndex + SIGN_PATH_IN_QR.length)
            val code = resolveSignCode(
                courseSchedId = ScanQuery.param(rawQuery, COURSE_SCHED_ID),
                timeTableId = ScanQuery.param(rawQuery, TIME_TABLE_ID),
                fallback = rawQuery.ifEmpty { text },
            ) ?: return Unknown(text)
            return DirectSign(
                path = SIGN_PATH,
                // 若 `?` 之后根本不像 query（连一个 `=` 都没有），说明班牌是把编号裸着
                // 拼在路径后面的（`…action?1234567`）。此时不能把这段当 query 再拼回去，
                // 否则会拼出 `1234567&courseSchedId=1234567` 这种四不像的请求。
                query = if (rawQuery.contains('=')) rawQuery else "",
                signCode = code,
                qrTimestamp = ScanQuery.param(rawQuery, TIMESTAMP),
                qrHost = text.toHttpUrlOrNull()?.host,
            )
        }

        /**
         * 7.0 平台通道：路径从原文里取，并去掉开头的 `app/`。
         *
         * 必须去掉：`BASE_URL` 已含 `/app/`，原样拼会得到 `…/app/app/common/…`。
         */
        private fun parsePlatformSign(text: String, platformIndex: Int): ScanPayload {
            val tail = text.substring(platformIndex)
            val rawPath = tail.substringBefore('?').substringBefore('#').trim()
            val query = if ('?' in tail) {
                tail.substringAfter('?').substringBefore('#').trim()
            } else {
                ""
            }
            return PlatformSign(
                path = rawPath.removePrefix("app/"),
                query = query,
                qrHost = text.toHttpUrlOrNull()?.host,
            )
        }

        /** 取 `?` 之后、`#` 之前的原始 query。 */
        private fun queryAfter(text: String, from: Int): String =
            text.substring(from).removePrefix("?").substringBefore('#').trim()

        /**
         * 认签到编号：先看具名参数，再退化到整段的宽松形态。
         *
         * 顺序不能颠倒 —— 具名参数是学校自己写进去的，可信度最高；
         * 宽松退化只是为了兜住「班牌把编号直接拼在 `?` 后面」这类实现。
         */
        private fun resolveSignCode(
            courseSchedId: String?,
            timeTableId: String?,
            fallback: String,
        ): SignCode? = courseSchedId?.let(::asCourseSchedId)
            ?: timeTableId?.let(::asTimeTableId)
            ?: SignCode.parse(fallback)

        private fun asCourseSchedId(value: String): SignCode? =
            SignCode.parse(value)?.takeIf { it.courseSchedId.isNotEmpty() }

        private fun asTimeTableId(value: String): SignCode? =
            SignCode.parse(value)?.takeIf { it.timeTableId.isNotEmpty() }
    }
}

/** 认得出的、**不是签到**的二维码种类。 */
enum class NotSignKind(val label: String, val hint: String) {
    /** `idesktop`：桌面端扫码登录，须在教室电脑上完成。 */
    IDESKTOP_LOGIN("桌面端扫码登录码", "这个码要在教室电脑上扫，本应用无法代签"),

    /** `/schoolCourse/stuJoinCourse/`：加入课程邀请码。 */
    JOIN_COURSE("加入课程码", "加入课程请用轻新课堂小程序"),

    /** `app/course/saomadati.action`：课堂答题码。 */
    EXAM_ANSWER("课堂答题码", "课堂答题请用轻新课堂小程序"),
}

/**
 * 「拼在 URL 里的 query」的极小工具。
 *
 * 刻意不走 [okhttp3.HttpUrl] 的解析：这里处理的常常是**残缺**的 query
 * （班牌实现千奇百怪），而 `HttpUrl` 对非法输入的行为是「整体解析失败」，
 * 那会把一个只差一个参数、本来还能签的请求直接判死。按 `&` / `=` 两段拆
 * 反而更稳：解析不了的片段原样留着，不影响我们替换自己认得的那几个键。
 */
private object ScanQuery {
    /** 取同名参数的值；缺失或为空串都算「没有」。 */
    fun param(query: String, name: String): String? = query.split('&')
        .asSequence()
        .map { it.split('=', limit = 2) }
        .firstOrNull { it.size == 2 && it[0].trim().equals(name, ignoreCase = true) }
        ?.get(1)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    /** 覆盖同名参数（存在则替换，不存在则追加），其余参数保持原有顺序。 */
    fun upsert(query: String, name: String, value: String): String {
        val kept = query.split('&').filter {
            it.isNotBlank() && !it.substringBefore('=').trim().equals(name, ignoreCase = true)
        }
        return (kept + "$name=$value").joinToString("&")
    }
}
