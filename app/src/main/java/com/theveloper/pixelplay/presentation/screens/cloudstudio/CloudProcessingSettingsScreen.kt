package com.theveloper.pixelplay.presentation.screens.cloudstudio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.KeyOff
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.NetworkCell
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.data.cloudstudio.CloudConfig
import com.theveloper.pixelplay.data.cloudstudio.CloudConfigInput
import com.theveloper.pixelplay.data.cloudstudio.CloudSecrets
import com.theveloper.pixelplay.data.cloudstudio.CloudSeparationQuality
import com.theveloper.pixelplay.presentation.screens.SettingsItem
import com.theveloper.pixelplay.presentation.screens.SwitchSettingItem
import com.theveloper.pixelplay.presentation.viewmodel.CloudStudioViewModel

/**
 * Settings › Experimental › Cloud processing (design §3.5 E, §7.2), ported from the iOS app's
 * `CloudProcessingSettingsView`: the consent switch, the RunPod endpoint and Restricted key, the R2 bucket and its key
 * pair (fields in the order of the owner's setup steps), Test connection (RunPod and storage reported on their own)
 * with the optional selftest, the outputs, mobile data, the GPU price and monthly cap, and the way to the queue.
 */
@Composable
fun CloudProcessingSettingsScreen(
    onBack: () -> Unit,
    onOpenQueue: () -> Unit,
    viewModel: CloudStudioViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val engine by viewModel.engineState.collectAsStateWithLifecycle()
    val draft = ui.draft ?: CloudSecrets.EMPTY

    LaunchedEffect(Unit) { viewModel.onScreenShown() }
    // Keys typed a moment ago are saved when the screen goes away (the draft is otherwise saved 0.6 s after typing).
    DisposableEffect(Unit) { onDispose { viewModel.flushDraft() } }

    var priceText by rememberSaveable { mutableStateOf(CloudStudioCopy.priceText(settings.pricePerSecondMicroUsd)) }
    var capText by rememberSaveable { mutableStateOf(CloudStudioCopy.dollars(settings.monthlyCapMicroUsd)) }

    val input = remember(settings, draft) {
        CloudConfigInput(settings.endpointId, draft.runpodKey, settings.r2Endpoint, settings.bucket,
            draft.accessKeyId, draft.secretAccessKey)
    }
    val problems = remember(input) { CloudConfig.problems(input) }
    val account = remember(settings.r2Endpoint) { CloudConfig.r2AccountId(settings.r2Endpoint) }
    val summary = remember(engine.jobs) { viewModel.summaryLine() }
    val committed = remember(engine.jobs, settings.pricePerSecondMicroUsd) { viewModel.committedThisMonthMicroUsd() }

    CloudScreenScaffold(title = "Cloud processing", onBack = onBack) {
        item(key = "consent") {
            CloudSection("Consent") {
                SwitchSettingItem(
                    title = "Send songs to my RunPod account",
                    subtitle = "Nothing leaves this phone until this is on, and only songs you send yourself go.",
                    checked = settings.enabled,
                    onCheckedChange = { on -> viewModel.updateSettings { it.copy(enabled = on) } },
                    leadingIcon = { Icon(Icons.Rounded.CloudUpload, null, tint = MaterialTheme.colorScheme.secondary) },
                )
                CloudPanel {
                    Text(
                        "Separates vocals with BS-RoFormer and times lyrics word by word on your own RunPod GPU. " +
                            "Songs go to your Cloudflare R2 bucket and are deleted after import.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (ui.keysMissing || ui.secureStorageUnavailable) {
            item(key = "keys_missing") {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    CloudPanel(color = MaterialTheme.colorScheme.errorContainer) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.KeyOff, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                            Text(
                                if (ui.secureStorageUnavailable) "Secure storage unavailable" else "Cloud keys missing — paste them again.",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        Text(
                            if (ui.secureStorageUnavailable) {
                                "This phone's Keystore couldn't open the encrypted key store, so keys can't be saved. " +
                                    "They are never kept anywhere else. Restarting the phone usually fixes this."
                            } else {
                                "This phone's encrypted key store is empty (app data was cleared or restored), so the " +
                                    "keys saved before can't be read."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }
        item(key = "runpod") {
            CloudSection("RunPod") {
                CloudPanel {
                    CloudTextField(
                        value = settings.endpointId,
                        onValueChange = { text -> viewModel.updateSettings { it.copy(endpointId = text) } },
                        label = "Endpoint ID",
                        placeholder = "e.g. abc123xyz",
                    )
                    CloudTextField(
                        value = draft.runpodKey,
                        onValueChange = { viewModel.updateDraft(draft.with(runpodKey = it)) },
                        label = "RunPod key (Restricted, Read/Write on this endpoint)",
                        placeholder = "rpa_…",
                        secret = true,
                    )
                }
            }
        }
        item(key = "storage") {
            CloudSection("Storage (Cloudflare R2)") {
                CloudPanel {
                    CloudTextField(
                        value = settings.r2Endpoint,
                        onValueChange = { text -> viewModel.updateSettings { it.copy(r2Endpoint = text) } },
                        label = "R2 endpoint or account ID",
                        placeholder = "https://<account-id>.r2.cloudflarestorage.com",
                        keyboardType = KeyboardType.Uri,
                    )
                    if (account != null) CloudCaption("Account $account")
                    CloudTextField(
                        value = settings.bucket,
                        onValueChange = { text -> viewModel.updateSettings { it.copy(bucket = text) } },
                        label = "Bucket",
                        placeholder = CloudConfig.DEFAULT_BUCKET,
                    )
                    CloudTextField(
                        value = draft.accessKeyId,
                        onValueChange = { viewModel.updateDraft(draft.with(accessKeyId = it)) },
                        label = "Access key ID",
                        secret = true,
                    )
                    CloudTextField(
                        value = draft.secretAccessKey,
                        onValueChange = { viewModel.updateDraft(draft.with(secretAccessKey = it)) },
                        label = "Secret access key",
                        secret = true,
                    )
                }
            }
        }
        item(key = "test") {
            CloudSection("Test connection") {
                CloudPanel {
                    if (problems.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            problems.forEach { problem ->
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Rounded.ErrorOutline, null, modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    CloudCaption(problem)
                                }
                            }
                        }
                    }
                    Button(
                        onClick = viewModel::testConnection,
                        enabled = problems.isEmpty() && !ui.isTesting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Verified, null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(if (ui.isTesting) "Testing…" else "Test connection")
                    }
                    ui.connectionReport?.let { report ->
                        CloudCheckLine("RunPod", report.runpod)
                        CloudCheckLine("Storage", report.storage)
                    }
                    FilledTonalButton(
                        onClick = viewModel::runSelftest,
                        enabled = problems.isEmpty() && !ui.isTesting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Memory, null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Run selftest (~1¢)")
                    }
                    ui.selftestCheck?.let { CloudCheckLine("Worker", it) }
                }
            }
        }
        item(key = "outputs") {
            CloudSection("Outputs") {
                SwitchSettingItem(
                    title = "Instrumental",
                    subtitle = "For Sing: the song without its vocals.",
                    checked = settings.wantsInstrumental,
                    onCheckedChange = { on -> viewModel.updateSettings { it.copy(wantsInstrumental = on) } },
                    leadingIcon = { Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.secondary) },
                )
                SwitchSettingItem(
                    title = "Word-timed lyrics",
                    subtitle = "Times your lyrics word by word against the vocals.",
                    checked = settings.wantsLyrics,
                    onCheckedChange = { on -> viewModel.updateSettings { it.copy(wantsLyrics = on) } },
                    leadingIcon = { Icon(Icons.Rounded.Subtitles, null, tint = MaterialTheme.colorScheme.secondary) },
                )
                SwitchSettingItem(
                    title = "Write lyrics when none are found (AI transcription)",
                    subtitle = "Saved as \"AI-written lyrics\". Line timing only outside the 11 languages the aligner knows.",
                    checked = settings.transcribeWhenMissing,
                    onCheckedChange = { on -> viewModel.updateSettings { it.copy(transcribeWhenMissing = on) } },
                    leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.secondary) },
                    enabled = settings.wantsLyrics,
                )
                CloudPanel {
                    Text("Quality", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    val options = listOf(CloudSeparationQuality.STANDARD to "Standard", CloudSeparationQuality.BEST to "Best")
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        options.forEachIndexed { index, (quality, label) ->
                            SegmentedButton(
                                selected = settings.quality == quality,
                                onClick = { viewModel.updateSettings { it.copy(quality = quality) } },
                                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                            ) { Text(label) }
                        }
                    }
                    CloudCaption("Best takes about twice the GPU time, for songs up to 8 minutes.")
                }
            }
        }
        item(key = "network") {
            CloudSection("Network") {
                SwitchSettingItem(
                    title = "Use mobile data",
                    subtitle = "Off: uploads and downloads wait for Wi-Fi or another unmetered network.",
                    checked = settings.useCellular,
                    onCheckedChange = { on -> viewModel.updateSettings { it.copy(useCellular = on) } },
                    leadingIcon = { Icon(Icons.Rounded.NetworkCell, null, tint = MaterialTheme.colorScheme.secondary) },
                )
            }
        }
        item(key = "cost") {
            CloudSection("Cost") {
                CloudPanel {
                    CloudTextField(
                        value = priceText,
                        onValueChange = { text ->
                            priceText = text
                            CloudStudioCopy.parseMicroUsd(text)?.takeIf { it > 0 }?.let { value ->
                                viewModel.updateSettings { it.copy(pricePerSecondMicroUsd = minOf(value, 10_000)) }
                            }
                        },
                        label = "GPU price per second (US$)",
                        placeholder = "0.000192",
                        keyboardType = KeyboardType.Decimal,
                    )
                    CloudTextField(
                        value = capText,
                        onValueChange = { text ->
                            capText = text
                            CloudStudioCopy.parseMicroUsd(text)?.let { value ->
                                viewModel.updateSettings { it.copy(monthlyCapMicroUsd = value) }
                            }
                        },
                        label = "Monthly cap (US$)",
                        placeholder = "3.00",
                        keyboardType = KeyboardType.Decimal,
                    )
                    Text(
                        CloudStudioCopy.monthLine(committed, settings.monthlyCapMicroUsd),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    CloudCaption(
                        "Estimates: about $0.004 a song once a GPU is awake, plus about $0.007 to wake one. " +
                            "The RunPod balance itself is the hard limit."
                    )
                }
            }
        }
        item(key = "queue") {
            CloudSection("Queue") {
                SettingsItem(
                    title = "Cloud queue",
                    subtitle = summary ?: "Send songs and see what comes back.",
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ListAlt, null, tint = MaterialTheme.colorScheme.secondary) },
                    trailingIcon = {
                        Icon(Icons.AutoMirrored.Rounded.ArrowForwardIos, null, modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    onClick = onOpenQueue,
                )
            }
        }
        item(key = "promise") {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                CloudPanel {
                    CloudCaption(CloudStudioCopy.PROMISE)
                    Button(
                        onClick = viewModel::forgetKeys,
                        enabled = !draft.isEmpty,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Key, null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Forget keys")
                    }
                }
            }
        }
    }
}

private fun CloudSecrets.with(
    runpodKey: String = this.runpodKey,
    accessKeyId: String = this.accessKeyId,
    secretAccessKey: String = this.secretAccessKey,
) = CloudSecrets(runpodKey, accessKeyId, secretAccessKey)
