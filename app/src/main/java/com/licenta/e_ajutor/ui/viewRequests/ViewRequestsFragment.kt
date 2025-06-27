package com.licenta.e_ajutor.ui.viewRequests // Adjust to your package structure

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.firebase.ui.firestore.FirestoreRecyclerOptions
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.tabs.TabLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.licenta.e_ajutor.R
import com.licenta.e_ajutor.databinding.FragmentViewRequestsBinding
import com.licenta.e_ajutor.model.ChatMessage
import com.licenta.e_ajutor.model.UserRequest
import java.text.SimpleDateFormat
import java.util.Locale

//TODO AI pt verificarea documentelor

class ViewRequestsFragment : Fragment() {

    private var _binding: FragmentViewRequestsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ViewRequestsViewModel by viewModels()

    private var requestAdapter: RequestAdapter? = null
    private var chatAdapter: ChatMessageAdapter? = null
    private var currentChatQuery: Query? = null

    private lateinit var documentPickerLauncher: ActivityResultLauncher<Intent>
    private var pendingDocForReplace: String? = null

    private lateinit var etAiFeedback: TextView
    private val auth = FirebaseAuth.getInstance()
    private val db   = FirebaseFirestore.getInstance()

    private val detailViewDateFormat = SimpleDateFormat("EEEE, d MMMM, yyyy 'at' hh:mm a", Locale("ro","RO"))
    private val TAG = "ViewRequestsFragment"

    private lateinit var detailViewBackPressedCallback: OnBackPressedCallback

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentViewRequestsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // No setupToolbar() needed here if Activity Toolbar is primary for the list view.
        // Title will be set by NavController or in userRole observer.
        setupRequestRecyclerView()
        setupFilterTabs()
        setupDetailViewListeners()
        observeViewModel()
        setupDocumentPickerLauncher()

        detailViewBackPressedCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (binding.detailContent.root.isVisible) {
                    showDetailView(false)
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, detailViewBackPressedCallback)

