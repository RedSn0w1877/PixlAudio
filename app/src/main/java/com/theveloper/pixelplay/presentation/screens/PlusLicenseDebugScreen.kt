package com.theveloper.pixelplay.presentation.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.data.premium.PlusLicenseManager
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight

/** Local fixture controls; never use this screen as a payment verifier. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlusLicenseDebugScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val manager = remember(context) { PlusLicenseManager(context) }
    var unlocked by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var keyInput by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Plus license tools") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (!unlocked) {
            AlertDialog(
                onDismissRequest = onBackClick,
                title = { Text("Developer access") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Enter the local testing password to manage fixture licenses.")
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it.take(16) },
                            singleLine = true,
                            label = { Text("Password") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (password == "1501") unlocked = true else message = "Incorrect password"
                    }) { Text("Unlock") }
                },
                dismissButton = { TextButton(onClick = onBackClick) { Text("Cancel") } }
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, padding.calculateBottomPadding() + MiniPlayerHeight),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(
                            "Testing only. These local keys are not proof of payment and do not unlock a production server entitlement.",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                item {
                    Button(
                        onClick = {
                            val key = manager.createLicense()
                            manager.activate(key)
                            keyInput = key
                            message = "Created and activated $key"
                            refresh++
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Key, contentDescription = null)
                        Text("  Create and activate test key")
                    }
                }
                item {
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        label = { Text("License key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = {
                            message = if (manager.activate(keyInput)) "License activated" else "Unknown or revoked key"
                            refresh++
                        }, modifier = Modifier.weight(1f)) { Text("Activate") }
                        OutlinedButton(onClick = {
                            message = if (manager.deactivate(keyInput)) "License deactivated" else "Unknown key"
                            refresh++
                        }, modifier = Modifier.weight(1f)) { Text("Deactivate") }
                    }
                }
                item {
                    val active = manager.activeKey()
                    Text(
                        text = if (active != null) "Active local key: $active" else "No local key is active",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("Stored test keys: ${manager.allKeys().size}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    message?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
                }
            }
        }
    }
    // Keep Compose aware that the manager-backed values should be reread after mutations.
    @Suppress("UNUSED_VARIABLE")
    val ignoredRefresh = refresh
}
