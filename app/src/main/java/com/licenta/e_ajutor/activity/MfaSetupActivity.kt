package com.licenta.e_ajutor.activity

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.FirebaseException
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.MultiFactorAssertion
import com.google.firebase.auth.MultiFactorSession
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.auth.PhoneMultiFactorGenerator
import com.google.firebase.auth.PhoneMultiFactorInfo
import com.licenta.e_ajutor.databinding.ActivityMfaSetupBinding
import java.util.concurrent.TimeUnit

class MfaSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMfaSetupBinding
    private lateinit var firebaseAuth: FirebaseAuth

    private var mfaEnrollmentSession: MultiFactorSession? = null
    private var verificationId: String? = null
    private var resendingToken: PhoneAuthProvider.ForceResendingToken? = null

    // Store the phone number to retry verification if needed after re-authentication
    private var currentPhoneNumberForEnrollment: String? = null
    private var currentSmsCodeForEnrollment: String? = null


    companion object {
        private const val TAG = "MfaSetupActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMfaSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        firebaseAuth = FirebaseAuth.getInstance()
        checkCurrentUserAndSession() // Renamed for clarity

        binding.btnSendVerificationCode.setOnClickListener {
            val phoneNumber = binding.etPhoneNumber.text.toString().trim()
            if (phoneNumber.isNotEmpty()) {
                currentPhoneNumberForEnrollment = phoneNumber // Store for potential retry
                startPhoneNumberVerificationForEnrollment(phoneNumber)
            } else {
                Toast.makeText(this, "Please enter a phone number.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnVerifyAndEnroll.setOnClickListener {
            val smsCode = binding.etSmsCode.text.toString().trim()
            if (verificationId != null && smsCode.isNotEmpty()) {
                currentSmsCodeForEnrollment = smsCode // Store for potential retry
                verifySmsCodeAndEnroll(smsCode)
            } else {
                Toast.makeText(this, "Please enter the verification code.", Toast.LENGTH_SHORT).show()
            }
        }
        displayMfaStatus()
    }

    private fun checkCurrentUserAndSession() {
        val currentUser = firebaseAuth.currentUser
        if (currentUser == null) {
            Toast.makeText(this, "User not logged in.", Toast.LENGTH_LONG).show()
            // Consider navigating to LoginActivity instead of just finishing
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        // Get the session for starting enrollment
        currentUser.multiFactor.session
            .addOnSuccessListener { session ->
                this.mfaEnrollmentSession = session
                binding.btnSendVerificationCode.isEnabled = true
                Log.d(TAG, "MultiFactorSession obtained for enrollment.")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error getting MultiFactorSession: ${e.message}", e)
                if (e is FirebaseAuthRecentLoginRequiredException) {
                    binding.tvMfaStatus.text = "Session requires recent login. Please re-authenticate."
                    handleRecentLoginRequired(currentUser) {
                        checkCurrentUserAndSession() // Retry getting session after re-auth
                    }
                } else {
                    binding.tvMfaStatus.text = "Error preparing for MFA setup: ${e.message}"
                }
                binding.btnSendVerificationCode.isEnabled = false
            }
    }

    private fun startPhoneNumberVerificationForEnrollment(phoneNumber: String) {
        val currentUser = firebaseAuth.currentUser
        if (currentUser == null) {
            Toast.makeText(this, "User not logged in. Please log in again.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (mfaEnrollmentSession == null) {
            Toast.makeText(this, "MFA Session not ready. Trying to refresh...", Toast.LENGTH_SHORT).show()
            checkCurrentUserAndSession() // Attempt to re-fetch session
            return
        }

        binding.btnSendVerificationCode.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE

        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                Log.d(TAG, "Enrollment: onVerificationCompleted (auto-retrieval or instant).")
                binding.progressBar.visibility = View.GONE
                binding.etSmsCode.setText(credential.smsCode)
                val assertion = PhoneMultiFactorGenerator.getAssertion(credential)
                enrollMfa(currentUser, assertion) // Pass currentUser
            }

            override fun onVerificationFailed(e: FirebaseException) {
                Log.e(TAG, "Enrollment: onVerificationFailed: ${e.message}", e)
                binding.progressBar.visibility = View.GONE
                binding.btnSendVerificationCode.isEnabled = true
                binding.tvMfaStatus.text = "Verification failed: ${e.message}"
                if (e is FirebaseAuthRecentLoginRequiredException) {
                    Toast.makeText(this@MfaSetupActivity, "Verification requires recent login. Please re-authenticate.", Toast.LENGTH_LONG).show()
                    handleRecentLoginRequired(currentUser) {
                        // Retry starting phone number verification
                        currentPhoneNumberForEnrollment?.let { storedPhoneNumber ->
                            startPhoneNumberVerificationForEnrollment(storedPhoneNumber)
                        }
                    }
                } else {
                    Toast.makeText(this@MfaSetupActivity, "Verification failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onCodeSent(
                verificationId: String,
                token: PhoneAuthProvider.ForceResendingToken
            ) {
                Log.d(TAG, "Enrollment: onCodeSent. Verification ID: $verificationId")
                binding.progressBar.visibility = View.GONE
                binding.btnSendVerificationCode.isEnabled = true

                this@MfaSetupActivity.verificationId = verificationId
                this@MfaSetupActivity.resendingToken = token

                binding.layoutVerifyEnroll.visibility = View.VISIBLE
                binding.btnVerifyAndEnroll.isEnabled = true
                Toast.makeText(this@MfaSetupActivity, "Verification code sent.", Toast.LENGTH_SHORT).show()
            }
        }

        val options = PhoneAuthOptions.newBuilder(firebaseAuth)
            .setPhoneNumber(phoneNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(this)
            .setCallbacks(callbacks)
            .setMultiFactorSession(mfaEnrollmentSession!!) // Critical for enrollment
            .build()
        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    private fun verifySmsCodeAndEnroll(smsCode: String) {
        val currentUser = firebaseAuth.currentUser
        if (currentUser == null || verificationId == null) {
            Toast.makeText(this, "Session error or Verification ID missing. Please try again.", Toast.LENGTH_SHORT).show()
            checkCurrentUserAndSession() // Try to refresh session and state
            return
        }
        binding.btnVerifyAndEnroll.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE

        val credential = PhoneAuthProvider.getCredential(verificationId!!, smsCode)
        val assertion = PhoneMultiFactorGenerator.getAssertion(credential)
        enrollMfa(currentUser, assertion)
    }

    private fun enrollMfa(user: FirebaseUser, assertion: MultiFactorAssertion) {
        user.multiFactor.enroll(assertion, "My SMS MFA")
            .addOnSuccessListener {
                Log.d(TAG, "MFA Enrolled successfully!")
                binding.progressBar.visibility = View.GONE
                binding.tvMfaStatus.text = "SMS MFA successfully enabled!"
                binding.layoutVerifyEnroll.visibility = View.GONE
                binding.etPhoneNumber.text.clear()
                binding.etSmsCode.text.clear()
                currentPhoneNumberForEnrollment = null // Clear stored values
                currentSmsCodeForEnrollment = null
                verificationId = null
                Toast.makeText(this@MfaSetupActivity, "MFA Enabled!", Toast.LENGTH_SHORT).show()
                displayMfaStatus()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error enrolling MFA: ${e.message}", e)
                binding.progressBar.visibility = View.GONE
                binding.btnVerifyAndEnroll.isEnabled = true
                binding.tvMfaStatus.text = "MFA enrollment failed: ${e.message}"
                if (e is FirebaseAuthRecentLoginRequiredException) {
                    Toast.makeText(this@MfaSetupActivity, "Enrollment requires recent login. Please re-authenticate.", Toast.LENGTH_LONG).show()
                    handleRecentLoginRequired(user) {
                        // Retry enrollment with the same assertion
                        enrollMfa(user, assertion)
                    }
                } else {
                    Toast.makeText(this@MfaSetupActivity, "Enrollment failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun handleRecentLoginRequired(user: FirebaseUser, onReauthSuccess: () -> Unit) {
        // Simple dialog to prompt re-authentication.
        // In a production app, you might navigate to a dedicated re-auth screen or use a more robust dialog.
        AlertDialog.Builder(this)
            .setTitle("Authentication Required")
            .setMessage("For your security, this action requires you to have signed in recently. Please sign in again to continue.")
            .setPositiveButton("Sign In") { dialog, _ ->
                dialog.dismiss()
                // For this example, we'll use a very basic password prompt.
                // It's highly recommended to navigate to your actual LoginActivity
                // or a dedicated ReAuthenticationActivity for a better UX and security.
                promptForPasswordAndReauthenticate(user, onReauthSuccess)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                binding.progressBar.visibility = View.GONE // Ensure progress bar is hidden
                binding.btnSendVerificationCode.isEnabled = true // Re-enable buttons
                binding.btnVerifyAndEnroll.isEnabled = true
                Toast.makeText(this, "Operation cancelled. Please log in again if needed.", Toast.LENGTH_LONG).show()
            }
            .setCancelable(false)
            .show()
    }

    private fun promptForPasswordAndReauthenticate(user: FirebaseUser, onReauthSuccess: () -> Unit) {
        if (user.email == null) {
            Toast.makeText(this, "Cannot re-authenticate without user email.", Toast.LENGTH_LONG).show()
            // Potentially offer to go to LoginActivity to re-login with any provider
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        val passwordInput = EditText(this).apply {
            hint = "Enter your password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val padding = (16 * resources.displayMetrics.density).toInt() // 16dp padding
        passwordInput.setPadding(padding, padding, padding, padding)


        AlertDialog.Builder(this)
            .setTitle("Re-enter Password")
            .setMessage("To continue, please enter the password for ${user.email}")
            .setView(passwordInput)
            .setPositiveButton("Verify") { dialog, _ ->
                val password = passwordInput.text.toString()
                if (password.isNotEmpty()) {
                    binding.progressBar.visibility = View.VISIBLE // Show progress during re-auth
                    val credential = EmailAuthProvider.getCredential(user.email!!, password)
                    user.reauthenticate(credential)
                        .addOnSuccessListener {
                            binding.progressBar.visibility = View.GONE
                            Log.d(TAG, "Re-authentication successful.")
                            Toast.makeText(this, "Re-authentication successful.", Toast.LENGTH_SHORT).show()
                            onReauthSuccess() // Call the retry callback
                        }
                        .addOnFailureListener { e ->
                            binding.progressBar.visibility = View.GONE
                            Log.w(TAG, "Re-authentication failed.", e)
                            Toast.makeText(this, "Re-authentication failed: ${e.message}", Toast.LENGTH_LONG).show()
                            // Optionally, offer to go to LoginActivity
                        }
                } else {
                    Toast.makeText(this, "Password cannot be empty.", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                binding.progressBar.visibility = View.GONE
                binding.btnSendVerificationCode.isEnabled = true // Re-enable buttons
                binding.btnVerifyAndEnroll.isEnabled = true
            }
            .setCancelable(false)
            .show()
    }


    private fun displayMfaStatus() {
        firebaseAuth.currentUser?.let { user ->
            var mfaFactorsInfo = "Enrolled MFA factors:\n"
            if (user.multiFactor.enrolledFactors.isEmpty()) {
                mfaFactorsInfo += "None"
            } else {
                user.multiFactor.enrolledFactors.forEach { factor ->
                    mfaFactorsInfo += "- ${factor.displayName ?: "Unknown Factor"} (ID: ${factor.uid.take(6)}...)" // Using uid as factorId is deprecated
                    if (factor is PhoneMultiFactorInfo) {
                        mfaFactorsInfo += ", Phone: ${factor.phoneNumber}"
                    }
                    mfaFactorsInfo += ")\n"
                }
            }
            binding.tvCurrentMfaInfo.text = mfaFactorsInfo // Ensure this TextView exists
        }
    }

    override fun onResume() {
        super.onResume()
        // It's good practice to re-check the user and session state onResume,
        // especially if the user might have logged out or re-authenticated in another part of the app.
        checkCurrentUserAndSession()
        displayMfaStatus()
    }
}