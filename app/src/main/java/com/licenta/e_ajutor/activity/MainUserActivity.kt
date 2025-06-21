package com.licenta.e_ajutor.activity

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import com.licenta.e_ajutor.R
import com.licenta.e_ajutor.databinding.ActivityMainBinding

class MainUserActivity : AppCompatActivity() {

    internal lateinit var binding: ActivityMainBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var navController: NavController
    private val tag = "MainUserActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val toolbar: Toolbar = findViewById(R.id.activityToolbar)
        setSupportActionBar(toolbar)

        auth = Firebase.auth

        if (auth.currentUser == null) {
            Log.w(tag, "User is null in MainActivity, redirecting to LoginActivity.")
            startActivity(Intent(this, LoginActivity::class.java))
            finish() // Important to finish this activity
            return   // Important to return to prevent further execution
        }

        // Find NavHostFragment and get NavController
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        // Setup BottomNavigationView with NavController
        binding.navView.setupWithNavController(navController)

        // After binding.navView.setupWithNavController(navController)
// Setup ActionBar with NavController
        setSupportActionBar(binding.activityToolbar) // Assuming your Toolbar ID is activityToolbar
        setupActionBarWithNavController(
            navController,
            AppBarConfiguration(navController.graph)
        ) // Default for top-level
// For more granular control over which destinations are top-level (no back arrow):
// val appBarConfiguration = AppBarConfiguration(
//     setOf(R.id.home_destination, R.id.requests_destination, R.id.profile_destination) // Your top-level fragment IDs
// )
// setupActionBarWithNavController(navController, appBarConfiguration)
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }
}