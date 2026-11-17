package com.itantra.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Emergency
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.itantra.feature.alerts.EmergencyAlertScreen
import com.itantra.feature.chat.ChatScreen
import com.itantra.feature.diagnostics.DiagnosticsScreen
import com.itantra.feature.handsfree.HandsFreeScreen
import com.itantra.feature.home.HomeDashboardScreen
import com.itantra.feature.languages.LanguageManagerScreen
import com.itantra.feature.onboarding.OnboardingScreen
import com.itantra.feature.ptt.PushToTalkScreen

sealed class Screen(
    val route: String,
    val title: String,
    val icon: ImageVector
) {
    object Home : Screen("home", "Home", Icons.Default.Home)
    object Chat : Screen("chat", "Chat", Icons.Default.Chat)
    object PTT : Screen("ptt", "PTT", Icons.Default.Mic)
    object HandsFree : Screen("handsfree", "Hands-Free", Icons.Default.Headset)
    object Alerts : Screen("alerts", "Alerts", Icons.Default.Emergency)
    object Languages : Screen("languages", "Models", Icons.Default.Translate)
    object Diagnostics : Screen("diagnostics", "Logs", Icons.Default.Analytics)
    object Onboarding : Screen("onboarding", "Guide", Icons.Default.Home)
}

val NavigationItems = listOf(
    Screen.Home,
    Screen.Chat,
    Screen.PTT,
    Screen.HandsFree,
    Screen.Alerts,
    Screen.Languages,
    Screen.Diagnostics
)

@Composable
fun MainAppContainer() {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }
    var showOnboarding by remember { mutableStateOf(false) }

    if (showOnboarding) {
        OnboardingScreen(onDismiss = { showOnboarding = false })
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    tonalElevation = 4.dp
                ) {
                    NavigationItems.forEach { screen ->
                        val selected = currentScreen == screen
                        NavigationBarItem(
                            selected = selected,
                            onClick = { currentScreen = screen },
                            icon = {
                                Icon(
                                    imageVector = screen.icon,
                                    contentDescription = screen.title,
                                    modifier = Modifier.size(22.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = screen.title,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    }
                }
            }
        ) { paddingValues ->
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.padding(paddingValues)
            ) {
                when (currentScreen) {
                    Screen.Home -> HomeDashboardScreen(
                        onOpenChat = { currentScreen = Screen.Chat },
                        onOpenPtt = { currentScreen = Screen.PTT },
                        onOpenGuide = { showOnboarding = true }
                    )
                    Screen.Chat -> ChatScreen()
                    Screen.PTT -> PushToTalkScreen()
                    Screen.HandsFree -> HandsFreeScreen()
                    Screen.Alerts -> EmergencyAlertScreen()
                    Screen.Languages -> LanguageManagerScreen()
                    Screen.Diagnostics -> DiagnosticsScreen()
                    Screen.Onboarding -> OnboardingScreen(onDismiss = { currentScreen = Screen.Home })
                }
            }
        }
    }
}
