package com.licenta.e_ajutor.ui.viewRequests

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.toObject
import com.licenta.e_ajutor.model.ChatMessage
import com.licenta.e_ajutor.model.UserRequest
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Locale

// Enum for User Roles - place outside or in a common file if used elsewhere
enum class UserRole { USER, OPERATOR, UNKNOWN }

class ViewRequestsViewModel : ViewModel() {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val TAG = "ViewRequestsViewModel"

    // LiveData for User Role
    private val _userRole = MutableLiveData<UserRole>(UserRole.UNKNOWN)
    val userRole: LiveData<UserRole> = _userRole

    // LiveData for the base query for requests
    private val _requestsQuery = MutableLiveData<Query>()
    val requestsQuery: LiveData<Query> = _requestsQuery

    // LiveData for loading state (main list and detail view)
    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    // LiveData for showing toast messages
    private val _toastMessage = MutableLiveData<String?>()
    val toastMessage: LiveData<String?> = _toastMessage

    // LiveData for the currently selected request (for detail view)
    private val _selectedRequest = MutableLiveData<UserRequest?>()
    val selectedRequest: LiveData<UserRequest?> = _selectedRequest

    // LiveData for chat messages query for the selected request
    private val _chatMessagesQuery = MutableLiveData<Query?>()
    val chatMessagesQuery: LiveData<Query?> = _chatMessagesQuery

    // LiveData to signal success of an operator action (approve/reject)
    // Used as an event, so it's reset after observation
    private val _operatorActionSuccess = MutableLiveData<Boolean?>()
    val operatorActionSuccess: LiveData<Boolean?> = _operatorActionSuccess

    init {
        fetchUserRoleAndSetupInitialQuery()
    }

