package org.weproz.etab.util

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri

/**
 * Manages Focus Mode functionality including:
 * - Screen pinning (kiosk mode) to prevent app switching
 * - WiFi control during focus sessions
 * - Overlay permission handling
 */
class FocusModeManager(private val context: Context) {

    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val prefs = context.getSharedPreferences("focus_mode_prefs", Context.MODE_PRIVATE)

    var isFocusModeActive = false
        private set

    // Callbacks for different scenarios
    var onFocusModeEnabled: (() -> Unit)? = null
    var onFocusModeDisabled: (() -> Unit)? = null
    var onPermissionRequired: ((Activity) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    init {
        // Restore state from preferences
        isFocusModeActive = prefs.getBoolean(KEY_FOCUS_MODE_ACTIVE, false)
    }

    /**
     * Check if app is currently screen pinned
     */
    fun isAppPinned(activity: Activity?): Boolean {
        return try {
            if (activity == null) return false
            val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val state = am.lockTaskModeState
            state == ActivityManager.LOCK_TASK_MODE_PINNED || state == ActivityManager.LOCK_TASK_MODE_LOCKED
        } catch (e: Exception) {
            Log.e(TAG, "Error checking pinned state: ${e.message}")
            false
        }
    }

    /**
     * Check if overlay permission is granted (required for focus mode)
     */
    fun isOverlayPermissionGranted(): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * Check and prepare for focus mode - requests permission if needed
     * @return true if ready to enable, false if permission is needed
     */
    fun checkAndPrepareFocusMode(activity: Activity): Boolean {
        return try {
            if (!Settings.canDrawOverlays(context)) {
                onPermissionRequired?.invoke(activity)
                false
            } else {
                true
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied: ${e.message}")
            onError?.invoke("Permission denied")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking focus mode: ${e.message}")
            onError?.invoke("Error: ${e.message}")
            false
        }
    }

    /**
     * Enable focus mode - pins the app and optionally disables WiFi
     */
    fun enableFocusMode(activity: Activity, disableWifi: Boolean = true): Boolean {
        return try {
            if (!Settings.canDrawOverlays(context)) {
                Log.e(TAG, "Cannot enable focus mode: Overlay permission not granted")
                onError?.invoke("Overlay permission not granted")
                return false
            }

            if (isAlreadyPinned(activity)) {
                isFocusModeActive = true
                saveState()
                onFocusModeEnabled?.invoke()
                return true
            }

            // Disable WiFi if requested
            if (disableWifi) {
                disableWifi()
            }

            // Start screen pinning
            startScreenPinning(activity)

            isFocusModeActive = true
            saveState()
            onFocusModeEnabled?.invoke()
            Log.d(TAG, "Focus mode enabled")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied: ${e.message}")
            onError?.invoke("Permission denied: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling focus mode: ${e.message}")
            onError?.invoke("Error: ${e.message}")
            false
        }
    }

    /**
     * Disable focus mode - unpins the app and re-enables WiFi
     */
    fun disableFocusMode(activity: Activity?) {
        try {
            // Stop screen pinning
            stopScreenPinning(activity)

            // Re-enable WiFi
            enableWifi()

            isFocusModeActive = false
            saveState()
            onFocusModeDisabled?.invoke()
            Log.d(TAG, "Focus mode disabled")
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling focus mode: ${e.message}")
            onError?.invoke("Error disabling: ${e.message}")
        }
    }

    /**
     * Toggle focus mode on/off
     */
    fun toggleFocusMode(activity: Activity): Boolean {
        return if (isFocusModeActive) {
            disableFocusMode(activity)
            false
        } else {
            if (checkAndPrepareFocusMode(activity)) {
                enableFocusMode(activity)
            } else {
                false
            }
        }
    }

    /**
     * Request overlay permission from user
     */
    fun requestOverlayPermission(activity: Activity) {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = "package:${context.packageName}".toUri()
            }
            activity.startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting overlay permission: ${e.message}")
            onError?.invoke("Cannot open settings")
        }
    }

    /**
     * Call this when returning from permission request
     */
    fun onPermissionResult(activity: Activity) {
        if (Settings.canDrawOverlays(context)) {
            try {
                enableFocusMode(activity)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enable after permission: ${e.message}")
                onError?.invoke("Failed to enable focus mode")
            }
        }
    }

    /**
     * Ensure WiFi is enabled (call after unpinning)
     */
    fun ensureWifiEnabled() {
        try {
            enableWifi()
        } catch (e: Exception) {
            Log.e(TAG, "Error ensuring WiFi enabled: ${e.message}")
        }
    }

    /**
     * Auto-enable focus mode on app start if it was active before
     */
    fun autoEnableIfNeeded(activity: Activity) {
        if (isAlreadyPinned(activity)) {
            isFocusModeActive = true
            saveState()
            return
        }

        // If focus mode was active but app was killed, try to restore
        if (prefs.getBoolean(KEY_FOCUS_MODE_ACTIVE, false)) {
            try {
                if (Settings.canDrawOverlays(context)) {
                    enableFocusMode(activity, disableWifi = false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error auto-enabling focus mode: ${e.message}")
                // Reset the state since we couldn't restore
                isFocusModeActive = false
                saveState()
            }
        }
    }

    private fun startScreenPinning(activity: Activity) {
        try {
            activity.startLockTask()
            Log.d(TAG, "Screen pinning started")
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot start screen pinning: ${e.message}")
            throw e
        }
    }

    private fun stopScreenPinning(activity: Activity?) {
        try {
            activity?.stopLockTask()
            Log.d(TAG, "Screen pinning stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Cannot stop lock task: ${e.message}")
        }
    }

    private fun isAlreadyPinned(activity: Activity): Boolean {
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return am?.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_PINNED ||
                am?.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_LOCKED
    }

    @Suppress("DEPRECATION")
    private fun disableWifi() {
        try {
            wifiManager?.isWifiEnabled = false
            Log.d(TAG, "WiFi disabled")
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling WiFi: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun enableWifi() {
        try {
            if (wifiManager?.isWifiEnabled == false) {
                wifiManager.isWifiEnabled = true
                Log.d(TAG, "WiFi enabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling WiFi: ${e.message}")
        }
    }

    private fun saveState() {
        prefs.edit().putBoolean(KEY_FOCUS_MODE_ACTIVE, isFocusModeActive).apply()
    }

    companion object {
        private const val TAG = "FocusModeManager"
        private const val KEY_FOCUS_MODE_ACTIVE = "focus_mode_active"
        const val OVERLAY_PERMISSION_REQUEST_CODE = 1001
    }
}

