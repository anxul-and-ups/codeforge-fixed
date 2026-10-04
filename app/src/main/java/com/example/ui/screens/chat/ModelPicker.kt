package com.example.ui.screens.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.local.entity.ProviderConfigEntity
import com.example.ui.components.ProviderIcon
import com.example.ui.theme.AppColors
import org.json.JSONArray

fun providerModels(p: ProviderConfigEntity): List<String> {
    val list = try {
        val arr = JSONArray(p.modelsJson)
        (0 until arr.length()).map { arr.getString(it) }
    } catch (e: Exception) {
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

private sealed class PickerRow {
    data class Header(val provider: ProviderConfigEntity) : PickerRow()
    data class Model(val provider: ProviderConfigEntity, val model: String, val selected: Boolean) : PickerRow()
    object SetupTitle : PickerRow()
    data class Setup(val provider: ProviderConfigEntity) : PickerRow()
    object AddCustom : PickerRow()
}

@Composable
fun ModelPickerDialog(
    providers: List<ProviderConfigEntity>,
    activeProviderId: String?,
    onSelect: (providerId: String, model: String) -> Unit,
    onSetupProvider: (providerId: String) -> Unit,
    onAddProvider: () -> Unit,
    onDismiss: () -> Unit
) {
    val ready = providers.filter { isProviderReady(it) }
    val notReady = providers.filter { !isProviderReady(it) }

    val rows = ArrayList<PickerRow>()
    for (p in ready) {
        rows.add(PickerRow.Header(p))
        for (m in providerModels(p)) {
            rows.add(PickerRow.Model(p, m, p.id == activeProviderId && m == p.selectedModel))
        }
    }
    if (notReady.isNotEmpty()) {
        rows.add(PickerRow.SetupTitle)
        for (p in notReady) rows.add(PickerRow.Setup(p))
    }
    rows.add(PickerRow.AddCustom)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = AppColors.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                Text(
                    text = "Choose model",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.textPrimary,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Text(
                    text = if (ready.isEmpty()) "Add an API key to start chatting." else "Other providers stay as backup if this one hits a limit.",
                    fontSize = 12.sp,
                    color = AppColors.textSecondary,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 8.dp)
                )
                LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
                    items(rows) { row ->
                        when (row) {
                            is PickerRow.Header -> Row(
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
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
                            }
                            is PickerRow.Model -> Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(row.provider.id, row.model) }
                                    .padding(horizontal = 20.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ProviderIcon(row.provider.id, row.model, size = 18.dp, providerName = row.provider.name)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = row.model,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (row.selected) AppColors.accent else AppColors.textPrimary,
                                    fontWeight = if (row.selected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (row.selected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = AppColors.accent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            is PickerRow.SetupTitle -> Text(
                                text = "ADD AN API KEY",
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
                                Text("Set up", fontSize = 12.sp, color = AppColors.accent)
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
                                Text("Add provider", fontSize = 14.sp, color = AppColors.accent, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}
