import Foundation

/// Everything the app needs from a backend; same data layout as the Android app,
/// so both apps can share one Firebase project and one customer report page.
@MainActor
protocol KitchenRepository: AnyObject {
    var isDemo: Bool { get }
    func signedInUserId() -> String?
    func signIn(email: String, password: String) async throws
    func signUp(email: String, password: String) async throws
    /// Emails a password-reset link (live mode only).
    func sendPasswordReset(email: String) async throws
    func signOut()

    func loadKitchenId() async throws -> String?
    func createKitchen(profile: [String: String]) async throws -> String
    func updateKitchenProfile(kitchenId: String, profile: [String: String]) async throws
    func replaceCurrentRecords(kitchenId: String, latest: [String: [String: Any]]) async throws
    func getKitchen(_ id: String) async throws -> Kitchen

    func saveLog(kitchen: Kitchen, section: Section, key: String, staffName: String, media: URL, mediaType: MediaType,
                 details: [String: String], label: String, photoHash: String?, progress: @escaping (Double) -> Void) async throws
    func saveInventory(kitchen: Kitchen, staffName: String, supplierType: String?, photos: [String: [InventoryPhoto]],
                       bills: [InventoryBill], progress: @escaping (Double) -> Void) async throws
    func createOrder(kitchen: Kitchen, platform: String, orderRef: String, packerName: String, foodType: String,
                     tempC: Double, clip: URL, clipRecordedAt: Int64, progress: @escaping (Double) -> Void) async throws -> OrderSummary
    func recentOrders(kitchenId: String) async throws -> [OrderSummary]
    func findOrderCode(platform: String, orderRef: String) async throws -> String?
    func getOrder(_ code: String) async throws -> [String: Any]?
    /// Permanently deletes the account and all its data (store requirement).
    func deleteAccount(password: String) async throws
    /// Customer answers from the last 30 days: (seal intact, food at the right temperature). Live mode only.
    func recentFeedback(kitchenId: String, since: Int64) async throws -> [(Bool, Bool)]
    /// Publishes the public kitchen page (profile and 30-day rating).
    func publishKitchen(_ kitchen: Kitchen, stats: KitchenStats) async throws
    /// Background upload as soon as a photo or clip is captured.
    func prefetch(_ kitchen: Kitchen, file: URL, video: Bool)
}

extension KitchenRepository {
    /// Starts uploading a photo or clip in the background as soon as it is captured,
    /// so Save only waits for what is left. Demo and live mode override this.
    func prefetch(_ kitchen: Kitchen, file: URL, video: Bool) {}

    /// The kitchen's 30-day rating from its rolling order list, spot checks and customer feedback.
    func kitchenStats(_ k: Kitchen, now: Int64 = nowMs()) async -> KitchenStats {
        let feedback = (try? await recentFeedback(kitchenId: k.id, since: now - KitchenStats.windowDays * 24 * 3_600_000)) ?? []
        return KitchenStats.compute(kitchenId: k.id, orderStats: k.orderStats, spotAnswers: k.spotAnswers, feedback: feedback, now: now)
    }
}

/// First name of whoever recorded a step (kept with the public record, like Android).
func recordedBy(_ staffName: String) -> String {
    staffName.trimmingCharacters(in: .whitespaces).split(separator: " ").first.map(String.init) ?? ""
}

// MARK: - Demo mode: everything stays on this iPhone

@MainActor
final class LocalRepository: KitchenRepository {
    let isDemo = true
    let cloud = CloudMedia()
    private let dir = AppFiles.folder("demo")
    private var mediaDir: URL { AppFiles.folder("demo/media") }
    private var stateFile: URL { dir.appendingPathComponent("state.json") }
    private var state: [String: Any]

