package com.kitchenlens.app.data

/** The five hygiene sections a kitchen records. */
enum class Section(val title: String, val subtitle: String, val media: MediaType, val freshForHours: Int) {
    INVENTORY("Food inventory details", "Photo of produce + supplier bill", MediaType.PHOTO, 48),
    UTENSILS("Cooking Utensils cleaning details", "Short clip of the wash + cleaning medium", MediaType.VIDEO, 12),
    STAFF("Chef, Support staff cooking hygiene", "Hand-wash, cap, gloves, apron check", MediaType.VIDEO, 12),
    PEST("Pest control details", "Service report photo + next due date", MediaType.PHOTO, 24 * 30),
    PACKING("Packing + temperature", "Per-order Hygiene Food Packing clip and Order QR seal", MediaType.VIDEO, 0);
}

enum class MediaType(val value: String) { PHOTO("photo"), VIDEO("video") }

/** Inventory is shown to customers by CATEGORY only, never item names or quantities. */
val INVENTORY_CATEGORIES = listOf("Vegetables", "Meat & Fish", "Dairy", "Spices", "Cooking oil", "Grains & Pulses")
val PERISHABLE_CATEGORIES = setOf("Vegetables", "Meat & Fish", "Dairy")

val CLEANING_MEDIA = listOf("Detergent", "Hot water", "Food-grade sanitiser", "Scrub pad", "Dishwasher machine", "Air-dried on rack")
val STAFF_CHECKLIST = listOf("Hand-wash with soap", "Cap / hairnet", "Gloves", "Clean apron", "Mask")
val SHIFTS = listOf("Morning", "Afternoon", "Night")
val PLATFORMS = listOf("Swiggy", "Zomato", "Ownly", "Toing", "Direct / Other")
val CITIES = listOf("Bengaluru", "Mumbai", "Delhi NCR", "Hyderabad", "Chennai", "Pune", "Kolkata", "Ahmedabad")
val TIERS = listOf("Up to 500 sq ft", "500 – 1,500 sq ft", "1,500 – 3,000 sq ft")

/** Key used in the kitchen's "latest" map. Inventory is stored per category. */
fun latestKey(section: Section, category: String? = null): String =
    if (section == Section.INVENTORY && category != null) "INV_" + category.uppercase().replace(Regex("[^A-Z]"), "_")
    else section.name

data class Kitchen(
    val id: String = "",
    val name: String = "",
    val locality: String = "",
    val city: String = "",
    val fssai: String = "",
    val gstin: String = "",
    val tier: String = "",
    /** latestKey -> summary map (at, mediaUrl, mediaType, label, details) */
    val latest: Map<String, Map<String, Any?>> = emptyMap(),
    val recentHashes: List<String> = emptyList(),
)

/** One produce photo taken on the inventory screen. */
data class InventoryPhoto(val file: java.io.File, val hash: String)

/**
 * One supplier bill photo. A bill can cover several categories (or all of them).
 * The bill photo and supplier GSTIN stay private; customers only see the date.
 */
data class InventoryBill(
    val file: java.io.File,
    val categories: Set<String>,
    val billDate: String,
    val supplierGstin: String,
)

data class OrderSummary(
    val code: String,
    val platform: String,
    val orderRefLast4: String,
    val orderRef: String = "",
    val tempC: Double,
    val score: Int,
    val packedAtMillis: Long,
)
