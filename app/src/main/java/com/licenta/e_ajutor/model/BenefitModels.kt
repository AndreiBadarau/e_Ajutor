package com.licenta.e_ajutor.model

// Represents a specific document that might be required
data class DocumentRequirement(
    val id: String, // Unique internal ID, e.g., "id_card_scan"
    val displayName: String // What the user sees, e.g., "Scan of Identity Card"
) {
    constructor(): this("", "")
}

// Represents a type of social benefit
data class BenefitType(
    var id: String, // Unique internal ID, e.g., "unemployment_benefit"
    val name: String, // What the user sees in the dropdown, e.g., "Unemployment Benefit"
    val requiredDocuments: List<DocumentRequirement>
) {
    constructor(): this("", "", emptyList())
}

data class BenefitDetails(
    var details: String = "",
    var documentsRequired: String = "",
    var source: String = "",
    var value: String = ""
) {
    constructor(): this("", "", "", "")
}

