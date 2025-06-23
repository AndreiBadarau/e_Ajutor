package com.licenta.e_ajutor.ui.viewRequests // Adjust to your package structure

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.firebase.ui.firestore.FirestoreRecyclerAdapter
import com.firebase.ui.firestore.FirestoreRecyclerOptions
import com.google.android.material.card.MaterialCardView
import com.licenta.e_ajutor.R
import com.licenta.e_ajutor.model.UserRequest
import java.text.SimpleDateFormat
import java.util.Locale

class RequestAdapter(
    options: FirestoreRecyclerOptions<UserRequest>,
    private val context: Context,
    val currentRole: UserRole, // Pass the current user's role
    private val onItemClick: (UserRequest) -> Unit
) : FirestoreRecyclerAdapter<UserRequest, RequestAdapter.RequestViewHolder>(options) {

    private val listItemDateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RequestViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.request_item_layout, parent, false)
        return RequestViewHolder(view)
    }

    override fun onBindViewHolder(holder: RequestViewHolder, position: Int, model: UserRequest) {
        holder.bind(model)
    }

    inner class RequestViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardView: MaterialCardView = itemView.findViewById(R.id.request_card_view)
        private val benefitTypeTextView: TextView = itemView.findViewById(R.id.textView_benefit_type_item)
        private val statusTextView: TextView = itemView.findViewById(R.id.textView_status_item)
        private val dateTextView: TextView = itemView.findViewById(R.id.textView_date_item)
        private val userIdentifierTextView: TextView = itemView.findViewById(R.id.textView_user_identifier_item) // For Operator view
        private val statusIndicatorView: View = itemView.findViewById(R.id.view_status_indicator_item)
        private val iconBenefit: ImageView = itemView.findViewById(R.id.imageView_benefit_icon_item) // Optional icon

        fun bind(request: UserRequest) {
            benefitTypeTextView.text = request.benefitTypeName.takeIf { it.isNotEmpty() } ?: request.benefitTypeId
            dateTextView.text = request.timestamp?.toDate()?.let { listItemDateFormat.format(it) } ?: "N/A"
            statusTextView.text = request.status.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }


            // Set status color and indicator
            when (request.status.lowercase(Locale.ROOT)) {
                "approved" -> {
                    statusTextView.setTextColor(ContextCompat.getColor(context, R.color.status_indicator_green))
                    statusIndicatorView.setBackgroundColor(ContextCompat.getColor(context, R.color.status_indicator_green))
                }
                "rejected" -> {
                    statusTextView.setTextColor(ContextCompat.getColor(context, R.color.status_indicator_red))
                    statusIndicatorView.setBackgroundColor(ContextCompat.getColor(context, R.color.status_indicator_red))
                }
                "pending" -> {
                    statusTextView.setTextColor(ContextCompat.getColor(context, R.color.status_indicator_yellow))
                    statusIndicatorView.setBackgroundColor(ContextCompat.getColor(context, R.color.status_indicator_yellow))
                }
                else -> {
                    statusTextView.setTextColor(ContextCompat.getColor(context, R.color.grey)) // Define this color
                    statusIndicatorView.setBackgroundColor(ContextCompat.getColor(context, R.color.grey_medium)) // Define this color
                }
            }

            // Show user identifier if the current user is an OPERATOR
            if (currentRole == UserRole.OPERATOR) {
                userIdentifierTextView.visibility = View.VISIBLE
                // You might want to display userName or a truncated userId
                userIdentifierTextView.text = "User: ${request.userName.takeIf { it.isNotEmpty() } ?: request.userId.take(8)+"..."}"
            } else {
                userIdentifierTextView.visibility = View.GONE
            }

            // Optional: Set an icon based on benefit type (example)
            // You would need a mapping from benefitTypeId/Name to drawable resources
            when (request.benefitTypeId.lowercase(Locale.ROOT)) {
                "somaj" -> iconBenefit.setImageResource(R.drawable.ic_work_off) // Example icon
                "locuinta" -> iconBenefit.setImageResource(R.drawable.ic_home) // Example icon
                else -> iconBenefit.setImageResource(R.drawable.ic_benefit_placeholder) // Default placeholder
            }


            itemView.setOnClickListener {
                onItemClick(getItem(adapterPosition)) // Pass the specific request object
            }
        }
    }

    override fun onDataChanged() {
        super.onDataChanged()
        // The fragment can observe this or use adapter.itemCount directly
        // Consider passing a lambda to the fragment to notify of data changes if needed
        // e.g., onDataChangedCallback?.invoke(itemCount == 0)
    }
}