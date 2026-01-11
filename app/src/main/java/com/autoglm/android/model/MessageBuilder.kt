package com.autoglm.android.model

import com.autoglm.android.config.AppPackages
import com.autoglm.android.device.InstalledAppsProvider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

object MessageBuilder {
    
    private var installedAppsCache: String? = null
    private var useLargeModelMode: Boolean = false
    
    fun createSystemMessage(content: String): Map<String, Any> {
        return mapOf(
            "role" to "system",
            "content" to content
        )
    }
    
    fun createUserMessage(text: String, imageBase64: String? = null): Map<String, Any> {
        val contentList = mutableListOf<Map<String, Any>>()
        
        if (imageBase64 != null) {
            contentList.add(mapOf(
                "type" to "image_url",
                "image_url" to mapOf(
                    "url" to "data:image/png;base64,$imageBase64"
                )
            ))
        }
        
        contentList.add(mapOf(
            "type" to "text",
            "text" to text
        ))
        
        return mapOf(
            "role" to "user",
            "content" to contentList
        )
    }
    
    fun createAssistantMessage(content: String): Map<String, Any> {
        return mapOf(
            "role" to "assistant",
            "content" to content
        )
    }
    
    fun removeImagesFromMessage(message: Map<String, Any>): Map<String, Any> {
        val content = message["content"]
        if (content is List<*>) {
            val filteredContent = content.filterIsInstance<Map<*, *>>()
                .filter { it["type"] == "text" }
            return message.toMutableMap().apply {
                this["content"] = filteredContent
            }
        }
        return message
    }
    
    fun buildScreenInfo(currentApp: String, extraInfo: Map<String, Any> = emptyMap()): String {
        val info = mutableMapOf<String, Any>("current_app" to currentApp)
        info.putAll(extraInfo)
        
        return buildJsonObject {
            info.forEach { (key, value) ->
                when (value) {
                    is String -> put(key, JsonPrimitive(value))
                    is Number -> put(key, JsonPrimitive(value))
                    is Boolean -> put(key, JsonPrimitive(value))
                    else -> put(key, JsonPrimitive(value.toString()))
                }
            }
        }.toString()
    }
    
    fun setUseLargeModelMode(use: Boolean) {
        if (useLargeModelMode != use) {
            useLargeModelMode = use
            installedAppsCache = null
        }
    }
    
    fun getInstalledAppsPrompt(): String {
        if (installedAppsCache == null) {
            installedAppsCache = if (useLargeModelMode) {
                val apps = InstalledAppsProvider.getInstalledApps(forceRefresh = true)
                apps.joinToString(", ") { "${it.name}(${it.packageName})" }
            } else {
                AppPackages.APP_PACKAGES.entries.joinToString(", ") { "${it.key}(${it.value})" }
            }
        }
        return installedAppsCache!!
    }
    
    fun buildFirstStepPrompt(task: String, currentApp: String): String {
        val screenInfo = buildScreenInfo(currentApp)
        val installedApps = getInstalledAppsPrompt()
        
        return """$task

** Screen Info **
$screenInfo

** Installed Apps **
$installedApps"""
    }
    
    fun clearCache() {
        installedAppsCache = null
    }
}
