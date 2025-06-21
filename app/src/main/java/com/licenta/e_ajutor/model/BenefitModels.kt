package com.licenta.e_ajutor.model // Your package name

// Represents a specific document that might be required
data class DocumentRequirement(
    val id: String, // Unique internal ID, e.g., "id_card_scan"
    val displayName: String // What the user sees, e.g., "Scan of Identity Card"
)

// Represents a type of social benefit
data class BenefitType(
    val id: String, // Unique internal ID, e.g., "unemployment_benefit"
    val name: String, // What the user sees in the dropdown, e.g., "Unemployment Benefit"
    val requiredDocuments: List<DocumentRequirement>
)