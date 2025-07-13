package com.licenta.e_ajutor.ui.addRequests

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.licenta.e_ajutor.R
import com.licenta.e_ajutor.databinding.FragmentInformationRequestBinding
import com.licenta.e_ajutor.model.BenefitDetails
import com.licenta.e_ajutor.model.BenefitType

class InformationAddRequestFragment : Fragment() {

    private var _binding: FragmentInformationRequestBinding? = null
    private val binding get() = _binding!!

    private lateinit var spinner: Spinner
    private lateinit var textViewDetails: TextView
    private lateinit var textViewDocuments: TextView
    private lateinit var textViewSource: TextView
    private lateinit var textViewValue: TextView

    private val benefitList = mutableListOf<BenefitType>()
    private val benefitNames = mutableListOf<String>()

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentInformationRequestBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Presupunând că ai un buton cu id "buttonGoToForm" în fragment_information_add_request.xml
        binding.buttonGoToForm.setOnClickListener {
            // Navighează la fragmentul de formular folosind acțiunea definită
            findNavController().navigate(R.id.action_information_to_add_request_form)
        }

        spinner = view.findViewById(R.id.benefitTypeSpinner)
        textViewDetails = view.findViewById(R.id.benefitDescription)
        textViewDocuments = view.findViewById(R.id.documentsRequired)
        textViewSource = view.findViewById(R.id.sourceText)
        textViewValue = view.findViewById(R.id.value)

        loadBenefitTypes()
        setupSpinnerListener()
    }

    private fun loadBenefitTypes() {
        FirebaseFirestore.getInstance().collection("benefit_types")
            .get()
            .addOnSuccessListener { result ->
                benefitList.clear()
                benefitNames.clear()

                for (doc in result) {
                    val benefit = doc.toObject(BenefitType::class.java)
                    benefit.id = doc.id
                    benefitList.add(benefit)
                    benefitNames.add(benefit.name)
                }

                val adapter = ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_spinner_item,
                    benefitNames
                )
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinner.adapter = adapter
            }
            .addOnFailureListener {
                Toast.makeText(requireContext(), "Eroare la încărcarea beneficiilor", Toast.LENGTH_SHORT).show()
            }
    }

    private fun setupSpinnerListener() {
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val selectedBenefit = benefitList[position]
                val detailId = selectedBenefit.id

                FirebaseFirestore.getInstance()
                    .collection("benefit_type_details")
                    .document(detailId)
                    .get()
                    .addOnSuccessListener { doc ->
                        val details = doc.toObject(BenefitDetails::class.java)
                        details?.let {
                            textViewDetails.text = it.details
                            textViewDocuments.text = it.documentsRequired
                            textViewSource.text = it.source
                            textViewValue.text = it.value
                        }
                    }
                    .addOnFailureListener {
                        Toast.makeText(requireContext(), "Eroare la încărcarea detaliilor", Toast.LENGTH_SHORT).show()
                    }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Do nothing
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
