package com.vibesync.toolkit.pdf.presentation.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vibesync.toolkit.pdf.R
import com.vibesync.toolkit.pdf.data.pdf.ProfileSpec
import com.vibesync.toolkit.pdf.data.pdf.ResolutionProfile
import com.vibesync.toolkit.pdf.data.pdf.ResolutionProfilePrefs

/** Horizontally scrollable Material 3 control using the VibeSync Crimson theme and RTL layout. */
@Composable
fun ResolutionProfileSelector(
    selected: ResolutionProfile,
    onSelect: (ResolutionProfile) -> Unit,
    modifier: Modifier = Modifier
) {
    val profiles = ResolutionProfile.values()
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.resolution_profile_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        ) {
            profiles.forEachIndexed { index, profile ->
                SegmentedButton(
                    selected = selected == profile,
                    onClick = { onSelect(profile) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = profiles.size),
                    modifier = Modifier.width(172.dp).testTag("resolution_profile_" + profile.name),
                    label = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stringResource(profile.labelRes()),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(
                                text = stringResource(profile.descriptionRes()),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (selected == profile) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )
            }
        }
    }
}

private fun ResolutionProfile.labelRes(): Int = when (this) {
    ResolutionProfile.SMALLEST -> R.string.resolution_profile_smallest
    ResolutionProfile.STANDARD -> R.string.resolution_profile_standard
    ResolutionProfile.LARGEST -> R.string.resolution_profile_largest
    ResolutionProfile.HD_AUTO -> R.string.resolution_profile_hd_auto
}

private fun ResolutionProfile.descriptionRes(): Int = when (this) {
    ResolutionProfile.SMALLEST -> R.string.resolution_profile_smallest_description
    ResolutionProfile.STANDARD -> R.string.resolution_profile_standard_description
    ResolutionProfile.LARGEST -> R.string.resolution_profile_largest_description
    ResolutionProfile.HD_AUTO -> R.string.resolution_profile_hd_auto_description
}

/** Preference-backed host for the existing export, compression, and image-to-PDF surfaces. */
@Composable
fun ResolutionProfilePreferencePanel(
    modifier: Modifier = Modifier,
    testTag: String = "resolution_profile_preference_panel"
) {
    val context = LocalContext.current.applicationContext
    val prefs = remember(context) { ResolutionProfilePrefs(context) }
    var selected by remember(context) { mutableStateOf(prefs.read()) }

    ResolutionProfileSelector(
        selected = selected,
        onSelect = { profile ->
            selected = profile
            prefs.write(profile)
        },
        modifier = modifier.testTag(testTag)
    )
}

/** Shows the required warning when a real source raster falls back to STANDARD. */
@Composable
fun ResolutionProfileFallbackSnackbar(
    spec: ProfileSpec,
    snackbarHostState: SnackbarHostState
) {
    val message = stringResource(R.string.resolution_profile_memory_fallback)
    androidx.compose.runtime.LaunchedEffect(spec.fellBack, message) {
        if (spec.fellBack) snackbarHostState.showSnackbar(message)
    }
}
