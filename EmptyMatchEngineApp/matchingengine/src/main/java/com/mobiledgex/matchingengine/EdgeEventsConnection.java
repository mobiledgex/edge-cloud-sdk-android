package com.mobiledgex.matchingengine;

import android.location.Location;
import android.net.Network;
import android.os.NetworkOnMainThreadException;
import android.util.Log;

import com.google.common.eventbus.DeadEvent;
import com.google.common.eventbus.Subscribe;
import com.mobiledgex.matchingengine.edgeeventhandlers.EdgeEventsIntervalHandler;
import com.mobiledgex.matchingengine.edgeeventhandlers.EdgeEventsLatencyIntervalHandler;
import com.mobiledgex.matchingengine.edgeeventhandlers.EdgeEventsLocationIntervalHandler;
import com.mobiledgex.matchingengine.edgeeventsconfig.ClientEventsConfig;
import com.mobiledgex.matchingengine.edgeeventsconfig.EdgeEventsConfig;
import com.mobiledgex.matchingengine.edgeeventsconfig.FindCloudletEvent;
import com.mobiledgex.matchingengine.edgeeventsconfig.FindCloudletEventTrigger;
import com.mobiledgex.matchingengine.performancemetrics.NetTest;
import com.mobiledgex.matchingengine.performancemetrics.Site;
import com.mobiledgex.matchingengine.util.MeLocation;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
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

    public MatchEngineApiGrpc.MatchEngineApiStub asyncStub;

    StreamObserver<AppClient.ClientEdgeEvent> sender;
    StreamObserver<AppClient.ServerEdgeEvent> receiver;

    enum ChannelStatus {
        open,
        opening,
        reconnecting,
        closing,
        closed
    }
    ChannelStatus channelStatus = ChannelStatus.closed;
    Object openAwaiter = new Object();

    String hostOverride;
    int portOverride;
    Network networkOverride;

    private Location mLastLocationPosted = null;

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
                } else if (serverEdgeEvent.getEventType() == ServerEventType.EVENT_CLOUDLET_UPDATE) {
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
        missingEdgeEventsConfig,
        missingNewFindCloudletHandler,
        missingServerEventsHandler,
        missingUpdateInterval,
        invalidUpdateInterval,
        hasNotDoneFindCloudlet,
        emptyAppPorts,
        portDoesNotExist,
        uninitializedEdgeEventsConnection,
        failedToClose,
        edgeEventsConnectionError,
        missingDmeDnsEntry,
        eventTriggeredButCurrentCloudletIsBest
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
        synchronized (this) {
            hostOverride = null;
            portOverride = 0;
            networkOverride = null;
        }
        this.me = me;

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
                openAwaiter.wait(10 * 1000);
            } catch (InterruptedException e) {
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
        this.mLastLocationPosted = mLastLocationPosted;
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
        me.getEdgeEventsBus().post(error);
    }

    /*!
     * Reconnects the EdgeEventsConnection with current settings. This will block until opened.
     */
    synchronized public void reconnect() throws DmeDnsException {
        // using existing config;
        reconnect(me.generateDmeHostAddress(), me.getPort(), null, mEdgeEventsConfig);
        hostOverride = null;
        portOverride = 0;
        networkOverride = null;
    }

    synchronized public void reconnect(String host, int port, Network network, EdgeEventsConfig eeConfig) throws DmeDnsException {
        if (me.isShutdown()) {
            return;
        }
        hostOverride = host;
        portOverride = port;
        networkOverride = network;
        mEdgeEventsConfig = eeConfig;
        Log.d(TAG, "Reconnecting...");
        channelStatus = ChannelStatus.reconnecting;
        stopEdgeEvents();
        closeInternal();

        if (hostOverride != null && portOverride > 0) {
            try {
                open(hostOverride, portOverride, networkOverride, mEdgeEventsConfig);
            } catch (DmeDnsException dde){
                Log.e(TAG, "Host : [" + hostOverride + "] does not appear to exist for reconnect(). Please check the DME host used.");
                postErrorToEventHandler(EdgeEventsError.missingDmeDnsEntry);
                throw dde;
            }
        } else {
            try {
                open();
            } catch (DmeDnsException dde) {
                Log.e(TAG, "Automatically generated DME Address failed to open for reconnect(). Check with MatchingEngine generateDmeHostAddress() to find current one.");
                postErrorToEventHandler(EdgeEventsError.missingDmeDnsEntry);
                 throw dde;
            }
        }

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
     * Use this to open a DME connection to nearest DME.
     */
    synchronized void open() throws DmeDnsException {
        // GenerateDmeHostAddress might actually hit a UI thread exception.
        try {
            open(me.generateDmeHostAddress(), me.getPort(), null, mEdgeEventsConfig);
        } catch (NetworkOnMainThreadException nomte) {
            Log.e(TAG, "Consider running this call from a background thread.");
        }
        finally {
            hostOverride = null;
            portOverride = 0;
            networkOverride = null;
        }
    }

    synchronized void open(String host, int port, Network network, EdgeEventsConfig eeConfig) throws DmeDnsException {
        hostOverride = host;
        portOverride = port;
        networkOverride = network;
        eventBusRegister();
        if (channelStatus != ChannelStatus.reconnecting) {
            channelStatus = ChannelStatus.opening;
        }

        // If there's a no EventBus handler (Deadhandler is called when there's no subscribers)
        // the default handler takes over for future messages, and puts warnings into the logs via
        // DeadEventsHandler until implemented. The default handler, is a public method to document,
        // open source and customize more closely to the application's needs.

        if (eeConfig == null) {
            mEdgeEventsConfig = new EdgeEventsConfig(EdgeEventsConfig.createDefaultEdgeEventsConfig());
        } else {
            mEdgeEventsConfig = new EdgeEventsConfig(eeConfig);
        }

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

        try {
            InetAddress.getAllByName(host);
        } catch (UnknownHostException uhe) {
            throw new DmeDnsException(("Could not find mcc-mnc.dme.mobiledgex.net DME server: " + host), uhe);
        }

        this.channel = me.channelPicker(host, port, network);
        this.asyncStub = MatchEngineApiGrpc.newStub(channel);

        receiver = new StreamObserver<AppClient.ServerEdgeEvent>() {
            @Override
            public void onNext(AppClient.ServerEdgeEvent value) {
                // If nothing is subscribed, it just goes to deadEvent.
                // Raw Events.
                if (me.getEdgeEventsBus() != null) {
                    me.getEdgeEventsBus().post(value);
                }

                if (value.getEventType() == ServerEventType.EVENT_INIT_CONNECTION) {
                    channelStatus = ChannelStatus.open;
                    notifyOpenAwaiter();
                    runEdgeEvents();
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
                // Shutdown invocation will also land here.
                Log.w(TAG, "Encountered error in DMEConnection: ", t);
                if (me.getEdgeEventsBus() != null) {
                    me.getEdgeEventsBus().post(EdgeEventsError.edgeEventsConnectionError);
                }
                // Reopen DME connection.
                try {
                    closeInternal();
                    if (channelStatus != ChannelStatus.closing && channelStatus != ChannelStatus.closed) {
                        reconnect();
                    }
                } catch (Exception e) {
                    Log.e("Message", "DME Connection closed. A new client initiated FindCloudlet required for edge events stream.");
                }
            }

            @Override
            public void onCompleted() {
                Log.i(TAG, "Stream closed.");

                // Reopen DME connection.
                try {
                    closeInternal();
                    if (channelStatus != ChannelStatus.closing && channelStatus != ChannelStatus.closed) {
                        reconnect();
                    }
                } catch (Exception e) {
                    Log.e("Message", "DME Connection closed. A new client initiated FindCloudlet required for edge events stream.");
                }
            }
        };

        // No deadline, since it's streaming:
        sender = asyncStub.streamEdgeEvent(receiver);

        // Client identifies itself with an Init message to DME EdgeEvents Connection upon opening the connection.
        AppClient.ClientEdgeEvent initDmeEvent = AppClient.ClientEdgeEvent.newBuilder()
                .setEventType(AppClient.ClientEdgeEvent.ClientEventType.EVENT_INIT_CONNECTION)
                .setSessionCookie(me.mSessionCookie)
                .setEdgeEventsCookie(me.mFindCloudletReply.getEdgeEventsCookie())
                .build();
        send(initDmeEvent);
    }

    /*!
     * Peer connection clear from onComplete, onError.
     */
    synchronized void closeInternal() {
        Log.d(TAG, "stream closing...");
        channelStatus = ChannelStatus.closing;
        sender = null;
        receiver = null;

        hostOverride = null;
        portOverride = 0;
        networkOverride = null;

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
    synchronized void close() {
        synchronized (openAwaiter) {
            openAwaiter.notifyAll();
            openAwaiter = null;
        }
        if (!isShutdown()) {
            Log.d(TAG, "closing...");
            eventBusUnRegister();
            closeInternal();
        }
    }

    synchronized public boolean isShutdown() {
        if (channel == null) {
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
                sender.onNext(clientEdgeEvent);
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

    synchronized boolean sendTerminate() {
        if (isShutdown()) {
            return false;
        }

        AppClient.ClientEdgeEvent clientEdgeEvent = AppClient.ClientEdgeEvent.newBuilder()
                .setEventType(AppClient.ClientEdgeEvent.ClientEventType.EVENT_TERMINATE_CONNECTION)
                .build();

        if (sender != null) {
            sender.onNext(clientEdgeEvent);
        }

        try {
            channel.shutdown();
            Log.d(TAG, "Awaiting termination...");
            channel.awaitTermination(5, TimeUnit.SECONDS);
            Log.d(TAG, "Done awaiting.");
            receiver = null;
            sender = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
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
        if (me.isShutdown()) {
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
        Appcommon.DeviceInfo deviceInfo = me.getDeviceInfoProto();
        if (deviceInfo != null) {
            clientEdgeEventBuilder.mergeDeviceInfo(deviceInfo);
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
        if (me.isShutdown()) {
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
        Appcommon.DeviceInfo deviceInfo = me.getDeviceInfoProto();
        if (deviceInfo != null) {
            clientEdgeEventBuilder.mergeDeviceInfo(deviceInfo);
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
        if (me.isShutdown()) {
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
        Appcommon.DeviceInfo deviceInfo = me.getDeviceInfoProto();
        if (deviceInfo != null) {
            clientEdgeEventBuilder.mergeDeviceInfo(deviceInfo);
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
            if (!doClientFindCloudlet(FindCloudletEventTrigger.LatencyTooHigh)) {
                postErrorToEventHandler(EdgeEventsError.eventTriggeredButCurrentCloudletIsBest);
            };
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
        if (me.isShutdown()) {
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
            if (!doClientFindCloudlet(FindCloudletEventTrigger.LatencyTooHigh)) {
                postErrorToEventHandler(EdgeEventsError.eventTriggeredButCurrentCloudletIsBest);
            }
        }

        for (int i = 0; i < site.samples.length; i++) {
            LocOuterClass.Sample.Builder sampleBuilder = LocOuterClass.Sample.newBuilder()
                    //.setLoc(loc) Location is not synchronous with measurement.
                    // Samples are not timestamped.
                    .setValue(site.samples[i]);
            clientEdgeEventBuilder.addSamples(sampleBuilder.build());
        }
        Appcommon.DeviceInfo deviceInfo = me.getDeviceInfoProto();
        if (deviceInfo != null) {
            clientEdgeEventBuilder.mergeDeviceInfo(deviceInfo);
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
            if (me.isShutdown()) {
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
            ClientEventsConfig locationUpdateConfig = mEdgeEventsConfig.locationUpdateConfig;

            switch (locationUpdateConfig.updatePattern) {
                case onStart:
                    postLocationUpdate(getLastLocationPosted());
                    break;
                case onTrigger:
                    eventBusRegister(); // Attach Subscriber for triggers.
                    break;
                case onInterval:
                    eventBusRegister(); // Attach Subscriber, to handle triggers and montoriing by interval.
                    // Start, and add to a list of known EdgeEvent Testing Handlers
                    addEdgeEventsIntervalTask(new EdgeEventsLocationIntervalHandler(me, locationUpdateConfig));
                    break;
            }
        }
    }

    synchronized private void runLatencyMonitorConfig() {
        if (mEdgeEventsConfig.latencyUpdateConfig != null) {
            ClientEventsConfig latencyUpdateConfig = mEdgeEventsConfig.latencyUpdateConfig;

            switch (latencyUpdateConfig.updatePattern) {
                case onStart:
                    if (mEdgeEventsConfig.latencyInternalPort <= 0) {
                        testPingAndPostLatencyUpdate(getLastLocationPosted());
                    } else {
                        testConnectAndPostLatencyUpdate(getLastLocationPosted());
                    }
                    break;
                case onTrigger:
                    eventBusRegister(); // Attach Subscriber for triggers.
                    break;
                case onInterval:
                    // Last FindCloudlet
                    eventBusRegister(); // Attach Subscriber, to handle triggers and monitoring by interval.
                    // Add to a list of known EdgeEvent Testing Handlers
                    addEdgeEventsIntervalTask(new EdgeEventsLatencyIntervalHandler(me, mEdgeEventsConfig.latencyTestType, latencyUpdateConfig));
                    break;
            }
        }
    }

    synchronized public void stopEdgeEvents() {
        for (EdgeEventsIntervalHandler eh : edgeEventsIntervalHandlers) {
            removeEdgeEventsIntervalTask(eh);
        }
        closeInternal();
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


    // Non raw ServerEdgeEvent.
    synchronized boolean doClientFindCloudlet(FindCloudletEventTrigger reason) {
        if (isShutdown()) {
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
        loc = getLastLocationPosted();
        if (loc == null) {
            loc = getLocation();
            if (loc == null) {
                return false;
            }
        }

        AppClient.FindCloudletRequest request = me.createDefaultFindCloudletRequest(me.mContext, loc)
                .build();

        try {
            AppClient.FindCloudletReply reply = me.findCloudlet(
                    request,
                    me.getNetworkManager().getTimeout(),
                    MatchingEngine.FindCloudletMode.PERFORMANCE);
            if (reply != null) {
                FindCloudletEvent event = new FindCloudletEvent(reply, reason);

                // TODO: Check if this is a new FindCloudlet before posting.
                postToFindCloudletEventHandler(event);
                if (me.isAutoMigrateEdgeEventsConnection()) {
                    reconnect();
                }
                return true;
            }
            // Caller decides what happens on failure.

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

    synchronized boolean handleFindCloudletServerPush(AppClient.ServerEdgeEvent event, FindCloudletEventTrigger reason) {
        if (event.getEventType() != ServerEventType.EVENT_CLOUDLET_UPDATE) {
            return false;
        }

        Log.i(TAG, "Received a new Edge FindCloudlet. Pushing to new FindCloudlet subscribers.");
        if (event.hasNewCloudlet()) {
            FindCloudletEvent fce = new FindCloudletEvent(
                    event.getNewCloudlet(),
                    reason);

            // Update MatchingEngine.
            me.setFindCloudletResponse(event.getNewCloudlet());

            // Post to new FindCloudletEvent Handler subscribers, if any, on the same EventBus:
            postToFindCloudletEventHandler(fce);

            // Policy: Migrate to new DME connection?
            if (!me.isAutoMigrateEdgeEventsConnection()) {
                Log.w(TAG, "autoMigrateEdgeEventsConnection is set to false. When app has migrated to new cloudlet, call MatchingEngine's switchedToNewCloudlet().");
            }
            else {
                try {
                    channelStatus = ChannelStatus.reconnecting;
                    closeInternal();
                    reconnect();
                } catch (DmeDnsException dde) {
                    postErrorToEventHandler(EdgeEventsError.missingDmeDnsEntry);
                    return false; // Cannot reconnect.
                }
            }
        } else {
            Log.e(TAG, "Error: Server is missing a findClooudlet!");
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
                Log.i(TAG, "AppInst Health event. Doing FindCloudlet. Reason: " + event.getHealthCheck());
                if (!doClientFindCloudlet(FindCloudletEventTrigger.AppInstHealthChanged)) {
                    postErrorToEventHandler(EdgeEventsError.eventTriggeredButCurrentCloudletIsBest);
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
                Log.i(TAG,"Maintenance state changed! Finding new Cloudlet. Reason: " + event.getMaintenanceState());
                if (!doClientFindCloudlet(FindCloudletEventTrigger.CloudletMaintenanceStateChanged)) {
                    postErrorToEventHandler(EdgeEventsError.eventTriggeredButCurrentCloudletIsBest);
                }
                break;
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
                Log.i(TAG,"Cloudlet State Change. Doing findCloudlet. Reason: " + event.getCloudletState());
                doClientFindCloudlet(FindCloudletEventTrigger.CloudletStateChanged);
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
                        me.getEdgeEventsConnection().testPingAndPostLatencyUpdate(getLastLocationPosted());
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
                    String host = appPort.getFqdnPrefix() + me.mFindCloudletReply.getFqdn();

                    Site site = new Site(me.mContext, NetTest.TestType.CONNECT, DEFAULT_NUM_SAMPLES, host, publicPort);
                    netTest.addSite(site);
                    // Blocks.
                    netTest.testSites(netTest.TestTimeoutMS); // Test the one we just added.

                    // Trigger(s):
                    if (site.average >= mEdgeEventsConfig.latencyThresholdTrigger) {
                        Log.i(TAG, "Latency higher than requested");
                        if (!doClientFindCloudlet(FindCloudletEventTrigger.LatencyTooHigh)) {
                            postErrorToEventHandler(EdgeEventsError.eventTriggeredButCurrentCloudletIsBest);
                        };
                    }

                    return me.getEdgeEventsConnection().postLatencyUpdate(netTest.getSite(host), getLastLocationPosted());
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
