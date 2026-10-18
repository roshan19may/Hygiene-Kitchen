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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import com.kitchenlens.app.data.PLATFORMS
import com.kitchenlens.app.util.HygieneScore
import kotlinx.coroutines.launch
import java.io.File

/**
 * Per-order flow (~30 seconds): platform + order no. → food temperature →
 * 5–20 s packing clip → QR seal.
 *
 * Temperature is typed from the probe display for now. A Bluetooth probe can
 * later fill [tempText] automatically (see README, "Next steps").
 */
@Composable
fun PackingScreen(vm: AppViewModel, onCreated: (String) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val kitchen by vm.kitchen.collectAsStateWithLifecycle()

    var platform by remember { mutableStateOf(setOf<String>()) }
    var orderRef by remember { mutableStateOf("") }
    var packer by remember { mutableStateOf(vm.lastPackerName) }
    var foodType by remember { mutableStateOf(setOf("Hot")) }
    var tempText by remember { mutableStateOf("") }
    var clip by remember { mutableStateOf<File?>(null) }
    var clipSeconds by remember { mutableIntStateOf(0) }
    var recording by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }

    val k = kitchen ?: run { CenteredLoading(); return }

    if (recording) {
        CameraCapture(
            mode = CaptureMode.VIDEO,
            hint = "Show the sealed containers going into the bag and the bag being closed. Keep spices and recipe cards out of frame.",
            onCaptured = { f, s -> clip = f; clipSeconds = s; recording = false },
            onCancel = { recording = false },
        )
        return
    }

    val temp = tempText.replace(',', '.').toDoubleOrNull()
    val tempOk = temp != null && HygieneScore.temperatureOk(foodType.first(), temp)

    Scaffold(
        topBar = { BackBar("Pack an order", onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ChipPicker("Order platform", PLATFORMS, platform, multi = false) { platform = it }
            OutlinedTextField(
                orderRef, { orderRef = it.take(30) },
                label = { Text("Platform order number (customers see last 4 digits)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(packer, { packer = it }, label = { Text("Packed by (first name is shown)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Food temperature at packing", fontWeight = FontWeight.SemiBold)
                    ChipPicker("Food type", listOf("Hot", "Cold"), foodType, multi = false) { foodType = it }
                    OutlinedTextField(
                        tempText, { tempText = it.filter { c -> c.isDigit() || c == '.' || c == ',' || c == '-' }.take(5) },
                        label = { Text("Probe reading in °C") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (temp != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (tempOk) Icons.Filled.CheckCircle else Icons.Filled.Warning, null, tint = if (tempOk) Ok else Chilli)
                            Spacer(Modifier.size(8.dp))
                            Text(
                                when {
                                    tempOk -> "Safe temperature"
                                    foodType.first() == "Hot" -> "Below 60°C: reheat before packing. Customers will see this reading."
                                    else -> "Above 5°C: chill before packing. Customers will see this reading."
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
                            Text("Clip recorded ($clipSeconds s)")
                        }
                    }
                    OutlinedButton(onClick = { recording = true }, enabled = !working, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Videocam, null); Spacer(Modifier.size(8.dp))
                        Text(if (clip == null) "Record packing clip" else "Re-record")
                    }
                }
            }

            ErrorText(error)
            if (working) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Button(
                enabled = !working,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                onClick = {
                    val c = clip
                    error = when {
                        platform.isEmpty() -> "Choose the order platform"
                        packer.isBlank() -> "Enter the packer's name"
                        temp == null -> "Enter the temperature reading"
                        temp < -30 || temp > 120 -> "That temperature looks wrong. Check the probe."
                        c == null -> "Record the packing clip"
                        else -> null
                    }
                    if (error == null && c != null && temp != null) {
                        working = true
                        progress = 0f
                        vm.lastPackerName = packer.trim()
                        scope.launch {
                            try {
                                val order = vm.repo.createOrder(
                                    kitchen = k, platform = platform.first(), orderRef = orderRef.trim(),
                                    packerName = packer, foodType = foodType.first(), tempC = temp,
                                    clip = c, onProgress = { progress = it },
                                )
                                onCreated(order.code)
                            } catch (e: Exception) {
                                error = "Upload failed: ${e.message}. Check internet and try again."
                            } finally { working = false }
                        }
                    }
                },
            ) { Text(if (working) "Uploading clip…" else "Create QR seal", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.size(24.dp))
        }
    }
}
