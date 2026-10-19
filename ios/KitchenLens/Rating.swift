import Foundation

// KitchenLens hygiene rating, version 2. Same rules as the Android app
// (HygieneScore.kt and KitchenStats.kt) — keep the two in step.
//
//  - Points come from PROOF (photos, clips, checklist, bills, temperature). Names, shift and
//    supplier are required, but earn no points.
//  - Records EXPIRE: full credit inside the window, half credit up to twice the window, then 0.
//    Utensils and staff: 12 h. Perishable inventory: 48 h. Dry goods: 7 days.
//    Pest control: until the next-due date, half credit for 7 days after it.
//  - "Not stocked" inventory categories are left out and the total is rescaled.
//  - A failed packing temperature WITHHOLDS the rating.

enum HygieneScore {
    static let version = 2
    static let hotMinC = 65.0   // FSSAI guideline for hot food
    static let coldMaxC = 5.0

    static let categoryWeights: [(String, Int)] = [
        ("Vegetables", 5), ("Meat & Fish", 5), ("Dairy", 3), ("Spices", 3), ("Cooking oil", 6), ("Grains & Pulses", 3),
    ]
    static func weight(_ cat: String) -> Int { categoryWeights.first { $0.0 == cat }?.1 ?? 0 }
    static let billPoints = 7, cleaningPoints = 5, utensilVideoPoints = 10
    static let staffVideoPoints = 10, pestVendorPoints = 3, pestRecencyPoints = 4, pestPhotoPoints = 8
    static let tempPoints = 8, clipPoints = 15

    static let shiftFullH: Int64 = 12, shiftHalfH: Int64 = 24
    static let perishableFullH: Int64 = 48, perishableHalfH: Int64 = 96
    static let dryFullH: Int64 = 168, dryHalfH: Int64 = 336

    private static let hour: Int64 = 3_600_000
    private static let day: Int64 = 24 * 3_600_000

    struct Item { let name: String; let earned: Int; let maximum: Int; let note: String
        var map: [String: Any] { ["name": name, "earned": earned, "maximum": maximum, "note": note] }
    }
    struct SectionResult { let key: String; let name: String; let items: [Item]
        var earned: Int { items.reduce(0) { $0 + $1.earned } }
        var maximum: Int { items.reduce(0) { $0 + $1.maximum } }
        var map: [String: Any] { ["key": key, "name": name, "earned": earned, "maximum": maximum, "items": items.map(\.map)] }
    }
    struct Result {
        let percent: Int, earned: Int, maximum: Int
        let sections: [SectionResult]
        let complete: Bool, safetyGatePassed: Bool
        let issues: [String]
        let notStocked: [String]
        var status: String { !complete ? "incomplete" : (!safetyGatePassed ? "withheld" : "rated") }
        var rating: String { HygieneScore.rating(percent) }
        var stars: Double { HygieneScore.stars(percent) }

        /// Items worth the most points that are not fully earned: what to record next.
        func tips(_ limit: Int = 3) -> [String] {
            sections.flatMap { s in s.items.map { (s, $0) } }
                .filter { $0.1.earned < $0.1.maximum }
                .sorted { ($0.1.maximum - $0.1.earned) > ($1.1.maximum - $1.1.earned) }
                .prefix(limit)
                .map { "\($0.0.name): \($0.1.name) (+\($0.1.maximum - $0.1.earned)) – \($0.1.note)" }
        }

        var map: [String: Any] {
            ["version": HygieneScore.version, "percent": percent, "earned": earned, "maximum": maximum,
             "sections": sections.map(\.map), "complete": complete, "safetyGatePassed": safetyGatePassed,
             "status": status, "rating": rating, "stars": stars, "issues": issues, "notStocked": notStocked]
        }
    }

    static func temperatureOk(foodType: String, tempC: Double) -> Bool {
        foodType == "Cold" ? tempC <= coldMaxC : tempC >= hotMinC
    }

    static func withheldReason(_ foodType: String) -> String {
        foodType == "Cold" ? "Food above 5°C at packing – rating withheld" : "Food below 65°C at packing – rating withheld"
    }

    static func freshness(_ at: Int64?, now: Int64, full: Int64, half: Int64) -> Double {
        guard let at, at > 0 else { return 0 }
        let age = now - at
        if age <= full * hour { return 1 }
        if age <= half * hour { return 0.5 }
        return 0
    }

