import SwiftUI

private struct ShareItem: Identifiable { let id = UUID(); let items: [Any] }
private struct WebItem: Identifiable { let id = UUID(); let url: URL }

/// The finished Order QR seal: label preview, print/share, customer preview.
struct QrResultView: View {
    @EnvironmentObject var model: AppModel
    let code: String
    @Binding var path: [Route]

    @State private var order: [String: Any]?
    @State private var loaded = false
    @State private var error: String?
    @State private var uploading = false
    @State private var uploadProgress = 0.0
    @State private var share: ShareItem?
    @State private var web: WebItem?

    /// Demo mode keeps media on the phone, so the order details travel inside the QR link itself.
    private var url: String {
        if model.repo.isDemo, let o = order { return OfflineSeal.url(base: AppConfig.scanBaseURL, code: code, order: o) }
        return AppConfig.scanURL(code)
    }

    private var tempText: String {
        guard let t = dbl(order?["tempC"]) else { return "" }
        return String(format: "Packed at %.0f°C", t)
    }

    private var orderLine: String? {
        guard let o = order else { return nil }
        let full = (o["orderRef"] as? String) ?? ""
        let ref = full.isEmpty ? ((o["orderRefLast4"] as? String).map { "··" + $0 } ?? "") : full
        return "\(o["platform"] as? String ?? "") order #\(ref)"
    }

    private func label() -> UIImage {
        QrLabel.label(url: url, code: code, kitchenName: order?["kitchenName"] as? String ?? "", tempText: tempText)
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                if model.repo.isDemo { DemoBanner() }
                ErrorText(text: error)
                if loaded {
                    Image(uiImage: label())
                        .resizable().interpolation(.none).scaledToFit()
                        .frame(width: 280).padding(8).background(Color.white)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                        .accessibilityLabel("QR seal label")
                } else { ProgressView().padding(40) }
                if let line = orderLine { Text(line).font(.headline) }
                Text(AppConfig.scanURL(code)).font(.caption.monospaced()).foregroundColor(.secondary)

                if model.repo.isDemo, loaded {
                    let hasMedia = order?["clipCloudId"] != nil
                    Text(hasMedia ? "This QR carries the order details and links to its photos and clips in your cloud storage."
                         : "This QR carries the order details. Photos and clips will NOT show when scanned until you connect free cloud storage in Kitchen details (shop icon on Home), then create the seal again.")
                        .font(.footnote).foregroundColor(hasMedia ? Brand.ok : Brand.chilli)
                    if !hasMedia, let local = model.local {
                        if local.cloud.isConfigured {
                            Button {
                                uploading = true; error = nil
                                Task {
                                    do { order = try await local.attachCloudMedia(code: code) { p in uploadProgress = p } }
                                    catch { self.error = error.localizedDescription }
                                    uploading = false
                                }
                            } label: {
                                Text(uploading ? "Uploading photos and clips…" : "Upload photos and clips, then refresh this QR").frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.bordered).disabled(uploading)
                            if uploading { ProgressView(value: uploadProgress) }
                        } else {
                            Button { path.append(.kitchen) } label: { Text("Set up free cloud storage").frame(maxWidth: .infinity) }
                                .buttonStyle(.bordered)
                        }
                    }
                }

                Button { share = ShareItem(items: [label()]) } label: {
                    Label("Print / share seal", systemImage: "printer").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent).disabled(!loaded)
                Button { if let u = URL(string: url) { web = WebItem(url: u) } } label: {
                    Label("Preview what the customer sees", systemImage: "eye").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered).disabled(!loaded)
                Button { model.startNewOrder(); path.removeAll() } label: {
                    Text("New order: clear everything").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent).tint(Brand.chilli)
                Button("Back to home") { path.removeAll() }
                Text("Stick the seal across the bag opening so it tears if the bag is opened.").font(.caption)
            }
            .padding(16)
        }
        .navigationTitle("QR seal ready")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard !loaded else { return }
            do { order = try await model.repo.getOrder(code) } catch { self.error = error.localizedDescription }
            loaded = true
        }
        .sheet(item: $share) { ShareSheet(items: $0.items) }
        .sheet(item: $web) { SafariView(url: $0.url).ignoresSafeArea() }
    }
}

/// Recent orders, searchable by platform order number or seal code.
struct OrdersView: View {
    @EnvironmentObject var model: AppModel
    @Binding var path: [Route]
    @State private var orders: [OrderSummary]?
    @State private var error: String?
    @State private var query = ""

    private var filtered: [OrderSummary] {
        let q = query.filter { $0.isLetter || $0.isNumber }.uppercased()
        return (orders ?? []).filter { o in
            q.isEmpty || o.orderRef.uppercased().filter { $0.isLetter || $0.isNumber }.contains(q) || o.code.uppercased().contains(q)
        }
    }

    var body: some View {
        List {
            TextField("Search by order number or seal code", text: $query)
                .autocorrectionDisabled().textInputAutocapitalization(.characters)
            if let e = error { ErrorText(text: e) }
            if orders == nil {
                ProgressView()
            } else if filtered.isEmpty {
                Text(query.isEmpty ? "No orders packed yet." : "No orders match \"\(query)\".").foregroundColor(.secondary)
            } else {
                ForEach(filtered) { o in
                    Button { path.append(.qr(o.code)) } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("\(o.platform) #\(o.orderRef.isEmpty ? "··" + o.orderRefLast4 : o.orderRef)")
                                    .font(.body.weight(.semibold)).foregroundColor(.primary)
                                Text("\(Dates.dateTime(o.packedAtMillis)) · \(String(format: "%.0f", o.tempC))°C · code \(o.code)")
                                    .font(.caption).foregroundColor(.secondary)
                            }
                            Spacer()
                            Text("\(o.score)").font(.subheadline.bold()).foregroundColor(.white)
                                .padding(.horizontal, 10).padding(.vertical, 4)
                                .background(o.score >= 80 ? Brand.ok : (o.score >= 50 ? Brand.amber : Brand.chilli))
                                .clipShape(RoundedRectangle(cornerRadius: 8))
                        }
                    }
                }
            }
        }
        .navigationTitle("Recent orders")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ResetButton { query = "" } }
        .task {
            guard orders == nil, let k = model.kitchen else { return }
            do { orders = try await model.repo.recentOrders(kitchenId: k.id) }
            catch { self.error = error.localizedDescription; orders = [] }
        }
    }
}
