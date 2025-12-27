package org.weproz.etab

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import dagger.hilt.android.AndroidEntryPoint
import org.weproz.etab.databinding.ActivityMainBinding
import org.weproz.etab.device.KioskManager

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var kioskManager: KioskManager

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

        // Initialize and enable Kiosk Mode
        kioskManager = KioskManager(this)
        kioskManager.enableKioskMode(this)
        
        checkAndRequestBluetoothPermission()
    }

    private fun checkAndRequestBluetoothPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_ADVERTISE), 100)
            } else {
                makeDeviceDiscoverable()
            }
        } else {
            makeDeviceDiscoverable()
        }
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            makeDeviceDiscoverable()
        }
    }
    
    private fun makeDeviceDiscoverable() {
        val discoverableIntent = android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            // 0 means "always discoverable" on Android versions that support it.
            // If capped, it will default to 120 or 300 seconds.
            // However, since we are in Kiosk mode, we can re-request it periodically if needed.
            putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 0) 
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(discoverableIntent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Could not request discoverability: ${e.message}")
            android.widget.Toast.makeText(this, "Could not request discoverability", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onBackPressed() {
        // Do nothing to prevent exiting
    }
}