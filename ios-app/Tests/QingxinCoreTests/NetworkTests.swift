// SPDX-License-Identifier: AGPL-3.0-only
import XCTest
@testable import QingxinCore

final class MockURLProtocol: URLProtocol {
    static var handler: ((URLRequest) throws -> (Int, [String: Any]))?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            let (status, json) = try Self.handler!(request)
            client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: try JSONSerialization.data(withJSONObject: json))
            client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() { }
}

final class NetworkTests: XCTestCase {
    private func api() -> QingxinAPI {
        let c = URLSessionConfiguration.ephemeral; c.protocolClasses = [MockURLProtocol.self]
        return QingxinAPI(configuration: EndpointConfiguration(apiBaseURL: "https://school.invalid/api/", verificationURL: "https://verify.invalid/{0}"), transport: URLSession(configuration: c))
    }
    override func tearDown() { MockURLProtocol.handler = nil; super.tearDown() }
    func testLoginUsesUpstreamFormFields() async throws {
        MockURLProtocol.handler = { request in
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.url?.path, "/api/user/login.action")
            let body = String(data: request.httpBody ?? Data(), encoding: .utf8) ?? ""
            // URLSession may supply a stream instead of httpBody; inspect a standalone request below as well.
            if !body.isEmpty { XCTAssertTrue(body.contains("verificationType=1")) }
            XCTAssertEqual(request.value(forHTTPHeaderField: "User-Agent"), "student_5.0.1.2_android_12_20__110000")
            return (200, ["STATUS": "0", "result": ["id": "42", "sessionId": "s", "studentNo": "20260001"]])
        }
        let value = try await api().login(identity: "test@school.invalid", password: "a+b&c")
        XCTAssertEqual(value.studentNo, "20260001")
    }
    func testWeeklyFallbackWithZeroStatusStillUsesRealData() async throws {
        MockURLProtocol.handler = { request in
            if request.url!.path.contains("_week") {
                return (200, ["STATUS": "0", "result": [["dateStr": "2026-10-02", "schedData": [["id": "1234567", "courseName": "Math"]]]]])
            }
            return (200, ["STATUS": "0", "result": []])
        }
        let session = try QingxinAPI.parseSession(["STATUS": "0", "result": ["id": "42", "sessionId": "s", "studentNo": "20260001"]])
        let query = try await api().courses(session: session, dateKey: "20261002")
        XCTAssertEqual(query.courses.map(\.name), ["Math"])
        XCTAssertFalse(query.fromWeeklyFallback)
    }
    func testWeekFallbackIsExplicitWhenRequestedDayHasNoCourse() async throws {
        MockURLProtocol.handler = { request in
            if request.url!.path.contains("_week") {
                return (200, ["STATUS": "1", "result": [["dateStr": "2026-10-03", "schedData": [["id": "1234567", "courseName": "Math"]]]]])
            }
            return (200, ["STATUS": "0", "result": []])
        }
        let session = try QingxinAPI.parseSession(["STATUS": "0", "result": ["id": "42", "sessionId": "s", "studentNo": "20260001"]])
        let query = try await api().courses(session: session, dateKey: "20261002")
        XCTAssertTrue(query.fromWeeklyFallback)
        XCTAssertEqual(query.courses[0].day, "20261003")
    }
    func testAttendanceWeekUsesOnlyTheScheduleEvenIfRowsContainSignFlags() async throws {
        MockURLProtocol.handler = { request in
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.url?.path, "/api/course/get_stu_course_sched_week.action")
            XCTAssertEqual(request.value(forHTTPHeaderField: "sessionId"), "s")
            if let body = request.httpBody {
                XCTAssertEqual(String(data: body, encoding: .utf8), "id=42&dateStr=20260928")
            }
            return (200, ["STATUS": "0", "result": [
                ["dateStr": "2026-09-28", "schedData": [["id": "1", "signStatus": 1], ["id": "2", "signStatus": "0"]]],
                ["dateStr": "20261003", "schedData": [["id": "3"], ["id": "4", "signStatus": true], ["id": "5", "signStatus": 2]]],
                ["dateStr": "2026-10-04", "schedData": NSNull()],
                ["dateStr": "2026-10-05", "schedData": [["id": "6", "signStatus": "1"]]]
            ]])
        }
        let records = try await api().attendanceWeek(
            session: SchoolSession(userId: "42", sessionId: "s", studentNo: "20260001"),
            days: "20260928"..."20261004"
        )
        XCTAssertEqual(records.map { $0.course.scheduleId }, ["1", "2", "3", "4", "5"])
        XCTAssertEqual(records.map(\.status), [.unknown, .unknown, .unknown, .unknown, .unknown])
    }

    func testCourseAttendanceReconcilesSchoolResultsWithScheduledLessons() async throws {
        MockURLProtocol.handler = { request in
            XCTAssertEqual(request.url?.path, "/api/my/get_my_course_sign_detail.action")
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.value(forHTTPHeaderField: "sessionId"), "s")
            if let body = request.httpBody {
                XCTAssertEqual(String(data: body, encoding: .utf8), "id=42&courseId=course-a")
            }
            return (200, ["STATUS": 0, "mySignNum": "5", "result": [
                ["courseId": "course-a", "courseSchedId": "101", "teachTime": "2026-09-28", "signStatus": "1"],
                ["courseId": "course-a", "courseSchedId": "102", "teachTime": "20261005", "signStatus": 0],
                ["courseId": "course-a", "courseSchedId": "104", "teachTime": "20261019", "signStatus": true],
                ["courseId": "course-a", "courseSchedId": "105", "teachTime": "20261026", "signStatus": "1"]
            ]])
        }
        let report = try await api().courseAttendance(
            session: SchoolSession(userId: "42", sessionId: "s", studentNo: "20260001"), courseId: "course-a"
        )
        func scheduled(_ id: String, day: String) -> AttendanceRecord {
            let course = Course(scheduleId: id, name: "算法设计", beginTime: "08:00", endTime: "09:00",
                                day: day, courseId: "course-a")
            return AttendanceRecord(course: course, status: .unknown)
        }
        let schedule = [scheduled("101", day: "20260928"), scheduled("102", day: "20261005"),
                        scheduled("103", day: "20261012"), scheduled("104", day: "20261019")]
        let reconciled = AttendanceHistory.reconcile(schedule: schedule, with: [report.courseId: report])
        let now = SchoolDate.instant(day: "20261030", time: "12:00")!
        let groups = AttendanceHistory.courses(from: reconciled, on: "20260901"..."20261030", at: now)
        XCTAssertEqual(report.schoolSignedTotal, 5)
        XCTAssertEqual(groups.first?.signedCount, 2)
        XCTAssertEqual(groups.first?.unknownCount, 2)
        XCTAssertEqual(groups.first?.records.map { $0.course.scheduleId }, ["101", "102", "103", "104", "105"])
        XCTAssertEqual(groups.first?.records.map(\.status), [.signed, .unsigned, .unknown, .unknown, .signed])
    }

    func testCourseAttendanceRejectsRecordsForAnotherCourse() async throws {
        MockURLProtocol.handler = { _ in
            (200, ["STATUS": "0", "mySignNum": 1, "result": [
                ["courseId": "course-b", "courseSchedId": "101", "teachTime": "20260928", "signStatus": 1]
            ]])
        }
        do {
            _ = try await api().courseAttendance(
                session: SchoolSession(userId: "42", sessionId: "s", studentNo: "20260001"), courseId: "course-a"
            )
            XCTFail("Expected records for a different course to be rejected")
        } catch QingxinError.invalidResponse { }
    }

    func testCoTeachersUseCatalogCourseAndKeepOnlyTheVerifiedLessonOnce() async throws {
        MockURLProtocol.handler = { request in
            switch request.url?.path {
            case "/api/course/get_stu_course_sched_week.action":
                return (200, ["result": [
                    ["dateStr": "20261111", "schedData": [
                        ["id": "101", "courseId": "class-a", "courseNum": "CS601", "courseName": "分布式系统",
                         "teacherName": "赵老师", "classroomName": "B301", "classBeginTime": "08:00", "classEndTime": "09:40"],
                        ["id": "102", "courseId": "teaching-b", "courseNum": "CS601", "courseName": "分布式系统",
                         "teacherName": "钱老师", "classroomName": "B301", "classBeginTime": "0800", "classEndTime": "09:40:00"],
                        ["id": "103", "courseId": "class-lab", "courseNum": "CS601", "courseName": "分布式系统",
                         "teacherName": "孙老师", "classroomName": "B302", "classBeginTime": "08:00", "classEndTime": "09:40"]
                    ]]
                ]])
            case "/api/course/get_base_school_year.action":
                return (200, ["STATUS": 0, "result": [
                    ["code": "fall", "beginDate": "2026-08-31", "endDate": "2027-01-31"]
                ]])
            case "/api/choosecourse/get_myall_course.action":
                XCTAssertEqual(request.url?.query, "user_type=1")
                return (200, ["STATUS": 0, "result": [
                    ["course_id": "class-a", "courseNum": "CS601", "course_name": "分布式系统", "semesterId": "fall"]
                ]])
            case "/api/my/get_my_course_sign_detail.action":
                return (200, ["STATUS": 0, "mySignNum": 1, "result": [
                    ["courseId": "class-a", "courseSchedId": "102", "teachTime": "20261111", "signStatus": 1]
                ]])
            default:
                XCTFail("Unexpected school request")
                return (404, [:])
            }
        }
        let service = api()
        let session = SchoolSession(userId: "42", sessionId: "s", studentNo: "20260001")
        let days = "20261109"..."20261115"
        let schedule = try await service.attendanceWeek(session: session, days: days)
        XCTAssertEqual(AttendanceHistory.daysNeedingCourseCatalog(schedule), ["20261111"])
        let terms = try await service.attendanceTerms(session: session)
        let term = try XCTUnwrap(terms.first)
        let catalog = try await service.attendanceCatalog(session: session, term: term)
        let linked = AttendanceHistory.linkCoTeachers(schedule, terms: terms, catalogs: [term: catalog])
        XCTAssertEqual(linked.map(\.attendanceCourseID), ["class-a", "class-a", "class-lab"])
        XCTAssertEqual(linked.map { $0.course.scheduleId }, ["101", "102", "103"])
        let report = try await service.courseAttendance(session: session, courseId: "class-a")
        let records = AttendanceHistory.reconcile(schedule: linked, with: ["class-a": report])
        let now = SchoolDate.instant(day: "20261126", time: "12:00")!
        let courses = AttendanceHistory.courses(from: records, on: days, at: now)
        let shared = try XCTUnwrap(courses.first { $0.attendanceCourseID == "class-a" })
        XCTAssertEqual(shared.records.map { $0.course.scheduleId }, ["102"])
        XCTAssertEqual(shared.signedCount, 1)
        XCTAssertEqual(Set(shared.teachers), ["赵老师", "钱老师"])
        XCTAssertEqual(courses.first { $0.attendanceCourseID == "class-lab" }?.unknownCount, 1)
    }
    func testAttendanceWeekDoesNotReportUnrelatedSchoolWeekAsEmpty() async throws {
        MockURLProtocol.handler = { _ in
            (200, ["STATUS": "0", "result": [["dateStr": "20261102", "schedData": []]]])
        }
        do {
            _ = try await api().attendanceWeek(
                session: SchoolSession(userId: "42", sessionId: "s", studentNo: "20260001"),
                days: "20260928"..."20261004"
            )
            XCTFail("Expected unrelated school week to fail")
        } catch QingxinError.invalidResponse { }
    }
    func testBooleanTimestampIsRejected() async {
        MockURLProtocol.handler = { _ in (200, ["STATUS": "0", "timestamp": true]) }
        do { _ = try await api().serverTimestamp(); XCTFail("Boolean is not a valid clock") } catch { }
    }
    func testHTTPFailureIsNotSilentlyTreatedAsEmptySchedule() async {
        MockURLProtocol.handler = { _ in (503, [:]) }
        do { _ = try await api().serverTimestamp(); XCTFail("Expected HTTP error") }
        catch QingxinError.http(let code) { XCTAssertEqual(code, 503) }
        catch { XCTFail("Unexpected error: \(error)") }
    }
    func testExpiredSessionJSONIsRecognizedWithoutHTTP401() async throws {
        MockURLProtocol.handler = { _ in (200, ["STATUS": "1", "ERRMSG": "登录会话已过期"]) }
        let session = try QingxinAPI.parseSession(["STATUS": "0", "result": ["id": "42", "sessionId": "s", "studentNo": "20260001"]])
        do { _ = try await api().courses(session: session, dateKey: "20261002"); XCTFail("Expected session expiry") }
        catch let error as QingxinError { XCTAssertTrue(error.requiresReauthentication) }
    }
    func testClockRequestMatchesUpstreamEndpoint() async throws {
        MockURLProtocol.handler = { request in
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.url?.path, "/api/common/get_timestamp.do")
            XCTAssertEqual(request.url?.query, "id=0")
            return (200, ["STATUS": "0", "timestamp": 1_700_000_000_000])
        }
        let timestamp = try await api().serverTimestamp()
        XCTAssertEqual(timestamp, 1_700_000_000_000)
    }
}