    private static func pts(_ weight: Int, _ factor: Double) -> Int {
        min(weight, max(0, Int((Double(weight) * factor).rounded())))
    }
    private static func windowText(_ h: Int64) -> String { h >= 48 ? "\(h / 24) days" : "\(h) h" }
    private static func ageText(_ at: Int64, _ now: Int64) -> String {
        let h = (now - at) / hour
        return h < 1 ? "under 1 h ago" : (h < 48 ? "\(h) h ago" : "\(h / 24) days ago")
    }
    private static func freshNote(_ at: Int64?, _ now: Int64, _ f: Double, _ full: Int64, _ what: String) -> String {
        guard let at, at > 0 else { return "\(what) not recorded" }
        if f >= 1 { return "\(what) recorded \(ageText(at, now))" }
        if f > 0 { return "Half credit: \(what) recorded \(ageText(at, now)) (full credit for \(windowText(full)))" }
        return "Expired: \(what) recorded \(ageText(at, now)) (counts for \(windowText(full)))"
    }

    static func compute(latest: [String: [String: Any]], foodType: String, tempC: Double, packerName: String,
                        packingClipUrl: String, now: Int64, notStocked: Set<String> = []) -> Result {
        func details(_ e: [String: Any]?) -> [String: Any] { e?["details"] as? [String: Any] ?? [:] }
        func value(_ e: [String: Any]?, _ k: String) -> String {
            let v = details(e)[k]
            return ((v as? String) ?? (v.map { "\($0)" } ?? "")).trimmingCharacters(in: .whitespaces)
        }
        func at(_ e: [String: Any]?) -> Int64? { num(e?["at"]) }
        func isVideo(_ e: [String: Any]?) -> Bool { (e?["mediaType"] as? String) == "video" }
        func isPhoto(_ e: [String: Any]?) -> Bool { e != nil && (e?["mediaType"] as? String) != "video" }
        func list(_ s: String) -> Int { Set(s.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }).count }

        // 1. Food inventory
        var inv: [Item] = []
        var bestBill = 0.0
        var billAt: Int64?
        for (cat, w) in categoryWeights {
            let e = latest[latestKey(.INVENTORY, category: cat)]
            let perishable = PERISHABLE_CATEGORIES.contains(cat)
            let full = perishable ? perishableFullH : dryFullH
            let f = freshness(at(e), now: now, full: full, half: perishable ? perishableHalfH : dryHalfH)
            if (Int(value(e, "billCount")) ?? 0) > 0 && f > bestBill { bestBill = f; billAt = at(e) }
            if notStocked.contains(cat) { continue }
            let f2 = (e != nil && isPhoto(e)) ? f : 0
            inv.append(Item(name: cat, earned: pts(w, f2), maximum: w, note: freshNote(at(e), now, f2, full, "Photos")))
        }
        inv.append(Item(name: "Supplier bills", earned: pts(billPoints, bestBill), maximum: billPoints,
                        note: billAt == nil ? "No supplier bill linked to a current delivery" : freshNote(billAt, now, bestBill, perishableFullH, "Bill")))
        let inventory = SectionResult(key: "INVENTORY", name: "Food inventory details", items: inv)

        // 2. Utensils
        let ut = latest[Section.UTENSILS.rawValue]
        let utF = freshness(at(ut), now: now, full: shiftFullH, half: shiftHalfH)
        let methods = list(value(ut, "cleaningMedium"))
        let methodPts = methods >= 3 ? 5 : (methods == 2 ? 3 : (methods == 1 ? 1 : 0))
        let utensils = SectionResult(key: "UTENSILS", name: "Cooking utensils cleaning", items: [
            Item(name: "Cleaning methods (3 or more for full points)", earned: pts(methodPts, utF), maximum: cleaningPoints,
                 note: ut == nil ? "Not recorded" : "\(methods) method(s) · " + freshNote(at(ut), now, utF, shiftFullH, "record")),
            Item(name: "Wash video", earned: isVideo(ut) ? pts(utensilVideoPoints, utF) : 0, maximum: utensilVideoPoints,
                 note: freshNote(at(ut), now, isVideo(ut) ? utF : 0, shiftFullH, "Video")),
        ])

