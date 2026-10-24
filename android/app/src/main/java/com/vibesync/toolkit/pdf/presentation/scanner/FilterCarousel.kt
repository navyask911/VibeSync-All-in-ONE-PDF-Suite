package com.vibesync.toolkit.pdf.presentation.scanner

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vibesync.toolkit.pdf.R
import com.vibesync.toolkit.pdf.data.pdf.filters.FilterId

/**
 * Filter choices for scanner previews. Callers supply pre-rendered 64dp thumbnails so filtering
 * itself remains outside composition and on the background dispatcher.
 */
@Composable
fun FilterCarousel(
    thumbnails: Map<FilterId, ImageBitmap>,
    selectedFilter: FilterId,
    onFilterSelected: (FilterId) -> Unit,
    processingFilter: FilterId? = null,
    progress: Float = 0f,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().testTag("filter_carousel"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.scan_filter_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterId.values().forEach { filterId ->
                val selected = selectedFilter == filterId
                val outline = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                Column(
                    modifier = Modifier
                        .width(84.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.RadioButton) { onFilterSelected(filterId) }
                        .semantics { this.selected = selected }
                        .testTag("filter_carousel_item_" + filterId.name)
                        .padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .border(if (selected) 2.dp else 1.dp, outline, RoundedCornerShape(9.dp))
                            .clip(RoundedCornerShape(9.dp))
                            .testTag("filter_carousel_thumbnail_" + filterId.name),
                        contentAlignment = Alignment.Center
                    ) {
                        val thumbnail = thumbnails[filterId]
                        if (thumbnail != null) {
                            Image(
                                bitmap = thumbnail,
                                contentDescription = stringResource(filterId.labelRes),
                                modifier = Modifier.size(64.dp)
                            )
                        } else {
                            Surface(
                                modifier = Modifier.size(64.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {}
                        }
                        if (processingFilter == filterId) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.58f))
                                    .testTag("filter_carousel_progress_" + filterId.name),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier.size(28.dp).testTag("filter_carousel_progress_indicator"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        progress = progress.coerceIn(0f, 1f),
                                        modifier = Modifier.fillMaxSize(),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                                        strokeWidth = 3.dp
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = stringResource(filterId.labelRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        modifier = Modifier.height(30.dp)
                    )
                }
            }
        }
    }
}
