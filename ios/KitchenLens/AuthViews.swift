import SwiftUI

struct LoginView: View {
    @EnvironmentObject var model: AppModel
    @State private var email = ""
    @State private var password = ""
    @State private var busy = false
    @State private var error: String?

    private func go(_ signUp: Bool) {
        error = nil
        guard email.contains("@"), password.count >= 6 else {
            error = "Enter a valid email and a password of at least 6 characters"; return
        }
        busy = true
        Task {
            do {
                if signUp { try await model.repo.signUp(email: email, password: password) }
                else { try await model.repo.signIn(email: email, password: password) }
                await model.refresh()
            } catch { self.error = error.localizedDescription }
            busy = false
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Spacer(minLength: 24)
                Text("KitchenLens").font(.largeTitle.bold()).foregroundColor(Brand.leaf)
                Text("Food Safety & Hygiene Verification").font(.title3.weight(.semibold))
                Text("Show customers how their food was prepared, without revealing your recipes.")
                if model.repo.isDemo { DemoBanner() }
                TextField("Kitchen email", text: $email)
                    .keyboardType(.emailAddress).textContentType(.username).autocapitalization(.none)
                    .textFieldStyle(.roundedBorder)
                SecureField("Password", text: $password).textContentType(.password).textFieldStyle(.roundedBorder)
                ErrorText(text: error)
                Button { go(false) } label: { Text(busy ? "Please wait…" : "Sign in").frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent).disabled(busy)
                Button("New kitchen? Create an account") { go(true) }.frame(maxWidth: .infinity).disabled(busy)
                Button("Reset") { email = ""; password = ""; error = nil }.frame(maxWidth: .infinity).disabled(busy)
            }
            .padding(24)
        }
    }
}

/// Editable kitchen details shared by first-time setup and Kitchen details.
struct ProfileForm {
    var name = ""
    var locality = ""
    var city: Set<String> = [CITIES[0]]
    var fssai = ""
    var gstin = ""
    var tier: Set<String> = [TIERS[0]]
    var fssaiRating: Set<String> = [ProfileForm.noRating]
    var fssaiAuditDate = ""
    var notStocked: Set<String> = []

    static let noRating = "Not rated"
    static let ratingOptions = [noRating, "1", "2", "3", "4", "5"]

    init() {}
    init(_ k: Kitchen) {
        name = k.name; locality = k.locality; city = [k.city.isEmpty ? CITIES[0] : k.city]
        fssai = k.fssai; gstin = k.gstin; tier = [k.tier.isEmpty ? TIERS[0] : k.tier]
        fssaiRating = [k.fssaiHygieneRating.isEmpty ? ProfileForm.noRating : k.fssaiHygieneRating]
        fssaiAuditDate = k.fssaiAuditDate; notStocked = k.notStocked
    }

    private var ratingValue: String { fssaiRating.first.flatMap { $0 == ProfileForm.noRating ? nil : $0 } ?? "" }

    func validate() -> String? {
        if name.trimmingCharacters(in: .whitespaces).isEmpty { return "Enter the kitchen name" }
        if locality.trimmingCharacters(in: .whitespaces).isEmpty { return "Enter the locality" }
        if fssai.count != 14 { return "FSSAI number must be 14 digits" }
        if !ratingValue.isEmpty {
            guard let t = Dates.parse(fssaiAuditDate) else { return "Enter the FSSAI hygiene audit date as dd-mm-yyyy" }
            if t > nowMs() { return "The FSSAI audit date can't be in the future" }
        }
        if notStocked.count >= INVENTORY_CATEGORIES.count { return "At least one inventory category must be stocked" }
        return Gstin.formError(gstin, required: false)
    }

    var map: [String: String] {
        profileMap(name: name, locality: locality, city: city.first ?? CITIES[0], fssai: fssai,
                   gstin: Gstin.normalize(gstin), tier: tier.first ?? TIERS[0],
                   fssaiHygieneRating: ratingValue, fssaiAuditDate: ratingValue.isEmpty ? "" : fssaiAuditDate, notStocked: notStocked)
    }
}

struct ProfileFields: View {
    @Binding var form: ProfileForm
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            TextField("Kitchen / brand name", text: $form.name).textFieldStyle(.roundedBorder)
            TextField("Locality (e.g. HSR Layout)", text: $form.locality).textFieldStyle(.roundedBorder)
            ChipPicker(label: "City", options: CITIES, selected: $form.city)
            TextField("FSSAI licence / registration no. (14 digits)", text: $form.fssai)
                .keyboardType(.numberPad).textFieldStyle(.roundedBorder)
                .onChange(of: form.fssai) { v in
                    let d = String(v.filter(\.isNumber).prefix(14))
                    if d != v { form.fssai = d }
                }
            VStack(alignment: .leading, spacing: 4) {
                TextField("GST number (GSTIN), if registered", text: $form.gstin)
                    .textInputAutocapitalization(.characters).autocorrectionDisabled().textFieldStyle(.roundedBorder)
                    .onChange(of: form.gstin) { v in
                        let n = Gstin.normalize(v)
                        if n != v { form.gstin = n }
                    }
                Text(Gstin.formError(form.gstin, required: false) ?? Gstin.warning(form.gstin)
                     ?? "Optional. 15 characters, e.g. 29ABCDE1234F1ZW. Shown to customers with your FSSAI number.")
                    .font(.caption)
                    .foregroundColor(Gstin.formError(form.gstin, required: false) != nil ? Brand.chilli : .secondary)
            }
            ChipPicker(label: "Kitchen area (kit tier)", options: TIERS, selected: $form.tier)

