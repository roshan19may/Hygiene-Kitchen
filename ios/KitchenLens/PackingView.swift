import SwiftUI

/// Per order: platform + order number → temperature → Hygiene Food Packing clip → Order QR seal.
/// Progress is kept as a draft: "Save draft", automatic save on leaving or recording, "Reset" clears it.
struct PackingView: View {
    @EnvironmentObject var model: AppModel
    @Binding var path: [Route]

    @State private var platform: Set<String> = []
    @State private var orderRef = ""
    @State private var packer = ""
    @State private var foodType: Set<String> = ["Hot"]
    @State private var tempText = ""
    @State private var clip: URL?
    @State private var clipSeconds = 0
    @State private var clipRecordedAt: Int64 = 0
    @State private var savedAt: Int64 = 0
    @State private var loaded = false
    @State private var recording = false
    @State private var working = false
    @State private var progress = 0.0
    @State private var error: String?
    @State private var existingCode: String?

    private var temp: Double? { Double(tempText.replacingOccurrences(of: ",", with: ".")) }
    private var needsOrderNo: Bool { platform.first != DIRECT_PLATFORM }

    private func currentDraft() -> OrderDraft {
        var d = model.draft ?? OrderDraft()
        d.platform = platform.first ?? ""
        d.orderRef = orderRef
        d.packer = packer
        d.foodType = foodType.first ?? "Hot"
        d.tempText = tempText
        d.clipSeconds = clipSeconds
        d.clipRecordedAt = clipRecordedAt
        if clip == nil { d.clipFile = nil }
        return d
    }

    @discardableResult
    private func saveDraft(newClip: URL? = nil) -> Bool {
        let d = currentDraft()
        if d.isEmpty && newClip == nil { return false }
        let saved = model.saveDraft(d, newClip: newClip)
        if let u = model.drafts.clipURL(saved) { clip = u }
        savedAt = saved.savedAt
        return true
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        let d = model.draft ?? OrderDraft()
        platform = d.platform.isEmpty ? [] : [d.platform]
        orderRef = d.orderRef
        packer = d.packer.isEmpty ? model.lastPackerName : d.packer
        foodType = [d.foodType.isEmpty ? "Hot" : d.foodType]
        tempText = d.tempText
        clip = model.drafts.clipURL(d)
        clipSeconds = d.clipSeconds
        clipRecordedAt = d.clipRecordedAt
        savedAt = d.savedAt
    }

    /// "Reset": clears this order form and its saved draft immediately.
    private func resetForm() {
        model.clearDraft()
        platform = []; orderRef = ""; foodType = ["Hot"]; tempText = ""
        clip = nil; clipSeconds = 0; clipRecordedAt = 0; savedAt = 0
        packer = model.lastPackerName
        error = nil; existingCode = nil
    }

    private func handle(_ result: CaptureResult?) {
        guard case .video(let raw)? = result else { return }
        working = true
        Task {
            defer { working = false }
            do {
                let ended = nowMs()
                let (url, secs) = try await VideoTools.removeAudio(raw)
                if secs < 5 { error = "Too short. Record at least 5 seconds."; return }
                clipSeconds = secs
                clipRecordedAt = ended - Int64(secs) * 1000
                saveDraft(newClip: url)   // never lose a recorded clip
                error = nil
            } catch { self.error = error.localizedDescription }
        }
    }

