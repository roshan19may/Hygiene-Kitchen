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
        let stocked = INVENTORY_CATEGORIES.filter { !k.notStocked.contains($0) }
        let entries = stocked.compactMap { c in num(k.latest[latestKey(.INVENTORY, category: c)]?["at"]).map { (c, $0) } }
        guard let newest = entries.max(by: { $0.1 < $1.1 }) else { return Status(color: Brand.chilli, text: "Not recorded yet") }
        // Same expiry as the rating: perishables 48 h, dry goods 7 days
        let expired = entries.filter { c, at in
            now - at > (PERISHABLE_CATEGORIES.contains(c) ? HygieneScore.perishableFullH : HygieneScore.dryFullH) * hour
        }
        let color = expired.isEmpty && entries.count == stocked.count ? Brand.ok : (entries.count > expired.count ? Brand.amber : Brand.chilli)
        return Status(color: color, text: "\(newest.0) \(Dates.ago(newest.1, now: now)) · \(entries.count - expired.count) of \(stocked.count) categories current"
                      + (expired.isEmpty ? "" : " · expired: " + expired.map(\.0).joined(separator: ", ")))
    }
    guard let e = k.latest[s.rawValue], let at = num(e["at"]) else { return Status(color: Brand.chilli, text: "Not recorded yet") }
    if s == .PEST {
        let d = e["details"] as? [String: Any]
        let due = num(d?["nextDueMillis"])
        let color = due == nil ? Brand.amber : (now <= due! ? Brand.ok : Brand.chilli)
        return Status(color: color, text: "Last service \(Dates.ago(at, now: now)) · next due \(d?["nextDueDate"] as? String ?? "-")")
    }
    let age = now - at
    let full = HygieneScore.shiftFullH * hour, half = HygieneScore.shiftHalfH * hour
    let color = age <= full ? Brand.ok : (age <= half ? Brand.amber : Brand.chilli)
    let note = age <= full ? "" : (age <= half ? " · half points now, record again this shift" : " · expired, earns 0 points: record again")
    return Status(color: color, text: "Updated \(Dates.ago(at, now: now))\(note)")
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
    @State private var tick = Date()
    private let timer = Timer.publish(every: 30, on: .main, in: .common).autoconnect()

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
        .onReceive(timer) { tick = $0 }   // shows and hides the spot-check card on time
    }

    @ViewBuilder
    private func content(_ k: Kitchen, proxy: ScrollViewProxy) -> some View {
        let now = max(nowMs(), Int64(tick.timeIntervalSince1970 * 1000))   // reading tick refreshes this every 30 s
        let readiness = HygieneScore.readiness(latest: k.latest, now: now, notStocked: k.notStocked)
        let window = SpotCheck.current(kitchenId: k.id, now: now)
        let spot: (start: Int64, end: Int64)? = window.map { SpotCheck.answered(k.spotAnswers, $0) } == false ? window : nil
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
            if let w = spot {
                Button { path.append(.section(.SPOT)) } label: {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Spot check now").font(.headline)
                        Text("Record a 10-second clip of your wash area and packing table. \(max(1, (w.end - now) / 60_000)) min left. Answered spot checks raise your kitchen rating.")
                            .font(.footnote).multilineTextAlignment(.leading)
                        Text("Record spot check").font(.subheadline.bold())
                    }
                    .foregroundColor(Color(red: 0.23, green: 0.16, blue: 0))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(14).background(Brand.turmeric).clipShape(RoundedRectangle(cornerRadius: 14))
                }
                .buttonStyle(.plain)
            }
            if k.gstin.isEmpty {
                Button { path.append(.kitchen) } label: {
                    Text("Tip: add your GST number in Kitchen details so customers see it on every seal.").font(.footnote)
                }
            }

            Card(tint: Brand.leaf) {
                Text("Next order's rating: \(String(format: "%.1f", readiness.stars)) \(HygieneScore.starText(readiness.stars)) \(readiness.rating) (\(readiness.percent)%)")
                    .font(.headline).foregroundColor(.white)
                Text("What customers will see on the seal if the food is at 65°C or above (FSSAI guideline) and you record the packing clip. Old records lose points: shift records after 12 h, perishables after 48 h.")
                    .font(.caption).foregroundColor(.white.opacity(0.85))
                let tips = readiness.tips()
                if !tips.isEmpty {
                    Text("To gain points:\n" + tips.map { "• " + $0 }.joined(separator: "\n")).font(.caption).foregroundColor(.white)
                }
                if let st = model.kitchenStats {
                    Button { path.append(.kitchen) } label: {
                        Text("Kitchen rating (30 days): " + st.summary() + (st.spotRequested > 0 ? " · spot checks \(st.spotAnswered)/\(st.spotRequested)" : ""))
                            .font(.caption).foregroundColor(.white).multilineTextAlignment(.leading)
                    }
                }
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
