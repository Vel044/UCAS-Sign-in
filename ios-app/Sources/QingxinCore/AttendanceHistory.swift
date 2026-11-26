// SPDX-License-Identifier: AGPL-3.0-only
import Foundation

public struct AttendanceRecord: Identifiable, Sendable {
    public enum Status: Hashable, Sendable {
        case signed, unsigned, unknown, upcoming, inProgress
    }

    public let course: Course
    public let status: Status
    public let teachers: [String]
    let classroom: String
    let catalogCourseID: String?

    public init(course: Course, status: Status, classroom: String = "") {
        self.init(course: course, status: status, classroom: classroom,
                  teachers: course.teacher.isEmpty ? [] : [course.teacher], catalogCourseID: nil)
    }

    private init(course: Course, status: Status, classroom: String, teachers: [String], catalogCourseID: String?) {
        self.course = course
        self.status = status
        self.classroom = classroom
        self.teachers = teachers
        self.catalogCourseID = catalogCourseID
    }

    public var attendanceCourseID: String { catalogCourseID ?? course.courseId }

    fileprivate func updated(status: Status, teachers: [String]? = nil, catalogCourseID: String? = nil) -> AttendanceRecord {
        var course = self.course
        course.signed = status == .signed
        return AttendanceRecord(course: course, status: status, classroom: classroom,
                                teachers: teachers ?? self.teachers, catalogCourseID: catalogCourseID ?? self.catalogCourseID)
    }

    public var id: String {
        if !course.scheduleId.isEmpty { return "\(course.day):id:\(course.scheduleId)" }
        if !course.uuid.isEmpty { return "\(course.day):uuid:\(course.uuid.lowercased())" }
        return course.id + ":" + courseKey + ":" + course.endTime
    }

    fileprivate var courseKey: String {
        if !attendanceCourseID.isEmpty { return "id:\(attendanceCourseID)" }
        return "course:\(course.courseNum):\(course.name):\(course.teacher)"
    }
}

public struct CourseAttendance: Identifiable, Sendable {
    public let id: String
    public let records: [AttendanceRecord]
    public var course: Course { records[0].course }
    public var attendanceCourseID: String { records[0].attendanceCourseID }
    public var teachers: [String] { Set(records.flatMap(\.teachers)).sorted() }
    public var signedCount: Int { records.filter { $0.status == .signed }.count }
    public var unknownCount: Int { records.filter { $0.status == .unknown }.count }
    public var hasVerifiedAttendance: Bool { records.contains { $0.status == .signed || $0.status == .unsigned } }
}

public struct AttendanceTerm: Hashable, Sendable {
    public let id: String
    public let days: ClosedRange<String>
}

public struct AttendanceCatalogCourse: Sendable {
    public let id: String
    let number: String
    let name: String
}

public struct CourseAttendanceReport: Sendable {
    struct Lesson: Sendable {
        let scheduleId: String
        let day: String
        let beginTime: String
        let endTime: String
        let status: AttendanceRecord.Status
        var key: String { "\(day):\(scheduleId)" }
    }

    public let courseId: String
    public let schoolSignedTotal: Int?
    let lessons: [Lesson]
}

public enum AttendanceHistory {
    private struct TeachingSlot: Hashable {
        let number: String
        let name: String
        let day: String
        let classroom: String
        let begin: Date
        let end: Date

        init?(_ record: AttendanceRecord) {
            let course = record.course
            guard !course.courseNum.isEmpty, !record.classroom.isEmpty,
                  let begin = course.begin, let end = course.end, begin < end else { return nil }
            number = course.courseNum
            name = course.name
            day = course.day
            classroom = record.classroom
            self.begin = begin
            self.end = end
        }
    }

    /// The week endpoint does not supply term boundaries. Include the transition
    /// month on either side of the August/February term change.
    public static func semesterWindow(containing date: Date) -> ClosedRange<Date> {
        let calendar = SchoolDate.calendar
        let year = calendar.component(.year, from: date)
        let month = calendar.component(.month, from: date)
        let start = calendar.date(from: DateComponents(
            year: month == 1 ? year - 1 : year,
            month: month >= 8 || month == 1 ? 8 : 2,
            day: 1
        ))!
        let afterEnd = calendar.date(byAdding: .month, value: 7, to: start)!
        return start...calendar.date(byAdding: .day, value: -1, to: afterEnd)!
    }

    /// The school timetable uses Monday through Sunday in the school's time zone.
    public static func weeks(in dates: ClosedRange<Date>) -> [ClosedRange<String>] {
        var calendar = SchoolDate.calendar
        calendar.firstWeekday = 2
        var monday = calendar.dateInterval(of: .weekOfYear, for: dates.lowerBound)!.start
        var weeks: [ClosedRange<String>] = []
        while monday <= dates.upperBound {
            let sunday = calendar.date(byAdding: .day, value: 6, to: monday)!
            weeks.append(SchoolDate.key(monday)...SchoolDate.key(sunday))
            monday = calendar.date(byAdding: .day, value: 7, to: monday)!
        }
        return weeks
    }

    private static func sharedSlots(_ records: [AttendanceRecord]) -> [TeachingSlot: [AttendanceRecord]] {
        var slots: [TeachingSlot: [AttendanceRecord]] = [:]
        for record in records {
            if let slot = TeachingSlot(record) { slots[slot, default: []].append(record) }
        }
        return slots.filter { Set($0.value.flatMap(\.teachers)).count > 1 }
    }

