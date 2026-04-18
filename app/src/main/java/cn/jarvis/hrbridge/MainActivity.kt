package cn.jarvis.hrbridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import cn.jarvis.hrbridge.ui.screens.HomeScreen
import cn.jarvis.hrbridge.ui.screens.ScanScreen
import cn.jarvis.hrbridge.ui.screens.SettingsScreen
import cn.jarvis.hrbridge.ui.theme.HRBridgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(0, 0),
            navigationBarStyle = SystemBarStyle.auto(0, 0)
        )
        setContent {
            HRBridgeTheme {
                val nav = rememberNavController()
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    NavHost(
                        navController = nav,
                        startDestination = Routes.HOME,
                        modifier = Modifier.padding(padding)
                    ) {
                        composable(Routes.HOME) {
                            HomeScreen(
                                onScanClick = { nav.navigate(Routes.SCAN) },
                                onSettingsClick = { nav.navigate(Routes.SETTINGS) }
                            )
                        }
                        composable(Routes.SCAN) {
                            ScanScreen(onBack = { nav.popBackStack() })
                        }
                        composable(Routes.SETTINGS) {
                            SettingsScreen(onBack = { nav.popBackStack() })
                        }
                    }
                }
            }
        }
    }

    private object Routes {
        const val HOME = "home"
        const val SCAN = "scan"
        const val SETTINGS = "settings"
    }
}
