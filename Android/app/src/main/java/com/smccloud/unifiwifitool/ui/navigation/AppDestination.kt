package com.smccloud.unifiwifitool.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.ui.graphics.vector.ImageVector

enum class AppDestination(
    val title: String,
    val icon: ImageVector
) {
    OVERVIEW("Overview", Icons.Outlined.Dashboard),
    DEVICES("Devices", Icons.Outlined.Devices),
    DISCIPLINE("Discipline", Icons.Outlined.Speed),
    AUDIT("Audit", Icons.Outlined.History)
}
