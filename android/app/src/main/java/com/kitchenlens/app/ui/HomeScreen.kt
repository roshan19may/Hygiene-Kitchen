@file:OptIn(ExperimentalMaterial3Api::class)

package com.kitchenlens.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kitchenlens.app.AppViewModel
import com.kitchenlens.app.data.INVENTORY_CATEGORIES
import com.kitchenlens.app.data.Kitchen
import com.kitchenlens.app.data.Section
import com.kitchenlens.app.data.latestKey
import com.kitchenlens.app.util.HygieneScore

/** Kitchen Hygiene proof sections with nothing recorded yet (inventory counts if any category is recorded). */
private fun missingSections(k: Kitchen): List<Section> = listOfNotNull(
    Section.INVENTORY.takeIf { k.latest.keys.none { it.startsWith("INV_") } },
    Section.UTENSILS.takeIf { k.latest[Section.UTENSILS.name] == null },
    Section.STAFF.takeIf { k.latest[Section.STAFF.name] == null },
    Section.PEST.takeIf { k.latest[Section.PEST.name] == null },
)

private data class Status(val color: Color, val text: String)

private fun statusFor(kitchen: Kitchen, section: Section, now: Long): Status {
    val hour = 3_600_000L
    if (section == Section.INVENTORY) {
        val entries = INVENTORY_CATEGORIES.mapNotNull { cat ->
            (kitchen.latest[latestKey(section, cat)]?.get("at") as? Number)?.toLong()?.let { cat to it }
        }
        if (entries.isEmpty()) return Status(Chilli, "Not recorded yet")
        val newest = entries.maxBy { it.second }
        val color = if (now - newest.second <= section.freshForHours * hour) Ok else Amber
        return Status(color, "${newest.first} ${timeAgo(newest.second, now)} · ${entries.size} categories logged")
    }
    val entry = kitchen.latest[section.name] ?: return Status(Chilli, "Not recorded yet")
    val at = (entry["at"] as? Number)?.toLong() ?: return Status(Chilli, "Not recorded yet")
    if (section == Section.PEST) {
        val due = ((entry["details"] as? Map<*, *>)?.get("nextDueMillis") as? String)?.toLongOrNull()
        val dueText = (entry["details"] as? Map<*, *>)?.get("nextDueDate") as? String ?: "-"
        val color = when {
            due == null -> Amber
            now <= due -> Ok
            else -> Chilli
        }
        return Status(color, "Last service ${timeAgo(at, now)} · next due $dueText")
    }
    val age = now - at
    val color = when {
        age <= section.freshForHours * hour -> Ok
        age <= section.freshForHours * 2 * hour -> Amber
        else -> Chilli
    }
    return Status(color, "Updated ${timeAgo(at, now)}")
}

