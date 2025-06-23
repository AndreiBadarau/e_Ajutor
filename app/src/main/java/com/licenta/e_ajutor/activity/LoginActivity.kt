package com.licenta.e_ajutor.activity

import BiometricCryptographyManager
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Log
import android.util.Patterns
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthMultiFactorException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GetTokenResult
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.MultiFactorAssertion
import com.google.firebase.auth.MultiFactorResolver
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.auth.PhoneMultiFactorGenerator
import com.google.firebase.auth.PhoneMultiFactorInfo
import com.google.firebase.auth.ktx.auth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.ktx.functions
import com.google.firebase.ktx.Firebase
import com.licenta.e_ajutor.PREFS_NAME
import com.licenta.e_ajutor.PREF_IS_BIOMETRIC_ENABLED_PREFIX
import com.licenta.e_ajutor.PREF_KEY_BIOMETRIC_KEY_ALIAS_PREFIX
import com.licenta.e_ajutor.PREF_LAST_LOGGED_IN_UID
import com.licenta.e_ajutor.PREF_USERDATA_ENCRYPTED_DATA_PREFIX
import com.licenta.e_ajutor.PREF_USERDATA_ENCRYPTED_IV_PREFIX
import com.licenta.e_ajutor.PREF_USERDATA_ENCRYPTED_SYM_KEY_PREFIX
import com.licenta.e_ajutor.R
import com.licenta.e_ajutor.databinding.DialogMfaLoginCodeBinding
import com.licenta.e_ajutor.databinding.LoginPageBinding
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher

class LoginActivity : AppCompatActivity() {

    private val lastUserUid: String?
        get() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_LAST_LOGGED_IN_UID, null)
    private lateinit var binding: LoginPageBinding
    private lateinit var auth: FirebaseAuth

    private lateinit var executor: Executor
    private lateinit var biometricPromptLogin: BiometricPrompt
    private lateinit var promptInfoLogin: BiometricPrompt.PromptInfo
    private var loginAttempts = 0
    private val MAX_LOGIN_ATTEMPTS_BEFORE_SUGGESTION = 2
    private var mfaSignInResolver: MultiFactorResolver? = null
    private var mfaSignInVerificationId: String? = null
    private var mfaSignInResendingToken: PhoneAuthProvider.ForceResendingToken? = null

    private lateinit var credentialManager: CredentialManager // Already here, good!


    private val functions: FirebaseFunctions by lazy {
        Firebase.functions
    }

    private val tag = "LoginActivity"

    private val lastUserBioKeyAlias: String?
        get() = lastUserUid?.let { "${PREF_KEY_BIOMETRIC_KEY_ALIAS_PREFIX}$it" }

    private val lastUserIsBiometricEnabledKey: String?
        get() = lastUserUid?.let { "${PREF_IS_BIOMETRIC_ENABLED_PREFIX}$it" }

