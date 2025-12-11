package org.weproz.etab

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import dagger.hilt.android.AndroidEntryPoint
import org.weproz.etab.databinding.ActivityMainBinding
import org.weproz.etab.util.FocusModeManager

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var focusModeManager: FocusModeManager

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
    }

    private fun setupFocusMode() {
        focusModeManager = FocusModeManager(this)

        // Set up callbacks
        focusModeManager.onFocusModeEnabled = {
            runOnUiThread {
                updateFocusModeUI(true)
                Toast.makeText(this, "Focus Mode enabled", Toast.LENGTH_SHORT).show()
            }
        }

        focusModeManager.onFocusModeDisabled = {
            runOnUiThread {
                updateFocusModeUI(false)
                Toast.makeText(this, "Focus Mode disabled", Toast.LENGTH_SHORT).show()
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
        AlertDialog.Builder(this)
            .setTitle("Enable Focus Mode")
            .setMessage("Focus Mode will:\n\n• Pin the app to prevent switching\n• Optionally disable WiFi\n\nThis helps you stay focused while studying.\n\nTo exit, hold Back and Recent buttons together.")
            .setPositiveButton("Enable") { _, _ ->
                if (focusModeManager.checkAndPrepareFocusMode(this)) {
                    focusModeManager.enableFocusMode(this)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDisableFocusModeDialog() {
        AlertDialog.Builder(this)
            .setTitle("Disable Focus Mode")
            .setMessage("Are you sure you want to exit Focus Mode?")
            .setPositiveButton("Disable") { _, _ ->
                focusModeManager.disableFocusMode(this)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage("Focus Mode requires 'Display over other apps' permission to work properly.\n\nWould you like to grant this permission?")
            .setPositiveButton("Open Settings") { _, _ ->
                focusModeManager.requestOverlayPermission(this)
            }
            .setNegativeButton("Cancel", null)
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
        // Sync UI with actual state
        updateFocusModeUI(focusModeManager.isFocusModeActive || focusModeManager.isAppPinned(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        // Ensure WiFi is re-enabled if app is destroyed while in focus mode
        if (focusModeManager.isFocusModeActive) {
            focusModeManager.ensureWifiEnabled()
        }
    }
}