package com.licenta.e_ajutor.ui.viewRequests

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.requestFocus
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.semantics.text
import androidx.compose.ui.test.performClick
import androidx.core.content.ContextCompat
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.privacysandbox.tools.core.generator.build
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.firebase.ui.firestore.FirestoreRecyclerOptions
import com.google.android.material.card.MaterialCardView
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.licenta.e_ajutor.R
import com.licenta.e_ajutor.databinding.FragmentViewRequestsBinding // Assumes ViewBinding
import com.licenta.e_ajutor.model.ChatMessage
import com.licenta.e_ajutor.model.UserRequest
import java.text.SimpleDateFormat
import java.util.*

class ViewRequestsFragment : Fragment() {

    private var _binding: FragmentViewRequestsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ViewRequestsViewModel by viewModels()

    private var requestAdapter: RequestAdapter? = null
    private var chatAdapter: ChatMessageAdapter? = null

    private val detailListItemDateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    private val detailViewDateFormat = SimpleDateFormat("EEEE, MMM dd, yyyy 'at' hh:mm a", Locale.getDefault())
    private val TAG = "ViewRequestsFragment"

    // Detail View UI Elements (from included layout or main layout)
    private var detailViewContainer: ViewGroup? = null
    // (Other detail view elements will be accessed via binding.detailContent.elementId if using <include>)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentViewRequestsBinding.inflate(inflater, container, false)
        // If your detail view is an <include> with an ID, like <include android:id="@+id/detail_content" ... />
        // then binding.detailContent will refer to the binding object of that included layout.
        // If it's just a ViewGroup within fragment_view_requests.xml, you'd find it directly.
        detailViewContainer = binding.detailContent.root // Assuming detail_content is the ID of your included layout or detail ViewGroup
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupRequestRecyclerView()
        setupFilterTabs()
        setupDetailViewListeners() // Setup listeners for detail view components
        observeViewModel()

