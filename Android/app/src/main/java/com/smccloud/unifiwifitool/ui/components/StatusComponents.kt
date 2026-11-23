package com.smccloud.unifiwifitool.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smccloud.unifiwifitool.ui.model.AccessMode
import com.smccloud.unifiwifitool.ui.model.TunnelState
import com.smccloud.unifiwifitool.ui.theme.LocalStatusColors
import com.smccloud.unifiwifitool.ui.theme.MonoTextStyles
import com.smccloud.unifiwifitool.ui.theme.StatusColorToken

@Composable
fun statusTokenFor(mode: AccessMode): StatusColorToken {
    val colors = LocalStatusColors.current
    return when (mode) {
        AccessMode.ALLOWED -> colors.allowed
        AccessMode.BLOCKED -> colors.blocked
        AccessMode.SCHEDULED -> colors.scheduled
        AccessMode.IGNORED -> colors.ignored
    }
}

@Composable
fun iconForAccessMode(mode: AccessMode): ImageVector = when (mode) {
    AccessMode.ALLOWED -> Icons.Outlined.CheckCircle
    AccessMode.BLOCKED -> Icons.Outlined.Block
    AccessMode.SCHEDULED -> Icons.Outlined.Schedule
    AccessMode.IGNORED -> Icons.Outlined.RemoveCircleOutline
}

/**
 * Semantic M3 badge for ALLOWED, BLOCKED, SCHEDULED, IGNORED, or active Discipline speed limits.
 */
@Composable
fun AccessStatusBadge(
    mode: AccessMode,
    modifier: Modifier = Modifier,
    overrideText: String? = null
) {
    val token = statusTokenFor(mode)
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = token.container,
        contentColor = token.content,
        border = BorderStroke(1.dp, token.border)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = iconForAccessMode(mode),
                contentDescription = null,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = overrideText ?: mode.badgeLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun DisciplineBadge(
    text: String,
    modifier: Modifier = Modifier
) {
    val token = LocalStatusColors.current.discipline
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = token.container,
        contentColor = token.content,
        border = BorderStroke(1.dp, token.border)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Speed,
                contentDescription = null,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * TopAppBar connection health pill. Tapping cycles the simulated tunnel state
 * so all offline/degraded UI states can be inspected during design review.
 */
@Composable
fun TunnelStatusPill(
    tunnelState: TunnelState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusColors = LocalStatusColors.current
    val token = when (tunnelState) {
        TunnelState.CONNECTED -> statusColors.allowed
        TunnelState.CONTROLLER_UNREACHABLE -> statusColors.discipline
        TunnelState.HOME_OFFLINE -> statusColors.blocked
    }

    Surface(
        modifier = modifier
            .clip(CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = token.container,
        contentColor = token.content,
        border = BorderStroke(1.dp, token.border)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(token.indicator)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = tunnelState.pillLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * Persistent M3 banner displayed when the home instance is offline or the UniFi
 * controller is unreachable. Per Issue #10, mutations are disabled and never queued offline.
 */
@Composable
fun ConnectionStateBanner(
    tunnelState: TunnelState,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title = tunnelState.bannerTitle ?: return
    val detail = tunnelState.bannerDetail.orEmpty()
    val isOffline = tunnelState == TunnelState.HOME_OFFLINE

    val containerColor = if (isOffline) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val contentColor = if (isOffline) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = contentColor
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (isOffline) Icons.Outlined.CloudOff else Icons.Outlined.WarningAmber,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            TextButton(onClick = onRetryClick) {
                Text("Retry", color = contentColor)
            }
        }
    }
}

@Composable
fun MonoTag(
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Text(
            text = text,
            style = MonoTextStyles.codeSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * Guardrail confirmation dialog required before blocking a device or revoking access.
 */
@Composable
fun DestructiveActionConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(text = message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