    init() {
        if let data = try? Data(contentsOf: AppFiles.folder("demo").appendingPathComponent("state.json")),
           let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
            state = obj
        } else {
            state = [:]
        }
    }

    private func save() {
        if let data = try? JSONSerialization.data(withJSONObject: state) { try? data.write(to: stateFile, options: .atomic) }
    }

    /// Media is referenced as "local:<file name>" because the app's folder path can change between updates.
    private func copyIn(_ file: URL, ext: String) throws -> String {
        let name = UUID().uuidString + "." + ext
        try FileManager.default.copyItem(at: file, to: mediaDir.appendingPathComponent(name))
        cloud.alias("local:" + name, from: file.path)   // reuse an upload that started when the photo was taken
        return "local:" + name
    }
    func fileFor(_ localUrl: String) -> URL? {
        guard localUrl.hasPrefix("local:") else { return nil }
        return mediaDir.appendingPathComponent(String(localUrl.dropFirst(6)))
    }

    func signedInUserId() -> String? { (state["user"] as? String).flatMap { $0.isEmpty ? nil : $0 } }
    func signIn(email: String, password: String) async throws {
        guard email.contains("@") else { throw AppError("Enter an email address") }
        state["user"] = email.trimmingCharacters(in: .whitespaces)
        save()
    }
    func signUp(email: String, password: String) async throws { try await signIn(email: email, password: password) }
    func sendPasswordReset(email: String) async throws {}   // demo mode has no passwords
    func signOut() { state.removeValue(forKey: "user"); save() }

    private var kitchenMap: [String: Any] {
        get { state["kitchen"] as? [String: Any] ?? [:] }
        set { state["kitchen"] = newValue }
    }

    func loadKitchenId() async throws -> String? { (kitchenMap["id"] as? String).flatMap { $0.isEmpty ? nil : $0 } }

    func createKitchen(profile: [String: String]) async throws -> String {
        let id = "demo-" + String(UUID().uuidString.prefix(8)).lowercased()
        var k: [String: Any] = ["id": id, "latest": [String: Any](), "recentHashes": [String]()]
        profile.forEach { k[$0.key] = $0.value }
        kitchenMap = k
        save()
        return id
    }

    func updateKitchenProfile(kitchenId: String, profile: [String: String]) async throws {
        var k = kitchenMap
        profile.forEach { k[$0.key] = $0.value }
        kitchenMap = k
        save()
    }

    func replaceCurrentRecords(kitchenId: String, latest: [String: [String: Any]]) async throws {
        var k = kitchenMap
        k["latest"] = latest
        kitchenMap = k
        save()
    }

    func getKitchen(_ id: String) async throws -> Kitchen { Kitchen.from(id: id, kitchenMap) }

    /// Best-effort early upload so creating the QR later is quick.
    /// Early upload in the BACKGROUND: saving no longer waits for cloud storage.
    /// Anything unfinished (or failed) is picked up when the QR seal is created.
    private func preUpload(_ urls: [String], video: Bool) {
        for u in urls { if let f = fileFor(u) { cloud.startUpload(localId: u, file: f, video: video) } }
    }

    func prefetch(_ kitchen: Kitchen, file: URL, video: Bool) {
        cloud.startUpload(localId: file.path, file: file, video: video)
    }

    private func commit(_ kitchen: Kitchen, summaries: [String: [String: Any]], newHashes: [String], logs: [[String: Any]]) {
        var k = kitchenMap
        var latest = k["latest"] as? [String: Any] ?? [:]
        summaries.forEach { latest[$0.key] = $0.value }
        k["latest"] = latest
        if !newHashes.isEmpty { k["recentHashes"] = Array((newHashes + kitchen.recentHashes).prefix(60)) }
        kitchenMap = k
        var all = state["logs"] as? [[String: Any]] ?? []
        all.append(contentsOf: logs)
        state["logs"] = all
        save()
    }

    func saveLog(kitchen: Kitchen, section: Section, key: String, staffName: String, media: URL, mediaType: MediaType,
                 details: [String: String], label: String, photoHash: String?, progress: @escaping (Double) -> Void) async throws {
        let url = try copyIn(media, ext: mediaType == .video ? "mp4" : "jpg")
        preUpload([url], video: mediaType == .video)
        progress(1)
        let now = nowMs()
        if section == .SPOT {
            // Spot checks count towards the kitchen rating; they don't replace a hygiene record
            var k = kitchenMap
            k["spotAnswers"] = Array((Kitchen.from(id: kitchen.id, k).spotAnswers + [now]).suffix(60))
            kitchenMap = k
            save()
            return
        }
        var d = details
        d["recordedBy"] = recordedBy(staffName)
        commit(kitchen, summaries: [key: publicSummary(at: now, mediaUrl: url, mediaType: mediaType, label: label, details: d)],
               newHashes: photoHash.map { [$0] } ?? [],
               logs: [["section": section.rawValue, "key": key, "staffName": staffName, "mediaUrl": url,
                       "details": details, "label": label, "capturedAtMillis": now]])
    }

    func saveInventory(kitchen: Kitchen, staffName: String, supplierType: String?, photos: [String: [InventoryPhoto]],
                       bills: [InventoryBill], progress: @escaping (Double) -> Void) async throws {
        var photoUrls: [String: [String]] = [:]
        for (cat, list) in photos { photoUrls[cat] = try list.map { try copyIn($0.file, ext: "jpg") } }
        let billUrls = try bills.map { try copyIn($0.file, ext: "jpg") }
        preUpload(photoUrls.values.flatMap { $0 }, video: false)
        progress(1)
        let now = nowMs()
        let records = buildInventoryRecords(photoUrls: photoUrls, bills: bills, billUrls: billUrls, supplierType: supplierType)
        var summaries: [String: [String: Any]] = [:]
        for r in records {
            var d = r.details
            d["recordedBy"] = recordedBy(staffName)
            summaries[r.key] = publicSummary(at: now, mediaUrl: r.mediaUrls[0], mediaType: .photo, label: r.label,
                                             details: d, mediaUrls: r.mediaUrls)
        }
        commit(kitchen, summaries: summaries, newHashes: photos.values.flatMap { $0.map(\.hash) },
               logs: records.map { ["section": "INVENTORY", "key": $0.key, "staffName": staffName, "mediaUrls": $0.mediaUrls,
                                    "details": $0.details, "label": $0.label, "capturedAtMillis": now] })
    }

    /// Adds Cloudinary IDs to every photo and clip in the hygiene snapshot (uploads what's missing).
    private func withCloudIds(_ latest: [String: [String: Any]], step: () -> Void) async throws -> [String: [String: Any]] {
        var out: [String: [String: Any]] = [:]
        // Start every missing upload at once, then collect the IDs (they upload in parallel)
        for e in latest.values {
            var urls = e["mediaUrls"] as? [String] ?? []
            if urls.isEmpty, let u = e["mediaUrl"] as? String { urls = [u] }
            let video = (e["mediaType"] as? String) == MediaType.video.rawValue
            for u in urls { if let f = fileFor(u) { cloud.startUpload(localId: u, file: f, video: video) } }
        }
        for (key, e) in latest {
            var urls = e["mediaUrls"] as? [String] ?? []
            if urls.isEmpty, let u = e["mediaUrl"] as? String { urls = [u] }
            let video = (e["mediaType"] as? String) == MediaType.video.rawValue
            var ids: [String] = []
            for u in urls {
                guard let f = fileFor(u) else { continue }
                ids.append(try await cloud.ensureUploaded(localId: u, file: f, video: video))
                step()
            }
            var copy = e
            copy["cloudIds"] = ids
            out[key] = copy
        }
        return out
    }

    func createOrder(kitchen: Kitchen, platform: String, orderRef: String, packerName: String, foodType: String,
                     tempC: Double, clip: URL, clipRecordedAt: Int64, progress: @escaping (Double) -> Void) async throws -> OrderSummary {
        if let existing = try await findOrderCode(platform: platform, orderRef: orderRef) {
            throw DuplicateOrderError(existingCode: existing, platform: platform, orderRef: orderRef)
        }
        let code = newSealCode()
        let clipUrl = try copyIn(clip, ext: "mp4")
        var fresh = try await getKitchen(kitchen.id)
        let now = nowMs()
        let tempOk = HygieneScore.temperatureOk(foodType: foodType, tempC: tempC)
        let result = HygieneScore.compute(latest: fresh.latest, foodType: foodType, tempC: tempC, packerName: packerName,
                                          packingClipUrl: clipUrl, now: now, notStocked: fresh.notStocked)
        let score = result.percent
        let stats = await kitchenStats(fresh, now: now)
        var extras: [String: Any] = [:]
        if cloud.isConfigured {
            let total = 1 + fresh.latest.values.reduce(0) { $0 + max(1, ($1["mediaUrls"] as? [String])?.count ?? 1) }
            var done = 0
            let step = { done += 1; progress(Double(done) / Double(total)) }
            let clipId = try await cloud.ensureUploaded(localId: clipUrl, file: fileFor(clipUrl)!, video: true)
            step()
            fresh.latest = try await withCloudIds(fresh.latest, step: step)
            extras = ["cloudName": cloud.cloudName, "clipCloudId": clipId]
        }
        progress(1)
        var record = buildOrderRecord(kitchen: fresh, platform: platform, orderRef: orderRef, packerName: packerName,
                                      foodType: foodType, tempC: tempC, tempOk: tempOk, clipUrl: clipUrl, packedAt: now,
                                      score: score, clipRecordedAt: clipRecordedAt,
                                      scoreBreakdown: result.map, kitchenStats: stats.map)
        extras.forEach { record[$0.key] = $0.value }
        var k = kitchenMap
        k["orderStats"] = KitchenStats.append(fresh.orderStats, KitchenStats.encode(now, score, result.status), now: now)
        kitchenMap = k
        var orders = state["orders"] as? [String: Any] ?? [:]
        orders[code] = record
        state["orders"] = orders
        if !orderRef.trimmingCharacters(in: .whitespaces).isEmpty {
            var refs = state["orderRefs"] as? [String: Any] ?? [:]
            refs[orderRefKey(platform: platform, orderRef: orderRef)] = code
            state["orderRefs"] = refs
        }
        save()
        return OrderSummary.from(code: code, record)
    }

    /// For a seal made before cloud storage was connected: upload its media now so a fresh QR can be printed.
    func attachCloudMedia(code: String, progress: @escaping (Double) -> Void) async throws -> [String: Any] {
        guard cloud.isConfigured else { throw AppError("Connect cloud storage in Kitchen details first.") }
        guard var o = try await getOrder(code) else { throw AppError("Order not found on this phone.") }
        let snapshot = o["snapshot"] as? [String: [String: Any]] ?? [:]
        let total = 1 + snapshot.values.reduce(0) { $0 + max(1, ($1["mediaUrls"] as? [String])?.count ?? 1) }
        var done = 0
        let step = { done += 1; progress(Double(done) / Double(total)) }
        guard let clipUrl = o["clipUrl"] as? String, let clipFile = fileFor(clipUrl) else { throw AppError("Packing clip missing") }
        let clipId = try await cloud.ensureUploaded(localId: clipUrl, file: clipFile, video: true)
        step()
        o["snapshot"] = try await withCloudIds(snapshot, step: step)
        o["cloudName"] = cloud.cloudName
        o["clipCloudId"] = clipId
        var orders = state["orders"] as? [String: Any] ?? [:]
        orders[code] = o
        state["orders"] = orders
        save()
        return o
    }

    func recentOrders(kitchenId: String) async throws -> [OrderSummary] {
        let orders = state["orders"] as? [String: Any] ?? [:]
        return orders.compactMap { code, v in (v as? [String: Any]).map { OrderSummary.from(code: code, $0) } }
            .sorted { $0.packedAtMillis > $1.packedAtMillis }
            .prefix(50).map { $0 }
    }

    func findOrderCode(platform: String, orderRef: String) async throws -> String? {
        guard !orderRef.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        return (state["orderRefs"] as? [String: Any])?[orderRefKey(platform: platform, orderRef: orderRef)] as? String
    }

    func getOrder(_ code: String) async throws -> [String: Any]? { (state["orders"] as? [String: Any])?[code] as? [String: Any] }

    /// Demo mode has no server for customers to send feedback to.
    func recentFeedback(kitchenId: String, since: Int64) async throws -> [(Bool, Bool)] { [] }

    func publishKitchen(_ kitchen: Kitchen, stats: KitchenStats) async throws {
        state["publicKitchen"] = publicKitchenMap(kitchen, stats)
        save()
    }

    var publicKitchen: [String: Any]? { state["publicKitchen"] as? [String: Any] }

    func deleteAccount(password: String) async throws {
        try? FileManager.default.removeItem(at: dir)
        state = [:]
        cloud.forgetAll()
        _ = AppFiles.folder("demo")
        save()
    }
}
