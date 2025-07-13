// functions/index.js
   const admin = require("firebase-admin");
   const { onDocumentWritten } = require("firebase-functions/v2/firestore");
   const { onDocumentCreated } = require("firebase-functions/v2/firestore");
   const { onCall } = require("firebase-functions/v2/https");

   admin.initializeApp();

   // Funcția ta existentă - RĂMÂNE NEMODIFICATĂ
   exports.exchangeIdTokenForCustomToken = onCall(
       { region: "us-central1" }, // Asigură-te că și aceasta este Gen 2 dacă vrei consistență
       async (request) => {
           const payload = request.data;
           if (!payload || !payload.idToken) {
               // Aruncă o eroare HttpsError corect pentru v2
               const { HttpsError } = require("firebase-functions/v2/https");
               throw new HttpsError("invalid-argument", "idToken is required.");
           }
           const idToken = payload.idToken;
           const uid = payload.uid;
           if (!uid) {
               const { HttpsError } = require("firebase-functions/v2/https");
               throw new HttpsError("invalid-argument", "uid is required.");
           }
           try {
               const customToken = await admin.auth().createCustomToken(uid);
               return { customToken: customToken };
           } catch (error) {
               console.error("Error creating custom token:", error);
               const { HttpsError } = require("firebase-functions/v2/https");
               // Adaptează codurile de eroare dacă e necesar
               throw new HttpsError("internal", error.message || "Failed to exchange token.");
           }
       }
   );

   // FUNCȚIA NOUĂ - Trigger Firestore Simplificat
   exports.onRequestStatusChange = onDocumentWritten(
       {
           document: "requests/{requestId}", // Monitorizează colecția corectă
           region: "us-central1"           // Specifică regiunea consistent
       },
       async (event) => { // Funcția va fi async datorită operațiilor Firestore și Messaging
           const requestId = event.params.requestId;
           const change = event.data;

           console.log(`onRequestStatusChange triggered for request: ${requestId}, Event Type: ${event.type}`);

           if (!change) {
               console.log(`No data change for request: ${requestId}`);
               return null;
           }

           const dataAfter = change.after.exists ? change.after.data() : null;
           const dataBefore = change.before.exists ? change.before.data() : null;

           // --- AICI ÎNCEPE LOGICA TA SPECIFICĂ ---

           // A. Verifică dacă este o actualizare relevantă (nu creare sau ștergere, și statusul s-a schimbat)
           if (!dataBefore || !dataAfter) {
               console.log(`Request ${requestId}: Document created or deleted. No status change notification needed for this logic.`);
               return null; // Sau gestionează creațiile/ștergerile dacă vrei alt tip de notificare
           }

           const newStatus = dataAfter.status;
           const oldStatus = dataBefore.status;

           if (newStatus === oldStatus) {
               console.log(`Request ${requestId}: Status unchanged (${newStatus}).`);
               return null;
           }

           // B. Verifică dacă noul status este unul pentru care vrei să trimiți notificare utilizatorului
           if (newStatus !== "aprobate" && newStatus !== "refuzate") {
               console.log(`Request ${requestId}: Status changed to ${newStatus}, not 'aprobate' or 'refuzate'.`);
               return null;
           }

           // C. Obține userId din documentul cererii
           const userId = dataAfter.userId; // Asigură-te că documentul 'requests' are câmpul 'userId'
           if (!userId) {
               console.error(`Request ${requestId}: Missing userId field.`);
               return null;
           }
           console.log(`Request ${requestId}: Status changed from ${oldStatus} to ${newStatus} for user ${userId}.`);

           // D. Obține token-ul FCM al utilizatorului din colecția 'users'
           let userFcmToken;
           try {
               const userDoc = await admin.firestore().collection("users").doc(userId).get();
               if (!userDoc.exists) {
                   console.error(`User document not found for userId: ${userId}`);
                   return null;
               }
               userFcmToken = userDoc.data().fcmToken; // Asigură-te că documentul user are câmpul 'fcmToken'
               if (!userFcmToken) {
                   console.log(`User ${userId} does not have an FCM token.`);
                   return null;
               }
           } catch (error) {
               console.error(`Error fetching FCM token for user ${userId}:`, error);
               return null;
           }

           // E. Construiește mesajul de notificare
           let notificationTitle;
           let notificationBody;
           const benefitTypeName = dataAfter.benefitTypeName || "Cererea ta"; // Folosește un fallback

           if (newStatus === "aprobate") {
               notificationTitle = "Cerere Aprobată!";
               notificationBody = `Cererea ta pentru ${benefitTypeName} a fost aprobată. Verifică aplicația pentru detalii.`;
           } else if (newStatus === "refuzate") {
               notificationTitle = "Cerere Respinsă";
               const reason = dataAfter.rejectionReason || "un motiv nespecificat";
               notificationBody = `Cererea ta pentru ${benefitTypeName} a fost respinsă din motivul: ${reason}. Verifică aplicația pentru detalii.`;
           } else {
               // Nu ar trebui să ajungă aici datorită verificărilor anterioare
               console.warn(`Request ${requestId}: Unexpected status ${newStatus} for notification.`);
               return null;
           }

           // F. Definește payload-ul pentru FCM
           const messagePayload = {
               token: userFcmToken, // Token-ul destinatarului
               notification: {
                   title: notificationTitle,
                   body: notificationBody,
                   // Poți adăuga și 'icon' sau 'image' dacă vrei
               },
               data: { // Date suplimentare pe care clientul le poate folosi (ex. pentru deep linking)
                   requestId: requestId,
                   newStatus: newStatus,
                   screenToOpen: "requestDetails" // Exemplu
               },
               // Poți adăuga opțiuni Android/APNS/Webpush specifice aici dacă e necesar
               // android: { notification: { click_action: "..." } }
           };

           // G. Trimite mesajul FCM
           console.log(`Attempting to send notification for request ${requestId} to user ${userId}`);
           try {
               const response = await admin.messaging().send(messagePayload);
               console.log(`Successfully sent message for request ${requestId}:`, response);
           } catch (error) {
               console.error(`Error sending FCM message for request ${requestId}:`, error);
               // Implementează logica de curățare a token-urilor invalide
               if (error.code === 'messaging/invalid-registration-token' ||
                   error.code === 'messaging/registration-token-not-registered') {

                   console.log(`FCM token for user ${userId} is invalid or not registered. Preparing to remove it from Firestore.`);

                   try {
                                       // Șterge câmpul fcmToken sau setează-l la null/o valoare specială
                                       await admin.firestore().collection("users").doc(userId).update({
                                           fcmToken: admin.firestore.FieldValue.delete() // Șterge complet câmpul
                                           // Sau: fcmToken: null // Setează la null
                                       });
                                       console.log(`Successfully removed/invalidated FCM token for user ${userId} from Firestore.`);
                                   } catch (dbError) {
                                       console.error(`Failed to remove/invalidate FCM token for user ${userId} from Firestore:`, dbError);
                                   }

               }
           }

           return null; // Semnalează finalizarea funcției
       }
   );

   exports.onNewRequestChatMessage = onDocumentCreated(
       {
           document: "requests/{requestId}/chat/{messageId}", // Monitorizează sub-colecția 'chat' din 'requests'
           region: "us-central1" // Sau regiunea ta
       },
       async (event) => {
           const requestId = event.params.requestId;
           const messageId = event.params.messageId;
           const messageData = event.data.data();

           if (!messageData) {
               console.log(`No data for new message in request ${requestId}, chat message ${messageId}.`);
               return null;
           }

           const senderId = messageData.senderId;
           const messageText = messageData.text || "Un mesaj nou"; // Folosește un fallback dacă textul lipsește

           console.log(`New chat message in request ${requestId} from sender ${senderId}: "${messageText}"`);

           // 1. Obține ID-urile participanților relevanți pentru această cerere/chat
           let requestDoc;
           try {
               requestDoc = await admin.firestore().collection("requests").doc(requestId).get();
               if (!requestDoc.exists) {
                   console.error(`Request document not found for requestId: ${requestId}`);
                   return null;
               }
           } catch (error) {
               console.error(`Error fetching request document ${requestId}:`, error);
               return null;
           }

           const requestData = requestDoc.data();
           const requestCreatorUserId = requestData.userId; // Beneficiarul cererii
           const operatorUserId = requestData.operatorId;  // Operatorul asignat (dacă există)

           let recipientId;

           // Determină cine este destinatarul notificării
           // Dacă expeditorul este creatorul cererii, notifică operatorul (dacă există)
           // Dacă expeditorul este operatorul, notifică creatorul cererii
           if (senderId === requestCreatorUserId) {
               recipientId = operatorUserId;
               if (!recipientId) {
                   console.log(`Message from request creator ${senderId} in request ${requestId}, but no operator assigned to notify.`);
                   return null;
               }
           } else if (senderId === operatorUserId) {
               recipientId = requestCreatorUserId;
               if (!recipientId) { // Teoretic, requestCreatorUserId ar trebui să existe mereu
                   console.error(`Message from operator ${senderId} in request ${requestId}, but request creator ID is missing.`);
                   return null;
               }
           } else {
               console.warn(`Sender ${senderId} in request ${requestId} is neither the request creator nor the assigned operator. No notification sent.`);
               return null; // Sau gestionează altfel acest caz, dacă e posibil
           }

           console.log(`Sender: ${senderId}, Determined recipient: ${recipientId} for chat in request ${requestId}.`);

           // 2. Obține token-ul FCM al destinatarului
           let recipientFcmToken;
           let recipientName = "Utilizator"; // Fallback name for recipient (not usually used in notification text itself)
           try {
               const userDoc = await admin.firestore().collection("users").doc(recipientId).get();
               if (!userDoc.exists) {
                   console.warn(`User document not found for recipient ${recipientId} of chat in request ${requestId}.`);
                   return null;
               }
               const userData = userDoc.data();
               recipientFcmToken = userData.fcmToken;
               recipientName = userData.firstName || userData.displayName || recipientName; // Get recipient's name for logs or future use

               if (!recipientFcmToken) {
                   console.log(`Recipient ${recipientId} (chat in request ${requestId}) does not have an FCM token.`);
                   return null;
               }
           } catch (error) {
               console.error(`Error fetching FCM token for recipient ${recipientId}:`, error);
               return null;
           }

           // 3. Obține numele expeditorului (sau folosește cel din mesaj dacă există)
           let senderDisplayName = messageData.senderName || "Cineva"; // Folosește senderName din mesaj dacă e disponibil
           if (!messageData.senderName) { // Dacă nu e în mesaj, încearcă să-l iei din users
               try {
                   const senderDoc = await admin.firestore().collection("users").doc(senderId).get();
                   if (senderDoc.exists) {
                       senderDisplayName = senderDoc.data().firstName || senderDoc.data().displayName || senderDisplayName;
                   }
               } catch (error) {
                   console.warn(`Could not fetch sender's name (user ${senderId}):`, error);
               }
           }


           // 4. Construiește și trimite mesajul
           const benefitTypeName = requestData.benefitTypeName || `cererea ${requestId.substring(0,6)}...`; // Numele beneficiului sau un ID scurt al cererii

           const notificationPayload = {
               token: recipientFcmToken,
               notification: {
                   title: `Mesaj nou în ${benefitTypeName} de la ${senderDisplayName}`,
                   body: `${messageText.length > 80 ? messageText.substring(0, 77) + "..." : messageText}`,
                   // tag: requestId // Grupează notificările per cerere
               },
               data: {
                   requestId: requestId,
                   chatMessageId: messageId,
                   senderId: senderId,
                   screenToOpen: "requestChatScreen" // Specifică clientului unde să navigheze
               },
               // android: {
               //   notification: {
               //     channel_id: "request_chat_channel" // Canal specific pentru chat-ul cererilor
               //   }
               // }
           };

           console.log(`Attempting to send chat notification to ${recipientId} (token: ${recipientFcmToken.substring(0,10)}...) for request ${requestId}`);
           try {
               await admin.messaging().send(notificationPayload);
               console.log(`Successfully sent chat notification to ${recipientId} for request ${requestId}`);
           } catch (fcmError) {
               console.error(`Error sending FCM message to ${recipientId} for request ${requestId}:`, fcmError);
               if (fcmError.code === 'messaging/invalid-registration-token' ||
                   fcmError.code === 'messaging/registration-token-not-registered') {
                   console.log(`Invalid token for recipient ${recipientId}. Consider removing it.`);
                   // admin.firestore().collection("users").doc(recipientId).update({ fcmToken: admin.firestore.FieldValue.delete() });
               }
           }
           return null;
       }
   );

