package com.mobiledgex.sdkdemo;

import android.util.Log;

import com.google.common.base.Stopwatch;
import com.mobiledgex.matchingengine.EdgeEventsConnection;
import com.mobiledgex.matchingengine.MatchingEngine;

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

        if (ecnCalculator.isShouldSend()) {
            ecnCalculator.resetSendTimer();
            eec.postECNMarkings(ecnStatus);
        }
    }

    /*
     * Inplace bandwidth calculator (UDP).
     *
     * Bandwidth determination needs a bit of history.
     * (bits per second needs 1) to start assumptions and 2) status updates to start adjusting).
     * With no limits to bandwidth, there needs to be a ramp up amount (and strategy to do
     * so, as well as a ramp down in case of ECNStatus signaled CE congestion encountered.
     *
     * As the router/network path only signals "congestion encountered", it is offering
     * no additional details as to what that bandwidth should be. Ideally, one would like to go
     * as fast as possible given moment to moment ECN signaling, but before packets are dropped
     * too often.
     *
     * And of course, all this ideally will beat TCP slow start.
     */
     public class EcnCalculator {
        Stopwatch staleDataTimer = Stopwatch.createUnstarted();
        Stopwatch sendTimer = Stopwatch.createUnstarted();
        public double bandwidth = 0d;
        public static final int clearThreshold = 100; // If the data hasn't been refreshed in some time, start over from scratch.
        public static final float RAMPUPSPEED = 200000f;
        public float minimumBandwidth = 50000f; // Floor bandwidth, if below this, the connection might not be usable anymore.


        public static final float rampUpScale = 0.2f;
        public static final float ecnCeScaleDown = 0.9f;


        boolean shouldSend = false;

        private int idx = 0;
        int capacity = 3;
        ArrayList<Double> aggregateBandwidth = new ArrayList<Double>(capacity);
        double averageBandwidth = 0;
        long sendIntervalMs = 5000; // ms;

        public EcnCalculator() {
            Log.d(TAG, "New ECNCalucator.");
        }

        public void resetSendTimer() {
            sendTimer.reset();
            sendTimer.start();
        }

        boolean isShouldSend() {
            if (sendTimer.elapsed(TimeUnit.MILLISECONDS) >= sendIntervalMs) {
                return true;
            }
            return false;
        }

        boolean isStaleTimer() {
            if (staleDataTimer.elapsed(TimeUnit.MILLISECONDS) > clearThreshold) {
                return true;
            }
            return false;
        }

        // This sort of assumes a stream of data, not bursty data.
        AppClient.ECNStatus Update(int ecn) {
            if (isStaleTimer()) {
                aggregateBandwidth.clear();
                averageBandwidth = 0d;
                bandwidth = RAMPUPSPEED;
            }
            staleDataTimer.reset();
            staleDataTimer.start();

            AppClient.ECNBit ecnBit = AppClient.ECNBit.internalGetValueMap().findValueByNumber(ecn);

            // Update stats:
            if (ecnBit == AppClient.ECNBit.CE) {
                bandwidth *= ecnCeScaleDown;
                if (bandwidth <= minimumBandwidth) {
                    bandwidth = minimumBandwidth;
                }
            }
            else if (ecnBit == AppClient.ECNBit.ECT_0 && ecnBit == AppClient.ECNBit.ECT_1) {
                bandwidth += (1 + rampUpScale) * RAMPUPSPEED;   // That is, not exponentially faster, but there are no maximums either.
            }
            else {
                // Not supported (anymore?)
                Log.d(TAG, "connection claims ECN marking is unsupported.");
            }

            // Minimum guard:
            if (bandwidth <= minimumBandwidth) {
                bandwidth = minimumBandwidth;
            }


            // sample(s) update (and historical samples)
            aggregateBandwidth.add(idx, bandwidth);
            idx = (idx + 1) % capacity;
            double num = bandwidth + (averageBandwidth * (aggregateBandwidth.size() - 1));
            averageBandwidth = num / aggregateBandwidth.size();

            AppClient.ECNStatus ecnStatus = AppClient.ECNStatus.newBuilder()
                    .setBandwidth((float)averageBandwidth) // FIXME: Remove casting
                    .setEcnBit(ecnBit)
                    .setStrategy(AppClient.ECNStrategy.STRATEGY_1)
                    .build();

            return ecnStatus;
        }
    }
}
