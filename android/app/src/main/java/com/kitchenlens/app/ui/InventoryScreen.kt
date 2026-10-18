package com.kitchenlens.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.kitchenlens.app.AppViewModel
import com.kitchenlens.app.data.INVENTORY_CATEGORIES
import com.kitchenlens.app.data.InventoryBill
import com.kitchenlens.app.data.InventoryPhoto
import com.kitchenlens.app.util.BillReader
import com.kitchenlens.app.util.Dates
import com.kitchenlens.app.util.Gstin
import com.kitchenlens.app.util.ImageTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private const val MAX_PHOTOS_PER_CATEGORY = 6
private const val BILL_TARGET = "__BILL__"
private val SUPPLIER_TYPES = listOf("Local market", "Wholesaler", "Online supplier", "Farm")

/** A supplier bill being filled in on screen. */
private data class BillDraft(
    val id: String,
    val file: File,
    val categories: Set<String>,
    val date: String = "",
    val gstin: String = "",
    val note: String? = null,
)

/**
 * Food inventory arrival: several categories at once, several photos per
 * category, and any number of optional supplier bills. One bill can cover
 * one category, a few, or all of them.
 */
@Composable
fun InventoryScreen(vm: AppViewModel, onDone: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val kitchen by vm.kitchen.collectAsStateWithLifecycle()

    var cats by remember { mutableStateOf(setOf<String>()) }
    val photos = remember { mutableStateMapOf<String, List<InventoryPhoto>>() }
    val bills = remember { mutableStateListOf<BillDraft>() }
    var supplier by remember { mutableStateOf(setOf<String>()) }
    var staffName by remember { mutableStateOf(vm.lastPackerName) }

    var capture by remember { mutableStateOf<String?>(null) } // a category, BILL_TARGET, or null
    var working by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }

    val k = kitchen
    if (k == null) { CenteredLoading(); return }

    /** Categories in the standard order, limited to the ones selected. */
    val selected = INVENTORY_CATEGORIES.filter { it in cats }

    fun updateBill(id: String, change: (BillDraft) -> BillDraft) {
        val i = bills.indexOfFirst { it.id == id }
        if (i >= 0) bills[i] = change(bills[i])
    }

    // ---- full-screen camera ----
    val target = capture
    if (target != null) {
        val forBill = target == BILL_TARGET
        CameraCapture(
            mode = CaptureMode.PHOTO,
            hint = if (forBill) "Photograph the supplier bill so the date and GST number are readable."
            else "Photograph the $target as it arrives. Keep spice labels and recipe cards out of the frame.",
            onCancel = { capture = null },
            onCaptured = { file, _ ->
                capture = null
                working = true
                scope.launch {
                    try {
                        val (small, hash) = withContext(Dispatchers.Default) { ImageTools.compress(file, context.cacheDir) }
                        if (forBill) {
                            val id = UUID.randomUUID().toString()
                            bills.add(BillDraft(id, small, cats, note = "Reading the bill…"))
                            val scan = runCatching { BillReader.read(context, small) }.getOrNull()
                            updateBill(id) { b ->
                                b.copy(
                                    date = scan?.date ?: b.date,
                                    gstin = scan?.gstin ?: b.gstin,
                                    note = when {
                                        scan?.date != null && scan.gstin != null -> "Date and GST number read automatically. Please check them."
                                        scan?.date != null -> "Date read automatically. Please check it."
                                        scan?.gstin != null -> "GST number read automatically. Please type the date."
                                        else -> "Could not read the bill. You can type the date, or leave it blank."
                                    },
                                )
                            }
                        } else {
                            val sessionHashes = photos.values.flatten().map { it.hash }
                            if (ImageTools.isDuplicate(hash, k.recentHashes + sessionHashes)) {
                                error = "This photo looks the same as one already taken. Please take a fresh photo."
                            } else {
                                photos[target] = (photos[target] ?: emptyList()) + InventoryPhoto(small, hash)
                                error = null
                            }
                        }
                    } catch (e: Exception) {
                        error = e.message
                    } finally { working = false }
                }
            },
        )
        return
    }

    /** "Reset": clears categories, photos, bills and supplier immediately. */
    fun resetScreen() {
        cats = setOf(); photos.clear(); bills.clear(); supplier = setOf(); error = null
        staffName = vm.lastPackerName
    }

    fun submit() {
        error = null
        val missingPhotos = selected.firstOrNull { photos[it].isNullOrEmpty() }
        val billProblem = bills.withIndex().firstNotNullOfOrNull { (i, b) ->
            val n = i + 1
            when {
                b.categories.none { it in cats } -> "Bill $n: choose which categories it covers, or remove it"
                b.date.isNotBlank() && Dates.parse(b.date) == null -> "Bill $n: date must be dd-mm-yyyy, or leave it blank"
                else -> Gstin.formError(b.gstin, required = false)?.let { "Bill $n: $it" }
            }
        }
        error = when {
            selected.isEmpty() -> "Choose at least one category that arrived"
            missingPhotos != null -> "Add at least one photo for $missingPhotos"
            staffName.isBlank() -> "Enter your name"
            else -> billProblem
        }
        if (error != null) return

        val photoMap = selected.associateWith { photos[it].orEmpty() }
        val billList = bills.map { b ->
            InventoryBill(b.file, b.categories.filter { it in cats }.toSet(), b.date.trim(), Gstin.normalize(b.gstin))
        }
        working = true
        progress = 0f
        vm.lastPackerName = staffName.trim()
        scope.launch {
            try {
                vm.repo.saveInventory(
                    kitchen = k, staffName = staffName, supplierType = supplier.firstOrNull(),
                    photos = photoMap, bills = billList, onProgress = { progress = it },
                )
                onDone()
            } catch (e: Exception) {
                error = "Upload failed: ${e.message}. Check internet and try again."
            } finally { working = false }
        }
    }

    Scaffold(
        topBar = { BackBar("Food inventory arrival", onReset = { resetScreen() }, onBack = onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Choose every category that arrived, then take one or more photos of each. " +
                    "Customers see the category and when it arrived, never item names or quantities.",
                style = MaterialTheme.typography.bodyMedium,
            )
            ChipPicker("Categories received", INVENTORY_CATEGORIES, cats, multi = true) { cats = it }

            // ---- photos per category ----
            selected.forEach { cat ->
                val list = photos[cat].orEmpty()
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("$cat · ${list.size} photo${if (list.size == 1) "" else "s"}", fontWeight = FontWeight.SemiBold)
                        if (list.isNotEmpty()) {
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                list.forEach { p ->
                                    Thumb(p.file) { photos[cat] = list - p }
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = { capture = cat },
                            enabled = !working && list.size < MAX_PHOTOS_PER_CATEGORY,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.AddAPhoto, null); Spacer(Modifier.size(8.dp))
                            Text(
                                when {
                                    list.isEmpty() -> "Take photo"
                                    list.size < MAX_PHOTOS_PER_CATEGORY -> "Add another photo"
                                    else -> "Maximum $MAX_PHOTOS_PER_CATEGORY photos"
                                }
                            )
                        }
                    }
                }
            }

            // ---- supplier bills (optional) ----
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Supplier bills (optional)", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Add a bill if you have one: it improves customer trust, but you can save without it. " +
                            "Add one bill per supplier. If a single bill covers everything, tap \"Covers all categories\". " +
                            "Bill photos and supplier GST numbers stay private; customers only see the bill date.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    bills.forEachIndexed { i, b ->
                        if (i > 0) HorizontalDivider()
                        BillEditor(
                            number = i + 1,
                            bill = b,
                            selectedCategories = selected,
                            onChange = { changed -> updateBill(b.id) { changed } },
                            onRemove = { bills.removeAll { it.id == b.id } },
                        )
                    }
                    OutlinedButton(
                        onClick = { capture = BILL_TARGET },
                        enabled = !working && selected.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Receipt, null); Spacer(Modifier.size(8.dp))
                        Text(if (bills.isEmpty()) "Add a bill" else "Add another bill")
                    }
                    if (selected.isEmpty()) {
                        Text("Choose the categories first, then add bills.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            ChipPicker("Bought from (optional)", SUPPLIER_TYPES, supplier, multi = false) { supplier = it }
            OutlinedTextField(
                staffName, { staffName = it }, label = { Text("Recorded by (your name)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            ErrorText(error)
            if (working && progress > 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { submit() }, enabled = !working, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                val count = selected.sumOf { photos[it].orEmpty().size }
                Text(if (working) "Uploading…" else "Save $count photo${if (count == 1) "" else "s"} and ${bills.size} bill${if (bills.size == 1) "" else "s"}")
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun Thumb(file: File, onRemove: () -> Unit) {
    Box(Modifier.size(84.dp)) {
        AsyncImage(
            model = file, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(84.dp).clip(RoundedCornerShape(10.dp)),
        )
        IconButton(
            onClick = onRemove,
            modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(26.dp)
                .background(Color(0xAA000000), CircleShape),
        ) { Icon(Icons.Filled.Close, contentDescription = "Remove photo", tint = Color.White, modifier = Modifier.size(16.dp)) }
    }
}

@Composable
private fun BillEditor(
    number: Int,
    bill: BillDraft,
    selectedCategories: List<String>,
    onChange: (BillDraft) -> Unit,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = bill.file, contentDescription = "Bill $number", contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Bill $number", fontWeight = FontWeight.SemiBold)
                if (bill.note != null) Text(bill.note, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onRemove) { Text("Remove") }
        }
        val covered = bill.categories.filter { it in selectedCategories }.toSet()
        ChipPicker("This bill covers", selectedCategories, covered, multi = true) { onChange(bill.copy(categories = it)) }
        TextButton(onClick = { onChange(bill.copy(categories = selectedCategories.toSet())) }) {
            Text(if (covered.size == selectedCategories.size) "✓ Covers all categories" else "Covers all categories")
        }
        OutlinedTextField(
            bill.date, { onChange(bill.copy(date = it)) },
            label = { Text("Bill date (dd-mm-yyyy, optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            bill.gstin, { onChange(bill.copy(gstin = Gstin.normalize(it))) },
            label = { Text("Supplier GST number (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            supportingText = {
                val msg = Gstin.formError(bill.gstin, required = false) ?: Gstin.warning(bill.gstin)
                if (msg != null) Text(msg) else Text("15 characters, e.g. 29ABCDE1234F1ZW")
            },
            isError = Gstin.formError(bill.gstin, required = false) != null,
        )
    }
}
