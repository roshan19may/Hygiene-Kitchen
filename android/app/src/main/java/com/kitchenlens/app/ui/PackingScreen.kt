package com.kitchenlens.app.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kitchenlens.app.AppViewModel
import com.kitchenlens.app.data.DuplicateOrderException
import com.kitchenlens.app.data.OrderDraft
import com.kitchenlens.app.data.PLATFORMS
import com.kitchenlens.app.util.HygieneScore
import kotlinx.coroutines.launch
import java.io.File

/** Platform for orders taken directly (phone, walk-in); the only one where an order number is optional. */
private val DIRECT = PLATFORMS.last()

/**
 * Per-order flow: platform + order number → food temperature → 5–20 s packing
 * clip → QR seal. The seal is linked to the platform order number.
 *
 * Progress is kept as a draft on the phone: "Save draft" stores it, leaving the
 * screen saves it automatically, and "New order" clears everything.
 *
 * Temperature is typed from the probe display for now. A Bluetooth probe can
 * later fill [tempText] automatically (see README, "Next steps").
 */
@Composable
fun PackingScreen(vm: AppViewModel, onCreated: (String) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val kitchen by vm.kitchen.collectAsStateWithLifecycle()
    val initial = remember { vm.draft.value ?: OrderDraft(packer = vm.lastPackerName) }

    var platform by remember { mutableStateOf(if (initial.platform.isBlank()) setOf() else setOf(initial.platform)) }
    var orderRef by remember { mutableStateOf(initial.orderRef) }
    var packer by remember { mutableStateOf(initial.packer.ifBlank { vm.lastPackerName }) }
    var foodType by remember { mutableStateOf(setOf(initial.foodType.ifBlank { "Hot" })) }
    var tempText by remember { mutableStateOf(initial.tempText) }
    var clip by remember { mutableStateOf(initial.clipPath?.let { File(it) }?.takeIf { it.exists() }) }
    var clipSeconds by remember { mutableIntStateOf(initial.clipSeconds) }
    var savedAt by remember { mutableLongStateOf(initial.savedAt) }
    var clipRecordedAt by remember { mutableLongStateOf(initial.clipRecordedAt) }

    var recording by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var existingCode by remember { mutableStateOf<String?>(null) }

    fun currentDraft() = OrderDraft(
        platform = platform.firstOrNull() ?: "",
        orderRef = orderRef,
        packer = packer,
        foodType = foodType.first(),
        tempText = tempText,
        clipPath = clip?.absolutePath,
        clipSeconds = clipSeconds,
        clipRecordedAt = clipRecordedAt,
    )

    /** Saves progress on the phone. Returns false when there was nothing to save. */
    fun saveDraft(): Boolean {
        val d = currentDraft()
        if (d.isEmpty) return false
        val saved = vm.saveDraft(d)
        clip = saved.clipPath?.let { File(it) }
        savedAt = saved.savedAt
        return true
    }

    /** "Reset": clears this order form (and its saved draft) immediately. */
    fun resetForm() {
        vm.clearDraft()
        platform = setOf(); orderRef = ""; foodType = setOf("Hot"); tempText = ""
        clip = null; clipSeconds = 0; clipRecordedAt = 0L; savedAt = 0L
        packer = vm.lastPackerName
        error = null; existingCode = null
    }

    fun leave() {
        if (!working) saveDraft()
        onBack()
    }
    BackHandler(enabled = !recording) { leave() }

    val k = kitchen
    if (k == null) { CenteredLoading(); return }

    if (recording) {
        CameraCapture(
            mode = CaptureMode.VIDEO,
            hint = "Show the sealed containers going into the bag and the bag being closed. Keep spices and recipe cards out of frame.",
            onCaptured = { f, s ->
                clip = f; clipSeconds = s; recording = false
                clipRecordedAt = System.currentTimeMillis() - s * 1000L
                saveDraft() // never lose a recorded clip
            },
            onCancel = { recording = false },
        )
        return
    }


    val temp = tempText.replace(',', '.').toDoubleOrNull()
    val tempOk = temp != null && HygieneScore.temperatureOk(foodType.first(), temp)
    val needsOrderNo = platform.firstOrNull() != DIRECT

    Scaffold(
        topBar = { BackBar("Pack an order", onReset = { resetForm() }) { leave() } },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---- draft status + New order ----
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (savedAt > 0) "Draft saved ${timeAgo(savedAt)}. You can leave and continue later from Home."
                        else "Fill in the order below. Use \"Save draft\" to keep progress and continue later.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = { vm.startNewOrder(); onBack() },
                        enabled = !working,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Chilli, contentColor = Color.White),
                    ) {
                        Icon(Icons.Filled.RestartAlt, null); Spacer(Modifier.size(8.dp))
                        Text("New order: clear everything", fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "Clears this order and today's hygiene records so you start from the beginning. " +
                            "\"Reset\" (top right) clears only this form. Seals already printed are not affected.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            ChipPicker("Order platform", PLATFORMS, platform, multi = false) { platform = it; existingCode = null }
            OutlinedTextField(
                orderRef, { orderRef = it.take(30); existingCode = null },
                label = { Text(if (needsOrderNo) "Platform order number (required)" else "Order number (optional)") },
                supportingText = { Text("The QR seal is linked to this number. Customers can also find their order by it.") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(packer, { packer = it }, label = { Text("Packed by (first name is shown)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Food temperature at packing", fontWeight = FontWeight.SemiBold)
                    ChipPicker("Food type", listOf("Hot", "Cold"), foodType, multi = false) { foodType = it }
                    OutlinedTextField(
                        tempText, { tempText = it.filter { c -> c.isDigit() || c == '.' || c == ',' || c == '-' }.take(5) },
                        label = { Text("Temperature shown on the probe (°C)") }, singleLine = true,
                        supportingText = {
                            Text(
                                if (foodType.first() == "Cold") "Enter the reading shown. Guideline: cold food at 5°C or below."
                                else "Enter the reading shown. FSSAI guideline: hot food at 65°C or above."
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (temp != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (tempOk) Icons.Filled.CheckCircle else Icons.Filled.Warning, null, tint = if (tempOk) Ok else Chilli)
                            Spacer(Modifier.size(8.dp))
                            Text(
                                when {
                                    tempOk -> "Meets the guideline"
                                    foodType.first() == "Hot" -> "Warning: below the 65°C FSSAI guideline. Reheat if you can. You can still continue; customers see the reading you enter."
                                    else -> "Warning: above the 5°C guideline. Chill if you can. You can still continue; customers see the reading you enter."
                                },
                                color = if (tempOk) Ok else Chilli,
                            )
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Packing clip (5–20 s, no sound)", fontWeight = FontWeight.SemiBold)
                    if (clip != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CheckCircle, null, tint = Ok)
                            Spacer(Modifier.size(8.dp))
                            Text("Clip recorded ($clipSeconds s) and saved in the draft")
                        }
                    }
                    OutlinedButton(onClick = { recording = true }, enabled = !working, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Videocam, null); Spacer(Modifier.size(8.dp))
                        Text(if (clip == null) "Record packing clip" else "Re-record")
                    }
                }
            }

            ErrorText(error)
            val dup = existingCode
            if (dup != null) {
                OutlinedButton(onClick = { resetForm(); onCreated(dup) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Open the existing seal for this order")
                }
            }
            if (working) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())

            OutlinedButton(
                onClick = {
                    error = if (saveDraft()) null else "Nothing to save yet. Fill in at least one field."
                },
                enabled = !working,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Icon(Icons.Filled.Save, null); Spacer(Modifier.size(8.dp)); Text("Save draft")
            }

            Button(
                enabled = !working,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(),
                onClick = {
                    val c = clip
                    existingCode = null
                    error = when {
                        platform.isEmpty() -> "Choose the order platform"
                        needsOrderNo && orderRef.isBlank() -> "Enter the ${platform.first()} order number"
                        packer.isBlank() -> "Enter the packer's name"
                        temp == null -> "Enter the temperature reading"
                        temp < -30 || temp > 120 -> "That temperature looks wrong. Check the probe."
                        c == null || !c.exists() -> "Record the packing clip"
                        else -> null
                    }
                    if (error == null && c != null && temp != null) {
                        saveDraft()
                        working = true
                        progress = 0f
                        vm.lastPackerName = packer.trim()
                        scope.launch {
                            try {
                                val order = vm.repo.createOrder(
                                    kitchen = k, platform = platform.first(), orderRef = orderRef.trim(),
                                    packerName = packer, foodType = foodType.first(), tempC = temp,
                                    clip = clip ?: c,
                                    clipRecordedAt = clipRecordedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                                    onProgress = { progress = it },
                                )
                                vm.clearDraft()
                                onCreated(order.code)
                            } catch (e: DuplicateOrderException) {
                                error = e.message
                                existingCode = e.existingCode
                            } catch (e: Exception) {
                                error = "Upload failed: ${e.message}. Your draft is saved; check internet and try again."
                            } finally { working = false }
                        }
                    }
                },
            ) { Text(if (working) "Uploading clip…" else "Create QR seal", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.size(24.dp))
        }
    }
}
