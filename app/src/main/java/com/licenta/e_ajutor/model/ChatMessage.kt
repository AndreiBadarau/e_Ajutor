package com.licenta.e_ajutor.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp

data class ChatMessage(
    @DocumentId var id: String = "",
    val senderId: String = "",
    val text: String = "",
    @ServerTimestamp val timestamp: Timestamp? = null
) {
    // No-argument constructor for Firestore
    constructor() : this("", "", "", null)
}