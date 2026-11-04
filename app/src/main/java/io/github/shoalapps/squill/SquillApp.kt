package io.github.shoalapps.squill

import android.app.Application

class SquillApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AlarmReceiver.createChannel(this)
    }
}
