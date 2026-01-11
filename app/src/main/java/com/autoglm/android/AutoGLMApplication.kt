package com.autoglm.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.autoglm.android.data.CrashHandler

class AutoGLMApplication : Application() {
    
    companion object {
        const val NOTIFICATION_CHANNEL_ID = "agent_service_channel"
        
        lateinit var instance: AutoGLMApplication
            private set
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashHandler.init(this)
        createNotificationChannel()
    }
    
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}
