package org.weproz.etab

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import dagger.hilt.android.AndroidEntryPoint
import org.weproz.etab.databinding.ActivityMainBinding
import org.weproz.etab.util.FocusModeManager

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var focusModeManager: FocusModeManager
    
    private val isPinned = androidx.lifecycle.MutableLiveData<Boolean>()
    private var monitorJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navView: BottomNavigationView = binding.navView

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        navView.setupWithNavController(navController)

        // Update header title based on navigation destination
        navController.addOnDestinationChangedListener { _, destination, _ ->
            binding.textHeaderTitle.text = when (destination.id) {
                R.id.navigation_books -> "Books"
                R.id.navigation_notes -> "Notes"
                R.id.navigation_dictionary -> "Dictionary"
                else -> "ETab"
            }
        }

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupFocusMode()
        
        isPinned.observe(this) { pinned ->
            if (::focusModeManager.isInitialized && focusModeManager.isFocusModeActive && !pinned) {
                focusModeManager.disableFocusMode(this)
            }
        }
    }

    private fun startMonitoringPinnedState() {
        monitorJob?.cancel()
        monitorJob = lifecycleScope.launch {
            // Wait for pinning to take effect
            val startTime = System.currentTimeMillis()
            while (isActive && ::focusModeManager.isInitialized && focusModeManager.isFocusModeActive) {
                if (focusModeManager.isAppPinned(this@MainActivity)) {
                    break
                }
                if (System.currentTimeMillis() - startTime > 5000) {
                    focusModeManager.disableFocusMode(this@MainActivity)
                    return@launch
                }
                delay(100)
            }

            while (isActive && ::focusModeManager.isInitialized && focusModeManager.isFocusModeActive) {
                isPinned.postValue(focusModeManager.isAppPinned(this@MainActivity))
                delay(500)
            }
        }
    }

    private fun stopMonitoringPinnedState() {
        monitorJob?.cancel()
    }

    private fun setupFocusMode() {
        focusModeManager = FocusModeManager(this)

        // Set up callbacks
        focusModeManager.onFocusModeEnabled = {
            runOnUiThread {
                updateFocusModeUI(true)
                Toast.makeText(this, "Focus Mode enabled", Toast.LENGTH_SHORT).show()
                startMonitoringPinnedState()
            }
        }

        focusModeManager.onFocusModeDisabled = {
            runOnUiThread {
                updateFocusModeUI(false)
                Toast.makeText(this, "Focus Mode disabled", Toast.LENGTH_SHORT).show()
                stopMonitoringPinnedState()
            }
        }

        focusModeManager.onPermissionRequired = { activity ->
            showPermissionDialog()
        }

        focusModeManager.onError = { message ->
            runOnUiThread {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }

        // Update initial UI state
        updateFocusModeUI(focusModeManager.isFocusModeActive)
        if (focusModeManager.isFocusModeActive) {
            startMonitoringPinnedState()
        }

        // Set up button click listener
        binding.btnFocusMode.setOnClickListener {
            if (focusModeManager.isFocusModeActive) {
                showDisableFocusModeDialog()
            } else {
                showEnableFocusModeDialog()
            }
        }

        // Auto-enable if needed (restores state after app restart)
        focusModeManager.autoEnableIfNeeded(this)
    }

    private fun updateFocusModeUI(isActive: Boolean) {
        if (isActive) {
            binding.btnFocusMode.text = "Exit"
            binding.btnFocusMode.setIconResource(R.drawable.ic_focus_mode_active)
            binding.btnFocusMode.setBackgroundColor(resources.getColor(android.R.color.holo_red_light, theme))
        } else {
            binding.btnFocusMode.text = "Focus"
            binding.btnFocusMode.setIconResource(R.drawable.ic_focus_mode)
            binding.btnFocusMode.setBackgroundColor(resources.getColor(android.R.color.holo_green_dark, theme))
        }
    }

    private fun showEnableFocusModeDialog() {
        org.weproz.etab.ui.custom.CustomDialog(this)
            .setTitle("Enable Focus Mode")
            .setMessage("Focus Mode will:\n\n• Pin the app to prevent switching\n• Optionally disable WiFi\n\nThis helps you stay focused while studying.\n\nTo exit, hold Back and Recent buttons together.")
            .setPositiveButton("Enable") { dialog ->
                if (focusModeManager.checkAndPrepareFocusMode(this)) {
                    focusModeManager.enableFocusMode(this)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel")
            .show()
    }

    private fun showDisableFocusModeDialog() {
        org.weproz.etab.ui.custom.CustomDialog(this)
            .setTitle("Disable Focus Mode")
            .setMessage("Are you sure you want to exit Focus Mode?")
            .setPositiveButton("Disable") { dialog ->
                focusModeManager.disableFocusMode(this)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel")
            .show()
    }

    private fun showPermissionDialog() {
        org.weproz.etab.ui.custom.CustomDialog(this)
            .setTitle("Permission Required")
            .setMessage("Focus Mode requires 'Display over other apps' permission to work properly.\n\nWould you like to grant this permission?")
            .setPositiveButton("Open Settings") { dialog ->
                focusModeManager.requestOverlayPermission(this)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel")
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FocusModeManager.OVERLAY_PERMISSION_REQUEST_CODE) {
            focusModeManager.onPermissionResult(this)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::focusModeManager.isInitialized) {
            updateFocusModeUI(focusModeManager.isFocusModeActive)
        }
    }

    override fun onDestroy() {
        stopMonitoringPinnedState()
        super.onDestroy()
        // Ensure WiFi is re-enabled if app is destroyed while in focus mode
        if (focusModeManager.isFocusModeActive) {
            focusModeManager.ensureWifiEnabled()
        }
    }
}