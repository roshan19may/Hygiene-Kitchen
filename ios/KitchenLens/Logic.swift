import Foundation
import Compression

// Rules shared with the Android app. Keep the two in step so seals look the same
// whichever phone created them.

// HygieneScore, KitchenStats and SpotCheck live in Rating.swift.

enum Gstin {
    enum Result { case empty, badFormat, badChecksum, ok }
    private static let chars = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ")
    private static let pattern = "[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]"

    static func normalize(_ s: String) -> String {
        String(s.uppercased().filter { $0.isLetter || $0.isNumber }.prefix(15))
    }

    static func check(_ raw: String) -> Result {
        let g = normalize(raw)
        if g.isEmpty { return .empty }
        guard g.range(of: "^" + pattern + "$", options: .regularExpression) != nil else { return .badFormat }
        return Array(g)[14] == checkChar(g) ? .ok : .badChecksum
    }

    static func checkChar(_ g: String) -> Character {
        let a = Array(g)
        var sum = 0
        for i in 0..<14 {
            let p = (chars.firstIndex(of: a[i]) ?? 0) * (i % 2 == 0 ? 1 : 2)
            sum += p / 36 + p % 36
        }
        return chars[(36 - sum % 36) % 36]
    }

    static func formError(_ raw: String, required: Bool) -> String? {
        switch check(raw) {
        case .empty: return required ? "Enter the GST number" : nil
        case .badFormat: return "GST number must be 15 characters, like 29ABCDE1234F1ZW"
        default: return nil
        }
    }

    static func warning(_ raw: String) -> String? {
        check(raw) == .badChecksum ? "The last character doesn't match. Please double-check the GST number." : nil
    }

    /// First GST number on a bill's text (prefers one whose check character is valid).
    static func find(in text: String) -> String? {
        let t = text.uppercased().replacingOccurrences(of: " ", with: "")
        guard let re = try? NSRegularExpression(pattern: pattern) else { return nil }
        let matches = re.matches(in: t, range: NSRange(t.startIndex..., in: t)).compactMap { Range($0.range, in: t).map { String(t[$0]) } }
        return matches.first { check($0) == .ok } ?? matches.first
    }
}

enum Dates {
    static let formatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "dd-MM-yyyy"
        f.locale = Locale(identifier: "en_US_POSIX")
        f.isLenient = false
        return f
    }()

    static func parse(_ s: String) -> Int64? {
        formatter.date(from: s.trimmingCharacters(in: .whitespaces)).map { Int64($0.timeIntervalSince1970 * 1000) }
    }
    static func format(_ ms: Int64) -> String { formatter.string(from: Date(timeIntervalSince1970: Double(ms) / 1000)) }
    static func latest(_ dates: [String]) -> String? {
        dates.compactMap { d in parse(d).map { (d.trimmingCharacters(in: .whitespaces), $0) } }.max { $0.1 < $1.1 }?.0
    }

    /// First day-first date (dd/mm/yyyy, dd-mm-yy, …) in the last 60 days, as dd-MM-yyyy.
    static func pickBillDate(_ text: String, now: Int64) -> String? {
        guard let re = try? NSRegularExpression(pattern: #"\b(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{2,4})\b"#) else { return nil }
        let sixty: Int64 = 60 * 24 * 3_600_000
        for m in re.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
            func g(_ i: Int) -> Int? { Range(m.range(at: i), in: text).flatMap { Int(text[$0]) } }
            guard let d = g(1), let mo = g(2), var y = g(3), (1...31).contains(d), (1...12).contains(mo) else { continue }
            if y < 100 { y += 2000 }
            let s = String(format: "%02d-%02d-%04d", d, mo, y)
            guard let t = parse(s) else { continue }
            if t <= now + 24 * 3_600_000 && now - t <= sixty { return s }
        }
        return nil
    }

    /// "just now", "25 min ago", "3 h ago", "2 days ago"
    static func ago(_ at: Int64, now: Int64 = nowMs()) -> String {
        let min = (now - at) / 60_000
        if min < 1 { return "just now" }
        if min < 60 { return "\(min) min ago" }
        if min < 48 * 60 { return "\(min / 60) h ago" }
        return "\(min / (24 * 60)) days ago"
    }

    static let stampFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "dd MMM yyyy, hh:mm:ss a zzz"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f
    }()
    /// e.g. "18 Oct 2026, 07:42:10 PM GMT+5:30" (burned into photos)
    static func stamp(_ date: Date) -> String { stampFormatter.string(from: date) }

    static func dateTime(_ ms: Int64) -> String {
        let f = DateFormatter()
        f.dateFormat = "d MMM, h:mm a"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
    }
}

