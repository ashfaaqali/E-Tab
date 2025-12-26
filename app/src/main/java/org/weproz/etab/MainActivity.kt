package org.weproz.etab

import android.os.Bundle
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
        
    }
}