# KitchenLens — Food Safety & Hygiene Verification

Hygiene transparency for cloud kitchens. Kitchen staff record hygiene proof in the
Android or iPhone app, and every delivery bag gets a QR seal. When the customer scans it, they
see the order's packing clip, the food temperature at packing, and the kitchen's
hygiene records from before the order, without any recipe being shown.

| Folder | What it is |
|---|---|
| `android/` | Kitchen app for Android (Kotlin, Jetpack Compose) |
| `ios/` | Kitchen app for iPhone (SwiftUI, iOS 16+); same features and same Firebase data |
| `store/` | Ready-to-paste Google Play and App Store listing texts, data-safety and privacy answers |
| `web/` | Customer scan page that the QR code opens |
| `firebase/`, `firebase.json` | Database and storage security rules, hosting setup |
| `.github/workflows/` | GitHub builds the Android APK / Play bundle, the iPhone app (and TestFlight upload), and publishes the scan page |

---

## 1. Test it on GitHub (no setup, about 10 minutes)

1. Create a new repository on GitHub (it can be private).
2. Upload everything in this folder, keeping the folder structure. The easiest way is
   GitHub Desktop, or on the website: **Add file > Upload files** and drag in the
   *contents* of this folder (including the hidden `.github` folder).
   If your file browser hides `.github`, turn on "show hidden files".
3. Open the **Actions** tab. The **Build Android app** workflow starts on its own.
   It runs the unit tests and builds the app (about 5–8 minutes).
4. When it shows a green tick, open the run, scroll to **Artifacts**, download
   **KitchenLens-debug-apk**, and unzip it to get `app-debug.apk`.
5. Copy the APK to an Android phone (Android 8 or newer), open it, and allow
   "install unknown apps" when asked.

The app starts in **demo mode**: any email and password works, and data stays on the
phone. You can try the full flow: set up a kitchen, record the four hygiene sections,
pack an order, and print or share the QR seal. Use **Preview what the customer sees**
to see the customer page inside the app.

### Show photos and clips on scanned seals (free, about 5 minutes)

A QR code holds under 3 KB, so photos and clips can't live inside it. In demo mode the app
uploads them to the kitchen's own free Cloudinary account and the QR carries only short IDs.

1. Create a free account at <https://cloudinary.com> (no card needed) and note the **Cloud name**
   on the dashboard.
2. In Cloudinary: **Settings → Upload → Upload presets → Add upload preset**, set
   **Signing mode: Unsigned**, save, and note the preset name.
3. In the app: **shop icon (Kitchen details) → Photos and clips on scanned seals**, enter both,
   tap **Test and save**.

From then on, hygiene photos and clips upload when saved, and each new seal links to them.
For a seal made before connecting, open it from **Recent orders** and tap
**Upload photos and clips, then refresh this QR**, then reprint it.

### Make the QR codes scannable (optional, 2 minutes)

On a free GitHub plan, Pages only works for **public** repositories: either make the
repository public (Settings > General > Danger Zone > Change visibility) or upgrade to
GitHub Pro. Otherwise scanning a seal shows GitHub's 404 page.

1. **Settings > Pages > Build and deployment > Source: GitHub Actions.**
2. Run **Actions > Publish customer scan page > Run workflow**.
3. Re-run the Android build so the QR codes point to
   `https://<your-username>.github.io/<repo-name>/?c=CODE`.

In demo mode the scan page shows a sample order, because demo clips stay on the phone.
`.../?c=DEMO` always shows the sample page, which is handy for sales demos.

---

## 2. Go live with Firebase (real kitchens)

Firebase hosts the logins, records, clips and scan page. Choose the **Mumbai
(asia-south1)** region so customer data stays in India.

1. Go to <https://console.firebase.google.com>, create a project, and upgrade to the
   **Blaze (pay-as-you-go)** plan. Google requires it for file storage. Set a budget
   alert (for example ₹2,000 a month). For 50 kitchens, storage and bandwidth should
   cost a few hundred rupees a month.
2. **Authentication > Sign-in method:** enable **Email/Password**.
3. **Firestore Database:** create it in **asia-south1**, production mode.
4. **Storage:** create the default bucket in **asia-south1**.
5. **Project settings > Your apps > Add app > Android**, package name
   `com.kitchenlens.app`. Download `google-services.json`.
