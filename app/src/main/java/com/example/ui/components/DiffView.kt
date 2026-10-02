package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.DiffLineType
import com.example.domain.model.FileDiff
import com.example.ui.theme.DiffAddBg
import com.example.ui.theme.DiffAddText
import com.example.ui.theme.DiffRemoveBg
import com.example.ui.theme.DiffRemoveText
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.RoseError

@Composable
fun FileDiffCard(
    diff: FileDiff,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(true) }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1F2937)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            // File header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E293B))
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.InsertDriveFile,
                        contentDescription = "File",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = diff.filePath,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFF1F5F9)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    if (diff.linesAdded > 0) {
                        Text(
                            text = "+${diff.linesAdded}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = DiffAddText,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    if (diff.linesRemoved > 0) {
                        Text(
                            text = "-${diff.linesRemoved}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = DiffRemoveText
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    // Lines container
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0B0F17))
                            .horizontalScroll(rememberScrollState())
                    ) {
                        Column {
                            for (line in diff.diffLines) {
                                val bgColor = when (line.type) {
                                    DiffLineType.ADD -> DiffAddBg
                                    DiffLineType.REMOVE -> DiffRemoveBg
                                    DiffLineType.UNCHANGED -> Color.Transparent
                                }
                                val textColor = when (line.type) {
                                    DiffLineType.ADD -> DiffAddText
                                    DiffLineType.REMOVE -> DiffRemoveText
                                    DiffLineType.UNCHANGED -> Color(0xFF94A3B8)
                                }
                                val prefix = when (line.type) {
                                    DiffLineType.ADD -> "+ "
                                    DiffLineType.REMOVE -> "- "
                                    DiffLineType.UNCHANGED -> "  "
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(bgColor)
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    val lineNum = (line.newLineNumber ?: line.oldLineNumber)?.toString() ?: ""
                                    Text(
                                        text = lineNum.padStart(4, ' '),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = Color(0xFF475569),
                                        modifier = Modifier.width(36.dp)
                                    )
                                    Text(
                                        text = prefix + line.text,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.5.sp,
                                        color = textColor
                                    )
                                }
                            }
                        }
                    }

                    // Per-file action bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF131A26))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = onReject,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = RoseError),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Reject", modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Reject", fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onAccept,
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldSuccess),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = "Accept", modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Accept", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
