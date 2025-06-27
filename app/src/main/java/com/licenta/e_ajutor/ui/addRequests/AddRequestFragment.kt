package com.licenta.e_ajutor.ui.addRequests

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.licenta.e_ajutor.R
import com.licenta.e_ajutor.activity.MainUserActivity
import com.licenta.e_ajutor.databinding.FragmentAddRequestBinding
import com.licenta.e_ajutor.model.BenefitType

class AddRequestFragment : Fragment() {

    private var _binding: FragmentAddRequestBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AddRequestViewModel by viewModels()

    private lateinit var documentPickerLauncher: ActivityResultLauncher<Intent>
    private var pendingDocIdForUpload: String? = null // To store which doc is being picked

    private val checkboxMapUi = mutableMapOf<String, CheckBox>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddRequestBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupGdprLink()
        setupDocumentPickerLauncher()
        setupClickListeners()
        observeViewModel()
    }

    private fun setupGdprLink() {
        val gdprCheckbox = binding.gdprCheckbox
        val fullText = getString(R.string.gdpr_checkbox_text)
        val linkText = "(GDPR)"
        val gdprUrl = "https://www.dataprotection.ro/?page=noua%20_pagina_regulamentul_GDPR"

        val spannableString = SpannableString(fullText)

        val clickableSpan = object : ClickableSpan() {
            override fun onClick(widget: View) {
                // Open the GDPR URL in a browser
                val intent = Intent(Intent.ACTION_VIEW, gdprUrl.toUri())
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    // Handle case where no browser is available or URL is invalid
                    Toast.makeText(requireContext(), "Link-ul nu a putut fi deschis.", Toast.LENGTH_SHORT).show()
                }
            }

            override fun updateDrawState(ds: TextPaint) {
                super.updateDrawState(ds)
                // Style the link (optional)
                ds.isUnderlineText = true // Add underline
                // You can also set ds.linkColor here if not using theme's default
                // ds.color = ContextCompat.getColor(requireContext(), R.color.your_link_color)
            }
        }

        val startIndex = fullText.indexOf(linkText)
        if (startIndex != -1) { // Check if the linkText was found
            val endIndex = startIndex + linkText.length
            spannableString.setSpan(
                clickableSpan,
                startIndex,
                endIndex,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            gdprCheckbox.text = spannableString
            gdprCheckbox.movementMethod = LinkMovementMethod.getInstance() // **Crucial for making links clickable**
        } else {
            // Log or handle the case where "(GDPR)" isn't found in your string
            // This might happen if the text changes or is translated without the keyword.
        }
    }

    private fun setupDocumentPickerLauncher() {
        documentPickerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    pendingDocIdForUpload?.let { docId ->
                        viewModel.uploadFile(docId, uri)
                    }
                }
            } else {
                Toast.makeText(context, "Nu există niciun fișier selectat", Toast.LENGTH_SHORT).show()
            }
            pendingDocIdForUpload = null
        }
    }

    private fun setupClickListeners() {
        binding.uploadDocumentButton.setOnClickListener {
            showDocumentSelectionDialog()
        }

        binding.submitRequestButton.setOnClickListener {
            val selectedBenefit = viewModel.selectedBenefitType.value
            if (selectedBenefit == null) {
                Toast.makeText(requireContext(), "Vă rugăm să selectați un tip de beneficii.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            viewModel.submitRequest(
                benefitTypeIdFromSpinner = selectedBenefit.id, // Use ID from ViewModel's selected type
                iban = binding.ibanEditText.text.toString(),
                extraInfo = binding.extraInfoEditText.text.toString(),
                gdprAgreed = binding.gdprCheckbox.isChecked
            )
        }
    }

    private fun showDocumentSelectionDialog() {
        val currentBenefit = viewModel.selectedBenefitType.value ?: return
        val requiredDocs = currentBenefit.requiredDocuments

        val docDisplayNames = requiredDocs.map { it.displayName }.toTypedArray()
        val docIds = requiredDocs.map { it.id }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("Selectați document pentru a Încărca/Gestiona")
            .setItems(docDisplayNames) { _, which ->
                val selectedDocId = docIds[which]
                val selectedDocDisplayName = docDisplayNames[which] // For prompts

                // Check if this document is already uploaded
                if (viewModel.uploadedDocuments.value?.containsKey(selectedDocId) == true) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Gestionează '${selectedDocDisplayName}'")
                        .setMessage("Acest document este deja încărcat. Ce ai vrea să faci?")
                        .setPositiveButton("Înlocuire") { _, _ ->
                            pendingDocIdForUpload = selectedDocId
                            launchFilePicker()
                        }
                        .setNegativeButton("Şterge") { _, _ ->
                            viewModel.deleteDocument(selectedDocId) {
                                // Optional: Could add a small delay or visual cue before picker if needed
                                // For now, no action needed here as LiveData will update UI
                            }
                        }
                        .setNeutralButton("Anulează", null)
                        .show()
                } else {
                    pendingDocIdForUpload = selectedDocId
                    launchFilePicker()
                }
            }
            .setNegativeButton("Anulează", null)
            .show()
    }

    private fun launchFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "image/*"))
        }
        documentPickerLauncher.launch(intent)
    }


    private fun observeViewModel() {
        viewModel.benefitTypes.observe(viewLifecycleOwner) { benefitTypes ->
            if (benefitTypes.isNotEmpty()) {
                val adapter = ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_spinner_item,
                    benefitTypes.map { it.name }
                )
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.benefitTypeSpinner.adapter = adapter

                binding.benefitTypeSpinner.onItemSelectedListener =
                    object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(
                            parent: AdapterView<*>?,
                            view: View?,
                            position: Int,
                            id: Long
                        ) {
                            viewModel.onBenefitTypeSelected(benefitTypes[position])
                        }
                        override fun onNothingSelected(parent: AdapterView<*>?) {}
                    }
                // If there's already a selected type in ViewModel (e.g. on config change), set spinner
                viewModel.selectedBenefitType.value?.let { currentSelected ->
                    val position = benefitTypes.indexOfFirst { it.id == currentSelected.id }
                    if (position != -1) {
                        binding.benefitTypeSpinner.setSelection(position)
                    }
                } ?: run {
                    // Auto-select the first one if nothing is selected yet and list is not empty
                    if (benefitTypes.isNotEmpty() && viewModel.selectedBenefitType.value == null) {
                        binding.benefitTypeSpinner.setSelection(0)
                        viewModel.onBenefitTypeSelected(benefitTypes[0])
                    }
                }
            }
        }

        viewModel.selectedBenefitType.observe(viewLifecycleOwner) { benefitType ->
            benefitType?.let {
                binding.benefitDescription.text =
                    getString(R.string.please_upload_all_required_documents_for, it.name)
                // THIS CALL IS GOOD HERE to build the initial list of checkboxes
                updateRequiredDocsUI(it) // Renamed for clarity
                // Clear other fields when benefit type changes
                binding.ibanEditText.setText("")
                binding.extraInfoEditText.setText("")
                binding.gdprCheckbox.isChecked = false
            } ?: run {
                binding.benefitDescription.text =
                    getString(R.string.select_a_benefit_type_to_see_requirements)
                binding.requiredDocsContainer.removeAllViews()
                checkboxMapUi.clear()
            }
        }

        viewModel.uploadedDocuments.observe(viewLifecycleOwner) { uploadedDocsMap ->
            // Update the state of EXISTING checkboxes based on what's in uploadedDocsMap
            checkboxMapUi.forEach { (docId, checkBox) ->
                checkBox.isChecked = uploadedDocsMap.containsKey(docId)
            }
            // You might also want to update the overall status, e.g., "X of Y documents uploaded"
            // based on checkboxMapUi.size and uploadedDocsMap.size if selectedBenefitType is not null
            val currentBenefit = viewModel.selectedBenefitType.value
            if (currentBenefit != null) {
                val totalRequired = currentBenefit.requiredDocuments.size
                val uploadedCount = uploadedDocsMap.size
                binding.uploadStatusText.text =
                    getString(R.string.uploaded_of_documents, uploadedCount, totalRequired)
                // If all are uploaded, you could change the text to "All documents uploaded."
                // and maybe even change the button text or disable it.
                if (uploadedCount == totalRequired && totalRequired > 0) {
                    binding.uploadStatusText.text =
                        getString(R.string.all_required_documents_uploaded)
                } else if (totalRequired == 0) {
                    binding.uploadStatusText.text =
                        getString(R.string.no_documents_required_for_this_benefit_type)
                }
            } else {
                binding.uploadStatusText.text = getString(R.string.select_a_benefit_type)
            }
        }

        viewModel.uploadStatusText.observe(viewLifecycleOwner) { status ->
            binding.uploadStatusText.text = status
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.submitRequestButton.isEnabled = !isLoading
            binding.uploadDocumentButton.isEnabled = !isLoading
            // Consider disabling other input fields too during loading
            binding.benefitTypeSpinner.isEnabled = !isLoading
            binding.ibanEditText.isEnabled = !isLoading
            binding.extraInfoEditText.isEnabled = !isLoading
            binding.gdprCheckbox.isEnabled = !isLoading
        }

        viewModel.uiToastEvent.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let { message ->
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            }
        }

        viewModel.navigateToSeeRequestsEvent.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let {
                // Ensure activity is of correct type before accessing its specific binding
                (activity as? MainUserActivity)?.binding?.navView?.selectedItemId = R.id.navigation_see_requests
            }
        }
    }

    private fun updateRequiredDocsUI(benefitType: BenefitType) {
        binding.requiredDocsContainer.removeAllViews()
        checkboxMapUi.clear() // Clear the map for the new set of checkboxes

        val currentUploadedDocs = viewModel.uploadedDocuments.value ?: emptyMap()

        for (docRequirement in benefitType.requiredDocuments) {
            val checkBox = CheckBox(requireContext()).apply {
                text = docRequirement.displayName
                isEnabled = false // Checkbox is not for user interaction, just status
                isChecked = currentUploadedDocs.containsKey(docRequirement.id)
                // You can add more styling here if needed
            }
            checkboxMapUi[docRequirement.id] = checkBox
            binding.requiredDocsContainer.addView(checkBox)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.benefitTypeSpinner.onItemSelectedListener = null // Clean up listener
        _binding = null
    }
}