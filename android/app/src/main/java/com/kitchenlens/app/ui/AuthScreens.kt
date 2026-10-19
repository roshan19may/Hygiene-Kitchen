package com.kitchenlens.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import com.kitchenlens.app.util.KitchenStats
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.VisualTransformation
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

/** Turns sign-in / sign-up errors into plain words (Firebase codes are not user friendly). */
fun friendlyAuthError(e: Throwable): String {
    if (e is com.google.firebase.FirebaseNetworkException) return "No internet connection. Check your connection and try again."
    val code = (e as? com.google.firebase.auth.FirebaseAuthException)?.errorCode.orEmpty()
    val msg = e.message.orEmpty()
    return when {
        code == "ERROR_EMAIL_ALREADY_IN_USE" -> "An account with this email already exists. Go back and sign in instead."
        code == "ERROR_WEAK_PASSWORD" -> "Password is too weak. Use at least 6 characters, mixing letters and numbers."
        code == "ERROR_INVALID_EMAIL" -> "That email address doesn't look right."
        code == "ERROR_WRONG_PASSWORD" || code == "ERROR_INVALID_CREDENTIAL" || code == "ERROR_USER_NOT_FOUND" ->
            "Email or password is incorrect. New here? Tap \"Create an account\"."
        code == "ERROR_TOO_MANY_REQUESTS" -> "Too many attempts. Please wait a few minutes and try again."
        code == "ERROR_OPERATION_NOT_ALLOWED" || msg.contains("CONFIGURATION_NOT_FOUND") ->
            "Email sign-up is switched off for this app. Owner: Firebase console → Authentication → Sign-in method → enable Email/Password."
        else -> msg.ifBlank { "Something went wrong. Please try again." }
    }
}

private fun validEmail(e: String) = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(e.trim())

@Composable
fun LoginScreen(vm: AppViewModel) {
    var creating by rememberSaveable { mutableStateOf(false) }
    if (creating) CreateAccountScreen(vm, onBack = { creating = false })
    else SignInScreen(vm, onCreate = { creating = true })
}

