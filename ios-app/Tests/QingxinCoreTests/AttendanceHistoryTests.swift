// SPDX-License-Identifier: AGPL-3.0-only
import XCTest
@testable import QingxinCore

final class AttendanceHistoryTests: XCTestCase {
    func testAmbiguousCatalogAndIndependentAttendanceAreKeptSeparate() throws {
        let day = "20261111"
        func lesson(_ id: String, _ courseID: String, _ teacher: String, _ status: AttendanceRecord.Status) -> AttendanceRecord {
            let course = Course(scheduleId: id, name: "分布式系统", teacher: teacher, beginTime: "08:00",
                                endTime: "09:40", day: day, courseId: courseID, courseNum: "CS601")
            return AttendanceRecord(course: course, status: status, classroom: "B301")
        }
        let term = AttendanceTerm(id: "fall", days: "20260831"..."20270131")
        let catalog = [
            AttendanceCatalogCourse(id: "group-a", number: "CS601", name: "分布式系统"),
            AttendanceCatalogCourse(id: "group-b", number: "CS601", name: "分布式系统")
        ]
        let schedule = [lesson("101", "group-a", "赵老师", .unknown), lesson("102", "group-b", "钱老师", .unknown)]
        let linked = AttendanceHistory.linkCoTeachers(schedule, terms: [term], catalogs: [term: catalog])
        XCTAssertEqual(linked.map(\.attendanceCourseID), ["group-a", "group-b"])
        let distinct = [lesson("103", "group-a", "赵老师", .signed), lesson("104", "group-a", "钱老师", .unsigned)]
        let now = try XCTUnwrap(SchoolDate.instant(day: "20261126", time: "12:00"))
        let group = try XCTUnwrap(AttendanceHistory.courses(from: distinct, on: day...day, at: now).first)
        XCTAssertEqual(group.records.map(\.status), [.signed, .unsigned])
    }

    func testWeeksCoverBothEndsOfRangeAcrossSchoolWeekAndYearBoundaries() {
        let from = SchoolDate.instant(day: "20261227", time: "23:30")!
        let through = SchoolDate.instant(day: "20270104", time: "00:05")!
        XCTAssertEqual(AttendanceHistory.weeks(in: from...through), [
            "20261221"..."20261227", "20261228"..."20270103", "20270104"..."20270110"
        ])
    }

    func testDefaultWindowIncludesStartAndEndOfAcademicTerm() {
        let november = SchoolDate.instant(day: "20261126", time: "12:00")!
        let january = SchoolDate.instant(day: "20270108", time: "12:00")!
        let fall = AttendanceHistory.semesterWindow(containing: november)
        XCTAssertEqual(fall, AttendanceHistory.semesterWindow(containing: january))
        XCTAssertEqual(SchoolDate.key(fall.lowerBound), "20260801")
        XCTAssertEqual(SchoolDate.key(fall.upperBound), "20270228")
        let spring = AttendanceHistory.semesterWindow(containing: SchoolDate.instant(day: "20270303", time: "12:00")!)
        XCTAssertEqual(SchoolDate.key(spring.lowerBound), "20270201")
        XCTAssertEqual(SchoolDate.key(spring.upperBound), "20270831")
    }

    func testHistoryCountsEachLessonOnceAndGroupsSameNamedCoursesSeparately() {
        func record(_ id: String, _ day: String, _ status: AttendanceRecord.Status,
                    courseId: String = "class-a", begin: String = "08:00", end: String = "10:00") -> AttendanceRecord {
            AttendanceRecord(course: Course(scheduleId: id, name: "数学", beginTime: begin, endTime: end, day: day,
                                            signed: true, courseId: courseId), status: status)
        }
        let records = [
            record("01", "20260908", .signed), record("01", "20260908", .signed),
            record("01", "20261125", .signed), record("02", "20261125", .unknown),
            record("03", "20261125", .signed), record("03", "20261125", .unsigned),
            record("04", "20261126", .unsigned, end: "15:00"),
            record("05", "20261126", .unsigned, end: "09:00"),
            record("06", "20261215", .signed), record("07", "20260723", .signed),
            record("08", "20261125", .unsigned, courseId: "class-b"),
            record("09", "20261126", .unsigned, begin: "14:00", end: "16:00")
        ]
        let now = SchoolDate.instant(day: "20261126", time: "10:30")!
        let groups = AttendanceHistory.courses(from: records, on: "20260801"..."20270228", at: now)
        XCTAssertEqual(groups.map(\.id), ["id:class-a", "id:class-b"])
        XCTAssertEqual(groups.map(\.signedCount), [2, 0])
        XCTAssertEqual(groups.first?.records.map { $0.course.scheduleId }, ["01", "01", "02", "03", "04", "05", "09", "06"])
        XCTAssertEqual(groups.first?.records.map(\.status), [.signed, .signed, .unknown, .unknown, .inProgress, .unsigned, .upcoming, .upcoming])
    }
}
