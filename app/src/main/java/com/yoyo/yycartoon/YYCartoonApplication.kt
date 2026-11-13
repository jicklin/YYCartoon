package com.yoyo.yycartoon

import android.app.Application

class YYCartoonApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: YYCartoonApplication
            private set
    }
}
