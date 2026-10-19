# Apple App Store listing (copy into App Store Connect)

## App information
- **Name (30 max):** KitchenLens: Food Hygiene
- **Subtitle (30 max):** Hygiene proof on every bag
- **Bundle ID:** com.kitchenlens.app
- **SKU:** kitchenlens-ios-1
- **Primary category:** Business · **Secondary:** Food & Drink
- **Age rating:** answer "None" to every question → 4+
- **Privacy policy URL:** `<WEB_BASE_URL>privacy.html`
- **Support URL:** `<WEB_BASE_URL>delete-account.html` (or your own support page)
- **Copyright:** 2026 <your registered business name>

## Version information
- **Promotional text (170 max):** Show customers how their food was packed, without revealing your recipes. Every delivery bag gets a QR seal with live hygiene proof.
- **Description:** use the full description in `google-play.md` (it fits the 4,000-character limit).
- **Keywords (100 max):** cloud kitchen,hygiene,food safety,FSSAI,QR seal,restaurant,packing,delivery,kitchen audit
- **Screenshots:** 6.9-inch iPhone (1320×2868) — at least 3: Home, Pack an order, QR seal, customer report. Take them in the iOS Simulator (Cmd+S) or on a phone.

## App Review information
- **Sign-in required:** Yes. Provide a test kitchen email and password (create it in your Firebase project; set up its kitchen profile first so the reviewer lands on Home).
- **Notes for the reviewer:**

> KitchenLens is a business tool for food kitchens. Staff record hygiene photos and short clips with the in-app camera (no gallery uploads, so proof is always live). Clips are saved without sound. Each order produces a QR seal label; scanning it opens a public web report for the customer. Account deletion: Kitchen details (shop icon, top right on Home) → Delete my account and data. Camera is required; on a device without a camera, the record buttons show a message.

## App Privacy ("nutrition label")
Data used to track you: **None**. Data linked to you:

| Category | Type | Purpose |
|---|---|---|
| Contact info | Email address | App functionality |
| Contact info | Name (staff first names) | App functionality |
| User content | Photos or videos | App functionality |
| User content | Other user content (business name, FSSAI, GSTIN, order numbers) | App functionality |

Not collected: location, health, financial info, contacts, browsing history, identifiers, usage data, diagnostics, audio.

## Export compliance
`ITSAppUsesNonExemptEncryption = NO` is already in the app (uses only standard HTTPS), so no question appears for each build.
