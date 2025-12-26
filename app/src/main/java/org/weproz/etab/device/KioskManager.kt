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
            enableLockTaskMode(activity)
            setAsHomeApp()
            disableSystemUI(activity)
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
            "com.android.bluetooth" // Default fallback
        )

        if (bluetoothPackage != null && bluetoothPackage != "com.android.bluetooth") {
            packages.add(bluetoothPackage)
        }

        devicePolicyManager.setLockTaskPackages(adminComponentName, packages.toTypedArray())

        if (devicePolicyManager.isLockTaskPermitted(context.packageName)) {
            activity.startLockTask()
        } else {
            // Should be permitted now that we set it, but just in case
            activity.startLockTask()
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
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }
}