        // 3. Staff
        let st = latest[Section.STAFF.rawValue]
        let stF = freshness(at(st), now: now, full: shiftFullH, half: shiftHalfH)
        let checks = min(5, list(value(st, "checklist")))
        let staff = SectionResult(key: "STAFF", name: "Chef and support-staff hygiene", items: [
            Item(name: "Hygiene checks shown (1 point each)", earned: pts(checks, stF), maximum: 5,
                 note: st == nil ? "Not recorded" : "\(checks) of 5 checks · " + freshNote(at(st), now, stF, shiftFullH, "record")),
            Item(name: "Hand-wash and kit video", earned: isVideo(st) ? pts(staffVideoPoints, stF) : 0, maximum: staffVideoPoints,
                 note: freshNote(at(st), now, isVideo(st) ? stF : 0, shiftFullH, "Video")),
        ])

        // 4. Pest control
        let pe = latest[Section.PEST.rawValue]
        let serviced = parseServiceDate(value(pe, "serviceDate"))
        let due = Int64(value(pe, "nextDueMillis")) ?? serviced.map { $0 + 30 * day }
        let peF: Double = {
            guard pe != nil, let due else { return 0 }
            if now <= due { return 1 }
            if now <= due + 7 * day { return 0.5 }
            return 0
        }()
        let dueNote: String = {
            if pe == nil { return "Not recorded" }
            guard let due else { return "Next service date missing" }
            if peF >= 1 { return "Valid until next service on \(Dates.format(due))" }
            if peF > 0 { return "Half credit: next service was due \(Dates.format(due))" }
            return "Expired: next service was due \(Dates.format(due))"
        }()
        let pest = SectionResult(key: "PEST", name: "Pest control", items: [
            Item(name: "Pest-control vendor", earned: value(pe, "vendor").isEmpty ? 0 : pts(pestVendorPoints, peF), maximum: pestVendorPoints, note: dueNote),
            Item(name: "Service recency", earned: peF > 0 ? pestDatePoints(serviced, now) : 0, maximum: pestRecencyPoints, note: pestDateNote(serviced, now)),
            Item(name: "Service report photo", earned: isPhoto(pe) ? pts(pestPhotoPoints, peF) : 0, maximum: pestPhotoPoints,
                 note: pe == nil ? "Not recorded" : dueNote),
        ])

        // 5. Packing
        let tempOk = temperatureOk(foodType: foodType, tempC: tempC)
        let packing = SectionResult(key: "PACKING", name: "Food packing and temperature", items: [
            Item(name: "Food temperature at packing", earned: tempOk ? tempPoints : 0, maximum: tempPoints,
                 note: tempOk ? "Guideline met (\(Int(tempC.rounded()))°C)" : withheldReason(foodType)),
            Item(name: "Packing video", earned: packingClipUrl.isEmpty ? 0 : clipPoints, maximum: clipPoints,
                 note: packingClipUrl.isEmpty ? "Packing video missing" : "Packing video linked"),
        ])

        let sections = [inventory, utensils, staff, pest, packing]
        let earned = sections.reduce(0) { $0 + $1.earned }
        let maximum = sections.reduce(0) { $0 + $1.maximum }
        let percent = maximum == 0 ? 0 : Int((Double(earned) * 100 / Double(maximum)).rounded())

