package com.example.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entity.ModelCacheEntity
import com.example.data.local.entity.ProviderConfigEntity
import com.example.ui.components.ProviderIcon
import com.example.ui.theme.AppColors
import org.json.JSONArray

fun providerModels(p: ProviderConfigEntity): List<String> {
    val list = try {
        val arr = JSONArray(p.modelsJson)
        (0 until arr.length()).map { arr.getString(it) }
    } catch (_: Exception) {
        emptyList()
    }
    return if (p.selectedModel.isNotBlank() && p.selectedModel !in list) listOf(p.selectedModel) + list else list
}

private fun isLocalUrl(url: String): Boolean {
    val u = url.lowercase()
    return u.contains("localhost") || u.contains("127.0.0.1") || u.contains("10.0.2.2") ||
        u.contains("192.168.") || u.contains("://10.")
}

/** A provider is usable when it is switched on and has an API key (or points to a local server). */
fun isProviderReady(p: ProviderConfigEntity): Boolean =
    p.isEnabled && (p.encryptedApiKey.isNotEmpty() || isLocalUrl(p.baseUrl))

/**
 * Strictly resolves the single active provider and ONLY its models — never mixing models
 * from other providers into the Chat model dropdown.
 */
fun modelsForActiveProvider(
    providers: List<ProviderConfigEntity>,
    activeProviderId: String?
): Pair<ProviderConfigEntity?, List<String>> {
    val ready = providers.filter { isProviderReady(it) }
    val selected = ready.firstOrNull { it.id == activeProviderId }
        ?: providers.firstOrNull { it.id == activeProviderId && it.isEnabled }
        ?: ready.firstOrNull()
    if (selected == null) return Pair(null, emptyList())
    return Pair(selected, providerModels(selected))
}

