package com.licenta.e_ajutor.model

import com.google.firebase.Timestamp

data class UserRequest(
    val id: String = "",
    val userId: String = "",
    val userName: String = "",
    val operatorId: String = "",
    val operatorName: String = "",
    val benefitTypeId: String = "",
    val benefitTypeName: String = "",
    val location: String = "",
    val documentLinks: Map<String, String> = emptyMap(), // docId -> Storage URL
    val status: String = "in curs",
    val timestamp: Timestamp = Timestamp.now(),
    val iban: String = "",
    val extraInfo: String = "",
    val rejectionReason: String = ""
)