            ChipPicker(label: "Inventory you never stock (left out of your hygiene rating)", options: INVENTORY_CATEGORIES,
                       selected: $form.notStocked, multi: true)
            Text("For example, a vegetarian kitchen selects Meat & Fish. Customers see that it is not stocked.").font(.caption)

            ChipPicker(label: "Official FSSAI Hygiene Rating (if you have the certificate)", options: ProfileForm.ratingOptions,
                       selected: Binding(get: { form.fssaiRating }, set: { form.fssaiRating = $0.isEmpty ? [ProfileForm.noRating] : $0 }))
            if form.fssaiRating.first != ProfileForm.noRating {
                TextField("FSSAI hygiene audit date (dd-mm-yyyy)", text: $form.fssaiAuditDate).textFieldStyle(.roundedBorder)
                Text("Shown to customers as \"declared by the kitchen\", separate from the KitchenLens rating. Keep the certificate ready: customers may ask for it. Certificates are valid for 2 years.")
                    .font(.caption)
            }
        }
    }
}

/// Public kitchen page address; demo mode packs the profile into the link (no server).
@MainActor
func kitchenPageURL(_ model: AppModel, _ k: Kitchen) -> String {
    let base = AppConfig.webBaseURL + "kitchen.html?k=" + (k.id.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? k.id)
    guard model.repo.isDemo, let pub = model.local?.publicKitchen else { return base }
    return base + "&d=" + OfflineSeal.kitchenPayload(pub)
}

/// Badge with the kitchen's 30-day rating, to share on listings and social media.
struct KitchenBadgeCard: View {
    @EnvironmentObject var model: AppModel
    let kitchen: Kitchen
    @State private var share: UIImage?
    @State private var web: URL?

    var body: some View {
        Card(tint: Brand.leafLight) {
            Text("Your kitchen hygiene badge").font(.headline)
            Text(model.kitchenStats?.summary() ?? "Calculating your 30-day rating…").font(.subheadline)
            Text("Post this badge on your Swiggy and Zomato menu photos, Instagram and Google listing. Its QR opens your kitchen page with your live rating.")
                .font(.caption)
            Button {
                guard let st = model.kitchenStats else { return }
                share = QrLabel.badge(url: kitchenPageURL(model, kitchen), kitchenName: kitchen.name,
                                      headline: st.eligible ? "\(String(format: "%.1f", st.stars)) ★  \(st.rating)" : "New on KitchenLens",
                                      detail: st.eligible ? "\(st.orders) sealed orders · last 30 days" : "Every order sealed with hygiene proof")
            } label: { Text("Share hygiene badge").frame(maxWidth: .infinity) }
                .buttonStyle(.borderedProminent).disabled(model.kitchenStats == nil)
            Button { web = URL(string: kitchenPageURL(model, kitchen)) } label: { Text("Open my kitchen page").frame(maxWidth: .infinity) }
                .buttonStyle(.bordered)
        }
        .sheet(isPresented: Binding(get: { share != nil }, set: { if !$0 { share = nil } })) {
            if let img = share { ShareSheet(items: [img]) }
        }
        .sheet(isPresented: Binding(get: { web != nil }, set: { if !$0 { web = nil } })) {
            if let u = web { SafariView(url: u).ignoresSafeArea() }
        }
    }
}

struct SetupKitchenView: View {
    @EnvironmentObject var model: AppModel
    @State private var form = ProfileForm()
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("Set up your kitchen").font(.title2.bold())
                Text("Customers will see these details when they scan the QR seal.")
                ProfileFields(form: $form)
                ErrorText(text: error)
                Button {
                    error = form.validate()
                    guard error == nil else { return }
                    busy = true
                    Task {
                        do { _ = try await model.repo.createKitchen(profile: form.map); await model.refresh() }
                        catch { self.error = error.localizedDescription }
                        busy = false
                    }
                } label: { Text(busy ? "Saving…" : "Save and continue").frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent).disabled(busy)
                Button("Reset") { form = ProfileForm(); error = nil }.frame(maxWidth: .infinity)
                Button("Sign out") { model.signOut() }.frame(maxWidth: .infinity)
            }
            .padding(24)
        }
    }
}

