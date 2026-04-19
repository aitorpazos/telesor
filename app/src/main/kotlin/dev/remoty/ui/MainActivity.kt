package dev.remoty.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.remoty.ui.screens.ConnectionScreen
import dev.remoty.ui.screens.PairingScreen
import dev.remoty.ui.screens.RoleSelectionScreen
import dev.remoty.ui.theme.RemotyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RemotyTheme {
                RemotyNavigation()
            }
        }
    }
}

object Routes {
    const val ROLE_SELECTION = "role_selection"
    const val PAIRING = "pairing/{role}"
    const val CONNECTION = "connection/{role}"

    fun pairing(role: String) = "pairing/$role"
    fun connection(role: String) = "connection/$role"
}

@Composable
fun RemotyNavigation() {
    val navController = rememberNavController()

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.ROLE_SELECTION,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.ROLE_SELECTION) {
                RoleSelectionScreen(
                    onRoleSelected = { role ->
                        navController.navigate(Routes.pairing(role.name))
                    }
                )
            }
            composable(Routes.PAIRING) { backStackEntry ->
                val roleName = backStackEntry.arguments?.getString("role") ?: "PROVIDER"
                PairingScreen(
                    roleName = roleName,
                    onPaired = {
                        navController.navigate(Routes.connection(roleName)) {
                            popUpTo(Routes.ROLE_SELECTION)
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.CONNECTION) { backStackEntry ->
                val roleName = backStackEntry.arguments?.getString("role") ?: "PROVIDER"
                ConnectionScreen(
                    roleName = roleName,
                    onDisconnect = {
                        navController.navigate(Routes.ROLE_SELECTION) {
                            popUpTo(Routes.ROLE_SELECTION) { inclusive = true }
                        }
                    },
                )
            }
        }
    }
}
