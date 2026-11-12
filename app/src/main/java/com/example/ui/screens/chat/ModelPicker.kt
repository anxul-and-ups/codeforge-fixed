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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    return u.contains("localhost") || u.contains("127.0.0.1") || u.contains("10.0.2.2")
}

/** A provider is usable when it is switched on and has an API key (or points to a local server). */
fun isProviderReady(p: ProviderConfigEntity): Boolean =
    p.isEnabled && (p.encryptedApiKey.isNotEmpty() || isLocalUrl(p.baseUrl))

enum class ModelFilterChip(val label: String) {
    ALL("All"),
    FAVORITES("Favorites"),
    TOOLS("Tools"),
    VISION("Vision"),
    LONG_CONTEXT("Long context")
}

private sealed class PickerRow {
    data class Header(val provider: ProviderConfigEntity, val modelCount: Int) : PickerRow()
    data class Model(
        val provider: ProviderConfigEntity,
        val model: String,
        val selected: Boolean,
        val metadata: ModelCacheEntity?
    ) : PickerRow()
    object SetupTitle : PickerRow()
    data class Setup(val provider: ProviderConfigEntity) : PickerRow()
    object AddCustom : PickerRow()
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

    val readyProviders = providers.filter { isProviderReady(it) }
        .sortedByDescending { it.id == activeProviderId }
    val notReady = providers.filter { !isProviderReady(it) }
    val query = searchQuery.trim()
    val cacheMap = remember(cachedModels) {
        cachedModels.associateBy { "${it.providerId}:${it.modelId}" }
    }

    val rows = ArrayList<PickerRow>()
    for (p in readyProviders) {
        val rawModels = providerModels(p)
        val filtered = rawModels.filter { modelId ->
            val meta = cacheMap["${p.id}:$modelId"]
            val matchesQuery = query.isEmpty() || modelId.contains(query, ignoreCase = true) || p.name.contains(query, ignoreCase = true)
            val matchesChip = when (activeFilter) {
                ModelFilterChip.ALL -> true
                ModelFilterChip.FAVORITES -> meta?.isFavorite == true
                ModelFilterChip.TOOLS -> meta?.supportsTools != false
                ModelFilterChip.VISION -> meta?.supportsVision == true
                ModelFilterChip.LONG_CONTEXT -> (meta?.contextWindow ?: 0) >= 100_000
            }
            matchesQuery && matchesChip
        }
        rows.add(PickerRow.Header(p, rawModels.size))
        for (m in filtered) {
            rows.add(
                PickerRow.Model(
                    provider = p,
                    model = m,
                    selected = p.id == activeProviderId && m == p.selectedModel,
                    metadata = cacheMap["${p.id}:$m"]
                )
            )
        }
    }
    if (notReady.isNotEmpty()) {
        rows.add(PickerRow.SetupTitle)
        for (p in notReady) rows.add(PickerRow.Setup(p))
    }
    rows.add(PickerRow.AddCustom)

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
                .padding(bottom = 12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Select Model",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.textPrimary
                    )
                    Text(
                        text = if (readyProviders.isEmpty()) {
                            "No providers configured yet. Add an API key and tap Test Connection."
                        } else {
                            "Models fetched live from your configured providers."
                        },
                        fontSize = 12.sp,
                        color = AppColors.textSecondary
                    )
                }
                val primaryReady = readyProviders.firstOrNull()
                if (primaryReady != null) {
                    IconButton(onClick = { onRefreshProviderModels(primaryReady.id) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh models from server", tint = AppColors.accent)
                    }
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                label = { Text("Search server-fetched models") },
                placeholder = { Text("Filter by model ID or provider") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = AppColors.textPrimary,
                    unfocusedTextColor = AppColors.textPrimary,
                    focusedBorderColor = AppColors.accent,
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

            // Custom model ID entry for endpoints that don't expose /models
            val targetProvider = readyProviders.firstOrNull()
            if (targetProvider != null) {
                if (!showAddCustomField) {
                    TextButton(
                        onClick = { showAddCustomField = true },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = AppColors.accent)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Enter custom model ID for ${targetProvider.name}", fontSize = 12.sp, color = AppColors.accent)
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = customModelInput,
                            onValueChange = { customModelInput = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("Custom model ID") }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                val trimmed = customModelInput.trim()
                                if (trimmed.isNotEmpty()) {
                                    onAddCustomModelId(targetProvider.id, trimmed)
                                    onSelect(targetProvider.id, trimmed)
                                    customModelInput = ""
                                    showAddCustomField = false
                                }
                            }
                        ) {
                            Text("Save", color = AppColors.accent, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(rows) { row ->
                    when (row) {
                        is PickerRow.Header -> Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ProviderIcon(row.provider.id, null, size = 16.dp, providerName = row.provider.name)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = row.provider.name,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppColors.textMuted
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (row.provider.lastTestStatus == "OK") AppColors.ok else AppColors.textMuted
                                    )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (row.modelCount == 0) "Tap Refresh or Test Connection to load models" else "${row.modelCount} models",
                                fontSize = 11.sp,
                                color = AppColors.textMuted
                            )
                        }
                        is PickerRow.Model -> Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(row.provider.id, row.model) }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ProviderIcon(row.provider.id, row.model, size = 18.dp, providerName = row.provider.name)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = row.model,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (row.selected) AppColors.accent else AppColors.textPrimary,
                                    fontWeight = if (row.selected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val meta = row.metadata
                                if (meta != null && (meta.contextWindow != null || meta.supportsVision)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        meta.contextWindow?.let { ctx ->
                                            Text(
                                                text = "${ctx / 1000}k ctx",
                                                fontSize = 10.sp,
                                                color = AppColors.textMuted
                                            )
                                        }
                                        if (meta.supportsTools) {
                                            Text("tools", fontSize = 10.sp, color = AppColors.textMuted)
                                        }
                                        if (meta.supportsVision) {
                                            Text("vision", fontSize = 10.sp, color = AppColors.textMuted)
                                        }
                                    }
                                }
                            }
                            IconButton(
                                onClick = {
                                    val fav = !(row.metadata?.isFavorite ?: false)
                                    onToggleFavorite(row.provider.id, row.model, fav)
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = if (row.metadata?.isFavorite == true) Icons.Default.Star else Icons.Default.StarBorder,
                                    contentDescription = "Favorite",
                                    tint = if (row.metadata?.isFavorite == true) AppColors.warn else AppColors.textMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            if (row.selected) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = AppColors.accent,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        is PickerRow.SetupTitle -> Text(
                            text = "CONFIGURE PROVIDERS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.textMuted,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp)
                        )
                        is PickerRow.Setup -> Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSetupProvider(row.provider.id) }
                                .padding(horizontal = 20.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ProviderIcon(row.provider.id, null, size = 18.dp, providerName = row.provider.name)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = row.provider.name,
                                fontSize = 14.sp,
                                color = AppColors.textPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            Text("Add key & test", fontSize = 12.sp, color = AppColors.accent)
                        }
                        is PickerRow.AddCustom -> Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAddProvider() }
                                .padding(horizontal = 20.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = AppColors.accent, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Manage providers & API keys", fontSize = 14.sp, color = AppColors.accent, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
