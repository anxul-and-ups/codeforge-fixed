package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AppColors

/**
 * Shared minimal dark header used across all Project Workspace tabs (Chat, Files, Terminal, Preview, GH Build),
 * strictly matching the clean reference header: Back arrow | Project title + subtitle | History button.
 */
@Composable
fun ProjectWorkspaceTopBar(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onHistoryClick: (() -> Unit)? = null,
    onSubtitleClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(AppColors.bg)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(44.dp)
                .testTag("workspace_back_button")
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back to Projects",
                tint = AppColors.textPrimary,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .then(
                    if (onSubtitleClick != null) Modifier.clickable { onSubtitleClick() } else Modifier
                )
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Text(
                text = title,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                fontSize = 12.5.sp,
                color = AppColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (onHistoryClick != null) {
            IconButton(
                onClick = onHistoryClick,
                modifier = Modifier
                    .size(44.dp)
                    .testTag("workspace_history_button")
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = "Conversation History",
                    tint = AppColors.textSecondary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
