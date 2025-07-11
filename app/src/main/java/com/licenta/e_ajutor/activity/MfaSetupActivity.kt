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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
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
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.licenta.e_ajutor.R
import com.licenta.e_ajutor.databinding.ActivityMfaSetupBinding
import java.util.concurrent.TimeUnit

class MfaSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMfaSetupBinding
    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

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
        db = Firebase.firestore
        checkCurrentUserAndSession()

        phoneNumberAutoFill()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val rootView = binding.root

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            view.updatePadding(
                top = insets.top
            )
            windowInsets
        }

        binding.btnSendVerificationCode.setOnClickListener {
            val phoneNumber = binding.etPhoneNumber.text.toString().trim()
            if (phoneNumber.startsWith("+40") && phoneNumber.length == 12) {
                currentPhoneNumberForEnrollment = phoneNumber // Store for potential retry
                startPhoneNumberVerificationForEnrollment(phoneNumber)
            } else {
                Toast.makeText(this, "Vă rugăm să introduceți un număr de telefon valid.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnVerifyAndEnroll.setOnClickListener {
            val smsCode = binding.etSmsCode.text.toString().trim()
            if (verificationId != null && smsCode.isNotEmpty()) {
                currentSmsCodeForEnrollment = smsCode // Store for potential retry
                verifySmsCodeAndEnroll(smsCode)
            } else {
                Toast.makeText(this, "Vă rugăm să introduceți codul de verificare.", Toast.LENGTH_SHORT).show()
            }
        }
        displayMfaStatus()
    }

    private fun phoneNumberAutoFill() {
        val user = firebaseAuth.currentUser

        if (user == null) {
            Log.d(TAG, "No authenticated user found in ProfileFragment.")
            return
        }

        db.collection("users").document(user.uid).get()
            .addOnSuccessListener { document ->
                if (document != null && document.exists()) {
                    Log.d(TAG, "User document found in Firestore.")
                    val phoneNumber = document.getString("phone")
                    binding.etPhoneNumber.setText("+4" + phoneNumber)
                }
                else {
                    Log.w(TAG, "No such user document for uid: ${user.uid}. Defaulting UI.")
                    binding.etPhoneNumber.setText("+40")
                }
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Firestore get failed for user ${user.uid}", exception)
            }
    }

    private fun checkCurrentUserAndSession() {
        val currentUser = firebaseAuth.currentUser
        if (currentUser == null) {
            Toast.makeText(this, "Utilizatorul nu a fost conectat.", Toast.LENGTH_LONG).show()
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
                    binding.tvMfaStatus.text = getString(R.string.session_requires_recent_login)
                    handleRecentLoginRequired(currentUser) {
                        checkCurrentUserAndSession() // Retry getting session after re-auth
                    }
                } else {
                    binding.tvMfaStatus.text =
                        getString(R.string.error_preparing_for_mfa_setup, e.message)
                }
                binding.btnSendVerificationCode.isEnabled = false
            }
    }

    private fun startPhoneNumberVerificationForEnrollment(phoneNumber: String) {
        val currentUser = firebaseAuth.currentUser
        if (currentUser == null) {
            Toast.makeText(this, "Utilizatorul nu a fost conectat. Vă rugăm să vă conectați din nou.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (mfaEnrollmentSession == null) {
            Toast.makeText(this, "Sesiunea MFA nu este pregătită. Încercând să reîmprospătați ...", Toast.LENGTH_SHORT).show()
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
                binding.tvMfaStatus.text = getString(R.string.verification_failed, e.message)
                if (e is FirebaseAuthRecentLoginRequiredException) {
                    Toast.makeText(this@MfaSetupActivity, "Verificarea necesită o autentificare recentă. Vă rugăm să reauțiți.", Toast.LENGTH_LONG).show()
                    handleRecentLoginRequired(currentUser) {
                        // Retry starting phone number verification
                        currentPhoneNumberForEnrollment?.let { storedPhoneNumber ->
                            startPhoneNumberVerificationForEnrollment(storedPhoneNumber)
                        }
                    }
                } else {
                    Toast.makeText(this@MfaSetupActivity, "Verificarea a eșuat: ${e.message}", Toast.LENGTH_LONG).show()
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
                Toast.makeText(this@MfaSetupActivity, "Cod de verificare trimis.", Toast.LENGTH_SHORT).show()
            }
        }

        val options = PhoneAuthOptions.newBuilder(firebaseAuth)
            .setPhoneNumber(phoneNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(this)
            .setCallbacks(callbacks)
            .setMultiFactorSession(mfaEnrollmentSession!!)
            .build()
        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    private fun verifySmsCodeAndEnroll(smsCode: String) {
        val currentUser = firebaseAuth.currentUser
        if (currentUser == null || verificationId == null) {
            Toast.makeText(this, "Eroare de sesiune sau ID de verificare lipsește. Încercați din nou.", Toast.LENGTH_SHORT).show()
            checkCurrentUserAndSession()
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
                binding.tvMfaStatus.text = getString(R.string.sms_mfa_successfully)
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
                binding.tvMfaStatus.text = getString(R.string.mfa_enrollment_failed, e.message)
                if (e is FirebaseAuthRecentLoginRequiredException) {
                    Toast.makeText(this@MfaSetupActivity, "Înscrierea necesită o autentificare recentă. Vă rugăm să reeutentificați.", Toast.LENGTH_LONG).show()
                    handleRecentLoginRequired(user) {
                        // Retry enrollment with the same assertion
                        enrollMfa(user, assertion)
                    }
                } else {
                    Toast.makeText(this@MfaSetupActivity, "Înscrierea a eșuat: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun handleRecentLoginRequired(user: FirebaseUser, onReauthSuccess: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Autentificare necesară")
            .setMessage("Pentru securitatea dvs., această acțiune necesită să fiți autentificat recent. Vă rugăm să vă conectați din nou pentru a continua.")
            .setPositiveButton("Conectare") { dialog, _ ->
                dialog.dismiss()
                promptForPasswordAndReauthenticate(user, onReauthSuccess)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                binding.progressBar.visibility = View.GONE
                binding.btnSendVerificationCode.isEnabled = true
                binding.btnVerifyAndEnroll.isEnabled = true
                Toast.makeText(this, "Operațiunea anulată. Vă rugăm să vă conectați din nou, dacă este necesar.", Toast.LENGTH_LONG).show()
            }
            .setCancelable(false)
            .show()
    }

    private fun promptForPasswordAndReauthenticate(user: FirebaseUser, onReauthSuccess: () -> Unit) {
        if (user.email == null) {
            Toast.makeText(this, "Nu se poate re-autentificarea fără e-mailul utilizatorului.", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        val passwordInput = EditText(this).apply {
            hint = "Introduceți parola"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val padding = (16 * resources.displayMetrics.density).toInt() // 16dp padding
        passwordInput.setPadding(padding, padding, padding, padding)


        AlertDialog.Builder(this)
            .setTitle("Reintroduceți parola")
            .setMessage("Pentru a continua, vă rugăm să introduceți parola pentru ${user.email}")
            .setView(passwordInput)
            .setPositiveButton("Verifica") { dialog, _ ->
                val password = passwordInput.text.toString()
                if (password.isNotEmpty()) {
                    binding.progressBar.visibility = View.VISIBLE // Show progress during re-auth
                    val credential = EmailAuthProvider.getCredential(user.email!!, password)
                    user.reauthenticate(credential)
                        .addOnSuccessListener {
                            binding.progressBar.visibility = View.GONE
                            Log.d(TAG, "Re-authentication successful.")
                            Toast.makeText(this, "Re-autentificarea a fost de succes.", Toast.LENGTH_SHORT).show()
                            onReauthSuccess() // Call the retry callback
                        }
                        .addOnFailureListener { e ->
                            binding.progressBar.visibility = View.GONE
                            Log.w(TAG, "Re-authentication failed.", e)
                            Toast.makeText(this, "Re-autentificarea a eșuat: ${e.message}", Toast.LENGTH_LONG).show()
                            // Optionally, offer to go to LoginActivity
                        }
                } else {
                    Toast.makeText(this, "Parola nu poate fi goală.", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Anulează") { dialog, _ ->
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
            var mfaFactorsInfo = "Factorii MFA înscriși:\n"
            if (user.multiFactor.enrolledFactors.isEmpty()) {
                mfaFactorsInfo += "Nici unul"
            } else {
                user.multiFactor.enrolledFactors.forEach { factor ->
                    mfaFactorsInfo += "- ${factor.displayName ?: "Factor necunoscut"} (ID: ${factor.uid.take(6)}...)"
                    if (factor is PhoneMultiFactorInfo) {
                        mfaFactorsInfo += ", Telefon: ${factor.phoneNumber}"
                    }
                    mfaFactorsInfo += ")\n"
                }
            }
            binding.tvCurrentMfaInfo.text = mfaFactorsInfo
        }
    }

    override fun onResume() {
        super.onResume()
        checkCurrentUserAndSession()
        displayMfaStatus()
    }
}