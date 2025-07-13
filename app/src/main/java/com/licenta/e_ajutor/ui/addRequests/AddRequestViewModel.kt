package com.licenta.e_ajutor.ui.addRequests

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.licenta.e_ajutor.model.BenefitType
import com.licenta.e_ajutor.model.DocumentRequirement
import com.licenta.e_ajutor.model.UserRequest
import com.licenta.e_ajutor.network.AiChatMessage
import com.licenta.e_ajutor.network.ChatRequest
import com.licenta.e_ajutor.network.OpenAiClient
import com.licenta.e_ajutor.util.Event
import com.licenta.e_ajutor.util.FileTextUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import retrofit2.HttpException

typealias CountResult = Pair<String, Int>

class AddRequestViewModel(application: Application) : AndroidViewModel(application) {

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

    companion object {
        private const val MAX_DOWNLOAD_BYTES: Long = 1024 * 1024 * 10 // 10MB
    }

    init {
        loadBenefitTypesFromFirestore()
    }

    /**
     * Încarcă imaginile „Cerere_Tip” și „Carte_de_Identitate_Mama”,
     * extrage text cu OCR și rulează un prompt scurt prin OpenAI
     * pentru a verifica numele și CNP-ul.
     */
    fun runAiLightValidation() {
        // 1. Obține link-urile de download din hartă
        val context = getApplication<Application>().applicationContext
        val docsMap = _uploadedDocuments.value ?: return
        val formUrl = docsMap["Cerere_Tip"] ?: return
        val idUrl = docsMap["Carte_de_Identitate_Mama"] ?: return

        _isLoading.value = true
        viewModelScope.launch {
            try {
                val formRef = storage.getReferenceFromUrl(formUrl)
                val idRef = storage.getReferenceFromUrl(idUrl)
                val formBytes = formRef.getBytes(MAX_DOWNLOAD_BYTES).await()
                val idBytes = idRef.getBytes(MAX_DOWNLOAD_BYTES).await()
                val formMeta = formRef.metadata.await()
                val idMeta = idRef.metadata.await()

                // 2. Extrage text indiferent de format
                val formText = FileTextUtil.extractText(
                    context,
                    formBytes,
                    formMeta.contentType,
                    formRef.name
                )
                val idText = FileTextUtil.extractText(
                    context,
                    idBytes,
                    idMeta.contentType,
                    idRef.name
                )

                Log.d("AI_Debug", "Form text: $formText")
                Log.d("AI_Debug", "ID text: $idText")

                // 5. Construiește prompt-ul Light
                val chatReq = ChatRequest(
                    messages = listOf(
                        AiChatMessage(
                            "system",
                            "Ești expert în verificarea numelui și CNP-ului."
                        ),
                        AiChatMessage(
                            "user",
                            """
                    Text CERERE TIP:
                    $formText

                    Text BULETIN MAMA:
                    $idText

                    Ești un
    EXPERT în verificarea numelui și CNP-ului. 
    DUPĂ ce analizezi datele, RĂSPUNDE DOAR cu:
      – “CNP sau NUME corespund” (fără ghilimele) dacă numele și CNP-ul CORESPUND,
      – “CNP sau NUME nu corespund” (fără ghilimele) dacă NU corespund.
    NU ADĂUGA nimic altceva în răspuns.
                    """.trimIndent()
                        )
                    )
                )

                // 6. Apelează OpenAI
                val response = OpenAiClient.api.createChat(chatReq)
                Log.d("AI_Debug", "OpenAI response: $response")

                val feedback = response.choices
                    .firstOrNull()
                    ?.message
                    ?.content
                    ?: "Nicio neconcordanță găsită."
                Log.d("AI_Debug", "Feedback: $feedback")

                // 7. Salvează în Firestore în câmpul aiLightFeedback
                db.collection("requests")
                    .document(requestId)
                    .update("aiLightFeedback", feedback)
                    .await()

            } catch (e: HttpException) {
                val code = e.code()
                val errorBody = e.response()?.errorBody()?.string()
                Log.e("AI_Debug", "OpenAI HTTP $code: $errorBody")
                _uiToastEvent.postValue(Event("Eroare AI $code: vedeți logcat pentru detalii"))
            } catch (e: Exception) {
                Log.e("AI_Debug", "Unexpected error", e)
                _uiToastEvent.postValue(Event("Eroare validare AI: ${e.localizedMessage}"))
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadBenefitTypesFromFirestore() {
        _isLoading.value = true
        viewModelScope.launch {
            try {
                val snapshot = db.collection("benefit_types").get().await()
                val types = snapshot.documents.mapNotNull { document ->
                    val requiredDocsList =
                        document.get("requiredDocuments") as? List<HashMap<String, String>>
                    val mappedDocs = requiredDocsList?.mapNotNull { docMap ->
                        val docId = docMap["id"]
                        val docName = docMap["displayName"]
                        if (docId != null && docName != null) {
                            DocumentRequirement(docId, docName)
                        } else {
                            null // Or log an error if a doc is malformed
                        }
                    } ?: emptyList()
                    BenefitType(
                        id = document.id, // Use Firestore document ID as the BenefitType ID
                        name = document.getString("name")
                            ?: "Unnamed Benefit", // Provide default if name is null
                        requiredDocuments = mappedDocs
                    )
                }
                _benefitTypes.value = types
                // If you want to auto-select the first item after loading:
                if (types.isNotEmpty()) {
                    onBenefitTypeSelected(types.first()) // Ensure this doesn't cause issues if fragment also tries to set initial
                }
            } catch (e: Exception) {
                _uiToastEvent.postValue(Event("Failed to load benefit types: ${e.message}"))
                _benefitTypes.value = emptyList() // Set to empty list on error
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun onBenefitTypeSelected(benefitType: BenefitType) {
        if (_selectedBenefitType.value?.id != benefitType.id) {
            _selectedBenefitType.value = benefitType
            _uploadedDocuments.value = emptyMap() // Clear previous uploads for new type
            _uploadStatusText.value = "Niciun fișier selectat."
        }
    }

    fun uploadFile(docId: String, uri: Uri) {
        val userId = auth.currentUser?.uid ?: run {
            _uiToastEvent.value = Event("Utilizatorul nu este autentificat.")
            return
        }
        _isLoading.value = true
        _uploadStatusText.value = "Se încarcă ${docId}..."

        viewModelScope.launch {
            try {
                val storageRef = storage.reference.child("documents/$userId/$requestId/$docId")
                storageRef.putFile(uri).await() // Suspending call
                val downloadUri = storageRef.downloadUrl.await() // Suspending call

                val currentUploads = _uploadedDocuments.value?.toMutableMap() ?: mutableMapOf()
                currentUploads[docId] = downloadUri.toString()
                _uploadedDocuments.value = currentUploads // This will trigger observer in fragment
                _uploadStatusText.value = "S-a încărcat: $docId"
            } catch (e: Exception) {
                _uploadStatusText.value = "Încărcare eșuată pentru $docId"
                _uiToastEvent.value = Event("Încărcare eșuată pentru $docId: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deleteDocument(docId: String, callbackOnSuccess: () -> Unit) {
        val userId = auth.currentUser?.uid ?: run {
            _uiToastEvent.value = Event("Utilizatorul nu este autentificat.")
            return
        }
        _isLoading.value = true
        _uploadStatusText.value = "Se șterge ${docId}..."

        viewModelScope.launch {
            try {
                val storageRef = storage.reference.child("documents/$userId/$requestId/$docId")
                storageRef.delete().await()

                val currentUploads = _uploadedDocuments.value?.toMutableMap() ?: mutableMapOf()
                currentUploads.remove(docId)
                _uploadedDocuments.value = currentUploads
                _uploadStatusText.value = "S-a șters: $docId. Selecteaza alt fișier."
                _uiToastEvent.value = Event("Fișierul anterior pentru $docId a fost șters.")
                callbackOnSuccess() // To re-launch picker
            } catch (e: Exception) {
                _uploadStatusText.value = "Ștergerea a eșuat pentru $docId"
                _uiToastEvent.value = Event("Ștergerea a eșuat pentru $docId: ${e.message}")
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
                    _uiToastEvent.postValue(Event("Nu a fost nicun operator pentru județul: $countyParam"))
                    return@withContext null
                }

                val countTasks = opSnap.documents.map { opDoc ->
                    val opId = opDoc.id
                    // Launch each count query asynchronously
                    viewModelScope.async { // Use async if you want to run these in parallel and await all
                        val requestCountSnap = db.collection("requests")
                            .whereEqualTo("operatorId", opId)
                            .whereEqualTo("status", "in curs")
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
                _uiToastEvent.postValue(Event("Eroare în găsirea unui operator: ${e.message}")) // Use postValue if on background thread
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
            _uiToastEvent.value = Event("Te rog acceptă termenii GDPR pentru a trimite cererea.")
            return
        }

        val currentSelectedBenefit = _selectedBenefitType.value
        if (currentSelectedBenefit == null || currentSelectedBenefit.id != benefitTypeIdFromSpinner) {
            _uiToastEvent.value =
                Event("Selectarea beneficiului a intampinat o eroare. Te rog alege iar.")
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
            _uiToastEvent.value = Event("Te rog încărca următoarele documente: $missingDocNames")
            return
        }

        val userId = auth.currentUser?.uid ?: run {
            _uiToastEvent.value =
                Event("Utilizatorul nu este autentificat. Nu s-a putut trimite cererea.")
            return
        }

        _isLoading.value = true
        viewModelScope.launch {
            try {
                val userDoc = db.collection("users").document(userId).get().await()
                val userCounty = userDoc.getString("county")
                val userName =
                    (userDoc.getString("firstName") ?: "") + " " + (userDoc.getString("lastName")
                        ?: "")

                if (userCounty.isNullOrBlank()) {
                    _uiToastEvent.value =
                        Event("Nu ți-ai setat un județ in profil. Te rog setează și încerca din nou.")
                    _isLoading.value = false
                    return@launch
                }

                val operatorId = findLeastLoadedOperatorByCounty(userCounty)
                if (operatorId.isNullOrEmpty()) {
                    _uiToastEvent.value =
                        Event("Niciun operator disponibil in județul: $userCounty. Vă rugăm să încercați din nou.")
                    _isLoading.value = false
                    return@launch
                }
                val opDoc = db.collection("users").document(operatorId).get().await()
                val operatorName = opDoc.getString("firstName") + " " + opDoc.getString("lastName")

                val request = UserRequest(
                    id = requestId, // Use ViewModel's requestId
                    userId = userId,
                    userName = userName.trim(),
                    operatorId = operatorId,
                    operatorName = operatorName.trim(),
                    benefitTypeId = currentSelectedBenefit.id,
                    benefitTypeName = currentSelectedBenefit.name,
                    location = userCounty,
                    documentLinks = currentUploadedDocsMap,
                    status = "in curs",
                    timestamp = Timestamp.now(),
                    iban = iban.trim(),
                    extraInfo = extraInfo.trim(),
                    aiLightFeedback = "",
                    aiFullFeedback = ""
                )

                db.collection("requests").document(requestId).set(request).await()
                Log.d("AddRequestViewModel", "Cererea a fost trimisă cu succes.")

                runAiLightValidation()
                Log.d("AddRequestViewModel", "AiLightValidation a fost rulat cu succes.")

                _uiToastEvent.value =
                    Event("Cererea a fost trimisă cu succes operatorului: $operatorName!")
                //_navigateToSeeRequestsEvent.value = Event(Unit)

                // Optionally reset form state after successful submission
                // _selectedBenefitType.value = null // This will trigger observers to clear UI
                // _uploadedDocuments.value = emptyMap()

            } catch (e: Exception) {
                _uiToastEvent.value = Event("Trimiterea cererei a eșuat: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }
}