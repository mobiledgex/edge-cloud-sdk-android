package com.mobiledgex.sdkdemo;

import android.util.Log;

import com.google.common.base.Stopwatch;
import com.mobiledgex.matchingengine.EdgeEventsConnection;
import com.mobiledgex.matchingengine.MatchingEngine;
import com.mobiledgex.matchingengine.performancemetrics.EcnCalculator;

import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.RtpReceiver;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import distributed_match_engine.AppClient;

public class WebRTCPeerConnectionObserver implements PeerConnection.Observer {
    String TAG = "WebRTCPeerConnectionObserver";
    MatchingEngine me;

    WebRTCPeerConnectionObserver(MatchingEngine me) {
        this.me = me;
    }

    @Override
    public void onSignalingChange(PeerConnection.SignalingState signalingState) {
        Log.d(TAG, "onSignalingChange() called with: signalingState = [" + signalingState + "]");
    }

    @Override
    public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
        Log.d(TAG, "onIceConnectionChange() called with: iceConnectionState = [" + iceConnectionState + "]");
    }

    @Override
    public void onIceConnectionReceivingChange(boolean b) {
        Log.d(TAG, "onIceConnectionReceivingChange() called with: b = [" + b + "]");
    }

    @Override
    public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
        Log.d(TAG, "onIceGatheringChange() called with: iceGatheringState = [" + iceGatheringState + "]");
    }

    @Override
    public void onIceCandidate(IceCandidate iceCandidate) {
        Log.d(TAG, "onIceCandidate() called with: iceCandidate = [" + iceCandidate + "]");
    }

    @Override
    public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {
        Log.d(TAG, "onIceCandidatesRemoved() called with: iceCandidates = [" + iceCandidates + "]");
    }

    @Override
    public void onAddStream(MediaStream mediaStream) {
        Log.d(TAG, "onAddStream() called with: mediaStream = [" + mediaStream + "]");
    }

    @Override
    public void onRemoveStream(MediaStream mediaStream) {
        Log.d(TAG, "onRemoveStream() called with: mediaStream = [" + mediaStream + "]");
    }

    @Override
    public void onDataChannel(DataChannel dataChannel) {
        Log.d(TAG, "onDataChannel() called with: dataChannel = [" + dataChannel + "]");
    }

    @Override
    public void onRenegotiationNeeded() {
        Log.d(TAG, "onRenegotiationNeeded() called");
    }

    @Override
    public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
        Log.d(TAG, "onAddTrack() called with: rtpReceiver = [" + rtpReceiver + "], mediaStreams = [" + mediaStreams + "]");
    }

    EcnCalculator ecnCalculator = new EcnCalculator();

    @Override
    public void onReceiveECN(int ecn) {
        EdgeEventsConnection eec = me.getEdgeEventsConnection();
        if (eec == null) {
            Log.d(TAG, "Dropping ECN marking notification. No EdgeEventsConnection.");
        }

        // Update:
        AppClient.ECNStatus ecnStatus = ecnCalculator.Update(ecn);
        if (ecnStatus == null) {
            return;
        }

        // Move to EdgeEventsConnection.
        if (ecnCalculator.isShouldSend()) {
            ecnCalculator.resetSendTimer();
            eec.postECNMarkings(ecnStatus);
        }
    }
}
