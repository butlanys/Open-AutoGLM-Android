/*
 * Copyright (C) 2024 AutoGLM
 *
 * Utility to check if an app supports running on virtual/secondary displays.
 * Determines compatibility based on manifest attributes and runtime behavior.
 */

package com.autoglm.android.display

import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.autoglm.android.shizuku.ShizukuExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object AppDisplayCompatibility {
    
    private const val TAG = "AppDisplayCompat"
    
    data class CompatibilityInfo(
        val packageName: String,
        val supportsVirtualDisplay: Boolean,
        val supportsResize: Boolean,
        val supportsPip: Boolean,
        val supportsMultiWindow: Boolean,
        val reason: String
    )
    
    private val compatibilityCache = mutableMapOf<String, CompatibilityInfo>()
    
    private val knownIncompatibleApps = setOf(
        "com.google.android.apps.maps",
        "com.google.ar.core",
        "com.android.camera",
        "com.android.camera2"
    )
    
    private val knownCompatibleApps = setOf(
        "com.android.settings",
        "com.android.chrome",
        "com.google.android.youtube",
        "com.whatsapp",
        "com.facebook.orca",
        "org.telegram.messenger",
        "com.twitter.android",
        "com.instagram.android"
    )
    
    fun checkCompatibility(
        packageName: String,
        packageManager: PackageManager
    ): CompatibilityInfo {
        compatibilityCache[packageName]?.let { return it }
        
        return try {
            val packageInfo = packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA
            )
            
            val appInfo = packageInfo.applicationInfo
            
            val supportsResize = isResizable(appInfo)
            val supportsPip = supportsPictureInPicture(packageInfo)
            val supportsMultiWindow = supportsMultiWindow(appInfo)
            
            val supportsVirtualDisplay = when {
                knownIncompatibleApps.contains(packageName) -> false
                knownCompatibleApps.contains(packageName) -> true
                !supportsResize -> false
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && supportsMultiWindow -> true
                supportsPip -> true
                else -> supportsResize
            }
            
            val reason = buildCompatibilityReason(
                packageName, supportsVirtualDisplay, supportsResize, 
                supportsPip, supportsMultiWindow
            )
            
            CompatibilityInfo(
                packageName = packageName,
                supportsVirtualDisplay = supportsVirtualDisplay,
                supportsResize = supportsResize,
                supportsPip = supportsPip,
                supportsMultiWindow = supportsMultiWindow,
                reason = reason
            ).also {
                compatibilityCache[packageName] = it
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Package not found: $packageName")
            CompatibilityInfo(
                packageName = packageName,
                supportsVirtualDisplay = false,
                supportsResize = false,
                supportsPip = false,
                supportsMultiWindow = false,
                reason = "Package not found"
            )
        }
    }
    
    private fun isResizable(appInfo: ApplicationInfo?): Boolean {
        if (appInfo == null) return true
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val field = ApplicationInfo::class.java.getDeclaredField("privateFlags")
                val privateFlags = field.getInt(appInfo)
                val PRIVATE_FLAG_RESIZABLE_ACTIVITY_VIA_SDK_VERSION = 1 shl 14
                val PRIVATE_FLAG_RESIZABLE_ACTIVITY = 1 shl 13
                
                (privateFlags and PRIVATE_FLAG_RESIZABLE_ACTIVITY != 0) ||
                (privateFlags and PRIVATE_FLAG_RESIZABLE_ACTIVITY_VIA_SDK_VERSION != 0) ||
                appInfo.targetSdkVersion >= Build.VERSION_CODES.N
            } catch (e: Exception) {
                appInfo.targetSdkVersion >= Build.VERSION_CODES.N
            }
        } else {
            true
        }
    }
    
    private fun supportsPictureInPicture(packageInfo: PackageInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        
        return packageInfo.activities?.any { activity ->
            try {
                val field = ActivityInfo::class.java.getDeclaredField("flags")
                val flags = field.getInt(activity)
                val FLAG_SUPPORTS_PICTURE_IN_PICTURE = 0x400000
                flags and FLAG_SUPPORTS_PICTURE_IN_PICTURE != 0
            } catch (e: Exception) {
                false
            }
        } ?: false
    }
    
    private fun supportsMultiWindow(appInfo: ApplicationInfo?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        if (appInfo == null) return false
        
        return try {
            val field = ApplicationInfo::class.java.getDeclaredField("privateFlags")
            val privateFlags = field.getInt(appInfo)
            val PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE = 1 shl 10
            privateFlags and PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE != 0
        } catch (e: Exception) {
            appInfo.targetSdkVersion >= Build.VERSION_CODES.N
        }
    }
    
    private fun buildCompatibilityReason(
        packageName: String,
        supports: Boolean,
        resizable: Boolean,
        pip: Boolean,
        multiWindow: Boolean
    ): String {
        return when {
            knownIncompatibleApps.contains(packageName) -> "Known incompatible app"
            knownCompatibleApps.contains(packageName) -> "Known compatible app"
            !resizable -> "App is not resizable"
            supports && multiWindow -> "Supports multi-window mode"
            supports && pip -> "Supports picture-in-picture"
            supports -> "Default compatible (resizable)"
            else -> "Compatibility check failed"
        }
    }
    
    suspend fun verifyRuntimeCompatibility(
        packageName: String,
        displayId: Int
    ): Boolean = withContext(Dispatchers.IO) {
        if (displayId == 0) return@withContext true
        
        val launchResult = ShizukuExecutor.execute(
            "am start -n $packageName/.MainActivity --display $displayId 2>&1"
        )
        
        if (!launchResult.success) {
            return@withContext false
        }
        
        delay(1500)
        
        val checkResult = ShizukuExecutor.execute(
            "dumpsys activity activities | grep -E 'displayId=$displayId.*$packageName'"
        )
        
        if (checkResult.success && checkResult.output.contains(packageName)) {
            Log.d(TAG, "App $packageName successfully running on display $displayId")
            return@withContext true
        }
        
        val mainDisplayCheck = ShizukuExecutor.execute(
            "dumpsys activity activities | grep -E 'displayId=0.*$packageName'"
        )
        
        if (mainDisplayCheck.success && mainDisplayCheck.output.contains(packageName)) {
            Log.w(TAG, "App $packageName fell back to main display")
            compatibilityCache[packageName]?.let { info ->
                compatibilityCache[packageName] = info.copy(
                    supportsVirtualDisplay = false,
                    reason = "Runtime fallback to main display detected"
                )
            }
            return@withContext false
        }
        
        true
    }
    
    suspend fun launchOnDisplay(
        packageName: String,
        displayId: Int,
        activityName: String? = null
    ): LaunchResult = withContext(Dispatchers.IO) {
        val activity = activityName ?: getLauncherActivity(packageName)
        
        val displayArg = if (displayId > 0) "--display $displayId" else ""
        val cmd = if (activity != null) {
            "am start -n $packageName/$activity $displayArg"
        } else {
            "monkey -p $packageName -c android.intent.category.LAUNCHER 1"
        }
        
        val result = ShizukuExecutor.execute(cmd)
        
        if (!result.success) {
            return@withContext LaunchResult(
                success = false,
                displayId = displayId,
                fellBackToMainDisplay = false,
                error = result.error
            )
        }
        
        if (displayId > 0) {
            delay(1500)
            
            val verified = verifyRuntimeCompatibility(packageName, displayId)
            if (!verified) {
                return@withContext LaunchResult(
                    success = true,
                    displayId = 0,
                    fellBackToMainDisplay = true,
                    error = "App fell back to main display"
                )
            }
        }
        
        LaunchResult(
            success = true,
            displayId = displayId,
            fellBackToMainDisplay = false,
            error = null
        )
    }
    
    private suspend fun getLauncherActivity(packageName: String): String? {
        val result = ShizukuExecutor.execute(
            "cmd package resolve-activity --brief $packageName"
        )
        
        if (result.success) {
            val lines = result.output.lines()
            for (line in lines) {
                if (line.contains("/")) {
                    val parts = line.split("/")
                    if (parts.size >= 2) {
                        return parts[1].trim()
                    }
                }
            }
        }
        
        return null
    }
    
    fun clearCache() {
        compatibilityCache.clear()
    }
    
    fun getCachedInfo(packageName: String): CompatibilityInfo? = compatibilityCache[packageName]
    
    data class LaunchResult(
        val success: Boolean,
        val displayId: Int,
        val fellBackToMainDisplay: Boolean,
        val error: String?
    )
}