/// "Swiggy" + "1588-2334 4471" -> "SWIGGY_158823344471"
func orderRefKey(platform: String, orderRef: String) -> String {
    func clean(_ s: String) -> String { s.uppercased().filter { ($0.isLetter || $0.isNumber) && $0.isASCII } }
    return clean(platform) + "_" + clean(orderRef)
}

let PRIVATE_DETAIL_KEYS: Set<String> = ["billUrl", "billUrls", "supplierGstins"]

func profileMap(name: String, locality: String, city: String, fssai: String, gstin: String, tier: String,
                fssaiHygieneRating: String = "", fssaiAuditDate: String = "", notStocked: Set<String> = []) -> [String: String] {
    ["name": name.trimmingCharacters(in: .whitespaces), "locality": locality.trimmingCharacters(in: .whitespaces),
     "city": city, "fssai": fssai.trimmingCharacters(in: .whitespaces), "gstin": gstin.trimmingCharacters(in: .whitespaces), "tier": tier,
     "fssaiHygieneRating": fssaiHygieneRating.trimmingCharacters(in: .whitespaces),
     "fssaiAuditDate": fssaiAuditDate.trimmingCharacters(in: .whitespaces),
     // Kept as text so the profile stays a simple map of strings (same as Android)
     "notStocked": notStocked.sorted().joined(separator: "|")]
}

/// Public kitchen profile for the kitchen page (no private data).
func publicKitchenMap(_ k: Kitchen, _ stats: KitchenStats) -> [String: Any] {
    ["name": k.name, "locality": k.locality, "city": k.city, "fssai": k.fssai, "gstin": k.gstin,
     "fssaiHygieneRating": k.fssaiHygieneRating, "fssaiAuditDate": k.fssaiAuditDate,
     "notStocked": k.notStocked.sorted(), "stats": stats.map, "updatedAt": stats.updatedAt]
}

/// Public summary kept under kitchen.latest; bill photos and supplier GSTINs stay private.
func publicSummary(at: Int64, mediaUrl: String, mediaType: MediaType, label: String,
                   details: [String: String], mediaUrls: [String] = [], mediaThumbs: [String] = []) -> [String: Any] {
    var m: [String: Any] = [
        "at": at, "mediaUrl": mediaUrl, "mediaType": mediaType.rawValue, "label": label,
        "details": details.filter { !PRIVATE_DETAIL_KEYS.contains($0.key) },
    ]
    if !mediaUrls.isEmpty { m["mediaUrls"] = mediaUrls }
    // Small preview copies in the same order as the photos (fast thumbnails on the report)
    if mediaThumbs.contains(where: { !$0.isEmpty }) { m["mediaThumbs"] = mediaThumbs }
    return m
}

struct InventoryRecord {
    let category: String
    let key: String
    let label: String
    let mediaUrls: [String]
    let details: [String: String]
}

