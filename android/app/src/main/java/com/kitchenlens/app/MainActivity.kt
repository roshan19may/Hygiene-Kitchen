package com.kitchenlens.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kitchenlens.app.data.Section
import com.kitchenlens.app.ui.CenteredLoading
import com.kitchenlens.app.ui.HomeScreen
import com.kitchenlens.app.ui.KitchenLensTheme
import com.kitchenlens.app.ui.LoginScreen
import com.kitchenlens.app.ui.OrdersScreen
import com.kitchenlens.app.ui.PackingScreen
import com.kitchenlens.app.ui.PreviewScreen
import com.kitchenlens.app.ui.QrResultScreen
import com.kitchenlens.app.ui.SectionScreen
import com.kitchenlens.app.ui.SetupKitchenScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KitchenLensTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Root()
                }
            }
        }
    }
}

@Composable
private fun Root(vm: AppViewModel = viewModel()) {
    val auth by vm.auth.collectAsStateWithLifecycle()
    when (val s = auth) {
        AuthState.Loading -> CenteredLoading()
        AuthState.LoggedOut -> LoginScreen(vm)
        AuthState.NeedsSetup -> SetupKitchenScreen(vm)
        is AuthState.Failed -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(s.message)
            Button(onClick = { vm.refresh() }) { Text("Try again") }
            TextButton(onClick = { vm.signOut() }) { Text("Sign out") }
        }
        is AuthState.Ready -> MainNav(vm, s.kitchenId)
    }
}

@Composable
private fun MainNav(vm: AppViewModel, kitchenId: String) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                vm = vm,
                onSection = { nav.navigate("section/${it.name}") },
                onPack = { nav.navigate("pack") },
                onOrders = { nav.navigate("orders") },
            )
        }
        composable("section/{name}") { entry ->
            val section = Section.valueOf(entry.arguments?.getString("name") ?: Section.UTENSILS.name)
            SectionScreen(vm, section, onDone = { nav.popBackStack() }, onBack = { nav.popBackStack() })
        }
        composable("pack") {
            PackingScreen(
                vm,
                onCreated = { code ->
                    nav.navigate("qr/$code") { popUpTo("pack") { inclusive = true } }
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable("qr/{code}") { entry ->
            val code = entry.arguments?.getString("code") ?: ""
            QrResultScreen(
                vm, code,
                onPreview = { nav.navigate("preview/$code") },
                onPackNext = { nav.navigate("pack") { popUpTo("home") } },
                onHome = { nav.popBackStack("home", inclusive = false) },
            )
        }
        composable("orders") {
            OrdersScreen(vm, kitchenId, onOpen = { nav.navigate("qr/$it") }, onBack = { nav.popBackStack() })
        }
        composable("preview/{code}") { entry ->
            PreviewScreen(vm, entry.arguments?.getString("code") ?: "", onBack = { nav.popBackStack() })
        }
    }
}