        let inventoryPresent = latest.keys.contains { $0.hasPrefix("INV_") }
        let packer = packerName.trimmingCharacters(in: .whitespaces)
        let complete = inventoryPresent && ut != nil && st != nil && pe != nil && !packer.isEmpty && !packingClipUrl.isEmpty
        var issues: [String] = []
        if !inventoryPresent { issues.append("Food inventory not recorded") }
        if ut == nil { issues.append("Utensils cleaning not recorded") }
        if st == nil { issues.append("Staff hygiene not recorded") }
        if pe == nil { issues.append("Pest control not recorded") }
        if packer.isEmpty { issues.append("Packer name missing") }
        if packingClipUrl.isEmpty { issues.append("Packing video missing") }
        if !tempOk { issues.append(withheldReason(foodType)) }
        for i in sections.flatMap(\.items) where i.note.hasPrefix("Expired") { issues.append("\(i.name): \(i.note)") }
        let skipped = categoryWeights.map(\.0).filter { notStocked.contains($0) }
        return Result(percent: percent, earned: earned, maximum: maximum, sections: sections, complete: complete,
                      safetyGatePassed: tempOk, issues: issues, notStocked: skipped)
    }

    /// What the NEXT order would score with today's records (temperature met, clip recorded).
    static func readiness(latest: [String: [String: Any]], now: Int64, notStocked: Set<String>) -> Result {
        compute(latest: latest, foodType: "Hot", tempC: hotMinC, packerName: "packer", packingClipUrl: "clip", now: now, notStocked: notStocked)
    }

    private static func pestDatePoints(_ at: Int64?, _ now: Int64) -> Int {
        guard let at else { return 0 }
        let days = (now - at) / day
        if days < 0 { return 0 }
        return days < 7 ? 4 : (days < 14 ? 3 : (days < 30 ? 2 : 1))
    }
    private static func pestDateNote(_ at: Int64?, _ now: Int64) -> String {
        guard let at else { return "Service date missing or invalid" }
        let days = (now - at) / day
        if days < 0 { return "Service date is in the future" }
        return days < 7 ? "Serviced within the last 7 days" : (days < 14 ? "Serviced within the last 14 days"
            : (days < 30 ? "Serviced within the last 30 days" : "Serviced more than 30 days ago"))
    }

    static func parseServiceDate(_ s: String) -> Int64? {
        guard !s.isEmpty else { return nil }
        for pattern in ["dd-MM-yyyy", "d-M-yyyy", "yyyy-MM-dd"] {
            let f = DateFormatter()
            f.dateFormat = pattern
            f.locale = Locale(identifier: "en_US_POSIX")
            f.isLenient = false
            if let d = f.date(from: s) { return Int64(d.timeIntervalSince1970 * 1000) }
        }
        return nil
    }

    static func rating(_ p: Int) -> String {
        p >= 90 ? "Excellent" : (p >= 80 ? "Very good" : (p >= 70 ? "Good" : (p >= 50 ? "Fair" : "Needs improvement")))
    }
    /// Percentage to stars out of 5 in half stars (90% = 4.5).
    static func stars(_ p: Int) -> Double { (Double(min(100, max(0, p))) / 10).rounded() / 2 }
    static func starText(_ s: Double) -> String {
        let full = Int(s), half = s - Double(full) >= 0.5
        return String(repeating: "★", count: full) + (half ? "½" : "") + String(repeating: "☆", count: 5 - full - (half ? 1 : 0))
    }
}

/// Kitchen-level rating over 30 days: 75% average order score + 15% consistency + 10% spot checks.
struct KitchenStats {
    static let windowDays: Int64 = 30
    static let minOrders = 5
    static let maxEntries = 3000
    private static let day: Int64 = 24 * 3_600_000

    var orders = 0, avgScore = 0, consistency = 0, spotAnswered = 0, spotRequested = 0, feedbackCount = 0
    var sealIntactPct: Int?, foodHotPct: Int?
    var trend = ""
    var percent = 0
    var updatedAt: Int64 = 0

    var eligible: Bool { orders >= KitchenStats.minOrders }
    var stars: Double { HygieneScore.stars(percent) }
    var rating: String { HygieneScore.rating(percent) }

    var map: [String: Any] {
        var m: [String: Any] = ["orders": orders, "avgScore": avgScore, "consistency": consistency,
                                "spotAnswered": spotAnswered, "spotRequested": spotRequested, "feedbackCount": feedbackCount,
                                "trend": trend, "percent": percent, "stars": stars, "rating": rating, "eligible": eligible,
                                "updatedAt": updatedAt, "windowDays": KitchenStats.windowDays]
        if let s = sealIntactPct { m["sealIntactPct"] = s }
        if let f = foodHotPct { m["foodHotPct"] = f }
        return m
    }

    func summary() -> String {
        eligible ? "\(stars)★ \(rating) · \(orders) orders in 30 days" + (trend.isEmpty ? "" : " · \(trend)")
            : "New kitchen: rating shown after \(KitchenStats.minOrders) sealed orders (\(orders) so far)"
    }

    static func encode(_ at: Int64, _ percent: Int, _ status: String) -> String { "\(at)|\(percent)|\(status.prefix(1))" }

    static func decode(_ s: String) -> (at: Int64, percent: Int, status: String)? {
        let p = s.split(separator: "|").map(String.init)
        guard p.count >= 3, let at = Int64(p[0]), let pct = Int(p[1]) else { return nil }
        return (at, pct, p[2] == "r" ? "rated" : (p[2] == "w" ? "withheld" : "incomplete"))
    }