/// One record per category; a bill counts for every category it covers.
func buildInventoryRecords(photoUrls: [String: [String]], bills: [InventoryBill], billUrls: [String],
                           supplierType: String?) -> [InventoryRecord] {
    photoUrls.filter { !$0.value.isEmpty }.sorted { $0.key < $1.key }.map { cat, urls in
        let idx = bills.indices.filter { bills[$0].categories.contains(cat) }
        var d: [String: String] = ["category": cat, "photoCount": String(urls.count)]
        if let s = supplierType, !s.isEmpty { d["supplierType"] = s }
        if !idx.isEmpty {
            d["billCount"] = String(idx.count)
            if let latest = Dates.latest(idx.map { bills[$0].billDate }) { d["billDate"] = latest }
            let gst = Array(Set(idx.map { bills[$0].supplierGstin }.filter { !$0.isEmpty })).sorted()
            if !gst.isEmpty { d["supplierGst"] = "registered"; d["supplierGstins"] = gst.joined(separator: ",") }
            d["billUrls"] = idx.compactMap { $0 < billUrls.count ? billUrls[$0] : nil }.joined(separator: ",")
        }
        return InventoryRecord(category: cat, key: latestKey(.INVENTORY, category: cat), label: "\(cat) received",
                               mediaUrls: urls, details: d)
    }
}

/// The public order record (same fields as the Android app).
func buildOrderRecord(kitchen: Kitchen, platform: String, orderRef: String, packerName: String, foodType: String,
                      tempC: Double, tempOk: Bool, clipUrl: String, packedAt: Int64, score: Int,
                      clipRecordedAt: Int64, scoreBreakdown: [String: Any] = [:], kitchenStats: [String: Any] = [:]) -> [String: Any] {
    let digits = orderRef.filter { $0.isLetter || $0.isNumber }
    return [
        "clipRecordedAt": clipRecordedAt,
        "kitchenId": kitchen.id, "kitchenName": kitchen.name, "kitchenLocality": kitchen.locality,
        "kitchenCity": kitchen.city, "fssai": kitchen.fssai, "gstin": kitchen.gstin,
        "platform": platform,
        "orderRef": orderRef.trimmingCharacters(in: .whitespaces),
        "orderRefKey": orderRefKey(platform: platform, orderRef: orderRef),
        "orderRefLast4": String(digits.suffix(4)),
        "packerName": packerName.trimmingCharacters(in: .whitespaces).split(separator: " ").first.map(String.init) ?? "",
        "foodType": foodType, "tempC": tempC, "tempOk": tempOk, "clipUrl": clipUrl,
        "packedAtMillis": packedAt, "score": score,
        "scoreBreakdown": scoreBreakdown,
        "scoreComplete": scoreBreakdown["complete"] as? Bool ?? false,
        "safetyGatePassed": scoreBreakdown["safetyGatePassed"] as? Bool ?? false,
        "scoreStatus": scoreBreakdown["status"] as? String ?? "incomplete",
        "rating": scoreBreakdown["rating"] as? String ?? "",
        "stars": scoreBreakdown["stars"] as? Double ?? 0,
        "notStocked": kitchen.notStocked.sorted(),
        "fssaiHygieneRating": kitchen.fssaiHygieneRating, "fssaiAuditDate": kitchen.fssaiAuditDate,
        "kitchenStats": kitchenStats,
        "snapshot": kitchen.latest.filter { $0.key != "SPOT" },
    ]
}

/// Random, unguessable 8-character seal code without look-alike characters.
func newSealCode() -> String {
    let alphabet = Array("23456789ABCDEFGHJKMNPQRSTUVWXYZ")
    var g = SystemRandomNumberGenerator()
    return String((0..<8).map { _ in alphabet[Int.random(in: 0..<alphabet.count, using: &g)] })
}

/// Packs the order into the QR link itself (demo mode), exactly like the Android app,
/// so the same customer report page can read it: "z" + base64url(raw deflate(json)).
enum OfflineSeal {
    static func url(base: String, code: String, order: [String: Any]) -> String {
        base + code + (base.contains("?") ? "&" : "?") + "d=" + payload(order)
    }

