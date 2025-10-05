# e-Ajutor

**DEVELOPMENT OF A MOBILE APPLICATION TO DIGITALIZE THE SOCIAL BENEFIT APPLICATION PROCESS IN ROMANIA**

The **e-Ajutor** application offers a complete flow for citizens: registration/authentication, application completion, document upload, automatic verification of documents with AI, sending and real-time communication with authorized operators. The implementation is native Android (Kotlin), backend on Firebase and integration with OpenAI for automatic verifications.

---

## General description

e-Ajutor is a mobile application built to simplify the bureaucratic process of submitting applications for social benefits (unemployment benefit, child allowance, sick leave, etc.). Users can fill out digital forms, upload supporting documents, receive automatic validations (AI) and communicate in real time with operators in public institutions. Operators manage applications from the same application (differentiated layout depending on role).
---

## Main functionalities

* Registration and authentication (email/password, Google Sign-In, MFA, biometric).
* Enable and use biometric authentication with encrypted token.
* Create, edit and send benefit requests (form + documents).
* Upload and store documents in Firebase Storage.
* Automatic validation of documents with OpenAI (OCR + rules).
* Chat per request.
* Automatic distribution of requests to operators based on address.
* Push notifications for status changes.
---

## Architecture and design

The architecture is client-server, with the Android application (MVVM) as the frontend and Firebase as the backend.

**Frontend:** Activities, ViewModels, utility classes.
**Backend:** Firebase Authentication, Firestore, Storage, Cloud Functions, App Check.

---

## Technologies used

* **Frontend:** Kotlin, Android Studio, Declarative XML, Material Design
* **Backend:** Firebase Authentication, Firestore, Storage, App Check, Cloud Functions (Node.js)
* **AI Integration:** OpenAI API
* **APIs:** Google Places API
* **Cryptography:** AndroidKeyStore, RSA/AES
* **Build:** Gradle (Kotlin DSL)

## Structura bazei de date (Firestore)

* **users/{userId}** → profil utilizator
* **requests/{requestId}** → cereri

  * sub-colecție `chat/{messageId}` → mesaje text / fișiere
* **Firebase Storage:** `/documents/{userId}/{requestId}` și `/chat_files/{userId}/{requestId}`

---

## Biometric authentication — summary flow

1. Upon authentication, the Firebase token is encrypted using keys stored in the AndroidKeyStore.
2. The encrypted token is saved in `SharedPreferences`.
3. Upon biometric authentication, the app decrypts the token using the private key protected by the hardware.

---

## Testing and validation

* **Unit testing:** ViewModel logic and utilities
* **UI testing:** authentication flows, request creation
* **Manual testing:** user-operator interaction testing
* **Security:** encryption validation, Firestore rules, App Check

---

## Security and privacy

* No tokens are saved in the clear
* Access restricted by Firestore/Storage rules
* App Check active for backend protection
* GDPR compliance: minimal data collection

---
