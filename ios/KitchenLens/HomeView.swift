import SwiftUI

enum Route: Hashable {
    case section(Section)
    case inventory
    case pack
    case qr(String)
    case orders
    case kitchen
}

private struct Status { let color: Color; let text: String }

private func status(_ k: Kitchen, _ s: Section, now: Int64) -> Status {
    let hour: Int64 = 3_600_000
    if s == .INVENTORY {
        let entries = INVENTORY_CATEGORIES.compactMap { c in num(k.latest[latestKey(.INVENTORY, category: c)]?["at"]).map { (c, $0) } }
        guard let newest = entries.max(by: { $0.1 < $1.1 }) else { return Status(color: Brand.chilli, text: "Not recorded yet") }
        return Status(color: now - newest.1 <= s.freshForHours * hour ? Brand.ok : Brand.amber,
                      text: "\(newest.0) \(Dates.ago(newest.1, now: now)) · \(entries.count) categories logged")
    }
    guard let e = k.latest[s.rawValue], let at = num(e["at"]) else { return Status(color: Brand.chilli, text: "Not recorded yet") }
    if s == .PEST {
        let d = e["details"] as? [String: Any]
        let due = num(d?["nextDueMillis"])
        let color = due == nil ? Brand.amber : (now <= due! ? Brand.ok : Brand.chilli)
        return Status(color: color, text: "Last service \(Dates.ago(at, now: now)) · next due \(d?["nextDueDate"] as? String ?? "-")")
    }
    let age = now - at
    let color = age <= s.freshForHours * hour ? Brand.ok : (age <= s.freshForHours * 2 * hour ? Brand.amber : Brand.chilli)
    return Status(color: color, text: "Updated \(Dates.ago(at, now: now))")
}

/// Kitchen Hygiene proof sections with nothing recorded yet.
private func missingSections(_ k: Kitchen) -> [Section] {
    var out: [Section] = []
    if !k.latest.keys.contains(where: { $0.hasPrefix("INV_") }) { out.append(.INVENTORY) }
    for s in [Section.UTENSILS, .STAFF, .PEST] where k.latest[s.rawValue] == nil { out.append(s) }
    return out
}

struct HomeView: View {
    @EnvironmentObject var model: AppModel
    @State private var path: [Route] = []
    @State private var missing: [Section]?

    private func packWithCheck() {
        guard let k = model.kitchen else { return }
        let m = missingSections(k)
        if m.isEmpty { path.append(.pack) } else { missing = m }
    }

    var body: some View {
        NavigationStack(path: $path) {
            ScrollViewReader { proxy in
                ScrollView {
                    if let k = model.kitchen { content(k, proxy: proxy) } else { ProgressView().padding(40) }
                }
                .refreshable { await model.reloadKitchen() }
                .alert("Kitchen Hygiene proof not entered", isPresented: Binding(get: { missing != nil }, set: { if !$0 { missing = nil } })) {
                    Button("Go back", role: .cancel) {
                        missing = nil
                        withAnimation { proxy.scrollTo("proof", anchor: .top) }
                    }
                    Button("Ignore") { missing = nil; path.append(.pack) }
                } message: {
                    Text("These details are not entered yet:\n" + (missing ?? []).map { "• " + $0.title }.joined(separator: "\n")
                         + "\n\nPacking without them may affect future regular orders, because customers will not see this proof when they scan the seal.")
                }
            }
            .navigationTitle(model.kitchen?.name ?? "KitchenLens")
            .toolbar {
                ToolbarItemGroup(placement: .navigationBarTrailing) {
                    Button { path.append(.kitchen) } label: { Image(systemName: "storefront") }.accessibilityLabel("Kitchen details")
                    Button { path.append(.orders) } label: { Image(systemName: "list.bullet.rectangle") }.accessibilityLabel("Recent orders")
                    Menu {
                        Button("Sign out", role: .destructive) { model.signOut() }
                    } label: { Image(systemName: "person.crop.circle") }
                }
            }
            .navigationDestination(for: Route.self) { route in
                switch route {
                case .section(let s): SectionView(section: s)
                case .inventory: InventoryView()
                case .pack: PackingView(path: $path)
                case .qr(let code): QrResultView(code: code, path: $path)
                case .orders: OrdersView(path: $path)
                case .kitchen: EditKitchenView()
                }
            }
        }
        .onChange(of: path) { p in if p.isEmpty { Task { await model.reloadKitchen() } } }
    }

