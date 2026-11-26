package com.ucas.qingxin.signin.course

import com.ucas.qingxin.signin.data.Course
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

internal enum class SchoolSignState {
    Signed, Unsigned, Unknown, Upcoming, InProgress,
}

internal data class AttendanceLesson(
    val course: Course,
    val date: LocalDate,
    val classroom: String = "",
    val attendanceCourseId: String = course.courseId,
    val teachers: Set<String> = setOf(course.teacher).filterTo(mutableSetOf()) { it.isNotBlank() },
    val status: SchoolSignState = SchoolSignState.Unknown,
) {
    val key: String get() = "$date|${course.id.ifBlank {
        course.uuid.ifBlank { "${course.beginTime}|${course.endTime}|${course.teacher}" }
    }}"
    val courseKey: String get() = if (attendanceCourseId.isNotBlank()) "id:$attendanceCourseId"
        else "course:${course.courseNum}|${course.name}|${course.teacher}"
}

internal data class AttendanceTerm(val id: String, val dates: ClosedRange<LocalDate>)
internal data class AttendanceCatalogCourse(val id: String, val number: String, val name: String)
internal data class SchoolAttendanceEntry(
    val scheduleId: String,
    val date: LocalDate,
    val beginTime: String,
    val endTime: String,
    val status: SchoolSignState,
) {
    val key: String get() = "$date|$scheduleId"
}

internal data class SchoolAttendanceReport(
    val courseId: String,
    val signedTotal: Int?,
    val entries: List<SchoolAttendanceEntry>,
)

internal data class CourseAttendanceHistory(
    val id: String,
    val lessons: List<AttendanceLesson>,
    val schoolSignedTotal: Int?,
) {
    val course: Course get() = lessons.first().course
    val teachers: List<String> get() = lessons.flatMap { it.teachers }.distinct().sorted()
    val signedCount: Int get() = lessons.count { it.status == SchoolSignState.Signed }
    val pendingCount: Int get() = lessons.count { it.status == SchoolSignState.Unknown }
    val hasVerifiedAttendance: Boolean get() = lessons.any { it.status.isVerified() }
}

internal object SchoolAttendance {
    private data class TeachingSlot(
        val number: String,
        val name: String,
        val date: LocalDate,
        val classroom: String,
        val begins: Long,
        val ends: Long,
    )

    fun semesterWindow(today: LocalDate): ClosedRange<LocalDate> {
        val year = if (today.monthValue == 1) today.year - 1 else today.year
        val month = if (today.monthValue >= 8 || today.monthValue == 1) 8 else 2
        val start = LocalDate.of(year, month, 1)
        return start..start.plusMonths(7).minusDays(1)
    }

