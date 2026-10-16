package com.shadowmesh.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
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
import com.shadowmesh.app.ui.alert.AlertHost
import dagger.hilt.android.AndroidEntryPoint

import com.shadowmesh.app.performance.PerformanceMonitor
import com.shadowmesh.ui_kit.performance.LocalPerformanceProfile
import androidx.compose.runtime.CompositionLocalProvider
import javax.inject.Inject
import com.shadowmesh.app.security.SessionGate
import com.shadowmesh.app.security.resolveSessionGate
import com.shadowmesh.app.ui.screens.LiveTunnelRecoveryScreen

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

    @Inject
    lateinit var alertRepository: com.shadowmesh.app.alert.AlertRepository

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
            val alerts by alertRepository.alerts.collectAsState()

            // A style that promises to be cheap must actually pin the effect
            // budget. forcesLowProfile exists so Minimalist drops the glass
            // render targets; without this pin the monitor would keep handing
            // every consumer HIGH on a capable device and the style would only
            // flatten the wallpaper, not the 16 glass call-sites.
            val effectiveProfile =
                if (com.shadowmesh.app.ui.theme.BackgroundStyle
                    .fromName(uiState.backgroundStyle).forcesLowProfile
                ) {
                    com.shadowmesh.ui_kit.performance.PerformanceProfile.LOW
                } else {
                    performanceProfile
                }

            ShadowMeshTheme(darkTheme = !uiState.isCamouflageEnabled) {
                CompositionLocalProvider(LocalPerformanceProfile provides effectiveProfile) {
                    // SOP 11 §2: Mask IP/Secrets in recent apps.
                    // Hardened Logic: ALWAYS secure on sovereignty-sensitive screens, otherwise follow user toggle.
                    val isSensitiveScreen = uiState.showMfaSetup || !uiState.isActivated || uiState.isSecurityLockEnabled
                    if (isSensitiveScreen || !uiState.isScreenshotEnabled) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }

                    Box {
                        SecurityLayer(uiState, viewModel, alerts)

                        // RFC-028: the single alert surface, overlaid above the
                        // screen switch so an alert raised by any subsystem is
                        // visible on any screen — including the sovereignty-
                        // sensitive ones gated above.
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding(),
                        ) {
                            AlertHost(
                                alerts = alerts,
                                onDismiss = { alertRepository.dismiss(it.id) },
                            )
                        }
                    }

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
    private fun SecurityLayer(
        uiState: VPNUiState,
        viewModel: VPNManagerViewModel,
        alerts: List<com.shadowmesh.app.alert.Alert>,
    ) {
        when {
            uiState.isIntegrityCompromised -> {
                // The integrity verdict message travels on the typed alert
                // channel now (SECURITY tag); the screen keeps a fixed
                // fallback for the pathological case of no alert present.
                IntegrityErrorScreen(
                    alerts.firstOrNull { it.tag == com.shadowmesh.app.alert.AlertTag.SECURITY }?.message
                        ?: "Security Violation",
                )
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
            resolveSessionGate(uiState.isActivated, uiState.status) == SessionGate.LOGIN -> {
                LoginScreen(viewModel = viewModel, uiState = uiState)
            }
            resolveSessionGate(uiState.isActivated, uiState.status) == SessionGate.RECOVER_LIVE_TUNNEL -> {
                LiveTunnelRecoveryScreen(
                    onRestore = { viewModel.restoreActivation() },
                    onDisconnect = { viewModel.disconnect() },
                )
            }
            else -> {
                MainScreen(viewModel = viewModel)
            }
        }
    }
}
