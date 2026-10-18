package com.kitchenlens.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kitchenlens.app.AppViewModel
import com.kitchenlens.app.data.CITIES
import com.kitchenlens.app.data.TIERS
import kotlinx.coroutines.launch

@Composable
fun DemoBanner() {
    Card(colors = CardDefaults.cardColors(containerColor = Turmeric.copy(alpha = 0.25f))) {
        Text(
            "Demo mode: data stays on this phone. Connect Firebase (see README) so customers can scan QR codes.",
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun LoginScreen(vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun go(block: suspend () -> Unit) {
        error = null
        if (!email.contains("@") || password.length < 6) {
            error = "Enter a valid email and a password of at least 6 characters"
            return
        }
        busy = true
        scope.launch {
            try { block(); vm.refresh() } catch (e: Exception) { error = e.message } finally { busy = false }
        }
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.size(32.dp))
        Text("KitchenLens", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = Leaf)
        Text("Show customers how their food was prepared, without revealing your recipes.")
        if (vm.repo.isDemo) DemoBanner()
        OutlinedTextField(
            value = email, onValueChange = { email = it }, label = { Text("Kitchen email") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text("Password") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        ErrorText(error)
        Button(onClick = { go { vm.repo.signIn(email, password) } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Please wait…" else "Sign in")
        }
        TextButton(onClick = { go { vm.repo.signUp(email, password) } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("New kitchen? Create an account")
        }
    }
}

@Composable
fun SetupKitchenScreen(vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var locality by remember { mutableStateOf("") }
    var city by remember { mutableStateOf(setOf(CITIES.first())) }
    var fssai by remember { mutableStateOf("") }
    var tier by remember { mutableStateOf(setOf(TIERS.first())) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Set up your kitchen", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Customers will see these details when they scan the QR seal.")
        OutlinedTextField(name, { name = it }, label = { Text("Kitchen / brand name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(locality, { locality = it }, label = { Text("Locality (e.g. HSR Layout)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        ChipPicker("City", CITIES, city, multi = false) { city = it }
        OutlinedTextField(
            fssai, { fssai = it.filter(Char::isDigit).take(14) },
            label = { Text("FSSAI licence / registration no. (14 digits)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        ChipPicker("Kitchen area (kit tier)", TIERS, tier, multi = false) { tier = it }
        ErrorText(error)
        Button(
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                error = when {
                    name.isBlank() -> "Enter the kitchen name"
                    locality.isBlank() -> "Enter the locality"
                    fssai.length != 14 -> "FSSAI number must be 14 digits"
                    else -> null
                }
                if (error == null) {
                    busy = true
                    scope.launch {
                        try {
                            vm.repo.createKitchen(name, locality, city.first(), fssai, tier.first())
                            vm.refresh()
                        } catch (e: Exception) { error = e.message } finally { busy = false }
                    }
                }
            },
        ) { Text(if (busy) "Saving…" else "Save and continue") }
        TextButton(onClick = { vm.signOut() }) { Text("Sign out") }
    }
}
