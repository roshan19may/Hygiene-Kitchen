import Foundation
import SwiftUI

/// Settings read from Info.plist (set by the GitHub workflow).
enum AppConfig {
    private static func value(_ key: String, _ fallback: String) -> String {
        (Bundle.main.object(forInfoDictionaryKey: key) as? String).flatMap { $0.isEmpty ? nil : $0 } ?? fallback
    }
    static var scanBaseURL: String { value("KLScanBaseURL", "https://YOUR-PROJECT-ID.web.app/o/") }
    static var webBaseURL: String { value("KLWebBaseURL", "https://YOUR-PROJECT-ID.web.app/") }
    static var supportEmail: String { value("KLSupportEmail", "support@example.com") }
    static func scanURL(_ code: String) -> String { scanBaseURL + code }
}

/// Saves the order in progress (and its clip) on the phone until the QR seal is created.
final class DraftStore {
    private var dir: URL { AppFiles.folder("draft") }
    private var file: URL { dir.appendingPathComponent("order.json") }

    func clipURL(_ d: OrderDraft) -> URL? {
        guard let name = d.clipFile else { return nil }
        let u = dir.appendingPathComponent(name)
        return FileManager.default.fileExists(atPath: u.path) ? u : nil
    }

    func load() -> OrderDraft? {
        guard let data = try? Data(contentsOf: file), var d = try? JSONDecoder().decode(OrderDraft.self, from: data) else { return nil }
        if clipURL(d) == nil { d.clipFile = nil }
        return d
    }

    /// Saves the draft; a newly recorded clip (outside the draft folder) is moved in.
    func save(_ draft: OrderDraft, newClip: URL?) -> OrderDraft {
        var d = draft
        if let src = newClip, src.deletingLastPathComponent().standardizedFileURL != dir.standardizedFileURL {
            let name = "clip_\(nowMs()).mp4"
            // Move (instant) rather than copy a multi-MB clip; copy only if moving fails
            let dest = dir.appendingPathComponent(name)
            if (try? FileManager.default.moveItem(at: src, to: dest)) == nil { try? FileManager.default.copyItem(at: src, to: dest) }
            d.clipFile = name
        }
        // Remove clips that no longer belong to the draft
        if let files = try? FileManager.default.contentsOfDirectory(atPath: dir.path) {
            for f in files where f.hasSuffix(".mp4") && f != d.clipFile { try? FileManager.default.removeItem(at: dir.appendingPathComponent(f)) }
        }
        d.savedAt = nowMs()
        if let data = try? JSONEncoder().encode(d) { try? data.write(to: file, options: .atomic) }
        return d
    }

    func clear() { try? FileManager.default.removeItem(at: dir) }
    /// Forgets the draft but keeps its clip, so "Undo" can bring it back.
    func clearKeepingClip() { try? FileManager.default.removeItem(at: file) }
}

@MainActor
final class AppModel: ObservableObject {
    enum AuthState: Equatable { case loading, loggedOut, needsSetup, ready(String), failed(String) }

    let repo: KitchenRepository
    let drafts = DraftStore()

    @Published var auth: AuthState = .loading
    @Published var kitchen: Kitchen? { didSet { if let k = kitchen { updateStats(k) } else { kitchenStats = nil } } }
    /// The kitchen's 30-day hygiene rating (kitchen page, badge, Home screen).
    @Published var kitchenStats: KitchenStats?
    @Published var draft: OrderDraft?
    @Published var message: String?
    @Published var lastReset: (kitchenId: String, draft: OrderDraft?, latest: [String: [String: Any]])?
    var lastPackerName = ""

    init() {
        repo = FirebaseSetup.configureIfPresent() ? FirebaseRepository() : LocalRepository()
        draft = drafts.load()
        Task { await refresh() }
    }

    var local: LocalRepository? { repo as? LocalRepository }

    private var feedback: [(Bool, Bool)] = []
    private var feedbackLoadedAt: Int64 = 0
    private var lastPublished = ""

