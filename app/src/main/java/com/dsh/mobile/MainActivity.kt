package com.dsh.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dsh.mobile.pair.PairScreen
import com.dsh.mobile.ui.ChatScreen
import com.dsh.mobile.ui.ChatScreenArgs
import com.dsh.mobile.ui.SessionListScreen
import com.dsh.mobile.ui.SettingsViewModel
import com.dsh.mobile.ui.WorkspaceListScreen
import com.dsh.mobile.ui.WorkspacesViewModel
import com.dsh.mobile.ui.theme.DSHMobileTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DSHMobileTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val settingsViewModel: SettingsViewModel = viewModel()
                    val navController = rememberNavController()

                    NavHost(
                        navController = navController,
                        startDestination = "pair"
                    ) {
                        composable("pair") {
                            PairScreen(
                                navController = navController,
                                settingsViewModel = settingsViewModel
                            )
                        }
                        composable("workspaces") {
                            WorkspaceListScreen(
                                navController = navController,
                                viewModel = WorkspacesViewModel(settingsViewModel)
                            )
                        }
                        composable(
                            "sessions/{workspaceId}",
                            arguments = listOf(navArgument("workspaceId") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val workspaceId = backStackEntry.arguments?.getString("workspaceId") ?: ""
                            SessionListScreen(
                                navController = navController,
                                workspaceId = workspaceId
                            )
                        }
                        composable(
                            "chat/{workspaceId}/{sessionId}",
                            arguments = listOf(
                                navArgument("workspaceId") { type = NavType.StringType },
                                navArgument("sessionId") { type = NavType.StringType }
                            )
                        ) { backStackEntry ->
                            val workspaceId = backStackEntry.arguments?.getString("workspaceId") ?: ""
                            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: ""
                            ChatScreen(
                                navController = navController,
                                args = ChatScreenArgs(workspaceId, sessionId)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    DSHMobileTheme {
        Text("Hello DSH")
    }
}
