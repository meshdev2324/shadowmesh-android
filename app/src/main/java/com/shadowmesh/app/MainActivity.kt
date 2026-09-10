package com.shadowmesh.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.shadowmesh.app.ui.decoy.NotesDecoyScreen
import com.shadowmesh.app.ui.main.MainScreen
import com.shadowmesh.app.ui.screens.*
import com.shadowmesh.app.ui.security.MfaSetupScreen
import com.shadowmesh.app.ui.security.SecurityLockScreen
import com.shadowmesh.ui_kit.theme.ShadowMeshTheme
import dagger.hilt.android.AndroidEntryPoint

import com.shadowmesh.app.performance.PerformanceMonitor
import com.shadowmesh.ui_kit.performance.LocalPerformanceProfile
import androidx.compose.runtime.CompositionLocalProvider
import javax.inject.Inject

/**
 * Entry point for the ShadowMesh Android Application.
 * SOP 13: Strictly follows Autonomous Engineer workflow.
 * SOP 11: Privacy-first masking via FLAG_SECURE.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    private val viewModel: VPNManagerViewModel by viewModels()
    
    @Inject
    lateinit var performanceMonitor: PerformanceMonitor

    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onVpnPrepared(result.resultCode == RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        
        // SOP 02: Seamless transition from Splash to UI.
        splashScreen.setKeepOnScreenCondition {
            !viewModel.uiState.value.isInitialized
        }

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        lifecycle.addObserver(performanceMonitor)
        
        setContent {
            val uiState by viewModel.uiState.collectAsState()
            val performanceProfile by performanceMonitor.profile.collectAsState()
            
            ShadowMeshTheme(darkTheme = !uiState.isCamouflageEnabled) {
                CompositionLocalProvider(LocalPerformanceProfile provides performanceProfile) {
                    // SOP 11 §2: Mask IP/Secrets in recent apps.
                    // Hardened Logic: ALWAYS secure on sovereignty-sensitive screens, otherwise follow user toggle.
                    val isSensitiveScreen = uiState.showMfaSetup || !uiState.isActivated || uiState.isSecurityLockEnabled
                    if (isSensitiveScreen || !uiState.isScreenshotEnabled) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }

                    SecurityLayer(uiState, viewModel)

                    // SOP 02: One-Tap Connection permission handling
                    if (uiState.prepareVpnTrigger) {
                        LaunchedEffect(Unit) {
                            val intent = android.net.VpnService.prepare(this@MainActivity)
                            if (intent != null) {
                                vpnPrepareLauncher.launch(intent)
                            } else {
                                viewModel.onVpnPrepared(true)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SecurityLayer(uiState: VPNUiState, viewModel: VPNManagerViewModel) {
        when {
            uiState.isIntegrityCompromised -> { 
                IntegrityErrorScreen(uiState.errorMessage ?: "Security Violation") 
            }
            uiState.isSessionFrozen -> { 
                SessionFrozenScreen(viewModel) 
            }
            uiState.isPanicTriggered -> {
                DecoyErrorScreen(uiState)
            }
            uiState.isCamouflageEnabled -> {
                NotesDecoyScreen(onExitDecoy = {
                    viewModel.toggleCamouflageMode(false)
                })
            }
            uiState.isSecurityLockEnabled -> {
                SecurityLockScreen(viewModel = viewModel, onUnlocked = { /* Logic handled in VM */ })
            }
            uiState.showMfaSetup -> {
                MfaSetupScreen(viewModel = viewModel, onDismiss = { viewModel.dismissMfaSetup() })
            }
            !uiState.isActivated -> {
                LoginScreen(viewModel = viewModel, uiState = uiState)
            }
            else -> {
                MainScreen(viewModel = viewModel)
            }
        }
    }
}
