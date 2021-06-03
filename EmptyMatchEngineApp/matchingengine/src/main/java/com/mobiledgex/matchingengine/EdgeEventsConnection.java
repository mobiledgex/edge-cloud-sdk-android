package com.mobiledgex.matchingengine;

import android.location.Location;
import android.net.Network;
import android.os.NetworkOnMainThreadException;
import android.util.Log;

import com.google.common.base.Stopwatch;
import com.google.common.eventbus.DeadEvent;
import com.google.common.eventbus.Subscribe;
import com.mobiledgex.matchingengine.edgeeventhandlers.EdgeEventsIntervalHandler;
import com.mobiledgex.matchingengine.edgeeventhandlers.EdgeEventsLatencyIntervalHandler;
import com.mobiledgex.matchingengine.edgeeventhandlers.EdgeEventsLocationIntervalHandler;
import com.mobiledgex.matchingengine.edgeeventsconfig.UpdateConfig;
import com.mobiledgex.matchingengine.edgeeventsconfig.EdgeEventsConfig;
import com.mobiledgex.matchingengine.edgeeventsconfig.FindCloudletEvent;
import com.mobiledgex.matchingengine.edgeeventsconfig.FindCloudletEventTrigger;
import com.mobiledgex.matchingengine.performancemetrics.NetTest;
import com.mobiledgex.matchingengine.performancemetrics.Site;
import com.mobiledgex.matchingengine.util.MeLocation;

import java.net.InetAddress;
import java.net.UnknownHostException;
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
 * \ingroup classes
 */
public class EdgeEventsConnection {

    public static final String TAG = "EdgeEventsConnection";

    // Persistent connection:
    private MatchingEngine me;
    private ManagedChannel channel;

    public final long OPEN_TIMEOUT_MS = 10 * 1000;
    private long openTimeoutMs = OPEN_TIMEOUT_MS;

    public MatchEngineApiGrpc.MatchEngineApiStub asyncStub;

    StreamObserver<AppClient.ClientEdgeEvent> sender;
    StreamObserver<AppClient.ServerEdgeEvent> receiver;

    enum ChannelStatus {
        open,
        opening,
        closing,
        closed
    }
    ChannelStatus channelStatus = ChannelStatus.closed;
    Object openAwaiter = new Object();

    class AppInstDetails {
        Site lastSite;
        double margin;
        AppInstDetails(Site site, double margin) {
            this.lastSite = site;
            this.margin = margin;
        }
    }
    ConnectionDetails lastConnectionDetails;
    class ConnectionDetails {
        String host;
        int port;
        Network network;
        ConnectionDetails(String aHost, int aPort, Network aNetwork) {
            host = aHost;
            port = aPort;
            network = aNetwork;
        }
        AppInstDetails appInstPerformanceDetails;
    }

    private Location mLastLocationPosted = null;
    private AppClient.FindCloudletReply mLastPostedFindCloudletReply;

    // TODO: Use Site.DEFAULT_NUM_SAMPLES ?
    final int DEFAULT_NUM_SAMPLES = 5;

    /*!
     * A DeadEvent handler will print messages still. Outside EdgeEvents handler.
     */
    private UnsubscribedEventsHandler unsubscribedEventsHandler = new UnsubscribedEventsHandler();
    private boolean noEventInitHandlerObserved = false;

    private EdgeEventsConfig mEdgeEventsConfig;

    /*!
     * The set of EdgeEvents interval tasks.
     */
    private ConcurrentLinkedQueue<EdgeEventsIntervalHandler> edgeEventsIntervalHandlers = new ConcurrentLinkedQueue<>();
    /*!
     * Submit a new scheduled task into a managed set of configured EdgeEvents onInterval Tasks. The task
     * will then have its lifetime managed according to the lifecycle of the EdgeEventsConnection status.
     * \param the (scheduled) or started task to add.
     */
    public boolean addEdgeEventsIntervalTask(EdgeEventsIntervalHandler edgeEventsIntervalHandler) {
        if (edgeEventsIntervalHandler != null) {
            edgeEventsIntervalHandlers.add(edgeEventsIntervalHandler);
            return true;
        }
        return false;
    }
    /*!
     * Remove a Scheduled EdgeEvents Task. This will attempt to stop, then remove the task.
     * \param edgeEventsIntervalHandler the task to remove.
     */
    public boolean removeEdgeEventsIntervalTask(EdgeEventsIntervalHandler edgeEventsIntervalHandler) {
        if (edgeEventsIntervalHandler == null) {
            return false;
        }

        if (edgeEventsIntervalHandler.isDone()) {
            edgeEventsIntervalHandlers.remove(edgeEventsIntervalHandler);
            return true;
        }

        edgeEventsIntervalHandler.cancel();
        edgeEventsIntervalHandlers.remove(edgeEventsIntervalHandler);
        return true;
    }

    // This class is mainly here to allow a clear object to register for deadEvents subscription.
    private class UnsubscribedEventsHandler {
        /**
         * Listens to dead events, and prints them as warnings. If they are of interest, subscribe.
         * @param deadEvent a polymorphic object event that had no subscribers watching when sent.
         */
        @Subscribe
        void handleDeadEvent(DeadEvent deadEvent) {
            if (deadEvent.getEvent() instanceof AppClient.ServerEdgeEvent) {
                AppClient.ServerEdgeEvent serverEdgeEvent = (AppClient.ServerEdgeEvent)deadEvent.getEvent();
                if (serverEdgeEvent.getEventType() == ServerEventType.EVENT_INIT_CONNECTION) {
                    // By missing the INIT message, fire only supported subset of events only:
                    if (!noEventInitHandlerObserved) {
                        Log.d(TAG, "EventBus: To get all raw edgeEvent updates pushed to your app, subscribe to the Guava EventBus object type ServerEdgeEvents. Event Received: " + serverEdgeEvent.getEventType());
                    }
                    noEventInitHandlerObserved = true;
                } else if (serverEdgeEvent.hasNewCloudlet()) {
                    Log.w(TAG, "EventBus: To get all NewCloudlet updates pushed to your app, subscribe to the Guava EventBus object type FindCloudletEvent.");
                }
            } else {
                Log.d(TAG, "EventBus: Non-subscribed event received over EdgeEventsConnection: " + deadEvent.toString());
            }
        }
    }

    /*!
     * Errors on EdgeEvents
     */
    public enum EdgeEventsError {
        missingSessionCookie,
        missingEdgeEventsCookie,

        unableToGetLastLocation,
        missingGetLastLocationFunction,

        invalidEdgeEventsSetup,
        missingEdgeEventsConfig,
        missingLatencyUpdateConfig,
        missingLocationUpdateConfig,

