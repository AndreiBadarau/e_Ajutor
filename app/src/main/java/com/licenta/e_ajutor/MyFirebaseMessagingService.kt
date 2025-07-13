package com.licenta.e_ajutor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.licenta.e_ajutor.activity.MainUserActivity

class MyFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "MyFirebaseMsgService"
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d(TAG, "From: ${remoteMessage.from}")

        // Verifică dacă mesajul conține un payload de date
        remoteMessage.data.isNotEmpty().let {
            Log.d(TAG, "Message data payload: " + remoteMessage.data)
            // Aici poți gestiona mesajele de date, chiar și când aplicația e în prim-plan.
            // Pentru notificări simple aprobate/respinse, payload-ul 'notification' este adesea suficient.
        }

        // Verifică dacă mesajul conține un payload de notificare.
        remoteMessage.notification?.let {
            Log.d(TAG, "Message Notification Body: ${it.body}")
            sendNotification(it.title, it.body)
        }
    }

    override fun onNewToken(token: String) {
        Log.d(TAG, "Refreshed token: $token")
        // Dacă ai nevoie să trimiți acest token nou către serverul tău de aplicații, fă-o aici.
        // În cazul nostru, vom actualiza token-ul în Firestore.
        sendRegistrationToServer(token)
    }

    private fun sendRegistrationToServer(token: String?) {
        val userId = FirebaseAuth.getInstance().currentUser?.uid
        if (userId != null && token != null) {
            val userDocRef =
                FirebaseFirestore.getInstance().collection("users").document(userId)
            userDocRef.update("fcmToken", token)
                .addOnSuccessListener { Log.d(TAG, "FCM token updated via onNewToken for user $userId") }
                .addOnFailureListener { e -> Log.w(TAG, "Error updating FCM token via onNewToken", e) }
        }
    }

    private fun sendNotification(messageTitle: String?, messageBody: String?) {
        if (messageTitle == null || messageBody == null) {
            Log.w(TAG, "Cannot send notification, title or body is null")
            return
        }

        val intent = Intent(this, MainUserActivity::class.java) // Activitatea de deschis
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // Poți adăuga extra-uri la intent dacă vrei să navighezi la un ecran specific
        // intent.putExtra("navigateTo", "requests_list")

        val pendingIntent = PendingIntent.getActivity(
            this, 0 /* Request code */, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = getString(R.string.default_notification_channel_id) // Definește în strings.xml
        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher) // Adaugă o iconiță de notificare
            .setContentTitle(messageTitle)
            .setContentText(messageBody)
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH) // Pentru vizibilitate mai bună

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // De la Android Oreo (API 26) în sus, canalele de notificare sunt necesare.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Notificări Cereri", // Numele canalului vizibil utilizatorului
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        notificationManager.notify(0 /* ID of notification */, notificationBuilder.build())
    }
}