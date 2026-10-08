package com.example.ui.screens.chat

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

private fun modelAccessLabel(provider: ProviderConfigEntity, model: String): String {
    if (isLocalUrl(provider.baseUrl)) return "Free"
    return "Paid"
}

fun isProviderReady(p: ProviderConfigEntity): Boolean =
    p.isEnabled && (p.encryptedApiKey.isNotEmpty() || isLocalUrl(p.baseUrl))

@Composable
fun ModelPickerDialog(
    providers: List<ProviderConfigEntity>,
    activeProviderId: String?,
    onSelect: (providerId: String, model: String) -> Unit,
    onSetupProvider: (providerId: String) -> Unit,
    onAddProvider: () -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val activeProvider = providers.firstOrNull { it.id == activeProviderId }
    val models = activeProvider?.let { providerModels(it) } ?: emptyList()
    val filteredModels = models.filter { model ->
        query.isBlank() || model.contains(query.trim(), ignoreCase = true)
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
                    text = activeProvider?.name ?: "No provider selected",
                    fontSize = 12.sp,
                    color = AppColors.textSecondary,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 8.dp)
                )
                if (activeProvider != null) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        singleLine = true,
                        placeholder = { Text("Search models") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
                    )
                }
                LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
                    if (activeProvider == null) {
                        item {
                            Text(
                                text = "Select a provider first.",
                                fontSize = 13.sp,
                                color = AppColors.textSecondary,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                            )
                        }
                    } else if (!isProviderReady(activeProvider)) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSetupProvider(activeProvider.id) }
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ProviderIcon(activeProvider.id, null, size = 18.dp, providerName = activeProvider.name)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Set up ${activeProvider.name}",
                                    fontSize = 14.sp,
                                    color = AppColors.textPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                Text("Set up", fontSize = 12.sp, color = AppColors.accent)
                            }
                        }
                    } else {
                        item {
                            Row(
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ProviderIcon(activeProvider.id, null, size = 16.dp, providerName = activeProvider.name)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = activeProvider.name,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AppColors.textMuted
                                )
                            }
                        }
                        items(filteredModels) { model ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(activeProvider.id, model) }
                                    .padding(horizontal = 20.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ProviderIcon(activeProvider.id, model, size = 18.dp, providerName = activeProvider.name)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = model,
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (model == activeProvider.selectedModel) AppColors.accent else AppColors.textPrimary,
                                        fontWeight = if (model == activeProvider.selectedModel) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = modelAccessLabel(activeProvider, model),
                                        fontSize = 10.sp,
                                        color = AppColors.textMuted
                                    )
                                }
                                if (model == activeProvider.selectedModel) {
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
                    item {
                        Row(
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