//    private val lastUserEncryptedSymmetricKeyUserDataPrefKey: String?
//        get() = lastUserUid?.let { "${PREF_USERDATA_ENCRYPTED_SYM_KEY_PREFIX}$it" }
//
//    private val lastUserIvUserDataPrefKey: String?
//        get() = lastUserUid?.let { "${PREF_USERDATA_ENCRYPTED_IV_PREFIX}$it" }
//
//    private val lastUserEncryptedUserDataPrefKey: String?
//        get() = lastUserUid?.let { "${PREF_USERDATA_ENCRYPTED_DATA_PREFIX}$it" }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.i("NotificationPermission", "POST_NOTIFICATIONS permission granted by user.")
            } else {
                Log.w("NotificationPermission", "POST_NOTIFICATIONS permission denied by user.")
                // Optionally, inform the user that some notification-based features might not work
                // Toast.makeText(this, "Notifications will not be shown.", Toast.LENGTH_SHORT).show()
            }
        }

    public override fun onStart() {
        super.onStart()
        val currentUser = auth.currentUser
        if (currentUser != null) {
            Log.d(
                tag,
                "onStart: User already logged in: ${currentUser.uid}, Email: ${currentUser.email}"
            )
            if (!currentUser.isEmailVerified && !isGoogleSignInUser(currentUser)) {
                Log.w(
                    tag,
                    "onStart: User logged in but email NOT verified. Showing verification dialog."
                )
                binding.progressBarLogin.visibility = View.GONE
                enableLoginUI(true)
                showEmailNotVerifiedDialog(
                    currentUser,
                    true
                )
            } else {
                Log.d(
                    tag,
                    "onStart: User logged in and email verified (or not required). Checking role..."
                )
                binding.progressBarLogin.visibility = View.VISIBLE
                disableLoginUIWhileChecking()
                handleSuccessfulLoginWithRoleCheck(currentUser)
            }
        } else {
            Log.d(tag, "onStart: No user logged in. Login screen will display.")
            enableLoginUI(true)
            binding.progressBarLogin.visibility = View.GONE
            checkBiometricAvailabilityAndSetupLoginPrompt()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(tag, "LoginActivity onCreate started")

        try {
            binding = LoginPageBinding.inflate(layoutInflater)
            setContentView(binding.root)
            Log.d(tag, "LoginActivity setContentView called with binding.root")
        } catch (e: Exception) {
            Log.e(tag, "Error during binding inflation or setContentView: ", e)
            Toast.makeText(
                this,
                "Vizualizare inițializare a erorilor. Vă rugăm să reporniți aplicația.",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        auth = Firebase.auth
        credentialManager = CredentialManager.create(this)
        Log.d(tag, "Firebase Auth and CredentialManager initialized")

        askNotificationPermission()

        binding.buttonLogin.setOnClickListener {
            Log.d(tag, "Login button clicked")
            loginUser()
        }

        binding.textViewGoToRegister.setOnClickListener {
            Log.d(tag, "Go to Register clicked")
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        binding.textViewForgotPassword.setOnClickListener {
            Log.d(tag, "Forgot Password clicked")
            showForgotPasswordDialog()
        }

        binding.googleSignInButton.setOnClickListener {
            Log.d(tag, "Google Sign-In button clicked (New GIS Flow)")
            binding.progressBarLogin.visibility = View.VISIBLE // Show progress
            enableLoginUI(false) // Disable other UI elements

            lifecycleScope.launch {
                val googleIdOption: GetGoogleIdOption = buildGoogleSignInRequest()

                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()

                try {
                    val result = credentialManager.getCredential(
                        context = this@LoginActivity,
                        request = request
                    )
                    Log.d(tag, "Google Sign-In GetCredential success.")
                    handleSignInWithGoogleCredential(result.credential)
                } catch (e: GetCredentialException) {
                    binding.progressBarLogin.visibility = View.GONE
                    enableLoginUI(true)
                    Log.e(tag, "Google Sign-In GetCredentialException: ${e.message}", e)
                    var errorMessage = "Conectarea Google a eșuat: ${e.message}"
                    when (e) {
                        is GetCredentialCancellationException -> {
                            Log.d(tag, "User cancelled Google Sign-In.")
                            errorMessage = "Conectarea Google a fost anulată."
                        }

                        is NoCredentialException -> {
                            Log.e(tag, "No Google credentials found on device for Sign-In.")
                            errorMessage = "Niciun cont Google nu a fost găsit pentru a se conecta."
                        }

                        else -> {
                            Log.e(tag, "An unexpected error occurred during Google sign-in.")
                            errorMessage = "A apărut o eroare neașteptată în timpul conectării Google."
                        }
                    }
                    Toast.makeText(this@LoginActivity, errorMessage, Toast.LENGTH_LONG).show()
                }
            }
        }
        Log.d(tag, "LoginActivity onCreate finished successfully")
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // API 33+
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // Permission is already granted
                    Log.i("NotificationPermission", "POST_NOTIFICATIONS permission already granted.")
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    // Show an educational UI to the user
                    // This is a good place to explain why your app *might* need notifications,
                    // even if you're unsure which specific feature triggers it.
                    // Keep it generic if you don't know the exact source.
                    AlertDialog.Builder(this)
                        .setTitle("Notification Permission")
                        .setMessage("To ensure you receive important updates and alerts, this app may need to display notifications. Please grant permission.")
                        .setPositiveButton("OK") { _, _ ->
                            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        .setNegativeButton("No thanks",  null) // Or handle denial more gracefully
                        .show()
                }
                else -> {
                    // Directly ask for the permission
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    private fun buildGoogleSignInRequest(): GetGoogleIdOption {
        return GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(getString(R.string.default_web_client_id))
            .setAutoSelectEnabled(true)
            .build()
    }

    private fun handleSignInWithGoogleCredential(credential: androidx.credentials.Credential) {
        when (credential) {
            is CustomCredential -> {
                if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    try {
                        val googleIdTokenCredential =
                            GoogleIdTokenCredential.createFrom(credential.data)
                        val googleIdToken = googleIdTokenCredential.idToken
                        Log.d(tag, "Got ID token from Google (GIS): $googleIdToken")
                        firebaseAuthWithGoogle(googleIdToken)
                    } catch (e: GoogleIdTokenParsingException) {
                        binding.progressBarLogin.visibility = View.GONE
                        enableLoginUI(true)
                        Log.e(tag, "Google Sign-In failed to parse ID token.", e)
                        Toast.makeText(
                            this,
                            "Eroare de conectare Google: nu a putut analiza token.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    binding.progressBarLogin.visibility = View.GONE
                    enableLoginUI(true)
                    Log.e(
                        tag,
                        "Google Sign-In error: Unexpected custom credential type: ${credential.type}"
                    )
                    Toast.makeText(
                        this,
                        "Eroare de conectare Google: tip de acreditare neașteptat.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            else -> {
                binding.progressBarLogin.visibility = View.GONE // Ensure hidden on error
                enableLoginUI(true)
                Log.e(
                    tag,
                    "Google Sign-In error: Unexpected credential type: ${credential::class.java.simpleName}"
                )
                Toast.makeText(
                    this,
                    "Eroare de conectare Google: tip de acreditare nu este acceptat.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }


    override fun onResume() {
        super.onResume()
        Log.d(tag, "LoginActivity onResume CALLED")

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val lastUid = prefs.getString(PREF_LAST_LOGGED_IN_UID, null)

        if (auth.currentUser != null) {
            Log.d(
                tag,
                "onResume: currentUser is NOT null (UID: ${auth.currentUser?.uid}). Last stored UID for biometrics: $lastUid"
            )
            if (auth.currentUser!!.uid == lastUid) {
                val biometricEnabledKeyForLastUser = lastUserIsBiometricEnabledKey
                val isBiometricActuallyEnabled = if (biometricEnabledKeyForLastUser != null) {
                    prefs.getBoolean(biometricEnabledKeyForLastUser, false)
                } else {
                    false
                }

                Log.d(
                    tag,
                    "onResume: Current user matches last biometric user. Is biometric enabled for them? $isBiometricActuallyEnabled (Key: $biometricEnabledKeyForLastUser)"
                )

                if (isBiometricActuallyEnabled) {
                    Log.d(
                        tag,
                        "onResume: Firebase session active for biometric user. Setting up biometric prompt for unlock."
                    )
                    checkBiometricAvailabilityAndSetupLoginPrompt() // This internally calls setupBiometricLoginComponents if needed

                } else {
                    Log.d(
                        tag,
                        "onResume: Current user matches last biometric user, but biometrics NOT enabled in prefs. Hiding biometric button."
                    )
                    binding.buttonBiometricLogin.visibility = View.GONE
                }
            } else {
                Log.d(
                    tag,
                    "onResume: Current user exists but is not the last biometric user (or no last biometric user stored). Hiding biometric button."
                )
                binding.buttonBiometricLogin.visibility = View.GONE
            }
        } else {
            Log.d(
                tag,
                "onResume: currentUser is NULL. Setting up biometric prompt for login if available for a previous user."
            )
            checkBiometricAvailabilityAndSetupLoginPrompt()
        }
    }

    private fun isGoogleSignInUser(user: FirebaseUser): Boolean {
        return user.providerData.any { it.providerId == GoogleAuthProvider.PROVIDER_ID }
    }

    private fun handleSuccessfulLoginWithRoleCheck(user: FirebaseUser) {
        // Progress bar should already be visible or set by the caller (e.g. firebaseAuthWithGoogle or biometric flow)
        // If not, uncomment: binding.progressBarLogin.visibility = View.VISIBLE
        // disableLoginUIWhileChecking() // Caller should also handle disabling UI if needed

        user.getIdToken(true)
            .addOnCompleteListener { task ->
                // This function is now responsible for hiding the progress bar and re-enabling UI
                binding.progressBarLogin.visibility = View.GONE
                enableLoginUI(true) // Re-enable all UI elements now

                if (task.isSuccessful) {
                    val idTokenResult: GetTokenResult? = task.result
                    val isOperator = idTokenResult?.claims?.get("operator") as? Boolean ?: false

                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putString(PREF_LAST_LOGGED_IN_UID, user.uid)
                        .apply()

                    if (isOperator) {
                        Log.d(tag, "User is an OPERATOR. Navigating to OperatorDashboardActivity.")
                        Toast.makeText(this, "Conectarea Operatorului a fost de succes.", Toast.LENGTH_SHORT)
                            .show()
                        val intent = Intent(
                            this,
                            OperatorDashboardActivity::class.java
                        )
                        intent.flags =
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                        finish()
                    } else {
                        Log.d(tag, "User is a REGULAR user. Navigating to MainUserActivity.")
                        Toast.makeText(this, "Conectare cu succes", Toast.LENGTH_SHORT).show()
                        val intent = Intent(
                            this,
                            MainUserActivity::class.java
                        )
                        intent.flags =
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                        finish()
                    }
                } else {
                    // Failed to get token or claims
                    Log.e(tag, "Failed to get ID token with claims: ", task.exception)
                    Toast.makeText(
                        baseContext,
                        "Conectarea a eșuat: nu a putut verifica rolul utilizatorului.",
                        Toast.LENGTH_SHORT
                    ).show()
                    auth.signOut()
                    // UI is already re-enabled by the start of this block's onCompleteListener
                }
            }
    }


    private fun checkBiometricAvailabilityAndSetupLoginPrompt() {
        Log.d(tag, "################################################################")
        Log.d(tag, "checkBiometricAvailabilityAndSetupLoginPrompt EXECUTING NOW")
        val prefs = this.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val currentLastUserUid = this.lastUserUid
        Log.d(tag, "1. Value of 'currentLastUserUid': '$currentLastUserUid'")

        if (currentLastUserUid == null) {
            Log.w(tag, "   'currentLastUserUid' is null. Biometric button will remain hidden.")
            binding.buttonBiometricLogin.visibility = View.GONE
            Log.d(tag, "----------------------------------------------------------------")
            return
        }

        val enabledKeyName = lastUserIsBiometricEnabledKey
        val rsaKeyAliasName = lastUserBioKeyAlias
        Log.d(
            tag,
            "2. Resolved for UID '$currentLastUserUid': enabledKeyName = '$enabledKeyName', rsaKeyAliasName = '$rsaKeyAliasName'"
        )

        if (enabledKeyName != null && rsaKeyAliasName != null) {
            val isEnabled = prefs.getBoolean(enabledKeyName, false)
            Log.d(tag, "3. Value of 'isEnabled' flag from SharedPreferences: $isEnabled")

            if (isEnabled) {
                Log.d(tag, "4. Biometric 'isEnabled' flag is TRUE for user '$currentLastUserUid'.")
                val biometricManager = BiometricManager.from(this)
                val canAuth = biometricManager.canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
                )
                Log.d(
                    tag,
                    "5. Result of 'BiometricManager.canAuthenticate()': $canAuth (SUCCESS is ${BiometricManager.BIOMETRIC_SUCCESS})"
                )

                if (canAuth == BiometricManager.BIOMETRIC_SUCCESS) {
                    Log.i(tag, "SUCCESS: All conditions met. Setting up biometric components.")
                    setupBiometricLoginComponents() // This function will also set the button listener
                    binding.buttonBiometricLogin.visibility = View.VISIBLE // Make button visible
                    binding.buttonBiometricLogin.isEnabled = true // Ensure it's enabled
                    Log.d(tag, "Biometric login button VISIBLE and ENABLED.")
                } else {
                    Log.w(tag, "   BiometricManager.canAuthenticate() FAILED. Code: $canAuth.")
                    binding.buttonBiometricLogin.visibility = View.GONE
                    Log.d(tag, "Biometric login button GONE due to canAuthenticate failure.")
                }
            } else {
                Log.w(
                    tag,
                    "   Biometric 'isEnabled' flag is FALSE. Biometric login button will be GONE."
                )
                binding.buttonBiometricLogin.visibility = View.GONE
            }
        } else {
            Log.w(
                tag,
                "   'enabledKeyName' or 'rsaKeyAliasName' is null. Biometric login button will be GONE."
            )
            binding.buttonBiometricLogin.visibility = View.GONE
        }
        Log.d(tag, "----------------------------------------------------------------")
    }

    private fun setupBiometricLoginComponents() {
        Log.d(tag, "setupBiometricLoginComponents CALLED")
        if (!::executor.isInitialized) {
            executor = ContextCompat.getMainExecutor(this)
        }

        biometricPromptLogin = BiometricPrompt(
            this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Log.e(tag, "LOGIN Biometric AuthError. Code: $errorCode, Msg: $errString")
                    Toast.makeText(
                        applicationContext,
                        "Eroare biometrică: $errString",
                        Toast.LENGTH_SHORT
                    ).show()
                    enableLoginUI(true) // Ensure UI is re-enabled on error

                    val currentRsaKeyAlias = lastUserBioKeyAlias
                    if (currentRsaKeyAlias != null) {
                        var shouldInvalidate = false
                        when (errorCode) {
                            BiometricPrompt.ERROR_VENDOR,
                            BiometricPrompt.ERROR_HW_UNAVAILABLE,
                            BiometricPrompt.ERROR_NO_BIOMETRICS,
                            BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> {
                                Log.w(
                                    tag,
                                    "Biometric error code $errorCode ($errString) triggers potential key invalidation."
                                )
                                shouldInvalidate = true
                            }
                        }
                        if (shouldInvalidate) {
                            Log.w(
                                tag,
                                "Handling invalidated key for: $currentRsaKeyAlias due to auth error $errorCode"
                            )
                            handleInvalidatedBiometricKey(currentRsaKeyAlias)
                        }
                    }
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    Log.i(tag, "LOGIN Biometric Authentication Succeeded.")
                    // UI will be handled by decryptAndSignIn or subsequent flows
                    val cryptoObject = result.cryptoObject
                    if (cryptoObject?.cipher != null) {
                        Log.d(tag, "Cipher object retrieved from AuthenticationResult for login.")
                        binding.progressBarLogin.visibility =
                            View.VISIBLE // Show progress for decryption/sign-in
                        disableLoginUIWhileChecking() // Disable UI during this process
                        decryptAndSignIn(cryptoObject.cipher!!)
                    } else {
                        Log.e(tag, "LOGIN Biometric Auth Succeeded, but Cipher object was null!")
                        Toast.makeText(
                            applicationContext,
                            "Autentificarea a reușit, dar nu a reușit să obțină cifru.",
                            Toast.LENGTH_SHORT
                        ).show()
                        enableLoginUI(true)
                        lastUserBioKeyAlias?.let {
                            Log.e(tag, "Cipher was null, invalidating key $it")
                            handleInvalidatedBiometricKey(it)
                        }
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Log.w(tag, "LOGIN Biometric Authentication Failed (e.g., wrong fingerprint).")
                    Toast.makeText(applicationContext, "Autentificarea a eșuat.", Toast.LENGTH_SHORT)
                        .show()
                    enableLoginUI(true) // Re-enable UI
                }
            })

        promptInfoLogin = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_login_title))
            .setSubtitle(getString(R.string.biometric_login_subtitle))
            .setNegativeButtonText(getString(android.R.string.cancel))
            .build()

        binding.buttonBiometricLogin.setOnClickListener {
            Log.d(tag, "buttonBiometricLogin CLICKED!")
            attemptBiometricLogin()
        }
        Log.d(tag, "biometricPromptLogin, promptInfoLogin INITIALIZED. Click listener SET.")
    }


    private fun attemptBiometricLogin() {
        Log.d(tag, "----------------------------------------------------")
        Log.d(tag, "attemptBiometricLogin CALLED")

        val rsaKeyAliasForLogin = lastUserBioKeyAlias
        Log.d(tag, "1. rsaKeyAliasForLogin retrieved: '$rsaKeyAliasForLogin'")

        if (rsaKeyAliasForLogin == null) {
            Log.e(tag, "   ERROR: rsaKeyAliasForLogin is null. Cannot attempt biometric login.")
            Toast.makeText(
                this,
                "Alias-ul cheii biometrice nu a fost găsită. Vă rugăm să configurați din nou biometria.",
                Toast.LENGTH_LONG
            ).show()
            binding.buttonBiometricLogin.visibility = View.GONE
            enableLoginUI(true)
            Log.d(tag, "----------------------------------------------------")
            return
        }

        if (!::biometricPromptLogin.isInitialized || !::promptInfoLogin.isInitialized) {
            Log.w(
                tag,
                "   WARN: biometricPromptLogin or promptInfoLogin NOT initialized! Re-initializing."
            )
            setupBiometricLoginComponents() // Attempt to re-initialize
            if (!::biometricPromptLogin.isInitialized || !::promptInfoLogin.isInitialized) { // Check again
                Log.e(tag, "   FATAL ERROR: Re-initialization of biometric components failed!")
                Toast.makeText(
                    this,
                    "Conectare biometrică indisponibilă. Vă rugăm să reporniți.",
                    Toast.LENGTH_LONG
                ).show()
                binding.buttonBiometricLogin.visibility = View.GONE
                enableLoginUI(true)
                Log.d(tag, "----------------------------------------------------")
                return
            }
        }

        Log.d(
            tag,
            "2. Attempting to get initialized RSA cipher for symmetric key decryption using alias: '$rsaKeyAliasForLogin'"
        )
        try {
            val rsaCipherForSymmetricKeyDecryption =
                BiometricCryptographyManager.getInitializedCipherForSymmetricKeyDecryption(
                    rsaKeyAliasForLogin
                )

            if (rsaCipherForSymmetricKeyDecryption != null) {
                Log.i(
                    tag,
                    "3. Successfully got RSA cipher. Calling biometricPromptLogin.authenticate()."
                )
                // Disable UI elements just before showing the prompt
                enableLoginUI(false) // This will disable the biometric button too temporarily
                binding.progressBarLogin.visibility =
                    View.GONE // Ensure progress bar is hidden for the prompt itself
                biometricPromptLogin.authenticate(
                    promptInfoLogin,
                    BiometricPrompt.CryptoObject(rsaCipherForSymmetricKeyDecryption)
                )
            } else {
                Log.e(
                    tag,
                    "   ERROR: Failed to get RSA cipher (it was null). Key Alias: '$rsaKeyAliasForLogin'."
                )
                Toast.makeText(
                    this,
                    "Eroare de autentificare biometrică. Cheia poate fi invalidă.",
                    Toast.LENGTH_LONG
                ).show()
                enableLoginUI(true)
                handleInvalidatedBiometricKey(rsaKeyAliasForLogin)
            }
        } catch (e: Exception) {
            Log.e(
                tag,
                "   EXCEPTION during attemptBiometricLogin for alias '$rsaKeyAliasForLogin'",
                e
            )
            Toast.makeText(this, "A apărut o eroare de pregătire a autentificării biometrice.", Toast.LENGTH_SHORT)
                .show()
            enableLoginUI(true)
            if (e is KeyPermanentlyInvalidatedException || e.cause is KeyPermanentlyInvalidatedException) {
                Log.w(tag, "   KeyPermanentlyInvalidatedException caught, handling.")
                handleInvalidatedBiometricKey(rsaKeyAliasForLogin)
            }
        }
        Log.d(tag, "----------------------------------------------------")
    }


    private fun handleInvalidatedBiometricKey(keyAlias: String) {
        Log.w(tag, "handleInvalidatedBiometricKey called for alias: $keyAlias")
        val prefs = this.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val uid = keyAlias.removePrefix(PREF_KEY_BIOMETRIC_KEY_ALIAS_PREFIX)

        if (uid.isNotEmpty() && keyAlias.startsWith(PREF_KEY_BIOMETRIC_KEY_ALIAS_PREFIX)) {
            Log.d(tag, "Invalidating biometric data for UID: $uid")
            // Use the correct SharedPreferences keys for the encrypted payload
            val symKeyPref = "${PREF_USERDATA_ENCRYPTED_SYM_KEY_PREFIX}$uid"
            val ivPref = "${PREF_USERDATA_ENCRYPTED_IV_PREFIX}$uid"
            val dataPref = "${PREF_USERDATA_ENCRYPTED_DATA_PREFIX}$uid"
            val enabledPref = "${PREF_IS_BIOMETRIC_ENABLED_PREFIX}$uid"

            prefs.edit()
                .remove(enabledPref)
                .remove(symKeyPref)
                .remove(ivPref)
                .remove(dataPref)
                .apply()
            Log.d(tag, "Removed prefs: $enabledPref, $symKeyPref, $ivPref, $dataPref")
        } else {
            Log.w(
                tag,
                "Could not reliably derive UID from alias '$keyAlias' to clear specific prefs. Only deleting Keystore key."
            )
        }

        BiometricCryptographyManager.deleteInvalidKey(keyAlias)
        binding.buttonBiometricLogin.visibility = View.GONE
        binding.buttonBiometricLogin.isEnabled = false // Explicitly disable
        Toast.makeText(this, "Conectare biometrică dezactivată. Vă rugăm să utilizați parola.", Toast.LENGTH_LONG)
            .show()
        Log.d(tag, "Biometric login button GONE after key invalidation for alias $keyAlias.")
    }


    private fun decryptAndSignIn(rsaCipherForSymmetricKey: Cipher) {
        Log.d(tag, "decryptAndSignIn: called with biometric-unlocked RSA Cipher.")
        // Progress bar should be visible, UI disabled by caller (onAuthenticationSucceeded)

        val prefs = this.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val currentUid = this.lastUserUid // Get UID first

        if (currentUid == null) {
            Log.e(tag, "decryptAndSignIn: lastUserUid is null. Cannot proceed.")
            Toast.makeText(
                this,
                "Eroare la sesiune biometrică. Vă rugăm să vă conectați manual.",
                Toast.LENGTH_LONG
            ).show()
            binding.progressBarLogin.visibility = View.GONE
            enableLoginUI(true)
            return
        }

        // Use currentUid to construct the correct preference keys
        val encSymmetricKey =
            prefs.getString("${PREF_USERDATA_ENCRYPTED_SYM_KEY_PREFIX}$currentUid", null)
        val iv = prefs.getString("${PREF_USERDATA_ENCRYPTED_IV_PREFIX}$currentUid", null)
        val encData = prefs.getString("${PREF_USERDATA_ENCRYPTED_DATA_PREFIX}$currentUid", null)


        var decryptedEmail: String? = null
        var decryptedIdToken: String? = null

        if (encSymmetricKey != null && iv != null && encData != null) {
            Log.d(
                tag,
                "Found all components for UserData decryption in SharedPreferences for UID: $currentUid"
            )
            val encryptedPayload =
                BiometricCryptographyManager.EncryptedPayload(encSymmetricKey, iv, encData)
            val decryptedJsonData = BiometricCryptographyManager.decryptDataHybrid(
                encryptedPayload,
                rsaCipherForSymmetricKey
            )

            if (decryptedJsonData != null) {
                Log.i(tag, "Stored UserData JSON decrypted successfully for UID: $currentUid")
                try {
                    val userData = JSONObject(decryptedJsonData)
                    decryptedEmail = userData.optString("email", null)
                    decryptedIdToken = userData.optString(
                        "idToken",
                        null
                    ) // Be cautious if idToken can be explicitly null vs. not present

                    if (decryptedIdToken.isNullOrEmpty()) { // More robust check
                        decryptedIdToken =
                            null // Ensure it's null if empty or actually "null" string from JSON
                        Log.w(
                            tag,
                            "Decrypted 'idToken' is null or empty from JSON for UID: $currentUid."
                        )
                    } else {
                        Log.d(tag, "Successfully extracted idToken from JSON for UID: $currentUid.")
                    }
                    if (decryptedEmail != null) {
                        Log.d(
                            tag,
                            "Decrypted email from JSON: $decryptedEmail for UID: $currentUid"
                        )
                    } else {
                        Log.w(
                            tag,
                            "Email not found or was null in decrypted UserData JSON for UID: $currentUid."
                        )
                    }

                } catch (e: JSONException) {
                    Log.e(tag, "Failed to parse decrypted UserData JSON for UID: $currentUid", e)
                    decryptedIdToken = null
                    decryptedEmail = null
                    // UI handling below
                }
            } else {
                Log.e(tag, "Failed to decrypt stored UserData for UID: $currentUid.")
                Toast.makeText(this, "Nu a reușit să decripteze acreditările stocate.", Toast.LENGTH_LONG)
                    .show()
                binding.progressBarLogin.visibility = View.GONE
                enableLoginUI(true)
                lastUserBioKeyAlias?.let {
                    Log.e(tag, "Decryption failed, invalidating key $it for UID $currentUid")
                    handleInvalidatedBiometricKey(it)
                }
                return
            }
        } else {
            Log.w(
                tag,
                "Missing one or more components for UserData decryption for UID: $currentUid."
            )
            Toast.makeText(this, "Date biometrice incomplete. Vă rugăm să vă conectați.", Toast.LENGTH_LONG)
                .show()
            binding.progressBarLogin.visibility = View.GONE
            enableLoginUI(true)
            lastUserBioKeyAlias?.let { // It's possible the data is missing but key exists
                handleInvalidatedBiometricKey(it) // Invalidate to force re-setup
            }
            return
        }

        Toast.makeText(this, "Biometrice recunoscute!", Toast.LENGTH_SHORT).show()

        val currentAuthUser = auth.currentUser
        if (currentAuthUser != null && currentAuthUser.uid == currentUid) {
            Log.d(tag, "Biometric: Firebase user active & matches ($currentUid). Checking role...")
            // UI elements (progress bar, buttons) are handled by handleSuccessfulLoginWithRoleCheck
            handleSuccessfulLoginWithRoleCheck(currentAuthUser)
        } else {
            Log.d(
                tag,
                "Biometric: Firebase session not active or user mismatch. Current Auth UID: ${currentAuthUser?.uid}, Expected UID: $currentUid"
            )

            if (!decryptedIdToken.isNullOrEmpty() && currentUid != null) {
                Log.d(
                    tag,
                    "Attempting ID token exchange for custom token via Cloud Function for UID: $currentUid"
                )
                val data = hashMapOf("idToken" to decryptedIdToken, "uid" to currentUid)

                // Progress bar should already be visible, UI disabled from onAuthenticationSucceeded
                functions.getHttpsCallable("exchangeIdTokenForCustomToken")
                    .call(data)
                    .addOnCompleteListener { task ->
                        // handleSuccessfulLoginWithRoleCheck will manage UI (progress bar, buttons)
                        if (task.isSuccessful) {
                            val result = task.result?.data as? Map<String, Any>
                            val customToken = result?.get("customToken") as? String
                            if (customToken != null) {
                                Log.d(
                                    tag,
                                    "Successfully received custom token for UID: $currentUid."
                                )
                                auth.signInWithCustomToken(customToken)
                                    .addOnCompleteListener(this) { signInTask ->
                                        if (signInTask.isSuccessful) {
                                            signInTask.result?.user?.let { firebaseUser ->
                                                Log.d(
                                                    tag,
                                                    "Firebase signInWithCustomToken success for UID: ${firebaseUser.uid}. Checking role..."
                                                )
                                                handleSuccessfulLoginWithRoleCheck(firebaseUser)
                                            } ?: run {
                                                Log.e(
                                                    tag,
                                                    "signInWithCustomToken success but user is null (UID: $currentUid)."
                                                )
                                                binding.progressBarLogin.visibility = View.GONE
                                                enableLoginUI(true)
                                                promptForManualLogin(
                                                    decryptedEmail,
                                                    "Session refresh error. Please sign in."
                                                )
                                            }
                                        } else {
                                            Log.w(
                                                tag,
                                                "Firebase signInWithCustomToken failed for UID: $currentUid.",
                                                signInTask.exception
                                            )
                                            binding.progressBarLogin.visibility = View.GONE
                                            enableLoginUI(true)
                                            promptForManualLogin(
                                                decryptedEmail,
                                                "Couldn't complete sign in. Please try again."
                                            )
                                        }
                                    }
                            } else {
                                Log.e(
                                    tag,
                                    "Custom token was null in backend response for UID: $currentUid."
                                )
                                binding.progressBarLogin.visibility = View.GONE
                                enableLoginUI(true)
                                promptForManualLogin(
                                    decryptedEmail,
                                    "Session refresh failed (no token). Please sign in."
                                )
                            }
                        } else {
                            val e = task.exception
                            Log.w(tag, "Backend token exchange failed for UID: $currentUid.", e)
                            var errorMessage = "Session refresh failed. Please sign in."
                            if (e is FirebaseFunctionsException) {
                                errorMessage = when (e.code) {
                                    FirebaseFunctionsException.Code.UNAUTHENTICATED -> "Your session token has expired. Please sign in."
                                    FirebaseFunctionsException.Code.INVALID_ARGUMENT -> "Biometric session data invalid. Please sign in."
                                    else -> "Error refreshing session (${e.code}). Please sign in."
                                }
                            }
                            binding.progressBarLogin.visibility = View.GONE
                            enableLoginUI(true)
                            promptForManualLogin(decryptedEmail, errorMessage)
                        }
                    }
            } else {
                Log.w(
                    tag,
                    "Skipping Cloud Function for UID: $currentUid. Reason: ID Token is null/empty ('$decryptedIdToken') or UID issue."
                )
                binding.progressBarLogin.visibility = View.GONE
                enableLoginUI(true)
                promptForManualLogin(
                    decryptedEmail,
                    "Biometric data incomplete. Please sign in with your password."
                )
            }
        }
    }


    private fun promptForManualLogin(email: String?, message: String) {
        var feedbackMessage = message
        if (email != null) {
            binding.editTextEmailLogin.setText(email)
            Log.d(tag, "Pre-filled email with: $email")
        }
        binding.editTextPasswordLogin.requestFocus()
        Toast.makeText(this, feedbackMessage, Toast.LENGTH_LONG).show()
        Log.i(tag, "Solicitarea de autentificare manuală. Mesaj: $feedbackMessage")
        // Ensure UI is enabled for manual input if not already
        enableLoginUI(true)
        binding.progressBarLogin.visibility = View.GONE // Ensure progress is hidden
    }


    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        // This is usually for things like BIOMETRIC_ERROR_NONE_ENROLLED if you redirect to settings.
        // For the new Credential Manager, this is less common for the sign-in flow itself.
    }


    private fun showForgotPasswordDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(getString(R.string.reset_password_title))
        val view = layoutInflater.inflate(R.layout.dialog_forgot_password, null)
        val editTextEmail = view.findViewById<EditText>(R.id.editTextEmailReset)
        builder.setView(view)

        builder.setPositiveButton(getString(R.string.reset_button_text)) { _, _ ->
            val email = editTextEmail.text.toString().trim()
            if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                Toast.makeText(this, getString(R.string.invalid_email_address), Toast.LENGTH_SHORT)
                    .show()
                return@setPositiveButton
            }
            sendPasswordResetInstructions(email)
        }
        builder.setNegativeButton(getString(android.R.string.cancel)) { dialog, _ ->
            dialog.dismiss()
        }
        builder.create().show()
    }

    private fun sendPasswordResetInstructions(email: String) {
        if (!isNetworkAvailable()) {
            Toast.makeText(this, getString(R.string.no_internet_connection), Toast.LENGTH_LONG)
                .show()
            return
        }

        binding.progressBarLogin.visibility = View.VISIBLE
        enableLoginUI(false)

        auth.sendPasswordResetEmail(email)
            .addOnCompleteListener { task ->
                binding.progressBarLogin.visibility = View.GONE
                enableLoginUI(true)
                if (task.isSuccessful) {
                    Log.d(tag, "Password reset email sent to $email")
                    Toast.makeText(
                        this,
                        getString(R.string.password_reset_email_sent_message),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Log.w(tag, "sendPasswordResetEmail:failure", task.exception)
                    Toast.makeText(
                        this,
                        getString(
                            R.string.password_reset_failed_message,
                            task.exception?.message ?: "Unknown error"
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        Log.d(tag, "Attempting Firebase authentication with Google ID token.")
        // Progress bar should be visible and UI disabled by the Google Sign-In click handler

        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                // handleSuccessfulLoginWithRoleCheck or the failure path below will handle UI
                if (task.isSuccessful) {
                    Log.d(tag, "Firebase signInWithCredential success. Checking role...")
                    val user = auth.currentUser
                    user?.let {
                        handleSuccessfulLoginWithRoleCheck(it)
                    } ?: run {
                        binding.progressBarLogin.visibility = View.GONE
                        enableLoginUI(true)
                        Log.e(tag, "Google Sign-In success but Firebase user is null.")
                        Toast.makeText(
                            this,
                            "Google Sign-In failed to link to Firebase user.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    binding.progressBarLogin.visibility = View.GONE
                    enableLoginUI(true)
                    Log.w(tag, "Firebase signInWithCredential failure", task.exception)
                    Toast.makeText(
                        this,
                        "Firebase Authentication Failed: ${task.exception?.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
    }


    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
            return when {
                activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
                activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
                activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
                else -> false
            }
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo ?: return false
            @Suppress("DEPRECATION")
            return networkInfo.isConnected
        }
    }

    private fun loginUser() {
        val email = binding.editTextEmailLogin.text.toString().trim()
        val password = binding.editTextPasswordLogin.text.toString().trim()

        if (!isNetworkAvailable()) {
            Toast.makeText(this, getString(R.string.no_internet_connection), Toast.LENGTH_LONG)
                .show()
            return
        }

        if (email.isEmpty()) {
            binding.editTextEmailLogin.error = getString(R.string.email_required)
            binding.editTextEmailLogin.requestFocus()
            return
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.editTextEmailLogin.error = getString(R.string.invalid_email_address)
            binding.editTextEmailLogin.requestFocus()
            return
        }
        if (password.isEmpty()) {
            binding.editTextPasswordLogin.error = getString(R.string.password_required)
            binding.editTextPasswordLogin.requestFocus()
            return
        }

        binding.progressBarLogin.visibility = View.VISIBLE
        enableLoginUI(false)

        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                // handleSuccessfulLoginWithRoleCheck or the failure path will manage UI
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    if (user != null) {
                        if (user.isEmailVerified) {
                            Log.d(tag, "signInWithEmail:success - Email Verified")
                            loginAttempts = 0
                            handleSuccessfulLoginWithRoleCheck(user) // This will hide progress, enable UI
                        } else {
                            Log.w(tag, "signInWithEmail:success - BUT Email NOT Verified")
                            binding.progressBarLogin.visibility = View.GONE
                            enableLoginUI(true)
                            loginAttempts = 0
                            showEmailNotVerifiedDialog(user)
                            auth.signOut()
                        }
                    } else {
                        binding.progressBarLogin.visibility = View.GONE
                        enableLoginUI(true)
                        Log.e(tag, "signInWithEmail:success - BUT user is null!")
                        Toast.makeText(
                            baseContext,
                            "Autentificarea a eșuat: datele utilizatorului nu au fost găsite.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    binding.progressBarLogin.visibility =
                        View.GONE // Ensure hidden on failure before other UI actions
                    enableLoginUI(true)
                    loginAttempts++
                    Log.w(tag, "signInWithEmail:failure", task.exception)
                    val exception = task.exception

                    if (exception is FirebaseAuthMultiFactorException) {
                        Log.d(
                            tag,
                            "MFA is required. Handling with FirebaseAuthMultiFactorException."
                        )
                        mfaSignInResolver = exception.resolver
                        promptForMfaSignIn(mfaSignInResolver!!) // This will manage its own UI
                    } else {
                        var errorMessage = getString(R.string.auth_failed_default)
                        if (exception is FirebaseAuthInvalidUserException) {
                            errorMessage = getString(R.string.auth_failed_no_user)
                        } else if (exception is FirebaseAuthInvalidCredentialsException) {
                            errorMessage = getString(R.string.auth_failed_incorrect_password)
                        } else if (exception != null) {
                            errorMessage =
                                getString(R.string.auth_failed_exception, exception.message)
                        }

                        if (loginAttempts >= MAX_LOGIN_ATTEMPTS_BEFORE_SUGGESTION) {
                            errorMessage += "\n" + getString(R.string.auth_failed_suggestion)
                        }
                        Toast.makeText(baseContext, errorMessage, Toast.LENGTH_LONG).show()
                    }
                }
            }
    }

    private fun promptForMfaSignIn(resolver: MultiFactorResolver) {
        Log.d(tag, "promptForMfaSignIn called.")
        // UI should be mostly disabled by loginUser, just ensure progress bar is hidden
        binding.progressBarLogin.visibility = View.GONE
        // Keep main login buttons disabled, MFA dialog will handle its own interaction
        binding.buttonLogin.isEnabled = false
        binding.googleSignInButton.isEnabled = false

        val phoneHint = resolver.hints.find { it is PhoneMultiFactorInfo } as? PhoneMultiFactorInfo
        if (phoneHint == null) {
            Toast.makeText(this, "MFA este necesar, dar nu a fost găsit niciun factor de telefon.", Toast.LENGTH_LONG)
                .show()
            Log.e(tag, "MFA required, but no PhoneMultiFactorInfo hint found.")
            enableLoginUI(true) // Re-enable main login UI
            mfaSignInResolver = null
            return
        }

        val dialogBinding = DialogMfaLoginCodeBinding.inflate(layoutInflater)
        val mfaDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.mfa_dialog_title))
            .setView(dialogBinding.root)
            .setCancelable(false)
            .create()

        mfaDialog.setOnDismissListener { // Changed from setOnCancelListener to handle all dismiss types
            Log.d(tag, "MFA Dialog dismissed/cancelled.")
            if (mfaSignInResolver != null) { // Only re-enable if resolver wasn't cleared by success/failure
                enableLoginUI(true)
            }
            mfaSignInResolver = null
        }


        val mfaSignInCallbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                Log.d(tag, "MFA Sign-in: onVerificationCompleted (auto-retrieval).")
                dialogBinding.etLoginMfaCode.setText(credential.smsCode)
                val assertion = PhoneMultiFactorGenerator.getAssertion(credential)
                if (mfaDialog.isShowing) mfaDialog.dismiss() // Dismiss before resolving
                binding.progressBarLogin.visibility = View.VISIBLE // Show progress for resolution
                // Main UI (login buttons) should still be disabled here
                resolveMfaSignIn(resolver, assertion)
            }

            override fun onVerificationFailed(e: FirebaseException) {
                Log.e(tag, "MFA Sign-in: onVerificationFailed: ${e.message}", e)
                Toast.makeText(
                    this@LoginActivity,
                    "Verificarea MFA a eșuat: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                if (mfaDialog.isShowing) mfaDialog.dismiss()
                // enableLoginUI(true) // Now handled by onDismissListener
                // mfaSignInResolver = null // Now handled by onDismissListener
            }

            override fun onCodeSent(
                verificationId: String,
                token: PhoneAuthProvider.ForceResendingToken
            ) {
                Log.d(tag, "MFA Sign-in: onCodeSent. Verification ID: $verificationId")
                this@LoginActivity.mfaSignInVerificationId = verificationId
                this@LoginActivity.mfaSignInResendingToken = token
                Toast.makeText(this@LoginActivity, "Codul MFA trimis.", Toast.LENGTH_SHORT).show()
                dialogBinding.buttonVerifyMfaCode.isEnabled = true
                dialogBinding.tvResendMfaCode.isEnabled = true
            }
        }

        Log.d(tag, "Requesting MFA code for hint: ${phoneHint.displayName}")
        val options = PhoneAuthOptions.newBuilder(auth)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(this)
            .setCallbacks(mfaSignInCallbacks)
            .setMultiFactorSession(resolver.session)
            .setMultiFactorHint(phoneHint)
            .build()
        PhoneAuthProvider.verifyPhoneNumber(options)

        dialogBinding.buttonVerifyMfaCode.setOnClickListener {
            val smsCode = dialogBinding.etLoginMfaCode.text.toString().trim()
            if (mfaSignInVerificationId != null && smsCode.isNotEmpty()) {
                dialogBinding.buttonVerifyMfaCode.isEnabled = false
                val credential = PhoneAuthProvider.getCredential(mfaSignInVerificationId!!, smsCode)
                val assertion = PhoneMultiFactorGenerator.getAssertion(credential)
                if (mfaDialog.isShowing) mfaDialog.dismiss()
                binding.progressBarLogin.visibility = View.VISIBLE
                resolveMfaSignIn(resolver, assertion)
            } else {
                Toast.makeText(this, "Vă rugăm să introduceți codul SMS.", Toast.LENGTH_SHORT).show()
            }
        }

        dialogBinding.tvResendMfaCode.setOnClickListener {
            if (mfaSignInResendingToken != null) {
                Toast.makeText(this@LoginActivity, "Revending codul MFA ...", Toast.LENGTH_SHORT)
                    .show()
                dialogBinding.tvResendMfaCode.isEnabled = false
                val resendOptions = PhoneAuthOptions.newBuilder(auth)
                    .setTimeout(60L, TimeUnit.SECONDS)
                    .setActivity(this)
                    .setCallbacks(mfaSignInCallbacks)
                    .setMultiFactorSession(resolver.session)
                    .setMultiFactorHint(phoneHint)
                    .setForceResendingToken(mfaSignInResendingToken!!)
                    .build()
                PhoneAuthProvider.verifyPhoneNumber(resendOptions)
            } else {
                Toast.makeText(this@LoginActivity, "Nu se poate retrimite codul acum.", Toast.LENGTH_SHORT)
                    .show()
            }
        }

        mfaDialog.show()
        dialogBinding.buttonVerifyMfaCode.isEnabled = false
        dialogBinding.tvResendMfaCode.isEnabled = false
    }

    private fun resolveMfaSignIn(
        resolver: MultiFactorResolver,
        assertion: MultiFactorAssertion
    ) {
        Log.d(tag, "resolveMfaSignIn called.")
        // Progress bar should be visible from caller
        // UI (login buttons) should be disabled from caller

        resolver.resolveSignIn(assertion)
            .addOnSuccessListener { authResult ->
                Log.d(
                    tag,
                    "MFA Sign-in fully resolved! User: ${authResult.user?.uid}. Checking role..."
                )
                this.mfaSignInResolver = null // Clear resolver on success
                authResult.user?.let {
                    handleSuccessfulLoginWithRoleCheck(it) // This will manage UI (progress, buttons)
                } ?: run {
                    binding.progressBarLogin.visibility = View.GONE
                    enableLoginUI(true)
                    Log.e(tag, "MFA resolved but user is null.")
                    Toast.makeText(this, "Eroare de conectare MFA.", Toast.LENGTH_LONG).show()
                }
            }
            .addOnFailureListener { e ->
                binding.progressBarLogin.visibility = View.GONE
                enableLoginUI(true)
                Log.e(tag, "Error resolving MFA sign-in: ${e.message}", e)
                Toast.makeText(this, "Verificarea MFA a eșuat: ${e.message}", Toast.LENGTH_LONG)
                    .show()
                this.mfaSignInResolver = null // Clear resolver on failure
            }
    }

    private fun showEmailNotVerifiedDialog(user: FirebaseUser, fromOnStart: Boolean = false) {
        val dialogBuilder = AlertDialog.Builder(this)
        dialogBuilder.setTitle(getString(R.string.email_verification_required_title))
        dialogBuilder.setMessage(
            getString(R.string.email_verification_message_part1, user.email ?: "your email") +
                    "\n\n" + getString(R.string.email_verification_message_part2)
        )
        dialogBuilder.setPositiveButton(getString(R.string.resend_email_button)) { dialog, _ ->
            user.sendEmailVerification()
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Toast.makeText(
                            this,
                            getString(R.string.verification_email_sent_toast),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Log.e(tag, "sendEmailVerification failed", task.exception)
                        Toast.makeText(
                            this,
                            getString(R.string.failed_to_send_verification_email_toast),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            dialog.dismiss()
            if (fromOnStart) {
                enableLoginUI(true)
            }
        }
        dialogBuilder.setNegativeButton(getString(R.string.ok_button)) { dialog, _ -> // Changed from "Later" to "OK" for clarity
            dialog.dismiss()
            if (fromOnStart) {
                auth.signOut()
                Log.d(tag, "User chose 'OK' for email verification from onStart. Signed out.")
                Toast.makeText(
                    this,
                    getString(R.string.please_verify_to_continue_toast),
                    Toast.LENGTH_LONG
                ).show()
                enableLoginUI(true)
            }
        }
        dialogBuilder.setCancelable(false)
        val alert = dialogBuilder.create()
        alert.show()
    }

    private fun enableLoginUI(enable: Boolean) {
        binding.editTextEmailLogin.isEnabled = enable
        binding.editTextPasswordLogin.isEnabled = enable
        binding.buttonLogin.isEnabled = enable
        binding.googleSignInButton.isEnabled = enable

        if (enable) {
            // Re-check biometric status only when enabling UI fully
            checkBiometricAvailabilityAndSetupLoginPrompt() // This will manage biometric button visibility and enabled state
        } else {
            binding.buttonBiometricLogin.isEnabled =
                false // Directly disable if overall UI is being disabled
        }

        binding.textViewGoToRegister.isClickable = enable
        binding.textViewGoToRegister.isEnabled = enable
        binding.textViewForgotPassword.isClickable = enable
        binding.textViewForgotPassword.isEnabled = enable
    }

    private fun disableLoginUIWhileChecking() {
        // This is called when an async operation starts (like role check)
        binding.editTextEmailLogin.isEnabled = false
        binding.editTextPasswordLogin.isEnabled = false
        binding.buttonLogin.isEnabled = false
        binding.googleSignInButton.isEnabled = false
        binding.buttonBiometricLogin.isEnabled = false
        binding.textViewGoToRegister.isClickable = false
        binding.textViewGoToRegister.isEnabled = false
        binding.textViewForgotPassword.isClickable = false
        binding.textViewForgotPassword.isEnabled = false
    }

    // navigateToMainApp is not directly called anymore, navigation happens in handleSuccessfulLoginWithRoleCheck
    // private fun navigateToMainApp() { ... }

}