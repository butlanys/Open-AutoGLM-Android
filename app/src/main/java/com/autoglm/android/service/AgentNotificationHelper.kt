package com.autoglm.android.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.autoglm.android.AutoGLMApplication
import com.autoglm.android.MainActivity
import com.autoglm.android.R

sealed class AgentNotificationState {
    data class Running(
        val step: Int,
        val maxSteps: Int,
        val actionType: String?,
        val thinking: String
    ) : AgentNotificationState()
    
    data class Paused(
        val step: Int,
        val maxSteps: Int,
        val thinking: String
    ) : AgentNotificationState()
    
    data class Completed(val message: String) : AgentNotificationState()
    data class Failed(val error: String) : AgentNotificationState()
}

class AgentNotificationHelper(private val context: Context) {
    
    companion object {
        private const val TAG = "AgentNotificationHelper"
        const val NOTIFICATION_ID = 1001
        const val RESULT_NOTIFICATION_ID = 1002
        private const val ANDROID_16 = 36
    }
    
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    private val contentIntent: PendingIntent by lazy {
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
    
    private val stopIntent: PendingIntent by lazy {
        PendingIntent.getService(
            context,
            1,
            Intent(context, AgentForegroundService::class.java).apply {
                action = AgentForegroundService.ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
    
    private val pauseIntent: PendingIntent by lazy {
        PendingIntent.getService(
            context,
            2,
            Intent(context, AgentForegroundService::class.java).apply {
                action = AgentForegroundService.ACTION_PAUSE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
    
    private val resumeIntent: PendingIntent by lazy {
        PendingIntent.getService(
            context,
            3,
            Intent(context, AgentForegroundService::class.java).apply {
                action = AgentForegroundService.ACTION_RESUME
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
    
    fun createNotification(state: AgentNotificationState): Notification {
        return if (Build.VERSION.SDK_INT >= ANDROID_16) {
            createLiveUpdateNotification(state)
        } else {
            createCompatNotification(state)
        }
    }
    
    fun updateNotification(state: AgentNotificationState) {
        val notification = createNotification(state)
        notificationManager.notify(NOTIFICATION_ID, notification)
        
        if (state is AgentNotificationState.Completed || state is AgentNotificationState.Failed) {
            postResultNotification(state)
            vibrate()
        }
    }
    
    private fun postResultNotification(state: AgentNotificationState) {
        val notification = createResultNotification(state)
        notificationManager.notify(RESULT_NOTIFICATION_ID, notification)
    }
    
    private fun createResultNotification(state: AgentNotificationState): Notification {
        return when (state) {
            is AgentNotificationState.Completed -> createCompletedResultNotification(state)
            is AgentNotificationState.Failed -> createFailedResultNotification(state)
            else -> throw IllegalArgumentException("Only Completed or Failed states are supported")
        }
    }
    
    private fun createCompletedResultNotification(state: AgentNotificationState.Completed): Notification {
        return NotificationCompat.Builder(context, AutoGLMApplication.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.notification_completed_title))
            .setContentText(state.message.take(100))
            .setStyle(NotificationCompat.BigTextStyle().bigText(state.message))
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
    }
    
    private fun createFailedResultNotification(state: AgentNotificationState.Failed): Notification {
        return NotificationCompat.Builder(context, AutoGLMApplication.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(context.getString(R.string.notification_failed_title))
            .setContentText(state.error.take(100))
            .setStyle(NotificationCompat.BigTextStyle().bigText(state.error))
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
    }
    
    @RequiresApi(ANDROID_16)
    private fun createLiveUpdateNotification(state: AgentNotificationState): Notification {
        return when (state) {
            is AgentNotificationState.Running -> createRunningLiveNotification(state)
            is AgentNotificationState.Paused -> createPausedLiveNotification(state)
            is AgentNotificationState.Completed -> createCompletedLiveNotification(state)
            is AgentNotificationState.Failed -> createFailedLiveNotification(state)
        }
    }
    
    @RequiresApi(ANDROID_16)
    private fun createRunningLiveNotification(state: AgentNotificationState.Running): Notification {
        val progressStyle = Notification.ProgressStyle()
            .setProgress(state.step)
            .setStyledByProgress(true)
        
        return Notification.Builder(context, AutoGLMApplication.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(context.getString(R.string.notification_running_title, state.step, state.maxSteps))
            .setContentText(state.actionType ?: context.getString(R.string.executing))
            .setStyle(progressStyle)
            .setOngoing(true)
            .setShortCriticalText("${state.step}/${state.maxSteps}")
            .setContentIntent(contentIntent)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(context, android.R.drawable.ic_media_pause),
                    context.getString(R.string.notification_pause),
                    pauseIntent
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(context, android.R.drawable.ic_delete),
                    context.getString(R.string.notification_stop),
                    stopIntent
                ).build()
            )
            .setFlag(Notification.FLAG_ONGOING_EVENT, true)
            .build()
    }
    
    @RequiresApi(ANDROID_16)
    private fun createPausedLiveNotification(state: AgentNotificationState.Paused): Notification {
        return Notification.Builder(context, AutoGLMApplication.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentTitle(context.getString(R.string.notification_paused_title, state.step, state.maxSteps))
            .setContentText(context.getString(R.string.paused))
            .setOngoing(true)
            .setShortCriticalText(context.getString(R.string.paused))
            .setContentIntent(contentIntent)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(context, android.R.drawable.ic_media_play),
                    context.getString(R.string.notification_resume),
                    resumeIntent
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(context, android.R.drawable.ic_delete),
                    context.getString(R.string.notification_stop),
                    stopIntent
                ).build()
            )
            .setFlag(Notification.FLAG_ONGOING_EVENT, true)
            .build()
    }
    
    @RequiresApi(ANDROID_16)
    private fun createCompletedLiveNotification(state: AgentNotificationState.Completed): Notification {
        return Notification.Builder(context, AutoGLMApplication.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.notification_completed_title))
            .setContentText(state.message.take(100))
            .setStyle(Notification.BigTextStyle().bigText(state.message))
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
    }
    
    @RequiresApi(ANDROID_16)
    private fun createFailedLiveNotification(state: AgentNotificationState.Failed): Notification {
        return Notification.Builder(context, AutoGLMApplication.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(context.getString(R.string.notification_failed_title))
            .setContentText(state.error.take(100))
            .setStyle(Notification.BigTextStyle().bigText(state.error))
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
    }
    
    private fun createCompatNotification(state: AgentNotificationState): Notification {
        return when (state) {
            is AgentNotificationState.Running -> createRunningCompatNotification(state)
            is AgentNotificationState.Paused -> createPausedCompatNotification(state)
            is AgentNotificationState.Completed -> createCompletedCompatNotification(state)
            is AgentNotificationState.Failed -> createFailedCompatNotification(state)
        }
    }
    
    private fun createRunningCompatNotification(state: AgentNotificationState.Running): Notification {
        return NotificationCompat.Builder(context, AutoGLMApplication.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(context.getString(R.string.notification_running_title, state.step, state.maxSteps))
            .setContentText(state.actionType ?: context.getString(R.string.executing))
            .setProgress(state.maxSteps, state.step, false)
            .setStyle(NotificationCompat.BigTextStyle().bigText(state.thinking.take(500)))
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                context.getString(R.string.notification_pause),
                pauseIntent
            )
            .addAction(
                android.R.drawable.ic_delete,
                context.getString(R.string.notification_stop),
                stopIntent
            )
            .build()
    }
    
    private fun createPausedCompatNotification(state: AgentNotificationState.Paused): Notification {
        return NotificationCompat.Builder(context, AutoGLMApplication.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentTitle(context.getString(R.string.notification_paused_title, state.step, state.maxSteps))
            .setContentText(context.getString(R.string.paused))
            .setProgress(state.maxSteps, state.step, false)
            .setStyle(NotificationCompat.BigTextStyle().bigText(state.thinking.take(500)))
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(
                android.R.drawable.ic_media_play,
                context.getString(R.string.notification_resume),
                resumeIntent
            )
            .addAction(
                android.R.drawable.ic_delete,
                context.getString(R.string.notification_stop),
                stopIntent
            )
            .build()
    }
    
    private fun createCompletedCompatNotification(state: AgentNotificationState.Completed): Notification {
        return NotificationCompat.Builder(context, AutoGLMApplication.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.notification_completed_title))
            .setContentText(state.message.take(100))
            .setStyle(NotificationCompat.BigTextStyle().bigText(state.message))
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .build()
    }
    
    private fun createFailedCompatNotification(state: AgentNotificationState.Failed): Notification {
        return NotificationCompat.Builder(context, AutoGLMApplication.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(context.getString(R.string.notification_failed_title))
            .setContentText(state.error.take(100))
            .setStyle(NotificationCompat.BigTextStyle().bigText(state.error))
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .build()
    }
    
    private fun vibrate() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                manager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            
            val effect = VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE)
            vibrator.vibrate(effect)
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed", e)
        }
    }
    
    @RequiresApi(ANDROID_16)
    fun canPostPromotedNotifications(): Boolean {
        return notificationManager.canPostPromotedNotifications()
    }
}