        missingNewFindCloudletHandler,
        missingServerEventsHandler,

        missingLatencyThreshold,
        invalidLatencyThreshold,

        missingUpdateInterval,
        invalidUpdateInterval,

        hasNotDoneFindCloudlet,
        emptyAppPorts,
        portDoesNotExist,
        uninitializedEdgeEventsConnection,
        failedToClose,
        connectionAlreadyClosed,
        unableToCleanup,
        gpsLocationDidNotChange,

        eventTriggeredButCurrentCloudletIsBest,

        missingDmeDnsEntry
    }

    /*!
     * Status for EdgeEvents
     */
    public enum EdgeEventsStatus {
        success,
        fail
    }

    /*!
     * EdgeEventsConnection is used to establish an asynchronous DME Connection for streamed EdgeEvents
     * to the current DME edge server.
     * \param me the parent MatchingEngine instance.
     * \param eeConfig the configration to use, when the connection is up.
     */
    EdgeEventsConnection(MatchingEngine me, EdgeEventsConfig eeConfig) {
        this.me = me;
        lastConnectionDetails = new ConnectionDetails(null, 0, null);

        Log.w(TAG, "Configuring EdgeEvent Defaults");
        if (eeConfig == null) {
            Log.w(TAG, "EdgeEventsConfig is required. Using a basic default config because there is none provided.");
            mEdgeEventsConfig = EdgeEventsConfig.createDefaultEdgeEventsConfig();
        } else {
            mEdgeEventsConfig = new EdgeEventsConfig(eeConfig);
        }
    }

    boolean awaitOpen() {
        if (isShutdown()) {
            return false;
        }
        synchronized (openAwaiter) {
            if (channelStatus == ChannelStatus.open) {
                return true;
            }
            Log.d(TAG, "Not open. Waiting...");
            try {
                openAwaiter.wait(getOpenTimeoutMs());
            } catch (InterruptedException e) {
                Log.w(TAG, "awaitOpen() was Interrupted.");
                e.printStackTrace();
            }
            if (channelStatus == ChannelStatus.open) {
                Log.d(TAG, "Now open.");
                return true;
            } else {
                Log.d(TAG, "Not open: " + channelStatus);
                return false;
            }
        }
    }

    private void notifyOpenAwaiter() {
        if (isShutdown()) {
            return;
        }
        synchronized (openAwaiter) {
            Log.d(TAG, "Opened EdgeEventsConnection. Notify.");
            openAwaiter.notifyAll();
            Log.d(TAG, "Done notifying.");
        }
    }

    boolean isRegisteredForEvents = false;
    private boolean eventBusRegister() {
        if (me.getEdgeEventsBus() != null && !isRegisteredForEvents) {
            me.getEdgeEventsBus().register(unsubscribedEventsHandler);
            isRegisteredForEvents = true;
        }
        return isRegisteredForEvents;
    }
    private boolean eventBusUnRegister() {
        if (me.getEdgeEventsBus() != null && isRegisteredForEvents) {
            try {
                me.getEdgeEventsBus().unregister(unsubscribedEventsHandler);
            } catch (IllegalArgumentException iae) {
                Log.e(TAG, "Already unsubscribed handler.");
            }
            isRegisteredForEvents = false;
            noEventInitHandlerObserved = false;
        }
        return isRegisteredForEvents;
    }

    public Location getLastLocationPosted() {
        return mLastLocationPosted;
    }
    synchronized public void setLastLocationPosted(Location mLastLocationPosted) {
        if (mLastLocationPosted != null) {
            this.mLastLocationPosted = mLastLocationPosted;
        }
    }


    private boolean validateFindCloudlet(AppClient.FindCloudletReply findCloudletReply) {
        if (findCloudletReply.getEdgeEventsCookie() == null || findCloudletReply.getEdgeEventsCookie().isEmpty()) {
            return false;
        }
        return true;
    }

    synchronized private void postToFindCloudletEventHandler(FindCloudletEvent findCloudletEvent) {
        Log.d(TAG, "postToFindCloudletEventHandler");
        if (!validateFindCloudlet(findCloudletEvent.newCloudlet)) {
            postErrorToEventHandler(EdgeEventsError.missingEdgeEventsConfig);
            return;
        }

        // Post to interested subscribers.
        boolean subscribed = false;
        if (mEdgeEventsConfig.triggers != null &&
            mEdgeEventsConfig.triggers.contains(findCloudletEvent.trigger)) {
            subscribed = true;
        }

        if (subscribed) {
            me.getEdgeEventsBus().post(findCloudletEvent);
        }
    }

    /*!
     * The EdgeEventsConfig monitor may encounter errors that need to be reported. This reports it on
     * the subscribed event bus;
     */
    synchronized private void postErrorToEventHandler(EdgeEventsError error) {
        if (me.isShutdown()) {
            return;
        }
        me.getEdgeEventsBus().post(error);
    }

    /*!
     * Reconnects the EdgeEventsConnection with current settings. This will block until opened.
     */
    synchronized public void reconnect() throws DmeDnsException {
        // FIXME: using existing config until FindCloudletReply has the "new DME".
        reconnect(lastConnectionDetails.host, lastConnectionDetails.port, lastConnectionDetails.network, mEdgeEventsConfig);
    }

    synchronized void reconnect(String host, int port, Network network, EdgeEventsConfig eeConfig) throws DmeDnsException {
        if (me.isShutdown()) {
            return;
        }
        mEdgeEventsConfig = eeConfig;
        Log.d(TAG, "Reconnecting...");
        stopEdgeEvents();
        closeInternal();

        open(host, port, network, mEdgeEventsConfig);

        // We need to re-init the DME connection:
        // Client identifies itself with an Init message to DME EdgeEvents Connection.
        if (me.mFindCloudletReply == null || me.getSessionCookie() == null) {
            Log.e(TAG, "State Error: Missing sessions to reconnect.");
            postErrorToEventHandler(EdgeEventsError.missingSessionCookie);
            return;
        }
        awaitOpen();
    }

    /*!
     * Gets the timeout for opening a connection.
     */
    public long getOpenTimeoutMs() {
        return openTimeoutMs;
    }

    /*!
     * Sets the timeout to open a connection.
     * \param timeoutMs timeout in MS. If <= 0, uses default.
     */
    public void setOpenTimeout(long timeoutMs) {
        if (timeoutMs <= 0) {
            openTimeoutMs = OPEN_TIMEOUT_MS;
        } else {
            openTimeoutMs = timeoutMs;
        }
    }