    @ViewBuilder
    private func content(_ k: Kitchen, proxy: ScrollViewProxy) -> some View {
        let now = nowMs()
        let readiness = HygieneScore.compute(latest: k.latest, tempOk: true, now: now)
        VStack(alignment: .leading, spacing: 12) {
            Text("\(k.locality), \(k.city)").font(.subheadline).foregroundColor(.secondary)
            if model.repo.isDemo { DemoBanner() }
            if let m = model.message {
                Card(tint: Brand.chilli.opacity(0.12)) {
                    Text(m).font(.footnote)
                    Button("OK") { model.message = nil }
                }
            }
            if model.lastReset != nil {
                Card(tint: Brand.leafLight) {
                    Text("New order started").font(.headline)
                    Text("The order form and today's hygiene records were cleared. Record the hygiene sections below, then pack the order. Seals already printed are not affected.")
                        .font(.footnote)
                    HStack {
                        Button("Undo") { model.undoNewOrder() }
                        Button("OK") { model.lastReset = nil }
                    }
                }
            }
            if k.gstin.isEmpty {
                Button { path.append(.kitchen) } label: {
                    Text("Tip: add your GST number in Kitchen details so customers see it on every seal.").font(.footnote)
                }
            }

            Card(tint: Brand.leaf) {
                Text("Ready-to-pack score: \(readiness) / 100").font(.headline).foregroundColor(.white)
                Text("Customers see this score on every order (temperature at 65°C or above, per FSSAI guideline, adds the last 20 points).")
                    .font(.caption).foregroundColor(.white.opacity(0.85))
                if let d = model.draft {
                    Text("Order in progress: \(d.platform.isEmpty ? "platform not chosen" : d.platform)\(d.orderRef.isEmpty ? "" : " #" + d.orderRef) · saved \(Dates.ago(d.savedAt))")
                        .font(.subheadline).foregroundColor(.white)
                }
                Button { packWithCheck() } label: {
                    Label(model.draft != nil ? "Continue this order" : "Pack an order + QR seal", systemImage: "qrcode")
                        .font(.headline).frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent).tint(Brand.turmeric).foregroundColor(Color(red: 0.23, green: 0.16, blue: 0))
                Button { model.startNewOrder() } label: {
                    Label("New order: clear everything", systemImage: "arrow.counterclockwise").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered).tint(.white)
            }

            Text("Kitchen Hygiene proof").font(.title3.bold()).id("proof")
            ForEach(Section.proof) { s in
                let st = status(k, s, now: now)
                Button { path.append(s == .INVENTORY ? .inventory : .section(s)) } label: {
                    HStack(spacing: 12) {
                        Circle().fill(st.color).frame(width: 12, height: 12)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(s.title).font(.body.weight(.semibold)).foregroundColor(.primary)
                            Text(st.text).font(.caption).foregroundColor(.secondary)
                        }
                        Spacer()
                        Text("Record").font(.subheadline.bold()).foregroundColor(Brand.leaf)
                    }
                    .padding(14)
                    .background(Color(.secondarySystemGroupedBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                }
                .buttonStyle(.plain)
            }
            Button { path.append(.orders) } label: { Text("Recent orders and QR seals").frame(maxWidth: .infinity) }
                .buttonStyle(.bordered)
        }
        .padding(16)
    }
}
