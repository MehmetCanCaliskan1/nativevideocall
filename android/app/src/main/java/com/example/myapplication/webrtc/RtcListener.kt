package com.example.myapplication.webrtc

import org.webrtc.MediaStream

/**
 * Interface to be notified of WebRTC events.
 */
interface RtcListener {
    fun onStatusChanged(newStatus: String)

    fun onAddLocalStream(localStream: MediaStream)
    fun onRemoveLocalStream(localStream: MediaStream)

    fun onAddRemoteStream(remoteStream: MediaStream)
    fun onRemoveRemoteStream()

    fun onPeersConnectionStatusChange(success: Boolean)


    fun onJoinRequest(participantId: String, participantName: String?)
    fun onJoinRejected(reason: String)

    fun onRoleDataReceived(isHost: Boolean)

}