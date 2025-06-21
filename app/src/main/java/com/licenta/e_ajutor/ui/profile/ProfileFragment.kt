package com.licenta.e_ajutor

import BiometricCryptographyManager
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.LocationManager
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.licenta.e_ajutor.activity.LoginActivity
import com.licenta.e_ajutor.activity.MfaSetupActivity
import com.licenta.e_ajutor.databinding.FragmentProfileBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executor

class ProfileFragment : Fragment() {
    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private lateinit var placesClient: PlacesClient
    private var autocompleteSessionToken: AutocompleteSessionToken? = null
    private lateinit var addressSuggestionsAdapter: ArrayAdapter<String>
    private val addressSuggestionDetails = mutableListOf<AutocompletePrediction>() // To store Place IDs or full predictions


    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val TAG = "ProfileFragment"

    // This will be set by loadUserProfileAndSetRoleUI
    private var currentUserIsOperator: Boolean = false

    // Prefix constants usage
    private val userBioKeyAlias: String
        get() = "${PREF_KEY_BIOMETRIC_KEY_ALIAS_PREFIX}${auth.currentUser?.uid ?: "unknown_user"}"

    private val isBiometricEnabledPrefKey: String
        get() = "${PREF_IS_BIOMETRIC_ENABLED_PREFIX}${auth.currentUser?.uid ?: "unknown_user"}"

    private enum class LocationFetchPurpose {
        NONE,
        OPERATOR_SERVICE_AREA,
        USER_RESIDENTIAL_ADDRESS_FROM_GPS, // For later, if users can set their address via GPS
        GENERAL_REQUEST // For when user makes a help request
    }