struct EditKitchenView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var form = ProfileForm()
    @State private var loaded = false
    @State private var busy = false
    @State private var error: String?
    @State private var showPrivacy = false
    @State private var confirmDelete = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("Changes appear on QR seals printed from now on.").font(.footnote).foregroundColor(.secondary)
                if let k = model.kitchen { KitchenBadgeCard(kitchen: k) }
                if let local = model.local { CloudStorageCard(cloud: local.cloud) }
                ProfileFields(form: $form)
                ErrorText(text: error)
                Button {
                    error = form.validate()
                    guard error == nil, let k = model.kitchen else { return }
                    busy = true
                    Task {
                        do {
                            try await model.repo.updateKitchenProfile(kitchenId: k.id, profile: form.map)
                            await model.reloadKitchen()
                            dismiss()
                        } catch { self.error = "Could not save: \(error.localizedDescription)" }
                        busy = false
                    }
                } label: { Text(busy ? "Saving…" : "Save changes").frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent).disabled(busy)

                Divider().padding(.vertical, 8)
                Text("Account").font(.headline)
                Button("Privacy policy") { showPrivacy = true }
                Button(role: .destructive) { confirmDelete = true } label: {
                    Text("Delete my account and data").frame(maxWidth: .infinity)
                }.buttonStyle(.bordered)
            }
            .padding(16)
        }
        .navigationTitle("Kitchen details")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ResetButton { if let k = model.kitchen { form = ProfileForm(k) }; error = nil } }
        .onAppear { if !loaded, let k = model.kitchen { form = ProfileForm(k); loaded = true } }
        .sheet(isPresented: $showPrivacy) {
            if let u = URL(string: AppConfig.webBaseURL + "privacy.html") { SafariView(url: u) }
        }
        .sheet(isPresented: $confirmDelete) { DeleteAccountSheet() }
    }
}

/// Permanent account deletion (required by the App Store and Google Play).
struct DeleteAccountSheet: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var password = ""
    @State private var typed = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Text("This deletes your kitchen profile, all hygiene records, photos, clips, orders and QR seals. Seals already given to customers will stop working. This cannot be undone.")
                if !model.repo.isDemo { SecureField("Your password", text: $password) }
                TextField("Type DELETE to confirm", text: $typed).textInputAutocapitalization(.characters)
                if model.repo.isDemo {
                    Text("Photos already uploaded to your own Cloudinary account must be deleted there.").font(.footnote)
                }
                ErrorText(text: error)
                Button(role: .destructive) {
                    busy = true; error = nil
                    Task {
                        do { try await model.deleteAccount(password: password); dismiss() }
                        catch { self.error = "Could not delete: \(error.localizedDescription)" }
                        busy = false
                    }
                } label: { Text(busy ? "Deleting…" : "Delete permanently") }
                    .disabled(busy || typed.trimmingCharacters(in: .whitespaces).uppercased() != "DELETE"
                              || (!model.repo.isDemo && password.isEmpty))
            }
            .navigationTitle("Delete account")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.disabled(busy) } }
        }
    }
}

/// Connects free Cloudinary storage so photos and clips play when a customer scans the seal (demo mode).
struct CloudStorageCard: View {
    let cloud: CloudMedia
    @State private var cloudName = ""
    @State private var preset = ""
    @State private var busy = false
    @State private var status: String?
    @State private var ok = false

    var body: some View {
        Card(tint: Color(.secondarySystemBackground)) {
            Text("Photos and clips on scanned seals").font(.headline)
            Text("A QR code is too small to hold photos or videos. Connect free cloud storage so customers see them when they scan:\n1. Create a free account at cloudinary.com.\n2. Copy the Cloud name shown on its dashboard.\n3. Settings → Upload → Upload presets → Add upload preset → Signing mode: Unsigned → Save, and copy the preset name.")
                .font(.footnote)
            TextField("Cloud name", text: $cloudName).autocapitalization(.none).autocorrectionDisabled().textFieldStyle(.roundedBorder)
            TextField("Upload preset name (Unsigned)", text: $preset).autocapitalization(.none).autocorrectionDisabled().textFieldStyle(.roundedBorder)
            if let s = status { Text(s).font(.footnote).foregroundColor(ok ? Brand.ok : Brand.chilli) }
            Button {
                busy = true; status = "Testing the connection…"; ok = true
                Task {
                    do {
                        try await cloud.testConnection(cloudName: cloudName, preset: preset)
                        cloud.saveSettings(cloudName: cloudName, preset: preset)
                        ok = true; status = "Connected and saved. New seals will show photos and clips."
                    } catch { ok = false; status = error.localizedDescription }
                    busy = false
                }
            } label: { Text(busy ? "Testing…" : "Test and save").frame(maxWidth: .infinity) }
                .buttonStyle(.borderedProminent)
                .disabled(busy || cloudName.isEmpty || preset.isEmpty)
        }
        .onAppear {
            cloudName = cloud.cloudName; preset = cloud.preset
            if cloud.isConfigured { ok = true; status = "Connected. Photos and clips will show when seals are scanned." }
        }
    }
}
