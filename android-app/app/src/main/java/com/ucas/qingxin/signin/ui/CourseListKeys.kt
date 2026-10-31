package com.ucas.qingxin.signin.ui

import com.ucas.qingxin.signin.data.Course

/**
 * 课程列表在 `LazyColumn` / `LazyVerticalGrid` 里的 **item key**。
 *
 * 旧的写法是 `key = { it.id + it.beginTime }`：它隐含假设「id 非空或时刻互异」。
 * 而学校课表在个别机型 / 个别数据形态下会返回**多门 id 为空、同一时段**的课
 * （合并班、讲座占位节次），此时 key 重复，Compose 直接抛
 * `IllegalArgumentException: Key was already used` —— 用户看到的不是「这行坏了」
 * 而是**整个页面打不开 / 弹窗打不开**（「不自动打卡的课程」弹窗打不开的
 * 兼容性反馈正来自这一类数据）。
 *
 * 这里改为「基础键 + 撞车序号」：基础键语义与旧版一致，只有真正撞车时才追加
 * `#2`、`#3`。键只要求**在同一列表内唯一且稳定**（列表本身来自当日课表，
 * 顺序在一次加载内不变），序号后缀满足这两个要求。
 */
fun courseListKeys(courses: List<Course>): List<String> {
    val used = HashSet<String>(courses.size * 2)
    return courses.map { course ->
        var key = course.id + course.beginTime
        var n = 2
        while (!used.add(key)) {
            key = "${course.id}${course.beginTime}#$n"
            n++
        }
        key
    }
}
