package com.autoglm.android.device

import android.util.Base64
import com.autoglm.android.shizuku.ShizukuExecutor
import kotlinx.coroutines.delay

object InputService {
    
    private const val ADB_KEYBOARD_IME = "com.android.adbkeyboard/.AdbIME"
    
    suspend fun typeText(text: String, delayMs: Long = 500) {
        val originalIme = detectCurrentIme()
        
        setAdbKeyboard()
        delay(200)
        
        clearText()
        delay(100)
        
        sendText(text)
        delay(delayMs)
        
        if (originalIme != null && originalIme != ADB_KEYBOARD_IME) {
            restoreIme(originalIme)
        }
    }
    
    suspend fun typeTextDirect(text: String, delayMs: Long = 500) {
        val escapedText = text.replace(" ", "%s")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
        ShizukuExecutor.execute("input text '$escapedText'")
        delay(delayMs)
    }
    
    private suspend fun detectCurrentIme(): String? {
        val result = ShizukuExecutor.execute("settings get secure default_input_method")
        return if (result.success && result.output.isNotBlank()) {
            result.output.trim()
        } else {
            null
        }
    }
    
    private suspend fun setAdbKeyboard() {
        ShizukuExecutor.execute("ime enable $ADB_KEYBOARD_IME")
        ShizukuExecutor.execute("ime set $ADB_KEYBOARD_IME")
    }
    
    private suspend fun restoreIme(imeName: String) {
        ShizukuExecutor.execute("ime set $imeName")
    }
    
    private suspend fun clearText() {
        ShizukuExecutor.execute(
            "am broadcast -a ADB_CLEAR_TEXT --user 0"
        )
    }
    
    private suspend fun sendText(text: String) {
        val base64Text = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        ShizukuExecutor.execute(
            "am broadcast -a ADB_INPUT_B64 --es msg '$base64Text' --user 0"
        )
    }
    
    suspend fun sendKeyEvent(keyCode: Int) {
        ShizukuExecutor.execute("input keyevent $keyCode")
    }
    
    suspend fun sendEnter() {
        sendKeyEvent(66)
    }
    
    suspend fun sendBackspace() {
        sendKeyEvent(67)
    }
}
