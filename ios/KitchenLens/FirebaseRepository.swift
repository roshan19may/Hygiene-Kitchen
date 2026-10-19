import Foundation
import FirebaseCore
import FirebaseAuth
import FirebaseFirestore
import FirebaseStorage

/// Real cloud backend (same Firebase project, collections and rules as the Android app).
@MainActor
final class FirebaseRepository: KitchenRepository {
    let isDemo = false
    private var auth: Auth { Auth.auth() }
    private var db: Firestore { Firestore.firestore() }
    private var storage: Storage { Storage.storage() }

    func signedInUserId() -> String? { auth.currentUser?.uid }

    func signIn(email: String, password: String) async throws {
        _ = try await auth.signIn(withEmail: email.trimmingCharacters(in: .whitespaces), password: password)
    }

    func signUp(email: String, password: String) async throws {
        _ = try await auth.createUser(withEmail: email.trimmingCharacters(in: .whitespaces), password: password)
    }

    func signOut() { try? auth.signOut() }

    func loadKitchenId() async throws -> String? {
        guard let uid = auth.currentUser?.uid else { return nil }
        return try await db.collection("users").document(uid).getDocument().data()?["kitchenId"] as? String
    }

    func createKitchen(profile: [String: String]) async throws -> String {
        guard let user = auth.currentUser else { throw AppError("Not signed in") }
        let ref = db.collection("kitchens").document()
        var data: [String: Any] = ["ownerUid": user.uid, "latest": [String: Any](), "recentHashes": [String](),
                                   "createdAt": FieldValue.serverTimestamp()]
        profile.forEach { data[$0.key] = $0.value }
        let batch = db.batch()
        batch.setData(data, forDocument: ref)
        batch.setData(["kitchenId": ref.documentID, "email": user.email ?? ""], forDocument: db.collection("users").document(user.uid))
        try await batch.commit()
        return ref.documentID
    }

    func updateKitchenProfile(kitchenId: String, profile: [String: String]) async throws {
        try await db.collection("kitchens").document(kitchenId).updateData(profile)
    }

    func replaceCurrentRecords(kitchenId: String, latest: [String: [String: Any]]) async throws {
        try await db.collection("kitchens").document(kitchenId).updateData(["latest": latest])
    }

    func getKitchen(_ id: String) async throws -> Kitchen {
        let snap = try await db.collection("kitchens").document(id).getDocument()
        return Kitchen.from(id: id, snap.data() ?? [:])
    }

    // ---------- Fast uploads ----------
    // Uploads start in the background as soon as a photo or clip is captured (prefetch),
    // several files go at once, every photo gets a ~20 KB thumbnail, and files are cacheable.
    private var pending: [String: Task<(url: String, thumb: String), Error>] = [:]

    func prefetch(_ kitchen: Kitchen, file: URL, video: Bool) {
        guard pending[file.path] == nil else { return }
        let kid = kitchen.id
        pending[file.path] = Task { try await self.send(kid, file, video, progress: nil) }
    }

    /// Uses the background upload if one was started (and worked), otherwise uploads now.
    private func uploadMedia(_ kitchenId: String, _ file: URL, video: Bool,
                             progress: ((Double) -> Void)? = nil) async throws -> (url: String, thumb: String) {
        if let started = pending.removeValue(forKey: file.path), let done = try? await started.value {
            progress?(1)
            return done
        }
        return try await send(kitchenId, file, video, progress: progress)
    }

    private func send(_ kitchenId: String, _ file: URL, _ video: Bool, progress: ((Double) -> Void)?) async throws -> (url: String, thumb: String) {
        let base = "kitchens/\(kitchenId)/logs/\(UUID().uuidString)"
        if video { return (try await upload(file, path: base + ".mp4", contentType: "video/mp4", progress: progress), "") }
        async let thumb = uploadThumb(file, path: base + "_t.jpg")
        let main = try await upload(file, path: base + ".jpg", contentType: "image/jpeg", progress: progress)
        return (main, await thumb)
    }

