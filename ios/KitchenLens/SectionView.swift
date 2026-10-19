import SwiftUI

private let hints: [Section: String] = [
    .UTENSILS: "Record the wash: scrubbing, rinsing in hot water or sanitiser, and the drying rack.",
    .STAFF: "Record each staff member washing hands with soap, then show cap, gloves and apron.",
    .PEST: "Photograph the pest-control service report showing the vendor and date.",
]

/// Cooking utensils, staff hygiene and pest control (inventory has its own screen).
struct SectionView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let section: Section

    @State private var staffName = ""
    @State private var media: Set<String> = []
    @State private var checklist: Set<String> = []
    @State private var shift: Set<String> = []
    @State private var staffCount = ""
    @State private var vendor = ""
    @State private var serviceDate = Dates.format(nowMs())
    @State private var nextDueDays: Set<String> = ["30"]
    @State private var file: URL?
    @State private var seconds = 0
    @State private var capturedAt: Int64 = 0
    @State private var photoHash: String?
    @State private var capturing = false
    @State private var working = false
    @State private var progress = 0.0
    @State private var error: String?

    private func reset() {
        media = []; checklist = []; shift = []; staffCount = ""; vendor = ""
        serviceDate = Dates.format(nowMs()); nextDueDays = ["30"]
        file = nil; seconds = 0; capturedAt = 0; photoHash = nil; error = nil
        staffName = model.lastPackerName
    }

    private func handle(_ result: CaptureResult?) {
        guard let result else { return }
        working = true
        Task {
            defer { working = false }
            do {
                switch result {
                case .photo(let image):
                    let (url, hash) = try ImageTools.process(image)
                    if ImageTools.isDuplicate(hash, recent: model.kitchen?.recentHashes ?? []) {
                        error = "This photo looks the same as an earlier upload. Please take a fresh photo."
                    } else { file = url; photoHash = hash; error = nil }
                case .video(let raw):
                    let started = nowMs()
                    let (url, secs) = try await VideoTools.removeAudio(raw)
                    if secs < 5 { error = "Too short. Record at least 5 seconds."; return }
                    file = url; seconds = secs; capturedAt = started - Int64(secs) * 1000; error = nil
                }
            } catch { self.error = error.localizedDescription }
        }
    }

    private func submit() {
        let sectionError: String? = {
            switch section {
            case .UTENSILS: return media.isEmpty ? "Choose the cleaning medium used" : (shift.isEmpty ? "Choose the shift" : nil)
            case .STAFF:
                if checklist.count < MIN_STAFF_CHECKS { return "Select at least \(MIN_STAFF_CHECKS) hygiene checks (\(checklist.count) selected)" }
                return shift.isEmpty ? "Choose the shift" : nil
            case .PEST:
                if vendor.trimmingCharacters(in: .whitespaces).isEmpty { return "Enter the pest-control vendor" }
                return Dates.parse(serviceDate) == nil ? "Service date must be dd-mm-yyyy" : nil
            default: return nil
            }
        }()
        if staffName.trimmingCharacters(in: .whitespaces).isEmpty { error = "Enter your name"; return }
        guard let f = file else { error = section.media == .photo ? "Take the photo first" : "Record the clip first"; return }
        if let e = sectionError { error = e; return }
        guard let k = model.kitchen else { return }

        var details: [String: String] = [:]
        if section.media == .video && capturedAt > 0 { details["recordedAt"] = String(capturedAt) }
        let label: String
        switch section {
        case .UTENSILS:
            label = "Utensils cleaned"
            details["cleaningMedium"] = CLEANING_MEDIA.filter { media.contains($0) }.joined(separator: ", ")
            details["shift"] = shift.first
        case .STAFF:
            label = "Staff hygiene check"
            details["checklist"] = STAFF_CHECKLIST.filter { checklist.contains($0) }.joined(separator: ", ")
            let missing = STAFF_CHECKLIST.filter { !checklist.contains($0) }
            if !missing.isEmpty { details["missing"] = missing.joined(separator: ", ") }
            if !staffCount.isEmpty { details["staffOnShift"] = staffCount }
            details["shift"] = shift.first
        case .PEST:
            label = "Pest control service"
            let serviced = Dates.parse(serviceDate) ?? nowMs()
            let due = serviced + (Int64(nextDueDays.first ?? "30") ?? 30) * 24 * 3_600_000
            details["vendor"] = vendor.trimmingCharacters(in: .whitespaces)
            details["serviceDate"] = serviceDate
            details["nextDueDate"] = Dates.format(due)
            details["nextDueMillis"] = String(due)
        default: label = section.title
        }
        working = true; progress = 0; error = nil
        model.lastPackerName = staffName.trimmingCharacters(in: .whitespaces)
        Task {
            do {
                try await model.repo.saveLog(kitchen: k, section: section, key: section.rawValue, staffName: staffName, media: f,
                                             mediaType: section.media, details: details, label: label, photoHash: photoHash) { p in progress = p }
                await model.reloadKitchen()
                dismiss()
            } catch { self.error = "Upload failed: \(error.localizedDescription). Check internet and try again." }
            working = false
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text(section.subtitle).font(.subheadline)
                switch section {
                case .UTENSILS:
                    ChipPicker(label: "Cleaning medium used", options: CLEANING_MEDIA, selected: $media, multi: true)
                    ChipPicker(label: "Shift", options: SHIFTS, selected: $shift)
                case .STAFF:
                    Card {
                        Text("Checks shown in the clip (select at least \(MIN_STAFF_CHECKS))").font(.subheadline.weight(.semibold))
                        ForEach(STAFF_CHECKLIST, id: \.self) { item in
                            Toggle(item, isOn: Binding(
                                get: { checklist.contains(item) },
                                set: { on in if on { checklist.insert(item) } else { checklist.remove(item) } }))
                                .toggleStyle(CheckboxStyle())
                        }
                        Text("\(checklist.count) of \(STAFF_CHECKLIST.count) selected" +
                             (checklist.count < MIN_STAFF_CHECKS ? ": select \(MIN_STAFF_CHECKS - checklist.count) more" : ""))
                            .font(.caption).foregroundColor(checklist.count < MIN_STAFF_CHECKS ? Brand.chilli : Brand.ok)
                    }
                    ChipPicker(label: "Shift", options: SHIFTS, selected: $shift)
                    TextField("Staff on this shift", text: $staffCount).keyboardType(.numberPad).textFieldStyle(.roundedBorder)
                case .PEST:
                    TextField("Pest-control vendor", text: $vendor).textFieldStyle(.roundedBorder)
                    TextField("Service date (dd-mm-yyyy)", text: $serviceDate).textFieldStyle(.roundedBorder)
                    ChipPicker(label: "Next service due in (days)", options: ["15", "30", "60", "90"], selected: $nextDueDays)
                default: EmptyView()
                }

                Card {
                    Text(section.media == .photo ? "Photo proof" : "Video proof (5–20 s, no sound)").font(.subheadline.weight(.semibold))
                    if let f = file {
                        if section.media == .photo, let img = UIImage(contentsOfFile: f.path) {
                            Image(uiImage: img).resizable().scaledToFill().frame(height: 200).frame(maxWidth: .infinity).clipped()
                                .clipShape(RoundedRectangle(cornerRadius: 10))
                        } else {
                            Label("Clip recorded (\(seconds) s)", systemImage: "checkmark.circle.fill").foregroundColor(Brand.ok)
                        }
                    }
                    Button { capturing = true } label: {
                        Label(file == nil ? (section.media == .photo ? "Take photo" : "Record clip") : "Retake",
                              systemImage: section.media == .photo ? "camera" : "video").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered).disabled(working)
                }

                TextField("Recorded by (your name)", text: $staffName).textFieldStyle(.roundedBorder)
                ErrorText(text: error)
                if working && progress > 0 { ProgressView(value: progress) }
                Button { submit() } label: { Text(working ? "Uploading…" : "Save proof").frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent).disabled(working)
            }
            .padding(16)
        }
        .navigationTitle(section.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ResetButton { reset() } }
        .onAppear { if staffName.isEmpty { staffName = model.lastPackerName } }
        .camera(isPresented: $capturing, mode: section.media == .photo ? .photo : .video, hint: hints[section] ?? "") { handle($0) }
    }
}

struct CheckboxStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        Button { configuration.isOn.toggle() } label: {
            HStack {
                Image(systemName: configuration.isOn ? "checkmark.square.fill" : "square")
                    .foregroundColor(configuration.isOn ? Brand.leaf : .secondary).font(.title3)
                configuration.label.foregroundColor(.primary)
                Spacer()
            }
        }
        .buttonStyle(.plain)
    }
}
