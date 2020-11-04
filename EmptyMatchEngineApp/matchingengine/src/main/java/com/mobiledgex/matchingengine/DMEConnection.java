package com.mobiledgex.matchingengine;


import android.net.Network;
import android.util.Log;

import com.google.android.gms.location.LocationResult;
import com.mobiledgex.matchingengine.performancemetrics.Site;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import distributed_match_engine.AppClient;
import distributed_match_engine.LocOuterClass;
import distributed_match_engine.MatchEngineApiGrpc;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;


/**
 * Async EdgeEvents StreamObserver to and from DME.
 */
public class DMEConnection {

    public static final String TAG = "DMEConnection";

    // Persistent connection:
    private MatchingEngine me;
    private ManagedChannel channel;

    public MatchEngineApiGrpc.MatchEngineApiStub asyncStub;

    StreamObserver<AppClient.ClientEdgeEvent> sender;
    StreamObserver<AppClient.ServerEdgeEvent> receiver;
    boolean open = false;

    DMEConnection(MatchingEngine me) {
        this.me = me;
        try {
            open();
        } catch (DmeDnsException dmedns) {
            Log.e(TAG, "There is no DME to connect to!");
        }
    }

    DMEConnection(MatchingEngine me, String host, int port, Network network) {
        this.me = me;
        try {
            open(host, port, network);
        } catch (DmeDnsException dmedns) {
            Log.e(TAG, "There is no DME to connect to!");
        }
    }

    void open() throws DmeDnsException {
        open(me.generateDmeHostAddress(), me.getPort(), null);
    }

    void open(String host, int port, Network network) throws DmeDnsException {
        if (network == null) {
            try {
                if (!me.isUseWifiOnly()) {
                    network = me.getNetworkManager().getCellularNetworkBlocking(false);
                } else {
                    network = me.getNetworkManager().getActiveNetwork();
                }
            } catch (InterruptedException | ExecutionException e) {
                network = me.getNetworkManager().getActiveNetwork();
            }
            if (network == null) {
                Log.e(TAG, "Unable to establish EdgeEvents DMEConnection!");
                return; // Unable to establish connect to backend!
            }
        }

        this.channel = me.channelPicker(host, port, network);
        this.asyncStub = MatchEngineApiGrpc.newStub(channel);

        receiver = new StreamObserver<AppClient.ServerEdgeEvent>() {
            @Override
            public void onNext(AppClient.ServerEdgeEvent value) {
                // Switch on type and/or post to bus.
                me.getEdgeEventBus().post(value);
            }

            @Override
            public void onError(Throwable t) {
                Log.w(TAG, "Encountered error in DMEConnection", t);
            }

            @Override
            public void onCompleted() {
                receiver.onCompleted();
                Log.i(TAG, "End of send.");
            }
        };

        // No deadline, since it's streaming:
        sender = asyncStub.streamEdgeEvent(receiver);

        // Create something to listen to it:

        open = true;
    }

    void close() {
        sender.onCompleted();
        receiver.onCompleted();
        if (channel != null) {
            channel.shutdown();
            try {
                channel.awaitTermination(5000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                open = false;
            }
            channel = null;
        }
    }

    public boolean isShutdown() {
        if (channel == null) {
            return true;
        }
        return channel.isShutdown();
    }

    void send(AppClient.ClientEdgeEvent clientEdgeEvent) {
        if (channel == null || channel.isShutdown()) {
            return;
        }

        sender.onNext(clientEdgeEvent);
    }

    public void postLatencyResult(Site site, LocationResult clientLocationResults, AppClient.FindCloudletReply findCloudlet) {
        LocOuterClass.Loc loc = null;

        if (site != null && (site.samples == null || site.samples.length == 0)) {
            // No results to post.
            return;
        }

        if (clientLocationResults.getLastLocation() != null) {
            loc = me.androidLocToMeLoc(clientLocationResults.getLastLocation());
        }

        AppClient.ClientEdgeEvent.Builder clientEdgeEventBuilder = AppClient.ClientEdgeEvent.newBuilder()
                .setEdgeEventsCookie(findCloudlet.getEdgeEventsCookie())
                .setSessionCookie(me.getSessionCookie())
                .setEventType(AppClient.ClientEdgeEvent.ClientEventType.EVENT_LATENCY_SAMPLES);

        if (loc != null) {
            clientEdgeEventBuilder.setGpsLocation(loc);
        }

        for (int i = 0; i < site.samples.length; i++) {
            if (site.samples[i] > 0d) {
                clientEdgeEventBuilder.addSamples(site.samples[i]);
            }
        }

        AppClient.ClientEdgeEvent clientEdgeEvent = clientEdgeEventBuilder.build();

        sender.onNext(clientEdgeEvent);
    }

}
