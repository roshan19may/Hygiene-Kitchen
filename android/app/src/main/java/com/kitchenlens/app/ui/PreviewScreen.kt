package com.kitchenlens.app.ui

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.kitchenlens.app.AppViewModel
import com.kitchenlens.app.data.INVENTORY_CATEGORIES
import com.kitchenlens.app.data.Section
import com.kitchenlens.app.data.latestKey

/** Shows the kitchen exactly what a customer sees after scanning the seal. */
@Composable
fun PreviewScreen(vm: AppViewModel, code: String, onBack: () -> Unit) {
    var order by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(code) {
        order = runCatching { vm.repo.getOrder(code) }.onFailure { error = it.message }.getOrNull()
        if (order == null && error == null) error = "Order not found"
    }
    Scaffold(topBar = { BackBar("Customer view", onBack) }, containerColor = MaterialTheme.colorScheme.background) { pad ->
        val o = order
        if (o == null) {
            Column(Modifier.padding(pad).padding(16.dp)) { if (error != null) ErrorText(error) else CenteredLoading() }
            return@Scaffold
        }
        CustomerView(o, Modifier.padding(pad))
    }
}

@Suppress("UNCHECKED_CAST")
@Composable
private fun CustomerView(o: Map<String, Any?>, modifier: Modifier) {
    val packedAt = (o["packedAtMillis"] as? Number)?.toLong() ?: 0L
    val temp = (o["tempC"] as? Number)?.toDouble() ?: 0.0
    val tempOk = o["tempOk"] as? Boolean ?: false
    val score = (o["score"] as? Number)?.toInt() ?: 0
    val snap = o["snapshot"] as? Map<String, Map<String, Any?>> ?: emptyMap()

    Column(
        modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(o["kitchenName"] as? String ?: "", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "${o["kitchenLocality"] ?: ""}, ${o["kitchenCity"] ?: ""} · FSSAI ${o["fssai"] ?: ""}" +
                ((o["gstin"] as? String)?.takeIf { it.isNotBlank() }?.let { " · GSTIN $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "${o["platform"]} order ··${o["orderRefLast4"]} · packed ${formatDateTime(packedAt)} by ${o["packerName"]}",
            style = MaterialTheme.typography.bodyMedium,
        )

        ClipPlayer(o["clipUrl"] as? String)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat(Modifier.weight(1f), "%.0f°C".format(temp), "Temperature at packing", if (tempOk) Ok else Chilli)
            Stat(Modifier.weight(1f), "$score/100", "Hygiene score", if (score >= 80) Ok else if (score >= 50) Amber else Chilli)
        }

        Text("Kitchen hygiene at the time of your order", fontWeight = FontWeight.Bold)
        val rows = buildList {
            INVENTORY_CATEGORIES.forEach { cat -> snap[latestKey(Section.INVENTORY, cat)]?.let { add(it) } }
            listOf(Section.UTENSILS, Section.STAFF, Section.PEST).forEach { s -> snap[s.name]?.let { add(it) } }
        }
        if (rows.isEmpty()) Text("No hygiene records were logged before this order.")
        rows.forEach { ProofRow(it, packedAt) }

        Text(
            "This page shows the kitchen's own recorded process. It is not a food-safety certification.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
        )
        Spacer(Modifier.size(24.dp))
    }
}

@Composable
private fun Stat(modifier: Modifier, value: String, label: String, color: Color) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun detailsLine(entry: Map<String, Any?>): String {
    val d = entry["details"] as? Map<*, *> ?: return ""
    val photos = (d["photoCount"] as? String)?.toIntOrNull()
    val billCount = (d["billCount"] as? String)?.toIntOrNull()
    return listOfNotNull(
        photos?.takeIf { it > 1 }?.let { "$it photos" },
        d["billDate"]?.let { "bill dated $it" },
        billCount?.takeIf { it > 1 }?.let { "$it bills" },
        d["supplierGst"]?.let { "GST-registered supplier" },
        d["supplierType"]?.toString(),
        d["cleaningMedium"]?.toString(),
        d["checklist"]?.toString(),
        d["missing"]?.let { "not shown: $it" },
        d["vendor"]?.let { "by $it" },
        d["nextDueDate"]?.let { "next due $it" },
    ).joinToString(" · ")
}

@Composable
private fun ProofRow(entry: Map<String, Any?>, packedAt: Long) {
    var open by remember { mutableStateOf(false) }
    val at = (entry["at"] as? Number)?.toLong() ?: 0L
    val isVideo = entry["mediaType"] == "video"
    Card(Modifier.fillMaxWidth().clickable { open = !open }) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(Leaf, CircleShape))
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry["label"] as? String ?: "", fontWeight = FontWeight.SemiBold)
                    Text(beforePacking(at, packedAt), style = MaterialTheme.typography.bodySmall)
                }
                Text(if (open) "Hide" else if (isVideo) "▶ Watch" else "View", color = Leaf, fontWeight = FontWeight.Bold)
            }
            val line = detailsLine(entry)
            if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodySmall)
            if (open) {
                val url = entry["mediaUrl"] as? String
                val urls = (entry["mediaUrls"] as? List<*>)?.filterIsInstance<String>().orEmpty().ifEmpty { listOfNotNull(url) }
                if (isVideo) ClipPlayer(url)
                else urls.forEach { u ->
                    AsyncImage(
                        model = u, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(10.dp)),
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipPlayer(url: String?) {
    if (url == null) return
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                setVideoURI(Uri.parse(url))
                setOnPreparedListener { mp -> mp.isLooping = true; mp.setVolume(0f, 0f); start() }
            }
        },
        modifier = Modifier.fillMaxWidth().aspectRatio(9f / 16f).clip(RoundedCornerShape(12.dp)),
    )
}
