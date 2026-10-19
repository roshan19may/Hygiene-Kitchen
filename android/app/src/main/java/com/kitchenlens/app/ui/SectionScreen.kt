package com.kitchenlens.app.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.kitchenlens.app.AppViewModel
import com.kitchenlens.app.data.CLEANING_MEDIA
import com.kitchenlens.app.data.MediaType
import com.kitchenlens.app.data.SHIFTS
import com.kitchenlens.app.data.STAFF_CHECKLIST
import com.kitchenlens.app.data.Section
import com.kitchenlens.app.util.ImageTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val SECTION_HINTS = mapOf(
    Section.UTENSILS to "Record the wash: scrubbing, rinsing in hot water or sanitiser, and the drying rack.",
    Section.STAFF to "Record each staff member washing hands with soap, then show cap, gloves and apron.",
    Section.PEST to "Photograph the pest-control service report showing the vendor and date.",
    Section.SPOT to "Spot check: slowly show the wash area, then the packing table, for about 10 seconds.",
)

private const val MIN_STAFF_CHECKS = 2

/** Tick-box list for the staff hygiene checks shown in the clip; at least two are required. */
@Composable
private fun StaffChecklist(selected: Set<String>, onChange: (Set<String>) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                "Staff hygiene checks shown in the clip (1 point each; select all 5 for full points)",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            STAFF_CHECKLIST.forEach { item ->
                val on = item in selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = on, role = Role.Checkbox) { onChange(if (it) selected + item else selected - item) }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = on, onCheckedChange = null)
                    Spacer(Modifier.size(8.dp))
                    Text(item)
                }
            }
            Text(
                "${selected.size} of ${STAFF_CHECKLIST.size} selected" +
                    if (selected.size < MIN_STAFF_CHECKS) ": select ${MIN_STAFF_CHECKS - selected.size} more" else "",
                color = if (selected.size < MIN_STAFF_CHECKS) Chilli else Ok,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

/** Records one of the four Kitchen Hygiene proof sections (inventory, utensils, staff, pest control). */
@Composable
fun SectionScreen(vm: AppViewModel, section: Section, onDone: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val kitchen by vm.kitchen.collectAsStateWithLifecycle()

    var staffName by remember { mutableStateOf(vm.lastPackerName) }
    var media by remember { mutableStateOf(setOf<String>()) }
    var checklist by remember { mutableStateOf(setOf<String>()) }
    var shift by remember { mutableStateOf(setOf<String>()) }
    var staffCount by remember { mutableStateOf("") }
    var vendor by remember { mutableStateOf("") }
    var serviceDate by remember { mutableStateOf(formatDate(System.currentTimeMillis())) }
    var nextDueDays by remember { mutableStateOf(setOf("30")) }

    var mainFile by remember { mutableStateOf<File?>(null) }
    var mainSeconds by remember { mutableIntStateOf(0) }
    var photoHash by remember { mutableStateOf<String?>(null) }
    var capturedAt by remember { mutableLongStateOf(0L) }

    var capturing by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }

    val k = kitchen
    if (k == null) { CenteredLoading(); return }

    // ---- full-screen camera when capturing ----
    if (capturing) {
        val photoMode = section.media == MediaType.PHOTO
        CameraCapture(
            mode = if (photoMode) CaptureMode.PHOTO else CaptureMode.VIDEO,
            hint = SECTION_HINTS[section] ?: "",
            onCancel = { capturing = false },
            onCaptured = { file, secs ->
                capturing = false
                if (!photoMode) {
                    mainFile = file; mainSeconds = secs
                    capturedAt = System.currentTimeMillis() - secs * 1000L
                    vm.repo.prefetch(k, file, video = true)   // start uploading now, while the form is filled
                } else {
                    working = true
                    scope.launch {
                        try {
                            val (small, hash) = withContext(Dispatchers.Default) { ImageTools.compress(file, context.cacheDir) }
                            if (ImageTools.isDuplicate(hash, k.recentHashes)) {
                                error = "This photo looks the same as an earlier upload. Please take a fresh photo."
                            } else {
                                mainFile = small; photoHash = hash; error = null
                                vm.repo.prefetch(k, small, video = false)
                            }
                        } catch (e: Exception) {
                            error = e.message
                        } finally { working = false }
                    }
                }
            },
        )
        return
    }

    /** "Reset": clears everything entered on this screen immediately. */
    fun resetScreen() {
        media = setOf(); checklist = setOf(); shift = setOf(); staffCount = ""; vendor = ""
        serviceDate = formatDate(System.currentTimeMillis()); nextDueDays = setOf("30")
        mainFile = null; mainSeconds = 0; photoHash = null; capturedAt = 0L; error = null
        staffName = vm.lastPackerName
    }

    fun submit() {
        error = null
        val f = mainFile
        val sectionError = when (section) {
            Section.INVENTORY -> null // handled by InventoryScreen
            Section.UTENSILS -> if (media.isEmpty()) "Choose the cleaning medium used" else if (shift.isEmpty()) "Choose the shift" else null
            Section.STAFF -> when {
                checklist.size < MIN_STAFF_CHECKS -> "Select at least $MIN_STAFF_CHECKS hygiene checks (${checklist.size} selected)"
                shift.isEmpty() -> "Choose the shift"
                staffCount.isBlank() -> "Enter the number of staff on shift"
                else -> null
            }
            Section.PEST -> when {
                vendor.isBlank() -> "Enter the pest-control vendor"
                parseDate(serviceDate) == null -> "Service date must be dd-mm-yyyy"
                else -> null
            }
            Section.PACKING, Section.SPOT -> null
        }
        error = when {
            staffName.isBlank() -> "Enter your name"
            f == null -> if (section.media == MediaType.PHOTO) "Take the photo first" else "Record the clip first"
            else -> sectionError
        }
        if (error != null || f == null) return

        val details = mutableMapOf<String, String>()
        if (section.media == MediaType.VIDEO && capturedAt > 0) details["recordedAt"] = capturedAt.toString()
        val key = section.name
        val label: String
        when (section) {
            Section.INVENTORY -> label = "Inventory" // handled by InventoryScreen
            Section.UTENSILS -> {
                label = "Utensils cleaned"
                details["cleaningMedium"] = media.joinToString(", ")
                details["shift"] = shift.first()
            }
            Section.STAFF -> {
                label = "Staff hygiene check"
                details["checklist"] = checklist.joinToString(", ")
                val missing = STAFF_CHECKLIST.filter { it !in checklist }
                if (missing.isNotEmpty()) details["missing"] = missing.joinToString(", ")
                if (staffCount.isNotBlank()) details["staffOnShift"] = staffCount
                details["shift"] = shift.first()
            }
            Section.PEST -> {
                label = "Pest control service"
                val serviced = parseDate(serviceDate)!!
                val due = serviced + nextDueDays.first().toLong() * 24 * 3_600_000L
                details["vendor"] = vendor.trim()
                details["serviceDate"] = serviceDate
                details["nextDueDate"] = formatDate(due)
                details["nextDueMillis"] = due.toString()
            }
            Section.PACKING -> label = "Packed"
            Section.SPOT -> label = "Spot check"
        }

        working = true
        progress = 0f
        vm.lastPackerName = staffName.trim()
        scope.launch {
            try {
                vm.repo.saveLog(
                    kitchen = k, section = section, key = key, staffName = staffName,
                    media = f, mediaType = section.media,
                    details = details, label = label, photoHash = photoHash,
                    onProgress = { progress = it },
                )
                onDone()
            } catch (e: Exception) {
                error = "Upload failed: ${e.message}. Check internet and try again."
            } finally { working = false }
        }
    }

    Scaffold(
        topBar = { BackBar(section.title, onReset = { resetScreen() }, onBack = onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(section.subtitle, style = MaterialTheme.typography.bodyMedium)
            Text(
                when (section) {
                    Section.UTENSILS -> "Worth 15 points: cleaning methods 5 (3 or more), wash video 10. Full points for 12 hours " +
                        "(one shift), half points up to 24 hours, then 0. Name and shift are required but earn no points."
                    Section.STAFF -> "Worth 15 points: 1 per hygiene check shown (up to 5), video 10. Full points for 12 hours " +
                        "(one shift), half points up to 24 hours, then 0. Name, shift and staff count are required."
                    Section.PEST -> "Worth 15 points: vendor 3, service recency 1–4, report photo 8. Counts until the next service " +
                        "date, half points for 7 days after it, then 0."
                    Section.SPOT -> "The app asks for one spot check a day at a random time. Recording it within 30 minutes " +
                        "raises your kitchen's 30-day rating."
                    else -> ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (section) {
                Section.INVENTORY -> Unit // handled by InventoryScreen
                Section.UTENSILS -> {
                    ChipPicker("Cleaning medium used (5 points for 3 or more)", CLEANING_MEDIA, media, multi = true) { media = it }
                    ChipPicker("Shift (required)", SHIFTS, shift, multi = false) { shift = it }
                }
                Section.STAFF -> {
                    StaffChecklist(checklist) { checklist = it }
                    ChipPicker("Shift (required)", SHIFTS, shift, multi = false) { shift = it }
                    OutlinedTextField(
                        staffCount, { staffCount = it.filter(Char::isDigit).take(2) },
                        label = { Text("Staff on this shift (required)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Section.PEST -> {
                    OutlinedTextField(vendor, { vendor = it }, label = { Text("Pest-control vendor (3 points)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(serviceDate, { serviceDate = it }, label = { Text("Service date (dd-mm-yyyy; 1–4 points by recency)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    ChipPicker("Next service due in (days)", listOf("15", "30", "60", "90"), nextDueDays, multi = false) { nextDueDays = it }
                }
                Section.PACKING, Section.SPOT -> Unit
            }

            // ---- main photo / clip ----
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val isPhoto = section.media == MediaType.PHOTO
                    Text(
                        when (section) {
                            Section.UTENSILS -> "Wash video (10 points; 5–20 s, no sound)"
                            Section.STAFF -> "Hand-wash and kit video (10 points; 5–20 s, no sound)"
                            Section.PEST -> "Service report photo (8 points)"
                            Section.SPOT -> "Spot-check clip (about 10 s, no sound)"
                            else -> if (isPhoto) "Photo proof" else "Video proof (5–20 s, no sound)"
                        }, fontWeight = FontWeight.SemiBold)
                    val f = mainFile
                    if (f != null) {
                        if (isPhoto) {
                            AsyncImage(
                                model = f, contentDescription = "Captured photo", contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(10.dp)),
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.CheckCircle, null, tint = Ok)
                                Spacer(Modifier.size(8.dp))
                                Text("Clip recorded ($mainSeconds s)")
                            }
                        }
                    }
                    OutlinedButton(onClick = { capturing = true }, enabled = !working, modifier = Modifier.fillMaxWidth()) {
                        Icon(if (isPhoto) Icons.Filled.CameraAlt else Icons.Filled.Videocam, null)
                        Spacer(Modifier.size(8.dp))
                        Text(if (f == null) (if (isPhoto) "Take photo" else "Record clip") else "Retake")
                    }
                }
            }

            OutlinedTextField(staffName, { staffName = it }, label = { Text(when (section) {
                    else -> "Recorded by (your name, required)"
                }) }, singleLine = true, modifier = Modifier.fillMaxWidth())

            ErrorText(error)
            if (working && progress > 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { submit() }, enabled = !working, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(if (working) "Uploading…" else "Save proof")
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}