    static func payload(_ order: [String: Any]) -> String {
        let json = Data(compactJson(order).utf8)
        return "z" + base64url(deflateRaw(json))
    }

    static func deflateRaw(_ data: Data) -> Data {
        let capacity = data.count + 1024
        let dst = UnsafeMutablePointer<UInt8>.allocate(capacity: capacity)
        defer { dst.deallocate() }
        let n = data.withUnsafeBytes { (src: UnsafeRawBufferPointer) -> Int in
            guard let base = src.bindMemory(to: UInt8.self).baseAddress else { return 0 }
            // COMPRESSION_ZLIB writes raw DEFLATE (no zlib header), as the web page expects
            return compression_encode_buffer(dst, capacity, base, data.count, nil, COMPRESSION_ZLIB)
        }
        return Data(bytes: dst, count: n)
    }

    static func base64url(_ d: Data) -> String {
        d.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func compactJson(_ o: [String: Any]) -> String {
        let packedAt = num(o["packedAtMillis"]) ?? 0
        let snap = o["snapshot"] as? [String: Any] ?? [:]
        struct Row { let code: String; let minutes: Int64; let detail: String; let ids: [String] }
        var rows: [Row] = []
        for (key, value) in snap {
            guard let e = value as? [String: Any], let at = num(e["at"]) else { continue }
            let d = e["details"] as? [String: Any] ?? [:]
            let code: String
            if key == "UTENSILS" { code = "U" } else if key == "STAFF" { code = "S" } else if key == "PEST" { code = "P" }
            else if key.hasPrefix("INV_") { code = "I" + ((d["category"] as? String) ?? String(key.dropFirst(4))) }
            else { continue }
            rows.append(Row(code: code, minutes: (packedAt - at) / 60_000, detail: rowDetail(code, d),
                            ids: e["cloudIds"] as? [String] ?? []))
        }
        rows.sort { $0.minutes < $1.minutes }

        var parts: [String] = []
        func field(_ name: String, _ value: Any?) {
            switch value {
            case nil: return
            case let s as String: if !s.isEmpty { parts.append("\"\(name)\":\(str(s))") }
            case let b as Bool: parts.append("\"\(name)\":\(b ? 1 : 0)")
            case let d as Double: parts.append("\"\(name)\":\(fmt(d))")
            case let i as Int: parts.append("\"\(name)\":\(i)")
            case let i as Int64: parts.append("\"\(name)\":\(i)")
            default: return
            }
        }
        field("v", 2)
        field("k", o["kitchenName"] as? String)
        field("a", [o["kitchenLocality"] as? String, o["kitchenCity"] as? String].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: ", "))
        field("f", o["fssai"] as? String)
        field("g", o["gstin"] as? String)
        field("p", o["platform"] as? String)
        field("o", o["orderRef"] as? String)
        field("n", o["packerName"] as? String)
        field("ft", (o["foodType"] as? String) == "Cold" ? "C" : "H")
        field("t", dbl(o["tempC"]).map { ($0 * 10).rounded() / 10 })
        field("tk", (o["tempOk"] as? Bool) ?? false)
        field("s", num(o["score"]))
        if let b = o["scoreBreakdown"] as? [String: Any] {
            let secs = b["sections"] as? [[String: Any]] ?? []
            if !secs.isEmpty {
                field("b", secs.map { String(num($0["earned"]) ?? 0) }.joined(separator: ","))
                field("bm", secs.map { String(num($0["maximum"]) ?? 0) }.joined(separator: ","))
            }
            field("co", b["complete"] as? Bool)
            field("sg", b["safetyGatePassed"] as? Bool)
            field("st", (b["status"] as? String).map { String($0.prefix(1)) })
        }
        field("ns", (o["notStocked"] as? [String])?.joined(separator: "|"))
        field("fh", o["fssaiHygieneRating"] as? String)
        field("fa", o["fssaiAuditDate"] as? String)
        if let ks = o["kitchenStats"] as? [String: Any], let n = num(ks["orders"]) {
            field("ks", ["\(num(ks["percent"]) ?? 0)", "\(n)", "\(num(ks["consistency"]) ?? 0)", "\(num(ks["spotAnswered"]) ?? 0)",
                         "\(num(ks["spotRequested"]) ?? 0)", String(((ks["trend"] as? String) ?? "").prefix(1))].joined(separator: ","))
        }
        field("pt", packedAt / 60_000)
        field("cl", o["cloudName"] as? String)
        field("c", o["clipCloudId"] as? String)
        if let clipAt = num(o["clipRecordedAt"]), clipAt > 0, clipAt < packedAt { field("cr", (packedAt - clipAt) / 1000) }
        let rowJson = rows.map { r -> String in
            var s = "[\(str(r.code)),\(r.minutes),\(str(r.detail))"
            if !r.ids.isEmpty { s += ",[" + r.ids.map { str($0) }.joined(separator: ",") + "]" }
            return s + "]"
        }
        return "{" + parts.joined(separator: ",") + ",\"r\":[" + rowJson.joined(separator: ",") + "]}"
    }

