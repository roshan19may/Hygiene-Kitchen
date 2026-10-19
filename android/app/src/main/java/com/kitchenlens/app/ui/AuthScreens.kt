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
import com.kitchenlens.app.data.INVENTORY_CATEGORIES
import com.kitchenlens.app.data.Kitchen
import com.kitchenlens.app.util.Dates
import com.kitchenlens.app.util.HygieneScore
import com.kitchenlens.app.util.OfflineSeal
import com.kitchenlens.app.util.QrLabel
import com.kitchenlens.app.data.TIERS
import androidx.compose.material3.Scaffold
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kitchenlens.app.data.LocalRepository
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalContext
import com.kitchenlens.app.BuildConfig
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
        Text("Food Safety & Hygiene Verification", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
        TextButton(onClick = { email = ""; password = ""; error = null }, enabled = !busy) { Text("Reset") }
        Button(onClick = { go { vm.repo.signIn(email, password) } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Please wait…" else "Sign in")
        }
        TextButton(onClick = { go { vm.repo.signUp(email, password) } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("New kitchen? Create an account")
        }
    }
}

/** Editable kitchen details, shared by first-time setup and the "Kitchen details" screen. */
private const val NO_FSSAI_RATING = "Not rated"
private val FSSAI_RATING_OPTIONS = listOf(NO_FSSAI_RATING, "1", "2", "3", "4", "5")

class KitchenProfileState(
    name: String = "", locality: String = "", city: String = CITIES.first(),
    fssai: String = "", gstin: String = "", tier: String = TIERS.first(),
    fssaiHygieneRating: String = "", fssaiAuditDate: String = "", notStocked: Set<String> = emptySet(),
) {
    constructor(k: Kitchen) : this(
        k.name, k.locality, k.city.ifBlank { CITIES.first() }, k.fssai, k.gstin, k.tier.ifBlank { TIERS.first() },
        k.fssaiHygieneRating, k.fssaiAuditDate, k.notStocked,
    )

    var name by mutableStateOf(name)
    var locality by mutableStateOf(locality)
    var city by mutableStateOf(setOf(city))
    var fssai by mutableStateOf(fssai)
    var gstin by mutableStateOf(gstin)
    var tier by mutableStateOf(setOf(tier))
    var fssaiRating by mutableStateOf(setOf(fssaiHygieneRating.ifBlank { NO_FSSAI_RATING }))
    var fssaiAuditDate by mutableStateOf(fssaiAuditDate)
    var notStocked by mutableStateOf(notStocked)

    fun loadFrom(k: Kitchen) {
        val f = KitchenProfileState(k)
        name = f.name; locality = f.locality; city = f.city; fssai = f.fssai; gstin = f.gstin; tier = f.tier
        fssaiRating = f.fssaiRating; fssaiAuditDate = f.fssaiAuditDate; notStocked = f.notStocked
    }

    private val ratingValue get() = fssaiRating.firstOrNull()?.takeIf { it != NO_FSSAI_RATING } ?: ""

    /** First problem to fix, or null when the details can be saved. */
    fun validate(): String? = when {
        name.isBlank() -> "Enter the kitchen name"
        locality.isBlank() -> "Enter the locality"
        fssai.length != 14 -> "FSSAI number must be 14 digits"
        ratingValue.isNotBlank() && Dates.parse(fssaiAuditDate) == null -> "Enter the FSSAI hygiene audit date as dd-mm-yyyy"
        ratingValue.isNotBlank() && (Dates.parse(fssaiAuditDate) ?: 0L) > System.currentTimeMillis() -> "The FSSAI audit date can't be in the future"
        notStocked.size >= INVENTORY_CATEGORIES.size -> "At least one inventory category must be stocked"
        else -> Gstin.formError(gstin, required = false)
    }

    fun toMap() = profileMap(
        name, locality, city.first(), fssai, Gstin.normalize(gstin), tier.first(),
        ratingValue, if (ratingValue.isBlank()) "" else fssaiAuditDate.trim(), notStocked,
    )
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

    ChipPicker("Inventory you never stock (left out of your hygiene rating)", INVENTORY_CATEGORIES, state.notStocked, multi = true) {
        state.notStocked = it
    }
    Text(
        "For example, a vegetarian kitchen selects Meat & Fish. Customers see that it is not stocked.",
        style = MaterialTheme.typography.bodySmall,
    )

    ChipPicker("Official FSSAI Hygiene Rating (if you have the certificate)", FSSAI_RATING_OPTIONS, state.fssaiRating, multi = false) {
        state.fssaiRating = it.ifEmpty { setOf(NO_FSSAI_RATING) }
    }
    if (state.fssaiRating.firstOrNull() != NO_FSSAI_RATING) {
        OutlinedTextField(
            state.fssaiAuditDate, { state.fssaiAuditDate = it.take(10) },
            label = { Text("FSSAI hygiene audit date (dd-mm-yyyy)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Shown to customers as \"declared by the kitchen\", separate from the KitchenLens rating. Keep the certificate " +
                "ready: customers may ask for it. Certificates are valid for 2 years.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Public kitchen page address; demo mode packs the profile into the link (no server). */
fun kitchenPageUrl(vm: AppViewModel, k: Kitchen): String {
    val base = BuildConfig.WEB_BASE_URL + "kitchen.html?k=" + Uri.encode(k.id)
    if (!vm.repo.isDemo) return base
    val pub = (vm.repo as? LocalRepository)?.publicKitchen() ?: return base
    return base + "&d=" + OfflineSeal.kitchenPayload(pub)
}

/** Badge with the kitchen's 30-day rating, to share on listings and social media. */
@Composable
private fun KitchenBadgeCard(vm: AppViewModel, k: Kitchen) {
    val context = LocalContext.current
    val stats by vm.kitchenStats.collectAsStateWithLifecycle()
    Card(colors = CardDefaults.cardColors(containerColor = LeafLight)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your kitchen hygiene badge", fontWeight = FontWeight.Bold)
            val s = stats
            Text(s?.summary() ?: "Calculating your 30-day rating…", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Post this badge on your Swiggy and Zomato menu photos, Instagram and Google listing. " +
                    "Its QR opens your kitchen page with your live rating.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                enabled = s != null,
                modifier = Modifier.fillMaxWidth(),
                onClick = onClick@{
                    val st = s ?: return@onClick
                    val headline = if (st.eligible) "${st.stars} ★  ${st.rating}" else "New on KitchenLens"
                    val detail = if (st.eligible) "${st.orders} sealed orders · last 30 days" else "Every order sealed with hygiene proof"
                    val bmp = QrLabel.badgeBitmap(kitchenPageUrl(vm, k), k.name, headline, detail)
                    QrLabel.share(context, bmp, "badge_" + k.id.takeLast(6), "Share hygiene badge")
                },
            ) { Text("Share hygiene badge") }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(kitchenPageUrl(vm, k)))) } },
            ) { Text("Open my kitchen page") }
        }
    }
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
                            val id = vm.repo.createKitchen(p["name"]!!, p["locality"]!!, p["city"]!!, p["fssai"]!!, p["gstin"]!!, p["tier"]!!)
                            vm.repo.updateKitchenProfile(id, p)   // FSSAI hygiene rating and "not stocked" choices
                            vm.refresh()
                        } catch (e: Exception) { error = e.message } finally { busy = false }
                    }
                }
            },
        ) { Text(if (busy) "Saving…" else "Save and continue") }
        TextButton(onClick = {
            form.name = ""; form.locality = ""; form.city = setOf(CITIES.first()); form.fssai = ""; form.gstin = ""
            form.tier = setOf(TIERS.first()); form.fssaiRating = setOf(NO_FSSAI_RATING); form.fssaiAuditDate = ""
            form.notStocked = emptySet(); error = null
        }, enabled = !busy) { Text("Reset") }
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
    val form = remember(k.id) { KitchenProfileState(k) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = {
        BackBar("Kitchen details", onReset = {
            // Puts back the saved details, discarding unsaved edits
            form.loadFrom(k); error = null
        }, onBack = onBack)
    }, containerColor = MaterialTheme.colorScheme.background) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Changes appear on QR seals printed from now on.", style = MaterialTheme.typography.bodySmall)
            KitchenBadgeCard(vm, k)
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

            Spacer(Modifier.size(16.dp))
            AccountSection(vm)
            Spacer(Modifier.size(24.dp))
        }
    }
}

