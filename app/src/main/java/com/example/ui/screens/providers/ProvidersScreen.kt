package com.example.ui.screens.providers

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entity.ProviderConfigEntity
import com.example.data.repository.ProviderRepository
import com.example.data.security.KeyStoreManager
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.ForgeAmber
import com.example.ui.theme.RoseError
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface

@Composable
fun ProvidersScreen(
    providerRepository: ProviderRepository,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val providers by providerRepository.allProviders.collectAsState(initial = emptyList())

    var showAddCustomDialog by remember { mutableStateOf(false) }
    var editingKeyProviderId by remember { mutableStateOf<String?>(null) }
    var rawKeyInput by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }

    val testingState = remember { mutableStateMapOf<String, Boolean>() }
    val testResult = remember { mutableStateMapOf<String, Boolean?>() }
    val testError = remember { mutableStateMapOf<String, String?>() }
    val fetchingState = remember { mutableStateMapOf<String, Boolean>() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F17))
    ) {
        // Top Toolbar
        Surface(
            color = Color(0xFF111827),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Dns,
                            contentDescription = "Providers",
                            tint = CyberCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "AI Providers & Failover",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF1F5F9)
                        )
                    }
                    Text(
                        text = "Ordered by priority. Auto-switches on 429 rate limit or outage.",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                }

                Button(
                    onClick = { showAddCustomDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFF003549))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Custom", fontSize = 12.sp, color = Color(0xFF003549))
                }
            }
        }

        // Failover Chain List
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(providers, key = { _, p -> p.id }) { index, provider ->
                ProviderCard(
                    provider = provider,
                    isFirst = index == 0,
                    isLast = index == providers.size - 1,
                    isTesting = testingState[provider.id] == true,
                    testSuccess = testResult[provider.id],
                    testError = testError[provider.id],
                    isFetching = fetchingState[provider.id] == true,
                    onMoveUp = {
                        val reordered = providers.map { it.id }.toMutableList()
                        val item = reordered.removeAt(index)
                        reordered.add(index - 1, item)
                        scope.launch { providerRepository.reorderProviders(reordered) }
                    },
                    onMoveDown = {
                        val reordered = providers.map { it.id }.toMutableList()
                        val item = reordered.removeAt(index)
                        reordered.add(index + 1, item)
                        scope.launch { providerRepository.reorderProviders(reordered) }
                    },
                    onToggleEnabled = { enabled ->
                        scope.launch { providerRepository.updateProvider(provider.copy(isEnabled = enabled)) }
                    },
                    onEditKey = {
                        editingKeyProviderId = provider.id
                        rawKeyInput = KeyStoreManager.decrypt(provider.encryptedApiKey)
                        keyVisible = false
                    },
                    onTestConnection = {
                        scope.launch {
                            testingState[provider.id] = true
                            testResult[provider.id] = null
                            val error = providerRepository.testConnectionError(provider)
                            testingState[provider.id] = false
                            testResult[provider.id] = (error == null)
                            testError[provider.id] = error
                            Toast.makeText(
                                context,
                                if (error == null) "Connection OK!" else "Connection failed. See the error under the provider.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    onFetchModels = {
                        scope.launch {
                            fetchingState[provider.id] = true
                            try {
                                val list = providerRepository.fetchModels(provider)
                                if (list.isEmpty()) {
                                    Toast.makeText(context, "The provider returned no models.", Toast.LENGTH_SHORT).show()
                                } else {
                                    val selected = if (provider.selectedModel in list) provider.selectedModel else list.first()
                                    providerRepository.updateProvider(
                                        provider.copy(modelsJson = JSONArray(list).toString(), selectedModel = selected)
                                    )
                                    Toast.makeText(context, "Loaded ${list.size} models. Tap one to select.", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Toast.makeText(context, "Could not fetch models: ${e.message?.take(140)}", Toast.LENGTH_LONG).show()
                            } finally {
                                fetchingState[provider.id] = false
                            }
                        }
                    },
                    onSelectModel = { model ->
                        scope.launch { providerRepository.updateProvider(provider.copy(selectedModel = model)) }
                    }
                )
            }
        }
    }

    // Edit API Key Dialog
    if (editingKeyProviderId != null) {
        val targetProvider = providers.find { it.id == editingKeyProviderId }
        AlertDialog(
            onDismissRequest = { editingKeyProviderId = null },
            title = { Text("Configure API Key: ${targetProvider?.name}") },
            text = {
                Column {
                    Text(
                        "Stored securely with Android Keystore AES-GCM hardware encryption.",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = rawKeyInput,
                        onValueChange = { rawKeyInput = it },
                        label = { Text("API Key") },
                        trailingIcon = {
                            IconButton(onClick = { keyVisible = !keyVisible }) {
                                Icon(
                                    imageVector = if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle visibility"
                                )
                            }
                        },
                        visualTransformation = if (keyVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            providerRepository.saveProviderApiKey(editingKeyProviderId!!, rawKeyInput.trim())
                            editingKeyProviderId = null
                            Toast.makeText(context, "API Key saved securely.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberCyan)
                ) {
                    Text("Save Key", color = Color(0xFF003549))
                }
            },
            dismissButton = {
                TextButton(onClick = { editingKeyProviderId = null }) { Text("Cancel") }
            }
        )
    }

    // Add Custom Provider Dialog
    if (showAddCustomDialog) {
        var customName by remember { mutableStateOf("") }
        var customUrl by remember { mutableStateOf("https://") }
        var customFormat by remember { mutableStateOf("OPENAI") }
        var customKey by remember { mutableStateOf("") }
        var customModel by remember { mutableStateOf("default-model") }
        var customHeaders by remember { mutableStateOf("") }
        var customVision by remember { mutableStateOf(true) }
        var customTools by remember { mutableStateOf(true) }
        var customFetching by remember { mutableStateOf(false) }
        var customModels by remember { mutableStateOf<List<String>>(emptyList()) }
        var customError by remember { mutableStateOf<String?>(null) }
        var customSaving by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showAddCustomDialog = false },
            title = { Text("Add Custom Provider") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text("API format", fontSize = 11.sp, color = Color(0xFF94A3B8))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (fmt in listOf("OPENAI", "ANTHROPIC", "GEMINI")) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (customFormat == fmt) CyberCyan else Color(0xFF1E293B),
                                modifier = Modifier.clickable { customFormat = fmt }
                            ) {
                                Text(
                                    text = fmt,
                                    fontSize = 11.sp,
                                    color = if (customFormat == fmt) Color(0xFF003549) else Color(0xFFCBD5E1),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text("Provider Name (e.g. My LM Studio)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = { customUrl = it },
                        label = { Text("Base URL (e.g. https://host/v1 or http://localhost:1234/v1)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = customModel,
                        onValueChange = { customModel = it },
                        label = { Text("Model ID") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = customKey,
                        onValueChange = { customKey = it },
                        label = { Text("API Key (optional for local)") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = customHeaders,
                        onValueChange = { customHeaders = it },
                        label = { Text("Custom headers JSON (optional)") },
                        placeholder = { Text("{\"X-Header\": \"value\"}") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Supports images", fontSize = 12.sp)
                        Switch(checked = customVision, onCheckedChange = { customVision = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Supports tool calling (required for the agent)", fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Switch(checked = customTools, onCheckedChange = { customTools = it })
                    }
                    TextButton(
                        enabled = !customFetching && customUrl.length > 8,
                        onClick = {
                            scope.launch {
                                customFetching = true
                                try {
                                    val temp = ProviderConfigEntity(
                                        id = "temp",
                                        name = customName,
                                        baseUrl = customUrl.trim().trimEnd('/'),
                                        apiFormat = customFormat,
                                        encryptedApiKey = KeyStoreManager.encrypt(customKey.trim()),
                                        modelsJson = "[]",
                                        selectedModel = customModel,
                                        priority = 0,
                                        customHeadersJson = customHeaders.ifBlank { null }
                                    )
                                    customModels = providerRepository.fetchModels(temp)
                                    if (customModels.isNotEmpty() && customModel in listOf("", "default-model")) {
                                        customModel = customModels.first()
                                    }
                                    Toast.makeText(context, "Loaded ${customModels.size} models", Toast.LENGTH_SHORT).show()
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Could not fetch models: ${e.message?.take(140)}", Toast.LENGTH_LONG).show()
                                } finally {
                                    customFetching = false
                                }
                            }
                        }
                    ) {
                        Text(if (customFetching) "Fetching…" else "Fetch models from URL", fontSize = 12.sp, color = CyberCyan)
                    }
                    if (customError != null) {
                        Text(customError ?: "", fontSize = 12.sp, color = RoseError)
                    }
                    if (customModels.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(customModels) { m ->
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (m == customModel) CyberCyan else Color(0xFF1E293B),
                                    modifier = Modifier.clickable { customModel = m }
                                ) {
                                    Text(
                                        text = m,
                                        fontSize = 11.sp,
                                        color = if (m == customModel) Color(0xFF003549) else Color(0xFFCBD5E1),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !customSaving,
                    onClick = {
                        val url = customUrl.trim()
                        val headersOk = customHeaders.isBlank() || try {
                            JSONObject(customHeaders); true
                        } catch (e: Exception) {
                            false
                        }
                        when {
                            customName.isBlank() -> customError = "Enter a name for the provider."
                            !(url.startsWith("http://") || url.startsWith("https://")) || url.length < 10 ->
                                customError = "Enter the full base URL, e.g. https://api.example.com/v1"
                            customModel.isBlank() -> customError = "Enter a model name (or tap Fetch models)."
                            !headersOk -> customError = "Custom headers must be valid JSON."
                            else -> {
                                customError = null
                                customSaving = true
                                scope.launch {
                                    try {
                                        providerRepository.addCustomProvider(
                                            name = customName.trim(),
                                            baseUrl = url,
                                            apiFormat = customFormat,
                                            rawApiKey = customKey.trim(),
                                            models = (customModels.ifEmpty { listOf(customModel.trim()) }),
                                            selectedModel = customModel.trim(),
                                            customHeadersJson = customHeaders.ifBlank { null },
                                            supportsVision = customVision,
                                            supportsTools = customTools
                                        )
                                        Toast.makeText(context, "Provider added", Toast.LENGTH_SHORT).show()
                                        showAddCustomDialog = false
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        customError = "Could not save: ${e.message ?: e.javaClass.simpleName}"
                                    } finally {
                                        customSaving = false
                                    }
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberCyan)
                ) {
                    Text("Add Provider", color = Color(0xFF003549))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddCustomDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun ProviderCard(
    provider: ProviderConfigEntity,
    isFirst: Boolean,
    isLast: Boolean,
    isTesting: Boolean,
    testSuccess: Boolean?,
    testError: String?,
    isFetching: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onEditKey: () -> Unit,
    onTestConnection: () -> Unit,
    onFetchModels: () -> Unit,
    onSelectModel: (String) -> Unit
) {
    val plainKey = remember(provider.encryptedApiKey) { KeyStoreManager.decrypt(provider.encryptedApiKey) }
    val hasKey = plainKey.isNotEmpty() || provider.id == "ollama"
    val isCoolingDown = provider.cooldownUntilTimestamp > System.currentTimeMillis()

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isCoolingDown) ForgeAmber.copy(alpha = 0.5f) else Color(0xFF1F2937)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header: Priority, Name, Enabled Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Priority Badge
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(CyberCyan.copy(alpha = 0.2f), RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "#${provider.priority}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyberCyan
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = provider.name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFF1F5F9)
                        )
                        Text(
                            text = provider.apiFormat,
                            fontSize = 10.sp,
                            color = Color(0xFF64748B)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Up/Down reorder arrows
                    IconButton(onClick = onMoveUp, enabled = !isFirst, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move Up", tint = if (!isFirst) Color(0xFF94A3B8) else Color(0xFF334155))
                    }
                    IconButton(onClick = onMoveDown, enabled = !isLast, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move Down", tint = if (!isLast) Color(0xFF94A3B8) else Color(0xFF334155))
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Switch(
                        checked = provider.isEnabled,
                        onCheckedChange = onToggleEnabled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = CyberCyan,
                            checkedTrackColor = CyberCyan.copy(alpha = 0.4f)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Models dropdown / chip
            val modelsList = remember(provider.modelsJson) {
                try {
                    val arr = JSONArray(provider.modelsJson)
                    List(arr.length()) { arr.getString(it) }
                } catch (e: Exception) {
                    listOf(provider.selectedModel)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Model: ${provider.selectedModel}",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFCBD5E1)
                )

                // Key configuration button
                OutlinedButton(
                    onClick = onEditKey,
                    modifier = Modifier.height(28.dp)
                ) {
                    Text(
                        text = if (plainKey.isNotEmpty()) "Key: ${KeyStoreManager.maskKey(plainKey)}" else if (hasKey) "Local (no key)" else "Set Key",
                        fontSize = 11.sp,
                        color = if (hasKey) EmeraldSuccess else ForgeAmber
                    )
                }
            }

            if (modelsList.size > 1) {
                Spacer(modifier = Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(modelsList) { m ->
                        val sel = m == provider.selectedModel
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (sel) CyberCyan else Color(0xFF1E293B),
                            modifier = Modifier.clickable { onSelectModel(m) }
                        ) {
                            Text(
                                text = m,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (sel) Color(0xFF003549) else Color(0xFFCBD5E1),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }

            if (!provider.lastError.isNullOrBlank() && !isCoolingDown) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Last error: ${provider.lastError}",
                    fontSize = 10.sp,
                    color = Color(0xFF94A3B8),
                    maxLines = 3
                )
            }

            // Cooldown alert if rate limited
            if (isCoolingDown) {
                val remainingSec = (provider.cooldownUntilTimestamp - System.currentTimeMillis()) / 1000
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Error, contentDescription = null, tint = ForgeAmber, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Temporarily skipped (limit/error). Auto-failover uses the next provider; retry in ${remainingSec}s.",
                        fontSize = 11.sp,
                        color = ForgeAmber
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Test connection row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = CyberCyan)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Testing ping…", fontSize = 11.sp, color = CyberCyan)
                    } else if (testSuccess == true) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldSuccess, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Connection Verified", fontSize = 11.sp, color = EmeraldSuccess)
                    } else if (testSuccess == false) {
                        Icon(Icons.Default.Error, contentDescription = null, tint = RoseError, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Connection Failed", fontSize = 11.sp, color = RoseError)
                    }
                }

                TextButton(
                    onClick = onFetchModels,
                    enabled = !isFetching,
                    modifier = Modifier.height(30.dp)
                ) {
                    Text(if (isFetching) "Fetching…" else "Fetch models", fontSize = 11.sp, color = CyberCyan)
                }

                TextButton(
                    onClick = onTestConnection,
                    enabled = !isTesting,
                    modifier = Modifier.height(30.dp)
                ) {
                    Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(14.dp), tint = CyberCyan)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Test Connection", fontSize = 11.sp, color = CyberCyan)
                }
            }
            if (testSuccess == false && !testError.isNullOrBlank()) {
                Text(
                    text = testError.take(300),
                    fontSize = 10.sp,
                    color = RoseError,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}