    /// Demo-mode kitchen page: profile and 30-day rating packed into the badge link.
    static func kitchenPayload(_ pub: [String: Any]) -> String {
        let st = pub["stats"] as? [String: Any] ?? [:]
        func n(_ k: String) -> Int64 { num(st[k]) ?? 0 }
        var parts: [String] = ["\"v\":2"]
        func field(_ name: String, _ v: String?) { if let v, !v.isEmpty { parts.append("\"\(name)\":\(str(v))") } }
        field("k", pub["name"] as? String)
        field("a", [pub["locality"] as? String, pub["city"] as? String].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: ", "))
        field("f", pub["fssai"] as? String)
        field("g", pub["gstin"] as? String)
        field("fh", pub["fssaiHygieneRating"] as? String)
        field("fa", pub["fssaiAuditDate"] as? String)
        field("ns", (pub["notStocked"] as? [String])?.joined(separator: "|"))
        field("ks", "\(n("percent")),\(n("orders")),\(n("consistency")),\(n("spotAnswered")),\(n("spotRequested")),"
              + String(((st["trend"] as? String) ?? "").prefix(1)))
        parts.append("\"u\":\((num(pub["updatedAt"]) ?? 0) / 60_000)")
        return "z" + base64url(deflateRaw(Data(("{" + parts.joined(separator: ",") + "}").utf8)))
    }

    private static func fmt(_ d: Double) -> String {
        d == d.rounded() ? String(format: "%.1f", d) : String(d)
    }

    private static func rowDetail(_ code: String, _ d: [String: Any]) -> String {
        func s(_ k: String) -> String? { (d[k] as? String).flatMap { $0.isEmpty ? nil : $0 } }
        var parts: [String] = []
        switch code {
        case "U": if let m = s("cleaningMedium") { parts.append(m) }
        case "S":
            if let c = s("checklist") { parts.append("\(c.split(separator: ",").count) checks") }
            if let sh = s("shift") { parts.append(sh) }
        case "P": if let n = s("nextDueDate") { parts.append("next due \(n)") }
        default:
            if let p = s("photoCount") { parts.append(p == "1" ? "1 photo" : "\(p) photos") }
            if let b = s("billDate") { parts.append("bill \(b)") }
            if s("supplierGst") != nil { parts.append("GST") }
        }
        return String(parts.joined(separator: ", ").prefix(36))
    }

    private static func str(_ s: String) -> String {
        var out = "\""
        for ch in s.unicodeScalars {
            switch ch {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            default:
                if ch.value < 0x20 { out += String(format: "\\u%04x", ch.value) } else { out.unicodeScalars.append(ch) }
            }
        }
        return out + "\""
    }
}