@Composable
fun HomeScreen(
    vm: AppViewModel,
    onSection: (Section) -> Unit,
    onPack: () -> Unit,
    onOrders: () -> Unit,
    onEditKitchen: () -> Unit,
) {
    val kitchen by vm.kitchen.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val lastReset by vm.lastReset.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var proofSectionY by remember { mutableIntStateOf(0) }
    var missingWarning by remember { mutableStateOf<List<Section>?>(null) }

    /** Pack an order: warns first when Kitchen Hygiene proof sections are not entered. */
    fun packWithCheck() {
        val k = kitchen ?: return
        val missing = missingSections(k)
        if (missing.isEmpty()) onPack() else missingWarning = missing
    }

    missingWarning?.let { missing ->
        AlertDialog(
            onDismissRequest = { missingWarning = null },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = Chilli) },
            title = { Text("Kitchen Hygiene proof not entered") },
            text = {
                Text(
                    "These details are not entered yet:\n" +
                        missing.joinToString("\n") { "• " + it.title } +
                        "\n\nPacking without them may affect future regular orders, because customers " +
                        "will not see this proof when they scan the seal."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    missingWarning = null
                    scope.launch { scroll.animateScrollTo(proofSectionY) }
                }) { Text("Go back") }
            },
            dismissButton = {
                TextButton(onClick = { missingWarning = null; onPack() }) { Text("Ignore") }
            },
        )
    }
    val message by vm.message.collectAsStateWithLifecycle()
    val k = kitchen
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(k?.name ?: "KitchenLens", fontWeight = FontWeight.Bold)
                        if (k != null) Text("${k.locality}, ${k.city}", style = MaterialTheme.typography.bodySmall)
                    }
                },
                actions = {
                    IconButton(onClick = onEditKitchen) { Icon(Icons.Filled.Storefront, "Kitchen details") }
                    IconButton(onClick = onOrders) { Icon(Icons.AutoMirrored.Filled.ListAlt, "Recent orders") }
                    IconButton(onClick = { vm.signOut() }) { Icon(Icons.AutoMirrored.Filled.Logout, "Sign out") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        if (k == null) { CenteredLoading(); return@Scaffold }
        val now = System.currentTimeMillis()
        val readiness = HygieneScore.compute(k.latest, tempOk = true, now = now)
        Column(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp).verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (vm.repo.isDemo) DemoBanner()
            message?.let { m ->
                Card(colors = CardDefaults.cardColors(containerColor = Chilli.copy(alpha = 0.12f))) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(m, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { vm.dismissMessage() }) { Text("OK") }
                    }
                }
            }
            if (lastReset != null) {
                Card(colors = CardDefaults.cardColors(containerColor = LeafLight)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("New order started", fontWeight = FontWeight.Bold)
                        Text(
                            "The order form and today's hygiene records were cleared. Record the hygiene sections below, " +
                                "then pack the order. Seals already printed are not affected.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row {
                            TextButton(onClick = { vm.undoNewOrder() }) { Text("Undo") }
                            TextButton(onClick = { vm.dismissUndo() }) { Text("OK") }
                        }
                    }
                }
            }
            if (k.gstin.isBlank()) {
                Text(
                    "Tip: add your GST number in Kitchen details (shop icon above) so customers see it on every seal.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.clickable { onEditKitchen() },
                )
            }

            Card(colors = CardDefaults.cardColors(containerColor = Leaf)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ready-to-pack score: $readiness / 100", color = Color.White, fontWeight = FontWeight.Bold)
                    Text(
                        "Customers see this score on every order (temperature at 65°C or above, per FSSAI guideline, adds the last 20 points).",
                        color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall,
                    )
                    val d = draft
                    if (d != null) {
                        Text(
                            "Order in progress: ${d.platform.ifBlank { "platform not chosen" }}" +
                                (if (d.orderRef.isNotBlank()) " #${d.orderRef}" else "") +
                                " · saved ${timeAgo(d.savedAt)}",
                            color = Color.White, style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Button(
                        onClick = { packWithCheck() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = Turmeric, contentColor = Color(0xFF3A2A00),
                        ),
                    ) {
                        Icon(Icons.Filled.QrCode2, null)
                        Spacer(Modifier.size(8.dp))
                        Text(if (d != null) "Continue this order" else "Pack an order + QR seal", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = { vm.startNewOrder() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    ) {
                        Icon(Icons.Filled.RestartAlt, null)
                        Spacer(Modifier.size(8.dp))
                        Text("New order: clear everything")
                    }
                }
            }

            Text(
                "Kitchen Hygiene proof",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.onGloballyPositioned { proofSectionY = it.positionInParent().y.toInt() },
            )
            listOf(Section.INVENTORY, Section.UTENSILS, Section.STAFF, Section.PEST).forEach { s ->
                val st = statusFor(k, s, now)
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onSection(s) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).background(st.color, CircleShape))
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.title, fontWeight = FontWeight.SemiBold)
                            Text(st.text, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Record", color = Leaf, fontWeight = FontWeight.Bold)
                    }
                }
            }
            OutlinedButton(onClick = onOrders, modifier = Modifier.fillMaxWidth()) { Text("Recent orders and QR seals") }
            Spacer(Modifier.size(24.dp))
        }
    }
}
