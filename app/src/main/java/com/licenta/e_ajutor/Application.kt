package com.licenta.e_ajutor

import android.app.Application
import com.google.android.libraries.places.api.Places

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        if (!Places.isInitialized()) {
            Places.initialize(applicationContext, "AIzaSyC970ugOBxUkDoInKY0MMNhMmzZU7_Xmww")
        }
    }
}