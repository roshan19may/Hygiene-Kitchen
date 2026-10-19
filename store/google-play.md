# Google Play listing (copy into Play Console)

## Main store listing
- **App name (30 max):** KitchenLens: Food Hygiene
- **Short description (80 max):** Hygiene proof for cloud kitchens. A QR seal shows customers how food was packed.
- **Full description:**

KitchenLens — Food Safety & Hygiene Verification helps cloud kitchens and restaurants show customers how their food was prepared and packed, without revealing any recipe.

Record your Kitchen Hygiene proof:
• Food inventory details: live photos of produce as it arrives, with optional supplier bills (the date and GST number are read automatically; bills stay private)
• Cooking utensils cleaning details: a short clip of the wash
• Chef and support staff cooking hygiene: hand-wash, cap, gloves and apron checks
• Pest control details: photo of the service report and next due date

Pack every order with a QR seal:
• Link the seal to the Swiggy, Zomato or other platform order number
• Record the food temperature (FSSAI guideline: hot food at 65°C or above)
• Record a 5–20 second Hygiene Food Packing clip
• Print the seal on a 58 mm thermal label and stick it across the bag opening

Customers scan the seal and see a clear report: packing clip, temperature, hygiene timeline and photos with zoom. No app is needed to scan.

Built for trust:
• Photos and clips are captured live in the app; gallery uploads are not allowed
• Every photo and clip carries its date and time
• Clips are recorded without sound, so recipe talk is never captured
• Duplicate photos are detected
• Records cannot be edited after saving

- **App category:** Business
- **Tags:** Food & drink, Business tools
- **Contact email:** your support email
- **Privacy policy URL:** `<WEB_BASE_URL>privacy.html`

## Graphics
- App icon 512×512 PNG: resize `ios/KitchenLens/Resources/Assets.xcassets/AppIcon.appiconset/icon-1024.png` to 512×512
- Feature graphic 1024×500 (make one with the logo and the line "Hygiene proof on every bag")
- Phone screenshots (at least 2, 1080×1920 or larger): Home, Pack an order, QR seal, customer report

## App content answers
| Question | Answer |
|---|---|
| Ads | No ads |
| App access | Restricted. Give the reviewer a test kitchen email and password (create one in your Firebase project) |
| Target audience | 18 and over |
| Content rating (IARC) | Business / utility, no objectionable content → Everyone |
| News app | No |
| Government app | No |
| Financial features | None |
| Health | No health features |
| Account deletion URL | `<WEB_BASE_URL>delete-account.html` |

## Data safety form
Data is encrypted in transit: **Yes**. Users can request deletion: **Yes** (in app and on the web page). Data shared with third parties: **No** (Firebase / Cloudinary are processors, not sharing).

| Data type | Collected | Purpose | Optional? |
|---|---|---|---|
| Email address | Yes | Account management | Required |
| Name (staff first names) | Yes | App functionality | Required |
| Photos | Yes | App functionality (hygiene proof shown to customers) | Required |
| Videos | Yes | App functionality (hygiene clips shown to customers) | Required |
| Other info: business name, FSSAI, GSTIN, order numbers | Yes | App functionality | Required |
| Audio | **No** (clips are saved without sound) | – | – |
| Location, contacts, device IDs, financial info | No | – | – |

## Personal developer accounts (created after Nov 2023)
Run a **closed test with at least 12 testers for 14 days** before you can apply for production.
Upload `app-release.aab` (from the GitHub run's **KitchenLens-play-store-bundle** artifact) to
**Testing → Closed testing**, add the testers' Gmail addresses, share the opt-in link, and keep them
installed for 14 days.
