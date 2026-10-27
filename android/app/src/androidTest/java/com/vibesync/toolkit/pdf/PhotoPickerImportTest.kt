package com.vibesync.toolkit.pdf.presentation.tools

import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vibesync.toolkit.pdf.presentation.scanner.rememberBookSplitPhotoPickerLauncher
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoPickerImportTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun registerImageImportLaunchers(): Pair<
        ActivityResultLauncher<PickVisualMediaRequest>,
        ActivityResultLauncher<PickVisualMediaRequest>
    > {
        val multipleLauncherRef = AtomicReference<ActivityResultLauncher<PickVisualMediaRequest>>()
        val singleLauncherRef = AtomicReference<ActivityResultLauncher<PickVisualMediaRequest>>()
        composeRule.setContent {
            val multipleLauncher = rememberPhotoPickerImportLauncher { _: List<Uri> -> }
            val singleLauncher = rememberBookSplitPhotoPickerLauncher { _: Uri? -> }
            SideEffect {
                multipleLauncherRef.set(multipleLauncher)
                singleLauncherRef.set(singleLauncher)
            }
        }
        composeRule.waitForIdle()
        return requireNotNull(multipleLauncherRef.get()) to requireNotNull(singleLauncherRef.get())
    }

    @Test
    fun test1_photoPickerUsesPickVisualMediaContract() {
        val (multiple, single) = registerImageImportLaunchers()
        composeRule.runOnIdle {
            assertTrue(
                "Images-to-PDF must register PickMultipleVisualMedia",
                multiple.contract is ActivityResultContracts.PickMultipleVisualMedia
            )
            assertTrue(
                "Book Split must register PickVisualMedia",
                single.contract is ActivityResultContracts.PickVisualMedia
            )
        }
    }

    @Test
    fun test2_noLegacyGetContentInImageImport() {
        val (multiple, single) = registerImageImportLaunchers()
        composeRule.runOnIdle {
            val registeredContracts = listOf(multiple.contract, single.contract)
            assertTrue(
                "All image-import launchers must use the Photo Picker contracts",
                registeredContracts.all {
                    it is ActivityResultContracts.PickVisualMedia ||
                        it is ActivityResultContracts.PickMultipleVisualMedia
                }
            )
        }
    }
}