    fun weeks(dates: ClosedRange<LocalDate>): List<LocalDate> =
        generateSequence(dates.start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))) { it.plusWeeks(1) }
            .takeWhile { it <= dates.endInclusive }
            .toList()

    private fun teachingSlot(lesson: AttendanceLesson): TeachingSlot? {
        val course = lesson.course
        if (course.courseNum.isBlank() || lesson.classroom.isBlank()) return null
        val begin = CourseRepository.parseCourseInstant(course.day, course.beginTime) ?: return null
        val end = CourseRepository.parseCourseInstant(course.day, course.endTime) ?: return null
        if (end <= begin) return null
        return TeachingSlot(course.courseNum, course.name, lesson.date, lesson.classroom, begin, end)
    }

    private fun sharedSlots(lessons: List<AttendanceLesson>): Map<TeachingSlot, List<AttendanceLesson>> =
        lessons.mapNotNull { lesson -> teachingSlot(lesson)?.let { it to lesson } }
            .groupBy({ it.first }, { it.second })
            .filterValues { group -> group.flatMap { it.teachers }.toSet().size > 1 }

    fun catalogDates(lessons: List<AttendanceLesson>): Set<LocalDate> =
        sharedSlots(lessons).filterValues { it.needsCatalog() }.keys.mapTo(mutableSetOf()) { it.date }

    fun linkCoTeachers(
        lessons: List<AttendanceLesson>,
        terms: List<AttendanceTerm>,
        catalogs: Map<AttendanceTerm, List<AttendanceCatalogCourse>>,
    ): List<AttendanceLesson> {
        val links = mutableMapOf<TeachingSlot, String>()
        for ((slot, group) in sharedSlots(lessons).filterValues { it.needsCatalog() }) {
            val term = terms.distinct().singleOrNull { slot.date in it.dates } ?: continue
            val catalog = catalogs[term] ?: continue
            val matching = catalog.filter { it.number == slot.number }
            val courseId = matching.map { it.id }.distinct().singleOrNull() ?: continue
            val originalIds = group.map { it.attendanceCourseId }.toSet()
            if (matching.none { it.name == slot.name } ||
                catalog.any { it.id in originalIds && it.id != courseId }
            ) continue
            links[slot] = courseId
        }
        return lessons.map { lesson ->
            val catalogId = teachingSlot(lesson)?.let(links::get)
            if (catalogId == null) lesson else lesson.copy(attendanceCourseId = catalogId)
        }
    }

    fun courses(
        schedule: List<AttendanceLesson>,
        reports: Map<String, SchoolAttendanceReport>,
        dates: ClosedRange<LocalDate>,
        now: Instant,
    ): List<CourseAttendanceHistory> =
        schedule.groupBy { it.courseKey }.mapNotNull { (key, scheduled) ->
            val template = scheduled.first()
            val report = reports[template.attendanceCourseId]?.takeIf { it.courseId == template.attendanceCourseId }
            val evidence = report?.entries.orEmpty().groupBy { it.key }
            val scheduledKeys = scheduled.map { it.key }.toSet()
            val historyOnly = report?.entries.orEmpty().filter { it.key !in scheduledKeys }.map { entry ->
                AttendanceLesson(
                    course = template.course.copy(
                        id = entry.scheduleId, uuid = "", beginTime = entry.beginTime,
                        endTime = entry.endTime, day = entry.date.format(DateTimeFormatter.BASIC_ISO_DATE),
                        signed = false,
                    ),
                    date = entry.date,
                    attendanceCourseId = template.attendanceCourseId,
                    status = entry.status,
                )
            }
            val reconciled = scheduled.map { lesson ->
                val state = knownStatus(evidence[lesson.key].orEmpty().map { it.status })
                lesson.copy(status = state)
            } + historyOnly
            val unique = reconciled.filter { it.date in dates }.groupBy { it.key }.values.map { copies ->
                copies.first().copy(
                    status = knownStatus(copies.map { it.status }),
                    teachers = copies.flatMap { it.teachers }.toSet(),
                )
            }
            if (unique.isEmpty()) return@mapNotNull null
            val lessons = mergeSharedSlots(unique).map { it.copy(status = displayStatus(it, now)) }
                .sortedWith(compareBy({ it.date }, { CourseRepository.parseCourseInstant(it.course.day, it.course.beginTime) }, { it.key }))
            CourseAttendanceHistory(key, lessons, report?.signedTotal)
        }.sortedWith(compareBy({ it.course.name }, { it.id }))

    private fun mergeSharedSlots(lessons: List<AttendanceLesson>): List<AttendanceLesson> {
        val combined = mutableListOf<AttendanceLesson>()
        val merged = mutableSetOf<String>()
        for (group in sharedSlots(lessons).values) {
            if (group.first().attendanceCourseId.isBlank()) continue
            val verified = group.filter { it.status.isVerified() }
            if (verified.size > 1) continue
            val representative = verified.firstOrNull() ?: group.minBy { it.key }
            combined += representative.copy(teachers = group.flatMap { it.teachers }.toSet())
            merged += group.map { it.key }
        }
        return lessons.filterNot { it.key in merged } + combined
    }

    private fun displayStatus(lesson: AttendanceLesson, now: Instant): SchoolSignState {
        val today = now.atZone(CourseRepository.ZONE).toLocalDate()
        if (lesson.date > today) return SchoolSignState.Upcoming
        if (lesson.date < today || lesson.status == SchoolSignState.Signed) return lesson.status
        val start = CourseRepository.parseCourseInstant(lesson.course.day, lesson.course.beginTime)
        val end = CourseRepository.parseCourseInstant(lesson.course.day, lesson.course.endTime)
        return when {
            start != null && start > now.toEpochMilli() -> SchoolSignState.Upcoming
            end != null && end <= now.toEpochMilli() -> lesson.status
            start != null && end != null -> SchoolSignState.InProgress
            else -> SchoolSignState.Unknown
        }
    }

    private fun List<AttendanceLesson>.needsCatalog(): Boolean =
        map { it.attendanceCourseId }.toSet().let { it.size > 1 || "" in it }

    private fun knownStatus(states: List<SchoolSignState>): SchoolSignState =
        states.filter { it.isVerified() }.distinct().singleOrNull() ?: SchoolSignState.Unknown
}

private fun SchoolSignState.isVerified(): Boolean =
    this == SchoolSignState.Signed || this == SchoolSignState.Unsigned
