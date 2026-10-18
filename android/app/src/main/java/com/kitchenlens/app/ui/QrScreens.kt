package com.kitchenlens.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kitchenlens.app.AppViewModel
import com.kitchenlens.app.BuildConfig
import com.kitchenlens.app.data.OrderSummary
import com.kitchenlens.app.util.QrLabel

fun scanUrl(code: String) = BuildConfig.SCAN_BASE_URL + code

@Composable
fun QrResultScreen(
    vm: AppViewModel,
    code: String,
    onPreview: () -> Unit,
    onPackNext: () -> Unit,
    onHome: () -> Unit,
) {
    val context = LocalContext.current
    var order by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(code) {
        order = runCatching { vm.repo.getOrder(code) }.onFailure { error = it.message }.getOrNull()
    }
    val o = order
    val kitchenName = o?.get("kitchenName") as? String ?: ""
    val temp = (o?.get("tempC") as? Number)?.toDouble()
    val tempText = if (temp != null) "Packed at %.0f°C".format(temp) else ""
    val label = remember(o) { QrLabel.labelBitmap(scanUrl(code), code, kitchenName, tempText) }

    Scaffold(topBar = { BackBar("QR seal ready", onHome) }, containerColor = MaterialTheme.colorScheme.background) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (vm.repo.isDemo) DemoBanner()
            ErrorText(error)
            Card {
                Image(
                    bitmap = label.asImageBitmap(), contentDescription = "QR seal label",
                    modifier = Modifier.width(280.dp).background(Color.White).padding(8.dp),
                )
            }
            Text(scanUrl(code), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Button(onClick = { QrLabel.share(context, label, code) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Print, null); Spacer(Modifier.size(8.dp)); Text("Print / share seal")
            }
            OutlinedButton(onClick = onPreview, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Visibility, null); Spacer(Modifier.size(8.dp)); Text("Preview what the customer sees")
            }
            Button(onClick = onPackNext, modifier = Modifier.fillMaxWidth()) { Text("Pack next order") }
            TextButton(onClick = onHome) { Text("Back to home") }
            Text(
                "Stick the seal across the bag opening so it tears if the bag is opened.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun OrdersScreen(vm: AppViewModel, kitchenId: String, onOpen: (String) -> Unit, onBack: () -> Unit) {
    var orders by remember { mutableStateOf<List<OrderSummary>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(kitchenId) {
        orders = runCatching { vm.repo.recentOrders(kitchenId) }.onFailure { error = it.message }.getOrDefault(emptyList())
    }
    Scaffold(topBar = { BackBar("Recent orders", onBack) }, containerColor = MaterialTheme.colorScheme.background) { pad ->
        val list = orders
        when {
            list == null -> CenteredLoading()
            list.isEmpty() -> Column(Modifier.padding(pad).padding(16.dp)) {
                ErrorText(error)
                Text("No orders packed yet.")
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.code }) { o ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(o.code) }) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${o.platform} ··${o.orderRefLast4}", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${formatDateTime(o.packedAtMillis)} · %.0f°C · code ${o.code}".format(o.tempC),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Text(
                                "${o.score}",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier
                                    .background(if (o.score >= 80) Ok else if (o.score >= 50) Amber else Chilli, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
