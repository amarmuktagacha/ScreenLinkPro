package com.screenlink.pro.webrtc

import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/**
 * Firestore-based WebRTC signaling: exchanging the SDP offer/answer and ICE candidates needed to
 * set up a peer-to-peer connection between a host and a viewer over the internet. Firestore's
 * realtime listeners stand in for a dedicated signaling server, avoiding the need to host one.
 *
 * One active call per host at a time (a second viewer would overwrite the first) — fine for
 * personal/small-group use; a proper multi-viewer model can be layered on later if needed.
 */
object WebRtcSignaling {
    private val db get() = FirebaseFirestore.getInstance()
    private fun callDoc(hostUid: String) = db.collection("calls").document(hostUid)
    private fun hostCandidates(hostUid: String) = callDoc(hostUid).collection("hostCandidates")
    private fun viewerCandidates(hostUid: String) = callDoc(hostUid).collection("viewerCandidates")

    // ---- Viewer side ----

    fun sendOffer(hostUid: String, viewerUid: String, offer: SessionDescription, onDone: (Boolean) -> Unit) {
        val data = mapOf(
            "offerSdp" to offer.description,
            "offerType" to offer.type.canonicalForm(),
            "viewerUid" to viewerUid,
            "status" to "offer",
            "answerSdp" to null,
            "answerType" to null
        )
        callDoc(hostUid).set(data)
            .addOnSuccessListener { onDone(true) }
            .addOnFailureListener { onDone(false) }
    }

    fun listenForAnswer(hostUid: String, onAnswer: (SessionDescription) -> Unit): ListenerRegistration =
        callDoc(hostUid).addSnapshotListener { snap, _ ->
            val sdp = snap?.getString("answerSdp")
            val type = snap?.getString("answerType")
            if (sdp != null && type != null) onAnswer(SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp))
        }

    fun listenForHostCandidates(hostUid: String, onCandidate: (IceCandidate) -> Unit): ListenerRegistration =
        hostCandidates(hostUid).addSnapshotListener { snap, _ ->
            snap?.documentChanges?.forEach { change ->
                if (change.type == DocumentChange.Type.ADDED) onCandidate(toCandidate(change))
            }
        }

    fun addViewerCandidate(hostUid: String, candidate: IceCandidate) {
        viewerCandidates(hostUid).add(fromCandidate(candidate))
    }

    // ---- Host side ----

    fun listenForOffer(hostUid: String, onOffer: (SessionDescription, String) -> Unit): ListenerRegistration =
        callDoc(hostUid).addSnapshotListener { snap, _ ->
            val status = snap?.getString("status")
            val sdp = snap?.getString("offerSdp")
            val type = snap?.getString("offerType")
            val viewerUid = snap?.getString("viewerUid")
            if (status == "offer" && sdp != null && type != null && viewerUid != null) {
                onOffer(SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp), viewerUid)
            }
        }

    fun sendAnswer(hostUid: String, answer: SessionDescription, onDone: (Boolean) -> Unit) {
        callDoc(hostUid).update(
            mapOf("answerSdp" to answer.description, "answerType" to answer.type.canonicalForm(), "status" to "answer")
        ).addOnSuccessListener { onDone(true) }.addOnFailureListener { onDone(false) }
    }

    fun listenForViewerCandidates(hostUid: String, onCandidate: (IceCandidate) -> Unit): ListenerRegistration =
        viewerCandidates(hostUid).addSnapshotListener { snap, _ ->
            snap?.documentChanges?.forEach { change ->
                if (change.type == DocumentChange.Type.ADDED) onCandidate(toCandidate(change))
            }
        }

    fun addHostCandidate(hostUid: String, candidate: IceCandidate) {
        hostCandidates(hostUid).add(fromCandidate(candidate))
    }

    // ---- Shared ----

    fun endCall(hostUid: String) {
        callDoc(hostUid).update("status", "ended")
        clearCandidates(hostUid)
    }

    private fun clearCandidates(hostUid: String) {
        hostCandidates(hostUid).get().addOnSuccessListener { snap -> snap.documents.forEach { it.reference.delete() } }
        viewerCandidates(hostUid).get().addOnSuccessListener { snap -> snap.documents.forEach { it.reference.delete() } }
    }

    private fun fromCandidate(candidate: IceCandidate) = mapOf(
        "sdpMid" to candidate.sdpMid, "sdpMLineIndex" to candidate.sdpMLineIndex, "candidate" to candidate.sdp
    )

    private fun toCandidate(change: DocumentChange): IceCandidate {
        val d = change.document
        return IceCandidate(d.getString("sdpMid") ?: "", (d.getLong("sdpMLineIndex") ?: 0L).toInt(), d.getString("candidate") ?: "")
    }

    /** Google's free public STUN plus the Open Relay Project's free public TURN as a NAT-traversal fallback. */
    val iceServers: List<PeerConnection.IceServer> by lazy {
        listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80").setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443").setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp").setUsername("openrelayproject").setPassword("openrelayproject").createIceServer()
        )
    }
}

/** No-op SdpObserver base so call sites only override what they actually need. */
open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
