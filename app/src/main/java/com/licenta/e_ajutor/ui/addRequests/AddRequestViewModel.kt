package com.licenta.e_ajutor.ui.addRequests

import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.licenta.e_ajutor.model.BenefitType
import com.licenta.e_ajutor.model.DocumentRequirement
import com.licenta.e_ajutor.model.UserRequest
import com.licenta.e_ajutor.util.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

typealias CountResult = Pair<String, Int>

class AddRequestViewModel : ViewModel() {

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
    private val storage: FirebaseStorage = FirebaseStorage.getInstance()

    // Unique request ID generated once per ViewModel instance
    val requestId: String = db.collection("requests").document().id

    // --- LiveData for UI state ---
    private val _benefitTypes = MutableLiveData<List<BenefitType>>()
    val benefitTypes: LiveData<List<BenefitType>> = _benefitTypes

    private val _selectedBenefitType = MutableLiveData<BenefitType?>()
    val selectedBenefitType: LiveData<BenefitType?> = _selectedBenefitType

    // docId to Storage URL
    private val _uploadedDocuments = MutableLiveData<Map<String, String>>(emptyMap())
    val uploadedDocuments: LiveData<Map<String, String>> = _uploadedDocuments

    private val _uploadStatusText = MutableLiveData<String>("No file selected")
    val uploadStatusText: LiveData<String> = _uploadStatusText

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _uiToastEvent = MutableLiveData<Event<String>>()
    val uiToastEvent: LiveData<Event<String>> = _uiToastEvent

    private val _navigateToSeeRequestsEvent = MutableLiveData<Event<Unit>>()
    val navigateToSeeRequestsEvent: LiveData<Event<Unit>> = _navigateToSeeRequestsEvent

    init {
        loadBenefitTypes()
    }

    //TODO: Remove this and use real data
    fun loadBenefitTypes() {
        _benefitTypes.value = listOf(
            BenefitType(
                "unemployment", "Unemployment Benefit", listOf(
                    DocumentRequirement("id_card", "ID Card"),
                    DocumentRequirement("proof_of_employment", "Proof of Employment")
                )
            ),
            BenefitType(
                "child_support", "Child Support", listOf(
                    DocumentRequirement("birth_certificate", "Birth Certificate"),
                    DocumentRequirement("parent_id", "Parent ID")
                )
            )
        )
        // Auto-select first if available
        if (_benefitTypes.value?.isNotEmpty() == true) {
            // selectBenefitType(_benefitTypes.value!!.first()) // Fragment will handle initial UI update based on observer
        }
    }

    fun onBenefitTypeSelected(benefitType: BenefitType) {
        if (_selectedBenefitType.value?.id != benefitType.id) {
            _selectedBenefitType.value = benefitType
            _uploadedDocuments.value = emptyMap() // Clear previous uploads for new type
            _uploadStatusText.value = "No file selected"
        }
    }

