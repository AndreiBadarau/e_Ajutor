const admin = require("firebase-admin");



const serviceAccount = require("C:/Users/Emilut/AndroidStudioProjects/eAjutor/app/e-ajutor-firebase-adminsdk-fbsvc-dba103831f.json");

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount)
});

const firstAdminUID = "4sg8PMvL5ChBW8d5pYlqwgzpsCx2";

async function setInitialAdminClaims() {
  if (!firstAdminUID){
    console.error("ERROR: 'firstAdminUID' is not set correctly in the script. Please replace the placeholder with a real User UID.");
    return;
  }
  if (!serviceAccount || !serviceAccount.project_id) { // Basic check for service account
      console.error("ERROR: Service account file path might be incorrect or the file is invalid.");
      return;
  }

  try {
    // Set both 'admin' and 'operator' if your first admin should also be an operator.
    // Or just { admin: true } if they are only an admin for managing other roles.
    await admin.auth().setCustomUserClaims(firstAdminUID, { admin: true, operator: true }); // You can adjust claims as needed
    console.log(`SUCCESS: Claims { admin: true, operator: true } successfully set for UID: ${firstAdminUID}`);
    console.log("IMPORTANT: The user must sign out and then sign back into your app for these new claims to take effect on their ID token.");
  } catch (error) {
    console.error("ERROR setting initial admin claims:", error);
    if (error.code === 'auth/user-not-found') {
        console.error(`Ensure the UID '${firstAdminUID}' is correct and the user exists.`);
    }
  }
}

setInitialAdminClaims().then(() => {
    // Optional: Add a small delay so you can read console output before exit on some systems
    // setTimeout(() => process.exit(0), 3000);
    process.exit(0); // Exits the script
});