    static func append(_ list: [String], _ entry: String, now: Int64) -> [String] {
        Array((list + [entry]).filter { (decode($0)?.at ?? 0) >= now - windowDays * day }.suffix(maxEntries))
    }

    static func compute(kitchenId: String, orderStats: [String], spotAnswers: [Int64], feedback: [(Bool, Bool)],
                        now: Int64, timeZone: TimeZone = .current) -> KitchenStats {
        let since = now - windowDays * day
        let orders = orderStats.compactMap(decode).filter { $0.at >= since && $0.at <= now }
        let n = orders.count
        var s = KitchenStats()
        s.orders = n
        s.updatedAt = now
        guard n > 0 else { return s }
        s.avgScore = Int((Double(orders.reduce(0) { $0 + $1.percent }) / Double(n)).rounded())
        s.consistency = Int((Double(orders.filter { $0.status == "rated" }.count) * 100 / Double(n)).rounded())

        let days = Set(orders.map { SpotCheck.dayStart($0.at, timeZone) })
        for d in days {
            let start = SpotCheck.windowStart(kitchenId: kitchenId, dayStart: d)
            if start + SpotCheck.windowMs > now { continue }
            s.spotRequested += 1
            if spotAnswers.contains(where: { $0 >= start && $0 <= start + SpotCheck.windowMs }) { s.spotAnswered += 1 }
        }
        let avg = Double(s.avgScore), cons = Double(s.consistency)
        let p: Double = s.spotRequested == 0 ? (0.75 * avg + 0.15 * cons) / 0.90
            : 0.75 * avg + 0.15 * cons + 0.10 * (Double(s.spotAnswered) * 100 / Double(s.spotRequested))
        s.percent = min(100, max(0, Int(p.rounded())))

        let recent = orders.filter { $0.at >= now - 7 * day }, older = orders.filter { $0.at < now - 7 * day }
        if recent.count >= 3 && older.count >= 3 {
            let mean: ([(at: Int64, percent: Int, status: String)]) -> Double = { a in Double(a.reduce(0) { $0 + $1.percent }) / Double(a.count) }
            let d = mean(recent) - mean(older)
            s.trend = d >= 3 ? "improving" : (d <= -3 ? "slipping" : "steady")
        }
        s.feedbackCount = feedback.count
        if feedback.count >= 5 {
            s.sealIntactPct = Int((Double(feedback.filter { $0.0 }.count) * 100 / Double(feedback.count)).rounded())
            s.foodHotPct = Int((Double(feedback.filter { $0.1 }.count) * 100 / Double(feedback.count)).rounded())
        }
        return s
    }
}

/// Daily surprise spot check between 11:00 and 21:30, at a time derived from the kitchen id
/// and date (same FNV-1a hash as the Android app, so both apps agree). 30 minutes to answer.
enum SpotCheck {
    static let windowMs: Int64 = 30 * 60_000
    private static let firstMinute: Int64 = 11 * 60
    private static let spanMinutes: UInt32 = 10 * 60 + 30

    static func dayStart(_ ms: Int64, _ tz: TimeZone = .current) -> Int64 {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = tz
        let d = cal.startOfDay(for: Date(timeIntervalSince1970: Double(ms) / 1000))
        return Int64(d.timeIntervalSince1970 * 1000)
    }

    static func windowStart(kitchenId: String, dayStart: Int64) -> Int64 {
        let dayNumber = (dayStart + 12 * 3_600_000) / (24 * 3_600_000)
        let minute = firstMinute + Int64(fnv1a("\(kitchenId)|\(dayNumber)") % spanMinutes)
        return dayStart + minute * 60_000
    }

    /// The open window right now (start, end), or nil.
    static func current(kitchenId: String, now: Int64) -> (start: Int64, end: Int64)? {
        let start = windowStart(kitchenId: kitchenId, dayStart: dayStart(now))
        return now >= start && now < start + windowMs ? (start, start + windowMs) : nil
    }

    static func answered(_ answers: [Int64], _ w: (start: Int64, end: Int64)) -> Bool {
        answers.contains { $0 >= w.start && $0 < w.end }
    }

    static func fnv1a(_ text: String) -> UInt32 {
        var h: UInt32 = 2_166_136_261
        for b in text.utf8 { h ^= UInt32(b); h = h &* 16_777_619 }
        return h
    }
}