enum class ModelFilterChip(val label: String) {
    ALL("All"),
    FAVORITES("Favorites"),
    TOOLS("Tools"),
    VISION("Vision"),
    LONG_CONTEXT("Long context")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerDialog(
    providers: List<ProviderConfigEntity>,
    activeProviderId: String?,
    cachedModels: List<ModelCacheEntity> = emptyList(),
    onSelect: (providerId: String, model: String) -> Unit,
    onSetupProvider: (providerId: String) -> Unit,
    onAddProvider: () -> Unit,
    onToggleFavorite: (providerId: String, modelId: String, favorite: Boolean) -> Unit = { _, _, _ -> },
    onAddCustomModelId: (providerId: String, modelId: String) -> Unit = { _, _ -> },
    onRefreshProviderModels: (providerId: String) -> Unit = {},
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf(ModelFilterChip.ALL) }
    var showAddCustomField by remember { mutableStateOf(false) }
    var customModelInput by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }

    val readyProviders = remember(providers) { providers.filter { isProviderReady(it) } }
    var browsingProviderId by remember(activeProviderId, readyProviders.size) {
        mutableStateOf(
            readyProviders.firstOrNull { it.id == activeProviderId }?.id
                ?: activeProviderId
                ?: readyProviders.firstOrNull()?.id
        )
    }

    val (selectedProvider, activeModelsRaw) = remember(providers, browsingProviderId) {
        modelsForActiveProvider(providers, browsingProviderId)
    }

    // Auto-fetch models if the active provider currently has no models loaded
    LaunchedEffect(selectedProvider?.id) {
        val p = selectedProvider ?: return@LaunchedEffect
        if (activeModelsRaw.isEmpty()) {
            refreshing = true
            onRefreshProviderModels(p.id)
        }
    }
    LaunchedEffect(activeModelsRaw.size, selectedProvider?.lastTestedAt) {
        refreshing = false
    }

    val query = searchQuery.trim()
    val cacheMap = remember(cachedModels, selectedProvider?.id) {
        val pid = selectedProvider?.id
        cachedModels.filter { it.providerId == pid }.associateBy { it.modelId }
    }

    val filteredModels = remember(activeModelsRaw, query, activeFilter, cacheMap) {
        activeModelsRaw.filter { modelId ->
            val meta = cacheMap[modelId]
            val matchesQuery = query.isEmpty() ||
                modelId.contains(query, ignoreCase = true) ||
                (meta?.displayName?.contains(query, ignoreCase = true) == true)
            val matchesChip = when (activeFilter) {
                ModelFilterChip.ALL -> true
                ModelFilterChip.FAVORITES -> meta?.isFavorite == true
                ModelFilterChip.TOOLS -> meta?.supportsTools != false
                ModelFilterChip.VISION -> meta?.supportsVision == true
                ModelFilterChip.LONG_CONTEXT -> (meta?.contextWindow ?: 0) >= 100_000
            }
            matchesQuery && matchesChip
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AppColors.surface,
        contentColor = AppColors.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 14.dp)
        ) {
            // Header: strictly shows the currently selected/active provider
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selectedProvider != null) {
                            ProviderIcon(selectedProvider.id, selectedProvider.name, size = 22.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = selectedProvider?.name ?: "Select Model",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (selectedProvider != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            val isHealthy = selectedProvider.lastTestStatus == "OK" || selectedProvider.lastTestStatus == "WORKING"
                            val dotColor = when {
                                isHealthy -> AppColors.ok
                                selectedProvider.lastTestStatus == "FAILED" || selectedProvider.lastTestStatus == "INVALID" -> AppColors.error
                                else -> AppColors.textMuted
                            }
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (selectedProvider == null) {
                            "No active provider configured yet. Add an API key in Settings."
                        } else {
                            "Showing ${activeModelsRaw.size} model(s) from ${selectedProvider.name}"
                        },
                        fontSize = 12.sp,
                        color = AppColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (selectedProvider != null) {
                    IconButton(
                        onClick = {
                            refreshing = true
                            onRefreshProviderModels(selectedProvider.id)
                        },
                        modifier = Modifier.testTag("refresh_provider_models_button")
                    ) {
                        if (refreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = AppColors.accent
                            )
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Refresh models from ${selectedProvider.name}",
                                tint = AppColors.accent
                            )
                        }
                    }
                }
            }

            // Optional compact provider switcher strip if >1 configured providers exist.
            // Selecting a provider strictly filters the model list below to ONLY that provider.
            if (readyProviders.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (p in readyProviders) {
                        val isSelectedProvider = p.id == selectedProvider?.id
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = if (isSelectedProvider) AppColors.accentSoft else AppColors.surfaceAlt,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .border(
                                    1.dp,
                                    if (isSelectedProvider) AppColors.accent else AppColors.border,
                                    RoundedCornerShape(999.dp)
                                )
                                .clickable { browsingProviderId = p.id }
                                .testTag("provider_switch_${p.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ProviderIcon(p.id, p.name, size = 14.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = p.name,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelectedProvider) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (isSelectedProvider) AppColors.accent else AppColors.textSecondary,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            if (selectedProvider != null) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .testTag("model_search_input"),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    placeholder = { Text("Search ${selectedProvider.name} models…", fontSize = 13.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AppColors.textPrimary,
                        unfocusedTextColor = AppColors.textPrimary,
                        focusedBorderColor = AppColors.accent,
                        unfocusedBorderColor = AppColors.border,
                        cursorColor = AppColors.accent
                    )
                )

                // Filter chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (chip in ModelFilterChip.entries) {
                        val selected = activeFilter == chip
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = if (selected) AppColors.accentSoft else AppColors.surfaceAlt,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .border(
                                    1.dp,
                                    if (selected) AppColors.accent else AppColors.border,
                                    RoundedCornerShape(999.dp)
                                )
                                .clickable { activeFilter = chip }
                        ) {
                            Text(
                                text = chip.label,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) AppColors.accent else AppColors.textSecondary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                // Custom model ID entry for the active provider
                if (showAddCustomField) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = customModelInput,
                            onValueChange = { customModelInput = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            placeholder = { Text("e.g. gpt-4o or deepseek-reasoner", fontSize = 13.sp) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                val trimmed = customModelInput.trim()
                                if (trimmed.isNotEmpty()) {
                                    onAddCustomModelId(selectedProvider.id, trimmed)
                                    onSelect(selectedProvider.id, trimmed)
                                    customModelInput = ""
                                    showAddCustomField = false
                                    onDismiss()
                                }
                            }
                        ) {
                            Text("Use", color = AppColors.accent, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 420.dp)
                        .padding(top = 4.dp)
                ) {
                    if (filteredModels.isEmpty()) {
                        item(key = "empty_models") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = if (refreshing) {
                                        "Loading models from ${selectedProvider.name}…"
                                    } else if (activeModelsRaw.isEmpty()) {
                                        "No models loaded yet for ${selectedProvider.name}."
                                    } else {
                                        "No ${selectedProvider.name} models match \"$query\"."
                                    },
                                    fontSize = 13.sp,
                                    color = AppColors.textSecondary
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Surface(
                                        shape = RoundedCornerShape(999.dp),
                                        color = AppColors.accentSoft,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .clickable {
                                                refreshing = true
                                                onRefreshProviderModels(selectedProvider.id)
                                            }
                                    ) {
                                        Text(
                                            text = "Fetch Models",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = AppColors.accent,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                        )
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(999.dp),
                                        color = AppColors.surfaceAlt,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .clickable { showAddCustomField = true }
                                    ) {
                                        Text(
                                            text = "+ Custom Model ID",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = AppColors.textPrimary,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        items(filteredModels, key = { "${selectedProvider.id}:$it" }) { modelId ->
                            val isSelected = selectedProvider.id == activeProviderId && modelId == selectedProvider.selectedModel
                            val meta = cacheMap[modelId]
                            ModelPickerItemRow(
                                provider = selectedProvider,
                                model = modelId,
                                selected = isSelected,
                                metadata = meta,
                                onSelect = {
                                    onSelect(selectedProvider.id, modelId)
                                    onDismiss()
                                },
                                onToggleFavorite = {
                                    onToggleFavorite(selectedProvider.id, modelId, meta?.isFavorite != true)
                                }
                            )
                        }
                    }

                    item(key = "footer_actions") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { showAddCustomField = !showAddCustomField }) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = AppColors.accent, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Custom model ID", fontSize = 12.sp, color = AppColors.accent)
                            }
                            TextButton(onClick = { onDismiss(); onAddProvider() }) {
                                Text("Manage providers", fontSize = 12.sp, color = AppColors.textSecondary)
                            }
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Configure an AI provider in Settings to see its models here.",
                        fontSize = 13.sp,
                        color = AppColors.textSecondary
                    )
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = AppColors.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { onDismiss(); onAddProvider() }
                    ) {
                        Text(
                            text = "Add or Configure Provider",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.onAccent,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelPickerItemRow(
    provider: ProviderConfigEntity,
    model: String,
    selected: Boolean,
    metadata: ModelCacheEntity?,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) AppColors.accentSoft else AppColors.surface)
            .border(
                1.dp,
                if (selected) AppColors.accent.copy(alpha = 0.45f) else AppColors.border,
                RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier.size(30.dp)
        ) {
            Icon(
                imageVector = if (metadata?.isFavorite == true) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = "Favorite model",
                tint = if (metadata?.isFavorite == true) AppColors.warn else AppColors.textMuted,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model,
                fontSize = 13.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) AppColors.accent else AppColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (metadata != null) {
                val badges = buildList {
                    if (metadata.contextWindow > 0) add("${metadata.contextWindow / 1000}k ctx")
                    if (metadata.supportsTools) add("Tools")
                    if (metadata.supportsVision) add("Vision")
                    if (metadata.inputPricePerMillion == 0.0 && metadata.outputPricePerMillion == 0.0 && model.endsWith(":free")) {
                        add("Free")
                    } else if (metadata.inputPricePerMillion > 0.0) {
                        add("$${"%.2f".format(metadata.inputPricePerMillion)}/1M")
                    }
                }
                if (badges.isNotEmpty()) {
                    Text(
                        text = badges.joinToString(" · "),
                        fontSize = 10.5.sp,
                        color = AppColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (selected) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected model",
                tint = AppColors.accent,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
