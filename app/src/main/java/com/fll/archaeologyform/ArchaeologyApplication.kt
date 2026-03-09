package com.fll.archaeologyform

import android.app.Application
import com.meta.wearable.dat.core.Wearables

class ArchaeologyApplication : Application() {
    override fun onCreate() {

        super.onCreate()
        
        // Initialize Meta Wearables SDK
        // The context (this) is passed to set up the connection
        Wearables.initialize(this)
    }
}
