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
import androidx.compose.material3.Scaffold
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kitchenlens.app.data.LocalRepository
import com.kitchenlens.app.data.profileMap
import com.kitchenlens.app.util.Gstin
import kotlinx.coroutines.launch

@Composable
fun DemoBanner() {
    Card(colors = CardDefaults.cardColors(containerColor = Turmeric.copy(alpha = 0.25f))) {
        Text(
            "Demo mode: records are kept on this phone. To show photos and clips when customers scan a seal, " +
                "connect free cloud storage in Kitchen details (shop icon).",
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

/** Editable kitchen details, shared by first-time setup and the "Kitchen details" screen. */
class KitchenProfileState(
    name: String = "", locality: String = "", city: String = CITIES.first(),
    fssai: String = "", gstin: String = "", tier: String = TIERS.first(),
) {
    var name by mutableStateOf(name)
    var locality by mutableStateOf(locality)
    var city by mutableStateOf(setOf(city))
    var fssai by mutableStateOf(fssai)
    var gstin by mutableStateOf(gstin)
    var tier by mutableStateOf(setOf(tier))

    /** First problem to fix, or null when the details can be saved. */
    fun validate(): String? = when {
        name.isBlank() -> "Enter the kitchen name"
        locality.isBlank() -> "Enter the locality"
        fssai.length != 14 -> "FSSAI number must be 14 digits"
        else -> Gstin.formError(gstin, required = false)
    }

    fun toMap() = profileMap(name, locality, city.first(), fssai, Gstin.normalize(gstin), tier.first())
}

@Composable
fun KitchenProfileFields(state: KitchenProfileState) {
    OutlinedTextField(state.name, { state.name = it }, label = { Text("Kitchen / brand name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(state.locality, { state.locality = it }, label = { Text("Locality (e.g. HSR Layout)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    ChipPicker("City", CITIES, state.city, multi = false) { state.city = it }
    OutlinedTextField(
        state.fssai, { state.fssai = it.filter(Char::isDigit).take(14) },
        label = { Text("FSSAI licence / registration no. (14 digits)") }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    OutlinedTextField(
        state.gstin, { state.gstin = Gstin.normalize(it) },
        label = { Text("GST number (GSTIN), if registered") }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        isError = Gstin.formError(state.gstin, required = false) != null,
        supportingText = {
            Text(
                Gstin.formError(state.gstin, required = false) ?: Gstin.warning(state.gstin)
                    ?: "Optional. 15 characters, e.g. 29ABCDE1234F1ZW. Shown to customers with your FSSAI number."
            )
        },
    )
    ChipPicker("Kitchen area (kit tier)", TIERS, state.tier, multi = false) { state.tier = it }
}

@Composable
fun SetupKitchenScreen(vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    val form = remember { KitchenProfileState() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Set up your kitchen", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Customers will see these details when they scan the QR seal.")
        KitchenProfileFields(form)
        ErrorText(error)
        Button(
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                error = form.validate()
                if (error == null) {
                    busy = true
                    val p = form.toMap()
                    scope.launch {
                        try {
                            vm.repo.createKitchen(p["name"]!!, p["locality"]!!, p["city"]!!, p["fssai"]!!, p["gstin"]!!, p["tier"]!!)
                            vm.refresh()
                        } catch (e: Exception) { error = e.message } finally { busy = false }
                    }
                }
            },
        ) { Text(if (busy) "Saving…" else "Save and continue") }
        TextButton(onClick = { vm.signOut() }) { Text("Sign out") }
    }
}

/** Lets an existing kitchen change its details, e.g. add a GST number later. */
@Composable
fun EditKitchenScreen(vm: AppViewModel, onDone: () -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val kitchen by vm.kitchen.collectAsStateWithLifecycle()
    val k = kitchen
    if (k == null) { CenteredLoading(); return }
    val form = remember(k.id) {
        KitchenProfileState(
            k.name, k.locality, k.city.ifBlank { CITIES.first() }, k.fssai, k.gstin, k.tier.ifBlank { TIERS.first() },
        )
    }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { BackBar("Kitchen details", onBack) }, containerColor = MaterialTheme.colorScheme.background) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Changes appear on QR seals printed from now on.", style = MaterialTheme.typography.bodySmall)
            (vm.repo as? LocalRepository)?.let { CloudStorageCard(it.cloud) }
            KitchenProfileFields(form)
            ErrorText(error)
            Button(
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    error = form.validate()
                    if (error == null) {
                        busy = true
                        scope.launch {
                            try {
                                vm.repo.updateKitchenProfile(k.id, form.toMap())
                                onDone()
                            } catch (e: Exception) { error = "Could not save: ${e.message}" } finally { busy = false }
                        }
                    }
                },
            ) { Text(if (busy) "Saving…" else "Save changes") }
        }
    }
}
