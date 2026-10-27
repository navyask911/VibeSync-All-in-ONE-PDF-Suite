package com.vibesync.toolkit.pdf.presentation.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vibesync.toolkit.pdf.R
import kotlin.math.roundToInt

data class PdfWatermarkOptions(
    val text: String,
    val opacity: Float,
    val angleDegrees: Float
)

private data class PdfDocumentActionItem(
    val label: String,
    val tag: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val destructive: Boolean = false,
    val expandsWatermarkSettings: Boolean = false,
    val onClick: () -> Unit
)

/** Shared Material 3 action surface for dashboard PDFs and saved scanner history entries. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PdfDocumentActionsSheet(
    documentName: String,
    onDismissRequest: () -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onSharePdf: () -> Unit,
    onShareImages: () -> Unit,
    onSplit: () -> Unit,
    onCompress: () -> Unit,
    onWatermark: (PdfWatermarkOptions) -> Unit,
    onEncrypt: () -> Unit,
    onDelete: () -> Unit,
    onSaveToDownloads: (() -> Unit)? = null
) {
    val defaultWatermarkText = stringResource(R.string.pdf_watermark_text)
    var showWatermarkSettings by remember(documentName) { mutableStateOf(false) }
    var watermarkText by remember(documentName) { mutableStateOf(defaultWatermarkText) }
    var watermarkOpacity by remember(documentName) { mutableStateOf(150f / 255f) }
    var watermarkAngle by remember(documentName) { mutableStateOf(0f) }

    val actions = listOf(
        PdfDocumentActionItem(stringResource(R.string.action_open_view_pdf), "open", Icons.Default.PictureAsPdf, onClick = onOpen),
        PdfDocumentActionItem(stringResource(R.string.edit_sign_action), "edit", Icons.Default.Draw, onClick = onEdit),
        PdfDocumentActionItem(stringResource(R.string.scan_rename), "rename", Icons.Default.DriveFileRenameOutline, onClick = onRename),
        PdfDocumentActionItem(stringResource(R.string.share_pdf), "share_pdf", Icons.Default.Share, onClick = onSharePdf),
        PdfDocumentActionItem(stringResource(R.string.action_share_jpg_images), "share_images", Icons.Default.Image, onClick = onShareImages)
    ) + (onSaveToDownloads?.let {
        listOf(PdfDocumentActionItem(stringResource(R.string.scan_save_downloads), "save_downloads", Icons.Default.PictureAsPdf, onClick = it))
    } ?: emptyList()) + listOf(
        PdfDocumentActionItem(stringResource(R.string.tool_split_document), "split", Icons.Default.CallSplit, onClick = onSplit),
        PdfDocumentActionItem(stringResource(R.string.action_compress_pdf), "compress", Icons.Default.Tune, onClick = onCompress),
        PdfDocumentActionItem(
            stringResource(R.string.action_watermark_pdf),
            "watermark",
            Icons.Default.Brush,
            expandsWatermarkSettings = true,
            onClick = {}
        ),
        PdfDocumentActionItem(stringResource(R.string.title_encrypt), "encrypt", Icons.Default.Lock, onClick = onEncrypt),
        PdfDocumentActionItem(stringResource(R.string.scan_delete), "delete", Icons.Default.DeleteOutline, destructive = true, onClick = onDelete)
    )

    ModalBottomSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(stringResource(R.string.document_actions_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(documentName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            actions.forEach { action ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            if (action.expandsWatermarkSettings) {
                                showWatermarkSettings = !showWatermarkSettings
                            } else {
                                onDismissRequest()
                                action.onClick()
                            }
                        }
                        .testTag("document_action_${action.tag}")
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(action.icon, contentDescription = null, tint = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    Text(
                        action.label,
                        modifier = Modifier.weight(1f).padding(start = 14.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                }
                if (action.expandsWatermarkSettings && showWatermarkSettings) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 12.dp)
                            .testTag("watermark_controls_panel"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = watermarkText,
                            onValueChange = { watermarkText = it },
                            label = { Text(stringResource(R.string.watermark_text_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("watermark_text_field")
                        )
                        Text(
                            "${stringResource(R.string.watermark_opacity_label)} ${(watermarkOpacity * 100f).roundToInt()}%",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Slider(
                            value = watermarkOpacity,
                            onValueChange = { watermarkOpacity = it },
                            valueRange = 0f..1f,
                            modifier = Modifier.fillMaxWidth().testTag("watermark_opacity_slider")
                        )
                        Text(
                            "${stringResource(R.string.watermark_angle_label)} ${watermarkAngle.roundToInt()}°",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Slider(
                            value = watermarkAngle,
                            onValueChange = { watermarkAngle = it },
                            valueRange = -180f..180f,
                            steps = 23,
                            modifier = Modifier.fillMaxWidth().testTag("watermark_angle_slider")
                        )
                        Button(
                            onClick = {
                                val options = PdfWatermarkOptions(
                                    text = watermarkText.trim(),
                                    opacity = watermarkOpacity.coerceIn(0f, 1f),
                                    angleDegrees = watermarkAngle.coerceIn(-180f, 180f)
                                )
                                onDismissRequest()
                                onWatermark(options)
                            },
                            enabled = watermarkText.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().testTag("watermark_apply_button")
                        ) {
                            Text(stringResource(R.string.watermark_apply_action))
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
