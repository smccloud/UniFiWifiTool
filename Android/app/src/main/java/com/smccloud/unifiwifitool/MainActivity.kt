package com.smccloud.unifiwifitool

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import com.smccloud.unifiwifitool.ui.UniFiWifiToolApp
import com.smccloud.unifiwifitool.ui.model.ThemePreference
import com.smccloud.unifiwifitool.ui.theme.UniFiWifiToolTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var themePreference by rememberSaveable { mutableStateOf(ThemePreference.SYSTEM) }
            var useDynamicColor by rememberSaveable { mutableStateOf(false) }

            UniFiWifiToolTheme(
                themePreference = themePreference,
                useDynamicColor = useDynamicColor
            ) {
                UniFiWifiToolApp(
                    themePreference = themePreference,
                    onThemePreferenceChange = { themePreference = it },
                    useDynamicColor = useDynamicColor,
                    onDynamicColorChange = { useDynamicColor = it }
                )
            }
        }
    }
}

@Preview(name = "Dark Mode", showBackground = true, widthDp = 412, heightDp = 892)
@Composable
private fun UniFiWifiToolAppDarkPreview() {
    UniFiWifiToolTheme(themePreference = ThemePreference.DARK) {
        UniFiWifiToolApp(
            themePreference = ThemePreference.DARK,
            onThemePreferenceChange = {},
            useDynamicColor = false,
            onDynamicColorChange = {}
        )
    }
}

@Preview(name = "Light Mode", showBackground = true, widthDp = 412, heightDp = 892)
@Composable
private fun UniFiWifiToolAppLightPreview() {
    UniFiWifiToolTheme(themePreference = ThemePreference.LIGHT) {
        UniFiWifiToolApp(
            themePreference = ThemePreference.LIGHT,
            onThemePreferenceChange = {},
            useDynamicColor = false,
            onDynamicColorChange = {}
        )
    }
}
