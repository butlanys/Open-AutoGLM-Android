package com.autoglm.android.device

import android.util.Log
import com.autoglm.android.config.AppPackages
import com.autoglm.android.shizuku.ShizukuExecutor
import kotlinx.coroutines.delay

object AppLauncher {
    
    private const val TAG = "AppLauncher"
    
    private var useLargeModelMode: Boolean = false
    
    fun setUseLargeModelMode(use: Boolean) {
        useLargeModelMode = use
    }
    
    suspend fun launchApp(appName: String, delayMs: Long = 2000): Boolean {
        val packageName = if (useLargeModelMode) {
            InstalledAppsProvider.findPackageByName(appName)
        } else {
            AppPackages.getPackageName(appName)
        }
        
        if (packageName == null) {
            Log.w(TAG, "App not found: $appName (largeModelMode=$useLargeModelMode)")
            return false
        }
        
        Log.d(TAG, "Launching app: $appName -> $packageName")
        return launchByPackage(packageName, delayMs)
    }
    
    suspend fun launchByPackage(packageName: String, delayMs: Long = 2000): Boolean {
        val result = ShizukuExecutor.execute(
            "monkey -p $packageName -c android.intent.category.LAUNCHER 1"
        )
        
        delay(delayMs)
        val success = result.success || result.output.contains("Events injected")
        Log.d(TAG, "Launch result for $packageName: success=$success")
        return success
    }
    
    suspend fun launchByIntent(packageName: String, activityName: String, delayMs: Long = 2000): Boolean {
        val result = ShizukuExecutor.execute(
            "am start -n $packageName/$activityName"
        )
        
        delay(delayMs)
        return result.success
    }
}