    /// ~20 KB preview copy; an empty string if it could not be made (the full photo is then used).
    private func uploadThumb(_ file: URL, path: String) async -> String {
        guard let t = ImageTools.thumbnail(file) else { return "" }
        defer { try? FileManager.default.removeItem(at: t) }
        return (try? await upload(t, path: path, contentType: "image/jpeg")) ?? ""
    }

    private func upload(_ file: URL, path: String, contentType: String, progress: ((Double) -> Void)? = nil) async throws -> String {
        let ref = storage.reference().child(path)
        let meta = StorageMetadata()
        meta.contentType = contentType
        // Files never change once uploaded, so browsers may keep them for a year
        meta.cacheControl = "public, max-age=31536000, immutable"
        _ = try await ref.putFileAsync(from: file, metadata: meta) { p in
            if let p, p.totalUnitCount > 0 { progress?(Double(p.completedUnitCount) / Double(p.totalUnitCount)) }
        }
        return try await ref.downloadURL().absoluteString
    }

    func saveLog(kitchen: Kitchen, section: Section, key: String, staffName: String, media: URL, mediaType: MediaType,
                 details: [String: String], label: String, photoHash: String?, progress: @escaping (Double) -> Void) async throws {
        let id = UUID().uuidString
        let uploaded = try await uploadMedia(kitchen.id, media, video: mediaType == .video, progress: progress)
        let mediaUrl = uploaded.url
        let now = nowMs()
        let kitchenRef = db.collection("kitchens").document(kitchen.id)
        var updates: [String: Any] = [:]
        if section == .SPOT {
            // Spot checks count towards the kitchen rating; they don't replace a hygiene record
            updates["spotAnswers"] = Array((kitchen.spotAnswers + [now]).suffix(60))
        } else {
            var d = details
            d["recordedBy"] = recordedBy(staffName)
            updates["latest.\(key)"] = publicSummary(at: now, mediaUrl: mediaUrl, mediaType: mediaType, label: label, details: d,
                                                     mediaThumbs: [uploaded.thumb])
        }
        if let h = photoHash { updates["recentHashes"] = Array(([h] + kitchen.recentHashes).prefix(60)) }
        let batch = db.batch()
        batch.setData(["section": section.rawValue, "key": key, "staffName": staffName.trimmingCharacters(in: .whitespaces),
                       "mediaType": mediaType.rawValue, "mediaUrl": mediaUrl, "details": details, "label": label,
                       "capturedAtMillis": now, "createdAt": FieldValue.serverTimestamp()],
                      forDocument: kitchenRef.collection("logs").document(id))
        batch.updateData(updates, forDocument: kitchenRef)
        try await batch.commit()
    }

    func saveInventory(kitchen: Kitchen, staffName: String, supplierType: String?, photos: [String: [InventoryPhoto]],
                       bills: [InventoryBill], progress: @escaping (Double) -> Void) async throws {
        let total = Double(photos.values.reduce(0) { $0 + $1.count } + bills.count)
        var done = 0.0
        // Start every upload at once (those already prefetched are reused), then collect them in order
        for list in photos.values { for p in list { prefetch(kitchen, file: p.file, video: false) } }
        for b in bills { prefetch(kitchen, file: b.file, video: false) }
        var photoUrls: [String: [String]] = [:]
        var photoThumbs: [String: [String]] = [:]
        for (cat, list) in photos {
            var urls: [String] = [], thumbs: [String] = []
            for p in list {
                let up = try await uploadMedia(kitchen.id, p.file, video: false)
                urls.append(up.url); thumbs.append(up.thumb)
                done += 1; progress(done / max(1, total))
            }
            photoUrls[cat] = urls
            photoThumbs[cat] = thumbs
        }
        var billUrls: [String] = []
        for b in bills {
            billUrls.append(try await uploadMedia(kitchen.id, b.file, video: false).url)
            done += 1; progress(done / max(1, total))
        }
        let records = buildInventoryRecords(photoUrls: photoUrls, bills: bills, billUrls: billUrls, supplierType: supplierType)
        let now = nowMs()
        let kitchenRef = db.collection("kitchens").document(kitchen.id)
        var updates: [String: Any] = [:]
        let batch = db.batch()
        for r in records {
            var d = r.details
            d["recordedBy"] = recordedBy(staffName)
            updates["latest.\(r.key)"] = publicSummary(at: now, mediaUrl: r.mediaUrls[0], mediaType: .photo, label: r.label,
                                                      details: d, mediaUrls: r.mediaUrls, mediaThumbs: photoThumbs[r.category] ?? [])
            batch.setData(["section": "INVENTORY", "key": r.key, "staffName": staffName.trimmingCharacters(in: .whitespaces),
                           "mediaType": "photo", "mediaUrl": r.mediaUrls[0], "mediaUrls": r.mediaUrls, "details": r.details,
                           "label": r.label, "capturedAtMillis": now, "createdAt": FieldValue.serverTimestamp()],
                          forDocument: kitchenRef.collection("logs").document())
        }
        updates["recentHashes"] = Array((photos.values.flatMap { $0.map(\.hash) } + kitchen.recentHashes).prefix(60))
        batch.updateData(updates, forDocument: kitchenRef)
        try await batch.commit()
    }