/** Privacy policy link and permanent account deletion (required by Google Play and the App Store). */
@Composable
private fun AccountSection(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Text("Account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    TextButton(onClick = {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.WEB_BASE_URL + "privacy.html")))
        }
    }) { Text("Privacy policy") }
    OutlinedButton(
        onClick = { confirm = true; error = null; password = ""; typed = "" },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Chilli),
    ) { Text("Delete my account and data") }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirm = false },
            title = { Text("Delete account permanently?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This deletes your kitchen profile, all hygiene records, photos, clips, orders and QR seals. " +
                            "Seals already given to customers will stop working. This cannot be undone."
                    )
                    if (!vm.repo.isDemo) {
                        OutlinedTextField(
                            password, { password = it }, label = { Text("Your password") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    OutlinedTextField(
                        typed, { typed = it }, label = { Text("Type DELETE to confirm") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (vm.repo.isDemo) {
                        Text(
                            "Photos already uploaded to your own Cloudinary account must be deleted there.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    ErrorText(error)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && typed.trim().equals("DELETE", ignoreCase = true) && (vm.repo.isDemo || password.isNotBlank()),
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            try {
                                vm.deleteAccount(password)
                            } catch (e: Exception) {
                                error = "Could not delete: ${e.message}"
                            } finally { busy = false }
                        }
                    },
                ) { Text(if (busy) "Deleting…" else "Delete permanently", color = Chilli) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }, enabled = !busy) { Text("Cancel") } },
        )
    }
}
