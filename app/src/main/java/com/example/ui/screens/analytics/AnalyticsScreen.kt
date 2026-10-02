package com.example.ui.screens.analytics

import android.content.Context
import com.example.ui.theme.AppColors
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.data.local.dao.UsageDao
import com.example.data.local.entity.UsageRecordEntity
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.ForgeAmber
import com.example.ui.theme.ReasoningPurple
import com.example.ui.theme.RoseError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import com.example.data.settings.SettingsStore

@Composable
fun AnalyticsScreen(
    usageDao: UsageDao,
    settingsStore: SettingsStore,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val allRecords by usageDao.getAllUsageRecords().collectAsState(initial = emptyList())

    var selectedPeriod by remember { mutableStateOf("All Time") }
    var monthlyBudget by remember { mutableStateOf(settingsStore.monthlyBudgetUsd.coerceAtLeast(1.0)) }
    var hardStop by remember { mutableStateOf(settingsStore.budgetHardStop) }
    LaunchedEffect(monthlyBudget) { settingsStore.monthlyBudgetUsd = monthlyBudget }
    LaunchedEffect(hardStop) { settingsStore.budgetHardStop = hardStop }
    val monthCost = remember(allRecords) {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        allRecords.filter { it.timestamp >= start }.sumOf { it.estimatedCostUsd }
    }
    var showPricingDialog by remember { mutableStateOf(false) }

    val filteredRecords = remember(allRecords, selectedPeriod) {
        val now = System.currentTimeMillis()
        val cutoff = when (selectedPeriod) {
            "Today" -> now - (24 * 60 * 60 * 1000L)
            "This Week" -> now - (7 * 24 * 60 * 60 * 1000L)
            "This Month" -> now - (30 * 24 * 60 * 60 * 1000L)
            else -> 0L
        }
        allRecords.filter { it.timestamp >= cutoff }
    }

    val totalRequests = filteredRecords.size
    val totalInputTokens = filteredRecords.sumOf { it.inputTokens }
    val totalOutputTokens = filteredRecords.sumOf { it.outputTokens }
    val totalCachedTokens = filteredRecords.sumOf { it.cachedTokens }
    val totalCost = filteredRecords.sumOf { it.estimatedCostUsd }

    fun exportCsv() {
        scope.launch(Dispatchers.IO) {
            val exportDir = File(context.cacheDir, "exports")
            if (!exportDir.exists()) exportDir.mkdirs()
            val csvFile = File(exportDir, "codeforge_usage_${System.currentTimeMillis()}.csv")

            csvFile.bufferedWriter().use { writer ->
                writer.write("Timestamp,Project,Provider,Model,InputTokens,OutputTokens,CachedTokens,EstimatedCostUSD,FailoverOccurred,FailoverReason\n")
                for (r in filteredRecords) {
                    val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(r.timestamp))
                    writer.write("\"$date\",\"${r.projectId}\",\"${r.providerName}\",\"${r.model}\",${r.inputTokens},${r.outputTokens},${r.cachedTokens},${r.estimatedCostUsd},${r.failoverOccurred},\"${r.failoverReason ?: ""}\"\n")
                }
            }

            withContext(Dispatchers.Main) {
                try {
                    val uri: Uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        csvFile
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Export Usage CSV"))
                } catch (e: Exception) {
                    Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.bg)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Top Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Analytics,
                            contentDescription = "Analytics",
                            tint = CyberCyan,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "API Usage & Cost",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppColors.textPrimary
                        )
                    }
                    Text(
                        text = "Real-time token counts, estimated spend, and failover tracking.",
                        fontSize = 11.sp,
                        color = AppColors.textSecondary
                    )
                }

                IconButton(onClick = { exportCsv() }) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Export CSV",
                        tint = CyberCyan
                    )
                }
            }
        }

        // Time Filters
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val periods = listOf("Today", "This Week", "This Month", "All Time")
                for (p in periods) {
                    FilterChip(
                        selected = selectedPeriod == p,
                        onClick = { selectedPeriod = p },
                        label = { Text(p, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CyberCyan,
                            selectedLabelColor = AppColors.onAccent
                        )
                    )
                }
            }
        }

        // Metrics Grid
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricBox(
                        title = "Estimated Cost",
                        value = "$${String.format(Locale.US, "%.4f", totalCost)}",
                        subtitle = "Based on model pricing",
                        accentColor = ForgeAmber,
                        modifier = Modifier.weight(1f)
                    )
                    MetricBox(
                        title = "Total Requests",
                        value = totalRequests.toString(),
                        subtitle = "Agent API calls",
                        accentColor = CyberCyan,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricBox(
                        title = "Input Tokens",
                        value = formatTokenCount(totalInputTokens),
                        subtitle = "Sent to models",
                        accentColor = AppColors.accent,
                        modifier = Modifier.weight(1f)
                    )
                    MetricBox(
                        title = "Output Tokens",
                        value = formatTokenCount(totalOutputTokens),
                        subtitle = "Generated by models",
                        accentColor = ReasoningPurple,
                        modifier = Modifier.weight(1f)
                    )
                    MetricBox(
                        title = "Cached Tokens",
                        value = formatTokenCount(totalCachedTokens),
                        subtitle = "Prompt cache savings",
                        accentColor = EmeraldSuccess,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Budget Progress
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = AppColors.surface),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Monthly Budget Guard", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary)
                        Text("$${String.format(Locale.US, "%.2f", monthCost)} / $${String.format(Locale.US, "%.0f", monthlyBudget)}", fontSize = 12.sp, color = ForgeAmber)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    val progress = (monthCost / monthlyBudget).toFloat().coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = if (progress > 0.8f) RoseError else CyberCyan,
                        trackColor = AppColors.surfaceAlt
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { monthlyBudget = (monthlyBudget - 5.0).coerceAtLeast(1.0) }) { Text("−$5", fontSize = 12.sp) }
                            TextButton(onClick = { monthlyBudget += 5.0 }) { Text("+$5", fontSize = 12.sp) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Hard stop", fontSize = 11.sp, color = AppColors.textSecondary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Switch(checked = hardStop, onCheckedChange = { hardStop = it })
                        }
                    }
                    Text(
                        "Costs are estimates from the pricing table. This month's spend counts toward the budget.",
                        fontSize = 10.sp,
                        color = AppColors.textMuted
                    )
                    if (progress >= 0.8f) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = RoseError, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (hardStop && progress >= 1f) "Budget reached. The agent is stopped until you raise the budget."
                                else "80% of monthly budget reached.",
                                fontSize = 11.sp,
                                color = RoseError
                            )
                        }
                    }
                }
            }
        }

        // Provider Breakdown
        item {
            val providerBreakdown = remember(filteredRecords) {
                filteredRecords.groupBy { it.providerName }
                    .mapValues { entry -> entry.value.sumOf { it.inputTokens + it.outputTokens } }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = AppColors.surface),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Token Usage by Provider",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val maxVal = providerBreakdown.values.maxOrNull() ?: 1
                    for ((prov, tokens) in providerBreakdown) {
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(prov, fontSize = 12.sp, color = AppColors.textPrimary)
                                Text("${formatTokenCount(tokens)} tokens", fontSize = 11.sp, color = CyberCyan)
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            LinearProgressIndicator(
                                progress = { (tokens.toFloat() / maxVal.toFloat()).coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp),
                                color = CyberCyan,
                                trackColor = AppColors.surfaceAlt
                            )
                        }
                    }
                }
            }
        }

        // Failover Events Section
        item {
            val failovers = remember(filteredRecords) {
                filteredRecords.filter { it.failoverOccurred }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = AppColors.surface),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Failover Events Log (${failovers.size})",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.textPrimary
                        )
                    }

                    if (failovers.isEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "No failovers occurred. All primary provider requests succeeded.",
                            fontSize = 11.sp,
                            color = AppColors.textMuted
                        )
                    } else {
                        Spacer(modifier = Modifier.height(8.dp))
                        for (f in failovers.take(5)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(AppColors.surfaceAlt, RoundedCornerShape(6.dp))
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Bolt, contentDescription = null, tint = ForgeAmber, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = f.failoverReason ?: "Automatic failover",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = AppColors.warn
                                    )
                                    val dateStr = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(f.timestamp))
                                    Text(
                                        text = "$dateStr • Switched to ${f.providerName} (${f.model})",
                                        fontSize = 10.sp,
                                        color = AppColors.textSecondary
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            }
        }

        // Plain-Language Educational Cards
        item {
            Text(
                text = "UNDERSTANDING TOKENS & BILLING",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.textMuted,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        item {
            ExplanationAccordionCard(
                title = "What are tokens?",
                content = "Tokens are the basic building blocks that AI models read and write. Roughly 1,000 tokens equal about 750 English words or ~50 lines of code. Both your prompt/code and the AI's response are counted in tokens."
            )
        }

        item {
            ExplanationAccordionCard(
                title = "Input vs. Output Tokens",
                content = "Input tokens are the files, chat history, and system instructions you send to the AI. Output tokens are the code and text generated back. Output tokens typically cost 3x-4x more per token than input tokens."
            )
        }

        item {
            ExplanationAccordionCard(
                title = "Prompt Caching & Cost Savings",
                content = "When you chat repeatedly about the same project, modern models (like Claude 3.5 and Gemini) can cache your repository files in memory. Cached tokens cost up to 90% less and process much faster!"
            )
        }

        item {
            ExplanationAccordionCard(
                title = "How Automatic Failover Works",
                content = "If an API returns a rate limit (HTTP 429), server overload (503/529), or runs out of credits, CodeForge immediately pauses, respects the Retry-After cooldown, and continues the exact same conversation on your next priority provider without losing any state."
            )
        }
    }
}

@Composable
fun MetricBox(
    title: String,
    value: String,
    subtitle: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.border),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(title, fontSize = 11.sp, color = AppColors.textSecondary)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                value,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = accentColor
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(subtitle, fontSize = 9.sp, color = AppColors.textMuted)
        }
    }
}

@Composable
fun ExplanationAccordionCard(
    title: String,
    content: String
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.border),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.HelpOutline,
                        contentDescription = null,
                        tint = CyberCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.textPrimary
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = AppColors.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        text = content,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = AppColors.textSecondary
                    )
                }
            }
        }
    }
}

fun formatTokenCount(count: Int): String {
    return when {
        count >= 1_000_000 -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format(Locale.US, "%.1fK", count / 1_000.0)
        else -> count.toString()
    }
}