6. Give the build the real config, using **one** of these:
   - On GitHub (keeps it out of the code): **Settings > Secrets and variables >
     Actions > New repository secret**, name `GOOGLE_SERVICES_JSON`, value = the whole
     file's contents.
   - Or replace `android/app/google-services.json` with the downloaded file.
7. Deploy the security rules and the scan page from a computer with Node.js:
   ```
   npm install -g firebase-tools
   firebase login
   firebase use --add            # pick your project
   firebase deploy               # rules, indexes and the scan page
   ```
8. Fill in `web/config.js` with your `projectId`, the **Web API key** (Project
   settings > General) and your support email, then run `firebase deploy --only hosting`
   again.
9. On GitHub: **Settings > Secrets and variables > Actions > Variables > New variable**,
   name `SCAN_BASE_URL`, value `https://<your-project-id>.web.app/o/`.
   Re-run the Android build.

Install the new APK. It now uses real logins and real cloud storage, and QR codes open
`https://<your-project-id>.web.app/o/CODE` for anyone who scans them.

---

## 3. How the app works

**Kitchen details** (shop icon on the Home screen): name, locality, FSSAI number and
GST number (GSTIN, optional, format-checked). Customers see FSSAI and GSTIN on every seal.

**Kitchen Hygiene proof (Home screen)**

| Section | Captured | Customer sees |
|---|---|---|
| Food inventory details | Several categories at once, up to 6 live photos per category; optional supplier bills (one bill can cover some or all categories), with date and supplier GSTIN read automatically | All photos per category, how long before packing it arrived, latest bill date, "GST-registered supplier" (bill photos and supplier GSTINs stay private) |
| Cooking Utensils cleaning details | 5–20 s clip, cleaning medium, shift | Clip and cleaning medium |
| Chef, Support staff cooking hygiene | 5–20 s clip, checklist (hand-wash, cap, gloves…), shift | Clip, checks done, checks **not** done |
| Pest control details | Photo of service report, vendor, service date, next due | Vendor and next due date |

**Per order (about 30 seconds):** tap **New order + QR seal**, choose the platform, enter the
**platform order number** (required except for direct orders), the temperature shown on the probe
(FSSAI guideline: hot food at 65°C or above, cold food 5°C or below; any reading can be saved), and record a
5–20 s packing clip, then tap **Create QR seal**. Share the label image to a Bluetooth
thermal-printer app (58 mm labels) and stick it across the bag opening.

- **Save draft** keeps the order on the phone; leaving the screen or recording a clip saves it
  automatically. Home shows "Order in progress" with **Continue this order**.
- **New order** clears the current order (after a confirmation) and starts from the beginning.
- Each seal is linked to its platform order number. The app refuses a second seal for the same
  order number and offers to open the existing one. **Recent orders** can be searched by order number.
- Customers can scan the seal, or open the scan page and look up their order by platform and
  order number (`.../?p=Swiggy&o=158823344471` also works).
- **Demo mode:** the QR link itself carries the order details (kitchen, order number,
  temperature, score, hygiene timeline). Photos and clips are too large for a QR code, so they
  appear on the customer page only after Firebase cloud storage is connected (section 2).

**Built-in checks**

- Only in-app camera capture; no gallery uploads, so old photos can't be reused.
- Duplicate-photo detection compares each new photo with the kitchen's last 60.
- On-device text recognition reads the date from supplier bills.
- Temperature check: hot food at 65°C or above (FSSAI guideline), cold food at 5°C or below; a warning only.
- Hygiene score (0–100): five pillars of 20 points each (utensils, staff, pest control,
  perishable delivery, temperature), frozen at the moment of packing.
- Clips are recorded **without sound**, so recipe discussions are never captured.
- Records can be added but never edited or deleted (enforced by the database rules).
- The customer sees only the last 4 digits of the order number and the packer's first name.

**Recipe protection** relies on what gets filmed: the packing station, the wash area and
the hand-wash area. Train staff to keep spice jars, labels and recipe cards out of frame,
and prepare secret masalas in a separate room in coded containers.

---

## 4. Developer notes

