package com.example.ui.screens.providers

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.entity.ProviderConfigEntity
import com.example.data.repository.ProviderDiagnosticResult
import com.example.data.repository.ProviderRepository
import com.example.data.security.KeyStoreManager
import com.example.ui.components.ProviderIcon
import com.example.ui.screens.chat.isProviderReady
import com.example.ui.screens.chat.providerModels
import com.example.ui.theme.AppColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

@Composable
fun ProvidersScreen(
    providerRepository: ProviderRepository,
    modifier: Modifier = Modifier,
    startWithAdd: Boolean = false,
    startEditId: String? = null,
    onFinished: (() -> Unit)? = null
) {
    val providers by providerRepository.allProviders.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var editingId by remember { mutableStateOf(startEditId) }
    var showAdd by remember { mutableStateOf(startWithAdd) }
    var testingAll by remember { mutableStateOf(false) }
    var testAllSummary by remember { mutableStateOf<String?>(null) }

    val editing = providers.firstOrNull { it.id == editingId }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.bg)
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Providers & API Keys",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Models are fetched live from each provider's server when you tap Test Connection. Reorder providers to set your fallback priority chain.",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = AppColors.textSecondary
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    enabled = !testingAll,
                    onClick = {
                        testingAll = true
                        testAllSummary = null
                        scope.launch {
                            try {
                                val results = providerRepository.testAllConfiguredProviders()
                                val okCount = results.count { it.ok }
                                val totalModels = results.sumOf { it.modelCount }
                                testAllSummary = if (results.isEmpty()) {
                                    "No providers have API keys configured yet."
                                } else {
                                    "Tested ${results.size} provider(s): $okCount verified, $totalModels server models cached."
                                }
                            } finally {
                                testingAll = false
                            }
                        }
                    }
                ) {
                    if (testingAll) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = AppColors.accent)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text("Test All", fontSize = 12.sp, color = AppColors.accent)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { showAdd = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.accent,
                        contentColor = AppColors.onAccent
                    )
                ) { Text("Add", fontSize = 13.sp) }
            }

            if (testAllSummary != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = AppColors.accentSoft,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = testAllSummary ?: "",
                        fontSize = 12.sp,
                        color = AppColors.textPrimary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(providers, key = { _, p -> p.id }) { index, provider ->
                ProviderRow(
                    index = index + 1,
                    provider = provider,
                    canMoveUp = index > 0,
                    canMoveDown = index < providers.size - 1,
                    onClick = { editingId = provider.id },
                    onQuickTest = {
                        scope.launch {
                            val res = providerRepository.testAndFetchModels(provider)
                            Toast.makeText(
                                context,
                                if (res.ok) "✓ ${provider.name}: ${res.modelCount} models (${res.latencyMs} ms)"
                                else "✗ ${provider.name}: ${res.errorMessage}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    onMoveUp = {
                        val ids = providers.map { it.id }.toMutableList()
                        val item = ids.removeAt(index)
                        ids.add(index - 1, item)
                        scope.launch { providerRepository.reorderProviders(ids) }
                    },
                    onMoveDown = {
                        val ids = providers.map { it.id }.toMutableList()
                        val item = ids.removeAt(index)
                        ids.add(index + 1, item)
                        scope.launch { providerRepository.reorderProviders(ids) }
                    }
                )
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    if (editing != null) {
        ProviderEditor(
            provider = editing,
            repo = providerRepository,
            onDismiss = {
                editingId = null
                onFinished?.invoke()
            }
        )
    }
    if (showAdd) {
        AddProviderDialog(
            repo = providerRepository,
            nextPriority = providers.size + 1,
            onDismiss = {
                showAdd = false
                onFinished?.invoke()
            }
        )
    }
}

@Composable
private fun ProviderRow(
    index: Int,
    provider: ProviderConfigEntity,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onQuickTest: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    val hasKey = provider.encryptedApiKey.isNotEmpty() || isProviderReady(provider)
    val now = System.currentTimeMillis()
    val cooling = provider.cooldownUntilTimestamp > now
    val modelCount = remember(provider.modelsJson) { providerModels(provider).size }

    val (statusText, statusColor) = when {
        !provider.isEnabled -> Pair("Disabled", AppColors.textMuted)
        !hasKey -> Pair("No API key configured", AppColors.textMuted)
        cooling -> Pair("Cooldown (${(provider.cooldownUntilTimestamp - now) / 1000}s) · ${provider.lastErrorClass ?: "RATE_LIMIT"}", AppColors.warn)
        provider.lastTestStatus == "ERROR" -> Pair("Error (${provider.lastErrorClass ?: "Check key/URL"})", AppColors.error)
        provider.lastTestStatus == "OK" -> Pair("Verified · ${provider.lastLatencyMs ?: 0} ms · $modelCount models", AppColors.ok)
        else -> Pair("Key saved · Tap Test to fetch models", AppColors.warn)
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = AppColors.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, AppColors.border, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProviderIcon(
                providerId = provider.id,
                model = provider.selectedModel,
                size = 32.dp,
                providerName = provider.name
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$index.  ${provider.name}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = provider.selectedModel.ifBlank { "No model selected yet — tap Test Connection" },
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (provider.selectedModel.isBlank()) AppColors.textMuted else AppColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(statusText, fontSize = 11.sp, color = statusColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (hasKey) {
                IconButton(onClick = onQuickTest, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = "Test & fetch models", tint = AppColors.accent, modifier = Modifier.size(18.dp))
                }
            }
            Column {
                IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = "Move up",
                        tint = if (canMoveUp) AppColors.textSecondary else AppColors.border
                    )
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "Move down",
                        tint = if (canMoveDown) AppColors.textSecondary else AppColors.border
                    )
                }
            }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AppColors.accent,
    unfocusedBorderColor = AppColors.border,
    focusedLabelColor = AppColors.accent,
    unfocusedLabelColor = AppColors.textMuted,
    focusedTextColor = AppColors.textPrimary,
    unfocusedTextColor = AppColors.textPrimary,
    cursorColor = AppColors.accent,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedPlaceholderColor = AppColors.textMuted,
    unfocusedPlaceholderColor = AppColors.textMuted
)

@Composable
private fun FormField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    secret: Boolean = false,
    mono: Boolean = false
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = if (placeholder.isNotEmpty()) ({ Text(placeholder, fontSize = 13.sp) }) else null,
        singleLine = true,
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (secret) ({
            TextButton(onClick = { visible = !visible }) {
                Text(if (visible) "Hide" else "Show", fontSize = 12.sp, color = AppColors.accent)
            }
        }) else null,
        textStyle = androidx.compose.ui.text.TextStyle(
            fontSize = 14.sp,
            fontFamily = if (mono || secret) FontFamily.Monospace else FontFamily.Default
        ),
        colors = fieldColors(),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SelectChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) AppColors.accentSoft else AppColors.surfaceAlt,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = if (selected) AppColors.accent else AppColors.textSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = AppColors.textMuted,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun ProviderEditor(
    provider: ProviderConfigEntity,
    repo: ProviderRepository,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val originalKey = remember(provider.id) { KeyStoreManager.decrypt(provider.encryptedApiKey) }
    var keyText by remember(provider.id) { mutableStateOf(originalKey) }
    var keyLabel by remember(provider.id) { mutableStateOf(provider.keyLabel) }
    var modelText by remember(provider.id) { mutableStateOf(provider.selectedModel) }
    var urlText by remember(provider.id) { mutableStateOf(provider.baseUrl) }
    var enabled by remember(provider.id) { mutableStateOf(provider.isEnabled) }
    var modelList by remember(provider.id, provider.modelsJson) { mutableStateOf(providerModels(provider)) }
    var testing by remember { mutableStateOf(false) }
    var diagResult by remember { mutableStateOf<ProviderDiagnosticResult?>(null) }
    var showBackupKeyForm by remember { mutableStateOf(false) }
    var backupKeyLabel by remember { mutableStateOf("Backup Key") }
    var backupKeyValue by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(false) }
    var headers by remember(provider.id) { mutableStateOf(provider.customHeadersJson ?: "") }
    var vision by remember(provider.id) { mutableStateOf(provider.supportsVision) }
    var tools by remember(provider.id) { mutableStateOf(provider.supportsTools) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    fun draft(): ProviderConfigEntity = provider.copy(
        baseUrl = urlText.trim().trimEnd('/'),
        selectedModel = modelText.trim(),
        keyLabel = keyLabel.trim().ifBlank { "Primary" },
        encryptedApiKey = if (keyText.isBlank()) "" else KeyStoreManager.encrypt(keyText.trim()),
        isEnabled = enabled,
        customHeadersJson = headers.trim().ifBlank { null },
        supportsVision = vision,
        supportsTools = tools
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = AppColors.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp)
                .heightIn(max = 680.dp)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProviderIcon(
                        providerId = provider.id,
                        model = modelText,
                        size = 36.dp,
                        providerName = provider.name
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(provider.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary)
                        Text(
                            text = provider.apiFormat.lowercase().replaceFirstChar { it.uppercase() } + " API",
                            fontSize = 12.sp,
                            color = AppColors.textMuted
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = AppColors.onAccent,
                            checkedTrackColor = AppColors.accent
                        )
                    )
                }

                FormField(
                    label = "Base URL",
                    value = urlText,
                    onChange = { urlText = it },
                    placeholder = "https://api.example.com/v1",
                    mono = true
                )

                FormField(
                    label = if (provider.id == "ollama") "API key (optional for local server)" else "Primary API Key (${keyLabel.ifBlank { "Primary" }})",
                    value = keyText,
                    onChange = { keyText = it },
                    placeholder = "Paste your API key",
                    secret = true
                )

                // Test Connection & Fetch Live Models Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        enabled = !testing,
                        onClick = {
                            testing = true
                            errorMessage = null
                            scope.launch {
                                try {
                                    val d = draft()
                                    repo.updateProvider(d)
                                    val res = repo.testAndFetchModels(d)
                                    diagResult = res
                                    if (res.ok) {
                                        val refreshed = repo.allProviders.first().firstOrNull { it.id == provider.id }
                                        if (refreshed != null) {
                                            modelList = providerModels(refreshed)
                                            if (modelText.isBlank() && refreshed.selectedModel.isNotBlank()) {
                                                modelText = refreshed.selectedModel
                                            }
                                        }
                                    }
                                } finally {
                                    testing = false
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppColors.accent,
                            contentColor = AppColors.onAccent
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (testing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.onAccent)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Testing & fetching models…", fontSize = 13.sp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Test Connection & Load Models", fontSize = 13.sp)
                        }
                    }
                }

                // Live Diagnostic Card
                diagResult?.let { diag ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (diag.ok) AppColors.diffAddBg else AppColors.diffRemoveBg,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (diag.ok) Icons.Default.Check else Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = if (diag.ok) AppColors.ok else AppColors.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (diag.ok) "Connected (${diag.latencyMs} ms) · ${diag.modelCount} models fetched from server"
                                    else "Connection Failed (${diag.errorClass?.name ?: "ERROR"})",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (diag.ok) AppColors.diffAddText else AppColors.diffRemoveText
                                )
                            }
                            if (!diag.ok && !diag.errorMessage.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(diag.errorMessage.orEmpty(), fontSize = 12.sp, color = AppColors.textPrimary)
                                diag.errorClass?.let { ec ->
                                    Text("Fix: ${ec.suggestedFix}", fontSize = 11.sp, color = AppColors.textSecondary)
                                }
                            }
                        }
                    }
                }

                SectionLabel("SELECTED MODEL (SERVER-FETCHED OR CUSTOM ID)")
                FormField(
                    label = "Model ID",
                    value = modelText,
                    onChange = { modelText = it },
                    placeholder = "Tap Test Connection above or type a custom model ID",
                    mono = true
                )
                if (modelList.isNotEmpty()) {
                    Text("Fetched from ${provider.name} server (${modelList.size}):", fontSize = 11.sp, color = AppColors.textSecondary)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(modelList) { m ->
                            SelectChip(text = m, selected = m == modelText.trim(), onClick = { modelText = m })
                        }
                    }
                }

                // Multi-key rotation support (Section 7.2)
                TextButton(onClick = { showBackupKeyForm = !showBackupKeyForm }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = AppColors.accent)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        if (showBackupKeyForm) "Hide backup key form" else "Add backup API key for automatic 429 rotation",
                        fontSize = 12.sp,
                        color = AppColors.accent
                    )
                }
                if (showBackupKeyForm) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = AppColors.surfaceAlt,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FormField(label = "Key label (e.g. Work, Free Tier 2)", value = backupKeyLabel, onChange = { backupKeyLabel = it })
                            FormField(label = "Backup API Key", value = backupKeyValue, onChange = { backupKeyValue = it }, secret = true)
                            Button(
                                onClick = {
                                    if (backupKeyValue.isNotBlank()) {
                                        scope.launch {
                                            repo.addAdditionalKeyForProvider(provider.id, backupKeyLabel.ifBlank { "Backup" }, backupKeyValue)
                                            backupKeyValue = ""
                                            showBackupKeyForm = false
                                            Toast.makeText(context, "Backup key added to rotation pool", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent, contentColor = AppColors.onAccent)
                            ) {
                                Text("Save Backup Key", fontSize = 12.sp)
                            }
                        }
                    }
                }

                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text(if (showAdvanced) "Hide advanced" else "Advanced options", fontSize = 13.sp, color = AppColors.textSecondary)
                }
                if (showAdvanced) {
                    FormField(
                        label = "Custom headers (JSON, optional)",
                        value = headers,
                        onChange = { headers = it },
                        placeholder = "{\"X-Header\": \"value\"}",
                        mono = true
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Supports images / vision", fontSize = 14.sp, color = AppColors.textPrimary, modifier = Modifier.weight(1f))
                        Switch(checked = vision, onCheckedChange = { vision = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Supports tool calling", fontSize = 14.sp, color = AppColors.textPrimary, modifier = Modifier.weight(1f))
                        Switch(checked = tools, onCheckedChange = { tools = it })
                    }
                }

                if (errorMessage != null) {
                    Text(errorMessage ?: "", fontSize = 12.sp, color = AppColors.error)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel", color = AppColors.textSecondary) }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        enabled = !saving,
                        onClick = {
                            val u = urlText.trim()
                            val headersOk = headers.isBlank() || try {
                                JSONObject(headers)
                                true
                            } catch (_: Exception) {
                                false
                            }
                            when {
                                u.isBlank() || !(u.startsWith("http://") || u.startsWith("https://")) ->
                                    errorMessage = "Enter a valid base URL starting with http:// or https://"
                                !headersOk -> errorMessage = "Custom headers must be valid JSON."
                                else -> {
                                    errorMessage = null
                                    saving = true
                                    scope.launch {
                                        try {
                                            val updated = draft()
                                            repo.updateProvider(updated)
                                            if (modelText.isNotBlank()) {
                                                repo.addCustomModelId(provider.id, modelText.trim())
                                            }
                                            Toast.makeText(context, "Provider saved", Toast.LENGTH_SHORT).show()
                                            onDismiss()
                                        } catch (e: kotlinx.coroutines.CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            errorMessage = "Could not save: ${e.message ?: e.javaClass.simpleName}"
                                        } finally {
                                            saving = false
                                        }
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppColors.accent,
                            contentColor = AppColors.onAccent
                        )
                    ) { Text("Save") }
                }
            }
        }
    }
}

