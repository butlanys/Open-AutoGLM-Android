package com.autoglm.android.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.autoglm.android.data.LogManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AgentForegroundService : Service() {
    
    companion object {
        private const val TAG = "AgentForegroundService"
        const val ACTION_START = "com.autoglm.android.action.START"
        const val ACTION_STOP = "com.autoglm.android.action.STOP"
        const val ACTION_PAUSE = "com.autoglm.android.action.PAUSE"
        const val ACTION_RESUME = "com.autoglm.android.action.RESUME"
        const val EXTRA_TASK = "extra_task"
        const val EXTRA_MAX_STEPS = "extra_max_steps"
        
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
        
        private val _isPaused = MutableStateFlow(false)
        val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()
        
        private var notificationHelper: AgentNotificationHelper? = null
        
        fun start(context: Context, task: String, maxSteps: Int = 100) {
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TASK, task)
                putExtra(EXTRA_MAX_STEPS, maxSteps)
            }
            context.startForegroundService(intent)
        }
        
        fun stop(context: Context) {
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop service via intent, stopping directly", e)
                notificationHelper?.updateNotification(AgentNotificationState.Completed("Task stopped"))
            }
        }
        
        fun pause(context: Context) {
            _isPaused.value = true
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_PAUSE
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to pause service via intent", e)
            }
        }
        
        fun resume(context: Context) {
            _isPaused.value = false
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_RESUME
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resume service via intent", e)
            }
        }
        
        fun updateProgress(
            context: Context,
            step: Int,
            maxSteps: Int,
            actionType: String?,
            thinking: String
        ) {
            currentStep = step
            currentMaxSteps = maxSteps
            currentThinking = thinking
            val helper = notificationHelper ?: AgentNotificationHelper(context).also { notificationHelper = it }
            if (!_isPaused.value) {
                helper.updateNotification(
                    AgentNotificationState.Running(step, maxSteps, actionType, thinking)
                )
            }
        }
        
        private var currentStep = 0
        private var currentMaxSteps = 100
        private var currentThinking = ""
        
        fun notifyCompleted(context: Context, message: String) {
            val helper = notificationHelper ?: AgentNotificationHelper(context).also { notificationHelper = it }
            helper.updateNotification(AgentNotificationState.Completed(message))
            _isRunning.value = false
        }
        
        fun notifyFailed(context: Context, error: String) {
            val helper = notificationHelper ?: AgentNotificationHelper(context).also { notificationHelper = it }
            helper.updateNotification(AgentNotificationState.Failed(error))
            _isRunning.value = false
        }
    }
    
    private lateinit var localNotificationHelper: AgentNotificationHelper
    
    override fun onCreate() {
        super.onCreate()
        localNotificationHelper = AgentNotificationHelper(this)
        notificationHelper = localNotificationHelper
        Log.d(TAG, "Service created")
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val task = intent.getStringExtra(EXTRA_TASK) ?: ""
                val maxSteps = intent.getIntExtra(EXTRA_MAX_STEPS, 100)
                currentMaxSteps = maxSteps
                Log.d(TAG, "Starting service with task: ${task.take(50)}")
                LogManager.i(TAG, "前台服务启动")
                
                val initialState = AgentNotificationState.Running(
                    step = 0,
                    maxSteps = maxSteps,
                    actionType = null,
                    thinking = task
                )
                startForeground(AgentNotificationHelper.NOTIFICATION_ID, localNotificationHelper.createNotification(initialState))
                _isRunning.value = true
                _isPaused.value = false
            }
            
            ACTION_STOP -> {
                Log.d(TAG, "Stopping service")
                LogManager.i(TAG, "前台服务停止")
                stopForegroundAndService()
            }
            
            ACTION_PAUSE -> {
                Log.d(TAG, "Pausing service")
                LogManager.i(TAG, "任务暂停")
                _isPaused.value = true
                localNotificationHelper.updateNotification(
                    AgentNotificationState.Paused(currentStep, currentMaxSteps, currentThinking)
                )
            }
            
            ACTION_RESUME -> {
                Log.d(TAG, "Resuming service")
                LogManager.i(TAG, "任务恢复")
                _isPaused.value = false
                localNotificationHelper.updateNotification(
                    AgentNotificationState.Running(currentStep, currentMaxSteps, null, currentThinking)
                )
            }
        }
        return START_NOT_STICKY
    }
    
    private fun stopForegroundAndService() {
        _isRunning.value = false
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        _isRunning.value = false
        notificationHelper = null
        Log.d(TAG, "Service destroyed")
    }
}
