package com.mobiledgex.matchingengine;


import android.location.Location;
import android.net.Network;
import android.util.Log;

import com.mobiledgex.matchingengine.performancemetrics.NetTest;
import com.mobiledgex.matchingengine.performancemetrics.Site;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import distributed_match_engine.AppClient;
import distributed_match_engine.LocOuterClass;
import distributed_match_engine.MatchEngineApiGrpc;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;

import static distributed_match_engine.AppClient.ServerEdgeEvent.ServerEventType.EVENT_CLOUDLET_UPDATE;
import static distributed_match_engine.AppClient.ServerEdgeEvent.ServerEventType.EVENT_INIT_CONNECTION;


/**
 * EdgeEventsConnection provides a asynchronious bi-directional connection to the server side DME.
 */
public class EdgeEventsConnection {

    public static final String TAG = "EdgeEventsConnection";

    // Persistent connection:
    private MatchingEngine me;
    private ManagedChannel channel;

    public MatchEngineApiGrpc.MatchEngineApiStub asyncStub;

    StreamObserver<AppClient.ClientEdgeEvent> sender;
    StreamObserver<AppClient.ServerEdgeEvent> receiver;
    boolean open = false;
    boolean reOpenDmeConnection = false;

    String hostOverride;
    int portOverride;
    Network networkOverride = null;

    /**
     * Establish an asynchronous DME Connection for streamed EdgeEvents to the current DME edge server.
     * @param me
     */
    EdgeEventsConnection(MatchingEngine me) {
        synchronized (this) {
            hostOverride = null;
            portOverride = 0;
        }
        this.me = me;
        try {
            open();
        } catch (DmeDnsException dmedns) {
            Log.e(TAG, "There is no DME to connect to!");
        }
    }

    EdgeEventsConnection(MatchingEngine me, String host, int port, Network network) {
        this.me = me;
        try {
            synchronized (this) {
                hostOverride = host;
                portOverride = port;
                networkOverride = network;
            }
            open(host, port, network);
        } catch (DmeDnsException dmedns) {
            Log.e(TAG, "There is no DME to connect to!");
        }
    }

    synchronized public void reconnect() throws DmeDnsException {
        if (hostOverride != null && portOverride > 0) {
            open(hostOverride, portOverride, networkOverride);
        } else {
            open(); // Default DME
        }

        // We need to re-init the DME connection:
        // Client identifies itself with an Init message to DME EdgeEvents Connection.
        if (me.mFindCloudletReply == null || me.getSessionCookie() == null) {
            Log.e(TAG, "State Error: Mission sessions to reconnect.");
            return;
        }

        AppClient.ClientEdgeEvent initDmeEvent = AppClient.ClientEdgeEvent.newBuilder()
                .setEventType(AppClient.ClientEdgeEvent.ClientEventType.EVENT_INIT_CONNECTION)
                .setSessionCookie(me.getSessionCookie())
                .setEdgeEventsCookie(me.mFindCloudletReply.getEdgeEventsCookie())
                .build();

        sender.onNext(initDmeEvent);
    }

    synchronized void open() throws DmeDnsException {
        open(me.generateDmeHostAddress(), me.getPort(), null);
        hostOverride = null;
        portOverride = 0;
    }

    synchronized void open(String host, int port, Network network) throws DmeDnsException {
        hostOverride = host;
        portOverride = port;

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
            networkOverride = null;
        } else {
            networkOverride = network;
        }

        reOpenDmeConnection = false;

        this.channel = me.channelPicker(host, port, network);
        this.asyncStub = MatchEngineApiGrpc.newStub(channel);

        receiver = new StreamObserver<AppClient.ServerEdgeEvent>() {
            @Override
            public void onNext(AppClient.ServerEdgeEvent value) {
                // Switch on type and/or post to bus.
                // A new FindCloudlet can arrive:
                if (value.getEventType() == EVENT_CLOUDLET_UPDATE) {
                    // New target FindCloudlet to use. Current FindCloudlet is known to the app and ought ot be in use.
                    me.mFindCloudletReply = value.getNewCloudlet();
                    reOpenDmeConnection = true;
                    sendTerminate();
                }

                me.getEdgeEventsBus().post(value);
            }

            @Override
            public void onError(Throwable t) {
                Log.w(TAG, "Encountered error in DMEConnection", t);
            }

            @Override
            public void onCompleted() {
                Log.i(TAG, "Stream closed.");
                close();
                // Reopen DME connection.
                try {
                    if (reOpenDmeConnection) { // Conditionally reconnect to receive EdgeEvents from closer DME.
                        reconnect();
                    }
                } catch (DmeDnsException dde) {
                    me.getEdgeEventsBus().post(
                            AppClient.ServerEdgeEvent.newBuilder()
                                    .setEventType(EVENT_INIT_CONNECTION)
                                    .putTags("Message", "DME Connection failed. A new client initiated FindCloudlet required for edge events stream.")
                                    .build()
                    );
                }
            }
        };

