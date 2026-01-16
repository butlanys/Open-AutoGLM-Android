/*
 * Copyright (C) 2024 AutoGLM
 *
 * Input manager for injecting touch/key events to specific displays.
 * Uses reflection to access InputManager hidden APIs similar to scrcpy.
 * 
 * Inspired by scrcpy project (Apache License 2.0)
 * https://github.com/Genymobile/scrcpy
 */

package com.autoglm.android.display

import android.annotation.SuppressLint
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import com.autoglm.android.shizuku.ShizukuExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.lang.reflect.Method

@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
object DisplayInputManager {
    
    private const val TAG = "DisplayInputManager"
    
    private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
    private const val INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT = 1
    private const val INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH = 2
    
    private val inputManagerClass: Class<*>? by lazy {
        try {
            Class.forName("android.hardware.input.InputManager")
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "InputManager class not found", e)
            null
        }
    }
    
    private val inputEventClass: Class<*>? by lazy {
        try {
            Class.forName("android.view.InputEvent")
        } catch (e: ClassNotFoundException) {
            null
        }
    }
    
    @Volatile
    private var inputManager: Any? = null
    
    @Volatile
    private var injectInputEventMethod: Method? = null
    
    @Volatile
    private var setDisplayIdMethod: Method? = null
    
    @Volatile
    private var useShellFallback = false
    
    init {
        initializeInputManager()
    }
    
    private fun initializeInputManager() {
        try {
            val imClass = inputManagerClass ?: return
            val getInstance = imClass.getMethod("getInstance")
            inputManager = getInstance.invoke(null)
            
            injectInputEventMethod = imClass.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            
            setDisplayIdMethod = try {
                inputEventClass?.getMethod("setDisplayId", Int::class.javaPrimitiveType)
            } catch (e: NoSuchMethodException) {
                Log.w(TAG, "setDisplayId method not found, using shell fallback")
                null
            }
            
            Log.d(TAG, "InputManager initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize InputManager", e)
            useShellFallback = true
        }
    }
    
    suspend fun tap(
        x: Int,
        y: Int,
        displayId: Int = 0,
        delayMs: Long = 500
    ): Boolean = withContext(Dispatchers.IO) {
        val success = if (displayId == 0 || useShellFallback) {
            tapViaShell(x, y, displayId)
        } else {
            tapViaReflection(x, y, displayId)
        }
        
        if (success) delay(delayMs)
        success
    }
    
    suspend fun doubleTap(
        x: Int,
        y: Int,
        displayId: Int = 0,
        delayMs: Long = 500
    ): Boolean = withContext(Dispatchers.IO) {
        val success1 = tap(x, y, displayId, 100)
        val success2 = tap(x, y, displayId, 0)
        if (success1 && success2) delay(delayMs)
        success1 && success2
    }
    
    suspend fun longPress(
        x: Int,
        y: Int,
        displayId: Int = 0,
        durationMs: Int = 3000,
        delayMs: Long = 500
    ): Boolean = withContext(Dispatchers.IO) {
        val success = if (displayId == 0 || useShellFallback) {
            longPressViaShell(x, y, durationMs, displayId)
        } else {
            longPressViaReflection(x, y, durationMs, displayId)
        }
        
        if (success) delay(delayMs)
        success
    }
    
    suspend fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        displayId: Int = 0,
        durationMs: Int? = null,
        delayMs: Long = 1000
    ): Boolean = withContext(Dispatchers.IO) {
        val duration = durationMs ?: run {
            val distSq = (startX - endX) * (startX - endX) + (startY - endY) * (startY - endY)
            (distSq / 1000).coerceIn(1000, 2000)
        }
        
        val success = if (displayId == 0 || useShellFallback) {
            swipeViaShell(startX, startY, endX, endY, duration, displayId)
        } else {
            swipeViaReflection(startX, startY, endX, endY, duration, displayId)
        }
        
        if (success) delay(delayMs)
        success
    }
    
    suspend fun back(displayId: Int = 0, delayMs: Long = 500): Boolean = withContext(Dispatchers.IO) {
        val success = pressKey(KeyEvent.KEYCODE_BACK, displayId)
        if (success) delay(delayMs)
        success
    }
    
    suspend fun home(displayId: Int = 0, delayMs: Long = 500): Boolean = withContext(Dispatchers.IO) {
        val success = pressKey(KeyEvent.KEYCODE_HOME, displayId)
        if (success) delay(delayMs)
        success
    }
    
    suspend fun pressEnter(displayId: Int = 0): Boolean = withContext(Dispatchers.IO) {
        pressKey(KeyEvent.KEYCODE_ENTER, displayId)
    }
    
    private suspend fun pressKey(keyCode: Int, displayId: Int): Boolean {
        return if (displayId == 0 || useShellFallback) {
            pressKeyViaShell(keyCode, displayId)
        } else {
            pressKeyViaReflection(keyCode, displayId)
        }
    }
    
    private fun tapViaReflection(x: Int, y: Int, displayId: Int): Boolean {
        return try {
            val now = SystemClock.uptimeMillis()
            
            val downEvent = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_DOWN,
                x.toFloat(), y.toFloat(), 0
            )
            setEventDisplayId(downEvent, displayId)
            
            val upEvent = MotionEvent.obtain(
                now, now + 50, MotionEvent.ACTION_UP,
                x.toFloat(), y.toFloat(), 0
            )
            setEventDisplayId(upEvent, displayId)
            
            val downResult = injectEvent(downEvent)
            val upResult = injectEvent(upEvent)
            
            downEvent.recycle()
            upEvent.recycle()
            
            downResult && upResult
        } catch (e: Exception) {
            Log.e(TAG, "tapViaReflection failed", e)
            false
        }
    }
    
    private fun longPressViaReflection(x: Int, y: Int, durationMs: Int, displayId: Int): Boolean {
        return try {
            val now = SystemClock.uptimeMillis()
            
            val downEvent = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_DOWN,
                x.toFloat(), y.toFloat(), 0
            )
            setEventDisplayId(downEvent, displayId)
            
            val result = injectEvent(downEvent)
            downEvent.recycle()
            
            if (!result) return false
            
            Thread.sleep(durationMs.toLong())
            
            val upEvent = MotionEvent.obtain(
                now, now + durationMs, MotionEvent.ACTION_UP,
                x.toFloat(), y.toFloat(), 0
            )
            setEventDisplayId(upEvent, displayId)
            
            val upResult = injectEvent(upEvent)
            upEvent.recycle()
            
            upResult
        } catch (e: Exception) {
            Log.e(TAG, "longPressViaReflection failed", e)
            false
        }
    }
    
    private fun swipeViaReflection(
        startX: Int, startY: Int,
        endX: Int, endY: Int,
        durationMs: Int,
        displayId: Int
    ): Boolean {
        return try {
            val now = SystemClock.uptimeMillis()
            val steps = 20
            val stepDuration = durationMs / steps
            
            val downEvent = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_DOWN,
                startX.toFloat(), startY.toFloat(), 0
            )
            setEventDisplayId(downEvent, displayId)
            if (!injectEvent(downEvent)) {
                downEvent.recycle()
                return false
            }
            downEvent.recycle()
            
            for (i in 1..steps) {
                val progress = i.toFloat() / steps
                val x = startX + (endX - startX) * progress
                val y = startY + (endY - startY) * progress
                val time = now + stepDuration * i
                
                val moveEvent = MotionEvent.obtain(
                    now, time, MotionEvent.ACTION_MOVE,
                    x, y, 0
                )
                setEventDisplayId(moveEvent, displayId)
                injectEvent(moveEvent)
                moveEvent.recycle()
                
                Thread.sleep(stepDuration.toLong())
            }
            
            val upEvent = MotionEvent.obtain(
                now, now + durationMs, MotionEvent.ACTION_UP,
                endX.toFloat(), endY.toFloat(), 0
            )
            setEventDisplayId(upEvent, displayId)
            val result = injectEvent(upEvent)
            upEvent.recycle()
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "swipeViaReflection failed", e)
            false
        }
    }
    
    private fun pressKeyViaReflection(keyCode: Int, displayId: Int): Boolean {
        return try {
            val now = SystemClock.uptimeMillis()
            
            val downEvent = KeyEvent(
                now, now, KeyEvent.ACTION_DOWN, keyCode, 0,
                0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD
            )
            setEventDisplayId(downEvent, displayId)
            
            val upEvent = KeyEvent(
                now, now + 50, KeyEvent.ACTION_UP, keyCode, 0,
                0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD
            )
            setEventDisplayId(upEvent, displayId)
            
            val downResult = injectEvent(downEvent)
            val upResult = injectEvent(upEvent)
            
            downResult && upResult
        } catch (e: Exception) {
            Log.e(TAG, "pressKeyViaReflection failed", e)
            false
        }
    }
    
    private fun setEventDisplayId(event: InputEvent, displayId: Int) {
        try {
            setDisplayIdMethod?.invoke(event, displayId)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set display ID: ${e.message}")
        }
    }
    
    private fun injectEvent(event: InputEvent): Boolean {
        return try {
            val result = injectInputEventMethod?.invoke(
                inputManager,
                event,
                INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH
            ) as? Boolean ?: false
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "injectEvent failed", e)
            false
        }
    }
    
    private suspend fun tapViaShell(x: Int, y: Int, displayId: Int): Boolean {
        val cmd = if (displayId > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "input -d $displayId tap $x $y"
        } else {
            "input tap $x $y"
        }
        
        val result = ShizukuExecutor.execute(cmd)
        return result.success
    }
    
    private suspend fun longPressViaShell(x: Int, y: Int, durationMs: Int, displayId: Int): Boolean {
        val cmd = if (displayId > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "input -d $displayId swipe $x $y $x $y $durationMs"
        } else {
            "input swipe $x $y $x $y $durationMs"
        }
        
        val result = ShizukuExecutor.execute(cmd)
        return result.success
    }
    
    private suspend fun swipeViaShell(
        startX: Int, startY: Int,
        endX: Int, endY: Int,
        durationMs: Int,
        displayId: Int
    ): Boolean {
        val cmd = if (displayId > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "input -d $displayId swipe $startX $startY $endX $endY $durationMs"
        } else {
            "input swipe $startX $startY $endX $endY $durationMs"
        }
        
        val result = ShizukuExecutor.execute(cmd)
        return result.success
    }
    
    private suspend fun pressKeyViaShell(keyCode: Int, displayId: Int): Boolean {
        val cmd = if (displayId > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "input -d $displayId keyevent $keyCode"
        } else {
            "input keyevent $keyCode"
        }
        
        val result = ShizukuExecutor.execute(cmd)
        return result.success
    }
    
    fun convertRelativeToAbsolute(
        element: List<Int>,
        screenWidth: Int,
        screenHeight: Int
    ): Pair<Int, Int> {
        val x = (element[0] / 1000.0 * screenWidth).toInt()
        val y = (element[1] / 1000.0 * screenHeight).toInt()
        return x to y
    }
}
