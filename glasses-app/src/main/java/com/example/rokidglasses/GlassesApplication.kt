package com.example.rokidglasses

import android.app.Application

class GlassesApplication : Application() {
    
    companion object {
        lateinit var instance: GlassesApplication
            private set
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
