package com.licenta.e_ajutor.activity // Assuming your package name

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.Patterns
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.licenta.e_ajutor.R
import com.licenta.e_ajutor.databinding.RegisterPageBinding

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: RegisterPageBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private val tag = "RegisterActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = RegisterPageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.statusBarColor = ContextCompat.getColor(this, com.google.android.libraries.places.R.color.gmp_ref_palette_neutral_transparent)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true

        auth = Firebase.auth
        db = Firebase.firestore

        setupLivePasswordValidation()

        binding.buttonRegister.setOnClickListener {
            registerUser()
        }

        binding.textViewGoToLogin.setOnClickListener {
            Log.d(tag, "Go to LoginActivity clicked")
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun setupLivePasswordValidation() {
        val passwordEditText = binding.editTextPasswordRegister
        val layout = binding.passwordRequirementsLayout
        val lengthText = binding.passwordLengthRequirement
        val uppercaseText = binding.passwordUppercaseRequirement
        val digitText = binding.passwordDigitRequirement
        val specialText = binding.passwordSpecialCharRequirement

        passwordEditText.setOnFocusChangeListener { _, hasFocus ->
            layout.visibility = if (hasFocus) View.VISIBLE else View.GONE
        }

        passwordEditText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val password = s.toString()
                // Assuming you have R.color.green and R.color.dark_gray defined in your colors.xml
                lengthText.setTextColor(
                    if (password.length >= 6) getColor(R.color.green) else getColor(R.color.dark_gray)
                )
                uppercaseText.setTextColor(
                    if (password.any { it.isUpperCase() }) getColor(R.color.green) else getColor(R.color.dark_gray)
                )
                digitText.setTextColor(
                    if (password.any { it.isDigit() }) getColor(R.color.green) else getColor(R.color.dark_gray)
                )
                specialText.setTextColor(
                    if (password.any { !it.isLetterOrDigit() }) getColor(R.color.green) else getColor(
                        R.color.dark_gray
                    )
                )
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
        return when {
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            else -> false
        }
    }

    private fun validateInputs(
        firstName: String,
        lastName: String,
        phone: String,
        email: String,
        password: String
    ): Boolean {
        if (firstName.isEmpty()) {
            binding.editTextFirstName.error =
                getString(R.string.first_name_required) // Use string resources
            binding.editTextFirstName.requestFocus()
            return false
        }
        if (lastName.isEmpty()) {
            binding.editTextLastName.error =
                getString(R.string.last_name_required) // Use string resources
            binding.editTextLastName.requestFocus()
            return false
        }
        if (phone.isEmpty()) { // Add more robust phone validation if needed
            binding.editTextPhone.error =
                getString(R.string.phone_number_required) // Use string resources
            binding.editTextPhone.requestFocus()
            return false
        }
        if (email.isEmpty()) {
            binding.editTextEmailRegister.error =
                getString(R.string.email_required) // Use string resources
            binding.editTextEmailRegister.requestFocus()
            return false
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.editTextEmailRegister.error =
                getString(R.string.invalid_email_address) // Use string resources
            binding.editTextEmailRegister.requestFocus()
            return false
        }
        if (password.isEmpty()) {
            binding.editTextPasswordRegister.error =
                getString(R.string.password_required) // Use string resources
            binding.editTextPasswordRegister.requestFocus()
            return false
        }
        if (password.length < 6) {
            binding.editTextPasswordRegister.error =
                getString(R.string.password_too_short) // Use string resources
            binding.editTextPasswordRegister.requestFocus()
            return false
        }
        if (!password.any { it.isUpperCase() }) {
            binding.editTextPasswordRegister.error =
                getString(R.string.password_requires_uppercase) // Use string resources
            binding.editTextPasswordRegister.requestFocus()
            return false
        }
        if (!password.any { it.isDigit() }) {
            binding.editTextPasswordRegister.error =
                getString(R.string.password_requires_digit) // Use string resources
            binding.editTextPasswordRegister.requestFocus()
            return false
        }
        if (!password.any { !it.isLetterOrDigit() }) {
            binding.editTextPasswordRegister.error =
                getString(R.string.password_requires_special) // Use string resources
            binding.editTextPasswordRegister.requestFocus()
            return false
        }
        return true
    }

    private fun registerUser() {
        val firstName = binding.editTextFirstName.text.toString().trim()
        val lastName = binding.editTextLastName.text.toString().trim()
        val phone = binding.editTextPhone.text.toString().trim()
        val email = binding.editTextEmailRegister.text.toString().trim()
        val password = binding.editTextPasswordRegister.text.toString().trim()

        if (!isNetworkAvailable()) {
            Toast.makeText(this, getString(R.string.no_internet_connection), Toast.LENGTH_LONG)
                .show()
            return
        }

        if (!validateInputs(firstName, lastName, phone, email, password)) {
            return
        }

        binding.progressBarRegister.visibility = View.VISIBLE
        binding.buttonRegister.isEnabled = false
        binding.textViewGoToLogin.isEnabled = false

        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { creationTask ->
                if (creationTask.isSuccessful) {
                    Log.d(tag, "createUserWithEmail:success")
                    val firebaseUser: FirebaseUser? = auth.currentUser
                    if (firebaseUser == null) {
                        Log.e(tag, "User creation successful but FirebaseUser is null.")
                        Toast.makeText(
                            baseContext,
                            getString(R.string.registration_error_try_again),
                            Toast.LENGTH_LONG
                        ).show() // Use string resource
                        binding.progressBarRegister.visibility = View.GONE
                        binding.buttonRegister.isEnabled = true
                        binding.textViewGoToLogin.isEnabled = true
                        return@addOnCompleteListener
                    }

                    firebaseUser.sendEmailVerification()
                        .addOnCompleteListener { verificationTask ->
                            if (verificationTask.isSuccessful) {
                                Log.d(tag, "Verification email sent to ${firebaseUser.email}")
                            } else {
                                Log.w(
                                    tag,
                                    "Failed to send verification email.",
                                    verificationTask.exception
                                )
                                // User can request resend from LoginActivity or a profile screen
                            }
                            // Proceed to save user details with default role
                            saveUserDetailsAndProceedToLogin(
                                firebaseUser.uid,
                                firstName,
                                lastName,
                                phone,
                                email
                            )
                        }
                } else {
                    Log.w(tag, "createUserWithEmail:failure", creationTask.exception)
                    Toast.makeText(
                        baseContext,
                        "Înregistrarea a eșuat: ${creationTask.exception?.message}",
                        Toast.LENGTH_LONG
                    ).show() // Use string resource
                    binding.progressBarRegister.visibility = View.GONE
                    binding.buttonRegister.isEnabled = true
                    binding.textViewGoToLogin.isEnabled = true
                }
            }
    }

    private fun saveUserDetailsAndProceedToLogin(
        userId: String,
        firstName: String,
        lastName: String,
        phone: String,
        email: String
    ) {
        val user = hashMapOf(
            "firstName" to firstName,
            "lastName" to lastName,
            "phone" to phone,
            "email" to email,
            "role" to "user" // MODIFIED: Added default role
            // You can add other default fields here, like "createdAt" timestamp
            // "createdAt" to com.google.firebase.Timestamp.now()
        )

        db.collection("users").document(userId)
            .set(user)
            .addOnSuccessListener {
                Log.d(
                    tag,
                    "DocumentSnapshot successfully written for user $userId with role 'user'!"
                )
                Toast.makeText(
                    baseContext,
                    getString(R.string.registration_successful_verify_email),
                    Toast.LENGTH_LONG
                ).show() // Use string resource
                proceedToLogin()
            }
            .addOnFailureListener { e ->
                Log.w(tag, "Error writing document for user $userId", e)
                Toast.makeText(
                    baseContext,
                    getString(R.string.registration_succeeded_details_failed),
                    Toast.LENGTH_LONG
                ).show() // Use string resource
                proceedToLogin() // Still proceed to login
            }
    }

    private fun proceedToLogin() {
        auth.signOut()
        Log.d(tag, "User signed out. Proceeding to LoginActivity.")

        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}