    /*!
     * Use this to open a DME connection to nearest DME.
     */
    synchronized void open() throws DmeDnsException {
        try {
            // FIXME: using existing config until FindCloudletReply has the "new DME".
            open(lastConnectionDetails.host, lastConnectionDetails.port, lastConnectionDetails.network, mEdgeEventsConfig);
        } catch (NetworkOnMainThreadException nomte) {
            Log.e(TAG, "Consider running this call from a background thread.");
        }
    }

    synchronized void open(String host, int port, Network network, EdgeEventsConfig eeConfig) throws DmeDnsException {
        Log.d(TAG, "Current channel state: " + channelStatus);
        if (me.isShutdown()) {
            Log.e(TAG, "MatchingEngine is closed. Not opening.");
            return;
        }
        eventBusRegister();
        channelStatus = ChannelStatus.opening;


        // If there's a no EventBus handler (Deadhandler is called when there's no subscribers)
        // the default handler takes over for future messages, and puts warnings into the logs via
        // DeadEventsHandler until implemented. The default handler, is a public method to document,
        // open source and customize more closely to the application's needs.

        if (eeConfig == null) {
            mEdgeEventsConfig = new EdgeEventsConfig(EdgeEventsConfig.createDefaultEdgeEventsConfig());
        } else {
            mEdgeEventsConfig = new EdgeEventsConfig(eeConfig);
        }

        lastConnectionDetails.host = host;
        lastConnectionDetails.port = port;
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
            lastConnectionDetails.network = null;
        } else {
            lastConnectionDetails.network = network;
        }

        try {
            InetAddress.getAllByName(host);
        } catch (UnknownHostException uhe) {
            throw new DmeDnsException(("Could not find mcc-mnc.dme.mobiledgex.net DME server: " + host), uhe);
        }

        Log.i(TAG, "Opening EdgeEventsConnection on: Host: " + lastConnectionDetails.host + ", Port: " + lastConnectionDetails.port);
        this.channel = me.channelPicker(lastConnectionDetails.host, lastConnectionDetails.port, lastConnectionDetails.network);
        this.asyncStub = MatchEngineApiGrpc.newStub(channel);

        receiver = new StreamObserver<AppClient.ServerEdgeEvent>() {
            @Override
            public void onNext(AppClient.ServerEdgeEvent value) {
                // If nothing is subscribed, it just goes to deadEvent.
                // Raw Events.
                if (me.getEdgeEventsBus() != null) {
                    me.getEdgeEventsBus().post(value);
                }

                if (channelStatus != ChannelStatus.open) {
                    // Whatever it was, it's now open.
                    channelStatus = ChannelStatus.open;
                }
                if (value.getEventType() == ServerEventType.EVENT_INIT_CONNECTION) {
                    notifyOpenAwaiter();
                    runEdgeEvents();
                }
                if (value.hasNewCloudlet()) {
                    // Performance re-test from scratch.
                    lastConnectionDetails.appInstPerformanceDetails = null;
                }

                // Default handler will handle incoming messages if no subscribers are currently observed
                // watching for the EVENT_INIT_CONNECTION message.
                // This is effective for the life of this EdgeEventsConnection.
                if (noEventInitHandlerObserved) {
                    // Internal handler, app wants default, but without observed events. These are triggers.

                    // This is not the same as runEdgeEvents, which are configured events.
                    HandleDefaultEdgeEvents(value);
                }
            }

            @Override
            public void onError(Throwable t) {
                channelStatus = ChannelStatus.closed;
                Log.w(TAG, "Encountered error in DMEConnection: ", t);
                if (me.getEdgeEventsBus() != null) {
                    me.getEdgeEventsBus().post(EdgeEventsError.uninitializedEdgeEventsConnection);
                }
                Log.e("Message", "DME Connection closed. A new client initiated FindCloudlet required for edge events stream.");
            }

            @Override
            public void onCompleted() {
                channelStatus = ChannelStatus.closed;
                Log.i(TAG, "Stream closed.");

                // Reopen DME connection.
                try {
                    reconnect();
                } catch (DmeDnsException dde) {
                    Log.e(TAG, "Message: " + dde.getLocalizedMessage());
                } catch (Exception e) {
                    Log.e(TAG, "Message: " + e.getLocalizedMessage() + " Exception. DME Connection closed. A new client initiated FindCloudlet required for edge events stream.");
                }
            }
        };

        // No deadline, since it's streaming:
        sender = asyncStub.streamEdgeEvent(receiver);

        String sessionCookie = me.mSessionCookie;
        String edgeEventsCookie = me.mFindCloudletReply != null ?me.mFindCloudletReply.getEdgeEventsCookie() : null;

