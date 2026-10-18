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
    var apiStatus by remember { mutableStateOf<ApiStatus?>(null) }
    var fetching by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun draft(): ProviderConfigEntity = provider.copy(
        baseUrl = urlText.trim().trimEnd('/'),
        selectedModel = modelText.trim(),
        encryptedApiKey = KeyStoreManager.encrypt(keyText.trim()),
        isEnabled = enabled
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
                var showAdvanced by remember { androidx.compose.runtime.mutableStateOf(false) }
                var headers by remember { androidx.compose.runtime.mutableStateOf("") }
                var vision by remember { androidx.compose.runtime.mutableStateOf(false) }
                var tools by remember { androidx.compose.runtime.mutableStateOf(false) }
                var errorMessage by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
                var saving by remember { androidx.compose.runtime.mutableStateOf(false) }
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
                            } catch (e: Exception) {
                                false
                            }
                            when {
                                u.isBlank() -> errorMessage = "Enter the full base URL, for example https://api.example.com/v1"
                                !(u.startsWith("http://") || u.startsWith("https://")) || u.length < 10 ->
                                    errorMessage = "Enter the full base URL, for example https://api.example.com/v1"
                                keyText.isBlank() -> errorMessage = "Enter the API key."
                                modelText.isBlank() -> errorMessage = "Enter a model ID, or load the model list."
                                !headersOk -> errorMessage = "Custom headers must be valid JSON."
                                else -> {
                                    errorMessage = null
                                    saving = true
                                    scope.launch {
                                        try {
                                            // Update the existing provider
                                            repo.updateProvider(draft())
                                            // Persist advanced fields if needed (custom headers / vision/tools)
                                            repo.updateProvider(draft().copy(customHeadersJson = headers.ifBlank { null }, supportsVision = vision, supportsTools = tools))
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


private data class ApiStatus(
    val active: Boolean,
    val message: String,
    val total: Int = 0,
    val free: Int = 0,
    val pricingKnown: Boolean = false,
    val shown: Int = 0,
    val freeOnly: Boolean = false
)

/** Green circle + "API is active" with the number of models, or a red circle with the error. */
@Composable
private fun ApiStatusLine(st: ApiStatus) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (st.active) AppColors.diffAddBg else AppColors.diffRemoveBg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (st.active) AppColors.ok else AppColors.error)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = if (st.active) "API is active" else "API is not working",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (st.active) AppColors.diffAddText else AppColors.diffRemoveText
                )
                if (st.active) {
                    if (st.total > 0) {
                        val line = buildString {
                            append("${st.total} models available")
                            if (st.pricingKnown) append("  ·  ${st.free} free")
                            if (st.freeOnly && st.pricingKnown) append("  ·  importing ${st.shown} free")
                        }
                        Text(line, fontSize = 12.sp, color = AppColors.textPrimary)
                        if (st.freeOnly && !st.pricingKnown) {
                            Text(
                                "This provider does not say which models are free, so all models are shown.",
                                fontSize = 11.sp,
                                color = AppColors.textSecondary
                            )
                        }
                    } else {
                        Text("The key works. This provider has no model list.", fontSize = 12.sp, color = AppColors.textPrimary)
                    }
                } else {
                    Text(st.message, fontSize = 12.sp, color = AppColors.textPrimary)
                }
            }
        }
    }
}


@Composable
fun AddProviderDialog(
    repo: ProviderRepository,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var modelText by remember { mutableStateOf("") }
    
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Card {
            Column(modifier = androidx.compose.ui.Modifier.padding(16.dp)) {
                Text("Add Provider", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Base URL") })
                OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("API Key") })
                OutlinedTextField(value = modelText, onValueChange = { modelText = it }, label = { Text("Model") })
                Spacer(modifier = androidx.compose.ui.Modifier.height(16.dp))
                androidx.compose.material3.Button(onClick = {
                    // Add logic here
                    onDismiss()
                }) {
                    Text("Save")
                }
            }
        }
    }
}
// Force update
