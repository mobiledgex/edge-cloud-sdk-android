package com.mobiledgex.matchingengine;


import android.location.Location;
import android.net.Network;
import android.util.Log;

import com.google.common.eventbus.AsyncEventBus;
import com.google.common.eventbus.DeadEvent;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.mobiledgex.matchingengine.edgeeventsconfig.ClientEventsConfig;
import com.mobiledgex.matchingengine.edgeeventsconfig.EdgeEventsConfig;
import com.mobiledgex.matchingengine.edgeeventsconfig.FindCloudletEvent;
import com.mobiledgex.matchingengine.edgeeventsconfig.FindCloudletEventTrigger;
import com.mobiledgex.matchingengine.performancemetrics.NetTest;
import com.mobiledgex.matchingengine.performancemetrics.Site;

import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
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

    EdgeEventsConfig eeConfig;
    Location mLastLocationPosted = null;

    ConcurrentLinkedQueue eventsQueue = new ConcurrentLinkedQueue();

    /*!
     * A DeadEvent handler will print messages still. Outside EdgeEvents handler.
     */
    private EventBus mEdgeEventsBus;
    private DeadEventHandler deadEventHandler = new DeadEventHandler();
    private boolean noEventHandlersObserved = true;

    /*!
     * This eventBus instance is a duplicate handler, but for the managed EventBus state machine so
     * it doesn't interfere with DeadEvents handling.
     */
    private EventBus mDefaultEdgeEventsBus;

    // This class is mainly here to allow a clear object to register for deadEvents subscription.
    private class DeadEventHandler {
        /**
         * Listens to dead events, and prints them as warnings. If they are of interest, subscribe.
         * @param deadEvent
         */
        @Subscribe
        void handleDeadEvent(DeadEvent deadEvent) {
            noEventHandlersObserved = true; // No Registered event subscribers has been observed.
            Log.d(TAG, "EventBus: Unhandled event: " + deadEvent.toString());

            if (deadEvent.getEvent() instanceof AppClient.ServerEdgeEvent) {
                AppClient.ServerEdgeEvent unhandledEvent = (AppClient.ServerEdgeEvent) deadEvent.getEvent();
                Log.w(TAG, "EventBus: To get pushed all raw edgeEvent updates, subscribe to EventBus object type ServerEdgeEvents: " + unhandledEvent.getEventType());
                Log.w(TAG, "EventBus: To get pushed NewCloudlet updates for your app, subscribe to EventBus object type FindCloudletEvent: " + unhandledEvent.getEventType());
            }
        }
    }

    Object syncObject = new Object();

    /*!
     * Errors on EdgeEvents
     */
    public enum EdgeEventsError {
        missingSessionCookie,
        missingEdgeEventsCookie,
        unableToGetLastLocation,
        missingGetLastLocationFunction,
        missingEdgeEventsConfig,
        missingNewFindCloudletHandler,
        missingServerEventsHandler,
        hasNotDoneFindCloudlet,
        emptyAppPorts,
        portDoesNotExist,
        uninitializedEdgeEventsConnection
    }

    /*!
     * Status for EdgeEvents
     */
    public enum EdgeEventsStatus {
        success,
        fail
    }

    /**
     * Establish an asynchronous DME Connection for streamed EdgeEvents to the current DME edge server.
     * @param me
     * @param executorService if null, this will use default executor.
     */
    EdgeEventsConnection(MatchingEngine me, ExecutorService executorService) {
        synchronized (this) {
            hostOverride = null;
            portOverride = 0;
        }
        this.me = me;

        Log.w(TAG, "Configuring EdgeEvent Defaults");
        eeConfig = me.createDefaultEdgeEventsConfig();
        mDefaultEdgeEventsBus = new AsyncEventBus(executorService);

        try {
            open();
        } catch (DmeDnsException dmedns) {
            Log.e(TAG, "There is no DME to connect to!");
        }
    }

    EdgeEventsConnection(MatchingEngine me, String host, int port, Network network, ExecutorService executorService) {
        this.me = me;

        Log.w(TAG, "Configuring EdgeEvent Defaults");
        eeConfig = me.createDefaultEdgeEventsConfig();
        mDefaultEdgeEventsBus = new AsyncEventBus(executorService);

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

    public EventBus getEdgeEventsBus() {
        return mEdgeEventsBus;
    }
    EventBus setEdgeEventsBus(EventBus eventBus) {
        mEdgeEventsBus = eventBus;
        mEdgeEventsBus.register(deadEventHandler);
        return mEdgeEventsBus;
    }

    private boolean validateFindCloudlet(AppClient.FindCloudletReply findCloudletReply) {
        if (findCloudletReply.getEdgeEventsCookie() == null || findCloudletReply.getEdgeEventsCookie().isEmpty()) {
            return false;
        }
        return true;
    }

    private void postToFindCloudletEventHandler(AppClient.ServerEdgeEvent value) {
        if (value.hasNewCloudlet()) {
            AppClient.FindCloudletReply fcr = value.getNewCloudlet();
            if (me.mFindCloudletReply != null) {
                if (fcr.getFqdn().equals(me.mFindCloudletReply.getFqdn())) {
                   Log.d(TAG, "New cloudlet. The base FQDN did not change.");
                }
            }
            FindCloudletEvent fce = new FindCloudletEvent(
                    value.getNewCloudlet(),
                    FindCloudletEventTrigger.CloudletStateChanged);

            postToFindCloudletEventHandler(fce);
        }
    }
    private void postToFindCloudletEventHandler(FindCloudletEvent findCloudletEvent) {
        if (!validateFindCloudlet(findCloudletEvent.newCloudlet)) {
            postErrorToEventHandler(EdgeEventsError.missingEdgeEventsConfig);
            return;
        }

        // Post to interested subscribers.
        boolean subed = false;
        for ( int i = 0; i  < eeConfig.triggers.length; i++) {
            if (findCloudletEvent.trigger == eeConfig.triggers[i]) {
                subed = true;
                break;
            }
        }

        if (subed) {
            mEdgeEventsBus.post(findCloudletEvent);
        }
    }

    /*!
     * The EdgeEventsConfig monitor may encounter errors that need to be reported. This reports it on
     * the subscribed event bus;
     */
    private void postErrorToEventHandler(EdgeEventsError error) {
        mEdgeEventsBus.post(error);
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
            Log.e(TAG, "State Error: Missing sessions to reconnect.");
            postErrorToEventHandler(EdgeEventsError.missingSessionCookie);
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
                        handleFindCloudletServerPush(value);
                    }
                    reOpenDmeConnection = true;
                    sendTerminate();
                }

                // If nothing is subscribed, it just goes to deadEvent.
                mEdgeEventsBus.post(value);

                // Default handler will handle incoming messages if no subscribers are currently observed.
                // This also preserves the DeadEvent handler for outside users.
                if (noEventHandlersObserved) {
                    HandleDefaultEdgeEvents(value);
                }
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
                } catch (Exception e) {
                    mEdgeEventsBus.post(
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
        mEdgeEventsBus = null;

        if (!isShutdown()) {
            try {
                sender = null;
                receiver = null;
                latencyTimer.cancel();
                locationTimer.cancel();
                channel.shutdown();
                channel.awaitTermination(5000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                latencyTimer = null;
                locationTimer = null;
                open = false;
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
        } catch (Exception e) {
            Log.e(TAG, e.getMessage() + ", cause: " + e.getCause());
            e.printStackTrace();
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
    // Why are we duplicating this? And only some?
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

    public void validateStartConfig(String host, int port, EdgeEventsConfig edgeEventsConfig) {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("Host canot be null!");
        }
        if (edgeEventsConfig == null) {
            throw new IllegalArgumentException("Config cannot be null!");
        }
    }

    /*!
     * Entry functions for default handlers.
     */
    public class LatencyTask extends TimerTask {
        ClientEventsConfig ceConfig;
        int calledCount;
        String host;
        int port;
        LatencyTask(String host, int port, ClientEventsConfig clientEventsConfig) {
            ceConfig = clientEventsConfig;
            calledCount = 0;
            this.host = host;
            this.port = port;

            if (host == null || host.trim().isEmpty()) {
                throw new IllegalArgumentException("Host cannot be null!");
            }
        }

        public void run() {
            if (calledCount < ceConfig.maxNumberOfUpdates) {
                if (port == 0) {
                    testPingAndPostLatencyUpdate(host, mLastLocationPosted);
                }
                else {
                    // This needs a connect test if a ping doesn't work.
                    testConnectAndPostLatencyUpdate(host, port, mLastLocationPosted);
                }
            } else {
                latencyTimer.cancel();
            }
        }
    }

    /*!
     * Entry functions for default handlers.
     */
    public class LocationTask extends TimerTask {
        ClientEventsConfig ceConfig;
        int calledCount;
        String host;
        int port;
        LocationTask(ClientEventsConfig clientEventsConfig) {
            ceConfig = clientEventsConfig;
            calledCount = 0;

            if (host == null || host.trim().isEmpty()) {
                throw new IllegalArgumentException("Host cannot be null!");
            }
        }

        public void run() {
            if (calledCount < ceConfig.maxNumberOfUpdates) {
                if (mLastLocationPosted != null) {
                    postLocationUpdate(mLastLocationPosted);
                }
            } else {
                locationTimer.cancel(); // Tests done.
            }
        }
    }
    private Timer latencyTimer = new Timer();
    private Timer locationTimer = new Timer();
    /*!
     * \param dmeHost DME hostname. FIXME: Why?
     * \param dmePort
     * \param edgeEventsConfig
     */
    public boolean startEdgeEvents(String dmeHost, int dmePort, final EdgeEventsConfig edgeEventsConfig) throws IllegalArgumentException {
        if (!me.isEnableEdgeEvents()) {
            return false;
        }

        String host;
        int port = -1;

        // Abort if no find cloudlet yet.
        if (me.mFindCloudletReply == null) {
            Log.e(TAG, "The App needs to have a FindCloudlet reply of FIND_FOUND before startEdgeEvents can be used");
            return false;
        }

        if (me.mFindCloudletReply.getPortsCount() < 1) {
            Log.e(TAG, "The last findCloudlet does NOT have any ports! Ports Count: " + me.mFindCloudletReply.getPortsCount());
            return false;
        }
        Appcommon.AppPort aPort = me.mFindCloudletReply.getPorts(0); // Get the first one.
        host = aPort.getFqdnPrefix() + me.mFindCloudletReply.getFqdn();
        port = edgeEventsConfig.latencyPort; // Caller specified port, presumably a valid port.

        // TODO: Validate that port (loop, and vaidate against ranges).

        eeConfig = edgeEventsConfig;
        if (eeConfig == null) {
            return false;
        }

        validateStartConfig(host, port, edgeEventsConfig);

        if (latencyTimer != null) {
            latencyTimer.cancel();
            latencyTimer = null;
        }
        if (eeConfig.latencyUpdateConfig != null) {
            ClientEventsConfig latencyUpdateConfig = eeConfig.latencyUpdateConfig;

            latencyTimer = new Timer();

            switch (latencyUpdateConfig.updatePattern) {
                case onStart:
                    if (port <= 0) {
                        testPingAndPostLatencyUpdate(host, mLastLocationPosted);
                    } else {
                        testConnectAndPostLatencyUpdate(host, port, mLastLocationPosted);
                    }
                    break;
                case onTrigger:
                    mEdgeEventsBus.register(this); // Attach Subscriber for triggers.
                    break;
                case onInterval:
                    // Last FindCloudlet
                    latencyTimer.schedule(new LatencyTask(host, port, latencyUpdateConfig),
                            (long)(latencyUpdateConfig.updateIntervalSeconds * 1000d));
                    break;
            }
        }

        if (locationTimer != null) {
            locationTimer.cancel();
            locationTimer = null;
        }
        if (eeConfig.locationUpdateConfig != null) {
            ClientEventsConfig locationUpdateConfig = eeConfig.locationUpdateConfig;

            locationTimer = new Timer();

            switch (locationUpdateConfig.updatePattern) {
                case onStart:
                    postLocationUpdate(mLastLocationPosted);
                    break;
                case onTrigger:
                    mEdgeEventsBus.register(this); // Attach Subscriber for triggers.
                    break;
                case onInterval:
                    locationTimer.schedule(new LocationTask(locationUpdateConfig),
                            (long)(locationUpdateConfig.updateIntervalSeconds * 1000d));
                    break;
            }
        }

        // Launched Scheduled tasks, or EdgeEvents listener..
        return true;
    }

    public void stopEdgeEvents() {
        if (latencyTimer != null) {
            latencyTimer.cancel();
        }
        if (locationTimer != null) {
            locationTimer.cancel();
        }
        latencyTimer = null;
        locationTimer = null;
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
        return ret;
    }


    // Non raw ServerEdgeEvent.
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
                FindCloudletEvent event = new FindCloudletEvent(reply, reason);

                // TODO: Check if this is a new FindCloudlet before posting.
                postToFindCloudletEventHandler(event);
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
            FindCloudletEvent fce = new FindCloudletEvent(
                    event.getNewCloudlet(),
                    FindCloudletEventTrigger.CloudletStateChanged);

            // Update MatchingEngine.
            me.setFindCloudletResponse(event.getNewCloudlet());

            // Post raw message to main EventBus:
            mEdgeEventsBus.post(event);

            // Post to new FindCloudletEvent Handler subscribers, if any, on the same EventBus:
            postToFindCloudletEventHandler(event);

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
                Log.i(TAG,"Maintenance state is all good!");
                break;
            default:
                Log.i(TAG,"Server maintenance: " + event.getMaintenanceState());
        }
        return true;
    }

    boolean handleCloudletState(AppClient.ServerEdgeEvent event) {
        if (event.getEventType() != ServerEventType.EVENT_CLOUDLET_STATE) {
            return false;
        }

        switch (event.getCloudletState()) {
            case CLOUDLET_STATE_INIT:
                Log.i(TAG,"Cloudlet is not ready yet. Wait or FindCloudlet again.");
                break;
            case CLOUDLET_STATE_NOT_PRESENT:
            case CLOUDLET_STATE_UPGRADE:
            case CLOUDLET_STATE_OFFLINE:
            case CLOUDLET_STATE_ERRORS:
                Log.i(TAG,"Cloudlet State is: " + event.getCloudletState());
                break;
            case CLOUDLET_STATE_READY:
                Log.i(TAG,"Cloudlet State is: " + event.getCloudletState());
                break;
            case CLOUDLET_STATE_NEED_SYNC:
                Log.i(TAG,"Cloudlet data needs to sync.");
                break;
            default:
                Log.i(TAG,"Not handled");
        }
        return true;
    }
    // Only the app knows with any certainty which AppPort (and internal port array)
    // it wants to test, so this is in the application.
    boolean handleLatencyRequest(AppClient.ServerEdgeEvent event) {
        if (event.getEventType() != AppClient.ServerEdgeEvent.ServerEventType.EVENT_LATENCY_REQUEST) {
            return false;
        }
        // Threadpool owns async context.
        CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(new Supplier<Boolean>() {
            @Override
              public Boolean get() {
                try {
                    // NetTest
                    // Local copy:
                    NetTest netTest = new NetTest();

                    // If there's a current FindCloudlet, we'd just use that.
                    if (me.mFindCloudletReply == null) {
                        return false;
                    }
                    if (me.mFindCloudletReply.getPortsCount() == 0) {
                        Log.i(TAG, "There are no ports to test!");
                        return false;
                    }

                    if (eeConfig == null) {
                        Log.e(TAG, "No Latency Config set!");
                        return false;
                    }

                    if (eeConfig.latencyPort == 0 || eeConfig.testType == NetTest.TestType.PING) {
                        Appcommon.AppPort aPort = me.mFindCloudletReply.getPortsList().get(0);
                        // Only host matters for Ping.
                        String host = aPort.getFqdnPrefix() + me.mFindCloudletReply.getFqdn();
                        me.getEdgeEventsConnection().testPingAndPostLatencyUpdate(host, mLastLocationPosted);
                        return false;
                    }

                    // have (internal) port defined, use it.
                    HashMap<Integer, Appcommon.AppPort> tcpports = me.getAppConnectionManager().getTCPMap(me.mFindCloudletReply);
                    Appcommon.AppPort anAppPort = tcpports.get(eeConfig.latencyPort); // The INTERNAL port. The public port is the one we can test with.
                    if (anAppPort == null) {
                        Log.i(TAG, "Your expected server (or port) doesn't seem to be here!");
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
                        return false;
                    }

                    return me.getEdgeEventsConnection().postLatencyUpdate(netTest.getSite(host), mLastLocationPosted);
                } catch (Exception e) {
                    Log.e(TAG, "Exception running latency test: " + e.getMessage());
                    e.printStackTrace();
                }
                return false;
            }
        }, me.threadpool);
        return true;
    }

}
