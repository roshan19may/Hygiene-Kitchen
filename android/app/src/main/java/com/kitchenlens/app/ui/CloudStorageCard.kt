package com.kitchenlens.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kitchenlens.app.util.CloudMedia
import kotlinx.coroutines.launch

/**
 * Connects free Cloudinary storage so photos and clips play when a customer
 * scans the seal (demo mode). Tested with a tiny upload before saving.
 */
@Composable
fun CloudStorageCard(cloud: CloudMedia) {
    val scope = rememberCoroutineScope()
    var cloudName by remember { mutableStateOf(cloud.cloudName) }
    var preset by remember { mutableStateOf(cloud.preset) }
    var busy by remember { mutableStateOf(false) }
    var status by remember {
        mutableStateOf(if (cloud.isConfigured) "Connected. Photos and clips will show when seals are scanned." else null)
    }
    var ok by remember { mutableStateOf(cloud.isConfigured) }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Photos and clips on scanned seals", fontWeight = FontWeight.SemiBold)
            Text(
                "A QR code is too small to hold photos or videos. Connect free cloud storage so customers see them when they scan:\n" +
                    "1. Create a free account at cloudinary.com (no card needed).\n" +
                    "2. Copy the Cloud name shown on its dashboard.\n" +
                    "3. Settings → Upload → Upload presets → Add upload preset → Signing mode: Unsigned → Save, and copy the preset name.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                cloudName, { cloudName = it.trim(); ok = false },
                label = { Text("Cloud name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                preset, { preset = it.trim(); ok = false },
                label = { Text("Upload preset name (Unsigned)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            if (status != null) {
                Text(status!!, color = if (ok) Ok else Chilli, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                enabled = !busy && cloudName.isNotBlank() && preset.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    busy = true
                    status = "Testing the connection…"
                    ok = true
                    scope.launch {
                        try {
                            cloud.testConnection(cloudName, preset)
                            cloud.saveSettings(cloudName, preset)
                            ok = true
                            status = "Connected and saved. New seals will show photos and clips."
                        } catch (e: Exception) {
                            ok = false
                            status = e.message ?: "Could not connect. Check your internet, cloud name and preset."
                        } finally { busy = false }
                    }
                },
            ) { Text(if (busy) "Testing…" else "Test and save") }
        }
    }
}
