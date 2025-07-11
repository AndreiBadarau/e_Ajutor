package com.licenta.e_ajutor.activity

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
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
    private val tag = "OperatorDashboardActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOperatorDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val toolbar: Toolbar = findViewById(R.id.activityToolbar)
        setSupportActionBar(toolbar)

        auth = Firebase.auth

        if (auth.currentUser == null) {
            Log.w(tag, "User is null in MainActivity, redirecting to LoginActivity.")
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = ContextCompat.getColor(this, R.color.colorPrimary)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.operator_nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        binding.operatorNavView.setupWithNavController(navController)

        setSupportActionBar(binding.activityToolbar)
        setupActionBarWithNavController(
            navController,
            AppBarConfiguration(navController.graph)
        )
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }
}