    public static func daysNeedingCourseCatalog(_ records: [AttendanceRecord]) -> Set<String> {
        Set(sharedSlots(records).compactMap { slot, group in
            let ids = Set(group.map(\.attendanceCourseID))
            return ids.count > 1 || ids.contains("") ? slot.day : nil
        })
    }

    public static func linkCoTeachers(_ records: [AttendanceRecord],
                                      terms: [AttendanceTerm], catalogs: [AttendanceTerm: [AttendanceCatalogCourse]]) -> [AttendanceRecord] {
        var links: [TeachingSlot: String] = [:]
        for (slot, group) in sharedSlots(records) {
            let ids = Set(group.map(\.attendanceCourseID))
            guard ids.count > 1 || ids.contains("") else { continue }
            let activeTerms = terms.filter { $0.days.contains(slot.day) }
            guard activeTerms.count == 1, let term = activeTerms.first, let catalog = catalogs[term] else { continue }
            let matches = catalog.filter { $0.number == slot.number }
            let matchingIDs = Set(matches.map(\.id))
            guard matchingIDs.count == 1, let id = matchingIDs.first,
                  matches.contains(where: { $0.name == slot.name }),
                  !catalog.contains(where: { ids.contains($0.id) && $0.id != id }) else { continue }
            links[slot] = id
        }
        return records.map { record in
            guard let slot = TeachingSlot(record), let id = links[slot] else { return record }
            return record.updated(status: record.status, catalogCourseID: id)
        }
    }

    public static func reconcile(schedule: [AttendanceRecord], with reports: [String: CourseAttendanceReport]) -> [AttendanceRecord] {
        Dictionary(grouping: schedule, by: \.attendanceCourseID).flatMap { courseId, entries in
            guard let report = reports[courseId], report.courseId == courseId,
                  let template = entries.first?.course else { return entries }
            let evidence = Dictionary(grouping: report.lessons, by: \.key)
            let scheduled = Set(entries.map { "\($0.course.day):\($0.course.scheduleId)" })
            let joined = entries.map { entry -> AttendanceRecord in
                let key = "\(entry.course.day):\(entry.course.scheduleId)"
                let status = resolvedStatus(evidence[key, default: []].map(\.status))
                return entry.updated(status: status)
            }
            let historyOnly = report.lessons.filter { !scheduled.contains($0.key) }.map { lesson in
                let course = Course(scheduleId: lesson.scheduleId, name: template.name, teacher: template.teacher,
                                    beginTime: lesson.beginTime, endTime: lesson.endTime, day: lesson.day,
                                    signed: lesson.status == .signed, courseId: courseId, courseNum: template.courseNum)
                return AttendanceRecord(course: course, status: lesson.status)
            }
            return joined + historyOnly
        }
    }

    private static func resolvedStatus(_ statuses: [AttendanceRecord.Status]) -> AttendanceRecord.Status {
        let known = Set(statuses).subtracting([.unknown])
        return (known.count == 1 ? known.first : nil) ?? .unknown
    }

    public static func courses(from records: [AttendanceRecord], on days: ClosedRange<String>, at now: Date) -> [CourseAttendance] {
        let today = SchoolDate.key(now)
        return Dictionary(grouping: records.filter { days.contains($0.course.day) }, by: \.courseKey).map { id, lessons in
            let unique = Dictionary(grouping: lessons, by: \.id).values.map { duplicates in
                duplicates[0].updated(status: resolvedStatus(duplicates.map(\.status)),
                                      teachers: Set(duplicates.flatMap(\.teachers)).sorted())
            }
            let combined = mergeSharedSlots(unique).map { record in
                let course = record.course
                var status = record.status
                if course.day > today {
                    status = .upcoming
                } else if course.day == today && status != .signed {
                    if let begin = course.begin, begin > now {
                        status = .upcoming
                    } else if let begin = course.begin, let end = course.end, begin <= now, now < end {
                        status = .inProgress
                    } else if (course.end ?? .distantFuture) > now {
                        status = .unknown
                    }
                }
                return record.updated(status: status)
            }
            return CourseAttendance(id: id, records: combined.sorted {
                if $0.course.day != $1.course.day { return $0.course.day < $1.course.day }
                return ($0.course.begin ?? .distantPast, $0.id) < ($1.course.begin ?? .distantPast, $1.id)
            })
        }.sorted { ($0.course.name, $0.id) < ($1.course.name, $1.id) }
    }

    private static func mergeSharedSlots(_ lessons: [AttendanceRecord]) -> [AttendanceRecord] {
        var combined: [AttendanceRecord] = []
        var mergedIDs = Set<String>()
        for group in sharedSlots(lessons).values {
            guard !group[0].attendanceCourseID.isEmpty else { continue }
            let verified = group.filter { $0.status == .signed || $0.status == .unsigned }
            guard verified.count <= 1 else { continue }
            let representative = verified.first ?? group.min { $0.id < $1.id }!
            combined.append(representative.updated(status: representative.status,
                                                   teachers: Set(group.flatMap(\.teachers)).sorted()))
            mergedIDs.formUnion(group.map(\.id))
        }
        return lessons.filter { !mergedIDs.contains($0.id) } + combined
    }
}
