import SwiftUI

private let maxPhotos = 6

/// Food inventory details: several categories, several photos each, optional bills
/// (one bill can cover some or all categories).
struct InventoryView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss

    @State private var cats: Set<String> = []
    @State private var photos: [String: [InventoryPhoto]] = [:]
    @State private var bills: [InventoryBill] = []
    @State private var supplier: Set<String> = []
    @State private var staffName = ""
    @State private var target: String?          // a category, or "__BILL__"
    @State private var capturing = false
    @State private var working = false
    @State private var progress = 0.0
    @State private var error: String?

    private var selected: [String] { INVENTORY_CATEGORIES.filter { cats.contains($0) } }

    private func reset() {
        cats = []; photos = [:]; bills = []; supplier = []; error = nil
        staffName = model.lastPackerName
    }

    private func capture(_ t: String) { target = t; capturing = true }

    private func handle(_ result: CaptureResult?) {
        guard case .photo(let image)? = result, let t = target else { return }
        working = true
        Task {
            defer { working = false }
            do {
                let (url, hash) = try ImageTools.process(image)
                if t == "__BILL__" {
                    var bill = InventoryBill(file: url, categories: cats, billDate: "", supplierGstin: "", note: "Reading the bill…")
                    bills.append(bill)
                    let scan = await BillReader.read(url)
                    bill.billDate = scan.date ?? ""
                    bill.supplierGstin = scan.gstin ?? ""
                    switch (scan.date != nil, scan.gstin != nil) {
                    case (true, true): bill.note = "Date and GST number read automatically. Please check them."
                    case (true, false): bill.note = "Date read automatically. Please check it."
                    case (false, true): bill.note = "GST number read automatically. Please type the date."
                    default: bill.note = "Could not read the bill. You can type the date, or leave it blank."
                    }
                    if let i = bills.firstIndex(where: { $0.id == bill.id }) { bills[i] = bill }
                } else {
                    let session = photos.values.flatMap { $0.map(\.hash) }
                    if ImageTools.isDuplicate(hash, recent: (model.kitchen?.recentHashes ?? []) + session) {
                        error = "This photo looks the same as one already taken. Please take a fresh photo."
                    } else {
                        photos[t, default: []].append(InventoryPhoto(file: url, hash: hash))
                        error = nil
                    }
                }
            } catch { self.error = error.localizedDescription }
        }
    }

    private func submit() {
        error = nil
        if selected.isEmpty { error = "Choose at least one category that arrived"; return }
        if let m = selected.first(where: { (photos[$0] ?? []).isEmpty }) { error = "Add at least one photo for \(m)"; return }
        if supplier.isEmpty { error = "Choose where the food was bought from"; return }
        if staffName.trimmingCharacters(in: .whitespaces).isEmpty { error = "Enter your name"; return }
        for (i, b) in bills.enumerated() {
            if b.categories.intersection(cats).isEmpty { error = "Bill \(i + 1): choose which categories it covers, or remove it"; return }
            if !b.billDate.isEmpty && Dates.parse(b.billDate) == nil { error = "Bill \(i + 1): date must be dd-mm-yyyy, or leave it blank"; return }
            if let e = Gstin.formError(b.supplierGstin, required: false) { error = "Bill \(i + 1): \(e)"; return }
        }
        guard let k = model.kitchen else { return }
        let photoMap = Dictionary(uniqueKeysWithValues: selected.map { ($0, photos[$0] ?? []) })
        let billList = bills.map { b -> InventoryBill in
            var c = b
            c.categories = b.categories.intersection(cats)
            c.billDate = b.billDate.trimmingCharacters(in: .whitespaces)
            c.supplierGstin = Gstin.normalize(b.supplierGstin)
            return c
        }
        working = true; progress = 0
        model.lastPackerName = staffName.trimmingCharacters(in: .whitespaces)
        Task {
            do {
                try await model.repo.saveInventory(kitchen: k, staffName: staffName, supplierType: supplier.first,
                                                   photos: photoMap, bills: billList) { p in progress = p }
                await model.reloadKitchen()
                dismiss()
            } catch { self.error = "Upload failed: \(error.localizedDescription). Check internet and try again." }
            working = false
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("Food inventory is worth 32 points: photos of each category (points shown below) and supplier bills (7). Perishables (vegetables, meat & fish, dairy) count for 48 hours, dry goods for 7 days, then they must be recorded again. Customers see categories, never item names or quantities.")
                    .font(.subheadline)
                ChipPicker(label: "Categories received", options: INVENTORY_CATEGORIES.filter { !(model.kitchen?.notStocked.contains($0) ?? false) },
                           selected: $cats, multi: true)
                if let ns = model.kitchen?.notStocked, !ns.isEmpty {
                    Text("Not stocked (left out of your rating): \(ns.sorted().joined(separator: ", ")). Change this in Kitchen details.").font(.caption)
                }

                ForEach(selected, id: \.self) { cat in
                    let list = photos[cat] ?? []
                    Card {
                        Text("\(cat) · \(list.count) photo\(list.count == 1 ? "" : "s") · \(HygieneScore.weight(cat)) points").font(.subheadline.weight(.semibold))
                        if !list.isEmpty {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 8) {
                                    ForEach(list) { p in Thumb(url: p.file) { photos[cat]?.removeAll { $0.id == p.id } } }
                                }
                            }
                        }
                        Button { capture(cat) } label: {
                            Label(list.isEmpty ? "Take photo" : (list.count < maxPhotos ? "Add another photo" : "Maximum \(maxPhotos) photos"),
                                  systemImage: "camera").frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered).disabled(working || list.count >= maxPhotos)
                    }
                }

                Card(tint: Color(.secondarySystemBackground)) {
                    Text("Supplier bills (7 points)").font(.subheadline.weight(.semibold))
                    Text("Add a bill if you have one: it improves customer trust, but you can save without it. Add one bill per supplier. If a single bill covers everything, tap \"Covers all categories\". Bill photos and supplier GST numbers stay private; customers only see the bill date.")
                        .font(.caption)
                    ForEach($bills) { $bill in
                        Divider()
                        BillEditor(bill: $bill, selected: selected) { bills.removeAll { $0.id == bill.id } }
                    }
                    Button { capture("__BILL__") } label: {
                        Label(bills.isEmpty ? "Add a bill" : "Add another bill", systemImage: "doc.text.viewfinder").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered).disabled(working || selected.isEmpty)
                    if selected.isEmpty { Text("Choose the categories first, then add bills.").font(.caption) }
                }

                ChipPicker(label: "Bought from (required)", options: SUPPLIER_TYPES, selected: $supplier)
                TextField("Recorded by (your name)", text: $staffName).textFieldStyle(.roundedBorder)
                ErrorText(text: error)
                if working && progress > 0 { ProgressView(value: progress) }
                let count = selected.reduce(0) { $0 + (photos[$1]?.count ?? 0) }
                Button { submit() } label: {
                    Text(working ? "Uploading…" : "Save \(count) photo\(count == 1 ? "" : "s") and \(bills.count) bill\(bills.count == 1 ? "" : "s")")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent).disabled(working)
            }
            .padding(16)
        }
        .navigationTitle(Section.INVENTORY.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ResetButton { reset() } }
        .onAppear { if staffName.isEmpty { staffName = model.lastPackerName } }
        .camera(isPresented: $capturing, mode: .photo,
                hint: target == "__BILL__" ? "Photograph the supplier bill so the date and GST number are readable."
                    : "Photograph the \(target ?? "produce") as it arrives. Keep spice labels and recipe cards out of the frame.") { handle($0) }
    }
}

