package com.licenta.e_ajutor.activity // Your package name

import androidx.appcompat.app.AppCompatActivity

class UserProfileActivity : AppCompatActivity() {

//    private lateinit var binding: FragmentProfileBinding
//    private lateinit var auth: FirebaseAuth
//    private lateinit var db: FirebaseFirestore
//    private lateinit var executor: Executor
//    private lateinit var biometricPrompt: BiometricPrompt
//    private lateinit var promptInfo: BiometricPrompt.PromptInfo
//    private val TAG = "UserProfileActivity"
//
//
//    private val userBioKeyAlias: String // For the RSA key in Keystore
//        get() = "${PREF_KEY_BIOMETRIC_KEY_ALIAS_PREFIX}${auth.currentUser?.uid ?: "unknown_user"}"
//
//    private val isBiometricEnabledPrefKey: String
//        get() = "${PREF_IS_BIOMETRIC_ENABLED_PREFIX}${auth.currentUser?.uid ?: "unknown_user"}"
//
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        binding = FragmentProfileBinding.inflate(layoutInflater)
//        setContentView(binding.root)
//
//        auth = Firebase.auth
//        db = Firebase.firestore
//
//        supportActionBar?.setDisplayHomeAsUpEnabled(true)
//        supportActionBar?.title = "Profilul meu"
//
//        if (auth.currentUser == null) {
//            Log.w(TAG, "User is null in UserProfileActivity, redirecting to LoginActivity.")
//            startActivity(Intent(this, LoginActivity::class.java).apply {
//                Intent.setFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
//            })
//            finish()
//            return
//        }
//
//        executor = ContextCompat.getMainExecutor(this)
//
//        loadUserProfile()
//        updateBiometricSwitchState()
//
//        binding.switchBiometricLogin.setOnCheckedChangeListener { _, isChecked ->
//            Log.d(TAG, "SwitchBiometricLogin Toggled. isChecked: $isChecked")
//            if (isChecked) {
//                enableBiometricLogin()
//            } else {
//                disableBiometricLogin()
//            }
//        }
//
//        binding.buttonEditName.setOnClickListener { showEditNameDialog() }
//
//        binding.buttonSetup2FA.setOnClickListener {
//            Log.d(TAG, "buttonSetup2FA clicked, launching MfaSetupActivity.")
//            val intent =
//                Intent(this, MfaSetupActivity::class.java) // Ensure MfaSetupActivity is imported
//            startActivity(intent)
//        }
//        binding.buttonSignOut.setOnClickListener { showSignOutConfirmationDialog() }
//    }
//
//    override fun onResume() {
//        super.onResume()
//        Log.d(TAG, "UserProfileActivity onResume called.")
//        updateBiometricSwitchState()
//    }
//
//
//    private fun enableBiometricLogin() {
//        Log.d(TAG, "enableBiometricLogin called.")
//        val biometricManager = BiometricManager.from(this)
//        val authResult = biometricManager.canAuthenticate(
//            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
//        )
//        Log.d(
//            TAG,
//            "BiometricManager.canAuthenticate result: $authResult (0 means BIOMETRIC_SUCCESS)"
//        )
//
//        when (authResult) {
//            BiometricManager.BIOMETRIC_SUCCESS -> {
//                Log.d(TAG, "App can authenticate using biometrics. Showing prompt for setup...")
//                // This prompt is just to confirm user's intent.
//                // The actual RSA key pair generation/retrieval for encryption happens in storeEncryptedTokenAfterBioAuth
//                setupBiometricPrompt(forSetup = true) // This sets up the prompt for user confirmation
//                biometricPrompt.authenticate(promptInfo) // Authenticate to confirm setup
//            }
//
//            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
//                Log.e(TAG, "No biometric features available on this device.")
//                Toast.makeText(this, "No biometric features available.", Toast.LENGTH_LONG).show()
//                binding.switchBiometricLogin.isChecked = false
//            }
//
//            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
//                Log.e(TAG, "Biometric features are currently unavailable.")
//                Toast.makeText(this, "Biometric features unavailable.", Toast.LENGTH_LONG).show()
//                binding.switchBiometricLogin.isChecked = false
//            }
//
//            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
//                Log.e(TAG, "No biometrics enrolled.")
//                Toast.makeText(
//                    this,
//                    "No biometrics enrolled. Please set up in device settings.",
//                    Toast.LENGTH_LONG
//                ).show()
//                binding.switchBiometricLogin.isChecked = false
//            }
//
//            else -> {
//                Log.e(TAG, "Biometric authentication unavailable. Code: $authResult")
//                Toast.makeText(this, "Biometric authentication unavailable.", Toast.LENGTH_LONG)
//                    .show()
//                binding.switchBiometricLogin.isChecked = false
//            }
//        }
//    }
//
//    private fun setupBiometricPrompt(forSetup: Boolean = true) {
//        biometricPrompt = BiometricPrompt(
//            this, executor,
//            object : BiometricPrompt.AuthenticationCallback() {
//                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
//                    super.onAuthenticationError(errorCode, errString)
//                    Log.e(
//                        TAG,
//                        "BiometricPrompt AuthError. Code: $errorCode, Msg: $errString, Setup: $forSetup"
//                    )
//                    Toast.makeText(
//                        applicationContext,
//                        "Authentication error: $errString",
//                        Toast.LENGTH_SHORT
//                    ).show()
//                    if (forSetup) {
//                        Log.d(TAG, "Auth error during setup. Setting switch to false.")
//                        binding.switchBiometricLogin.isChecked = false
//                    }
//                }
//
//                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
//                    super.onAuthenticationSucceeded(result)
//                    Log.d(TAG, "BiometricPrompt AuthSucceeded. Setup: $forSetup")
//                    Toast.makeText(
//                        applicationContext,
//                        "Biometric Authentication succeeded!",
//                        Toast.LENGTH_SHORT
//                    ).show()
//
//                    if (forSetup) {
//                        // The BiometricPrompt for setup doesn't need a CryptoObject for RSA key usage
//                        // The RSA public key is used directly for encryption.
//                        Log.d(TAG, "Proceeding to storeEncryptedTokenAfterBioAuth...")
//                        storeEncryptedTokenAfterBioAuth()
//                    }
//                    // If !forSetup, this callback would be for LoginActivity, which handles its CryptoObject.
//                }
//
//                override fun onAuthenticationFailed() {
//                    super.onAuthenticationFailed()
//                    Log.w(TAG, "BiometricPrompt AuthFailed. Setup: $forSetup")
//                    Toast.makeText(applicationContext, "Authentication failed", Toast.LENGTH_SHORT)
//                        .show()
//                    if (forSetup) {
//                        binding.switchBiometricLogin.isChecked = false
//                    }
//                }
//            })
//
//        // PromptInfo for initial biometric setup (just confirming user presence)
//        promptInfo = BiometricPrompt.PromptInfo.Builder()
//            .setTitle("Biometric Login Setup")
//            .setSubtitle("Confirm fingerprint/face to enable biometric login")
//            .setNegativeButtonText("Cancel")
//            .build()
//    }
//
//
//    private fun storeEncryptedTokenAfterBioAuth() {
//        val currentUser = auth.currentUser
//        if (currentUser == null) {
//            Toast.makeText(this, "User not logged in.", Toast.LENGTH_SHORT).show()
//            binding.switchBiometricLogin.isChecked = false
//            Log.w(TAG, "storeEncryptedTokenAfterBioAuth: User is null.")
//            return
//        }
//
//        val userId = currentUser.uid
//        val userEmail = currentUser.email // Get email here
//
//        Log.d(TAG, "storeEncryptedTokenAfterBioAuth: Attempting for UID: $userId")
//
//        currentUser.getIdToken(true).addOnCompleteListener { task ->
//            if (task.isSuccessful) {
//                val idToken = task.result?.token
//                if (idToken != null) {
//                    // Use the userBioKeyAlias getter which is already defined for the current user
//                    val currentRsaKeyAlias = userBioKeyAlias
//
//                    // Get or create RSA public key.
//                    val rsaPublicKey = BiometricCryptographyManager.getPublicKey(currentRsaKeyAlias)
//
//                    if (rsaPublicKey != null) {
//                        // 2. Create a JSON object with user data (ID Token and Email)
//                        val userDataMap = mutableMapOf<String, String?>()
//                        userDataMap["idToken"] = idToken
//                        userDataMap["email"] = userEmail // This can be null if userEmail was null
//
//                        val userDataJson = JSONObject(userDataMap).toString()
//                        Log.d(
//                            TAG,
//                            "User data JSON to encrypt in storeEncryptedTokenAfterBioAuth: $userDataJson"
//                        )
//
//                        // 3. Encrypt the JSON string
//                        val encryptedUserPayload = BiometricCryptographyManager.encryptDataHybrid(
//                            userDataJson,
//                            rsaPublicKey
//                        )
//
//                        if (encryptedUserPayload != null) {
//                            val prefs = this.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
//                            val editor = prefs.edit()
//
//                            // STORE USING THE "_userdata_" KEYS
//                            editor.putString(
//                                "bio_enc_sym_key_userdata_$userId",
//                                encryptedUserPayload.encryptedSymmetricKey
//                            )
//                            editor.putString("bio_iv_userdata_$userId", encryptedUserPayload.iv)
//                            editor.putString(
//                                "bio_enc_data_userdata_$userId",
//                                encryptedUserPayload.encryptedData
//                            )
//
//                            // Store other necessary flags
//                            editor.putString(
//                                PREF_KEY_BIOMETRIC_KEY_ALIAS_PREFIX + userId,
//                                currentRsaKeyAlias
//                            ) // Use the one with underscores
//                            editor.putBoolean(
//                                isBiometricEnabledPrefKey,
//                                true
//                            ) // isBiometricEnabledPrefKey uses the getter, which is fine
//                            editor.putString(PREF_LAST_LOGGED_IN_UID, userId)
//                            editor.apply()
//
//                            Toast.makeText(this, "Biometric login enabled.", Toast.LENGTH_SHORT)
//                                .show()
//                            Log.i(
//                                TAG,
//                                "SUCCESS: Biometric setup complete and all preferences stored for UID: $userId using _userdata_ keys."
//                            )
//                            Log.d(
//                                TAG,
//                                "  - Encrypted Symmetric Key (userdata) stored under: bio_enc_sym_key_userdata_$userId"
//                            )
//                            Log.d(TAG, "  - IV (userdata) stored under: bio_iv_userdata_$userId")
//                            Log.d(
//                                TAG,
//                                "  - Encrypted Data (userdata) stored under: bio_enc_data_userdata_$userId"
//                            )
//                            Log.d(
//                                TAG,
//                                "  - Biometric key alias stored under: ${PREF_KEY_BIOMETRIC_KEY_ALIAS_PREFIX + userId}"
//                            )
//                            Log.d(
//                                TAG,
//                                "  - Biometric enabled flag stored under: $isBiometricEnabledPrefKey"
//                            )
//                            Log.d(TAG, "  - PREF_LAST_LOGGED_IN_UID set to: $userId")
//
//                        } else {
//                            Toast.makeText(
//                                this,
//                                "Failed to encrypt user data payload.",
//                                Toast.LENGTH_SHORT
//                            ).show()
//                            Log.e(
//                                TAG,
//                                "storeEncryptedTokenAfterBioAuth: encryptDataHybrid returned null for UID: $userId"
//                            )
//                            binding.switchBiometricLogin.isChecked = false
//                            BiometricCryptographyManager.deleteInvalidKey(currentRsaKeyAlias) // Clean up key if encryption failed
//                        }
//                    } else {
//                        Toast.makeText(
//                            this,
//                            "Failed to get/create RSA encryption key.",
//                            Toast.LENGTH_SHORT
//                        ).show()
//                        Log.e(
//                            TAG,
//                            "storeEncryptedTokenAfterBioAuth: rsaPublicKey was null for alias '$currentRsaKeyAlias' for UID: $userId"
//                        )
//                        binding.switchBiometricLogin.isChecked = false
//                    }
//                } else {
//                    Toast.makeText(this, "Failed to get Firebase ID token.", Toast.LENGTH_SHORT)
//                        .show()
//                    Log.e(
//                        TAG,
//                        "storeEncryptedTokenAfterBioAuth: Firebase ID token was null for UID: $userId"
//                    )
//                    binding.switchBiometricLogin.isChecked = false
//                }
//            } else {
//                Toast.makeText(
//                    this,
//                    "Error getting Firebase ID token: ${task.exception?.message}",
//                    Toast.LENGTH_SHORT
//                ).show()
//                Log.e(
//                    TAG,
//                    "storeEncryptedTokenAfterBioAuth: Error getting Firebase ID token for UID: $userId",
//                    task.exception
//                )
//                binding.switchBiometricLogin.isChecked = false
//            }
//        }
//    }
//
//    private fun disableBiometricLogin() {
//        Log.d(TAG, "disableBiometricLogin called.")
//        val prefs = this.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
//        val currentUserId = auth.currentUser?.uid
//
//        if (currentUserId == null) {
//            Log.w(TAG, "Cannot disable biometric login, user is null.")
//            binding.switchBiometricLogin.isChecked = false // Ensure switch is off
//            return
//        }
//
//        Log.d(TAG, "Removing biometric preferences for UID: $currentUserId")
//        prefs.edit()
//            // Use the correct keys for the idToken/email payload
//            .remove("${PREF_USERDATA_ENCRYPTED_SYM_KEY_PREFIX}$currentUserId")
//            .remove("${PREF_USERDATA_ENCRYPTED_IV_PREFIX}$currentUserId")
//            .remove("${PREF_USERDATA_ENCRYPTED_DATA_PREFIX}$currentUserId")
//            // This is correct
//            .putBoolean(isBiometricEnabledPrefKey, false)
//            // Optionally, also remove the stored alias if you don't need it after disabling
//            .remove("${PREF_KEY_BIOMETRIC_KEY_ALIAS_PREFIX}$currentUserId")
//            .apply()
//
//        // Also delete the corresponding RSA key from Android Keystore
//        BiometricCryptographyManager.deleteInvalidKey(userBioKeyAlias) // userBioKeyAlias getter is fine
//
//        Toast.makeText(this, "Biometric login disabled.", Toast.LENGTH_SHORT).show()
//        Log.d(TAG, "Biometric login disabled for UID: $currentUserId")
//        binding.switchBiometricLogin.isChecked = false // Ensure switch reflects the state
//    }
//
//    private fun updateBiometricSwitchState() {
//        val prefs = this.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
//        val currentUid = auth.currentUser?.uid
//        if (currentUid == null) {
//            Log.e(TAG, "updateBiometricSwitchState: Current user is null.")
//            binding.switchBiometricLogin.isChecked = false
//            return
//        }
//
//        val isEnabledKey = isBiometricEnabledPrefKey
//        val isEnabled = prefs.getBoolean(isEnabledKey, false)
//        Log.d(
//            TAG,
//            "updateBiometricSwitchState: For UID '$currentUid', pref key '$isEnabledKey', isEnabled: $isEnabled"
//        )
//        binding.switchBiometricLogin.isChecked = isEnabled
//
//        if (isEnabled) {
//            // Further check if hardware is still available and biometrics are enrolled
//            val biometricManager = BiometricManager.from(this)
//            val canAuth = biometricManager.canAuthenticate(
//                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
//            )
//            Log.d(
//                TAG,
//                "updateBiometricSwitchState: Biometrics were enabled. Hardware status: $canAuth"
//            )
//            if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
//                Log.w(
//                    TAG,
//                    "Biometrics were enabled but hardware status changed (Code: $canAuth). Disabling."
//                )
//                Toast.makeText(
//                    this,
//                    "Biometric status changed. Disabling feature.",
//                    Toast.LENGTH_LONG
//                ).show()
//                disableBiometricLogin() // This will also update the switch by setting the pref to false
//            }
//        }
//    }
//
//    override fun onSupportNavigateUp(): Boolean {
//        onBackPressedDispatcher.onBackPressed()
//        return true
//    }
//
//    private fun loadUserProfile() {
//        val user = auth.currentUser
//        user?.let { firebaseUser ->
//            binding.textViewEmail.text = firebaseUser.email ?: "N/A"
//            val userDocRef = db.collection("users").document(firebaseUser.uid)
//
//            userDocRef.get()
//                .addOnSuccessListener { document ->
//                    if (document != null && document.exists()) {
//                        binding.textViewFirstName.text = document.getString("firstName") ?: ""
//                        binding.textViewLastName.text = document.getString("lastName") ?: ""
//                        binding.textViewPhone.text = document.getString("phone") ?: ""
//                    } else {
//                        Log.d(
//                            TAG,
//                            "No Firestore document for UID: ${firebaseUser.uid}. Creating a basic one."
//                        )
//                        var initialFirstName = ""
//                        var initialLastName = ""
//                        val initialEmail = firebaseUser.email ?: ""
//                        val initialPhone = ""
//                        if (!firebaseUser.displayName.isNullOrEmpty()) {
//                            val nameParts = firebaseUser.displayName?.split(" ", limit = 2)
//                            initialFirstName = nameParts?.getOrNull(0) ?: ""
//                            initialLastName = nameParts?.getOrNull(1) ?: ""
//                        }
//                        binding.textViewFirstName.text = initialFirstName.ifEmpty { "N/A" }
//                        binding.textViewLastName.text = initialLastName
//                        // binding.textViewPhone.text = "N/A"
//
//                        val basicProfileData = hashMapOf(
//                            "email" to initialEmail,
//                            "firstName" to initialFirstName,
//                            "lastName" to initialLastName,
//                            "phone" to initialPhone
//                        )
//                        userDocRef.set(basicProfileData)
//                            .addOnSuccessListener {
//                                Log.d(
//                                    TAG,
//                                    "Basic profile created for UID: ${firebaseUser.uid}"
//                                )
//                            }
//                            .addOnFailureListener { e ->
//                                Log.e(
//                                    TAG,
//                                    "Error creating basic profile: ",
//                                    e
//                                )
//                            }
//                    }
//                }
//                .addOnFailureListener { exception ->
//                    Log.e(
//                        TAG,
//                        "Error getting user document: ",
//                        exception
//                    )
//                }
//        }
//    }
//
//    private fun showEditNameDialog() {
//        val user = auth.currentUser ?: return
//        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_name, null)
//        val editTextNewFirstName = dialogView.findViewById<EditText>(R.id.editTextNewFirstName)
//        val editTextNewLastName = dialogView.findViewById<EditText>(R.id.editTextNewLastName)
//
//        editTextNewFirstName.setText(binding.textViewFirstName.text.takeIf { it != "N/A" })
//        editTextNewLastName.setText(binding.textViewLastName.text)
//
//        AlertDialog.Builder(this)
//            .setTitle("Edit Name")
//            .setView(dialogView)
//            .setPositiveButton("Save") { dialog, _ ->
//                val newFirstName = editTextNewFirstName.text.toString().trim()
//                val newLastName = editTextNewLastName.text.toString().trim()
//                if (newFirstName.isNotEmpty() || newLastName.isNotEmpty()) { // Allow one to be empty if desired
//                    saveUserName(user.uid, newFirstName, newLastName)
//                } else {
//                    Toast.makeText(this, "At least one name field is required.", Toast.LENGTH_SHORT)
//                        .show()
//                }
//                dialog.dismiss()
//            }
//            .setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
//            .create()
//            .show()
//    }
//
//    private fun saveUserName(userId: String, newFirstName: String, newLastName: String) {
//        val currentUserEmail = auth.currentUser?.email ?: ""
//        // val currentUserPhone = binding.textViewPhone.text.toString() // If you have phone
//
//        val userProfileData = hashMapOf<String, Any>(
//            "firstName" to newFirstName,
//            "lastName" to newLastName,
//            "email" to currentUserEmail
//            // "phone" to currentUserPhone
//        )
//
//        db.collection("users").document(userId)
//            .set(userProfileData, SetOptions.merge())
//            .addOnSuccessListener {
//                Log.d(TAG, "User profile updated in Firestore for UID: $userId")
//                Toast.makeText(this, "Name updated!", Toast.LENGTH_SHORT).show()
//                binding.textViewFirstName.text = newFirstName.ifEmpty { "N/A" }
//                binding.textViewLastName.text = newLastName
//
//                val profileUpdates = UserProfileChangeRequest.Builder()
//                    .setDisplayName(listOf(newFirstName, newLastName).filter { it.isNotEmpty() }
//                        .joinToString(" "))
//                    .build()
//                auth.currentUser?.updateProfile(profileUpdates)
//            }
//            .addOnFailureListener { e ->
//                Log.e(TAG, "Error updating user profile in Firestore: ", e)
//                Toast.makeText(this, "Error updating name: ${e.message}", Toast.LENGTH_LONG).show()
//            }
//    }
//
//    private fun showSignOutConfirmationDialog() {
//        AlertDialog.Builder(this)
//            .setTitle("Deconectare")
//            .setMessage("Sigur doriți să vă deconectați?")
//            .setPositiveButton("Deconectare") { _, _ -> signOutUser() }
//            .setNegativeButton("Anulare") { dialog, _ -> dialog.dismiss() } // Changed "Cancel" to "Anulare" for consistency
//            .show()
//    }
//
//    private fun signOutUser() {
//        Log.d(TAG, "Signing out user...")
//        // If biometric login is enabled, good practice to clear its specific data
//        // disableBiometricLogin() // This is now handled more broadly by clearing all prefs for the user in some apps.
//        // Or you might want to explicitly call it if you want to also delete the Keystore key.
//        // For now, assume a full sign out means the biometric token is invalid anyway.
//
//        auth.signOut()
//        // Clear any app-specific shared preferences related to the signed-out user if needed
//        // For example, if you stored the last logged-in UID for biometric prompt on LoginActivity:
//        // getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(PREF_LAST_LOGGED_IN_UID).apply()
//
//        val intent = Intent(this, LoginActivity::class.java)
//        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
//        startActivity(intent)
//        finishAffinity()
//    }
}