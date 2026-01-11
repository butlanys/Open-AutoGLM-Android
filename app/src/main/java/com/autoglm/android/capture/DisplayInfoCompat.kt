/*
 * Copyright (C) 2024 AutoGLM
 *
 * Display information wrapper using reflection for hidden APIs.
 * 
 * Inspired by scrcpy project (Apache License 2.0)
 * https://github.com/Genymobile/scrcpy
 * Copyright (C) 2018 Genymobile
 * Copyright (C) 2018-2024 Romain Vimont
 */

package com.autoglm.android.capture

import android.annotation.SuppressLint
import android.graphics.Point
import android.os.Build
import android.util.Log
import android.view.Display
import java.lang.reflect.Method

@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
object DisplayInfoCompat {
    
    private const val TAG = "DisplayInfoCompat"
    
    private val displayManagerGlobalClass: Class<*>? by lazy {
        try {
            Class.forName("android.hardware.display.DisplayManagerGlobal")
        } catch (e: ClassNotFoundException) {
            null
        }
    }
    
    private val displayInfoClass: Class<*>? by lazy {
        try {
            Class.forName("android.view.DisplayInfo")
        } catch (e: ClassNotFoundException) {
            null
        }
    }
    
    data class DisplayMetrics(
        val width: Int,
        val height: Int,
        val rotation: Int,
        val density: Int
    )
    
    /**
     * Get display metrics using hidden DisplayManagerGlobal API.
     * This approach is derived from scrcpy's DisplayManager wrapper.
     */
    fun getDisplayInfo(displayId: Int = Display.DEFAULT_DISPLAY): DisplayMetrics? {
        return try {
            getDisplayInfoViaReflection(displayId)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get display info via reflection: ${e.message}")
            null
        }
    }
    
    private fun getDisplayInfoViaReflection(displayId: Int): DisplayMetrics? {
        val dmgClass = displayManagerGlobalClass ?: return null
        val diClass = displayInfoClass ?: return null
        
        val getInstance = dmgClass.getMethod("getInstance")
        val dmgInstance = getInstance.invoke(null) ?: return null
        
        val getDisplayInfo = dmgClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
        val displayInfo = getDisplayInfo.invoke(dmgInstance, displayId) ?: return null
        
        val logicalWidth = diClass.getField("logicalWidth").getInt(displayInfo)
        val logicalHeight = diClass.getField("logicalHeight").getInt(displayInfo)
        val rotation = diClass.getField("rotation").getInt(displayInfo)
        val logicalDensityDpi = diClass.getField("logicalDensityDpi").getInt(displayInfo)
        
        return DisplayMetrics(
            width = logicalWidth,
            height = logicalHeight,
            rotation = rotation,
            density = logicalDensityDpi
        )
    }
    
    /**
     * Get real display size using WindowManager hidden API.
     */
    fun getRealDisplaySize(displayId: Int = Display.DEFAULT_DISPLAY): Point? {
        return try {
            val displayInfo = getDisplayInfoViaReflection(displayId)
            displayInfo?.let { Point(it.width, it.height) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get real display size", e)
            null
        }
    }
}
