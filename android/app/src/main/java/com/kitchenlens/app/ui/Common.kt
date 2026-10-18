@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.kitchenlens.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screen title bar with Back. When [onReset] is given, a "Reset" button on the
 * right clears what was entered on this screen immediately.
 */
@Composable
fun BackBar(title: String, onReset: (() -> Unit)? = null, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
        actions = {
            if (onReset != null) {
                TextButton(onClick = onReset) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = null)
                    Text(" Reset")
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
fun ChipPicker(
    label: String,
    options: List<String>,
    selected: Set<String>,
    multi: Boolean,
    onChange: (Set<String>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { opt ->
                val on = opt in selected
                val tick: (@Composable () -> Unit)? =
                    if (on) ({ Icon(Icons.Filled.Check, contentDescription = null) }) else null
                FilterChip(
                    selected = on,
                    onClick = {
                        onChange(
                            when {
                                multi && on -> selected - opt
                                multi -> selected + opt
                                else -> setOf(opt)
                            }
                        )
                    },
                    label = { Text(opt) },
                    leadingIcon = tick,
                )
            }
        }
    }
}

@Composable
fun CenteredLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun ErrorText(msg: String?) {
    if (msg != null) Text(msg, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 4.dp))
}

/** "just now", "25 min ago", "3 h ago", "2 days ago" */
fun timeAgo(at: Long, now: Long = System.currentTimeMillis()): String {
    val min = (now - at) / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 48 * 60 -> "${min / 60} h ago"
        else -> "${min / (24 * 60)} days ago"
    }
}

/** Age of a record relative to the moment the order was packed. */
fun beforePacking(at: Long, packedAt: Long): String {
    val min = (packedAt - at) / 60_000
    return when {
        min < 1 -> "at packing"
        min < 60 -> "$min min before packing"
        min < 48 * 60 -> "${min / 60} h before packing"
        else -> "${min / (24 * 60)} days before packing"
    }
}

fun formatDateTime(at: Long): String = SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH).format(Date(at))
fun formatDate(at: Long): String = SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH).format(Date(at))

fun parseDate(text: String): Long? = runCatching {
    SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH).apply { isLenient = false }.parse(text.trim())?.time
}.getOrNull()
