package dev.telesor.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.telesor.TelesorApp
import dev.telesor.data.ConnectionState
import dev.telesor.data.DeviceRole
import dev.telesor.net.ConnectionManager
import dev.telesor.ui.screens.ConnectionScreen
import dev.telesor.ui.screens.PairingScreen
import dev.telesor.ui.screens.PermissionGateScreen
import dev.telesor.ui.screens.RoleSelectionScreen
import dev.telesor.ui.screens.SettingsScreen
import dev.telesor.ui.theme.TelesorTheme
import dev.telesor.util.PermissionHelper

class MainActivity : ComponentActivity() {

    private lateinit var connectionManager: ConnectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        connectionManager = ConnectionManager(
            context = this,
            preferences = TelesorApp.instance.preferences,
        )

        setContent {
            TelesorTheme {
                TelesorNavigation(connectionManager)
            }
        }
    }

    override fun onDestroy() {
        connectionManager.destroy()
        super.onDestroy()
    }
}

object Routes {
    const val ROLE_SELECTION = "role_selection"
    const val PERMISSIONS = "permissions/{role}"
    const val PAIRING = "pairing/{role}"
    const val CONNECTION = "connection/{role}"
    const val SETTINGS = "settings"

    fun permissions(role: String) = "permissions/$role"
    fun pairing(role: String) = "pairing/$role"
    fun connection(role: String) = "connection/$role"
}

@Composable
fun TelesorNavigation(connectionManager: ConnectionManager) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val connectionState by connectionManager.connectionState.collectAsState()

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.ROLE_SELECTION,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.ROLE_SELECTION) {
                RoleSelectionScreen(
                    onRoleSelected = { role ->
                        val perms = when (role) {
                            DeviceRole.PROVIDER -> PermissionHelper.PROVIDER_PERMISSIONS
                            DeviceRole.CONSUMER -> PermissionHelper.CONSUMER_PERMISSIONS
                        }
                        if (PermissionHelper.allGranted(context, perms)) {
                            navController.navigate(Routes.pairing(role.name))
                        } else {
                            navController.navigate(Routes.permissions(role.name))
                        }
                    },
                    onSettings = {
                        navController.navigate(Routes.SETTINGS)
                    },
                )
            }

            composable(Routes.PERMISSIONS) { backStackEntry ->
                val roleName = backStackEntry.arguments?.getString("role") ?: "PROVIDER"
                PermissionGateScreen(
                    roleName = roleName,
                    onAllGranted = {
                        navController.navigate(Routes.pairing(roleName)) {
                            popUpTo(Routes.permissions(roleName)) { inclusive = true }
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.PAIRING) { backStackEntry ->
                val roleName = backStackEntry.arguments?.getString("role") ?: "PROVIDER"
                PairingScreen(
                    roleName = roleName,
                    connectionManager = connectionManager,
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
                val latencyMs by connectionManager.latencyMs.collectAsState()

                ConnectionScreen(
                    roleName = roleName,
                    latencyMs = latencyMs,
                    connectionState = connectionState,
                    onDisconnect = {
                        connectionManager.disconnect()
                        navController.navigate(Routes.ROLE_SELECTION) {
                            popUpTo(Routes.ROLE_SELECTION) { inclusive = true }
                        }
                    },
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