    func createOrder(kitchen: Kitchen, platform: String, orderRef: String, packerName: String, foodType: String,
                     tempC: Double, clip: URL, clipRecordedAt: Int64, progress: @escaping (Double) -> Void) async throws -> OrderSummary {
        let refKey = orderRefKey(platform: platform, orderRef: orderRef)
        if let existing = try await findOrderCode(platform: platform, orderRef: orderRef) {
            throw DuplicateOrderError(existingCode: existing, platform: platform, orderRef: orderRef)
        }
        var code = newSealCode()
        for _ in 0..<3 {
            let taken = try await db.collection("orders").document(code).getDocument().exists
            if !taken { break }
            code = newSealCode()
        }

        // Usually already uploaded in the background while the packer filled in the form
        let clipUrl: String
        if let started = pending.removeValue(forKey: clip.path), let done = try? await started.value {
            clipUrl = done.url
            progress(1)
        } else {
            clipUrl = try await upload(clip, path: "kitchens/\(kitchen.id)/orders/\(code).mp4", contentType: "video/mp4", progress: progress)
        }
        let fresh = try await getKitchen(kitchen.id)
        let now = nowMs()
        let tempOk = HygieneScore.temperatureOk(foodType: foodType, tempC: tempC)
        let result = HygieneScore.compute(latest: fresh.latest, foodType: foodType, tempC: tempC, packerName: packerName,
                                          packingClipUrl: clipUrl, now: now, notStocked: fresh.notStocked)
        let score = result.percent
        let stats = await kitchenStats(fresh, now: now)
        var record = buildOrderRecord(kitchen: fresh, platform: platform, orderRef: orderRef, packerName: packerName,
                                      foodType: foodType, tempC: tempC, tempOk: tempOk, clipUrl: clipUrl, packedAt: now,
                                      score: score, clipRecordedAt: clipRecordedAt,
                                      scoreBreakdown: result.map, kitchenStats: stats.map)
        let orderStats = KitchenStats.append(fresh.orderStats, KitchenStats.encode(now, score, result.status), now: now)
        let batch = db.batch()
        record["createdAt"] = FieldValue.serverTimestamp()
        batch.setData(record, forDocument: db.collection("orders").document(code))
        if !orderRef.trimmingCharacters(in: .whitespaces).isEmpty {
            batch.setData(["code": code, "kitchenId": kitchen.id, "createdAt": FieldValue.serverTimestamp()],
                          forDocument: db.collection("orderRefs").document(refKey))
        }
        batch.setData(["section": "PACKING", "key": "PACKING", "staffName": packerName.trimmingCharacters(in: .whitespaces),
                       "mediaType": "video", "mediaUrl": clipUrl,
                       "details": ["orderRef": orderRef, "platform": platform, "code": code, "tempC": String(tempC), "foodType": foodType],
                       "label": "Order packed", "capturedAtMillis": now, "createdAt": FieldValue.serverTimestamp()],
                      forDocument: db.collection("kitchens").document(kitchen.id).collection("logs").document())
        // Rolling 30-day list used for the kitchen rating (no extra reads needed)
        batch.updateData(["orderStats": orderStats], forDocument: db.collection("kitchens").document(kitchen.id))
        try await batch.commit()
        record.removeValue(forKey: "createdAt")
        return OrderSummary.from(code: code, record)
    }

