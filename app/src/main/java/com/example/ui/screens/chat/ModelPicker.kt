package com.example.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.local.entity.ProviderConfigEntity
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

private sealed class PickerRow {
    data class Header(val title: String, val note: String?) : PickerRow()
    data class Model(val providerId: String, val model: String, val selected: Boolean) : PickerRow()
}

@Composable
fun ModelPickerDialog(
    providers: List<ProviderConfigEntity>,
    activeProviderId: String?,
    onSelect: (providerId: String, model: String) -> Unit,
    onOpenProviders: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val usable = providers.filter { it.isEnabled && (it.encryptedApiKey.isNotEmpty() || it.id == "ollama") }
    val rows = ArrayList<PickerRow>()
    for (p in usable) {
        rows.add(PickerRow.Header(p.name, null))
        for (m in providerModels(p)) {
            rows.add(PickerRow.Model(p.id, m, p.id == activeProviderId && m == p.selectedModel))
        }
    }

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
                    text = "Other providers stay as backup if this one hits a limit.",
                    fontSize = 12.sp,
                    color = AppColors.textSecondary,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 8.dp)
                )
                if (rows.isEmpty()) {
                    Text(
                        text = "No provider with an API key yet. Open the Providers tab and add one.",
                        fontSize = 13.sp,
                        color = AppColors.textSecondary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                    if (onOpenProviders != null) {
                        TextButton(
                            onClick = {
                                onDismiss()
                                onOpenProviders()
                            },
                            modifier = Modifier.padding(horizontal = 12.dp)
                        ) { Text("Open Providers", color = AppColors.accent) }
                    }
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        items(rows) { row ->
                            when (row) {
                                is PickerRow.Header -> Text(
                                    text = row.title.uppercase(),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AppColors.textMuted,
                                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp)
                                )
                                is PickerRow.Model -> Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelect(row.providerId, row.model) }
                                        .padding(horizontal = 20.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
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
                            }
                        }
                    }
                }
            }
        }
    }
}
