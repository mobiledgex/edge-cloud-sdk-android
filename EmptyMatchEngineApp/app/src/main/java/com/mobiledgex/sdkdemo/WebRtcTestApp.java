package com.mobiledgex.sdkdemo;

import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import com.mobiledgex.matchingengine.MatchingEngine;

import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;

import java.util.concurrent.ExecutionException;

import distributed_match_engine.AppClient;

public class WebRtcTestApp extends AppCompatActivity {

    private static final String TAG = "WebRtcTestApp";
    PeerConnection peerConnection;
    WebRTCPeerConnectionObserver observer;
    long observerRef;

    MatchingEngine me;
    long TIMEOUT_MS = 10000;

    String roomID = "mobiledgex123";

    public WebRtcTestApp(MatchingEngine me) {
        observer = new WebRTCPeerConnectionObserver(me);
        observerRef = PeerConnection.createNativePeerConnectionObserver(observer);

        PeerConnectionFactory factory = PeerConnectionFactory.builder().createPeerConnectionFactory();

        //peerConnection = new PeerConnection(() -> )
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // WebRTC Video View

    }

    @Override
    protected void onResume() {
        super.onResume();
        if (me == null || me.isShutdown()) {
            me = new MatchingEngine(this);

            try {
                String dmeHostOverride = "192.168.1.176";
                String carrierNameOverride = "";
                AppClient.RegisterClientRequest registerClientRequest =
                        me.createDefaultRegisterClientRequest(this, "MobiledgeX-Samples")
                                .setAppName("webrtcapp")
                                .setAppVers("1.0.2")
                                .setCarrierName(carrierNameOverride)
                                .build();
                AppClient.RegisterClientReply reply = me.registerClient(registerClientRequest, dmeHostOverride, me.getPort(), TIMEOUT_MS);

                if (reply != null) {
                    Log.d(TAG, "Registered!");
                }
                else {
                    Log.d(TAG, "NOT registered!");
                }

                Location location = new Location("WebRTC location provider src");
                location.setLongitude(-121.8863286);
                location.setLatitude(37.3382082);
                AppClient.FindCloudletRequest findCloudletRequest = me.createDefaultFindCloudletRequest(this, location)
                        .setCarrierName(carrierNameOverride)
                        .build();
                AppClient.FindCloudletReply findCloudletReply = me.findCloudlet(findCloudletRequest, dmeHostOverride, me.getPort(), TIMEOUT_MS);

                // EdgeConnection should be up (and pausing the UI...).

                // Launch WebRTC client, with our DME enabled peer observer callback, with roomID:


            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (ExecutionException e) {
                e.printStackTrace();
            }
        }

        // Start webrtc call, with room ID:


    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (me != null && !me.isShutdown()) {
            me.close();
        }
    }
}