@Composable
fun AddProviderDialog(
    repo: ProviderRepository,
    nextPriority: Int = 11,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("https://") }
    var apiKey by remember { mutableStateOf("") }
    var modelText by remember { mutableStateOf("") }
    var apiFormat by remember { mutableStateOf("OPENAI") }
    var errorText by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = AppColors.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Add Custom Provider", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary)
                Text(
                    "Connect any OpenAI-compatible (e.g. TokenHarbor, OpenRouter, Groq), Anthropic, or Gemini endpoint with live /models discovery.",
                    fontSize = 12.sp,
                    color = AppColors.textSecondary
                )
                Text("1-Tap Presets:", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textMuted)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val presets = listOf(
                        Triple("TokenHarbor", "https://api.tokenharbor.ai/v1", "OPENAI"),
                        Triple("OpenRouter", "https://openrouter.ai/api/v1", "OPENAI"),
                        Triple("Groq Cloud", "https://api.groq.com/openai/v1", "OPENAI"),
                        Triple("Local Termux LLM", "http://127.0.0.1:8080/v1", "OPENAI")
                    )
                    items(presets) { (pName, pUrl, pFmt) ->
                        SelectChip(
                            text = pName,
                            selected = name == pName,
                            onClick = {
                                name = pName
                                baseUrl = pUrl
                                apiFormat = pFmt
                            }
                        )
                    }
                }
                FormField(label = "Display Name", value = name, onChange = { name = it }, placeholder = "e.g. TokenHarbor, Together AI, vLLM")
                FormField(label = "Base URL", value = baseUrl, onChange = { baseUrl = it }, placeholder = "https://api.example.com/v1", mono = true)
                FormField(label = "API Key (optional for local)", value = apiKey, onChange = { apiKey = it }, secret = true)
                FormField(label = "Optional Custom Model ID", value = modelText, onChange = { modelText = it }, placeholder = "Or leave blank and Test Connection", mono = true)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (fmt in listOf("OPENAI", "ANTHROPIC", "GEMINI")) {
                        SelectChip(text = fmt, selected = apiFormat == fmt, onClick = { apiFormat = fmt })
                    }
                }

                if (errorText != null) {
                    Text(errorText ?: "", fontSize = 12.sp, color = AppColors.error)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel", color = AppColors.textSecondary) }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (name.isBlank() || !baseUrl.startsWith("http")) {
                                errorText = "Please enter a name and a valid http/https Base URL."
                                return@Button
                            }
                            scope.launch {
                                val id = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') + "-" + UUID.randomUUID().toString().take(4)
                                val entity = ProviderConfigEntity(
                                    id = id,
                                    name = name.trim(),
                                    apiFormat = apiFormat,
                                    baseUrl = baseUrl.trim().trimEnd('/'),
                                    selectedModel = modelText.trim(),
                                    modelsJson = if (modelText.isNotBlank()) "[\"${modelText.trim()}\"]" else "[]",
                                    encryptedApiKey = if (apiKey.isBlank()) "" else KeyStoreManager.encrypt(apiKey.trim()),
                                    priority = nextPriority,
                                    isEnabled = true
                                )
                                repo.updateProvider(entity)
                                if (apiKey.isNotBlank() || baseUrl.contains("127.0.0.1") || baseUrl.contains("localhost")) {
                                    repo.testAndFetchModels(entity)
                                }
                                onDismiss()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent, contentColor = AppColors.onAccent)
                    ) {
                        Text("Add & Test")
                    }
                }
            }
        }
    }
}
