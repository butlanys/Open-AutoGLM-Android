package com.autoglm.android.device

import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.autoglm.android.AutoGLMApplication

data class InstalledApp(
    val name: String,
    val packageName: String
)

object InstalledAppsProvider {
    
    private const val TAG = "InstalledAppsProvider"
    
    private var cachedApps: List<InstalledApp>? = null
    
    fun getInstalledApps(forceRefresh: Boolean = false): List<InstalledApp> {
        if (!forceRefresh && cachedApps != null) {
            return cachedApps!!
        }
        
        val context = AutoGLMApplication.instance
        val pm = context.packageManager
        
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        
        val apps = pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
            .mapNotNull { resolveInfo ->
                try {
                    val appName = resolveInfo.loadLabel(pm).toString()
                    val packageName = resolveInfo.activityInfo.packageName
                    InstalledApp(appName, packageName)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get app info", e)
                    null
                }
            }
            .distinctBy { it.packageName }
            .sortedBy { it.name }
        
        cachedApps = apps
        Log.d(TAG, "Found ${apps.size} installed apps")
        return apps
    }
    
    fun findPackageByName(appName: String): String? {
        val apps = getInstalledApps()
        
        return apps.find { it.name.equals(appName, ignoreCase = true) }?.packageName
            ?: apps.find { it.name.contains(appName, ignoreCase = true) }?.packageName
            ?: apps.find { it.packageName.contains(appName, ignoreCase = true) }?.packageName
    }
    
    fun getAppsForPrompt(maxApps: Int = 100): String {
        val apps = getInstalledApps().take(maxApps)
        return apps.joinToString("\n") { "- ${it.name}: ${it.packageName}" }
    }
    
    fun getAppsAsMap(): Map<String, String> {
        return getInstalledApps().associate { it.name to it.packageName }
    }
    
    fun clearCache() {
        cachedApps = null
    }
}