@Composable
private fun SignInScreen(vm: AppViewModel, onCreate: () -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    fun signIn() {
        error = null; info = null
        when {
            !validEmail(email) -> { error = "Enter your kitchen email address"; return }
            password.isEmpty() -> { error = "Enter your password"; return }
        }
        busy = true
        scope.launch {
            try { vm.repo.signIn(email.trim(), password); vm.refresh() }
            catch (e: Exception) { error = friendlyAuthError(e) }
            finally { busy = false }
        }
    }

    fun forgot() {
        error = null; info = null
        if (!validEmail(email)) { error = "Type your email above first, then tap \"Forgot password?\""; return }
        busy = true
        scope.launch {
            try { vm.repo.sendPasswordReset(email.trim()); info = "Password reset link sent to ${email.trim()}. Check your inbox (and spam)." }
            catch (e: Exception) { error = friendlyAuthError(e) }
            finally { busy = false }
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
        Text("Sign in", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = email, onValueChange = { email = it.trim() }, label = { Text("Kitchen email") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text("Password") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = { TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show") } },
        )
        ErrorText(error)
        info?.let { Text(it, color = Ok, style = MaterialTheme.typography.bodySmall) }
        Button(onClick = { signIn() }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(if (busy) "Please wait…" else "Sign in")
        }
        TextButton(onClick = { forgot() }, enabled = !busy && !vm.repo.isDemo) { Text("Forgot password?") }
        Spacer(Modifier.size(8.dp))
        Text("New kitchen?", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onCreate, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Create an account", fontWeight = FontWeight.Bold)
        }
    }
}

/** Simple sign-up: name, email, password twice, agree to the privacy policy. */
@Composable
private fun CreateAccountScreen(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var agreed by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = !busy) { onBack() }

    fun create() {
        error = when {
            name.isBlank() -> "Enter your name"
            !validEmail(email) -> "Enter a valid email address, e.g. owner@mykitchen.in"
            password.length < 6 -> "Password must be at least 6 characters"
            password != confirm -> "The two passwords don't match"
            !agreed -> "Please agree to the privacy policy to continue"
            else -> null
        }
        if (error != null) return
        busy = true
        scope.launch {
            try {
                vm.repo.signUp(email.trim(), password)
                vm.lastPackerName = name.trim()   // pre-fills "Recorded by" on the hygiene screens
                vm.refresh()                      // goes on to "Set up your kitchen"
            } catch (e: Exception) {
                error = friendlyAuthError(e)
            } finally { busy = false }
        }
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack, enabled = !busy) { Text("‹ Back to sign in") }
        Text("Create your account", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "One account per kitchen. Next you'll add your kitchen's name, FSSAI number and address.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (vm.repo.isDemo) DemoBanner()
        OutlinedTextField(
            name, { name = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        )
        OutlinedTextField(
            email, { email = it.trim() }, label = { Text("Kitchen email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            supportingText = { Text("You'll sign in with this email") },
        )
        OutlinedTextField(
            password, { password = it }, label = { Text("Password (at least 6 characters)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = { TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show") } },
        )
        OutlinedTextField(
            confirm, { confirm = it }, label = { Text("Confirm password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = confirm.isNotEmpty() && confirm != password,
            supportingText = { if (confirm.isNotEmpty() && confirm != password) Text("Passwords don't match") },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = agreed, onCheckedChange = { agreed = it })
            Text("I agree to the ", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.WEB_BASE_URL + "privacy.html"))) }
            }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("privacy policy") }
        }
        ErrorText(error)
        Button(onClick = { create() }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(if (busy) "Creating account…" else "Create account", fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = onBack, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Already have an account? Sign in") }
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

/** Headline and detail line printed on the badge picture. */
private fun badgeLines(st: KitchenStats?): Pair<String, String> =
    if (st != null && st.eligible) "${st.stars} ★  ${st.rating}" to "${st.orders} sealed orders · last 30 days"
    else "New on KitchenLens" to "Every order sealed with hygiene proof"

/**
 * "Share your hygiene rating": shows the kitchen's star rating, a small preview of the
 * badge picture, two short lines of explanation and one Share button.
 */
@Composable
private fun KitchenBadgeCard(vm: AppViewModel, k: Kitchen) {
    val context = LocalContext.current
    val stats by vm.kitchenStats.collectAsStateWithLifecycle()
    val st = stats
    val url = remember(k, st) { kitchenPageUrl(vm, k) }
    val badge = remember(url, k.name, st) {
        val (headline, detail) = badgeLines(st)
        QrLabel.badgeBitmap(url, k.name, headline, detail)
    }
    Card(colors = CardDefaults.cardColors(containerColor = LeafLight)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Share your hygiene rating", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                // Small preview of the exact picture that will be shared
                androidx.compose.foundation.Image(
                    bitmap = badge.asImageBitmap(), contentDescription = "Your hygiene badge",
                    modifier = Modifier.width(96.dp).clip(RoundedCornerShape(8.dp)),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    when {
                        st == null -> Text("Working out your rating…")
                        st.eligible -> {
                            Text("★ ${st.stars}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Leaf)
                            Text(st.rating, fontWeight = FontWeight.SemiBold)
                            Text("From your last ${st.orders} orders", style = MaterialTheme.typography.bodySmall)
                        }
                        else -> {
                            Text("Rating coming soon", fontWeight = FontWeight.Bold)
                            Text(
                                "Pack ${KitchenStats.MIN_ORDERS - st.orders} more order${if (KitchenStats.MIN_ORDERS - st.orders == 1) "" else "s"} with a QR seal to get your stars.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            LinearProgressIndicator(
                                progress = { st.orders / KitchenStats.MIN_ORDERS.toFloat() },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            Text(
                "This picture shows your star rating and a QR code. Post it on WhatsApp, Instagram or your " +
                    "Swiggy/Zomato photos. Customers scan it to see how clean your kitchen is.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                onClick = { QrLabel.share(context, badge, "badge_" + k.id.takeLast(6), "Share hygiene badge") },
            ) { Text("Share badge", fontWeight = FontWeight.Bold) }
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
            ) { Text("See what customers see") }
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
