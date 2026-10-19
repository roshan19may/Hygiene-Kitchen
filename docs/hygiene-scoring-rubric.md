# KitchenLens hygiene rating — version 2

Implemented identically in the Android app (`util/HygieneScore.kt`, `util/KitchenStats.kt`),
the iPhone app (`Rating.swift`) and read by the customer report (`web/index.html`) and the
kitchen page (`web/kitchen.html`). Unit tests: `android/app/src/test/.../LogicTest.kt`.

Version 2 changes the weights in `Points_Allocation_System.xlsx`: points that were given for
typed fields (names, shift, supplier type) now go to proof, records expire, and "not stocked"
categories are left out.

## 1. Order rating (shown top right of every customer report)

| Section | Item | Points |
|---|---|---:|
| Food inventory (32) | Vegetables 5 · Meat & Fish 5 · Dairy 3 · Spices 3 · Cooking oil 6 · Grains & Pulses 3 (photos) | 25 |
| | Supplier bill linked to a current delivery | 7 |
| Utensils cleaning (15) | Cleaning methods: 3 or more = 5, two = 3, one = 1 | 5 |
| | Wash video | 10 |
| Staff hygiene (15) | 1 point per check shown (hand-wash, cap, gloves, apron, mask) | 5 |
| | Hand-wash and kit video | 10 |
| Pest control (15) | Vendor | 3 |
| | Service recency: under 7 days 4, under 14 days 3, under 30 days 2, older 1 | 4 |
| | Service report photo | 8 |
| Packing (23) | Temperature meets guideline (hot ≥ 65°C, cold ≤ 5°C) | 8 |
| | Packing video | 15 |
| **Total** | | **100** |

**Required, but 0 points:** recorded-by name, shift, staff on shift, supplier ("bought from"), packer name.

**Expiry** (full credit → half credit → 0):

| Record | Full credit | Half credit until |
|---|---|---|
| Utensils, staff hygiene | 12 h (one shift) | 24 h |
| Vegetables, meat & fish, dairy | 48 h | 96 h |
| Spices, cooking oil, grains & pulses | 7 days | 14 days |
| Supplier bill | follows the delivery it is linked to | |
| Pest control | until the next-due date | 7 days after it |

**Not stocked:** categories a kitchen marks as never stocked (Kitchen details) are removed
from the maximum, and the percentage is calculated on what applies (e.g. a vegetarian kitchen
is scored out of 95).

**Percentage → customer display**

| % | Words | Stars (half-star steps, % ÷ 20) |
|---|---|---|
| 90–100 | Excellent | 4.5–5 |
| 80–89 | Very good | 4–4.5 |
| 70–79 | Good | 3.5–4 |
| 50–69 | Fair | 2.5–3.5 |
| 0–49 | Needs improvement | 0–2.5 |

**When no stars are shown**

| Situation | Badge text |
|---|---|
| Packing temperature failed | "Rating withheld – Food below 65°C at packing" (or above 5°C for cold food); no percentage |
| A section was never recorded | "Not rated – Some hygiene records missing" |
| Seal made before version 2 | "Not rated – Issued before the rating system" |

## 2. Kitchen rating (last 30 days)

Shown on every report under the header, on the kitchen page and on the hygiene badge.

```
kitchen % = 75% × average order percentage
          + 15% × consistency (share of orders rated, i.e. complete and temperature passed)
          + 10% × spot checks answered
```
Before any spot check has been requested, the first two parts are rescaled to 100%.
A kitchen needs **5 sealed orders** in 30 days before its rating is shown.
Trend: average of the last 7 days vs days 8–30 (±3 points = improving / slipping, else steady;
needs 3 orders in each period).

**Spot checks:** once a day, on days the kitchen packs orders, the app asks for a 10-second clip
of the wash area and packing table at a time between 11:00 and 21:30 that only the app knows
(FNV-1a hash of kitchen id + date). It counts if recorded within 30 minutes.

**Customer feedback** (live mode): after scanning, customers answer "Was the seal intact?" and
"Was the food at the right temperature?" — one answer per seal, enforced by the database rules.
Shown as percentages once 5 answers exist; not mixed into the kitchen %.

## 3. Other trust signals

- **Verified visit:** set only by KitchenLens staff in `publicKitchens/{kitchenId}.verifiedVisit`
  (`{ date: "2026-10-07", by: "KitchenLens" }`). Kitchens cannot write it (database rules).
  Shown for 60 days.
- **FSSAI Hygiene Rating:** the kitchen may enter its official rating (1–5) and audit date. It is
  shown only as "declared by the kitchen", with a link to FSSAI FoSCoS, and flagged when the audit
  is over 2 years old. KitchenLens uses stars, never FSSAI's smiley design, and every page says
  the KitchenLens rating is not the FSSAI Hygiene Rating.

## 4. Known limits

- All evidence is recorded by the kitchen. Expiry, spot checks, customer feedback and paid
  verified visits reduce, but do not remove, the chance of staged proof.
- The 30-day statistics are calculated in the kitchen's app and published to `publicKitchens`.
  For stronger tamper resistance, move this calculation to a Cloud Function.
- A kitchen could answer feedback for its own seals; moving feedback behind a Cloud Function with
  rate limits, or asking customers for a one-time code from the platform order, would close this.
- The rating measures documented process evidence; it does not guarantee that a meal is safe.
