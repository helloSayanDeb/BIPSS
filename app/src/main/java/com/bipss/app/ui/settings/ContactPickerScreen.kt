package com.bipss.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.ui.theme.BipssTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import org.koin.androidx.compose.koinViewModel

/**
 * Lets the user pick up to (5 - already saved) emergency contacts straight
 * from the device's own Contacts app in one panel — Android has no native
 * multi-select contact picker, so this is a real ContactsContract query
 * (see ContactPickerViewModel), not a mocked list.
 */
@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ContactPickerScreen(
    navController: NavController,
    vm: ContactPickerViewModel = koinViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val contactsPermission = rememberPermissionState(android.Manifest.permission.READ_CONTACTS)

    LaunchedEffect(contactsPermission.status.isGranted) {
        if (contactsPermission.status.isGranted) vm.loadDeviceContacts()
    }
    LaunchedEffect(state.saved) {
        if (state.saved) navController.popBackStack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Emergency Contact") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            if (contactsPermission.status.isGranted && state.totalSelectable > 0) {
                Surface(shadowElevation = 8.dp) {
                    Button(
                        onClick = { vm.save() },
                        enabled = state.selectedIds.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp)
                    ) {
                        Text("Save (${state.selectedIds.size}/${state.totalSelectable})")
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                !contactsPermission.status.isGranted -> ContactsPermissionPrompt {
                    contactsPermission.launchPermissionRequest()
                }
                state.totalSelectable <= 0 -> Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "You already have the maximum of $MAX_EMERGENCY_CONTACTS emergency contacts. Remove one from Settings to add another.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.deviceContacts.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No contacts with phone numbers found on this device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    Text(
                        "Select up to ${state.totalSelectable} contact(s). They'll get an SMS if you trigger SOS, with your location if BIPSS can get it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.deviceContacts, key = { it.contactId }) { contact ->
                            val checked = contact.contactId in state.selectedIds
                            val disabled = !checked && state.remainingSlots <= 0
                            DeviceContactRow(
                                contact = contact,
                                checked = checked,
                                enabled = !disabled,
                                onToggle = { vm.toggleSelection(contact.contactId) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactsPermissionPrompt(onGrant: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Outlined.Contacts,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Contacts access needed",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "BIPSS needs permission to read your contacts so you can pick who gets notified during an SOS.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGrant, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Grant Access")
        }
    }
}

@Composable
private fun DeviceContactRow(
    contact: DeviceContact,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = contact.name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString(""),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(contact.name, fontWeight = FontWeight.Bold)
            Text(
                contact.phoneNumber,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
    }
}
