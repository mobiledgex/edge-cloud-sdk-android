package com.mobiledgex.matchingengine;


import android.location.Location;
import android.net.Network;
import android.util.Log;

import com.mobiledgex.matchingengine.edgeeventsconfig.ClientEventsConfig;
import com.mobiledgex.matchingengine.edgeeventsconfig.EdgeEventsConfig;
import com.mobiledgex.matchingengine.edgeeventsconfig.FindCloudletEvent;
import com.mobiledgex.matchingengine.edgeeventsconfig.FindCloudletEventTrigger;
import com.mobiledgex.matchingengine.performancemetrics.NetTest;
import com.mobiledgex.matchingengine.performancemetrics.Site;

import java.security.cert.CertPathValidatorException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import distributed_match_engine.AppClient;
import distributed_match_engine.Appcommon;
import distributed_match_engine.LocOuterClass;
import distributed_match_engine.MatchEngineApiGrpc;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;

import static distributed_match_engine.AppClient.ServerEdgeEvent.ServerEventType;


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

    EdgeEventsConfig eeConfig = null;
    ClientEventsConfig ceConfig = null;
    Location mLastLocationPosted = null;

    ConcurrentLinkedQueue eventsQueue = new ConcurrentLinkedQueue();

    Object syncObject = new Object();

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
                if (value.getEventType() == ServerEventType.EVENT_CLOUDLET_UPDATE) {
                    // New target FindCloudlet to use. Current FindCloudlet is known to the app and ought ot be in use.
                    if (value.hasNewCloudlet()) {
                        me.setFindCloudletResponse(value.getNewCloudlet());
                    }
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
                                    .setEventType(ServerEventType.EVENT_INIT_CONNECTION)
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
    // Why are we dumplicating this? And only some?
    Appcommon.DeviceInfo getDeviceInfo() {
        Appcommon.DeviceInfo.Builder deviceInfoBuilder = Appcommon.DeviceInfo.newBuilder();
        HashMap<String, String> hmap = me.getDeviceInfo();
        if (hmap != null && hmap.size() > 0) {
            return null;
        }

        for (Map.Entry<String, String> entry : hmap.entrySet()) {
            String key;
            String value;
            key = entry.getKey();
            if (entry.getValue() != null && entry.getValue().length() > 0) {
                switch (key) {
                    case "PhoneType":
                        deviceInfoBuilder.setDeviceOs(entry.getValue());
                        break;
                    case "DataNetworkType":
                        deviceInfoBuilder.setDataNetworkType(entry.getValue());
                        break;
                    case "ManufacturerCode":
                        deviceInfoBuilder.setDeviceModel(entry.getValue());
                        break;
                    case "SignalStrength":
                        try {
                            // This is an abstract "getLevel()" for the last known radio signal update.
                            deviceInfoBuilder.setSignalStrength(new Integer(entry.getValue()));
                        } catch (NumberFormatException e) {
                            Log.e(TAG, "Cannot attach signal strength. Reason: " + e.getMessage());
                        }
                        break;
                }
            }
        }
        return deviceInfoBuilder.build();
    }


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

        mLastLocationPosted = location;
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
    public boolean postLatencyUpdate(Site site, Location location) {

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
    public boolean testPingAndPostLatencyUpdate(String host, Location location) {
        return testPingAndPostLatencyUpdate(host, location, 5);
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
    public boolean testPingAndPostLatencyUpdate(String host, Location location, int numSamples) {
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
            mLastLocationPosted = location;
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
    public boolean testConnectAndPostLatencyUpdate(String host, int port, Location location) {
        return testConnectAndPostLatencyUpdate(host, port, location, 5);
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
    public boolean testConnectAndPostLatencyUpdate(String host, int port, Location location, int numSamples) {
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

    /*!
     * Entry functions for default handlers.
     */
    public boolean startEdgeEvents(final EdgeEventsConfig eeConfig, final ClientEventsConfig ceConfig) {
        if (!me.isEnableEdgeEvents()) {
            return false;
        }

        this.eeConfig = eeConfig;
        this.ceConfig = ceConfig;

        // Run on a threadpool (that expands as needed).
        CompletableFuture future = CompletableFuture.supplyAsync(new Supplier<Boolean>() {

            @Override
            public Boolean get() {

                long updateInterval = 1000;
                long numUpdates = 0;
                if (ceConfig != null) {
                    if (ceConfig.updateInterval < 1d) {
                        updateInterval = 1000;
                    } else {
                        updateInterval = (long) ceConfig.updateInterval;
                    }

                    if (ceConfig.numberOfUpdates < 0) {
                        return false;
                    } else {
                        numUpdates = ceConfig.numberOfUpdates; // Including INT_MAX.
                    }
                }


                // Loop on configured events.
                while (true) {

                    // Latency:
                    Log.d(TAG, "Waiter not Implemented");

                    // If a FindCloudlet arrived, post to Polymorphic EventBus


                    // If Latency is past configured threshold:


                    // Scheduled Interval.
                    try {
                        // If Posted Event queue is empty, just sleep until notified.
                        numUpdates--;
                        if (numUpdates < 0) {
                            return true;
                        }
                        syncObject.wait(updateInterval);
                    } catch (InterruptedException e) {
                        // if told to exit, close() EdgeEventsBus:
                        // close();
                    }
                }
            }
        }, me.threadpool);


        return true; // Launched thread.
    }

    // Configured Defaults:
    private boolean HandleDefaultEdgeEvents(AppClient.ServerEdgeEvent event) {
        Map<String, String> tagsMap = event.getTagsMap();
        boolean ret = true;

        switch (event.getEventType()) {
            case EVENT_INIT_CONNECTION:
                Log.d(TAG, "Received Init response: " + event);
                break;
            case EVENT_APPINST_HEALTH:
                Log.d(TAG, "Received: AppInst Health: " + event);
                ret = handleAppInstHealth(event);
                break;
            case EVENT_CLOUDLET_STATE:
                Log.d(TAG, "Received: Cloutlet State event: " + event);
                ret = handleCloudletState(event);
                break;
            case EVENT_CLOUDLET_MAINTENANCE:
                Log.d(TAG,"Received: Cloutlet Maintenance event." + event);
                ret = handleCloudletMaintenance(event);
                break;
            case EVENT_LATENCY_PROCESSED:
                Log.d(TAG,"Received: Latency has been processed on server: " + event);
                break;
            case EVENT_LATENCY_REQUEST:
                Log.d(TAG,"Received: Latency has been requested to be tested (client perspective): " + event);
                ret = handleLatencyRequest(event);
                break;
            case EVENT_CLOUDLET_UPDATE:
                Log.d(TAG,"Received: Server pushed a new FindCloudletReply to switch to: " + event);
                ret = handleFindCloudletServerPush(event);
                break;
            case EVENT_UNKNOWN:
                Log.d(TAG,"Received UnknownEvent.");
                ret = false;
                break;
            default:
                Log.d(TAG,"Event Received: " + event.getEventType());
                ret = false;
        }

        // TODO: Need event switch of some kind to handle.
        if (tagsMap.containsKey("shutdown")) {
            // unregister self.
            me.getEdgeEventsBus().unregister(this);
        }

        return ret;
    }



    boolean doClientFindCloudlet(FindCloudletEventTrigger reason) {

        Location loc = null;

        // Need app details to attempt posting.
        if (me.mRegisterClientRequest == null) {
            return false;
        }

        // FindCloudlet need location:
        if (mLastLocationPosted == null) {
            return false;
        }

        AppClient.FindCloudletRequest request = me.createDefaultFindCloudletRequest(me.mContext, loc)
                .build();

        try {
            AppClient.FindCloudletReply reply = me.findCloudlet(request, me.getNetworkManager().getTimeout());
            if (reply != null) {
                FindCloudletEvent event = new FindCloudletEvent();
                event.trigger = reason;
                event.newCloudlet = reply;

                // TODO: Check if this is a new FindCloudlet before posting.
                me.getEdgeEventsBus().post(event);
                return true;
            }

        } catch (DmeDnsException e) {
            Log.d(TAG, "DME DNS Exception doing auto doClientFindCloudlet(): " + e.getMessage());
        } catch (InterruptedException e) {
            // do nothing.
        } catch (ExecutionException e) {
            Log.d(TAG, "EdgeEvents ExecutionException doing auto doClientFindCloudlet(): " + e.getMessage());
            e.printStackTrace();
        }
        return false;
    }

    boolean handleFindCloudletServerPush(AppClient.ServerEdgeEvent event) {
        if (event.getEventType() != ServerEventType.EVENT_CLOUDLET_UPDATE) {
            return false;
        }

        Log.i(TAG, "Received a new Edge FindCloudlet. Pushing to new FindCloudlet subscribers.");
        if (event.hasNewCloudlet()) {
            FindCloudletEvent fce = new FindCloudletEvent();
            fce.trigger = FindCloudletEventTrigger.CloudletStateChanged;
            fce.newCloudlet = event.getNewCloudlet();
            me.getEdgeEventsBus().post(fce);

            me.setFindCloudletResponse(event.getNewCloudlet());
        } else {
            Log.e(TAG, "Error: Server is missing a findClooudlet!");
            return false;
        }

        return true;
    }

    boolean handleAppInstHealth(AppClient.ServerEdgeEvent event) {
        if (event.getEventType() != AppClient.ServerEdgeEvent.ServerEventType.EVENT_APPINST_HEALTH) {
            return false;
        }

        switch (event.getHealthCheck()) {
            case HEALTH_CHECK_FAIL_ROOTLB_OFFLINE:
            case HEALTH_CHECK_FAIL_SERVER_FAIL:
                doClientFindCloudlet(FindCloudletEventTrigger.AppInstHealthChanged);
                break;
            case HEALTH_CHECK_OK:
                Log.i(TAG, "AppInst Health is OK");
                break;
            case UNRECOGNIZED:
                // fall through
            default:
                Log.i(TAG, "AppInst Health event: " + event.getHealthCheck());
        }
        return true;
    }

    boolean handleCloudletMaintenance(AppClient.ServerEdgeEvent event) {
        if (event.getEventType() != AppClient.ServerEdgeEvent.ServerEventType.EVENT_CLOUDLET_MAINTENANCE) {
            return false;
        }

        switch (event.getMaintenanceState()) {
            case NORMAL_OPERATION:
                System.out.println("Maintenance state is all good!");
                break;
            default:
                System.out.println("Server maintenance: " + event.getMaintenanceState());
        }
        return true;
    }

    boolean handleCloudletState(AppClient.ServerEdgeEvent event) {
        if (event.getEventType() != ServerEventType.EVENT_CLOUDLET_STATE) {
            return false;
        }

        switch (event.getCloudletState()) {
            case CLOUDLET_STATE_INIT:
                System.out.println("Cloudlet is not ready yet. Wait or FindCloudlet again.");
                break;
            case CLOUDLET_STATE_NOT_PRESENT:
            case CLOUDLET_STATE_UPGRADE:
            case CLOUDLET_STATE_OFFLINE:
            case CLOUDLET_STATE_ERRORS:
                System.out.println("Cloudlet State is: " + event.getCloudletState());
                break;
            case CLOUDLET_STATE_READY:
                // Just re-fire post:
                {
                    me.getEdgeEventsBus().post(event);
                }
                break;
            case CLOUDLET_STATE_NEED_SYNC:
                System.out.println("Cloudlet data needs to sync.");
                break;
            default:
                System.out.println("Not handled");
        }
        return true;
    }
    // Only the app knows with any certainty which AppPort (and internal port array)
    // it wants to test, so this is in the application.
    boolean handleLatencyRequest(AppClient.ServerEdgeEvent event) {
        if (event.getEventType() != AppClient.ServerEdgeEvent.ServerEventType.EVENT_LATENCY_REQUEST) {
            return false;
        }
        CompletableFuture<Void> future = CompletableFuture.runAsync(new Runnable() {
            @Override
            public void run() {
                // NetTest
                // Local copy:
                NetTest netTest = new NetTest();

                // If there's a current FindCloudlet, we'd just use that.
                if (me.mFindCloudletReply == null) {
                    return;
                }
                if (me.mFindCloudletReply.getPortsCount() == 0) {
                    Log.i(TAG, "There are no ports to test!");
                    return;
                }

                if (eeConfig.latencyPort == 0 || eeConfig.testType == NetTest.TestType.PING) {
                    Appcommon.AppPort aPort = me.mFindCloudletReply.getPortsList().get(0);
                    // Only host matters for Ping.
                    String host = aPort.getFqdnPrefix() + me.mFindCloudletReply.getFqdn();
                    me.getEdgeEventsConnection().testPingAndPostLatencyUpdate(host, mLastLocationPosted);
                    return;
                }


                // have (internal) port defined, use it.
                HashMap<Integer, Appcommon.AppPort> tcpports = me.getAppConnectionManager().getTCPMap(me.mFindCloudletReply);
                Appcommon.AppPort anAppPort = tcpports.get(eeConfig.latencyPort); // The INTERNAL port. The public port is the one we can test with.
                if (anAppPort == null) {
                    System.out.println("Your expected server (or port) doesn't seem to be here!");
                }

                // Test with default network in use:
                String host = me.getAppConnectionManager().getHost(me.mFindCloudletReply, anAppPort);

                int publicPort = anAppPort.getPublicPort();
                Site site = new Site(me.mContext, NetTest.TestType.CONNECT, 5, host, publicPort);
                netTest.addSite(site);
                // Blocks.
                netTest.testSites(netTest.TestTimeoutMS); // Test the one we just added.

                // Trigger(s):
                if (site.average >= eeConfig.latencyThresholdTrigger) {
                    Log.i(TAG, "Latency higher than requested");
                    doClientFindCloudlet(FindCloudletEventTrigger.LatencyTooHigh);
                }

                if (mLastLocationPosted == null) {
                    return;
                }

                me.getEdgeEventsConnection().postLatencyUpdate(netTest.getSite(host), mLastLocationPosted);
            }
        });
        return true;
    }

}
