package com.licenta.e_ajutor.activity

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import com.licenta.e_ajutor.R
import com.licenta.e_ajutor.databinding.ActivityOperatorDashboardBinding

class OperatorDashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOperatorDashboardBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var navController: NavController
    private val TAG = "OperatorDashboardActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOperatorDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = Firebase.auth

        if (auth.currentUser == null) {
            Log.w(TAG, "User is null in MainActivity, redirecting to LoginActivity.")
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.operator_nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        // Leagă BottomNavigationView de NavController
        binding.operatorNavView.setupWithNavController(navController)

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