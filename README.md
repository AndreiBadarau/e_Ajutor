# e-Ajutor

**DEZVOLTAREA UNEI APLICAȚII MOBILE PENTRU DIGITALIZAREA PROCESULUI DE SOLICITARE A BENEFICIILOR SOCIALE În ROMÂNIA**

Aplicația **e-Ajutor** oferă un flux complet pentru cetățeni: înregistrare/autentificare, completare cerere, încărcare documente, verificare automata a documentelor cu AI, trimitere și comunicare în timp real cu operatorii autorizați. Implementarea este nativ Android (Kotlin), backend pe Firebase și integrare cu OpenAI pentru verificări automate.

---

## Cuprins

1. Descriere generală
2. Funcționalități principale
3. Arhitectură și design
4. Tehnologii utilizate
5. Cerințe sistem
6. Instalare și configurare (developer)
7. Rulare și build
8. Structura bazei de date (Firestore) și Storage
9. Autentificare biometrică — fluxul criptare/decriptare (rezumat)
10. Testare și validare
11. Securitate și confidențialitate
12. Depanare — probleme comune
13. Contribuții
14. Licence & Contact
15. Anexe & Resurse utile

---

## Descriere generală

e-Ajutor este o aplicație mobilă construită pentru a simplifica procesul birocratic de depunere a cererilor pentru beneficii sociale (indemnizație de șomaj, alocație pentru copii, concediu medical etc.). Utilizatorii pot completa formulare digitale, încărca documente justificative, primi validări automate (AI) și comunica în timp real cu operatorii din instituțiile publice. Operatorii gestionează cererile din aceeași aplicație (layout diferențiat în funcție de rol).

---

## Funcționalități principale

* Înregistrare și autentificare (email/parolă, Google Sign-In, MFA, biometric).
* Activare și utilizare autentificare biometrică cu token criptat.
* Creare, editare și trimitere cereri de beneficii (formular + documente).
* Încărcare și stocare documente în Firebase Storage.
* Validare automata a documentelor cu OpenAI (OCR + reguli).
* Chat per cerere (sub-colecție `requests/{requestId}/chat`).
* Distribuire automata a cererilor către operatori pe baza adresei.
* Notificări push pentru schimbări de status.

---

## Arhitectură și design

Arhitectura este de tip client-server, cu aplicația Android (MVVM) ca frontend și Firebase ca backend.

**Frontend:** Activități, ViewModel-uri, clase utilitare.
**Backend:** Firebase Authentication, Firestore, Storage, Cloud Functions, App Check.

---

## Tehnologii utilizate

* **Frontend:** Kotlin, Android Studio, XML declarativ, Material Design
* **Backend:** Firebase Authentication, Firestore, Storage, App Check, Cloud Functions (Node.js)
* **Integrare AI:** OpenAI API
* **APIs:** Google Places API
* **Criptografie:** AndroidKeyStore, RSA/AES
* **Build:** Gradle (Kotlin DSL)

---

## Cerințe sistem

| Componentă      | Specificații minime                |
| --------------- | ---------------------------------- |
| **Android SDK** | minSdk = 26 (Android 8.0)          |
| **Target SDK**  | 35                                 |
| **Memorie**     | ≥ 4 GB RAM                         |
| **Backend**     | Firebase Project + Cloud Functions |
| **Altele**      | Node.js >= 16, Firebase CLI        |

---

## Instalare și configurare (developer)

1. Clonează repo-ul:

   ```bash
   git clone https://github.com/<username>/e-ajutor.git
   cd e-ajutor
   ```
2. Creează proiect Firebase și adaugă `google-services.json`.
3. Activează Authentication, Firestore, Storage, App Check.
4. Setează cheia OpenAI local, în `local.properties`:

   ```properties
   OPENAI_API_KEY=sk-...
   ```
5. Rulează aplicația:

   ```bash
   ./gradlew assembleDebug
   ```

---

## Structura bazei de date (Firestore)

* **users/{userId}** → profil utilizator
* **requests/{requestId}** → cereri

  * sub-colecție `chat/{messageId}` → mesaje text / fișiere
* **Firebase Storage:** `/documents/{userId}/{requestId}` și `/chat_files/{userId}/{requestId}`

---

## Autentificare biometrică — flux rezumat

1. La autentificare, tokenul Firebase este criptat folosind chei stocate în AndroidKeyStore.
2. Tokenul criptat este salvat în `SharedPreferences`.
3. La autentificarea biometrică, aplicația decriptează tokenul folosind cheia privată protejată de hardware.

---

## Testare și validare

* **Unit testing:** logica ViewModel și utilitare
* **UI testing:** fluxuri de autentificare, creare cerere
* **Manual testing:** testarea interacțiunii utilizator-operator
* **Securitate:** validarea criptării, reguli Firestore, App Check

---

## Securitate și confidențialitate

* Nu se salvează tokenuri în clar
* Acces restricționat prin reguli Firestore/Storage
* App Check activ pentru protecția backend-ului
* Respectarea principiilor GDPR: colectare minimă de date

---

## Depanare

* **IndexOutOfBoundsException în RecyclerView:** folosește `WrapContentLinearLayoutManager`
* **Probleme cu autentificarea Google:** verifică SHA-1 și SHA-256 în Firebase
* **Cloud Functions nu rulează:** `firebase deploy --only functions`

---

## Contribuții

1. Fork repo-ul
2. Creează un branch nou: `feature/<nume>`
3. Rulează testele înainte de PR: `./gradlew check`

---

## Licence & Contact

Licență recomandată: **MIT**
Contact: `cristian.andrei@example.com`

---

## Anexe & Resurse utile

* **Diagrame UML:** `docs/diagrams/`
* **Documentație:** Firebase, Android, OpenAI
* **Comenzi utile:** `firebase deploy`, `./gradlew assembleDebug`

---