    private fun fetchUserRoleAndSetupInitialQuery() {
        _isLoading.value = true
        viewModelScope.launch {
            val userId = auth.currentUser?.uid
            if (userId == null) {
                _userRole.value = UserRole.UNKNOWN
                _isLoading.value = false
                _toastMessage.value = "User not authenticated. Please log in."
                // Potentially trigger navigation to login here if desired
                return@launch
            }

            try {
                // Assuming you have a 'users' collection where each document ID is the user's UID
                // and contains a 'role' field ("user" or "operator")
                val userDoc = db.collection("users").document(userId).get().await()
                val roleString = userDoc.getString("role")
                val determinedRole = when (roleString?.lowercase(Locale.ROOT)) {
                    "user" -> UserRole.USER
                    "operator" -> UserRole.OPERATOR
                    else -> {
                        Log.w(TAG, "User role not found or unrecognized: $roleString for UID: $userId")
                        UserRole.UNKNOWN // Fallback to UNKNOWN if role is missing or invalid
                    }
                }
                _userRole.value = determinedRole
                Log.d(TAG, "User role determined: $determinedRole")
                // Apply a default filter after role is determined.
                // For Operators, default to "PENDING", for Users, default to "ALL".
                val initialFilter = if (determinedRole == UserRole.OPERATOR) "PENDING" else "ALL"
                updateQueryBasedOnRoleAndFilter(determinedRole, initialFilter)

            } catch (e: Exception) {
                Log.e(TAG, "Error fetching user role for UID: $userId", e)
                _userRole.value = UserRole.UNKNOWN
                _toastMessage.value = "Error determining user role: ${e.message}"
                // Still attempt to update query with UNKNOWN role to show nothing or an error state
                updateQueryBasedOnRoleAndFilter(UserRole.UNKNOWN, "ALL")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun setFilter(filterStatus: String) {
        // isLoading is primarily for data fetching, not just query changes
        // _isLoading.value = true // Optional: show loading if query change is slow
        updateQueryBasedOnRoleAndFilter(userRole.value ?: UserRole.UNKNOWN, filterStatus)
        // _isLoading.value = false // Optional
    }

    private fun updateQueryBasedOnRoleAndFilter(role: UserRole, filterStatus: String) {
        val currentUserId = auth.currentUser?.uid
        if (currentUserId == null && role != UserRole.UNKNOWN) {
            Log.e(TAG, "Cannot update query: User not logged in, but role is not UNKNOWN.")
            _requestsQuery.value = db.collection("requests").whereEqualTo("userId", "INVALID_NO_USER") // Empty query
            return
        }

        var query: Query = db.collection("requests")
        Log.d(TAG, "Updating query for role: $role, filter: $filterStatus, UserID: $currentUserId")

        when (role) {
            UserRole.USER -> {
                query = query.whereEqualTo("userId", currentUserId)
            }
            UserRole.OPERATOR -> {
                // Operators see requests assigned to them OR unassigned pending requests
                // This logic might need refinement based on your exact assignment process.
                // For simplicity now, let's assume operators have an 'operatorId' field on the request
                // and they only see requests explicitly assigned to their operatorId.
                // OR, if your system assigns requests to operators by some other means (e.g. county),
                // that logic would go here.
                query = query.whereEqualTo("operatorId", currentUserId) // Example: for assigned requests
                // If operators should also see ALL pending requests that are NOT yet assigned:
                // query = query.whereEqualTo("status", "pending").whereEqualTo("operatorId", null)
                // This part depends heavily on your application's business logic for operators.
            }
            UserRole.UNKNOWN -> {
                Log.w(TAG, "User role is UNKNOWN. Query will yield no results.")
                // Create a query that intentionally returns no results
                _requestsQuery.value = query.whereEqualTo("userId", "NON_EXISTENT_USER_FOR_UNKNOWN_ROLE")
                return
            }
        }

        // Apply status filter, unless "ALL"
        if (!filterStatus.equals("ALL", ignoreCase = true)) {
            query = query.whereEqualTo("status", filterStatus.lowercase(Locale.ROOT))
        }

        // Always order by timestamp descending to show newest first
        _requestsQuery.value = query.orderBy("timestamp", Query.Direction.DESCENDING)
        Log.d(TAG, "New Firestore query set for requests list.")
    }

    fun loadRequestDetails(requestId: String) {
        if (requestId.isBlank()) {
            _toastMessage.value = "Invalid Request ID."
            _selectedRequest.value = null
            _chatMessagesQuery.value = null
            return
        }
        _isLoading.value = true
        viewModelScope.launch {
            try {
                val requestDoc = db.collection("requests").document(requestId).get().await()
                val request = requestDoc.toObject<UserRequest>()
                _selectedRequest.value = request

                if (request != null) {
                    _chatMessagesQuery.value = db.collection("requests").document(requestId)
                        .collection("chat")
                        .orderBy("timestamp", Query.Direction.ASCENDING)
                    Log.d(TAG, "Details loaded for $requestId. Chat query set.")
                } else {
                    _toastMessage.value = "Request details not found."
                    _chatMessagesQuery.value = null
                    Log.w(TAG, "Request document $requestId not found or failed to parse.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading request details for $requestId", e)
                _toastMessage.value = "Error loading details: ${e.message}"
                _selectedRequest.value = null
                _chatMessagesQuery.value = null
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearSelectedRequest() {
        _selectedRequest.value = null
        _chatMessagesQuery.value = null
        Log.d(TAG, "Selected request and chat query cleared.")
    }

    fun sendChatMessage(requestId: String, messageText: String) {
        val senderId = auth.currentUser?.uid
        if (senderId == null) {
            _toastMessage.value = "Cannot send message: User not authenticated."
            return
        }
        if (messageText.isBlank()) {
            // _toastMessage.value = "Message cannot be empty." // Fragment handles this
            return
        }

        val chatMessage = ChatMessage(
            senderId = senderId,
            text = messageText.trim()
            // Firestore @ServerTimestamp will handle the timestamp
        )

        db.collection("requests").document(requestId).collection("chat")
            .add(chatMessage)
            .addOnSuccessListener {
                Log.d(TAG, "Message sent successfully to request $requestId")
                // The UI will update automatically due to FirestoreRecyclerAdapter listening to the query
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error sending message to request $requestId", e)
                _toastMessage.value = "Error sending message: ${e.message}"
            }
    }

    fun approveRequest(requestId: String) {
        if (userRole.value != UserRole.OPERATOR) {
            _toastMessage.value = "Action not allowed."
            return
        }
        _isLoading.value = true
        val updates = mapOf(
            "status" to "approved",
            "rejectionReason" to null // Explicitly clear rejection reason on approval
        )
        db.collection("requests").document(requestId)
            .update(updates)
            .addOnSuccessListener {
                _toastMessage.value = "Request approved."
                _operatorActionSuccess.value = true
                loadRequestDetails(requestId) // Refresh details view
                Log.d(TAG, "Request $requestId approved.")
            }
            .addOnFailureListener { e ->
                _toastMessage.value = "Error approving request: ${e.message}"
                Log.e(TAG, "Error approving request $requestId", e)
            }
            .addOnCompleteListener {
                _isLoading.value = false
                _operatorActionSuccess.value = null // Reset event
            }
    }

    fun rejectRequest(requestId: String, reason: String) {
        if (userRole.value != UserRole.OPERATOR) {
            _toastMessage.value = "Action not allowed."
            return
        }
        if (reason.isBlank()) {
            // _toastMessage.value = "Rejection reason cannot be empty." // Fragment handles this
            return
        }
        _isLoading.value = true
        val updates = mapOf(
            "status" to "rejected",
            "rejectionReason" to reason.trim()
        )
        db.collection("requests").document(requestId)
            .update(updates)
            .addOnSuccessListener {
                _toastMessage.value = "Request rejected."
                _operatorActionSuccess.value = true
                loadRequestDetails(requestId) // Refresh details view
                Log.d(TAG, "Request $requestId rejected with reason: $reason")
            }
            .addOnFailureListener { e ->
                _toastMessage.value = "Error rejecting request: ${e.message}"
                Log.e(TAG, "Error rejecting request $requestId", e)
            }
            .addOnCompleteListener {
                _isLoading.value = false
                _operatorActionSuccess.value = null // Reset event
            }
    }

    fun onToastMessageShown() {
        _toastMessage.value = null
    }
}