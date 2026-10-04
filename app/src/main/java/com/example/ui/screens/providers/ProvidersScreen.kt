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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import com.example.data.repository.ProviderRepository
import com.example.data.security.KeyStoreManager
import com.example.ui.components.ProviderIcon
import com.example.ui.screens.chat.isProviderReady
import com.example.ui.screens.chat.providerModels
import com.example.ui.theme.AppColors
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val PRESET_IDS = setOf("anthropic", "openai", "gemini", "deepseek", "kimi", "xai", "groq", "openrouter", "mistral", "ollama")

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
    var editingId by remember { mutableStateOf(startEditId) }
    var showAdd by remember { mutableStateOf(startWithAdd) }

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
                        text = "Models & API keys",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Tap a provider to add its API key. They are used from top to bottom: when one hits a limit, the next takes over.",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = AppColors.textSecondary
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = { showAdd = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.accent,
                        contentColor = AppColors.onAccent
                    )
                ) { Text("Add", fontSize = 13.sp) }
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
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    val hasKey = provider.encryptedApiKey.isNotEmpty() || isProviderReady(provider)
    val now = System.currentTimeMillis()
    val cooling = provider.cooldownUntilTimestamp > now
    val (statusText, statusColor) = when {
        !provider.isEnabled -> Pair("Off", AppColors.textMuted)
        !hasKey -> Pair("No API key", AppColors.textMuted)
        cooling -> Pair("Paused · limit or error, back in ${(provider.cooldownUntilTimestamp - now) / 1000}s", AppColors.warn)
        else -> Pair("Ready", AppColors.ok)
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
                    text = provider.selectedModel,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = AppColors.textSecondary,
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
                    Text(statusText, fontSize = 11.sp, color = statusColor)
                }
            }
            Column {
                IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = "Move up",
                        tint = if (canMoveUp) AppColors.textSecondary else AppColors.border
                    )
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(32.dp)) {
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

// ------------------------------------------------------------------------------------------
// Shared form widgets
// ------------------------------------------------------------------------------------------

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

// ------------------------------------------------------------------------------------------
// Edit provider
// ------------------------------------------------------------------------------------------

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
    var modelText by remember(provider.id) { mutableStateOf(provider.selectedModel) }
    var urlText by remember(provider.id) { mutableStateOf(provider.baseUrl) }
    var enabled by remember(provider.id) { mutableStateOf(provider.isEnabled) }
    var modelList by remember(provider.id) { mutableStateOf(providerModels(provider)) }
    var testing by remember { mutableStateOf(false) }
    var fetching by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun draft(): ProviderConfigEntity = provider.copy(
        baseUrl = urlText.trim().trimEnd('/'),
        selectedModel = modelText.trim(),
        encryptedApiKey = KeyStoreManager.encrypt(keyText.trim())
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
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .heightIn(max = 640.dp)
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
                    label = if (provider.id == "ollama") "API key (not needed for local)" else "API key",
                    value = keyText,
                    onChange = { keyText = it },
                    placeholder = "Paste your key",
                    secret = true
                )

                SectionLabel("MODEL")
                FormField(label = "Model name", value = modelText, onChange = { modelText = it }, mono = true)
                if (modelList.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(modelList) { m ->
                            SelectChip(text = m, selected = m == modelText.trim(), onClick = { modelText = m })
                        }
                    }
                }
                TextButton(
                    enabled = !fetching,
                    onClick = {
                        scope.launch {
                            fetching = true
                            try {
                                val list = repo.fetchModels(draft())
                                if (list.isEmpty()) {
                                    Toast.makeText(context, "No models returned. Check the key and URL.", Toast.LENGTH_SHORT).show()
                                } else {
                                    modelList = list
                                    if (modelText.isBlank() || modelText.trim() !in list) modelText = list.first()
                                    Toast.makeText(context, "Loaded ${list.size} models", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Toast.makeText(context, "Could not load models: ${e.message?.take(140)}", Toast.LENGTH_LONG).show()
                            } finally {
                                fetching = false
                            }
                        }
                    }
                ) {
                    Text(if (fetching) "Loading…" else "Load model list from provider", fontSize = 13.sp, color = AppColors.accent)
                }

                SectionLabel("ADVANCED")
                FormField(label = "Base URL", value = urlText, onChange = { urlText = it }, mono = true)

                OutlinedButton(
                    enabled = !testing,
                    onClick = {
                        scope.launch {
                            testing = true
                            testMessage = null
                            val err = repo.testConnectionError(draft())
                            testOk = err == null
                            testMessage = err ?: "Connection works"
                            testing = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (testing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.accent)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("Test connection", color = AppColors.textPrimary)
                }
                if (testMessage != null) {
                    Text(
                        text = testMessage ?: "",
                        fontSize = 12.sp,
                        color = if (testOk) AppColors.ok else AppColors.error
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (provider.id !in PRESET_IDS) {
                        TextButton(onClick = { confirmDelete = true }) {
                            Text("Delete", color = AppColors.error)
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel", color = AppColors.textSecondary) }
                    Button(
                        onClick = {
                            scope.launch {
                                repo.updateProvider(
                                    provider.copy(
                                        baseUrl = urlText.trim().trimEnd('/'),
                                        selectedModel = modelText.trim().ifEmpty { provider.selectedModel },
                                        isEnabled = enabled,
                                        modelsJson = JSONArray(modelList).toString(),
                                        cooldownUntilTimestamp = 0L,
                                        lastError = null
                                    )
                                )
                                if (keyText.trim() != originalKey) repo.saveProviderApiKey(provider.id, keyText)
                                Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                                onDismiss()
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

    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete provider?") },
            text = { Text("${provider.name} and its saved API key will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repo.deleteProvider(provider.id)
                        confirmDelete = false
                        onDismiss()
                    }
                }) { Text("Delete", color = AppColors.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

// ------------------------------------------------------------------------------------------
// Add provider
// ------------------------------------------------------------------------------------------

@Composable
private fun AddProviderDialog(
    repo: ProviderRepository,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var format by remember { mutableStateOf("OPENAI") }
    var url by remember { mutableStateOf("https://") }
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var headers by remember { mutableStateOf("") }
    var vision by remember { mutableStateOf(true) }
    var tools by remember { mutableStateOf(true) }
    var showAdvanced by remember { mutableStateOf(false) }
    var fetching by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = AppColors.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .heightIn(max = 660.dp)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Add provider", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary)
                Text(
                    "Any service with an OpenAI-compatible, Anthropic or Gemini API.",
                    fontSize = 12.sp,
                    color = AppColors.textSecondary
                )

                FormField(label = "Name", value = name, onChange = { name = it }, placeholder = "e.g. My provider")

                SectionLabel("API TYPE")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SelectChip("OpenAI-compatible", format == "OPENAI") { format = "OPENAI" }
                    SelectChip("Anthropic", format == "ANTHROPIC") { format = "ANTHROPIC" }
                    SelectChip("Gemini", format == "GEMINI") { format = "GEMINI" }
                }

                FormField(
                    label = "Base URL",
                    value = url,
                    onChange = { url = it },
                    placeholder = "https://api.example.com/v1",
                    mono = true
                )
                FormField(label = "API key", value = key, onChange = { key = it }, placeholder = "Paste your key", secret = true)
                FormField(label = "Model", value = model, onChange = { model = it }, placeholder = "model name", mono = true)

                if (models.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(models) { m -> SelectChip(text = m, selected = m == model.trim(), onClick = { model = m }) }
                    }
                }
                TextButton(
                    enabled = !fetching && url.trim().length > 10,
                    onClick = {
                        scope.launch {
                            fetching = true
                            error = null
                            try {
                                val temp = ProviderConfigEntity(
                                    id = "temp",
                                    name = name,
                                    baseUrl = url.trim().trimEnd('/'),
                                    apiFormat = format,
                                    encryptedApiKey = KeyStoreManager.encrypt(key.trim()),
                                    modelsJson = "[]",
                                    selectedModel = model,
                                    priority = 0,
                                    customHeadersJson = headers.ifBlank { null }
                                )
                                models = repo.fetchModels(temp)
                                if (models.isNotEmpty() && model.isBlank()) model = models.first()
                                if (models.isEmpty()) error = "The provider returned no models."
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = "Could not load models: ${e.message?.take(160)}"
                            } finally {
                                fetching = false
                            }
                        }
                    }
                ) {
                    Text(if (fetching) "Loading…" else "Load model list from provider", fontSize = 13.sp, color = AppColors.accent)
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
                        Text("Supports images", fontSize = 14.sp, color = AppColors.textPrimary, modifier = Modifier.weight(1f))
                        Switch(checked = vision, onCheckedChange = { vision = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Supports tool calling (needed by the agent)", fontSize = 14.sp, color = AppColors.textPrimary, modifier = Modifier.weight(1f))
                        Switch(checked = tools, onCheckedChange = { tools = it })
                    }
                }

                if (error != null) {
                    Text(error ?: "", fontSize = 12.sp, color = AppColors.error)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel", color = AppColors.textSecondary) }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        enabled = !saving,
                        onClick = {
                            val u = url.trim()
                            val headersOk = headers.isBlank() || try {
                                JSONObject(headers)
                                true
                            } catch (e: Exception) {
                                false
                            }
                            when {
                                name.isBlank() -> error = "Enter a name."
                                !(u.startsWith("http://") || u.startsWith("https://")) || u.length < 10 ->
                                    error = "Enter the full base URL, for example https://api.example.com/v1"
                                model.isBlank() -> error = "Enter a model name, or load the list from the provider."
                                !headersOk -> error = "Custom headers must be valid JSON."
                                else -> {
                                    error = null
                                    saving = true
                                    scope.launch {
                                        try {
                                            repo.addCustomProvider(
                                                name = name.trim(),
                                                baseUrl = u,
                                                apiFormat = format,
                                                rawApiKey = key.trim(),
                                                models = models.ifEmpty { listOf(model.trim()) },
                                                selectedModel = model.trim(),
                                                customHeadersJson = headers.ifBlank { null },
                                                supportsVision = vision,
                                                supportsTools = tools
                                            )
                                            Toast.makeText(context, "Provider added", Toast.LENGTH_SHORT).show()
                                            onDismiss()
                                        } catch (e: kotlinx.coroutines.CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            error = "Could not save: ${e.message ?: e.javaClass.simpleName}"
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
                    ) { Text("Add provider") }
                }
            }
        }
    }
}
