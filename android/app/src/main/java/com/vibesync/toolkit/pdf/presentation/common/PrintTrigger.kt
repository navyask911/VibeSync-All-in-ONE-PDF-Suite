package com.vibesync.toolkit.pdf.presentation.common

import android.app.Activity
import android.content.Context
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintManager

interface PrintTrigger {
    fun triggerPrint(
        activity: Activity,
        jobName: String,
        adapter: PrintDocumentAdapter,
        attributes: PrintAttributes
    )
}

class AndroidPrintTrigger : PrintTrigger {
    override fun triggerPrint(
        activity: Activity,
        jobName: String,
        adapter: PrintDocumentAdapter,
        attributes: PrintAttributes
    ) {
        val printManager = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
        printManager.print(jobName, adapter, attributes)
    }
}