    func recentOrders(kitchenId: String) async throws -> [OrderSummary] {
        let snap = try await db.collection("orders").whereField("kitchenId", isEqualTo: kitchenId)
            .order(by: "packedAtMillis", descending: true).limit(to: 50).getDocuments()
        return snap.documents.map { OrderSummary.from(code: $0.documentID, $0.data()) }
    }

    func findOrderCode(platform: String, orderRef: String) async throws -> String? {
        guard !orderRef.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        let snap = try await db.collection("orderRefs").document(orderRefKey(platform: platform, orderRef: orderRef)).getDocument()
        return snap.exists ? snap.data()?["code"] as? String : nil
    }

    func recentFeedback(kitchenId: String, since: Int64) async throws -> [(Bool, Bool)] {
        let snap = try await db.collection("feedback").whereField("kitchenId", isEqualTo: kitchenId)
            .whereField("at", isGreaterThanOrEqualTo: since).limit(to: 1000).getDocuments()
        return snap.documents.map { (($0.data()["sealIntact"] as? Bool) ?? false, ($0.data()["foodHot"] as? Bool) ?? false) }
    }

    func publishKitchen(_ kitchen: Kitchen, stats: KitchenStats) async throws {
        // merge: never touches "verifiedVisit", which only KitchenLens staff can set
        try await db.collection("publicKitchens").document(kitchen.id).setData(publicKitchenMap(kitchen, stats), merge: true)
    }

    func getOrder(_ code: String) async throws -> [String: Any]? {
        try await db.collection("orders").document(code).getDocument().data()
    }

    func deleteAccount(password: String) async throws {
        guard let user = auth.currentUser, let email = user.email else { throw AppError("Not signed in") }
        // Confirm the password first, so a half-finished deletion cannot happen
        _ = try await user.reauthenticate(with: EmailAuthProvider.credential(withEmail: email, password: password))
        let uid = user.uid
        if let kid = try await db.collection("users").document(uid).getDocument().data()?["kitchenId"] as? String {
            let kitchenRef = db.collection("kitchens").document(kid)
            try await deleteAll(try await kitchenRef.collection("logs").getDocuments().documents.map(\.reference))
            let orders = try await db.collection("orders").whereField("kitchenId", isEqualTo: kid).getDocuments().documents
            let refs = orders.compactMap { ($0.data()["orderRefKey"] as? String).map { db.collection("orderRefs").document($0) } }
            try await deleteAll(refs + orders.map(\.reference))
            try await deleteAll(try await db.collection("feedback").whereField("kitchenId", isEqualTo: kid).getDocuments().documents.map(\.reference))
            try? await db.collection("publicKitchens").document(kid).delete()
            try await deleteFolder(storage.reference().child("kitchens/\(kid)"))
            try await kitchenRef.delete()
        }
        try await db.collection("users").document(uid).delete()
        try await user.delete()
    }

    private func deleteAll(_ refs: [DocumentReference]) async throws {
        var start = 0
        while start < refs.count {
            let batch = db.batch()
            for r in refs[start..<min(start + 400, refs.count)] { batch.deleteDocument(r) }
            try await batch.commit()
            start += 400
        }
    }

    private func deleteFolder(_ folder: StorageReference) async throws {
        let list = try await folder.listAll()
        for item in list.items { try? await item.delete() }
        for prefix in list.prefixes { try await deleteFolder(prefix) }
    }
}

/// True when a Firebase config (GoogleService-Info.plist) is bundled; otherwise the app runs in demo mode.
enum FirebaseSetup {
    static func configureIfPresent() -> Bool {
        guard let path = Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist"),
              let options = FirebaseOptions(contentsOfFile: path) else { return false }
        if FirebaseApp.app() == nil { FirebaseApp.configure(options: options) }
        return true
    }
}