- Open `android/` in Android Studio (Ladybug or newer). Android Studio may offer to
  update library versions; accepting is fine.
- Unit tests: `./gradlew testDebugUnitTest` (pure logic: score, bill dates, duplicates,
  order codes, privacy of public order records).
- Backend switch: `AppViewModel` uses `LocalRepository` (demo) when the Firebase project
  id is `kitchenlens-demo`, and `FirebaseRepository` otherwise.
- Data model:
  - `users/{uid}` → `kitchenId`
  - `kitchens/{id}` → profile, `latest` (newest record per section), `recentHashes`
  - `kitchens/{id}/logs/{id}` → every record (append-only, includes private bill photos
    and full order numbers)
  - `orders/{code}` → public record the scan page reads, including a frozen `snapshot`
    of the kitchen's hygiene state at packing
- Android targets API 36 (Google Play requirement from 31 August 2026), built with AGP 8.10 / Gradle 8.11.
- iPhone: `ios/project.yml` is an XcodeGen spec. On a Mac: `brew install xcodegen && cd ios && xcodegen generate && open KitchenLens.xcodeproj`.
  Demo mode is used when `GoogleService-Info.plist` is not in `ios/KitchenLens/Resources/`.

## 5. Publish on Google Play (Android users)

Do section 2 first: store users need the real Firebase backend, not demo mode.

1. **Create the upload key (once, keep it safe; losing it blocks updates).** On any computer with Java:
   ```
   keytool -genkeypair -v -keystore kitchenlens-upload.jks -alias kitchenlens \
     -keyalg RSA -keysize 2048 -validity 10000
   base64 -w0 kitchenlens-upload.jks > keystore.txt      # macOS: base64 -i kitchenlens-upload.jks -o keystore.txt
   ```
2. **GitHub secrets** (Settings > Secrets and variables > Actions > Secrets):

   | Secret | Value |
   |---|---|
   | `GOOGLE_SERVICES_JSON` | contents of `google-services.json` (section 2) |
   | `KEYSTORE_BASE64` | contents of `keystore.txt` |
   | `KEYSTORE_PASSWORD` | the keystore password |
   | `KEY_ALIAS` | `kitchenlens` |
   | `KEY_PASSWORD` | the key password |

   **Variables** (same page, Variables tab): `SCAN_BASE_URL` = `https://<project-id>.web.app/o/`,
   `WEB_BASE_URL` = `https://<project-id>.web.app/`, `SUPPORT_EMAIL` = your support email.
3. Fill in `businessName`, `businessAddress` and `supportEmail` in `web/config.js` and run
   `firebase deploy --only hosting`. Check that `<WEB_BASE_URL>privacy.html` and
   `<WEB_BASE_URL>delete-account.html` open.
4. Push any change (or **Actions > Build Android app > Run workflow**). Download the artifact
   **KitchenLens-play-store-bundle** → `app-release.aab`. Each run gets a higher version code automatically.
5. <https://play.google.com/console> → pay the one-time US$25 → **Create app**
   (name "KitchenLens: Food Hygiene", App, Free). Fill the store listing, app content and data
   safety form from [`store/google-play.md`](store/google-play.md).
6. Upload the `.aab` to **Testing > Closed testing** (personal accounts: 12 testers for 14 days, then
   apply for production). Organisation accounts can go straight to **Production**. Accept
   **Play App Signing** when asked. Review usually takes 1–7 days.

## 6. Publish on the Apple App Store (iPhone users)

No Mac is needed: GitHub's Mac builder compiles, signs and uploads the app.

1. **Apple Developer Program** (US$99 a year): <https://developer.apple.com/programs/enroll/>.
   Enrol as an organisation (needs a D-U-N-S number) so your business name shows as the seller.
2. **Firebase:** Project settings > Your apps > Add app > **iOS**, bundle ID `com.kitchenlens.app`.
   Download `GoogleService-Info.plist`.
