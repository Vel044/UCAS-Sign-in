package com.ucas.qingxin.signin.attendance

import java.util.Locale

/**
 * 「二维码里的 32 位标识」→「学校签到接口认的 7 位节次号」的改写。
 *
 * ## 为什么需要这一步（2026-10-19 的现场证据）
 * 现场记录给了同一场课的**两个标识、两种结果**（相隔 7 秒）：
 *
 * ```text
 * 18:22:47  扫码     timeTableId=2025CF66…CB48（32 位）  → 失败
 * 18:22:50  扫码     timeTableId=2025CF66…CB48（32 位）  → 失败
 * 18:22:57  一键签到 1241918（7 位）                      → 成功
 * ```
 *
 * 两者的请求形态几乎相同（同一个 `course/stu_scan_sign.action`、同一个 `id`、
 * 同一个新鲜的 `timestamp`），唯一差别是**标识的形态与参数名**。
 * 而项目里 6 次成功**全部**是 7 位节次号，4 次失败**全部**是 32 位标识 ——
 * 这个分布本身就是最有力的线索。
 *
 * ## 因此这一版做什么
 * 当二维码只给了 32 位标识、而我们**本机能证明**它与今天课表里的某一节课是同一节
 * （`Course.uuid` 与二维码标识逐字相同）时，把该课的 7 位 `courseSchedId`
 * **一并补进 query**，再提交：
 *
 * - 补而不是换：二维码里的 `timeTableId` 原样留着。服务端按哪个参数查由它决定，
 *   两个标识都在场时，两种查法都能命中 —— 我们不必赌它的实现。
 * - 只在**能证明是同一节**时补：映射来自课程表本身（`uuid` ↔ `id`），不是猜的。
 *   找不到对应节次时**一个字节都不改**，仍是原来的原样重放，不会更差。
 *
 * ## 为什么不是「只发 7 位」
 * 因为「服务端到底认不认 `timeTableId`」目前**没有直接证据**。只发 7 位等于把一个
 * 未证伪的假设当成事实；而两个都发，无论它按哪个查都成立。
 * 下一份现场记录里，「本次提交」那行会写明补了哪个节次号、学校又回了什么原话
 * （见 [com.ucas.qingxin.signin.data.SignResult.schoolMessage]），届时即可收口。
 *
 * 纯函数、不触网，因此映射与改写都能离线断言（见 `ScanIdMappingTest`）。
 */
object ScanIdMapping {

    /** 32 位标识的规范形态：去掉连字符、去空白、大写。与 `Course.uuid` 的口径一致。 */
    fun normalizeUuid(raw: String): String = raw.replace("-", "").trim().uppercase(Locale.ROOT)

    /**
     * 32 位标识是否就是这门课（`Course.uuid`）。
     *
     * 大小写与连字符都容忍：同一份数据在学校接口里出现过 `8-4-4-4-12` 与 32 位紧凑两种写法
     * （见 `SignCode.parse` 的同款宽容），拿其中一种去和另一种比会假性不相等 ——
     * 那种失败很难看出来，因为报错只会是「没找到对应节次」。
     */
    fun matches(timeTableId: String, courseUuid: String): Boolean {
        if (timeTableId.isBlank() || courseUuid.isBlank()) return false
        return normalizeUuid(timeTableId) == normalizeUuid(courseUuid)
    }

    /**
     * 在候选课表里找出与该 32 位标识对应的 7 位节次号；找不到返回 `null`。
     *
     * [candidates] 是「(uuid, 7 位节次号)」对。**必须容忍 uuid 为空的课**：
     * 学校对少数条目不给 `uuid`，把空串拿去匹配会与同样为空的标识撞上，
     * 那会把一节毫不相干的课签掉 —— 这是本函数里唯一危险的方向，因此显式排除。
     */
    fun resolveSchedId(
        timeTableId: String,
        candidates: List<Pair<String, String>>,
    ): String? {
        if (timeTableId.isBlank()) return null
        val target = normalizeUuid(timeTableId)
        return candidates.firstOrNull { (uuid, schedId) ->
            schedId.isNotBlank() && uuid.isNotBlank() && normalizeUuid(uuid) == target
        }?.second
    }

    /**
     * 把 7 位节次号补进 query（`timeTableId` 保持原样）。
     *
     * ## 两个刻意的选择
     * - **补而不换**：删掉 `timeTableId` 等于赌「服务端不认它」，而这一点尚未被证伪。
     *   两个标识都在场时，无论服务端按哪个查都能命中。
     * - **放在最前**：若服务端不是按名字取参（`getParameter` 与顺序无关），位置无所谓；
     *   但若它「取 query 里第一个 id 参数」，放在最前才能让**已验证可用**的
     *   7 位节次号被选中。这一条不花任何代价，纯属对冲。
     *
     * 同名参数已存在时先删再放，避免把同一个名字发两遍（哪个生效取决于服务端实现）。
     */
    fun withCourseSchedId(query: String, courseSchedId: String): String {
        val rest = ScanQuery.remove(query, "courseSchedId").trim()
        return if (rest.isEmpty()) "courseSchedId=$courseSchedId"
        else "courseSchedId=$courseSchedId&$rest"
    }
}
