import Foundation

/// Milliseconds since 1970, the time unit shared with the Android app and the web report.
func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

/// Reads a number stored as Int, Int64, Double, NSNumber or String.
func num(_ v: Any?) -> Int64? {
    switch v {
    case let n as Int64: return n
    case let n as Int: return Int64(n)
    case let n as Double: return Int64(n)
    case let n as NSNumber: return n.int64Value
    case let s as String: return Int64(s)
    default: return nil
    }
}

func dbl(_ v: Any?) -> Double? {
    switch v {
    case let n as Double: return n
    case let n as Int: return Double(n)
    case let n as Int64: return Double(n)
    case let n as NSNumber: return n.doubleValue
    case let s as String: return Double(s)
    default: return nil
    }
}

enum MediaType: String { case photo, video }

/// The Kitchen Hygiene proof sections (same names and keys as the Android app).
enum Section: String, CaseIterable, Identifiable {
    case INVENTORY, UTENSILS, STAFF, PEST, PACKING
    var id: String { rawValue }

    var title: String {
        switch self {
        case .INVENTORY: return "Food inventory details"
        case .UTENSILS: return "Cooking Utensils cleaning details"
        case .STAFF: return "Chef, Support staff cooking hygiene"
        case .PEST: return "Pest control details"
        case .PACKING: return "Packing + temperature"
        }
    }
    var subtitle: String {
        switch self {
        case .INVENTORY: return "Photo of produce + supplier bill"
        case .UTENSILS: return "Short clip of the wash + cleaning medium"
        case .STAFF: return "Hand-wash, cap, gloves, apron check"
        case .PEST: return "Service report photo + next due date"
        case .PACKING: return "Per-order Hygiene Food Packing clip and Order QR seal"
        }
    }
    var media: MediaType {
        switch self {
        case .INVENTORY, .PEST: return .photo
        default: return .video
        }
    }
    var freshForHours: Int64 {
        switch self {
        case .INVENTORY: return 48
        case .UTENSILS, .STAFF: return 12
        case .PEST: return 24 * 30
        case .PACKING: return 0
        }
    }
    static let proof: [Section] = [.INVENTORY, .UTENSILS, .STAFF, .PEST]
}

let INVENTORY_CATEGORIES = ["Vegetables", "Meat & Fish", "Dairy", "Spices", "Cooking oil", "Grains & Pulses"]
let PERISHABLE_CATEGORIES: Set<String> = ["Vegetables", "Meat & Fish", "Dairy"]
let CLEANING_MEDIA = ["Detergent", "Hot water", "Food-grade sanitiser", "Scrub pad", "Dishwasher machine", "Air-dried on rack"]
let STAFF_CHECKLIST = ["Hand-wash with soap", "Cap / hairnet", "Gloves", "Clean apron", "Mask"]
let MIN_STAFF_CHECKS = 2
let SHIFTS = ["Morning", "Afternoon", "Night"]
let PLATFORMS = ["Swiggy", "Zomato", "Ownly", "Toing", "Direct / Other"]
let DIRECT_PLATFORM = "Direct / Other"
let CITIES = ["Bengaluru", "Mumbai", "Delhi NCR", "Hyderabad", "Chennai", "Pune", "Kolkata", "Ahmedabad"]
let TIERS = ["Up to 500 sq ft", "500 – 1,500 sq ft", "1,500 – 3,000 sq ft"]
let SUPPLIER_TYPES = ["Local market", "Wholesaler", "Online supplier", "Farm"]

/// Key in the kitchen's "latest" map. Inventory is stored per category.
func latestKey(_ section: Section, category: String? = nil) -> String {
    if section == .INVENTORY, let c = category {
        return "INV_" + c.uppercased().map { $0.isLetter && $0.isASCII ? String($0) : "_" }.joined()
    }
    return section.rawValue
}

struct Kitchen: Identifiable {
    var id: String
    var name = ""
    var locality = ""
    var city = ""
    var fssai = ""
    var gstin = ""
    var tier = ""
    var latest: [String: [String: Any]] = [:]
    var recentHashes: [String] = []

    static func from(id: String, _ m: [String: Any]) -> Kitchen {
        var k = Kitchen(id: id)
        k.name = m["name"] as? String ?? ""
        k.locality = m["locality"] as? String ?? ""
        k.city = m["city"] as? String ?? ""
        k.fssai = m["fssai"] as? String ?? ""
        k.gstin = m["gstin"] as? String ?? ""
        k.tier = m["tier"] as? String ?? ""
        k.latest = m["latest"] as? [String: [String: Any]] ?? [:]
        k.recentHashes = m["recentHashes"] as? [String] ?? []
        return k
    }
}

struct OrderSummary: Identifiable {
    var code: String
    var platform: String
    var orderRef: String
    var orderRefLast4: String
    var tempC: Double
    var score: Int
    var packedAtMillis: Int64
    var id: String { code }

    static func from(code: String, _ m: [String: Any]) -> OrderSummary {
        OrderSummary(
            code: code,
            platform: m["platform"] as? String ?? "",
            orderRef: m["orderRef"] as? String ?? "",
            orderRefLast4: m["orderRefLast4"] as? String ?? "",
            tempC: dbl(m["tempC"]) ?? 0,
            score: Int(num(m["score"]) ?? 0),
            packedAtMillis: num(m["packedAtMillis"]) ?? 0
        )
    }
}

/// A produce photo taken on the inventory screen.
struct InventoryPhoto: Identifiable {
    let id = UUID()
    let file: URL
    let hash: String
}

/// A supplier bill; can cover several categories. Photo and supplier GSTIN stay private.
struct InventoryBill: Identifiable {
    let id = UUID()
    let file: URL
    var categories: Set<String>
    var billDate: String
    var supplierGstin: String
    var note: String? = nil
}

/// The order being packed, saved on the phone until its QR seal is created.
struct OrderDraft: Codable, Equatable {
    var platform = ""
    var orderRef = ""
    var packer = ""
    var foodType = "Hot"
    var tempText = ""
    var clipFile: String? = nil      // file name inside the draft folder
    var clipSeconds = 0
    var clipRecordedAt: Int64 = 0
    var savedAt: Int64 = 0

    var isEmpty: Bool { platform.isEmpty && orderRef.isEmpty && tempText.isEmpty && clipFile == nil }
}

struct DuplicateOrderError: LocalizedError {
    let existingCode: String
    let platform: String
    let orderRef: String
    var errorDescription: String? {
        "A QR seal already exists for \(platform) order \(orderRef) (code \(existingCode)). Open it from Recent orders to reprint."
    }
}

struct AppError: LocalizedError {
    let message: String
    init(_ m: String) { message = m }
    var errorDescription: String? { message }
}