private struct BillEditor: View {
    @Binding var bill: InventoryBill
    let selected: [String]
    let onRemove: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 12) {
                Thumb(url: bill.file)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Supplier bill").font(.subheadline.weight(.semibold))
                    if let n = bill.note { Text(n).font(.caption) }
                }
                Spacer()
                Button("Remove", role: .destructive, action: onRemove)
            }
            ChipPicker(label: "This bill covers", options: selected,
                       selected: Binding(get: { bill.categories.intersection(selected) }, set: { bill.categories = $0 }), multi: true)
            Button(bill.categories.isSuperset(of: Set(selected)) ? "✓ Covers all categories" : "Covers all categories") {
                bill.categories = Set(selected)
            }
            TextField("Bill date (dd-mm-yyyy, optional)", text: $bill.billDate).textFieldStyle(.roundedBorder)
            TextField("Supplier GST number (optional)", text: $bill.supplierGstin)
                .textInputAutocapitalization(.characters).autocorrectionDisabled().textFieldStyle(.roundedBorder)
            Text(Gstin.formError(bill.supplierGstin, required: false) ?? Gstin.warning(bill.supplierGstin) ?? "15 characters, e.g. 29ABCDE1234F1ZW")
                .font(.caption).foregroundColor(Gstin.formError(bill.supplierGstin, required: false) != nil ? Brand.chilli : .secondary)
        }
    }
}