        if (sessionCookie != null && edgeEventsCookie != null) {
            AppClient.ClientEdgeEvent.Builder initDmeEventBuilder = AppClient.ClientEdgeEvent.newBuilder()
                    .setEventType(AppClient.ClientEdgeEvent.ClientEventType.EVENT_INIT_CONNECTION)
                    .setSessionCookie(me.mSessionCookie)
                    .setEdgeEventsCookie(me.mFindCloudletReply.getEdgeEventsCookie())
                    .mergeDeviceInfoStatic(me.getDeviceInfoStaticProto())
                    .mergeDeviceInfoDynamic(me.getDeviceInfoDynamicProto());
                send(initDmeEventBuilder.build());
        } else {
            Log.e(TAG, "Missing info for INIT. Not opening.");
            postErrorToEventHandler(EdgeEventsError.uninitializedEdgeEventsConnection);
            closeInternal();
        }
    }

    /*!
     * Peer connection clear from onComplete, onError.
     */
    void closeInternal() {
        if (channelStatus == ChannelStatus.closed) {
            return;
        }

        Log.d(TAG, "stream closing...");
        channelStatus = ChannelStatus.closing;
        if (channel != null && !isShutdown()) {
            sendTerminate();
        }
        channel = null;
        channelStatus = ChannelStatus.closed;
        Log.d(TAG, "stream closed!");
    }

    /*!
     * Call this to shutdown EdgeEvents cleanly. Instance cannot be reopened.
     */
    void close() {
        if (!isShutdown()) {
            Log.d(TAG, "closing...");
            eventBusUnRegister();
            closeInternal();
            lastConnectionDetails = null;
        }
        synchronized (openAwaiter) {
            Log.d(TAG, "notify...");
            openAwaiter.notifyAll();
            openAwaiter = null;
        }
    }

    /* Set the best site for the configured App for this EdgeEventsConnection.
     * \param Site
     */
    void updateBestAppSite(Site site, double margin) {
        if (isShutdown()) {
            return;
        }
        Log.d(TAG, "Best site is being updated to: " + site.host + ", avg latency: " + site.average + ", Margin: " + margin);
        lastConnectionDetails.appInstPerformanceDetails = new AppInstDetails(site, margin);
    }

    public boolean isShutdown() {
        if (channel == null) {
            return true;
        }

        if (channelStatus == ChannelStatus.closed) {
            return true;
        }

        return channel.isShutdown();
    }

    synchronized public boolean send(AppClient.ClientEdgeEvent clientEdgeEvent) {
        try {
            if (me.isShutdown()) {
                Log.w(TAG, "MatchingEngine is shutdown. Message is not Posted!");
                return false;
            }
            Log.d(TAG, "Received this event to post to server: " + clientEdgeEvent);
            if (!me.isEnableEdgeEvents()) {
                Log.e(TAG, "EdgeEvents is disabled. Message is not sent.");
                return false;
            }
            if (isShutdown() && channelStatus != ChannelStatus.closing && channelStatus != ChannelStatus.closed) {
                Log.d(TAG, "Reconnecting to post: Channel status: " + channelStatus);
                reconnect();
            }
            if (sender != null) {
                // Submit from one thread to GRPC.
                CompletableFuture.runAsync(
                        () -> {
                            sender.onNext(clientEdgeEvent);
                        }, me.threadpool);
                Log.d(TAG, "Posted!");
            }
            else {
                Log.d(TAG, "NOT Posted!");
            }
        } catch (Exception e) {
            Log.e(TAG, e.getMessage() + ", cause: " + e.getCause());
            e.printStackTrace();
            return false;
        }
        return true;
    }

    boolean sendTerminate() {
        channelStatus = ChannelStatus.closed;
        if (isShutdown() || me.isShutdown())  {
            return false;
        }

        AppClient.ClientEdgeEvent clientEdgeEvent = AppClient.ClientEdgeEvent.newBuilder()
                .setEventType(AppClient.ClientEdgeEvent.ClientEventType.EVENT_TERMINATE_CONNECTION)
                .build();

        if (sender != null) {
            Log.d(TAG, "Submitting connection termination message to server.");
            try {
                CompletableFuture.runAsync(
                        () -> {
                            sender.onNext(clientEdgeEvent);
                            sender = null;
                        }, me.threadpool).get();
            } catch (ExecutionException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            Log.d(TAG, "Posted!");
        }
        receiver = null;
        sender = null;
        channel.shutdown();
        try {
            Stopwatch stopwatch = Stopwatch.createStarted();
            channel.awaitTermination(10, TimeUnit.SECONDS);
            Log.e(TAG, "Time to terminate: " + stopwatch.elapsed(TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        channel = null;
        Log.d(TAG, "Submitted termination.");

        return true;
    }

    /*!
     * Get Location. This is a blocking call.
     */
    public Location getLocation() {
        if (!MatchingEngine.isMatchingEngineLocationAllowed()) {
            Log.e(TAG, "MobiledgeX Location services are not permitted until allowed by application.");
            return null;
        }

        Location location = null;
        MeLocation meLocation = new MeLocation(me);
        try {
            location = meLocation.getBlocking(me.mContext, 10000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            e.printStackTrace();
        } finally {
            return location;
        }
    }

    /*!
     * Outbound Client to Server location update. If there is a closer cloudlet, this will cause a
     * Guava ServerEdgeEvent EVENT_CLOUDLET_UPDATE message to be sent to subscribers.
     * \param location (Android location format)
     * \return whether the message was posted.
     * \ingroup functions_edge_events_api
     * \section basic_location_handler_example Example
     * \snippet MainActivity.java basic_location_handler_example
     */
    synchronized public boolean postLocationUpdate(Location location) {
        if (isShutdown() || me.isShutdown())  {
            Log.w(TAG, "Connection not currently open. Message dropped.");
            return false;
        }
        if (!me.isMatchingEngineLocationAllowed()) {
            return false;
        }

        if (isShutdown()) {
            return false;
        }

        LocOuterClass.Loc loc;
        if (location == null) {
            Log.w(TAG, "Location passed was passed null. Getting location automatically.");
            location = getLocation();
        }
        if (location == null) {
            Log.e(TAG, "Location supplied is null, and cannot get location from location provider.");
            postErrorToEventHandler(EdgeEventsError.unableToGetLastLocation);
            return false;
        }

        loc = me.androidLocToMeLoc(location);
        if (loc == null) {
            Log.e(TAG, "Location Loc cannot be null!");
            postErrorToEventHandler(EdgeEventsError.unableToGetLastLocation);
            return false;
        }

        AppClient.ClientEdgeEvent.Builder clientEdgeEventBuilder = AppClient.ClientEdgeEvent.newBuilder()
                .setEventType(AppClient.ClientEdgeEvent.ClientEventType.EVENT_LOCATION_UPDATE)
                .setGpsLocation(loc);
        Appcommon.DeviceInfoDynamic deviceInfoDynamic = me.getDeviceInfoDynamicProto();
        if (deviceInfoDynamic != null) {
            clientEdgeEventBuilder.setDeviceInfoDynamic(deviceInfoDynamic);
        }

        AppClient.ClientEdgeEvent clientEdgeEvent = clientEdgeEventBuilder.build();

        if (send(clientEdgeEvent)) {
            setLastLocationPosted(location);
            return true;
        }
        return false;
    }

    /*!
     * Outbound ClientEdgeEvent to DME. Post already collected site statistics.
     * A DME administrator of your Application may request an client application to collect performance
     * NetTest stats to their current AppInst with the ServerEdgeEvent EVENT_LATENCY_REQUEST.
     *
     * \param site
     * \param location
     * \return boolean indicating whether the site results are posted or not.
     * \ingroup functions_edge_events_api
     */
    synchronized public boolean postLatencyUpdate(Site site, Location location) {
        if (isShutdown() || me.isShutdown())  {
            Log.w(TAG, "Connection not currently open. Message dropped.");
            return false;
        }
        if (!me.isMatchingEngineLocationAllowed()) {
            return false;
        }

        LocOuterClass.Loc loc = null;

        if (site == null || (site.samples == null || site.samples.length == 0)) {
            // No results to post.
            return false;
        }

        if (location == null) {
            location = getLocation();
        }
        if (location == null) {
            Log.e(TAG, "Location supplied is null, and cannot get location from location provider.");
            postErrorToEventHandler(EdgeEventsError.unableToGetLastLocation);
            return false;
        }
        loc = me.androidLocToMeLoc(location);
        if (loc == null) {
            Log.e(TAG, "Location Loc cannot be null!");
            postErrorToEventHandler(EdgeEventsError.unableToGetLastLocation);
            return false;
        }

        AppClient.ClientEdgeEvent.Builder clientEdgeEventBuilder = AppClient.ClientEdgeEvent.newBuilder()
                .setEventType(AppClient.ClientEdgeEvent.ClientEventType.EVENT_LATENCY_SAMPLES)
                .setGpsLocation(loc);

        for (int i = 0; i < site.samples.length; i++) {
            LocOuterClass.Sample.Builder sampleBuilder = LocOuterClass.Sample.newBuilder()
                    //.setLoc(loc) Location is not synchronous with measurement.
                    // Samples are not timestamped.
                    .setValue(site.samples[i]);
            clientEdgeEventBuilder.addSamples(sampleBuilder.build());
        }
        Appcommon.DeviceInfoDynamic deviceInfoDynamic = me.getDeviceInfoDynamicProto();
        if (deviceInfoDynamic != null) {
            clientEdgeEventBuilder.mergeDeviceInfoDynamic(deviceInfoDynamic);
        }

        AppClient.ClientEdgeEvent clientEdgeEvent = clientEdgeEventBuilder.build();

        if (send(clientEdgeEvent)) {
            setLastLocationPosted(location);
            return true;
        }
        return false;
    }

    /*!
     * Outbound ClientEdgeEvent to DME. Post site statistics with the most recent FindCloudletReply.
     * A DME administrator of your Application may request an client application to collect performance
     * stats to their current AppInst with the ServerEdgeEvent EVENT_LATENCY_REQUEST.
     *
     * \param android format location
     * \return boolean indicating whether the site results are posted or not.
     * \ingroup functions_edge_events_api
     */
    synchronized public boolean testPingAndPostLatencyUpdate(Location location) {
        if (isShutdown() || me.isShutdown())  {
            Log.w(TAG, "Connection not currently open. Message dropped.");
            return false;
        }
        if (!me.isMatchingEngineLocationAllowed()) {
            return false;
        }

        LocOuterClass.Loc loc;

        if (location == null) {
            location = getLocation();
        }
        if (location == null) {
            Log.e(TAG, "Location supplied is null, and cannot get location from location provider.");
            postErrorToEventHandler(EdgeEventsError.unableToGetLastLocation);
            return false;
        }
        loc = me.androidLocToMeLoc(location);
        if (loc == null) {
            Log.e(TAG, "Location Loc cannot be null!");
            postErrorToEventHandler(EdgeEventsError.unableToGetLastLocation);
            return false;
        }

        AppClient.FindCloudletReply lastFc = me.getLastFindCloudletReply();
        if (lastFc == null || lastFc.getStatus() != AppClient.FindCloudletReply.FindStatus.FIND_FOUND) {
            Log.w(TAG, "Unable to test. A previous successful FindCloudletReply is required to test edge AppInst");
            return false;
        }
        String host = me.getAppConnectionManager().getHost(lastFc, mEdgeEventsConfig.latencyInternalPort);
        int port = me.getAppConnectionManager().getPublicPort(lastFc, mEdgeEventsConfig.latencyInternalPort);

        if (host == null || host.length() == 0) {
            // No results to post.
            Log.e(TAG, "host cannot be null.");
            return false;
        }

        AppClient.ClientEdgeEvent.Builder clientEdgeEventBuilder = AppClient.ClientEdgeEvent.newBuilder()
                .setEventType(AppClient.ClientEdgeEvent.ClientEventType.EVENT_LATENCY_SAMPLES)
                .setGpsLocation(loc);
        Appcommon.DeviceInfoDynamic deviceInfoDynamic = me.getDeviceInfoDynamicProto();
        if (deviceInfoDynamic != null) {
            clientEdgeEventBuilder.mergeDeviceInfoDynamic(deviceInfoDynamic);
        }

        Site site = new Site(me.mContext, NetTest.TestType.PING,
                Site.DEFAULT_NUM_SAMPLES,
                host, port);
        NetTest netTest = new NetTest();
        netTest.addSite(site);
        // Test list of sites:
        netTest.testSites(netTest.TestTimeoutMS);

        // Trigger(s):
        if (site.average >= mEdgeEventsConfig.latencyThresholdTrigger) {
            Log.i(TAG, "Latency higher than requested during Ping latency test.");
            doClientFindCloudlet(FindCloudletEventTrigger.LatencyTooHigh).thenApply( result -> {
                if (!result) {
                    postErrorToEventHandler(EdgeEventsError.eventTriggeredButCurrentCloudletIsBest);
                }
                return result;
            });
        }

        for (int i = 0; i < site.samples.length; i++) {
            LocOuterClass.Sample.Builder sampleBuilder = LocOuterClass.Sample.newBuilder()
                    //.setLoc(loc) Location is not synchronous with measurement.
                    // Samples are not timestamped.
                    .setValue(site.samples[i]);
            clientEdgeEventBuilder.addSamples(sampleBuilder.build());
        }

        AppClient.ClientEdgeEvent clientEdgeEvent = clientEdgeEventBuilder.build();

        if (send(clientEdgeEvent)) {
            setLastLocationPosted(location);
            return true;
        }
        return false;
    }

    /*!
     * Outbound ClientEdgeEvent to DME. Post site statistics with the most recent FindCloudletReply.
     * A DME administrator of your Application may request an client application to collect performance
     * NetTest stats to their current AppInst with the ServerEdgeEvent EVENT_LATENCY_REQUEST.
     *
     * This utility function uses the default network path. It does not swap network interfaces.
     *
     * \param android format GPS location.
     * \param number of samples to test
     * \return boolean indicating whether the site results are posted or not.
     * \ingroup functions_edge_events_api
     */
    synchronized public boolean testConnectAndPostLatencyUpdate(Location location) {
        return testConnectAndPostLatencyUpdate(0, location);
    }

    /*!
     * Outbound ClientEdgeEvent to DME. Post site statistics with the most recent FindCloudletReply.
     * A DME administrator of your Application may request an client application to collect performance
     * stats to their current AppInst with the ServerEdgeEvent EVENT_LATENCY_REQUEST.
     *
     * This utility function uses the default network path. It does not swap network interfaces.
     *
     * \param internalPort the internal port of your App definition. Not the public mapped port for the edge instance.
     * \param android format GPS location.
     * \return boolean indicating whether the site results are posted or not.
     * \ingroup functions_edge_events_api
     */
    synchronized public boolean testConnectAndPostLatencyUpdate(int internalPort, Location location) {
        if (isShutdown() || me.isShutdown())  {
            Log.w(TAG, "Connection not currently open. Message dropped.");
            return false;
        }
        if (!me.isMatchingEngineLocationAllowed()) {
            return false;
        }

        LocOuterClass.Loc loc = null;

        AppClient.FindCloudletReply lastFc = me.getLastFindCloudletReply();
        if (lastFc == null || lastFc.getStatus() != AppClient.FindCloudletReply.FindStatus.FIND_FOUND) {
            Log.w(TAG, "Unable to test. A previous successful FindCloudletReply is required to test edge AppInst");
            return false;
        }
        String host = me.getAppConnectionManager().getHost(lastFc, mEdgeEventsConfig.latencyInternalPort);
        int port = internalPort;
        if (port <= 0) {
            port = me.getAppConnectionManager().getPublicPort(lastFc, mEdgeEventsConfig.latencyInternalPort);
        }

        if (host == null || host.length() == 0) {
            // No results to post.
            return false;
        }

        if (port <= 0) {
            postErrorToEventHandler(EdgeEventsError.portDoesNotExist);
            return false;
        }

        if (location == null) {
            location = getLocation();
        }
        if (location == null) {
            Log.e(TAG, "Location supplied is null, and cannot get location from location provider.");
            postErrorToEventHandler(EdgeEventsError.unableToGetLastLocation);
            return false;
        }
        loc = me.androidLocToMeLoc(location);
        if (loc == null) {
            Log.e(TAG, "Location Loc cannot be null!");
            postErrorToEventHandler(EdgeEventsError.unableToGetLastLocation);
            return false;
        }

        AppClient.ClientEdgeEvent.Builder clientEdgeEventBuilder = AppClient.ClientEdgeEvent.newBuilder()
                .setEventType(AppClient.ClientEdgeEvent.ClientEventType.EVENT_LATENCY_SAMPLES)
                .setGpsLocation(loc);

        Site site = new Site(me.mContext, NetTest.TestType.CONNECT,
                Site.DEFAULT_NUM_SAMPLES,
                host,
                port);
        NetTest netTest = new NetTest();
        netTest.addSite(site);
        // Test list of sites:
        netTest.testSites(netTest.TestTimeoutMS);

        // Trigger(s):
        if (site.average >= mEdgeEventsConfig.latencyThresholdTrigger) {
            Log.i(TAG, "Latency higher than requested during Connect latency test.");
            doClientFindCloudlet(FindCloudletEventTrigger.LatencyTooHigh).thenApply( result -> {
                if (!result) {
                    postErrorToEventHandler(EdgeEventsError.eventTriggeredButCurrentCloudletIsBest);
                }
                return result;
            });
        }

        for (int i = 0; i < site.samples.length; i++) {
            LocOuterClass.Sample.Builder sampleBuilder = LocOuterClass.Sample.newBuilder()
                    //.setLoc(loc) Location is not synchronous with measurement.
                    // Samples are not timestamped.
                    .setValue(site.samples[i]);
            clientEdgeEventBuilder.addSamples(sampleBuilder.build());
        }
        Appcommon.DeviceInfoDynamic deviceInfoDynamic = me.getDeviceInfoDynamicProto();
        if (deviceInfoDynamic != null) {
            clientEdgeEventBuilder.mergeDeviceInfoDynamic(deviceInfoDynamic);
        }

        AppClient.ClientEdgeEvent clientEdgeEvent = clientEdgeEventBuilder.build();

        if (send(clientEdgeEvent)) {
            setLastLocationPosted(location);
            return true;
        }
        return false;
    }

    private void validateStartConfig(String host, EdgeEventsConfig edgeEventsConfig) {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("Host canot be null!");
        }
        if (edgeEventsConfig == null) {
            throw new IllegalArgumentException("Config cannot be null!");
        }
    }


    ///
    // Auto test example code pieces.
    ///

    /*!
     * Fires off tasks to run EdgeEvents monitoring as per config. EdgeEvents must be enabled.
     * This is separate from the normal SDK handled events, like latency requests and new cloudlet
     * availability. Those EdgeEvents, you should subscribe to.
     *
     * Cancel with stopEdgeEvents.
     * \param dmeHost DME hostname.
     * \param dmePort
     * \param edgeEventsConfig
     * \param network override network.
     * \ingroup functions_edge_events_api
     */
    synchronized public boolean runEdgeEvents() {
        try {
            if (isShutdown() || me.isShutdown())  {
                Log.w(TAG, "Connection not currently open. Interval Tests will not run.");
                return false;
            }
            if (!me.isEnableEdgeEvents()) {
                Log.e(TAG, "EdgeEvents are disabled. Managed EdgeEvents disabled.");
                return false;
            }

            // Abort if no find cloudlet yet.
            if (me.mFindCloudletReply == null) {
                Log.e(TAG, "The App needs to have a FindCloudlet reply of FIND_FOUND before startEdgeEvents can be used");
                return false;
            }

            if (me.mFindCloudletReply.getPortsCount() < 1) {
                Log.e(TAG, "The last findCloudlet does NOT have any ports! Ports Count: " + me.mFindCloudletReply.getPortsCount());
                return false;
            }
            int internalPort = mEdgeEventsConfig.latencyInternalPort;
            Appcommon.AppPort appPort = me.getAppConnectionManager().getAppPort(me.mFindCloudletReply, mEdgeEventsConfig.latencyInternalPort);
            if (appPort == null) {
                Log.e(TAG, "The latencyInternalPort [" + mEdgeEventsConfig.latencyInternalPort + "] was not found. EdgeEvents cannot start.");
                return false;
            }
            String host = me.getAppConnectionManager().getHost(me.mFindCloudletReply, appPort);

            int publicPort = me.getAppConnectionManager().getPublicPort(me.mFindCloudletReply, internalPort);

            if (publicPort == 0) {
                Log.e(TAG, "Public port doesn't seem to exist for the Latency Test InternalPort: " + internalPort);
                return false;
            }

            if (mEdgeEventsConfig == null) {
                return false;
            }

            validateStartConfig(host, mEdgeEventsConfig);

            // Known Scheduled Timer tasks:
            runLatencyMonitorConfig();
            runLocationMonitorConfig();

            // Launched Scheduled tasks.
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Hit exception attempting to runEdgeEvents: " + e.getMessage());
            throw e;
        }
    }

    synchronized private void runLocationMonitorConfig() {
        if (!me.mMatchingEngineLocationAllowed) {
            Log.w(TAG, "Location disabled. setMatchignEngineLocationAllowed(true) to enable task.");
            return;
        }

        if (mEdgeEventsConfig.locationUpdateConfig != null) {
            UpdateConfig locationUpdateConfig = mEdgeEventsConfig.locationUpdateConfig;

            switch (locationUpdateConfig.updatePattern) {
                case onStart:
                    postLocationUpdate(getLocation());
                    break;
                case onTrigger:
                    eventBusRegister(); // Attach Subscriber for triggers.
                    break;
                case onInterval:
                    eventBusRegister(); // Attach Subscriber, to handle triggers and montoriing by interval.
                    // Start, and add to a list of known EdgeEvent Testing Handlers
                    if (locationUpdateConfig != null) {
                        addEdgeEventsIntervalTask(new EdgeEventsLocationIntervalHandler(me, locationUpdateConfig));
                    }
                    break;
            }
        }
    }

    synchronized private void runLatencyMonitorConfig() {
        if (mEdgeEventsConfig.latencyUpdateConfig != null) {
            UpdateConfig latencyUpdateConfig = mEdgeEventsConfig.latencyUpdateConfig;

            switch (latencyUpdateConfig.updatePattern) {
                case onStart:
                    if (mEdgeEventsConfig.latencyInternalPort <= 0) {
                        testPingAndPostLatencyUpdate(getLocation());
                    } else {
                        testConnectAndPostLatencyUpdate(getLocation());
                    }
                    break;
                case onTrigger:
                    eventBusRegister(); // Attach Subscriber for triggers.
                    break;
                case onInterval:
                    // Last FindCloudlet
                    eventBusRegister(); // Attach Subscriber, to handle triggers and monitoring by interval.
                    // Add to a list of known EdgeEvent Testing Handlers
                    if (latencyUpdateConfig != null) {
                        addEdgeEventsIntervalTask(new EdgeEventsLatencyIntervalHandler(me, mEdgeEventsConfig.latencyTestType, latencyUpdateConfig));
                    }
                    break;
            }
        }
    }

    public void stopEdgeEvents() {
        Log.d(TAG, "stopEdgeEvents()");
        for (EdgeEventsIntervalHandler eh : edgeEventsIntervalHandlers) {
            removeEdgeEventsIntervalTask(eh);
        }
    }

    // Configured Defaults:
    // This is the default EventsHandler and Monitor for onTrigger events.
    // It should not be subscribed, it is directly called if DeadEvents is observed.
    private boolean HandleDefaultEdgeEvents(AppClient.ServerEdgeEvent event) {
        boolean ret = true;

        if (isShutdown()) {
            Log.e(TAG, "Received events after shutdown at EdgeEvents handler. Not sending.");
            return false;
        }

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
                ret = handleFindCloudletServerPush(event, FindCloudletEventTrigger.CloserCloudlet);
                break;
            case EVENT_ERROR:
                Log.d(TAG,"Received: An edgeEvents error: " + event.getErrorMsg());
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


    // Non raw ServerEdgeEvent, this will create a FindCloudlet.
    CompletableFuture<Boolean> doClientFindCloudlet(FindCloudletEventTrigger reason) {
        return CompletableFuture.supplyAsync( () -> {
            if (isShutdown() && me.isShutdown()) {
                return false;
            }
            Location loc;

            // Need app details to attempt posting.
            if (me.getLastRegisterClientRequest() == null) {
                return false;
            }
            if (me.getMatchingEngineStatus() == null || me.getMatchingEngineStatus().getSessionCookie() == null) {
                return false;
            }

            // FindCloudlet needs location:
            loc = getLocation();

            AppClient.FindCloudletRequest request = me.createDefaultFindCloudletRequest(me.mContext, loc)
                    .build();

            try {
                // If the latency trigger spec is not met, no auto-migrate happens.
                AppClient.FindCloudletReply lastMeReply = me.getLastFindCloudletReply();
                AppClient.FindCloudletReply reply = me.findCloudlet(
                        request,
                        lastConnectionDetails.host,
                        lastConnectionDetails.port,
                        me.getNetworkManager().getTimeout(),
                        me.mEdgeEventsConfig.latencyTriggerTestMode,
                        (long)me.mEdgeEventsConfig.latencyThresholdTrigger);

                if (reply == null || reply.getStatus() == AppClient.FindCloudletReply.FindStatus.FIND_NOTFOUND) {
                    postErrorToEventHandler(EdgeEventsError.eventTriggeredButCurrentCloudletIsBest);
                    return false;
                }

                boolean doPost = false;
                if (mLastPostedFindCloudletReply == null) {
                    // First post.
                    mLastPostedFindCloudletReply = lastMeReply;
                }

                // Weak check: FQDN changed.
                if (!reply.getFqdn().equals(mLastPostedFindCloudletReply.getFqdn())) {
                    doPost = true;
                }

                if (!doPost) {
                    postErrorToEventHandler(EdgeEventsError.eventTriggeredButCurrentCloudletIsBest);
                    return false;
                }

                // Note: Auto start or AutoMigrate of edgeEvents already happened as necessary in FindCloudlet.
                // Just post here. NewCloudlet push is, however, subject to AutoMigrate flag.
                Log.i(TAG, "Different cloudlet than last posted.");
                mLastPostedFindCloudletReply = reply;
                FindCloudletEvent event = new FindCloudletEvent(reply, reason);
                postToFindCloudletEventHandler(event);
                return true;
            } catch (InterruptedException e) {
                // do nothing.
            } catch (ExecutionException e) {
                Log.d(TAG, "EdgeEvents ExecutionException doing auto doClientFindCloudlet(): " + e.getMessage());
                e.printStackTrace();
            }
            return false;
        }, me.threadpool);
    }

    synchronized boolean handleFindCloudletServerPush(AppClient.ServerEdgeEvent event, FindCloudletEventTrigger reason) {
        Log.i(TAG, "Received a new Edge FindCloudlet. Pushing to new FindCloudlet subscribers.");
        if (event.hasNewCloudlet()) {


            if (me.getLastFindCloudletReply() == null) {
                Log.i(TAG, "No previous cloudlet.");
            }
            else if (event.getNewCloudlet().getFqdn().equals(me.getLastFindCloudletReply().getFqdn())) {
                Log.w(TAG, "newCloudlet from server is the same a the last one. Nothing to do. Posting error message.");
                postErrorToEventHandler(EdgeEventsError.eventTriggeredButCurrentCloudletIsBest);
                return true;
            }

            // Update MatchingEngine.
            me.setFindCloudletResponse(event.getNewCloudlet());

            FindCloudletEvent fce = new FindCloudletEvent(
                    event.getNewCloudlet(),
                    reason);

            // Post to new FindCloudletEvent Handler subscribers, if any, on the same EventBus:
            postToFindCloudletEventHandler(fce);

            // Policy: Migrate to new DME connection?
            if (!me.isAutoMigrateEdgeEventsConnection()) {
                Log.w(TAG, "autoMigrateEdgeEventsConnection is set to false. When app has migrated to new cloudlet, call MatchingEngine's switchedToNewCloudlet().");
            }
            else {
                try {
                    reconnect();
                } catch (DmeDnsException dde) {
                    postErrorToEventHandler(EdgeEventsError.missingDmeDnsEntry);
                    return false; // Cannot reconnect.
                }
            }
        } else {
            Log.e(TAG, "Error: Server is missing an expected findCloudlet!");
            postErrorToEventHandler(EdgeEventsError.eventTriggeredButCurrentCloudletIsBest);
            return false;
        }

        return true;
    }

    synchronized boolean handleAppInstHealth(AppClient.ServerEdgeEvent event) {
        if (event.getEventType() != AppClient.ServerEdgeEvent.ServerEventType.EVENT_APPINST_HEALTH) {
            return false;
        }

        switch (event.getHealthCheck()) {
            // Informational:
            case HEALTH_CHECK_OK:
            case HEALTH_CHECK_UNKNOWN: // yes, OK.
                Log.i(TAG, "Informational appInst HealthCheck is OK. Reason: " + event.getHealthCheck());
                break;

            // Handle Event:
            case HEALTH_CHECK_FAIL_ROOTLB_OFFLINE: // fallthrough
            case HEALTH_CHECK_FAIL_SERVER_FAIL:
            case UNRECOGNIZED: // Presumably if not OK, means to get a new FindCloudlet.
            default:
                Log.i(TAG, "AppInst Health event. Reason: " + event.getHealthCheck());
                if (mEdgeEventsConfig.triggers.contains(FindCloudletEventTrigger.AppInstHealthChanged)) {
                    handleFindCloudletServerPush(event, FindCloudletEventTrigger.AppInstHealthChanged);
                }
                break;
        }
        return true;
    }

    synchronized boolean handleCloudletMaintenance(AppClient.ServerEdgeEvent event) {
        if (event.getEventType() != AppClient.ServerEdgeEvent.ServerEventType.EVENT_CLOUDLET_MAINTENANCE) {
            return false;
        }

        switch (event.getMaintenanceState()) {
            // Handle event:
            case UNDER_MAINTENANCE:
                Log.i(TAG,"Maintenance state changed! Reason: " + event.getMaintenanceState());
                if (mEdgeEventsConfig.triggers.contains(FindCloudletEventTrigger.CloudletMaintenanceStateChanged)) {
                    handleFindCloudletServerPush(event, FindCloudletEventTrigger.CloudletMaintenanceStateChanged);
                }
            // Informational.
            case NORMAL_OPERATION:
            case MAINTENANCE_START: // all fall through
            case FAILOVER_REQUESTED:
            case FAILOVER_DONE:
            case FAILOVER_ERROR:
            case MAINTENANCE_START_NO_FAILOVER:
            case CRM_REQUESTED:
            case CRM_UNDER_MAINTENANCE:
            case CRM_ERROR:
            case NORMAL_OPERATION_INIT:
            default:
                Log.i(TAG,"Informational maintenance state changed! Reason:" + event.getMaintenanceState());
        }
        return true;
    }

    synchronized boolean handleCloudletState(AppClient.ServerEdgeEvent event) {
        if (event.getEventType() != ServerEventType.EVENT_CLOUDLET_STATE) {
            return false;
        }

        switch (event.getCloudletState()) {
            // Informational:
            case CLOUDLET_STATE_READY:
                Log.i(TAG,"Informational cloudletState is ready and OK. Reason: " + event.getCloudletState());
                break;
            // Handle event:
            case CLOUDLET_STATE_UNKNOWN:
            case CLOUDLET_STATE_ERRORS:
            case CLOUDLET_STATE_OFFLINE:
            case CLOUDLET_STATE_NOT_PRESENT:
            case CLOUDLET_STATE_INIT:
            case CLOUDLET_STATE_UPGRADE:
            case CLOUDLET_STATE_NEED_SYNC:
            default:
                Log.i(TAG,"Cloudlet State Change. Reason: " + event.getCloudletState());
                if (mEdgeEventsConfig.triggers.contains(FindCloudletEventTrigger.CloudletStateChanged)) {
                    handleFindCloudletServerPush(event, FindCloudletEventTrigger.CloudletStateChanged);
                }
                break;
        }
        return true;
    }
    // Only the app knows with any certainty which AppPort (and internal port array)
    // it wants to test, so this is in the application.
    synchronized boolean handleLatencyRequest(AppClient.ServerEdgeEvent event) {
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

                    if (mEdgeEventsConfig == null) {
                        Log.e(TAG, "No Latency Config set!");
                        return false;
                    }

                    if (mEdgeEventsConfig.latencyInternalPort <= 0 || mEdgeEventsConfig.latencyTestType == NetTest.TestType.PING) {
                        me.getEdgeEventsConnection().testPingAndPostLatencyUpdate(getLocation());
                        return false;
                    }

                    // have (internal) port defined, use it.
                    int publicPort = me.getAppConnectionManager().getPublicPort(me.mFindCloudletReply, mEdgeEventsConfig.latencyInternalPort);
                    if (publicPort == 0) {
                        Log.i(TAG, "Your expected server (or port) doesn't seem to be here!");
                        return false;
                    }
                    // Test with default network in use:
                    Appcommon.AppPort appPort = me.getAppConnectionManager().getAppPort(me.mFindCloudletReply, mEdgeEventsConfig.latencyInternalPort);
                    if (appPort == null) {
                        postErrorToEventHandler(EdgeEventsError.portDoesNotExist);
                        return false;
                    }
                    String host = appPort.getFqdnPrefix() + me.mFindCloudletReply.getFqdn();

                    Site site = new Site(me.mContext, NetTest.TestType.CONNECT, DEFAULT_NUM_SAMPLES, host, publicPort);
                    netTest.addSite(site);
                    // Blocks.
                    netTest.testSites(netTest.TestTimeoutMS); // Test the one we just added.

                    // Trigger(s):
                    if (site.average >= mEdgeEventsConfig.latencyThresholdTrigger) {
                        Log.i(TAG, "Latency higher than requested");
                        doClientFindCloudlet(FindCloudletEventTrigger.LatencyTooHigh).thenApply( result -> {
                            if (!result) {
                                postErrorToEventHandler(EdgeEventsError.eventTriggeredButCurrentCloudletIsBest);
                            }
                            return result;
                        });
                    }

                    return me.getEdgeEventsConnection().postLatencyUpdate(netTest.getSite(host), getLocation());
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
