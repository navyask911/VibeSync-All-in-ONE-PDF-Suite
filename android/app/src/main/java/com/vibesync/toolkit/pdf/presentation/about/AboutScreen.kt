package com.vibesync.toolkit.pdf.presentation.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vibesync.toolkit.pdf.R

private const val PDF_PLAY_STORE_URL =
    "https://play.google.com/store/apps/details?id=com.vibesync.toolkit.pdf"
private const val KIDS_PLAY_STORE_URL =
    "https://play.google.com/store/apps/details?id=com.navya.kids_drawing_studio"
private const val DEVELOPER_OPTIONS_PLAY_STORE_URL =
    "https://play.google.com/store/apps/details?id=com.utility.devoptions"
private const val ANTIVIRUS_PLAY_STORE_URL =
    "https://play.google.com/store/apps/details?id=com.vibesync.antivirus"
private const val VIBESYNC_WEBSITE_URL = "https://vibesyncro.com/"

private data class AboutAppItem(
    val name: String,
    val description: String,
    val icon: ImageVector,
    val url: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val apps = listOf(
        AboutAppItem(
            name = stringResource(R.string.app_pdf_name),
            description = stringResource(R.string.app_pdf_desc),
            icon = Icons.Default.PictureAsPdf,
            url = PDF_PLAY_STORE_URL
        ),
        AboutAppItem(
            name = stringResource(R.string.app_kids_name),
            description = stringResource(R.string.app_kids_desc),
            icon = Icons.Default.Brush,
            url = KIDS_PLAY_STORE_URL
        ),
        AboutAppItem(
            name = stringResource(R.string.app_dev_name),
            description = stringResource(R.string.app_dev_desc),
            icon = Icons.Default.Settings,
            url = DEVELOPER_OPTIONS_PLAY_STORE_URL
        ),
        AboutAppItem(
            name = stringResource(R.string.app_av_name),
            description = stringResource(R.string.app_av_desc),
            icon = Icons.Default.Security,
            url = ANTIVIRUS_PLAY_STORE_URL
        )
    )

    fun openExternalUrl(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_us_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = stringResource(R.string.about_us_mission),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.about_us_ecosystem),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            items(apps, key = { it.url }) { app ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openExternalUrl(app.url) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(
                            imageVector = app.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(34.dp)
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = app.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = app.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = { openExternalUrl(VIBESYNC_WEBSITE_URL) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.about_us_website))
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
