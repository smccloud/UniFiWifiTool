package com.smccloud.unifiwifitool

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.smccloud.unifiwifitool.data.biometric.BiometricGate
import com.smccloud.unifiwifitool.ui.AccessUiState
import com.smccloud.unifiwifitool.ui.AccessViewModel
import com.smccloud.unifiwifitool.ui.NoOpAccessActions
import com.smccloud.unifiwifitool.ui.UniFiWifiToolApp
import com.smccloud.unifiwifitool.ui.model.ThemePreference
import com.smccloud.unifiwifitool.ui.theme.UniFiWifiToolTheme
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private val viewModel: AccessViewModel by viewModels {
        AccessViewModel.factory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val biometricGate = BiometricGate(this)

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()

            UniFiWifiToolTheme(
                themePreference = state.themePreference,
                useDynamicColor = state.useDynamicColor
            ) {
                UniFiWifiToolApp(
                    state = state,
                    actions = viewModel,
                    events = viewModel.events,
                    canUseBiometrics = biometricGate.canAuthenticate(),
                    onRequestBiometricUnlock = {
                        lifecycleScope.launch {
                            val unlocked = biometricGate.authenticate()
                            viewModel.onUnlockResult(unlocked)
                        }
                    }
                )
            }

            LaunchedEffect(state.isLocked, state.isPaired) {
                if (state.isPaired && state.isLocked && biometricGate.canAuthenticate()) {
                    val unlocked = biometricGate.authenticate()
                    viewModel.onUnlockResult(unlocked)
                }
            }
        }
    }
}

@Preview(name = "Dark Mode", showBackground = true, widthDp = 412, heightDp = 892)
@Composable
private fun UniFiWifiToolAppDarkPreview() {
    val state = AccessUiState.preview().copy(
        themePreference = ThemePreference.DARK,
        isLocked = false
    )
    UniFiWifiToolTheme(themePreference = ThemePreference.DARK) {
        UniFiWifiToolApp(state = state, actions = NoOpAccessActions)
    }
}

@Preview(name = "Light Mode", showBackground = true, widthDp = 412, heightDp = 892)
@Composable
private fun UniFiWifiToolAppLightPreview() {
    val state = AccessUiState.preview().copy(
        themePreference = ThemePreference.LIGHT,
        isLocked = false
    )
    UniFiWifiToolTheme(themePreference = ThemePreference.LIGHT) {
        UniFiWifiToolApp(state = state, actions = NoOpAccessActions)
    }
}
