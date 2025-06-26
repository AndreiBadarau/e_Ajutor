package com.licenta.e_ajutor.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.ServerTimestamp // Or use Timestamp.now() as in ViewModel

enum class MessageType { TEXT, IMAGE, FILE }

data class ChatMessage(
    var id: String = "", // Make sure this is 'id' if you named it 'id' in ViewModel
    var senderId: String = "",
    var senderName: String? = null,
    var text: String? = null, // Can be null for file messages if caption is part of file info
    @ServerTimestamp var timestamp: Timestamp? = null, // If using @ServerTimestamp
    // OR var timestamp: Timestamp = Timestamp.now(), // If setting manually
    var messageType: MessageType = MessageType.TEXT, // Good default
    var fileUrl: String? = null,
    var fileName: String? = null,
    var fileSize: Long? = null,
    var fileMimeType: String? = null
)