        // Handle back press when detail view is visible
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(false) { // Initially disabled
            override fun handleOnBackPressed() {
                if (detailViewContainer?.isVisible == true) {
                    showDetailView(false) // Hide detail view
                    this.isEnabled = false // Disable this callback
                }
            }
        })

        // Initially hide detail view
        showDetailView(false) // This will also update the back press callback's enabled state
    }

    private fun setupToolbar() {
        // binding.toolbar.title = "My Requests" // Set dynamically by ViewModel
        // For standalone toolbar: (activity as? AppCompatActivity)?.setSupportActionBar(binding.toolbar)
    }

    private fun setupRequestRecyclerView() {
        binding.recyclerViewRequests.layoutManager = LinearLayoutManager(requireContext())
        // Adapter will be set when the query is ready from ViewModel
    }

    private fun setupFilterTabs() {
        val tabs = listOf("ALL", "PENDING", "APPROVED", "REJECTED")
        if (binding.tabLayoutFilters.tabCount == 0) { // Add tabs only if not already present
            tabs.forEach { tabTitle ->
                binding.tabLayoutFilters.addTab(binding.tabLayoutFilters.newTab().setText(tabTitle))
            }
        }

        binding.tabLayoutFilters.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                tab?.text?.toString()?.let {
                    viewModel.setFilter(it)
                    Log.d(TAG, "Tab selected: $it")
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {
                tab?.text?.toString()?.let { // Re-apply filter if re-selected
                    viewModel.setFilter(it)
                }
            }
        })
    }

    private fun observeViewModel() {
        viewModel.userRole.observe(viewLifecycleOwner) { role ->
            role?.let {
                binding.toolbar.title = if (it == UserRole.OPERATOR) "Assigned Requests" else "My Requests"
                // Re-initialize adapter with the correct role if it changes and adapter exists
                if (requestAdapter != null && requestAdapter?.currentRole != it) {
                    // This scenario (role changing while fragment is active) might be rare
                    // but if it happens, re-create adapter. Or, pass LiveData<UserRole> to adapter.
                    viewModel.requestsQuery.value?.let { query ->
                        createAndSetRequestAdapter(query, it)
                    }
                }
                // Set initial tab based on role, only if not already set by user interaction
                if (binding.tabLayoutFilters.selectedTabPosition == -1 ||
                    (it == UserRole.OPERATOR && binding.tabLayoutFilters.selectedTabPosition != 1) ||
                    (it == UserRole.USER && binding.tabLayoutFilters.selectedTabPosition != 0)
                ) {
                    if (it == UserRole.OPERATOR) {
                        binding.tabLayoutFilters.getTabAt(1)?.select() // PENDING for operator
                    } else {
                        binding.tabLayoutFilters.getTabAt(0)?.select() // ALL for user
                    }
                }
            }
        }

        viewModel.requestsQuery.observe(viewLifecycleOwner) { query ->
            query?.let {
                Log.d(TAG, "RequestsQuery Observer: New query received. Role: ${viewModel.userRole.value}")
                createAndSetRequestAdapter(it, viewModel.userRole.value ?: UserRole.UNKNOWN)
            }
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressBarRequests.isVisible = isLoading && (detailViewContainer?.isVisible == false)
            binding.detailContent.progressBarDetail.isVisible = isLoading && (detailViewContainer?.isVisible == true)
        }

        viewModel.toastMessage.observe(viewLifecycleOwner) { message ->
            message?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
                viewModel.onToastMessageShown()
            }
        }

        // --- Observers for Detail View ---
        viewModel.selectedRequest.observe(viewLifecycleOwner) { request ->
            if (detailViewContainer?.isVisible == true) { // Only update if detail view is active
                populateDetailView(request)
            }
        }

        viewModel.chatMessagesQuery.observe(viewLifecycleOwner) { query ->
            if (detailViewContainer?.isVisible == true && query != null) {
                val chatOptions = FirestoreRecyclerOptions.Builder<ChatMessage>()
                    .setQuery(query, ChatMessage::class.java)
                    .setLifecycleOwner(viewLifecycleOwner) // Important for auto start/stop
                    .build()

                if (chatAdapter == null || chatAdapter?.snapshots?.query != query) {
                    chatAdapter = ChatMessageAdapter(chatOptions)
                    binding.detailContent.recyclerViewChatMessages.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
                    binding.detailContent.recyclerViewChatMessages.adapter = chatAdapter
                }
                chatAdapter?.startListening() // Ensure listening

                // Scroll to bottom when new messages arrive
                chatAdapter?.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                    override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                        super.onItemRangeInserted(positionStart, itemCount)
                        binding.detailContent.recyclerViewChatMessages.smoothScrollToPosition( (chatAdapter?.itemCount ?: 0) -1 )
                    }
                })

            } else if (detailViewContainer?.isVisible == true) { // query is null but detail view is visible
                chatAdapter?.stopListening()
                binding.detailContent.recyclerViewChatMessages.adapter = null
                Log.d(TAG, "Chat query is null, clearing chat adapter.")
            }
        }

        viewModel.operatorActionSuccess.observe(viewLifecycleOwner) { success ->
            if (success == true && detailViewContainer?.isVisible == true) {
                // The ViewModel reloads the request details, which will update the UI.
                // You might want to briefly show a success message or animation.
                // The LiveData is reset in the ViewModel after being observed.
                binding.detailContent.textInputLayoutRejectionReasonInput.isVisible = false
                binding.detailContent.buttonSubmitRejection.isVisible = false
                binding.detailContent.editTextRejectionReasonInput.setText("")
            }
        }
    }

    private fun createAndSetRequestAdapter(query: Query, role: UserRole) {
        val options = FirestoreRecyclerOptions.Builder<UserRequest>()
            .setQuery(query, UserRequest::class.java)
            .setLifecycleOwner(viewLifecycleOwner)
            .build()

        requestAdapter = RequestAdapter(options, requireContext(), role) { selectedRequest ->
            Log.d(TAG, "Request clicked: ${selectedRequest.id}")
            viewModel.loadRequestDetails(selectedRequest.id)
            showDetailView(true)
        }
        binding.recyclerViewRequests.adapter = requestAdapter
        requestAdapter?.startListening() // Essential

        // Handle empty list state for the main list
        requestAdapter?.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                super.onItemRangeInserted(positionStart, itemCount)
                checkEmptyList()
            }
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                super.onItemRangeRemoved(positionStart, itemCount)
                checkEmptyList()
            }
            private fun checkEmptyList() {
                binding.textViewEmptyList.isVisible = requestAdapter?.itemCount == 0 && !viewModel.isLoading.value!!
            }
        })
        binding.textViewEmptyList.isVisible = requestAdapter?.itemCount == 0 && !viewModel.isLoading.value!!

    }


    private fun showDetailView(show: Boolean) {
        detailViewContainer?.isVisible = show
        binding.appBarLayout.isVisible = !show // Hide main app bar and tabs
        binding.recyclerViewRequests.isVisible = !show
        binding.textViewEmptyList.isVisible = !show && (requestAdapter?.itemCount == 0)

        // Enable/disable back press callback for detail view
        val callback = requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(show) {
            override fun handleOnBackPressed() {
                if (detailViewContainer?.isVisible == true) {
                    showDetailView(false)
                    this.isEnabled = false // Disable after handling
                } else if (isEnabled) { // Fallback if still enabled but view not visible
                    isEnabled = false
                    requireActivity().onBackPressed() // Perform default back
                }
            }
        })
        callback.isEnabled = show // Explicitly set based on `show`


        if (!show) {
            viewModel.clearSelectedRequest() // Clear data when hiding
            chatAdapter?.stopListening() // Stop listening to chat when detail is hidden
            chatAdapter = null
            binding.detailContent.recyclerViewChatMessages.adapter = null // Clear adapter
        } else {
            // Request details and chat will be loaded by observers when viewModel.selectedRequest/chatMessagesQuery update
            binding.detailContent.toolbarDetail.setNavigationOnClickListener {
                showDetailView(false) // Custom back navigation
            }
        }
    }

    private fun setupDetailViewListeners() {
        binding.detailContent.fabSendChatMessage.setOnClickListener {
            val message = binding.detailContent.editTextChatMessage.text.toString().trim()
            viewModel.selectedRequest.value?.id?.let { requestId ->
                if (message.isNotEmpty()) {
                    viewModel.sendChatMessage(requestId, message)
                    binding.detailContent.editTextChatMessage.setText("")
                } else {
                    Toast.makeText(requireContext(), "Message cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.detailContent.editTextChatMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                binding.detailContent.fabSendChatMessage.performClick()
                return@setOnEditorActionListener true
            }
            false
        }

        binding.detailContent.buttonApproveRequest.setOnClickListener {
            viewModel.selectedRequest.value?.id?.let { requestId ->
                AlertDialog.Builder(requireContext())
                    .setTitle("Confirm Approval")
                    .setMessage("Are you sure you want to approve this request?")
                    .setPositiveButton("Approve") { _, _ -> viewModel.approveRequest(requestId) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        binding.detailContent.buttonRejectRequest.setOnClickListener {
            binding.detailContent.textInputLayoutRejectionReasonInput.isVisible = true
            binding.detailContent.buttonSubmitRejection.isVisible = true
            binding.detailContent.editTextRejectionReasonInput.requestFocus()
        }

        binding.detailContent.buttonSubmitRejection.setOnClickListener {
            val reason = binding.detailContent.editTextRejectionReasonInput.text.toString().trim()
            if (reason.isEmpty()) {
                binding.detailContent.textInputLayoutRejectionReasonInput.error = "Reason cannot be empty"
                return@setOnClickListener
            }
            binding.detailContent.textInputLayoutRejectionReasonInput.error = null
            viewModel.selectedRequest.value?.id?.let { requestId ->
                AlertDialog.Builder(requireContext())
                    .setTitle("Confirm Rejection")
                    .setMessage("Are you sure you want to reject this request with the provided reason?")
                    .setPositiveButton("Reject") { _, _ -> viewModel.rejectRequest(requestId, reason) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun populateDetailView(request: UserRequest?) {
        val detailBinding = binding.detailContent // Assuming detail_content is the <include> id or root of detail views

        if (request == null) {
            Log.w(TAG, "Attempted to populate detail view with null request.")
            // Optionally hide specific elements or show an error message in the detail view itself
            detailBinding.textViewDetailBenefitType.text = "Error: Request not found."
            detailBinding.linearLayoutOperatorActions.isVisible = false
            // Hide other elements too
            return
        }

        detailBinding.toolbarDetail.title = "Request #${request.id.takeLast(6).toUpperCase(Locale.ROOT)}"
        detailBinding.textViewDetailBenefitType.text = request.benefitTypeName.takeIf { it.isNotEmpty() } ?: request.benefitTypeId
        detailBinding.textViewDetailStatus.text = request.status.capitalize(Locale.ROOT)
        detailBinding.textViewDetailSubmissionDate.text = "Submitted: ${request.timestamp?.toDate()?.let { detailViewDateFormat.format(it) } ?: "N/A"}"

        // Status specific UI
        when (request.status.toLowerCase()) {
            "approved" -> {
                detailBinding.textViewDetailStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_indicator_green))
                detailBinding.cardRejectionReason.isVisible = false
            }
            "rejected" -> {
                detailBinding.textViewDetailStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_indicator_red))
                detailBinding.textViewDetailRejectionReason.text = request.rejectionReason ?: "No reason provided."
                detailBinding.cardRejectionReason.isVisible = true
            }
            "pending" -> {
                detailBinding.textViewDetailStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_indicator_yellow))
                detailBinding.cardRejectionReason.isVisible = false
            }
            else -> {
                detailBinding.textViewDetailStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.grey))
                detailBinding.cardRejectionReason.isVisible = false
            }
        }


        if (viewModel.userRole.value == UserRole.OPERATOR) {
            detailBinding.textViewDetailUserInfo.text = "User: ${request.userName} (ID: ${request.userId})"
            detailBinding.textViewDetailUserInfo.isVisible = true
            detailBinding.linearLayoutOperatorActions.isVisible = request.status.equals("pending", ignoreCase = true)
            // Hide rejection input unless reject button is clicked
            detailBinding.textInputLayoutRejectionReasonInput.isVisible = false
            detailBinding.buttonSubmitRejection.isVisible = false
        } else { // User role
            detailBinding.textViewDetailUserInfo.isVisible = false
            detailBinding.linearLayoutOperatorActions.isVisible = false
            detailBinding.textInputLayoutRejectionReasonInput.isVisible = false
            detailBinding.buttonSubmitRejection.isVisible = false
        }

        detailBinding.textViewDetailIban.text = "IBAN: ${request.iban.takeIf { it.isNotEmpty() } ?: "Not Provided"}"
        detailBinding.textViewDetailExtraInfo.text = request.extraInfo.takeIf { it.isNotEmpty() } ?: "No additional notes."

        populateDocumentsList(request.documentLinks)
    }

    private fun populateDocumentsList(documents: Map<String, String>) {
        val documentsContainer = binding.detailContent.linearLayoutDocumentsContainer
        documentsContainer.removeAllViews() // Clear previous documents

        if (documents.isEmpty()) {
            val noDocsTextView = TextView(requireContext()).apply {
                text = "No documents uploaded for this request."
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 16 }
            }
            documentsContainer.addView(noDocsTextView)
            return
        }

        val inflater = LayoutInflater.from(requireContext())
        documents.forEach { (docId, docUrl) -> // Assuming docId is a descriptive name or type
            // You might need a more descriptive name if docId is just a Firestore ID
            // For example, fetch the original document requirement name based on benefit type and docId.
            // For now, using docId as the display name.

            val docItemView = inflater.inflate(R.layout.document_item_detail, documentsContainer, false) as RelativeLayout

            val docNameTextView = docItemView.findViewById<TextView>(R.id.textView_doc_name_detail)
            val downloadButton = docItemView.findViewById<Button>(R.id.button_download_doc_detail)
            val docIcon = docItemView.findViewById<ImageView>(R.id.imageView_doc_icon_detail)

            docNameTextView.text = docId // Replace with a user-friendly name if possible
            docIcon.setImageResource(R.drawable.ic_document_placeholder) // Set appropriate icon

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
        }
    }


    override fun onStart() {
        super.onStart()
        requestAdapter?.startListening()
        // Chat adapter listening is managed when detail view becomes visible/hidden
    }

    override fun onStop() {
        super.onStop()
        requestAdapter?.stopListening()
        chatAdapter?.stopListening() // Ensure chat adapter also stops if fragment stops
    }

    override fun onDestroyView() {
        super.onDestroyView()
        requestAdapter = null // Clean up adapter
        chatAdapter = null
        _binding = null
    }
}