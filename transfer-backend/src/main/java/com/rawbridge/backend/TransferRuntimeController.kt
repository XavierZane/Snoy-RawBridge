package com.rawbridge.backend

import android.content.Context
import androidx.core.content.ContextCompat
import com.rawbridge.backend.runtime.TransferReceiverService

interface TransferRuntimeController {
    fun startReceiver()
    fun stopReceiver()
    fun refreshCatalog()
    fun importSelected(captureIds: List<String>)
}

internal class AndroidTransferRuntimeController(
    private val appContext: Context,
) : TransferRuntimeController {
    override fun startReceiver() {
        ContextCompat.startForegroundService(
            appContext,
            TransferReceiverService.startIntent(appContext),
        )
    }

    override fun stopReceiver() {
        appContext.startService(TransferReceiverService.stopIntent(appContext))
    }

    override fun refreshCatalog() {
        appContext.startService(TransferReceiverService.refreshCatalogIntent(appContext))
    }

    override fun importSelected(captureIds: List<String>) {
        appContext.startService(
            TransferReceiverService.importSelectedIntent(appContext, captureIds),
        )
    }
}
