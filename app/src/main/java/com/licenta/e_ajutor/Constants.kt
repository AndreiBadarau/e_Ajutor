package com.licenta.e_ajutor

const val PREFS_NAME = "BiometricPrefs"
const val PREF_LAST_LOGGED_IN_UID = "lastLoggedInUid"

const val PREF_IS_BIOMETRIC_ENABLED_PREFIX = "isBiometricEnabled_"
// Assuming this is the correct one to use:
const val PREF_KEY_BIOMETRIC_KEY_ALIAS_PREFIX = "biometric_key_alias_"
// Remove or clarify usage of: const val PREF_BIOMETRIC_KEY_ALIAS_PREFIX = "biometricKeyAlias_"

// --- Prefixes for the main biometric data payload (idToken, email, etc.) ---
const val PREF_USERDATA_ENCRYPTED_SYM_KEY_PREFIX = "bio_enc_sym_key_userdata_"
const val PREF_USERDATA_ENCRYPTED_IV_PREFIX = "bio_iv_userdata_"
const val PREF_USERDATA_ENCRYPTED_DATA_PREFIX = "bio_enc_data_userdata_"

// --- Optional: Original/Legacy prefixes if used elsewhere for different data ---
// const val PREF_LEGACY_ENCRYPTED_SYM_KEY_PREFIX = "bio_enc_sym_key_"
// const val PREF_LEGACY_ENCRYPTED_IV_PREFIX = "bio_iv_"
// const val PREF_LEGACY_ENCRYPTED_DATA_PREFIX = "bio_enc_data_"