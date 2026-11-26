// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import QingxinCore

struct AttendanceHistoryView: View {
    @EnvironmentObject private var state: AppState
    @State private var from: Date
    @State private var through: Date
    @State private var query: Query
    @State private var loadedQueryID: UUID?
    @State private var finishedWeeks = 0
    @State private var totalWeeks = 0
    @State private var finishedReports = 0
    @State private var totalReports = 0
    @State private var unavailableWeeks: [ClosedRange<String>] = []
    @State private var loading = true
    @State private var failure: String?
    @State private var courses: [CourseAttendance] = []
    @State private var reports: [String: CourseAttendanceReport] = [:]

    private struct Query {
        let id = UUID()
        let dates: ClosedRange<Date>
        var dayKeys: ClosedRange<String> { SchoolDate.key(dates.lowerBound)...SchoolDate.key(dates.upperBound) }
    }

    init() {
        let dates = AttendanceHistory.semesterWindow(containing: Date())
        _from = State(initialValue: dates.lowerBound)
        _through = State(initialValue: dates.upperBound)
        _query = State(initialValue: Query(dates: dates))
    }

    var body: some View {
        List {
            if state.loggedIn {
                Section {
                    DatePicker("开始日期", selection: $from, in: ...through, displayedComponents: .date)
                    DatePicker("结束日期", selection: $through, in: from..., displayedComponents: .date)
                    Button("查询") {
                        loading = true
                        query = Query(dates: from...through)
                    }
                    .disabled(loading && query.dates == (from...through))
                } footer: {
                    Text(state.demo ? "演示数据" : "默认覆盖当前学期。可调整起止日期查询其他学期。")
                }
                if loading {
                    if totalReports > 0 {
                        ProgressView(value: Double(finishedReports), total: Double(totalReports)) {
                            Text("正在核对学校考勤 \(finishedReports) / \(totalReports) 门")
                        }
                    } else if totalWeeks > 0 && finishedWeeks == totalWeeks {
                        ProgressView("正在核对课程资料")
                    } else if totalWeeks > 0 {
                        ProgressView(value: Double(finishedWeeks), total: Double(totalWeeks)) {
                            Text("正在查询第 \(min(finishedWeeks + 1, totalWeeks)) / \(totalWeeks) 周")
                        }
                    } else {
                        ProgressView("正在查询")
                    }
                } else {
                    results
                }
            } else {
                ContentUnavailableView("请先登录", systemImage: "person.crop.circle")
            }
        }
        .environment(\.timeZone, SchoolDate.zone)
        .navigationTitle("签到记录")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: query.id) { [query] in
            if state.loggedIn && loadedQueryID != query.id { await load(query) }
        }
    }

    @ViewBuilder private var results: some View {
        let successfulWeeks = finishedWeeks - unavailableWeeks.count
        let hasVerifiedAttendance = courses.contains(where: \.hasVerifiedAttendance)
        let unknownCount = courses.reduce(0) { $0 + $1.unknownCount }
        if failure != nil || !unavailableWeeks.isEmpty {
            Section {
                Label(finishedWeeks < totalWeeks || !unavailableWeeks.isEmpty
                      ? "已取得 \(successfulWeeks) / \(totalWeeks) 周课表" : "学校考勤查询中断",
                      systemImage: "exclamationmark.triangle")
                    .foregroundStyle(.orange)
                if !unavailableWeeks.isEmpty {
                    DisclosureGroup("\(unavailableWeeks.count) 周未取得可用课表") {
                        ForEach(unavailableWeeks.sorted { $0.lowerBound < $1.lowerBound }, id: \.lowerBound) { week in
                            Text("\(displayDay(week.lowerBound)) 至 \(displayDay(week.upperBound))")
                        }
                    }
                }
                if let failure { Text(failure).font(.footnote).foregroundStyle(.secondary) }
            }
        }
        if successfulWeeks > 0 {
            Section {
                Text("\(courses.count) 门课程")
                if hasVerifiedAttendance {
                    Text("合计已签到 \(courses.reduce(0) { $0 + $1.signedCount }) 次")
                }
                if unknownCount > 0 {
                    Text("\(unknownCount) 节签到状态待核实").foregroundStyle(.secondary)
                }
                if courses.isEmpty {
                    Text("已取得的课表暂无课次").foregroundStyle(.secondary)
                }
            } header: {
                Text("\(displayDay(query.dayKeys.lowerBound)) 至 \(displayDay(query.dayKeys.upperBound))")
            }
            Section("课程") {
                ForEach(courses) { group in
                    NavigationLink {
                        courseDetail(group, partial: successfulWeeks < totalWeeks)
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(group.course.name).font(.headline)
                            let details = [group.teachers.joined(separator: "、"), group.course.courseNum].filter { !$0.isEmpty }
                            if !details.isEmpty {
                                Text(details.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary)
                            }
                            Text(group.hasVerifiedAttendance
                                 ? "共 \(group.records.count) 节 · 已签到 \(group.signedCount) 次"
                                 : "共 \(group.records.count) 节")
                                .font(.subheadline).foregroundStyle(.secondary)
                            if group.unknownCount > 0 {
                                Text("\(group.unknownCount) 节签到状态待核实").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
        }
    }

    private func courseDetail(_ group: CourseAttendance, partial: Bool) -> some View {
        List {
            Section {
                Text("共 \(group.records.count) 节")
                if group.hasVerifiedAttendance { Text("已签到 \(group.signedCount) 次") }
                if group.unknownCount > 0 { Text("\(group.unknownCount) 节签到状态待核实") }
                if let total = reports[group.attendanceCourseID]?.schoolSignedTotal {
                    Text("学校整门课统计已签到 \(total) 次")
                }
            } footer: {
                Text(partial ? "部分周课表未取得，课次可能不完整。" : "签到状态来自学校的个人考勤明细。")
            }
            Section("全部课次") {
                ForEach(group.records) { record in
                    VStack(alignment: .leading, spacing: 4) {
                        Text("\(displayDay(record.course.day))  \(SchoolDate.time(record.course.begin))")
                        if !record.teachers.isEmpty {
                            Text(record.teachers.joined(separator: "、")).font(.caption).foregroundStyle(.secondary)
                        }
                        Text(statusLabel(record.status))
                            .font(.caption)
                            .foregroundStyle(record.status == .signed ? Color.qingxin : .secondary)
                    }
                }
            }
        }
        .navigationTitle(group.course.name)
    }

    private func load(_ current: Query) async {
        let now = Date()
        let today = SchoolDate.key(now)
        let weeks = AttendanceHistory.weeks(in: current.dates)
        let ordered = Array(weeks.filter { $0.lowerBound <= today }.reversed()) + weeks.filter { $0.lowerBound > today }
        let days = current.dayKeys
        var records: [AttendanceRecord] = []
        finishedWeeks = 0
        totalWeeks = weeks.count
        finishedReports = 0
        totalReports = 0
        unavailableWeeks = []
        failure = nil
        courses = []
        reports = [:]
        loading = true
        do {
            try await state.loadAttendance(weeks: ordered, days: days) { week, result in
                guard current.id == query.id, !Task.isCancelled else { return }
                switch result {
                case .success(let lessons): records += lessons
                case .failure: unavailableWeeks.append(week)
                }
                finishedWeeks += 1
            } onReportsStarted: { schedule, count in
                guard current.id == query.id, !Task.isCancelled else { return }
                records = schedule
                totalReports = count
            } onReport: { report in
                guard current.id == query.id, !Task.isCancelled else { return }
                if let report { reports[report.courseId] = report }
                finishedReports += 1
            }
        } catch {
            guard !Task.isCancelled, !(error is CancellationError), current.id == query.id else { return }
            failure = error.localizedDescription
        }
        guard !Task.isCancelled, current.id == query.id else { return }
        let verified = AttendanceHistory.reconcile(schedule: records, with: reports)
        courses = AttendanceHistory.courses(from: verified, on: days, at: now)
        loadedQueryID = current.id
        loading = false
    }

    private func statusLabel(_ status: AttendanceRecord.Status) -> String {
        switch status {
        case .signed: return "已签到"
        case .unsigned: return "学校未标记签到"
        case .unknown: return "签到状态待核实"
        case .upcoming: return "未开始"
        case .inProgress: return "上课中"
        }
    }

    private func displayDay(_ key: String) -> String {
        "\(key.prefix(4))-\(key.dropFirst(4).prefix(2))-\(key.suffix(2))"
    }
}