3. **App Store Connect** (<https://appstoreconnect.apple.com>) > **Apps > + New App**: iOS, name
   "KitchenLens: Food Hygiene", bundle ID `com.kitchenlens.app` (register it first under
   developer.apple.com > Identifiers if it isn't listed), SKU `kitchenlens-ios-1`.
4. **API key for GitHub:** App Store Connect > Users and Access > **Integrations > App Store Connect API** >
   generate a key with **Admin** access (needed so the build can create its own signing certificate).
   Download the `.p8` file (only once). Note the **Key ID** and **Issuer ID**.
5. **GitHub secrets:**

   | Secret | Value |
   |---|---|
   | `APPLE_TEAM_ID` | 10-character Team ID (developer.apple.com > Membership) |
   | `ASC_KEY_ID` | the API Key ID |
   | `ASC_ISSUER_ID` | the Issuer ID |
   | `ASC_KEY_P8` | the whole text of the `.p8` file, including the BEGIN/END lines |
   | `GOOGLE_SERVICE_INFO_PLIST` | the whole text of `GoogleService-Info.plist` |

   The `SCAN_BASE_URL`, `WEB_BASE_URL` and `SUPPORT_EMAIL` variables from section 5 are used too.
6. Run **Actions > Build iPhone app > Run workflow**. It compiles the app, then signs it and uploads
   it to App Store Connect. Without the Apple secrets it only does the compile check.
7. After 10–30 minutes the build appears under **TestFlight**. Install the **TestFlight** app on an iPhone
   and test with your own Apple ID (internal testers need no review).
8. In App Store Connect fill in the listing, App Privacy and App Review details from
   [`store/apple-app-store.md`](store/apple-app-store.md), choose the build, and **Submit for Review**
   (usually 1–3 days). Give the reviewer a working test kitchen login.

**Updating later:** change the code and push. Both workflows build new versions with higher build
numbers; upload the new `.aab` to Play, and pick the new build in App Store Connect. To change the
version shown to users, edit `versionName` in `android/gradle.properties` and `MARKETING_VERSION`
in `ios/project.yml`.

## 7. Next steps (not built yet)

1. **Bluetooth food probe:** fill the temperature field automatically so it can't be typed.
2. **Upload queue:** retry uploads in the background when kitchen internet drops.
3. **Server-side AI:** blur staff faces in clips, flag clips that show spice labels, and
   detect missing caps or gloves.
4. **Staff logins:** separate accounts per staff member, with an owner dashboard.
5. **Auto-delete:** remove clips after 30 days unless linked to a complaint.
6. **Billing:** kit orders, subscriptions and seal refills.
7. **Languages:** Kannada, Hindi, Tamil and Telugu for staff screens.


## Customer report redesign

The public scan page (`web/index.html`) uses a customer-facing report layout with:
- clearer kitchen identity, seal reference, order facts, packing evidence and timeline;
- mobile-first cards, accessible focus states and print / Save as PDF support;
- masked order references (only the final four digits are displayed);
- plain-language explanations that the hygiene score is a record-coverage indicator, not a certification;
- explicit distinction between a kitchen-recorded temperature guideline and independent food-safety validation.

Before production use, review the temperature acceptance criteria against the kitchen's documented food-safety plan and applicable FSSAI requirements. The page intentionally does not describe a kitchen-entered score as independent certification.

## Latest changes

- **Timestamps:** every photo has the date and time burned into it when taken; the camera shows a
  live clock; every clip plays with a running date-time overlay (recording start + playback time)
  in the app and on the customer page.
- **Reset** (top right) on every input screen clears what was entered on that screen immediately.
- **New order: clear everything** (Home, order screen and after a seal is created) immediately clears
  the order form and the current hygiene records so the next order starts from the beginning.
  Seals already created keep their own records. An **Undo** banner appears on Home.
- **Temperature:** the note shows the FSSAI guideline of 65°C or above for hot food (5°C or below for
  cold). A lower reading shows a warning but never blocks the order.
- **Seal label** now reads "We care your Hygiene; scan the barcode for the proof." above the QR code.
- **Pack an order** warns when any Kitchen Hygiene proof section is not entered, with **Go back**
  (scrolls to the Kitchen Hygiene proof list) or **Ignore** (continues to Pack an order).
- **Photos in the customer report:** every evidence photo shows as a thumbnail (no tap needed);
  tapping one opens a full-screen viewer with pinch / double-tap / +/− zoom, swipe or arrows to
  move between photos, and "Open original". The app's "Preview what the customer sees" works the
  same way. The sample report (`?c=DEMO`) includes sample photos to try it.
