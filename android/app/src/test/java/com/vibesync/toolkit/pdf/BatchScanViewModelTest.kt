package com.vibesync.toolkit.pdf

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.vibesync.toolkit.pdf.data.pdf.ResolutionProfilePrefs
import com.vibesync.toolkit.pdf.presentation.scanner.BatchScanViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BatchScanViewModelTest {
    @Test fun orderedPagesReloadWhenBatchScanViewModelIsRecreated() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val dir=File(app.filesDir,"VibeSyncScans")
        val prefs=app.getSharedPreferences(ResolutionProfilePrefs.PREFERENCES_FILE,0)
        val had=prefs.contains(BatchScanViewModel.KEY_BATCH_ENABLED)
        val previous=prefs.getBoolean(BatchScanViewModel.KEY_BATCH_ENABLED,false)
        dir.deleteRecursively()
        val sources=sourcePages(app)
        try {
            val first=BatchScanViewModel(app)
            captureTwo(first,sources)
            val original=first.state.value.pages
            assertEquals(2,original.size)
            assertTrue(File(dir,"batch_pages.json").isFile)
            val recreated=BatchScanViewModel(app)
            assertEquals(2,recreated.state.value.pages.size)
            assertEquals(original.map{it.file.name},recreated.state.value.pages.map{it.file.name})
            assertEquals(original.map{it.id},recreated.state.value.pages.map{it.id})
            assertTrue(recreated.state.value.pages.all{it.file.isFile})
            recreated.clearPages()
            assertTrue(recreated.state.value.pages.isEmpty())
            assertTrue(original.none{it.file.exists()})
            assertFalse(File(dir,"batch_pages.json").exists())
        } finally {
            sources.forEach{it.delete()}; dir.deleteRecursively(); restorePref(prefs,had,previous)
        }
    }

    @Test fun compileSuccessKeepsPersistentPagesUntilClearAll() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val dir=File(app.filesDir,"VibeSyncScans")
        val prefs=app.getSharedPreferences(ResolutionProfilePrefs.PREFERENCES_FILE,0)
        val had=prefs.contains(BatchScanViewModel.KEY_BATCH_ENABLED)
        val previous=prefs.getBoolean(BatchScanViewModel.KEY_BATCH_ENABLED,false)
        dir.deleteRecursively()
        PDFBoxResourceLoader.init(app)
        val sources=sourcePages(app)
        var pdf:File?=null
        try {
            val vm=BatchScanViewModel(app)
            captureTwo(vm,sources)
            val originalFiles=vm.state.value.pages.map{it.file}
            vm.compileBatch("All")
            waitFor {
                vm.state.value.savedFile!=null ||
                    (!vm.state.value.isCompiling && vm.state.value.messageRes!=null)
            }
            pdf=vm.state.value.savedFile
            assertTrue("Compile should succeed.",pdf?.isFile==true)
            assertEquals(2,vm.state.value.pages.size)
            assertTrue(originalFiles.all{it.isFile})
            assertTrue(File(dir,"batch_pages.json").isFile)
            vm.clearPages()
            assertTrue(vm.state.value.pages.isEmpty())
            assertTrue(originalFiles.none{it.exists()})
            assertFalse(File(dir,"batch_pages.json").exists())
        } finally {
            pdf?.delete(); sources.forEach{it.delete()}; dir.deleteRecursively(); restorePref(prefs,had,previous)
        }
    }

    private fun captureTwo(vm:BatchScanViewModel,sources:List<File>) {
        vm.setBatchModeEnabled(true); vm.requestCapture()
        assertTrue(vm.handleCaptureResult(Activity.RESULT_OK,sources.map(Uri::fromFile)))
        waitFor{vm.state.value.pages.size==2}
    }
    private fun sourcePages(app: Application): List<File> = (1..2).map { index ->
        val f=File.createTempFile("batch-vm-$index-",".jpg",app.cacheDir)
        val bitmap=Bitmap.createBitmap(96,144,Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(if(index==1) android.graphics.Color.RED else android.graphics.Color.BLUE)
            FileOutputStream(f).use{out->check(bitmap.compress(Bitmap.CompressFormat.JPEG,92,out))}
        } finally { bitmap.recycle() }
        f
    }
    private fun restorePref(p:android.content.SharedPreferences,had:Boolean,previous:Boolean) {
        if(had)p.edit().putBoolean(BatchScanViewModel.KEY_BATCH_ENABLED,previous).apply()
        else p.edit().remove(BatchScanViewModel.KEY_BATCH_ENABLED).apply()
    }
    private fun waitFor(predicate:()->Boolean) {
        val deadline=System.currentTimeMillis()+15_000L
        val main=Shadows.shadowOf(Looper.getMainLooper())
        while(!predicate()&&System.currentTimeMillis()<deadline){main.idle();Thread.sleep(15L)}
        main.idle()
        assertTrue("Timed out waiting for BatchScanViewModel.",predicate())
    }
}
