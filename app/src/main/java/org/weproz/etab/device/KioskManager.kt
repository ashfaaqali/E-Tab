package org.weproz.etab.device

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.UserManager
import android.provider.Settings
import android.view.View
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.weproz.etab.MainActivity

class KioskManager(private val context: Context) {

    private val devicePolicyManager: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponentName: ComponentName =
        ComponentName(context, ETabDeviceAdminReceiver::class.java)

    fun enableKioskMode(activity: Activity) {
        if (devicePolicyManager.isDeviceOwnerApp(context.packageName)) {
            setRestrictions()
            grantPermissions()
            enableLockTaskMode(activity)
            setAsHomeApp()
            disableSystemUI(activity)
        }
    }

    private fun grantPermissions() {
        val permissions = arrayOf(
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_ADVERTISE,
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.MANAGE_EXTERNAL_STORAGE
        )

        for (permission in permissions) {
            devicePolicyManager.setPermissionGrantState(
                adminComponentName,
                context.packageName,
                permission,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
        }
    }

    private fun setRestrictions() {
        val restrictions = arrayOf(
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
            UserManager.DISALLOW_ADJUST_VOLUME,
            UserManager.DISALLOW_CONFIG_WIFI,
            UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS,
            UserManager.DISALLOW_CONFIG_TETHERING,
            UserManager.DISALLOW_SMS,
            UserManager.DISALLOW_FUN,
            UserManager.DISALLOW_INSTALL_APPS,
            UserManager.DISALLOW_UNINSTALL_APPS,
            UserManager.DISALLOW_USB_FILE_TRANSFER,
            UserManager.DISALLOW_OUTGOING_CALLS,
             // We allow using bluetooth, but maybe not configuring it? User said "bluetooth functionality". Usually this means allowing the radio.
            // If we disallow config bluetooth, they might not be able to pair.
            // Let's keep bluetooth config allowed for now as "bluetooth functionality" implies pairing.
        )

        for (restriction in restrictions) {
            devicePolicyManager.addUserRestriction(adminComponentName, restriction)
        }
        
        // Explicitly allow Bluetooth config if we want them to pair devices
        devicePolicyManager.clearUserRestriction(adminComponentName, UserManager.DISALLOW_CONFIG_BLUETOOTH)
        devicePolicyManager.clearUserRestriction(adminComponentName, UserManager.DISALLOW_BLUETOOTH)
        // Ensure Bluetooth sharing is allowed
        devicePolicyManager.clearUserRestriction(adminComponentName, UserManager.DISALLOW_BLUETOOTH_SHARING)

        // Disable WiFi and Mobile Data
        // Note: setGlobalSetting is deprecated in some versions but still the way to go for DPM
        try {
            devicePolicyManager.setGlobalSetting(adminComponentName, Settings.Global.WIFI_ON, "0")
            devicePolicyManager.setGlobalSetting(adminComponentName, "mobile_data", "0")
            devicePolicyManager.setGlobalSetting(adminComponentName, Settings.Global.AIRPLANE_MODE_ON, "0")
            // Automatically enable Bluetooth
            devicePolicyManager.setGlobalSetting(adminComponentName, Settings.Global.BLUETOOTH_ON, "1")
        } catch (e: SecurityException) {
            // Handle exception
        }

        // Keep screen on
        // devicePolicyManager.setGlobalSetting(adminComponentName, Settings.System.STAY_ON_WHILE_PLUGGED_IN, (BatteryManager.BATTERY_PLUGGED_AC or BatteryManager.BATTERY_PLUGGED_USB or BatteryManager.BATTERY_PLUGGED_WIRELESS).toString())
    }

    private fun enableLockTaskMode(activity: Activity) {
        // Whitelist our app and Bluetooth so sharing UI can appear
        val bluetoothPackage = getBluetoothPackageName()
        val packages = mutableListOf(
            context.packageName,
            "com.android.bluetooth" // Default Bluetooth package
        )

        if (bluetoothPackage != null && bluetoothPackage != "com.android.bluetooth") {
            packages.add(bluetoothPackage)
        }

        devicePolicyManager.setLockTaskPackages(adminComponentName, packages.toTypedArray())

        // REQUIRED: Enable Notifications so the "Accept File" prompt is visible.
        // REMOVED: LOCK_TASK_FEATURE_HOME to prevent escaping via Home button.
        try {
            devicePolicyManager.setLockTaskFeatures(
                adminComponentName,
                DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or
                DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
            )
        } catch (e: SecurityException) {
            // Handle exception
        }

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        if (activityManager.lockTaskModeState == android.app.ActivityManager.LOCK_TASK_MODE_NONE) {
            try {
                activity.startLockTask()
            } catch (e: Exception) {
                // Ignore "Invalid task, not in foreground" error
                e.printStackTrace()
            }
        }
    }

    private fun getBluetoothPackageName(): String? {
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "*/*"
        val list = context.packageManager.queryIntentActivities(intent, 0)
        for (info in list) {
            if (info.activityInfo.packageName.contains("bluetooth", ignoreCase = true)) {
                return info.activityInfo.packageName
            }
        }
        return null
    }

    private fun setAsHomeApp() {
        val intentFilter = IntentFilter(Intent.ACTION_MAIN)
        intentFilter.addCategory(Intent.CATEGORY_HOME)
        intentFilter.addCategory(Intent.CATEGORY_DEFAULT)
        
        devicePolicyManager.addPersistentPreferredActivity(
            adminComponentName,
            intentFilter,
            ComponentName(context, MainActivity::class.java)
        )
    }

    private fun disableSystemUI(activity: Activity) {
        val window = activity.window
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // Only hide navigation bars (Back/Home/Recents).
        // We MUST keep the Status Bar visible so the user can see the "Incoming File" notification.
        windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
    }
}