    private func create() {
        existingCode = nil
        if platform.isEmpty { error = "Choose the order platform"; return }
        if needsOrderNo && orderRef.trimmingCharacters(in: .whitespaces).isEmpty { error = "Enter the \(platform.first ?? "") order number"; return }
        if packer.trimmingCharacters(in: .whitespaces).isEmpty { error = "Enter the packer's name"; return }
        guard let t = temp else { error = "Enter the temperature reading"; return }
        if t < -30 || t > 120 { error = "That temperature looks wrong. Check the probe."; return }
        guard let c = clip, FileManager.default.fileExists(atPath: c.path) else { error = "Record the Hygiene Food Packing clip"; return }
        guard let k = model.kitchen else { return }
        error = nil
        saveDraft()
        working = true; progress = 0
        model.lastPackerName = packer.trimmingCharacters(in: .whitespaces)
        Task {
            do {
                let order = try await model.repo.createOrder(
                    kitchen: k, platform: platform.first ?? "", orderRef: orderRef.trimmingCharacters(in: .whitespaces),
                    packerName: packer, foodType: foodType.first ?? "Hot", tempC: t, clip: clip ?? c,
                    clipRecordedAt: clipRecordedAt > 0 ? clipRecordedAt : nowMs()) { p in progress = p }
                model.clearDraft()
                loaded = false          // don't re-save the finished order as a draft on leaving
                path.removeLast()
                path.append(.qr(order.code))
            } catch let dup as DuplicateOrderError {
                error = dup.localizedDescription
                existingCode = dup.existingCode
            } catch {
                self.error = "Upload failed: \(error.localizedDescription). Your draft is saved; check internet and try again."
            }
            working = false
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Card(tint: Color(.secondarySystemBackground)) {
                    Text(savedAt > 0 ? "Draft saved \(Dates.ago(savedAt)). You can leave and continue later from Home."
                         : "Fill in the order below. Use \"Save draft\" to keep progress and continue later.")
                        .font(.caption)
                    Button { loaded = false; model.startNewOrder(); path.removeAll() } label: {
                        Label("New order: clear everything", systemImage: "arrow.counterclockwise").font(.headline).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent).tint(Brand.chilli).disabled(working)
                    Text("Clears this order and today's hygiene records so you start from the beginning. \"Reset\" (top right) clears only this form. Seals already printed are not affected.")
                        .font(.caption)
                }

                ChipPicker(label: "Order platform", options: PLATFORMS, selected: $platform)
                VStack(alignment: .leading, spacing: 4) {
                    TextField(needsOrderNo ? "Platform order number (required)" : "Order number (optional)", text: $orderRef)
                        .textFieldStyle(.roundedBorder)
                    Text("The QR seal is linked to this number. Customers can also find their order by it.").font(.caption).foregroundColor(.secondary)
                }
                TextField("Packed by (first name is shown)", text: $packer).textFieldStyle(.roundedBorder)

                Card {
                    Text("Food temperature at packing").font(.subheadline.weight(.semibold))
                    ChipPicker(label: "Food type", options: ["Hot", "Cold"], selected: $foodType)
                    TextField("Temperature shown on the probe (°C)", text: $tempText).keyboardType(.decimalPad).textFieldStyle(.roundedBorder)
                    Text(foodType.first == "Cold" ? "Enter the reading shown. Guideline: cold food at 5°C or below."
                         : "Enter the reading shown. FSSAI guideline: hot food at 65°C or above.")
                        .font(.caption).foregroundColor(.secondary)
                    if let t = temp {
                        let ok = HygieneScore.temperatureOk(foodType: foodType.first ?? "Hot", tempC: t)
                        Label(ok ? "Meets the guideline"
                              : (foodType.first == "Cold"
                                 ? "Warning: above the 5°C guideline. Chill if you can. You can still continue; customers see the reading you enter."
                                 : "Warning: below the 65°C FSSAI guideline. Reheat if you can. You can still continue; customers see the reading you enter."),
                              systemImage: ok ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                            .font(.footnote).foregroundColor(ok ? Brand.ok : Brand.chilli)
                    }
                }

                Card {
                    Text("Hygiene Food Packing clip (5–20 s, no sound)").font(.subheadline.weight(.semibold))
                    if clip != nil {
                        Label("Clip recorded (\(clipSeconds) s) and saved in the draft", systemImage: "checkmark.circle.fill").foregroundColor(Brand.ok)
                    }
                    Button { recording = true } label: {
                        Label(clip == nil ? "Record Hygiene Food Packing clip" : "Re-record", systemImage: "video").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered).disabled(working)
                }

                ErrorText(text: error)
                if let dup = existingCode {
                    Button("Open the existing seal for this order") { resetForm(); path.removeLast(); path.append(.qr(dup)) }
                        .buttonStyle(.bordered)
                }
                if working { ProgressView(value: progress) }
                Button { error = saveDraft() ? nil : "Nothing to save yet. Fill in at least one field." } label: {
                    Label("Save draft", systemImage: "square.and.arrow.down").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered).disabled(working)
                Button { create() } label: {
                    Text(working ? "Uploading clip…" : "Create Order QR seal").font(.headline).frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent).disabled(working)
            }
            .padding(16)
        }
        .navigationTitle("Pack an order")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ResetButton { resetForm() } }
        .onAppear { load() }
        .onDisappear { if !working && loaded { saveDraft() } }
        .camera(isPresented: $recording, mode: .video,
                hint: "Show the sealed containers going into the bag and the bag being closed. Keep spices and recipe cards out of frame.") { handle($0) }
    }
}