        // No deadline, since it's streaming:
        sender = asyncStub.streamEdgeEvent(receiver);
        open = true;
    }

    /*!
     * Call this to shutdown EdgeEvents cleanly.
     */
    synchronized void close() {
        if (!isShutdown()) {
            channel.shutdown();
            try {
                channel.awaitTermination(5000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                open = false;
                sender = null;
                receiver = null;
                channel = null;
            }
        }
    }

    public boolean isShutdown() {
        if (channel == null) {
            return true;
        }

        return channel.isShutdown();
    }

    boolean send(AppClient.ClientEdgeEvent clientEdgeEvent) {
        if (isShutdown()) {
            return false;
        }

        sender.onNext(clientEdgeEvent);
        return true;
    }

    private boolean tryPost(AppClient.ClientEdgeEvent clientEdgeEvent) {
        try {
            if (isShutdown()) {
                reconnect();
            }
            sender.onNext(clientEdgeEvent);
        } catch (DmeDnsException dde) {
            Log.e(TAG, dde.getMessage() + ", cause: " + dde.getCause());
            dde.printStackTrace();
            return false;
        }
        return true;
    }

    public boolean sendTerminate() {
        if (isShutdown()) {
            return false;
        }

        AppClient.ClientEdgeEvent.Builder clientEdgeEventBuilder = AppClient.ClientEdgeEvent.newBuilder()
                .setEventType(AppClient.ClientEdgeEvent.ClientEventType.EVENT_TERMINATE_CONNECTION);

        return tryPost(clientEdgeEventBuilder.build());
    }

    // Utility functions below:

    /*!
     * Outbound Client to Server location update. If there is a closer cloudlet, this will cause a
     * Guava ServerEdgeEvent EVENT_CLOUDLET_UPDATE message to be sent to subscribers.
     * @param location
     * @return whether the message was posted.
     */
    public boolean postLocationUpdate(Location location) {
        if (!me.isMatchingEngineLocationAllowed()) {
            return false;
        }

        if (location == null) {
            return false;
        }

        if (isShutdown()) {
            return false;
        }

        LocOuterClass.Loc loc = me.androidLocToMeLoc(location);
        if (loc == null) {
            return false;
        }

        AppClient.ClientEdgeEvent.Builder clientEdgeEventBuilder = AppClient.ClientEdgeEvent.newBuilder()
                .setEventType(AppClient.ClientEdgeEvent.ClientEventType.EVENT_LOCATION_UPDATE)
                .setGpsLocation(loc);

        AppClient.ClientEdgeEvent clientEdgeEvent = clientEdgeEventBuilder.build();

        return tryPost(clientEdgeEvent);
    }

    /*!
     * Outbound ClientEdgeEvent to DME. Post site statistics with the most recent FindCloudletReply.
     * A DME administrator of your Application may request an client application to collect performance
     * NetTest stats to their current AppInst with the ServerEdgeEvent EVENT_LATENCY_REQUEST.
     *
     * @param site
     * @param location
     * @return boolean indicating whether the site results are posted or not.
     */
    public boolean postLatencyResult(Site site, Location location) {

        if (!me.isMatchingEngineLocationAllowed()) {
            return false;
        }

        if (isShutdown()) {
            return false;
        }

        LocOuterClass.Loc loc = null;

        if (site == null || (site.samples == null || site.samples.length == 0)) {
            // No results to post.
            return false;
        }

        if (location != null) {
            loc = me.androidLocToMeLoc(location);
        }

        AppClient.ClientEdgeEvent.Builder clientEdgeEventBuilder = AppClient.ClientEdgeEvent.newBuilder()
                .setEventType(AppClient.ClientEdgeEvent.ClientEventType.EVENT_LATENCY_SAMPLES);

        if (loc != null) {
            clientEdgeEventBuilder.setGpsLocation(loc);
        }

        for (int i = 0; i < site.samples.length; i++) {
            if (site.samples[i] > 0d) {
                LocOuterClass.Sample.Builder sampleBuilder = LocOuterClass.Sample.newBuilder()
                        //.setLoc(loc) Location is not synchronous with measurement.
                        // Samples are not timestamped.
                        .setValue(site.samples[i]);
                clientEdgeEventBuilder.addSamples(sampleBuilder.build());
            }
        }

        AppClient.ClientEdgeEvent clientEdgeEvent = clientEdgeEventBuilder.build();

        return tryPost(clientEdgeEvent);
    }

    /*!
     * Outbound ClientEdgeEvent to DME. Post site statistics with the most recent FindCloudletReply.
     * A DME administrator of your Application may request an client application to collect performance
     * NetTest stats to their current AppInst with the ServerEdgeEvent EVENT_LATENCY_REQUEST.
     *
     * @param host string uri of the site to test with Ping (from default network network interface)
     * @param location
     * @return boolean indicating whether the site results are posted or not.
     */
    public boolean testPingAndPostLatencyResult(String host, Location location) {
        return testPingAndPostLatencyResult(host, location, 5);
    }

    /*!
     * Outbound ClientEdgeEvent to DME. Post site statistics with the most recent FindCloudletReply.
     * A DME administrator of your Application may request an client application to collect performance
     * NetTest stats to their current AppInst with the ServerEdgeEvent EVENT_LATENCY_REQUEST.
     *
     * @param site
     * @param android format location
     * @param number of samples to test.
     * @return boolean indicating whether the site results are posted or not.
     */
    public boolean testPingAndPostLatencyResult(String host, Location location, int numSamples) {
        if (!me.isMatchingEngineLocationAllowed()) {
            return false;
        }

        if (isShutdown()) {
            return false;
        }

        LocOuterClass.Loc loc = null;

        if (host == null || host.length() == 0) {
            // No results to post.
            return false;
        }

        if (location != null) {
            loc = me.androidLocToMeLoc(location);
        }

        AppClient.ClientEdgeEvent.Builder clientEdgeEventBuilder = AppClient.ClientEdgeEvent.newBuilder()
                .setEventType(AppClient.ClientEdgeEvent.ClientEventType.EVENT_LATENCY_SAMPLES);

        if (loc != null) {
            clientEdgeEventBuilder.setGpsLocation(loc);
        }

        if (numSamples == 0) {
            numSamples = 5;
        }
        Site site = new Site(me.mContext, NetTest.TestType.PING, numSamples, host, 0);
        NetTest netTest = new NetTest();
        netTest.addSite(site);
        // Test list of sites:
        netTest.testSites(netTest.TestTimeoutMS);

        for (int i = 0; i < site.samples.length; i++) {
            if (site.samples[i] > 0d) {
                LocOuterClass.Sample.Builder sampleBuilder = LocOuterClass.Sample.newBuilder()
                        //.setLoc(loc) Location is not synchronous with measurement.
                        // Samples are not timestamped.
                        .setValue(site.samples[i]);
                clientEdgeEventBuilder.addSamples(sampleBuilder.build());
            }
        }

        AppClient.ClientEdgeEvent clientEdgeEvent = clientEdgeEventBuilder.build();

        return tryPost(clientEdgeEvent);
    }

    /*!
     * Outbound ClientEdgeEvent to DME. Post site statistics with the most recent FindCloudletReply.
     * A DME administrator of your Application may request an client application to collect performance
     * NetTest stats to their current AppInst with the ServerEdgeEvent EVENT_LATENCY_REQUEST.
     *
     * This utility function uses the default network path. It does not swap networks.
     *
     * @param site
     * @param location
     * @return boolean indicating whether the site results are posted or not.
     */
    public boolean testConnectAndPostLatencyResult(String host, int port, Location location) {
        return testConnectAndPostLatencyResult(host, port, location, 5);
    }

    /*!
     * Outbound ClientEdgeEvent to DME. Post site statistics with the most recent FindCloudletReply.
     * A DME administrator of your Application may request an client application to collect performance
     * NetTest stats to their current AppInst with the ServerEdgeEvent EVENT_LATENCY_REQUEST.
     *
     * This utility function uses the default network path. It does not swap networks.
     *
     * @param host
     * @param port
     * @param android format GPS location.
     * @param number of samples to test
     * @return boolean indicating whether the site results are posted or not.
     */
    public boolean testConnectAndPostLatencyResult(String host, int port, Location location, int numSamples) {
        if (!me.isMatchingEngineLocationAllowed()) {
            return false;
        }

        if (isShutdown()) {
            return false;
        }

        LocOuterClass.Loc loc = null;

        if (host == null || host.length() == 0) {
            // No results to post.
            return false;
        }

        if (port <= 0) {
            return false;
        }

        if (location != null) {
            loc = me.androidLocToMeLoc(location);
        }

        AppClient.ClientEdgeEvent.Builder clientEdgeEventBuilder = AppClient.ClientEdgeEvent.newBuilder()
                .setEventType(AppClient.ClientEdgeEvent.ClientEventType.EVENT_LATENCY_SAMPLES);

        if (loc != null) {
            clientEdgeEventBuilder.setGpsLocation(loc);
        }

        if (numSamples == 0) {
            numSamples = 5;
        }

        Site site = new Site(me.mContext, NetTest.TestType.CONNECT, numSamples, host, port);
        NetTest netTest = new NetTest();
        netTest.addSite(site);
        // Test list of sites:
        netTest.testSites(netTest.TestTimeoutMS);

        for (int i = 0; i < site.samples.length; i++) {
            if (site.samples[i] > 0d) {
                LocOuterClass.Sample.Builder sampleBuilder = LocOuterClass.Sample.newBuilder()
                        //.setLoc(loc) Location is not synchronous with measurement.
                        // Samples are not timestamped.
                        .setValue(site.samples[i]);
                clientEdgeEventBuilder.addSamples(sampleBuilder.build());
            }
        }

        AppClient.ClientEdgeEvent clientEdgeEvent = clientEdgeEventBuilder.build();

        return tryPost(clientEdgeEvent);
    }
}
