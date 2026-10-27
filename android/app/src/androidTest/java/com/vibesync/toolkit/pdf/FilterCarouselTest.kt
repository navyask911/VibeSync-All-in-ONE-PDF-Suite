package com.vibesync.toolkit.pdf

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.asImageBitmap
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncPdfTheme
import com.vibesync.toolkit.pdf.data.pdf.filters.FilterId
import com.vibesync.toolkit.pdf.presentation.scanner.FilterCarousel
import org.junit.Rule
import org.junit.Test

class FilterCarouselTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersSixFilterThumbnailsAndUpdatesSelectedStateInRtl() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).asImageBitmap()
        val thumbnails = FilterId.values().associateWith { bitmap }
        var selected by mutableStateOf(FilterId.MAGIC_COLOR)
        composeRule.setContent {
            VibeSyncPdfTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    FilterCarousel(
                        thumbnails = thumbnails,
                        selectedFilter = selected,
                        onFilterSelected = { selected = it }
                    )
                }
            }
        }

        composeRule.onAllNodesWithTag("filter_carousel_item_MAGIC_COLOR").assertCountEquals(1)
        composeRule.onAllNodesWithTag("filter_carousel_item_BW").assertCountEquals(1)
        composeRule.onAllNodesWithTag("filter_carousel_item_LIGHTEN").assertCountEquals(1)
        composeRule.onAllNodesWithTag("filter_carousel_item_NO_SHADOW").assertCountEquals(1)
        composeRule.onAllNodesWithTag("filter_carousel_item_SCREEN").assertCountEquals(1)
        composeRule.onAllNodesWithTag("filter_carousel_item_CLEAN_MARKS").assertCountEquals(1)
        composeRule.onNodeWithTag("filter_carousel_item_MAGIC_COLOR").assertIsSelected()
        composeRule.onNodeWithTag("filter_carousel_item_BW").performClick()
        composeRule.onNodeWithTag("filter_carousel_item_BW").assertIsSelected()
    }

    @Test
    fun displaysDeterminateProgressOverlayForCurrentlyProcessedFilter() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).asImageBitmap()
        val thumbnails = FilterId.values().associateWith { bitmap }
        composeRule.setContent {
            VibeSyncPdfTheme {
                FilterCarousel(
                    thumbnails = thumbnails,
                    selectedFilter = FilterId.BW,
                    onFilterSelected = {},
                    processingFilter = FilterId.BW,
                    progress = 0.42f
                )
            }
        }
        composeRule.onNodeWithTag("filter_carousel").assertIsDisplayed()
        // Filter items are clickable and merge descendant semantics; inspect overlay nodes unmerged.
        composeRule.onNodeWithTag("filter_carousel_progress_BW", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("filter_carousel_progress_indicator", useUnmergedTree = true).assertExists()
    }
}