    private var currentLocationFetchPurpose: LocationFetchPurpose = LocationFetchPurpose.NONE


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        auth = Firebase.auth // Initialize here
        db = Firebase.firestore   // Initialize here
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!Places.isInitialized()) {
            Log.e(TAG, "Places SDK not initialized! Make sure to initialize it in your Application class or an Activity.")
            // You might want to disable address input features if Places isn't initialized.
            // For now, we'll proceed assuming it will be.
            // Consider adding a fallback or error message if it's critical.
            // A robust way: initialize in Application class, then get client here.
            // For demo:
            // Places.initialize(requireContext().applicationContext, "YOUR_API_KEY_HERE_IF_NOT_IN_APP_CLASS")
        }
        placesClient = Places.createClient(requireContext())
        autocompleteSessionToken = AutocompleteSessionToken.newInstance() // Create a new token for each session

        // Setup for Operator Address Autocomplete
        setupAddressAutocomplete(
            binding.editTextOperatorAddress // Assuming this is an AutoCompleteTextView now
        )

        setupAddressAutocomplete(
            binding.editTextUserAddress
        )

        // auth and db are already initialized in onCreate
        executor = ContextCompat.getMainExecutor(requireContext())

        loadUserProfileAndSetRoleUI() // This function will now handle fetching profile and setting UI based on role
        updateBiometricSwitchState()

        binding.switchBiometricLogin.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) enableBiometricLogin() else disableBiometricLogin()
        }
        binding.buttonEditName.setOnClickListener { showEditNameDialog() }

        binding.buttonSetup2FA.setOnClickListener {
            val intent = Intent(requireContext(), MfaSetupActivity::class.java)
            startActivity(intent)
        }
        binding.buttonSignOut.setOnClickListener { showSignOutConfirmationDialog() }


        binding.buttonSaveOperatorLocation.setOnClickListener {
            if (currentUserIsOperator) { // Check the flag set by loadUserProfileAndSetRoleUI
                handleSaveOperatorLocation()
            } else {
                Log.d(
                    TAG,
                    "Save Operator Location button clicked by non-operator or role not yet determined."
                )
            }
        }

        binding.buttonUseCurrentGpsForOperator.setOnClickListener {
            if (currentUserIsOperator) {
                currentLocationFetchPurpose = LocationFetchPurpose.OPERATOR_SERVICE_AREA
                checkLocationPermissionAndFetch() // This will eventually call fetchLastLocation or fetchCurrentLocationOnce
            } else {
                Log.d(TAG, "Use GPS button clicked by non-operator")
            }

        }

        binding.buttonSaveUserAddress.setOnClickListener {
            handleSaveUserResidentialAddress()
        }

        binding.buttonUseCurrentGpsForUser.setOnClickListener {
            currentLocationFetchPurpose = LocationFetchPurpose.USER_RESIDENTIAL_ADDRESS_FROM_GPS
            checkLocationPermissionAndFetch()
        }

    }

    override fun onResume() {
        super.onResume()
        // If the user navigates away and comes back, or if biometric settings change outside,
        // it's good to refresh the biometric switch.
        // Role UI should be relatively static once set, but consider if it needs refresh here.
        updateBiometricSwitchState()
        // Optionally, re-check role if it can change dynamically without fragment recreation,
        // but typically it's set on creation/login.
        // loadUserProfileAndSetRoleUI() // Might be redundant if role doesn't change often while fragment is visible
    }

    private fun handleSaveUserResidentialAddress(){
        val addressText = binding.editTextUserAddress.text.toString().trim()
        if (addressText.isEmpty()) {
            binding.textInputLayoutUserAddress.error = "Adresa nu poate fi goala."
            Toast.makeText(requireContext(), "Adresa nu poate fi goala.", Toast.LENGTH_SHORT).show()
            return
        }
        binding.textInputLayoutUserAddress.error = null

        val geocoder = Geocoder(requireContext())
        val user = auth.currentUser

        if (user == null) {
            Toast.makeText(requireContext(), "User not logged in.", Toast.LENGTH_SHORT).show()
            return
        }

        // Show loading state
        binding.buttonSaveUserAddress.isEnabled = false
        binding.editTextUserAddress.isEnabled = false
        // Consider adding a ProgressBar: binding.yourProgressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val addresses: List<Address>? = withContext(Dispatchers.IO) {
                    geocoder.getFromLocationName(addressText, 1)
                }

                withContext(Dispatchers.Main) {
                    if (!addresses.isNullOrEmpty()) {
                        val location    = addresses[0]
                        val latitude    = location.latitude
                        val longitude   = location.longitude
                        val geoPoint    = GeoPoint(latitude, longitude)

                        // Extragem county, city
                        val county      = location.adminArea     ?: ""
                        val city        = location.locality     ?: ""

                        // Pregătim harta de update
                        val updates = mapOf(
                            "homeLocation" to geoPoint,
                            "county"       to county,
                            "city"         to city
                        )

                        db.collection("users").document(user.uid)
                            .update(updates)
                            .addOnSuccessListener {
                                Toast.makeText(requireContext(), "Locație utilizator salvată!", Toast.LENGTH_SHORT).show()
                                binding.textViewCurrentUserLocation.text =
                                    "Home: $county, $city"
                            }
                            .addOnFailureListener { e ->
                                Toast.makeText(requireContext(), "Eroare la salvare: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                    } else {
                        Toast.makeText(requireContext(), "Adresa nu a putut fi găsită.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Eroare geocoding: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    binding.buttonSaveUserAddress.isEnabled = true
                    binding.editTextUserAddress.isEnabled   = true
                }
            }
        }
    }

    private fun loadUserProfileAndSetRoleUI() {
        val user = auth.currentUser
        if (user == null) {
            Log.d(TAG, "No authenticated user found in ProfileFragment.")
            binding.operatorLocationSection.visibility = View.GONE
            binding.userAddressSection.visibility = View.GONE
            // Potentially navigate to login or show an error
            return
        }

        Log.d(TAG, "Loading profile for user: ${user.uid}")
        binding.textViewEmail.text = user.email ?: "N/A" // Set email early

        db.collection("users").document(user.uid).get()
            .addOnSuccessListener { document ->
                if (document != null && document.exists()) {
                    Log.d(TAG, "User document found in Firestore.")
                    // Assuming you have separate firstName and lastName fields in Firestore
                    // and corresponding TextViews (textViewFirstName, textViewLastName) in your layout
                    val firstName = document.getString("firstName") ?: ""
                    val lastName = document.getString("lastName") ?: ""

                    // If you only have one TextView for the full name like "textViewProfileName"
                    // val fullName = document.getString("name") ?: user.displayName ?: "N/A"
                    // binding.textViewProfileName.text = fullName

                    // Update this based on your actual layout xml
                    binding.textViewFirstName.text =
                        firstName // Example: if you have textViewFirstName
                    binding.textViewLastName.text =
                        lastName   // Example: if you have textViewLastName

                    val userRole = document.getString("role")
                    val isOperator = userRole == "operator"
                    currentUserIsOperator = isOperator // Set the member variable for button clicks
                    Log.d(
                        TAG,
                        "Fetched 'operator' field from Firestore: $isOperator. currentUserIsOperator set to $currentUserIsOperator"
                    )

                    if (isOperator) {
                        Log.d(TAG, "User is an OPERATOR. Showing operator section.")
                        binding.operatorLocationSection.visibility = View.VISIBLE
                        binding.userAddressSection.visibility = View.GONE
                        loadAndDisplayOperatorServiceLocation()
                    } else {
                        Log.d(TAG, "User is a REGULAR USER. Showing user address section.")
                        binding.operatorLocationSection.visibility = View.GONE
                        binding.userAddressSection.visibility = View.VISIBLE
                        loadAndDisplayUserResidentialAddress()
                    }
                } else {
                    Log.w(TAG, "No such user document for uid: ${user.uid}. Defaulting UI.")
                    currentUserIsOperator = false // Default if document doesn't exist
                    binding.operatorLocationSection.visibility = View.GONE
                    binding.userAddressSection.visibility = View.VISIBLE // Default to user view
                    // Potentially set name/email from auth object if Firestore profile is missing
                    binding.textViewFirstName.text = user.displayName ?: "User"
                    binding.textViewLastName.text = ""
                }
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Firestore get failed for user ${user.uid}", exception)
                currentUserIsOperator = false // Default on failure
                binding.operatorLocationSection.visibility = View.GONE
                binding.userAddressSection.visibility =
                    View.VISIBLE // Default to user view on error
                Toast.makeText(context, "Failed to load profile.", Toast.LENGTH_SHORT).show()
                // Set name/email from auth object as fallback
                binding.textViewFirstName.text = user.displayName ?: "User"
                binding.textViewLastName.text = ""
            }
    }

    private fun setupAddressAutocomplete(
        autoCompleteTextView: MaterialAutoCompleteTextView // Or AutoCompleteTextView
    ) {
        addressSuggestionsAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, mutableListOf<String>())
        autoCompleteTextView.setAdapter(addressSuggestionsAdapter)
        autoCompleteTextView.threshold = 1 // Start suggesting after 1 character

        autoCompleteTextView.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s.isNullOrEmpty() || s.length < autoCompleteTextView.threshold) {
                    addressSuggestionsAdapter.clear()
                    addressSuggestionDetails.clear()
                    return
                }
                fetchAddressPredictions(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString()
                Log.d(TAG, "Operator afterTextChanged: query = '$query'") // Check if this

                if (query.length >= 3) { // Or your desired threshold
                    Log.d(TAG, "Operator: Query long enough, attempting to fetch suggestions.")
                    val request = FindAutocompletePredictionsRequest.builder()
                        .setSessionToken(autocompleteSessionToken) // Ensure token is valid
                        .setQuery(query)
                        // .setCountries("US", "CA") // Optional: filter by countries
                        .build()

                    placesClient.findAutocompletePredictions(request)
                        .addOnSuccessListener { response ->
                            Log.d(TAG, "Operator: Successfully got suggestions for '$query'. Count: ${response.autocompletePredictions.size}")
                            // ... your logic to update adapter ...
                        }
                        .addOnFailureListener { exception ->
                            Log.e(TAG, "Operator: Failed to get suggestions for '$query'", exception)
                            // Check the type of exception. Is it an API key issue, network issue, or something else?
                        }
                } else {
                    // Clear suggestions if query is too short
                    Log.d(TAG, "Operator: Query too short, clearing suggestions.")
                    // ... clear adapter ...
                }}
        })

        autoCompleteTextView.setOnItemClickListener { parent, _, position, _ ->
            val selectedPrediction = addressSuggestionDetails.getOrNull(position)
            if (selectedPrediction != null) {
                val selectedAddress = selectedPrediction.getFullText(null).toString()
                autoCompleteTextView.setText(selectedAddress, false) // Set text without triggering watcher again
                Log.d(TAG, "Address selected: $selectedAddress, Place ID: ${selectedPrediction.placeId}")
                // You can now directly use this selectedAddress for geocoding
                // or fetch more place details using the placeId if needed for lat/lng
                // For now, handleSaveOperatorLocation will still use the text from the field.
            }
        }
    }


    private fun fetchAddressPredictions(query: String) {
        if (autocompleteSessionToken == null) {
            autocompleteSessionToken = AutocompleteSessionToken.newInstance() // Ensure token exists
        }

        val request = FindAutocompletePredictionsRequest.builder()
            .setCountries("RO") // Bias to Romania, for example. Add more as needed.
            // .setTypeFilter(TypeFilter.ADDRESS) // Or REGIONS, GEOCODE, ESTABLISHMENT
            .setSessionToken(autocompleteSessionToken)
            .setQuery(query)
            .build()

        placesClient.findAutocompletePredictions(request)
            .addOnSuccessListener { response ->
                addressSuggestionsAdapter.clear()
                addressSuggestionDetails.clear()
                val suggestions = mutableListOf<String>()
                for (prediction in response.autocompletePredictions) {
                    suggestions.add(prediction.getFullText(null).toString())
                    addressSuggestionDetails.add(prediction) // Store the full prediction object
                }
                addressSuggestionsAdapter.addAll(suggestions)
                addressSuggestionsAdapter.notifyDataSetChanged()
                Log.d(TAG, "Fetched ${suggestions.size} predictions for query: $query")
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Autocomplete prediction fetching failed", exception)
                // Handle error, maybe show a toast
            }
    }

    private fun loadAndDisplayOperatorServiceLocation() {
        val user = auth.currentUser ?: return
        Log.d(TAG, "loadAndDisplayOperatorServiceLocation for user: ${user.uid}")
        db.collection("users").document(user.uid).get()
            .addOnSuccessListener { document ->
                if (document != null && document.exists()) {
                    val serviceLocationGeoPoint = document.getGeoPoint("serviceLocation")
                    if (serviceLocationGeoPoint != null) {
                        Log.d(
                            TAG,
                            "Operator serviceLocation found: ${serviceLocationGeoPoint.latitude}, ${serviceLocationGeoPoint.longitude}"
                        )
                        binding.textViewCurrentOperatorLocation.text =
                            "Current Service Location: Lat ${serviceLocationGeoPoint.latitude}, Lng ${serviceLocationGeoPoint.longitude}"
                    } else {
                        Log.d(TAG, "Operator serviceLocation is null/not set.")
                        binding.textViewCurrentOperatorLocation.text =
                            "Current Service Location: Not Set"
                    }
                } else {
                    Log.w(TAG, "Operator document not found when trying to load service location.")
                    binding.textViewCurrentOperatorLocation.text =
                        "Current Service Location: Not Set (profile missing)"
                }
            }
            .addOnFailureListener {
                binding.textViewCurrentOperatorLocation.text =
                    "Current Service Location: Error loading"
                Log.e(TAG, "Error loading operator service location", it)
            }
    }

    private fun loadAndDisplayUserResidentialAddress() {
        val user = auth.currentUser ?: return
        Log.d(TAG, "loadAndDisplayUserResidentialAddress for user: ${user.uid}")
        db.collection("users").document(user.uid).get()
            .addOnSuccessListener { document ->
                if (document != null && document.exists()) {
                    val serviceLocationGeoPoint = document.getGeoPoint("homeLocation")
                    if (serviceLocationGeoPoint != null) {
                        Log.d(
                            TAG,
                            "User Home Location found: ${serviceLocationGeoPoint.latitude}, ${serviceLocationGeoPoint.longitude}"
                        )
                        binding.textViewCurrentUserLocation.text =
                            "Current Home Residential Address: Lat ${serviceLocationGeoPoint.latitude}, Lng ${serviceLocationGeoPoint.longitude}"
                    } else {
                        Log.d(TAG, "Home Residential Address is null/not set.")
                        binding.textViewCurrentOperatorLocation.text =
                            "Current Home Residential Address: Not Set"
                    }
                } else {
                    Log.w(TAG, "User document not found when trying to load Home Residential Address.")
                    binding.textViewCurrentOperatorLocation.text =
                        "Current Home Residential Address: Not Set (profile missing)"
                }
            }
            .addOnFailureListener {
                binding.textViewCurrentOperatorLocation.text =
                    "Current Home Residential Address: Error loading"
                Log.e(TAG, "Error loading user Home Residential Address", it)
            }
    }

    private fun handleSaveOperatorLocation() {
        val addressText = binding.editTextOperatorAddress.text.toString().trim()
        if (addressText.isEmpty()) {
            binding.textInputLayoutOperatorAddress.error = "Address cannot be empty"
            Toast.makeText(requireContext(), "Address cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }
        binding.textInputLayoutOperatorAddress.error = null

        val geocoder = Geocoder(requireContext())
        val user = auth.currentUser

        if (user == null) {
            Toast.makeText(requireContext(), "User not logged in.", Toast.LENGTH_SHORT).show()
            return
        }

        // Show loading state
        binding.buttonSaveOperatorLocation.isEnabled = false
        binding.editTextOperatorAddress.isEnabled = false
        // Consider adding a ProgressBar: binding.yourProgressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val addresses: List<Address>? = withContext(Dispatchers.IO) {
                    geocoder.getFromLocationName(addressText, 1)
                }
                withContext(Dispatchers.Main) {
                    if (!addresses.isNullOrEmpty()) {
                        val location    = addresses[0]
                        val latitude    = location.latitude
                        val longitude   = location.longitude
                        val geoPoint    = GeoPoint(latitude, longitude)

                        val county      = location.adminArea    ?: ""
                        val city        = location.locality    ?: ""

                        val updates = mapOf(
                            "serviceLocation" to geoPoint,
                            "county"          to county,
                            "city"            to city
                        )

                        db.collection("users").document(user.uid)
                            .update(updates)
                            .addOnSuccessListener {
                                Toast.makeText(requireContext(), "Locație operator salvată!", Toast.LENGTH_SHORT).show()
                                binding.textViewCurrentOperatorLocation.text =
                                    "Service: $county, $city"
                            }
                            .addOnFailureListener { e ->
                                Toast.makeText(requireContext(), "Eroare la salvare: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                    } else {
                        Toast.makeText(requireContext(), "Adresa operator nu a putut fi găsită.", Toast.LENGTH_LONG).show()
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    binding.buttonSaveOperatorLocation.isEnabled = true
                    binding.editTextOperatorAddress.isEnabled   = true
                }
            }
        }
    }
    private fun handleUseCurrentGpsForOperator() {
        Toast.makeText(
            requireContext(),
            "Using current GPS for operator (Not Implemented Yet)",
            Toast.LENGTH_LONG
        ).show()
        Log.d(TAG, "Attempting to use current GPS for operator")
        // TODO: Reuse checkLocationPermissionAndFetch and then save to Firestore
        // You'll need a way to know that the location fetched is for the operator's service area
        // e.g., pass a parameter to checkLocationPermissionAndFetch or set a flag.
        // For now, let's just call it:
        // checkLocationPermissionAndFetch() // Then handle the result appropriately
    }

    private val requestLocationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d(TAG, "Location permission granted (callback)")
                // TODO: Decide if this location is for general use, user's request, or operator service area
                fetchLastLocation() // Or a more specific function
            } else {
                Log.d(TAG, "Location permission denied (callback)")
                Toast.makeText(
                    requireContext(),
                    "Location permission denied. Cannot fetch location.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private fun checkLocationPermissionAndFetch() {
        val locationManager =
            requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
            !locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        ) {
            Log.w(TAG, "Location services are disabled by the user.")
            AlertDialog.Builder(requireContext())
                .setMessage("Location services are required. Enable them in settings?")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        when {
            ContextCompat.checkSelfPermission(
                requireContext(),
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED -> {
                Log.d(TAG, "Permission already granted, fetching location.")
                // TODO: Differentiate why location is being fetched.
                fetchLastLocation()
            }

            shouldShowRequestPermissionRationale(android.Manifest.permission.ACCESS_FINE_LOCATION) -> {
                Log.d(TAG, "Showing permission rationale.")
                AlertDialog.Builder(requireContext())
                    .setTitle("Location Permission Needed")
                    .setMessage("This app needs Location permission for various features. Please grant the permission.")
                    .setPositiveButton("OK") { _, _ ->
                        requestLocationPermissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                    .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
                    .create().show()
            }

            else -> {
                Log.d(TAG, "Requesting location permission.")
                requestLocationPermissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }


    @SuppressLint("MissingPermission")
    private fun fetchLastLocation() {
        Log.d(TAG, "Attempting to get last known location...")
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                if (location != null) {
                    val latitude = location.latitude
                    val longitude = location.longitude
                    Log.d(TAG, "Last known location: Lat $latitude, Lng $longitude. Purpose: $currentLocationFetchPurpose")
                    handleFetchedLocation(latitude, longitude)
                } else {
                    Log.d(TAG, "Last known location is null. Requesting current location (once). Purpose: $currentLocationFetchPurpose")
                    fetchCurrentLocationOnce() // Fetch a fresh one
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to get last location", e)
                Toast.makeText(requireContext(), "Failed to get last location.", Toast.LENGTH_SHORT).show()
                resetLocationFetchPurpose()
            }
    }

    @SuppressLint("MissingPermission")
    private fun fetchCurrentLocationOnce() {
        Log.d(TAG, "Attempting to get current location (once)... Purpose: $currentLocationFetchPurpose")
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (location != null) {
                    val latitude = location.latitude
                    val longitude = location.longitude
                    Log.d(TAG, "Current location (once): Lat $latitude, Lng $longitude. Purpose: $currentLocationFetchPurpose")
                    handleFetchedLocation(latitude, longitude)
                } else {
                    Log.w(TAG, "Failed to get current location (once), result was null.")
                    Toast.makeText(requireContext(), "Could not get current location. Ensure location is ON.", Toast.LENGTH_LONG).show()
                    resetLocationFetchPurpose()
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to get current location (once)", e)
                Toast.makeText(requireContext(), "Failed to get current location.", Toast.LENGTH_SHORT).show()
                resetLocationFetchPurpose()
            }
    }

    private fun handleFetchedLocation(latitude: Double, longitude: Double) {
        val user = auth.currentUser
        if (user == null) {
            Toast.makeText(requireContext(), "User not logged in.", Toast.LENGTH_SHORT).show()
            resetLocationFetchPurpose()
            return
        }

        when (currentLocationFetchPurpose) {
            LocationFetchPurpose.OPERATOR_SERVICE_AREA -> {
                val geoPoint = GeoPoint(latitude, longitude)
                db.collection("users").document(user.uid)
                    .update("serviceLocation", geoPoint)
                    .addOnSuccessListener {
                        Log.d(TAG, "Operator service location updated via GPS successfully.")
                        Toast.makeText(requireContext(), "Service location set to current GPS!", Toast.LENGTH_SHORT).show()
                        binding.textViewCurrentOperatorLocation.text = "Current Service Location: Lat ${"%.4f".format(latitude)}, Lng ${"%.4f".format(longitude)}"
                        reverseGeocodeLocation(latitude, longitude, forOperator = true)
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Error saving service location from GPS", e)
                        Toast.makeText(requireContext(), "Failed to save GPS location: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    .addOnCompleteListener {
                        resetLocationFetchPurpose()
                    }
            }
            LocationFetchPurpose.USER_RESIDENTIAL_ADDRESS_FROM_GPS -> {
                val geoPoint = GeoPoint(latitude, longitude)
                db.collection("users").document(user.uid)
                    .update("userLocation", geoPoint)
                    .addOnSuccessListener {
                        Log.d(TAG, "User residential address updated via GPS successfully.")
                        Toast.makeText(requireContext(), "User location set to current GPS!", Toast.LENGTH_SHORT).show()
                        binding.textViewCurrentUserLocation.text = "Current Home Location: Lat ${"%.4f".format(latitude)}, Lng ${"%.4f".format(longitude)}"
                        reverseGeocodeLocation(latitude, longitude, forOperator = false)
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Error saving user location from GPS", e)
                        Toast.makeText(requireContext(), "Failed to save GPS location: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    .addOnSuccessListener {
                        resetLocationFetchPurpose()
                    }
            }
            LocationFetchPurpose.GENERAL_REQUEST -> {
                // This would be handled when a user submits a new help request, not directly in profile
                Log.d(TAG, "Location fetched for a general request (Not implemented in profile).")
                Toast.makeText(requireContext(), "General GPS location: Lat $latitude, Lng $longitude", Toast.LENGTH_LONG).show()
                resetLocationFetchPurpose()
            }
            LocationFetchPurpose.NONE -> {
                // Location fetched without a specific purpose (e.g., from the generic "Get Location" button)
                Log.d(TAG, "Location fetched without specific purpose: Lat $latitude, Lng $longitude")
                Toast.makeText(requireContext(), "Location: Lat $latitude, Lng $longitude (Purpose: None)", Toast.LENGTH_LONG).show()
                // binding.textViewCurrentLocation.text = "Lat: $latitude, Lng: $longitude" // Example display
            }
        }
    }

    private fun resetLocationFetchPurpose() {
        currentLocationFetchPurpose = LocationFetchPurpose.NONE
    }

    // Optional: Reverse Geocoding function
    private fun reverseGeocodeLocation(latitude: Double, longitude: Double, forOperator: Boolean) {
        val geocoder = Geocoder(requireContext())
        try {
            // As before, this should be off the main thread for production
            val addresses: List<Address>? = geocoder.getFromLocation(latitude, longitude, 1)
            if (addresses != null && addresses.isNotEmpty()) {
                val address = addresses[0]
                val addressText = address.getAddressLine(0) ?: "Address not found"
                Log.d(TAG, "Reverse geocoded to: $addressText")
                if (forOperator) {
                    binding.textViewCurrentOperatorLocation.text = "Current Service Location: $addressText"
                } else {
                    // Update UI for user's residential address if implementing that
                }
            } else {
                if (forOperator) {
                    binding.textViewCurrentOperatorLocation.text = "Current Service Location: Lat ${"%.4f".format(latitude)}, Lng ${"%.4f".format(longitude)} (Address not found)"
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Reverse geocoding failed", e)
            if (forOperator) {
                binding.textViewCurrentOperatorLocation.text = "Current Service Location: Lat ${"%.4f".format(latitude)}, Lng ${"%.4f".format(longitude)} (Error finding address)"
            }
        }
    }

    // Make sure these functions are present from your original code
    private fun enableBiometricLogin() {
        Log.d(TAG, "enableBiometricLogin called.")
        val biometricManager = BiometricManager.from(requireContext())
        val authResult = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
        )
        Log.d(
            TAG,
            "BiometricManager.canAuthenticate result: $authResult (0 means BIOMETRIC_SUCCESS)"
        )

        when (authResult) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                Log.d(TAG, "App can authenticate using biometrics. Showing prompt for setup...")
                // This prompt is just to confirm user's intent.
                // The actual RSA key pair generation/retrieval for encryption happens in storeEncryptedTokenAfterBioAuth
                setupBiometricPrompt(forSetup = true) // This sets up the prompt for user confirmation
                biometricPrompt.authenticate(promptInfo) // Authenticate to confirm setup
            }

            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                Log.e(TAG, "No biometric features available on this device.")
                Toast.makeText(requireContext(), "No biometric features available.", Toast.LENGTH_LONG).show()
                binding.switchBiometricLogin.isChecked = false
            }

            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                Log.e(TAG, "Biometric features are currently unavailable.")
                Toast.makeText(requireContext(), "Biometric features unavailable.", Toast.LENGTH_LONG).show()
                binding.switchBiometricLogin.isChecked = false
            }

            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                Log.e(TAG, "No biometrics enrolled.")
                Toast.makeText(
                    requireContext(),
                    "No biometrics enrolled. Please set up in device settings.",
                    Toast.LENGTH_LONG
                ).show()
                binding.switchBiometricLogin.isChecked = false
            }

            else -> {
                Log.e(TAG, "Biometric authentication unavailable. Code: $authResult")
                Toast.makeText(requireContext(), "Biometric authentication unavailable.", Toast.LENGTH_LONG)
                    .show()
                binding.switchBiometricLogin.isChecked = false
            }
        }
    }

    private fun setupBiometricPrompt(forSetup: Boolean = true) {
        biometricPrompt = BiometricPrompt(
            this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Log.e(
                        TAG,
                        "BiometricPrompt AuthError. Code: $errorCode, Msg: $errString, Setup: $forSetup"
                    )
                    Toast.makeText(
                        requireContext(),
                        "Authentication error: $errString",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (forSetup) {
                        Log.d(TAG, "Auth error during setup. Setting switch to false.")
                        binding.switchBiometricLogin.isChecked = false
                    }
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    Log.d(TAG, "BiometricPrompt AuthSucceeded. Setup: $forSetup")
                    Toast.makeText(
                        requireContext(),
                        "Biometric Authentication succeeded!",
                        Toast.LENGTH_SHORT
                    ).show()

                    if (forSetup) {
                        // The BiometricPrompt for setup doesn't need a CryptoObject for RSA key usage
                        // The RSA public key is used directly for encryption.
                        Log.d(TAG, "Proceeding to storeEncryptedTokenAfterBioAuth...")
                        storeEncryptedTokenAfterBioAuth()
                    }
                    // If !forSetup, this callback would be for LoginActivity, which handles its CryptoObject.
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Log.w(TAG, "BiometricPrompt AuthFailed. Setup: $forSetup")
                    Toast.makeText(requireContext(), "Authentication failed", Toast.LENGTH_SHORT)
                        .show()
                    if (forSetup) {
                        binding.switchBiometricLogin.isChecked = false
                    }
                }
            })

        // PromptInfo for initial biometric setup (just confirming user presence)
        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Biometric Login Setup")
            .setSubtitle("Confirm fingerprint/face to enable biometric login")
            .setNegativeButtonText("Cancel")
            .build()
    }

    private fun storeEncryptedTokenAfterBioAuth() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Toast.makeText(requireContext(), "User not logged in.", Toast.LENGTH_SHORT).show()
            binding.switchBiometricLogin.isChecked = false
            Log.w(TAG, "storeEncryptedTokenAfterBioAuth: User is null.")
            return
        }

        val userId = currentUser.uid
        val userEmail = currentUser.email // Get email here

        Log.d(TAG, "storeEncryptedTokenAfterBioAuth: Attempting for UID: $userId")

        currentUser.getIdToken(true).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val idToken = task.result?.token
                if (idToken != null) {
                    // Use the userBioKeyAlias getter which is already defined for the current user
                    val currentRsaKeyAlias = userBioKeyAlias

                    // Get or create RSA public key.
                    val rsaPublicKey = BiometricCryptographyManager.getPublicKey(currentRsaKeyAlias)

                    if (rsaPublicKey != null) {
                        // 2. Create a JSON object with user data (ID Token and Email)
                        val userDataMap = mutableMapOf<String, String?>()
                        userDataMap["idToken"] = idToken
                        userDataMap["email"] = userEmail // This can be null if userEmail was null

                        val userDataJson = JSONObject(userDataMap).toString()
                        Log.d(
                            TAG,
                            "User data JSON to encrypt in storeEncryptedTokenAfterBioAuth: $userDataJson"
                        )

                        // 3. Encrypt the JSON string
                        val encryptedUserPayload = BiometricCryptographyManager.encryptDataHybrid(
                            userDataJson,
                            rsaPublicKey
                        )

                        if (encryptedUserPayload != null) {
                            val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            val editor = prefs.edit()

                            // STORE USING THE "_userdata_" KEYS
                            editor.putString(
                                "bio_enc_sym_key_userdata_$userId",
                                encryptedUserPayload.encryptedSymmetricKey
                            )
                            editor.putString("bio_iv_userdata_$userId", encryptedUserPayload.iv)
                            editor.putString(
                                "bio_enc_data_userdata_$userId",
                                encryptedUserPayload.encryptedData
                            )

                            // Store other necessary flags
                            editor.putString(
                                PREF_KEY_BIOMETRIC_KEY_ALIAS_PREFIX + userId,
                                currentRsaKeyAlias
                            ) // Use the one with underscores
                            editor.putBoolean(
                                isBiometricEnabledPrefKey,
                                true
                            ) // isBiometricEnabledPrefKey uses the getter, which is fine
                            editor.putString(PREF_LAST_LOGGED_IN_UID, userId)
                            editor.apply()

                            Toast.makeText(requireContext(), "Biometric login enabled.", Toast.LENGTH_SHORT)
                                .show()
                            Log.i(
                                TAG,
                                "SUCCESS: Biometric setup complete and all preferences stored for UID: $userId using _userdata_ keys."
                            )
                            Log.d(
                                TAG,
                                "  - Encrypted Symmetric Key (userdata) stored under: bio_enc_sym_key_userdata_$userId"
                            )
                            Log.d(TAG, "  - IV (userdata) stored under: bio_iv_userdata_$userId")
                            Log.d(
                                TAG,
                                "  - Encrypted Data (userdata) stored under: bio_enc_data_userdata_$userId"
                            )
                            Log.d(
                                TAG,
                                "  - Biometric key alias stored under: ${PREF_KEY_BIOMETRIC_KEY_ALIAS_PREFIX + userId}"
                            )
                            Log.d(
                                TAG,
                                "  - Biometric enabled flag stored under: $isBiometricEnabledPrefKey"
                            )
                            Log.d(TAG, "  - PREF_LAST_LOGGED_IN_UID set to: $userId")

                        } else {
                            Toast.makeText(
                                requireContext(),
                                "Failed to encrypt user data payload.",
                                Toast.LENGTH_SHORT
                            ).show()
                            Log.e(
                                TAG,
                                "storeEncryptedTokenAfterBioAuth: encryptDataHybrid returned null for UID: $userId"
                            )
                            binding.switchBiometricLogin.isChecked = false
                            BiometricCryptographyManager.deleteInvalidKey(currentRsaKeyAlias) // Clean up key if encryption failed
                        }
                    } else {
                        Toast.makeText(
                            requireContext(),
                            "Failed to get/create RSA encryption key.",
                            Toast.LENGTH_SHORT
                        ).show()
                        Log.e(
                            TAG,
                            "storeEncryptedTokenAfterBioAuth: rsaPublicKey was null for alias '$currentRsaKeyAlias' for UID: $userId"
                        )
                        binding.switchBiometricLogin.isChecked = false
                    }
                } else {
                    Toast.makeText(requireContext(), "Failed to get Firebase ID token.", Toast.LENGTH_SHORT)
                        .show()
                    Log.e(
                        TAG,
                        "storeEncryptedTokenAfterBioAuth: Firebase ID token was null for UID: $userId"
                    )
                    binding.switchBiometricLogin.isChecked = false
                }
            } else {
                Toast.makeText(
                    requireContext(),
                    "Error getting Firebase ID token: ${task.exception?.message}",
                    Toast.LENGTH_SHORT
                ).show()
                Log.e(
                    TAG,
                    "storeEncryptedTokenAfterBioAuth: Error getting Firebase ID token for UID: $userId",
                    task.exception
                )
                binding.switchBiometricLogin.isChecked = false
            }
        }
    }


    private fun disableBiometricLogin() {
        Log.d(TAG, "disableBiometricLogin called.")
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentUserId = auth.currentUser?.uid

        if (currentUserId == null) {
            Log.w(TAG, "Cannot disable biometric login, user is null.")
            binding.switchBiometricLogin.isChecked = false // Ensure switch is off
            return
        }

        Log.d(TAG, "Removing biometric preferences for UID: $currentUserId")
        prefs.edit()
            // Use the correct keys for the idToken/email payload
            .remove("${PREF_USERDATA_ENCRYPTED_SYM_KEY_PREFIX}$currentUserId")
            .remove("${PREF_USERDATA_ENCRYPTED_IV_PREFIX}$currentUserId")
            .remove("${PREF_USERDATA_ENCRYPTED_DATA_PREFIX}$currentUserId")
            // This is correct
            .putBoolean(isBiometricEnabledPrefKey, false)
            // Optionally, also remove the stored alias if you don't need it after disabling
            .remove("${PREF_KEY_BIOMETRIC_KEY_ALIAS_PREFIX}$currentUserId")
            .apply()

        // Also delete the corresponding RSA key from Android Keystore
        BiometricCryptographyManager.deleteInvalidKey(userBioKeyAlias) // userBioKeyAlias getter is fine

        Toast.makeText(requireContext(), "Biometric login disabled.", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Biometric login disabled for UID: $currentUserId")
        binding.switchBiometricLogin.isChecked = false // Ensure switch reflects the state
    }


    private fun updateBiometricSwitchState() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentUid = auth.currentUser?.uid
        if (currentUid == null) {
            Log.e(TAG, "updateBiometricSwitchState: Current user is null.")
            binding.switchBiometricLogin.isChecked = false
            return
        }

        val isEnabledKey = isBiometricEnabledPrefKey
        val isEnabled = prefs.getBoolean(isEnabledKey, false)
        Log.d(
            TAG,
            "updateBiometricSwitchState: For UID '$currentUid', pref key '$isEnabledKey', isEnabled: $isEnabled"
        )
        binding.switchBiometricLogin.isChecked = isEnabled

        if (isEnabled) {
            // Further check if hardware is still available and biometrics are enrolled
            val biometricManager = BiometricManager.from(requireContext())
            val canAuth = biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
            )
            Log.d(
                TAG,
                "updateBiometricSwitchState: Biometrics were enabled. Hardware status: $canAuth"
            )
            if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
                Log.w(
                    TAG,
                    "Biometrics were enabled but hardware status changed (Code: $canAuth). Disabling."
                )
                Toast.makeText(
                    requireContext(),
                    "Biometric status changed. Disabling feature.",
                    Toast.LENGTH_LONG
                ).show()
                disableBiometricLogin() // This will also update the switch by setting the pref to false
            }
        }
    }


    private fun showEditNameDialog() {
        val user = auth.currentUser ?: return

        val viewInflated = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_edit_name, view as ViewGroup?, false)
        val editTextNewFirstName = viewInflated.findViewById<EditText>(R.id.editTextNewFirstName)
        val editTextNewLastName = viewInflated.findViewById<EditText>(R.id.editTextNewLastName)

        // Pre-fill with current names
        editTextNewFirstName.setText(binding.textViewFirstName.text) // Assuming you have textViewFirstName
        editTextNewLastName.setText(binding.textViewLastName.text)   // Assuming you have textViewLastName

        AlertDialog.Builder(requireContext())
            .setTitle("Edit Name")
            .setView(viewInflated)
            .setPositiveButton("Save") { dialog, _ ->
                val newFirstName = editTextNewFirstName.text.toString().trim()
                val newLastName = editTextNewLastName.text.toString().trim()

                if (newFirstName.isNotEmpty() || newLastName.isNotEmpty()) { // Or other validation
                    saveUserName(user.uid, newFirstName, newLastName)
                } else {
                    Toast.makeText(context, "Names cannot be empty.", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.cancel()
            }
            .show()
    }

    private fun saveUserName(userId: String, firstName: String, lastName: String) {
        val userProfileUpdates = hashMapOf(
            "firstName" to firstName,
            "lastName" to lastName
            // "name" to "$firstName $lastName" // Optional: if you also store a combined name
        )

        db.collection("users").document(userId)
            .set(
                userProfileUpdates,
                SetOptions.merge()
            ) // Use merge to avoid overwriting other fields like 'operator' or 'email'
            .addOnSuccessListener {
                Log.d(TAG, "User name updated successfully in Firestore.")
                binding.textViewFirstName.text = firstName
                binding.textViewLastName.text = lastName
                Toast.makeText(requireContext(), "Name updated!", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error updating user name", e)
                Toast.makeText(
                    requireContext(),
                    "Failed to update name: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
    }


    private fun showSignOutConfirmationDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Deconectare")
            .setMessage("Sigur doriți să vă deconectați?")
            .setPositiveButton("Deconectare") { _, _ -> signOutUser() }
            .setNegativeButton("Anulare", null)
            .show()
    }

    private fun signOutUser() {
        Log.d(TAG, "Signing out user.")
        auth.signOut()
        // Clear any sensitive SharedPreferences if needed upon sign-out
        // Example: Clear biometric preference for the signed-out user if it's not tied to a specific key alias that gets deleted.
        // However, disableBiometricLogin() should handle clearing its own prefs.

        val intent = Intent(requireContext(), LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        activity?.finish() // Finish the current activity (e.g., OperatorDashboardActivity or MainUserActivity)
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null // Important to prevent memory leaks
        Log.d(TAG, "onDestroyView called, binding set to null.")
    }
}