    /// Recomputes the 30-day rating when the kitchen changes and publishes the public kitchen
    /// page when something customers see has changed. Feedback is re-read at most every 30 min.
    private func updateStats(_ k: Kitchen) {
        Task {
            let now = nowMs()
            if now - feedbackLoadedAt > 30 * 60_000 {
                if let f = try? await repo.recentFeedback(kitchenId: k.id, since: now - KitchenStats.windowDays * 24 * 3_600_000) { feedback = f }
                feedbackLoadedAt = now
            }
            let stats = KitchenStats.compute(kitchenId: k.id, orderStats: k.orderStats, spotAnswers: k.spotAnswers, feedback: feedback, now: now)
            kitchenStats = stats
            var visible = publicKitchenMap(k, stats)
            visible["updatedAt"] = nil
            var sm = stats.map
            sm["updatedAt"] = nil
            visible["stats"] = sm
            let key = (try? JSONSerialization.data(withJSONObject: visible, options: [.sortedKeys])).flatMap { String(data: $0, encoding: .utf8) } ?? ""
            if key != lastPublished {
                do { try await repo.publishKitchen(k, stats: stats); lastPublished = key } catch {}
            }
        }
    }

    func refresh() async {
        auth = .loading
        guard repo.signedInUserId() != nil else { auth = .loggedOut; return }
        do {
            if let kid = try await repo.loadKitchenId() {
                kitchen = try await repo.getKitchen(kid)
                auth = .ready(kid)
            } else {
                auth = .needsSetup
            }
        } catch {
            auth = .failed(error.localizedDescription)
        }
    }

    func reloadKitchen() async {
        guard case .ready(let kid) = auth else { return }
        if let k = try? await repo.getKitchen(kid) { kitchen = k }
    }

    func signOut() {
        repo.signOut()
        kitchen = nil
        auth = .loggedOut
    }

    @discardableResult
    func saveDraft(_ d: OrderDraft, newClip: URL? = nil) -> OrderDraft {
        if d.isEmpty && newClip == nil { clearDraft(); return d }
        let saved = drafts.save(d, newClip: newClip)
        draft = saved
        return saved
    }

    func clearDraft() { drafts.clear(); draft = nil }

    /// "New order": clears the order form and the current hygiene records so the
    /// next order starts from the beginning. Seals already created keep their records.
    func startNewOrder() {
        guard let k = kitchen else { return }
        lastReset = (k.id, draft, k.latest)
        drafts.clearKeepingClip()
        draft = nil
        Task {
            do {
                try await repo.replaceCurrentRecords(kitchenId: k.id, latest: [:])
                await reloadKitchen()
            } catch { message = "Could not clear the hygiene records: \(error.localizedDescription)" }
        }
    }

    func undoNewOrder() {
        guard let u = lastReset else { return }
        lastReset = nil
        if let d = u.draft { saveDraft(d) }
        Task {
            do {
                try await repo.replaceCurrentRecords(kitchenId: u.kitchenId, latest: u.latest)
                await reloadKitchen()
            } catch { message = "Could not restore the hygiene records: \(error.localizedDescription)" }
        }
    }

    func deleteAccount(password: String) async throws {
        try await repo.deleteAccount(password: password)
        drafts.clear()
        draft = nil
        lastReset = nil
        kitchen = nil
        lastPublished = ""
        repo.signOut()
        auth = .loggedOut
    }
}

@main
struct KitchenLensApp: App {
    @StateObject private var model = AppModel()
    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .tint(Brand.leaf)
        }
    }
}

struct RootView: View {
    @EnvironmentObject var model: AppModel
    var body: some View {
        switch model.auth {
        case .loading: ProgressView()
        case .loggedOut: LoginView()
        case .needsSetup: SetupKitchenView()
        case .failed(let msg):
            VStack(spacing: 12) {
                Text(msg).multilineTextAlignment(.center)
                Button("Try again") { Task { await model.refresh() } }
                Button("Sign out") { model.signOut() }
            }.padding()
        case .ready: HomeView()
        }
    }
}
