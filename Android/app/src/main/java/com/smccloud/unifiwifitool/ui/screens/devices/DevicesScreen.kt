package com.smccloud.unifiwifitool.ui.screens.devices

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smccloud.unifiwifitool.ui.components.DeviceCard
import com.smccloud.unifiwifitool.ui.model.AccessMode
import com.smccloud.unifiwifitool.ui.model.DeviceUiModel

val DeviceFilterOptions = listOf(
    "All",
    "Online now",
    "Allowed",
    "Blocked",
    "Scheduled",
    "Throttled",
    "Ignored"
)

@Composable
fun DevicesScreen(
    devices: List<DeviceUiModel>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedFilter: String,
    onSelectFilter: (String) -> Unit,
    onDeviceClick: (DeviceUiModel) -> Unit,
    modifier: Modifier = Modifier
) {
    val filteredDevices = devices.filter { device ->
        val matchesQuery = searchQuery.isBlank() ||
            device.label.contains(searchQuery, ignoreCase = true) ||
            (device.hostname?.contains(searchQuery, ignoreCase = true) == true) ||
            device.mac.contains(searchQuery, ignoreCase = true) ||
            (device.ip?.contains(searchQuery, ignoreCase = true) == true) ||
            (device.ssid?.contains(searchQuery, ignoreCase = true) == true)

        val matchesFilter = when (selectedFilter) {
            "Online now" -> device.isOnline
            "Allowed" -> device.accessMode == AccessMode.ALLOWED
            "Blocked" -> device.accessMode == AccessMode.BLOCKED
            "Scheduled" -> device.accessMode == AccessMode.SCHEDULED
            "Throttled" -> device.activeDiscipline != null
            "Ignored" -> device.accessMode == AccessMode.IGNORED
            else -> true
        }
        matchesQuery && matchesFilter
    }

    Column(modifier = modifier.fillMaxSize()) {
        // M3 Rounded Search Field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = MaterialTheme.shapes.extraLarge,
            singleLine = true,
            placeholder = { Text("Search name, MAC, IP, or SSID…") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = "Search"
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Outlined.Clear,
                            contentDescription = "Clear search"
                        )
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        )

        // Filter Chip Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DeviceFilterOptions.forEach { filter ->
                FilterChip(
                    selected = selectedFilter == filter,
                    onClick = { onSelectFilter(filter) },
                    label = { Text(filter) }
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (filteredDevices.isEmpty()) {
                item {
                    Text(
                        text = "No WiFi devices match the current filter.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            } else {
                items(filteredDevices, key = { it.id }) { device ->
                    DeviceCard(
                        device = device,
                        onClick = { onDeviceClick(device) }
                    )
                }
            }
        }
    }
}