    fun uploadFile(docId: String, uri: Uri) {
        val userId = auth.currentUser?.uid ?: run {
            _uiToastEvent.value = Event("User not logged in.")
            return
        }
        _isLoading.value = true
        _uploadStatusText.value = "Uploading ${docId}..."

        viewModelScope.launch {
            try {
                val storageRef = storage.reference.child("documents/$userId/$requestId/$docId")
                storageRef.putFile(uri).await() // Suspending call
                val downloadUri = storageRef.downloadUrl.await() // Suspending call

                val currentUploads = _uploadedDocuments.value?.toMutableMap() ?: mutableMapOf()
                currentUploads[docId] = downloadUri.toString()
                _uploadedDocuments.value = currentUploads // This will trigger observer in fragment
                _uploadStatusText.value = "Uploaded: $docId"
            } catch (e: Exception) {
                _uploadStatusText.value = "Upload failed for $docId"
                _uiToastEvent.value = Event("Upload failed for $docId: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deleteDocument(docId: String, callbackOnSuccess: () -> Unit) {
        val userId = auth.currentUser?.uid ?: run {
            _uiToastEvent.value = Event("User not logged in.")
            return
        }
        _isLoading.value = true
        _uploadStatusText.value = "Deleting ${docId}..."

        viewModelScope.launch {
            try {
                val storageRef = storage.reference.child("documents/$userId/$requestId/$docId")
                storageRef.delete().await()

                val currentUploads = _uploadedDocuments.value?.toMutableMap() ?: mutableMapOf()
                currentUploads.remove(docId)
                _uploadedDocuments.value = currentUploads
                _uploadStatusText.value = "Deleted: $docId. Select new file."
                _uiToastEvent.value = Event("Previous file for $docId deleted.")
                callbackOnSuccess() // To re-launch picker
            } catch (e: Exception) {
                _uploadStatusText.value = "Deletion failed for $docId"
                _uiToastEvent.value = Event("Deletion failed for $docId: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }


    private suspend fun findLeastLoadedOperatorByCounty(countyParam: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val opSnap = db.collection("users")
                    .whereEqualTo("role", "operator")
                    .whereEqualTo("county", countyParam)
                    .get()
                    .await()

                if (opSnap.isEmpty) {
                    return@withContext null
                }

                val countTasks = opSnap.documents.map { opDoc ->
                    val opId = opDoc.id
                    // Launch each count query asynchronously
                    viewModelScope.async { // Use async if you want to run these in parallel and await all
                        val requestCountSnap = db.collection("requests")
                            .whereEqualTo("operatorId", opId)
                            .whereEqualTo("status", "pending")
                            .get()
                            .await()
                        opId to (requestCountSnap.size())
                    }
                }
                // Await all async tasks and get their results
                val results =
                    countTasks.map { it.await() } // This was kotlinx.coroutines.awaitAll but it's simpler to map and await
                return@withContext results.minByOrNull { it.second }?.first

            } catch (e: Exception) {
                // Log error e
                _uiToastEvent.postValue(Event("Error finding operator: ${e.message}")) // Use postValue if on background thread
                return@withContext null
            }
        }


    fun submitRequest(
        benefitTypeIdFromSpinner: String, // Or pass the full BenefitType object
        iban: String,
        extraInfo: String,
        gdprAgreed: Boolean
    ) {
        if (!gdprAgreed) {
            _uiToastEvent.value = Event("Please agree to GDPR terms to submit.")
            return
        }

        val currentSelectedBenefit = _selectedBenefitType.value
        if (currentSelectedBenefit == null || currentSelectedBenefit.id != benefitTypeIdFromSpinner) {
            _uiToastEvent.value = Event("Benefit type selection error. Please re-select.")
            return
        }

        val requiredDocIds = currentSelectedBenefit.requiredDocuments.map { it.id }
        val currentUploadedDocsMap = _uploadedDocuments.value ?: emptyMap()
        val missingDocs = requiredDocIds.filter { !currentUploadedDocsMap.containsKey(it) }

        if (missingDocs.isNotEmpty()) {
            val missingDocNames = missingDocs.joinToString { docId ->
                currentSelectedBenefit.requiredDocuments.find { it.id == docId }?.displayName
                    ?: docId
            }
            _uiToastEvent.value = Event("Please upload all required documents: $missingDocNames")
            return
        }

        val userId = auth.currentUser?.uid ?: run {
            _uiToastEvent.value = Event("User not logged in. Cannot submit request.")
            return
        }

        _isLoading.value = true
        viewModelScope.launch {
            try {
                val userDoc = db.collection("users").document(userId).get().await()
                val userCounty = userDoc.getString("county")

                if (userCounty.isNullOrBlank()) {
                    _uiToastEvent.value =
                        Event("Your county is not set in your profile. Please update it.")
                    _isLoading.value = false
                    return@launch
                }

                val operatorId = findLeastLoadedOperatorByCounty(userCounty)
                if (operatorId.isNullOrEmpty()) {
                    _uiToastEvent.value =
                        Event("No operator currently available in $userCounty. Please try again later.")
                    _isLoading.value = false
                    return@launch
                }

                val request = UserRequest(
                    id = requestId, // Use ViewModel's requestId
                    userId = userId,
                    userName = userDoc.getString("firstName") + " " + userDoc.getString("lastName") ?: "",
                    operatorId = operatorId,
                    benefitTypeId = currentSelectedBenefit.id,
                    benefitTypeName = currentSelectedBenefit.name,
                    location = userCounty,
                    documentLinks = currentUploadedDocsMap,
                    status = "pending",
                    timestamp = Timestamp.now(),
                    iban = iban.trim(),
                    extraInfo = extraInfo.trim()
                )

                db.collection("requests").document(requestId).set(request).await()
                _uiToastEvent.value = Event("Request sent successfully to operator $operatorId!")
                _navigateToSeeRequestsEvent.value = Event(Unit)

                // Optionally reset form state after successful submission
                // _selectedBenefitType.value = null // This will trigger observers to clear UI
                // _uploadedDocuments.value = emptyMap()

            } catch (e: Exception) {
                _uiToastEvent.value = Event("Submission failed: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }
}