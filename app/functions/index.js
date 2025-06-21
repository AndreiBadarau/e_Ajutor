// functions/index.js
const functions = require("firebase-functions");
const admin = require("firebase-admin");

admin.initializeApp(); // Initialize Admin SDK only ONCE

exports.exchangeIdTokenForCustomToken = functions.https.onCall(async (data, context) => {
    // --- VERY IMPORTANT LOGGING - RETAIN FOR A BIT ---
    console.log("RUNNING REVISED exchangeIdTokenForCustomToken V2 - expecting data.data.idToken");

    console.log("Cloud Function 'exchangeIdTokenForCustomToken' called.");
    // Typo: should be console.log, not onsole.log
    console.log("Received raw data.data from SDK (client payload):", JSON.stringify(data.data));
    console.log("Type of raw data object:", typeof data);
    // If you want to see other top-level keys in the `data` object passed to onCall:
    console.log("Top-level keys in 'data' object from SDK:", Object.keys(data));

    // --- GRAB THE ACTUAL PAYLOAD FROM THE 'data' PROPERTY ---
    const payload = data.data;

    if (payload) {
        console.log("Extracted payload object:", JSON.stringify(payload));
        console.log("payload.idToken value:", payload.idToken);
        console.log("Type of payload.idToken:", typeof payload.idToken);
        console.log("All keys in payload object:", Object.keys(payload));
    } else {
        console.log("Payload object (data.data) is null or undefined.");
    }
    // --- END OF IMPORTANT LOGGING ---

    // Your existing logic, now using 'payload' instead of 'data' for your parameters:
    if (!payload || !payload.idToken) {
        console.error("Function called without an idToken in the payload (data.data).");
        throw new functions.https.HttpsError(
            "invalid-argument",
            "The function must be called with an 'idToken' argument within the data payload."
        );
    }

    const idToken = payload.idToken;
    const uid = payload.uid; // Make sure your client is sending this if you require it

    if (!uid) { // If you require UID to be sent from the client
        console.error("UID is missing from the request payload (data.data).");
        throw new functions.https.HttpsError(
            "invalid-argument",
            "The function must be called with a 'uid' argument in the data payload."
        );
    }

    try {
        console.log(`Creating custom token for UID: ${uid} based on validated idToken.`);
        // IMPORTANT: You should verify the idToken here before trusting the uid from the client
        // For example:
        // const decodedToken = await admin.auth().verifyIdToken(idToken);
        // if (decodedToken.uid !== uid) {
        //   throw new functions.https.HttpsError("unauthenticated", "Token UID does not match provided UID.");
        // }
        // Now you can trust 'uid' (or better, use decodedToken.uid)

        const customToken = await admin.auth().createCustomToken(uid); // Use the verified UID
        console.log("Successfully created custom token.");
        return { customToken: customToken };

    } catch (error) {
        console.error("Error exchanging ID token for custom token:", error);
        let errorCode = "internal";
        // Check for specific Firebase Admin SDK error codes for token verification/creation
        if (error.code === "auth/id-token-expired" || error.code === "auth/invalid-id-token") {
            errorCode = "unauthenticated";
        } else if (error.code === "auth/argument-error") {
            errorCode = "invalid-argument";
        }
        // Add more specific error handling if needed
        throw new functions.https.HttpsError(
            errorCode,
            error.message || "Failed to exchange token."
        );
    }
});