        showDetailView(false) // Initial state
    }

    private fun setupRequestRecyclerView() {
        binding.recyclerViewRequests.layoutManager = LinearLayoutManager(requireContext())
    }

    private fun setupFilterTabs() {
        if (binding.tabLayoutFilters.tabCount == 0) {
            val tabs = listOf("TOATE", "IN CURS", "APROBATE", "REFUZATE")
            tabs.forEach { tabTitle ->
                binding.tabLayoutFilters.addTab(binding.tabLayoutFilters.newTab().setText(tabTitle))
            }
        }

        binding.tabLayoutFilters.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                tab?.text?.toString()?.let {
                    viewModel.setFilter(it)
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {
                tab?.text?.toString()?.let { viewModel.setFilter(it) }
            }
        })
    }

    private fun observeViewModel() {
        viewModel.userRole.observe(viewLifecycleOwner) { role ->
            role?.let {
                // Update Activity's toolbar title if not in detail view
                if (!binding.detailContent.root.isVisible) {
                    val title = if (it == UserRole.OPERATOR) "Assigned Requests" else "My Requests"
                    (activity as? AppCompatActivity)?.supportActionBar?.title = title
                }

                if (requestAdapter != null && requestAdapter?.currentRole != it) {
                    viewModel.requestsQuery.value?.let { query ->
                        createAndSetRequestAdapter(query, it)
                    }
                }

                if (binding.tabLayoutFilters.selectedTabPosition == -1 ||
                    !binding.tabLayoutFilters.getTabAt(binding.tabLayoutFilters.selectedTabPosition)!!.isSelected) {
                    val targetTabIndex = if (it == UserRole.OPERATOR) 1 else 0
                    binding.tabLayoutFilters.getTabAt(targetTabIndex)?.select()
                }
            }
        }

        viewModel.requestsQuery.observe(viewLifecycleOwner) { query ->
            query?.let {
                createAndSetRequestAdapter(it, viewModel.userRole.value ?: UserRole.UNKNOWN)
            }
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressBarRequests.isVisible = isLoading && !binding.detailContent.root.isVisible
            binding.detailContent.progressBarDetail.isVisible = isLoading && binding.detailContent.root.isVisible
        }

        viewModel.toastMessage.observe(viewLifecycleOwner) { messageEvent ->
            messageEvent?.let { message ->
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                viewModel.onToastMessageShown()
            }
        }

        viewModel.selectedRequest.observe(viewLifecycleOwner) { request ->
            if (binding.detailContent.root.isVisible) {
                populateDetailViewData(request)
            }
        }

        viewModel.chatMessagesQuery.observe(viewLifecycleOwner) { newChatQuery -> // Renamed for clarity
            if (binding.detailContent.root.isVisible) {
                if (newChatQuery != null) {
                    // Check if the adapter needs to be created or if the query has fundamentally changed
                    // A simple instance check for the query from the ViewModel is often sufficient.
                    // If the ViewModel provides a new Query object, we assume we need a new adapter.
                    var createOrUpdateAdapter = false
                    if (chatAdapter == null) {
                        createOrUpdateAdapter = true
                    } else {
                        // If you want to be more specific, you'd need to compare the actual parameters
                        // of the old and new query. For simplicity, if the ViewModel emits a new
                        // non-null query while one is active, we can assume it might be for a new context.
                        // However, FirestoreRecyclerAdapter is usually smart enough.
                        // The crucial part is that `setLifecycleOwner` handles updates for the *same* query.
                        // So, we primarily need to re-create if the query *target* changes (e.g. different request ID)
                        // This is implicitly handled if `viewModel.chatMessagesQuery` emits a new Query instance
                        // only when the request ID actually changes.

                        // Let's simplify: if the adapter exists and the new query is different from its *original* query
                        // (though accessing the original query from options isn't direct),
                        // or more practically, if the viewmodel's liveData emits a new query instance
                        // that isn't the one currently powering the adapter.
                        // For now, let's re-create if it's null or if the new query is different from the one used to create it.
                        // This logic could be refined based on how `chatMessagesQuery` in ViewModel is updated.
                        // The key is: FirestoreRecyclerAdapter handles data changes for a GIVEN query.
                        // Rebuild the adapter IF THE QUERY ITSELF CHANGES (e.g. new chat room).

                        // A common pattern: if the LiveData emits a new Query object,
                        // and it's different than what the adapter was last configured with, rebuild.
                        // Since accessing the adapter's direct query is tricky, we compare newChatQuery
                        // to the ViewModel's current value. If it's a fresh emission, it's likely "new".
//                        if (chatAdapter?.snapshots?.getOrNull(0)?.reference?.parent?.parent?.id != newChatQuery.firestore.collection(newChatQuery.path).id) {
//                            // This is a more involved check if the parent document ID changed
//                            // For simplicity:
//                            // If the viewModel guarantees chatMessagesQuery only emits a new Query instance
//                            // when the underlying request ID changes, then we recreate.
//                            // For now, let's assume if chatAdapter is not null, it's already listening to the "correct" query
//                            // for the current selectedRequest, and FirestoreUI will handle updates.
//                            // We only create if it's null.
//                        }
                    }

                    // Simplified approach: Rebuild adapter if it's null or if the ViewModel's query instance changes.
                    // This relies on the ViewModel providing a new Query instance primarily when the chat target changes.
                    if (chatAdapter == null || currentChatQuery != newChatQuery) { // Need to store currentChatQuery
                        Log.d(TAG, "ChatMessagesQuery: Creating/Recreating ChatAdapter.")
                        currentChatQuery = newChatQuery // Store the new query
                        chatAdapter?.stopListening() // Stop previous one if exists

                        val chatOptions = FirestoreRecyclerOptions.Builder<ChatMessage>()
                            .setQuery(newChatQuery, ChatMessage::class.java)
                            .setLifecycleOwner(viewLifecycleOwner) // This is key for auto start/stop
                            .build()
                        chatAdapter = ChatMessageAdapter(chatOptions, FirebaseAuth.getInstance().currentUser?.uid ?: "")
                        binding.detailContent.recyclerViewChatMessages.layoutManager =
                            WrapContentLinearLayoutManager(requireContext()).apply { stackFromEnd = true }

// 2) Dezactivează animațiile de tip change (previne update-uri parţiale care duc la inconsistenţe)
                        (binding.detailContent.recyclerViewChatMessages.itemAnimator as? SimpleItemAnimator)
                            ?.supportsChangeAnimations = false

                        binding.detailContent.recyclerViewChatMessages.adapter = chatAdapter
                        // chatAdapter?.startListening() // Not strictly needed if setLifecycleOwner is used
                    }
                    // else, adapter exists and FirestoreUI handles updates for the current query.

                    // Scroll to bottom logic (can remain as is if adapter is updated/recreated)
                    chatAdapter?.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                        override fun onChanged() {
                            super.onChanged()
                            val count = chatAdapter?.itemCount ?: 0
                            if (count > 0) {
                                binding.detailContent.recyclerViewChatMessages.scrollToPosition(count - 1)
                            }
                        }
                    })

                } else { // newChatQuery is null
                    Log.d(TAG, "ChatMessagesQuery: Query is null, clearing chat adapter.")
                    chatAdapter?.stopListening()
                    binding.detailContent.recyclerViewChatMessages.adapter = null
                    chatAdapter = null
                    currentChatQuery = null // Reset stored query
                }
            } else { // Detail view is not visible
                if (chatAdapter != null) {
                    Log.d(TAG, "ChatMessagesQuery: Detail view not visible, stopping chat adapter.")
                    chatAdapter?.stopListening()
                    // Optionally nullify adapter and clear recyclerview if you want to free resources immediately
                    // binding.detailContent.recyclerViewChatMessages.adapter = null
                    // chatAdapter = null
                    // currentChatQuery = null
                }
            }
        }

        viewModel.operatorActionSuccess.observe(viewLifecycleOwner) { successEvent ->
            successEvent?.let { success ->
                if (success && binding.detailContent.root.isVisible) {
                    binding.detailContent.textInputLayoutRejectionReasonInput.isVisible = false
                    binding.detailContent.buttonSubmitRejection.isVisible = false
                    binding.detailContent.editTextRejectionReasonInput.setText("")
                }
            }
        }
    }

    private fun createAndSetRequestAdapter(query: Query, role: UserRole) {
        val options = FirestoreRecyclerOptions.Builder<UserRequest>()
            .setQuery(query, UserRequest::class.java)
            .setLifecycleOwner(viewLifecycleOwner)
            .build()

        requestAdapter = RequestAdapter(options, requireContext(), role) { selectedRequest ->
            viewModel.loadRequestDetails(selectedRequest.id)
            showDetailView(true)
        }
        binding.recyclerViewRequests.adapter = requestAdapter

        requestAdapter?.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() { checkEmptyList() }
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { checkEmptyList() }
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { checkEmptyList() }
        })
        checkEmptyList() // Initial check
    }

    private fun checkEmptyList() {
        val isListEmpty = requestAdapter?.itemCount == 0
        val isProgressBarHidden = !binding.progressBarRequests.isVisible
        val isDetailViewHidden = !binding.detailContent.root.isVisible

        binding.textViewEmptyList.isVisible = isListEmpty && isProgressBarHidden && isDetailViewHidden
    }

    private fun showDetailView(show: Boolean) {
        binding.detailContent.root.isVisible = show
        binding.listContentGroup.isVisible = !show // list_content_group contains tabs and RecyclerView

        detailViewBackPressedCallback.isEnabled = show

        // Manage visibility of BottomNavigationView and Activity Toolbar
        val bottomNavView = activity?.findViewById<BottomNavigationView>(R.id.nav_view)
        val activityToolbar = activity?.findViewById<Toolbar>(R.id.activityToolbar) // Use Toolbar directly

        bottomNavView?.isVisible = !show

        if (show) {
            // Hide Activity's toolbar and show detail's toolbar
            activityToolbar?.visibility = View.GONE
            binding.detailContent.toolbarDetail.visibility = View.VISIBLE
            (activity as? AppCompatActivity)?.setSupportActionBar(binding.detailContent.toolbarDetail)
            (activity as? AppCompatActivity)?.supportActionBar?.setDisplayHomeAsUpEnabled(true)
            (activity as? AppCompatActivity)?.supportActionBar?.title = "Request Details" // Can be dynamic

            binding.detailContent.toolbarDetail.setNavigationOnClickListener {
                showDetailView(false) // This will also restore Activity toolbar and BottomNav
            }
        } else {
            // Hide detail's toolbar and restore Activity's toolbar
            binding.detailContent.toolbarDetail.visibility = View.GONE
            (activity as? AppCompatActivity)?.setSupportActionBar(activityToolbar) // Restore Activity's toolbar
            activityToolbar?.visibility = View.VISIBLE

            // Restore title for the list view (NavController usually handles this based on label)
            val currentRole = viewModel.userRole.value
            val title = if (currentRole == UserRole.OPERATOR) "Assigned Requests" else "My Requests"
            (activity as? AppCompatActivity)?.supportActionBar?.title = title
            (activity as? AppCompatActivity)?.supportActionBar?.setDisplayHomeAsUpEnabled(false) // Or based on NavGraph

            // Clear detail view specific data
            viewModel.clearSelectedRequest()
            chatAdapter?.stopListening() // Ensure chat adapter is stopped
            binding.detailContent.recyclerViewChatMessages.adapter = null // Clear chat adapter
            chatAdapter = null

            // Clear focus and hide keyboard if it was open for detail inputs
            val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(view?.windowToken, 0)
            binding.detailContent.editTextChatMessage.clearFocus()
            binding.detailContent.editTextRejectionReasonInput.clearFocus()
        }
        checkEmptyList() // Update empty list visibility after toggling detail view
    }


    private fun setupDetailViewListeners() {
        val detailBinding = binding.detailContent

        detailBinding.fabSendChatMessage.setOnClickListener {
            val message = detailBinding.editTextChatMessage.text.toString().trim()
            viewModel.selectedRequest.value?.id?.let { requestId ->
                if (message.isNotEmpty()) {
                    viewModel.sendChatMessage(requestId, message)
                    detailBinding.editTextChatMessage.setText("")
                    val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.hideSoftInputFromWindow(detailBinding.editTextChatMessage.windowToken, 0)
                } else {
                    Toast.makeText(requireContext(), "Message cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
        }

        detailBinding.editTextChatMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                detailBinding.fabSendChatMessage.performClick()
                return@setOnEditorActionListener true
            }
            false
        }

        detailBinding.buttonApproveRequest.setOnClickListener {
            viewModel.selectedRequest.value?.id?.let { requestId ->
                AlertDialog.Builder(requireContext())
                    .setTitle("Confirm Approval")
                    .setMessage("Are you sure you want to approve this request?")
                    .setPositiveButton("Approve") { _, _ -> viewModel.approveRequest(requestId) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        detailBinding.buttonRejectRequest.setOnClickListener {
            detailBinding.textInputLayoutRejectionReasonInput.isVisible = true
            detailBinding.buttonSubmitRejection.isVisible = true
            detailBinding.editTextRejectionReasonInput.requestFocus()
        }

        detailBinding.buttonSubmitRejection.setOnClickListener {
            val reason = detailBinding.editTextRejectionReasonInput.text.toString().trim()
            if (reason.isEmpty()) {
                detailBinding.textInputLayoutRejectionReasonInput.error = "Reason cannot be empty"
                return@setOnClickListener
            }
            detailBinding.textInputLayoutRejectionReasonInput.error = null
            viewModel.selectedRequest.value?.id?.let { requestId ->
                AlertDialog.Builder(requireContext())
                    .setTitle("Confirm Rejection")
                    .setMessage("Are you sure you want to reject this request with the provided reason?")
                    .setPositiveButton("Reject") { _, _ -> viewModel.rejectRequest(requestId, reason) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        detailBinding.fabAttachFile.setOnClickListener {
            val req = viewModel.selectedRequest.value ?: return@setOnClickListener

            // 1) Fetch the req’d docs for this benefit type
            viewModel.fetchRequiredDocuments(req.benefitTypeId)

            // 2) Once loaded, show the same AlertDialog
            viewModel.requiredDocuments.observe(viewLifecycleOwner) { docs ->
                if (docs.isEmpty()) {
                    Toast.makeText(requireContext(), "No documents required.", Toast.LENGTH_SHORT)
                        .show()
                    return@observe
                }
                val names = docs.map { it.displayName }.toTypedArray()
                val ids = docs.map { it.id }.toTypedArray()

                AlertDialog.Builder(requireContext())
                    .setTitle("Select document to replace")
                    .setItems(names) { _, idx ->
                        // launch picker, then in the result...
                        pendingDocForReplace = ids[idx]
                        // reuse your ActivityResultLauncher (or make a new one)
                        launchFilePicker()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun launchFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*" // Allow all file types
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        documentPickerLauncher.launch(intent)
    }

    private fun setupDocumentPickerLauncher() {
        documentPickerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    val req = viewModel.selectedRequest.value ?: return@let
                    pendingDocForReplace?.let { docId ->
                        // Call our new VM method
                        viewModel.replaceDocument(req.id, docId, uri)
                    }
                }
            } else {
                Toast.makeText(context, "No file selected", Toast.LENGTH_SHORT).show()
            }
            pendingDocForReplace = null
        }
    }

    private fun populateDetailViewData(request: UserRequest?) {
        val detailBinding = binding.detailContent

        if (request == null) {
            detailBinding.toolbarDetail.title = "Request Not Found"
            detailBinding.textViewDetailBenefitType.text =
                getString(R.string.error_request_data_is_unavailable)
            detailBinding.textViewDetailStatus.text = "N/A"
            // ... (hide/clear other fields as in your previous version) ...
            detailBinding.linearLayoutOperatorActions.isVisible = false
            detailBinding.linearLayoutDocumentsContainer.removeAllViews()
            detailBinding.cardRejectionReason.isVisible = false
            detailBinding.cardAiFeedback.isVisible = false
            return
        }

        detailBinding.toolbarDetail.title = "Request #${request.id.takeLast(6).uppercase(Locale.ROOT)}"
        detailBinding.textViewDetailBenefitType.text = request.benefitTypeName.ifEmpty { request.benefitTypeId }
        detailBinding.textViewDetailStatus.text = request.status.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        detailBinding.textViewDetailSubmissionDate.text = getString(
            R.string.submitted,
            request.timestamp?.toDate()?.let { detailViewDateFormat.format(it) } ?: "N/A")

        val statusColor = when (request.status.lowercase(Locale.ROOT)) {
            "aprobate" -> ContextCompat.getColor(requireContext(), R.color.status_indicator_green)
            "refuzate" -> ContextCompat.getColor(requireContext(), R.color.status_indicator_red)
            "in curs" -> ContextCompat.getColor(requireContext(), R.color.status_indicator_yellow)
            else -> ContextCompat.getColor(requireContext(), R.color.grey_dark)
        }
        detailBinding.textViewDetailStatus.setTextColor(statusColor)

        if (request.status.equals("refuzate", ignoreCase = true) && !request.rejectionReason.isNullOrEmpty()) {
            detailBinding.textViewDetailRejectionReason.text = request.rejectionReason
            detailBinding.cardRejectionReason.isVisible = true
        } else {
            detailBinding.cardRejectionReason.isVisible = false
        }

        if (viewModel.userRole.value == UserRole.OPERATOR) {
            Log.d(TAG, "AI Feedback: ${request.aiLightFeedback}")
            detailBinding.cardAiFeedback.isVisible = true
            detailBinding.etAiFeedback.text = request.aiLightFeedback
        } else {
            detailBinding.cardAiFeedback.isVisible = false
        }

        if (viewModel.userRole.value == UserRole.OPERATOR) {
            detailBinding.textViewDetailUserInfo.text =
                getString(R.string.utilizator, request.userName.ifEmpty { request.userId })
            detailBinding.fabAttachFile.isVisible = false
            detailBinding.textViewDetailUserInfo.isVisible = true
            detailBinding.linearLayoutOperatorActions.isVisible = request.status.equals("in curs", ignoreCase = true)
            if (!detailBinding.buttonSubmitRejection.isVisible) {
                detailBinding.textInputLayoutRejectionReasonInput.isVisible = false
            }
        } else {
            detailBinding.textViewDetailUserInfo.text =
                getString(R.string.operator, request.operatorName.ifEmpty { request.operatorId })
            detailBinding.textViewDetailUserInfo.isVisible = true
            detailBinding.fabAttachFile.isVisible = true
            detailBinding.linearLayoutOperatorActions.isVisible = false
            detailBinding.textInputLayoutRejectionReasonInput.isVisible = false
            detailBinding.buttonSubmitRejection.isVisible = false
        }

        detailBinding.textViewDetailIban.text =
            getString(R.string.iban, request.iban.ifEmpty { "Not Provided" })
        detailBinding.textViewDetailExtraInfo.text = request.extraInfo.ifEmpty { "No additional information." }

        populateDocumentsListInDetailView(request.documentLinks)
    }

    private fun populateDocumentsListInDetailView(documents: Map<String, String>) {
        val documentsContainer = binding.detailContent.linearLayoutDocumentsContainer
        documentsContainer.removeAllViews()

        if (documents.isEmpty()) {
            val noDocsTextView = TextView(requireContext()).apply {
                text = context.getString(R.string.no_documents_available_for_this_request)
                // ... (styling as before)
            }
            documentsContainer.addView(noDocsTextView)
            return
        }

        val inflater = LayoutInflater.from(requireContext())
        documents.forEach { (docName, docUrl) ->
            try {
                val docItemView = inflater.inflate(R.layout.document_item_detail, documentsContainer, false) as RelativeLayout
                val docNameTextView = docItemView.findViewById<TextView>(R.id.textView_doc_name_detail)
                val downloadButton = docItemView.findViewById<Button>(R.id.button_download_doc_detail)
                val docIconImageView = docItemView.findViewById<ImageView>(R.id.imageView_doc_icon_detail)

                val displayName = docName.substringAfterLast('/').replace("_", " ").replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                docNameTextView.text = displayName.ifEmpty { "Document File" }

                when {
                    docUrl.contains(".pdf", ignoreCase = true) -> docIconImageView.setImageResource(R.drawable.ic_file_pdf)
                    docUrl.contains(".jpg", ignoreCase = true) || docUrl.contains(".png", ignoreCase = true) -> docIconImageView.setImageResource(R.drawable.ic_file_image)
                    else -> docIconImageView.setImageResource(R.drawable.ic_document_placeholder)
                }

                downloadButton.setOnClickListener {
                    if (docUrl.isNotEmpty()) {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(docUrl))
                            startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(requireContext(), "Could not open document link.", Toast.LENGTH_SHORT).show()
                            Log.e(TAG, "Error opening document URL: $docUrl", e)
                        }
                    } else {
                        Toast.makeText(requireContext(), "Document URL is missing.", Toast.LENGTH_SHORT).show()
                    }
                }
                documentsContainer.addView(docItemView)
            } catch (e: Exception) {
                Log.e(TAG, "Error inflating document_item_detail for $docName", e)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // FirestoreRecyclerAdapter with setLifecycleOwner should handle this.
        // If chatAdapter also uses setLifecycleOwner in its options, this explicit call might be redundant.
        if (binding.detailContent.root.isVisible && viewModel.chatMessagesQuery.value != null) {
            chatAdapter?.startListening()
        }
    }

    override fun onStop() {
        super.onStop()
        chatAdapter?.stopListening()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.recyclerViewRequests.adapter = null
        binding.detailContent.recyclerViewChatMessages.adapter = null
        requestAdapter = null
        chatAdapter = null
        _binding = null
    }
}