package com.vibesync.toolkit.pdf.presentation.common

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vibesync.toolkit.pdf.R
import com.vibesync.toolkit.pdf.core.util.UriUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads provider-backed display names asynchronously. Calling ContentResolver.query
 * while composing a screen can block the UI on slow/OEM document providers.
 */
@Composable
fun rememberUriDisplayName(uri: Uri?): String {
    val context = LocalContext.current
    val fallback = stringResource(R.string.selected_file)
    var displayName by remember(uri) { mutableStateOf(fallback) }

    LaunchedEffect(uri, context, fallback) {
        if (uri == null) {
            displayName = fallback
            return@LaunchedEffect
        }
        displayName = withContext(Dispatchers.IO) {
            try {
                UriUtils.getFileName(context, uri).ifBlank { fallback }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                fallback
            }
        }
    }
    return displayName
}
