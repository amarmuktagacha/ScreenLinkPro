package com.screenlink.pro.cloud

import android.os.Build
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * Thin wrapper around Firebase Auth + Firestore for the optional internet-sharing mode.
 *
 * Everything here is best-effort and silently no-ops when the user isn't logged in (currentUser
 * is null), so the existing local Wi-Fi sharing/viewing flow keeps working exactly as before,
 * with no account required. Logging in is only needed to make a device's live status visible on
 * the companion website (screenlink-pro.web.app), where any logged-in account can see and view
 * any other account's live device.
 */
object CloudSync {
    private const val COLLECTION = "profiles"
    private const val TAG = "CloudSync"

    private val auth get() = FirebaseAuth.getInstance()
    private val db get() = FirebaseFirestore.getInstance()

    fun isLoggedIn(): Boolean = auth.currentUser != null
    fun currentEmail(): String? = auth.currentUser?.email
    fun currentUid(): String? = auth.currentUser?.uid
    fun signOut() = auth.signOut()

    fun logIn(email: String, password: String, onResult: (Boolean, String?) -> Unit) {
        auth.signInWithEmailAndPassword(email, password)
            .addOnSuccessListener { ensureProfile(onResult) }
            .addOnFailureListener { e -> onResult(false, e.localizedMessage) }
    }

    fun signUp(email: String, password: String, onResult: (Boolean, String?) -> Unit) {
        auth.createUserWithEmailAndPassword(email, password)
            .addOnSuccessListener { ensureProfile(onResult) }
            .addOnFailureListener { e -> onResult(false, e.localizedMessage) }
    }

    /** Creates the Firestore profile doc the first time this account logs in; keeps email fresh on later logins. */
    private fun ensureProfile(onResult: (Boolean, String?) -> Unit) {
        val user = auth.currentUser ?: return onResult(false, "Not logged in")
        val ref = db.collection(COLLECTION).document(user.uid)
        ref.get()
            .addOnSuccessListener { snap ->
                if (snap.exists()) {
                    ref.set(mapOf("email" to (user.email ?: "")), SetOptions.merge())
                    onResult(true, null)
                    return@addOnSuccessListener
                }
                val data = hashMapOf(
                    "name" to (Build.MODEL ?: "My device"),
                    "email" to (user.email ?: ""),
                    "isLive" to false,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                ref.set(data)
                    .addOnSuccessListener { onResult(true, null) }
                    .addOnFailureListener { e -> onResult(false, e.localizedMessage) }
            }
            .addOnFailureListener { e -> onResult(false, e.localizedMessage) }
    }

    /**
     * Reflects whether this device is currently sharing its screen. No-ops if not logged in.
     * [onResult] is optional — pass it when the caller needs to know the write actually landed
     * (e.g. WebRtcHostService, so it can broadcast a truthful live/offline state instead of
     * assuming success).
     */
    fun setLive(isLive: Boolean, onResult: ((Boolean) -> Unit)? = null) {
        val uid = auth.currentUser?.uid ?: return onResult?.invoke(false) ?: Unit
        db.collection(COLLECTION).document(uid)
            .set(mapOf("isLive" to isLive, "updatedAt" to FieldValue.serverTimestamp()), SetOptions.merge())
            .addOnSuccessListener { onResult?.invoke(true) }
            .addOnFailureListener { e -> Log.e(TAG, "setLive($isLive) failed", e); onResult?.invoke(false) }
    }
}
