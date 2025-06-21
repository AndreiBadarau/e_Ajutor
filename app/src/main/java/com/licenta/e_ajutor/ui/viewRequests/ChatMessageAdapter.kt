package com.licenta.e_ajutor.ui.viewRequests

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.firebase.ui.firestore.FirestoreRecyclerAdapter
import com.firebase.ui.firestore.FirestoreRecyclerOptions
import com.google.firebase.auth.FirebaseAuth
import com.licenta.e_ajutor.R
import com.licenta.e_ajutor.model.ChatMessage
import java.text.SimpleDateFormat
import java.util.*

class ChatMessageAdapter(
    options: FirestoreRecyclerOptions<ChatMessage>
) : FirestoreRecyclerAdapter<ChatMessage, ChatMessageAdapter.ChatMessageViewHolder>(options) {

    private val currentUserId = FirebaseAuth.getInstance().currentUser?.uid
    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())

    companion object {
        private const val VIEW_TYPE_USER_MESSAGE = 1
        private const val VIEW_TYPE_OPERATOR_MESSAGE = 2
    }

    override fun getItemViewType(position: Int): Int {
        val message = getItem(position)
        return if (message.senderId == currentUserId) {
            VIEW_TYPE_USER_MESSAGE
        } else {
            VIEW_TYPE_OPERATOR_MESSAGE
        }
    }

    inner class ChatMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // These IDs must match your chat_message_item_user.xml and chat_message_item_operator.xml
        private val messageText: TextView? = itemView.findViewById(R.id.textView_chat_message_text_user)
            ?: itemView.findViewById(R.id.textView_chat_message_text_operator)

        private val messageTimestamp: TextView? = itemView.findViewById(R.id.textView_chat_message_timestamp_user)
            ?: itemView.findViewById(R.id.textView_chat_message_timestamp_operator)

        fun bind(chatMessage: ChatMessage) {
            messageText?.text = chatMessage.text
            messageTimestamp?.text = chatMessage.timestamp?.toDate()?.let { timeFormat.format(it) } ?: ""
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatMessageViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        val view = if (viewType == VIEW_TYPE_USER_MESSAGE) {
            layoutInflater.inflate(R.layout.chat_message_item_user, parent, false)
        } else {
            layoutInflater.inflate(R.layout.chat_message_item_operator, parent, false)
        }
        return ChatMessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatMessageViewHolder, position: Int, model: ChatMessage) {
        holder.bind(model)
    }
}