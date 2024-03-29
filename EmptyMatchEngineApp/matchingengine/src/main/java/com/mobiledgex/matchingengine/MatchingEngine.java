/**
 * Copyright 2018-2021 MobiledgeX, Inc. All rights and licenses reserved.
 * MobiledgeX, Inc. 156 2nd Street #408, San Francisco, CA 94105
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mobiledgex.matchingengine;

import android.Manifest;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Looper;
import android.provider.Settings;

import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;

import android.telephony.CarrierConfigManager;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import distributed_match_engine.AppClient;
import distributed_match_engine.AppClient.RegisterClientRequest;
import distributed_match_engine.AppClient.RegisterClientReply;
import distributed_match_engine.AppClient.VerifyLocationRequest;
import distributed_match_engine.AppClient.VerifyLocationReply;
import distributed_match_engine.AppClient.FindCloudletRequest;
import distributed_match_engine.AppClient.FindCloudletReply;
import distributed_match_engine.AppClient.GetLocationRequest;
import distributed_match_engine.AppClient.GetLocationReply;
import distributed_match_engine.AppClient.AppInstListRequest;
import distributed_match_engine.AppClient.AppInstListReply;
import distributed_match_engine.AppClient.QosPositionRequest;
import distributed_match_engine.AppClient.QosPositionKpiReply;
import distributed_match_engine.AppClient.QosPosition;
import distributed_match_engine.AppClient.QosPrioritySessionCreateRequest;
import distributed_match_engine.AppClient.QosPrioritySessionReply;
import distributed_match_engine.AppClient.QosPrioritySessionDeleteRequest;
import distributed_match_engine.AppClient.QosPrioritySessionDeleteReply;
import distributed_match_engine.AppClient.BandSelection;

import distributed_match_engine.AppClient.DynamicLocGroupRequest;
import distributed_match_engine.AppClient.DynamicLocGroupReply;

import distributed_match_engine.Appcommon;
import distributed_match_engine.LocOuterClass;
import distributed_match_engine.LocOuterClass.Loc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.okhttp.OkHttpChannelBuilder;

import android.content.pm.PackageInfo;
import android.util.Log;

import com.google.common.eventbus.AsyncEventBus;
import com.google.common.eventbus.EventBus;
import com.mobiledgex.matchingengine.edgeeventsconfig.EdgeEventsConfig;
import com.mobiledgex.matchingengine.performancemetrics.NetTest;

import static android.content.Context.TELEPHONY_SUBSCRIPTION_SERVICE;
import static android.content.Context.WIFI_SERVICE;

/*!
 * Main MobiledgeX SDK class. This class provides functions to find nearest cloudlet with the
 * developer's application instance deployed and to connect to that application instance.
 * \ingroup classes
 */
public class MatchingEngine {
    public static final String TAG = "MatchingEngine";
    public static final String baseDmeHost = "dme.mobiledgex.net";
    public static final String WIFIHOST = "wifi";
    public static final String wifiOnlyDmeHost = WIFIHOST + "." + baseDmeHost; // Demo mode only.
    private String host = baseDmeHost;
    private NetworkManager mNetworkManager;
    private AppConnectionManager mAppConnectionManager;
    private int port = 50051;

    // A threadpool for all the MatchEngine API callable interfaces:
    final ExecutorService threadpool;
    private boolean externalExecutor = false;

    // State info for engine
    String mSessionCookie; // TODO: Session Map lookup for multiple Edge Apps.
    private String mTokenServerURI;
    private String mTokenServerToken;

    RegisterClientRequest mRegisterClientRequest;
    private RegisterClientReply mRegisterClientReply;
    private VerifyLocationReply mVerifyLocationReply;
    private GetLocationReply mGetLocationReply;
    private DynamicLocGroupReply mDynamicLocGroupReply;

    private LocOuterClass.Loc mMatchEngineLocation;

    private boolean isSSLEnabled = true;
    private boolean useOnlyWifi = false;

    private AtomicBoolean shutdown = new AtomicBoolean();

    public boolean isShutdown() {
        return shutdown.get();
    }

    /*!
     * Two modes to call FindCloudlet. First is Proximity (default) which finds the nearest cloudlet based on gps location with application instance
     * Second is Performance. This mode will test all cloudlets with application instance deployed to find cloudlet with lowest latency. This mode takes longer to finish because of latency test.
     */
    public enum FindCloudletMode {
        PROXIMITY,
        PERFORMANCE
    }

    Context mContext;
    private NetTest mNetTest;
    private boolean threadedPerformanceTest = false;


    private EdgeEventsConnection mEdgeEventsConnection;
    private EventBus mEdgeEventBus;
    boolean mEnableEdgeEvents = true;
    EdgeEventsConfig mEdgeEventsConfig = null; // Developer's copy.
    boolean mAppInitiatedRunEdgeEvents = false;

    /*!
     * Constructor for MatchingEngine class.
     * \param context (android.content.Context)
     * \section meconstructorexample Example
     * \snippet EngineCallTest.java meconstructorexample
     */
    public MatchingEngine(Context context) {
        threadpool = Executors.newCachedThreadPool();
        ConnectivityManager connectivityManager = context.getSystemService(ConnectivityManager.class);
        mNetworkManager = NetworkManager.getInstance(connectivityManager, getSubscriptionManager(context));
        mAppConnectionManager = new AppConnectionManager(mNetworkManager, threadpool);
        mContext = context;
        mNetTest = new NetTest();
        mEdgeEventBus = new AsyncEventBus(threadpool);
        mEdgeEventsConnection = new EdgeEventsConnection(this, null);
    }

    /*!
     * Constructor for MatchingEngine class.
     * \param context (android.content.Context)
     * \param executorService (java.util.concurrent.ExecutorService)
     */
    public MatchingEngine(Context context, ExecutorService executorService) {
        threadpool = executorService;
        externalExecutor = true;
        ConnectivityManager connectivityManager = context.getSystemService(ConnectivityManager.class);
        mNetworkManager = NetworkManager.getInstance(connectivityManager, getSubscriptionManager(context), threadpool);
        mAppConnectionManager = new AppConnectionManager(mNetworkManager, threadpool);
        mContext = context;
        mNetTest = new NetTest();
        mEdgeEventBus = new AsyncEventBus(executorService);
        mEdgeEventsConnection = new EdgeEventsConnection(this, null);
    }

    public boolean warnIfUIThread() {
        if (Looper.getMainLooper().isCurrentThread()) {
            Log.w(TAG, "The call is running a network activity on the UI Thread. Consider using a CompletableFuture.");
            return true;
        }
        return false;
    }

    public boolean isEnableEdgeEvents() {
        return mEnableEdgeEvents;
    }

    /*!
     * Enable or disable edge events API.
     * \param enableEdgeEvents this defaults to true in the MatchingEngine.
     * \ingroup functions_edge_events_api
     * \section enable_edgeevents Example
     * \snippet MainActivity.java enable_edgeevents
     */
    synchronized public void setEnableEdgeEvents(boolean enableEdgeEvents) {
        this.mEnableEdgeEvents = enableEdgeEvents;
    }

    synchronized public void setEdgeEventsConfig(EdgeEventsConfig edgeEventsConfig) {
        this.mEdgeEventsConfig = edgeEventsConfig;
    }

    private boolean autoMigrateEdgeEventsConnection = true;
    /*!
     * Automatically switched EdgeEventsConnection
     * \return boolean value whether the EdgeEventsConnection is migrated automatically.
     * \return
     * \ingroup functions_edge_events_api
     */
    public boolean isAutoMigrateEdgeEventsConnection() {
        return autoMigrateEdgeEventsConnection;
    }
    /*!
     * When you switch AppInsts between Cloudlets, the EdgeEventsConnection should also migrate.
     * If set to false, when notified of a newCLoudlet availability, call "switchedToNewFindCloudlet()
     * to indicate the app has finally migrated to the new cloudlet.
     * \param autoMigrateEdgeEventsConnection
     * \ingroup functions_edge_events_api
     */
    synchronized public void setAutoMigrateEdgeEventsConnection(boolean autoMigrateEdgeEventsConnection) {
        this.autoMigrateEdgeEventsConnection = autoMigrateEdgeEventsConnection;
    }

    /*!
     * Helper util to create a useful config.
     */
    public EdgeEventsConfig createDefaultEdgeEventsConfig() {
        return EdgeEventsConfig.createDefaultEdgeEventsConfig();
    }
    /*!
     * Helper util to create a useful config.
     * \param latencyUpdateIntervalSeconds how often the edgeEvents tests latency to configured server.
     * \param locationUpdateIntervalSeconds how often edgeEvents will send GPS to the server. Set the location, or enable location permissions in MatchingEngine to get the location to send.
     * \param latencyThresholdTriggerMs sets the upper bound of acceptable latency, and then informs the app.
     * \param internalPort this is the internal port for your application.
     */
    public EdgeEventsConfig createDefaultEdgeEventsConfig(double latencyUpdateIntervalSeconds,
                                                          double locationUpdateIntervalSeconds,
                                                          double latencyThresholdTriggerMs, int internalPort) {
        return EdgeEventsConfig.createDefaultEdgeEventsConfig(latencyUpdateIntervalSeconds, locationUpdateIntervalSeconds, latencyThresholdTriggerMs, internalPort);
    }

    /*!
     * CompletableFuture wrapper for startEdgeEvents running on MatchingEngine ExecutorService pool.
     * \param edgeEventsConfig the configuration for background run EdgeEvents.
     * \ingroup functions_edge_events_api
     * \section startedgeevents_example Example
     */
    synchronized public CompletableFuture<Boolean> startEdgeEventsFuture(EdgeEventsConfig edgeEventsConfig) {
        final EdgeEventsConfig config = edgeEventsConfig;
        return CompletableFuture.supplyAsync(new Supplier<Boolean>() {
            @Override
            public Boolean get() {
                return startEdgeEvents(config);
            }
        }, threadpool);
    }

    /*!
     * startEdgeEvents() begins processing as soon as a FindCloudletReply is FIND_FOUND with the
     * EdgeEventsConfig given.
     *
     * If you want to handle the EdgeEvents with a custom handler, call getEdgeEventsBus(),
     * register your class, and @Subscribe to either these event objects:
     *
     *   - FindCloudletEvent - A new cloudlet found and is available for your app to migrate to when ready.
     *   - ServerEdgeEvent - Optional. All raw events. Accepting raw events will disable the default EdgeEvents handler for custom app logic.
     *
     *   The errors on the EdgeEventsBus is useful for the app to attach a handler
     *   - EdgeEventsError - Errors encountered during the EdgeEvents threads will be posted here for the app to handle.
     *
     * \param edgeEventsConfig a events profile on how to monitor the edgeConnection state. null to use defaults.
     * \ingroup functions_edge_events_api
     * \section startedgeevents_example Example
     * \snippet MainActivity.java startedgeevents_example
     */
    synchronized public boolean startEdgeEvents(EdgeEventsConfig edgeEventsConfig) {
        warnIfUIThread();
        if (edgeEventsConfig == null) {
            Log.e(TAG, "EdgeEventsConfig should not be null!");
            return false;
        }
        if (isShutdown()) {
            return false;
        }

        mAppInitiatedRunEdgeEvents = true;
        try {
            return startEdgeEvents(null, 0, null, edgeEventsConfig);
        } catch (DmeDnsException dde) {
            Log.e(TAG, "Failed to start DME EdgeEvents Connection, could not create DME hostname. The configuration given will be used for next attempt.");
            return false;
        }
    }

    synchronized boolean startEdgeEventsInternal(String host, int port, Network network, EdgeEventsConfig edgeEventsConfig) {
        if (edgeEventsConfig == null) {
            Log.e(TAG, "No config for EdgeEvents to use. Creating a do nothing config for event monitoring only.");
            edgeEventsConfig = createDefaultEdgeEventsConfig();
            edgeEventsConfig.latencyUpdateConfig = null;
            edgeEventsConfig.locationUpdateConfig = null;
        }
        if (isShutdown()) {
            return false;
        }

        if (mEdgeEventsConnection.channelStatus != EdgeEventsConnection.ChannelStatus.closed) {
            stopEdgeEvents();
            mEdgeEventsConnection.closeInternal(); // Restartable close.
        }
        try {
            return startEdgeEvents(host, port, network, edgeEventsConfig);
        } catch (DmeDnsException dde) {
            Log.e(TAG, "Failed to start DME EdgeEvents Connection, could not create DME hostname. The configuration given will be used for next attempt.");
            return false;
        }
    }

    synchronized boolean startEdgeEvents(String dmeHost,
                                         int dmePort,
                                         Network network,
                                         EdgeEventsConfig edgeEventsConfig) throws DmeDnsException {
        warnIfUIThread();
        if (!mEnableEdgeEvents) {
            Log.w(TAG, "EdgeEvents has been disabled.");
            return false;
        }

        if (edgeEventsConfig == null && !mAppInitiatedRunEdgeEvents) {
            Log.w(TAG, "Cannot start edgeEvents without a configuration. Doing nothing.");
            return false;
        } else {
            if (edgeEventsConfig != null) {
                mEdgeEventsConfig = new EdgeEventsConfig(edgeEventsConfig);
            }
            else {
                mEdgeEventsConfig = createDefaultEdgeEventsConfig();
                mEdgeEventsConfig.locationUpdateConfig = null;
                mEdgeEventsConfig.latencyUpdateConfig = null;
            }
        }

        // This is an exposed path to start/restart EdgeEvents, state check everything.
        if (!validateEdgeEventsConfig()) {
            Log.e(TAG, "startEdgeEvents EdgeEvents Configuration for starting does not look correct: " + mEdgeEventsConfig);
            return false; // NOT started.
        }
        Log.i(TAG, "startEdgeEvents has been started with this edgeEventsConfig parameter: " + mEdgeEventsConfig);

        // Start, if not already, the edgeEvents connection. It also starts any deferred events.
        // Reconnecting via FindCloudlet, will also call startEdgeEvents.
        if (mEdgeEventsConnection.isShutdown()) {
            mEdgeEventsConnection.open(dmeHost, dmePort, network, edgeEventsConfig);
            Log.i(TAG, "EdgeEventsConnection is now started with: " + edgeEventsConfig);
        } else {
            Log.i(TAG, "EdgeEventsConnection will be restarted with: " + edgeEventsConfig);
            mEdgeEventsConnection.reconnect(dmeHost, dmePort, network, edgeEventsConfig);
        }
        return true;
    }

    /*!
     * CompletableFuture wrapper for restartEdgeEvents running on MatchingEngine ExecutorService pool.
     * The background task might throw a DmeDnsException exception, and return false.
     *
     * \param edgeEventsConfig the configuration for background run EdgeEvents.
     * \ingroup functions_edge_events_api
     * \section startedgeevents_example Example
     */
    public CompletableFuture<Boolean> restartEdgeEventsFuture() {
        return CompletableFuture.supplyAsync(new Supplier<Boolean>() {
            @Override
            public Boolean get() {
                try {
                    return restartEdgeEvents();
                } catch (DmeDnsException e) {
                    Log.e(TAG, "DME Host exception. Message: " + e.getLocalizedMessage());
                    throw new CompletionException(e);
                }
            }
        }, threadpool);
    }

    /*!
     * This is required, if the app needs to swap AppInst edge servers, and auto reconnect to the
     * next DME's EdgeEventsConnection is disabled.
     *
     * \throws DmeDnsException if the next DME for the EdgeEventsConnection for some reason doesn't exist in DNS yet.
     * \ingroup functions_edge_events_api
     */
    synchronized public boolean restartEdgeEvents() throws DmeDnsException {
        try {
            warnIfUIThread();
            if (isShutdown()) {
                return false;
            }

            if (mEdgeEventsConnection.isShutdown()) {
                mEdgeEventsConnection.open();
            } else {
                mEdgeEventsConnection.reconnect(mEdgeEventsConfig);
            }
            return true;
        } catch (DmeDnsException dde) {
            Log.e(TAG, "Background restart failed. Check DME DNS mapping." + dde.getMessage());
            throw dde;
        }
    }

    /*!
     * Just an alias to restartEdgeEvents. Blocking call.
     * \throws DmeDnsException if the next DME for the EdgeEventsConnection for some reason doesn't exist in DNS yet.
     * \ingroup functions_edge_events_api
     */
    synchronized public boolean switchedToNextCloudlet() throws DmeDnsException {
        return restartEdgeEvents();
    }

    /*!
     * Stops processsing of DME server pushed EdgeEvents. Futures version.
     * \return A future for stopEdgeEvents running on MatchingEngine's ExecutorService pool.
     * \ingroup functions_edge_events_api
     */
    public CompletableFuture<Boolean> stopEdgeEventsFuture() {
        return CompletableFuture.supplyAsync(new Supplier<Boolean>() {
            @Override
            public Boolean get() {
                return stopEdgeEvents();
            }
        }, threadpool);
    }

    /*!
     * Stops processsing of DME server pushed EdgeEvents.
     * \ingroup functions_edge_events_api
     */
    synchronized public boolean stopEdgeEvents() {
        warnIfUIThread();
        mAppInitiatedRunEdgeEvents = false;
        mEdgeEventsConnection.stopEdgeEvents();
        return true;
    }

    /**
     * validate prior to creating a EdgeEventsConnection outside FindCloudlet auto creation.
     * @return
     */
    private boolean validateEdgeEventsConfig() {
        if (!mEnableEdgeEvents) {
            Log.w(TAG, "EdgeEvents is set to disabled.");
            return false;
        }

        if (!MatchingEngine.isMatchingEngineLocationAllowed()) {
            Log.w(TAG, "MobiledgeX Location services are disabled. Reduced functionality. EdgeEvents can only receive server push events.");
        }

        if (mEdgeEventsConfig == null) {
            Log.w(TAG, "No configuration to run edgeEvents");
            return false;
        }
        return true;
    }



    /*!
     * Returns an EdgeEventsConnection singleton to call edge events util functions.
     *
     * \return a EdgeEventsConnection instance. May be null if not currently available.
     * \ingroup functions_dmeapis
     */
    synchronized public CompletableFuture<EdgeEventsConnection> getEdgeEventsConnectionFuture() {
        return CompletableFuture.supplyAsync(new Supplier<EdgeEventsConnection>() {
            @Override
            public EdgeEventsConnection get() {
                return getEdgeEventsConnection();
            }
        }, threadpool);
    }

    synchronized public EdgeEventsConnection getEdgeEventsConnection() {
        warnIfUIThread();
        if (isShutdown()) {
            Log.e(TAG, "MatchingEngine has been closed.");
            return null;
        }
        if (mEnableEdgeEvents == false)
        {
            Log.w(TAG, "EdgeEvents has been disabled.");
            return null;
        }

        return mEdgeEventsConnection;
    }

    /*!
     * MatchingEngine contains some long lived resources. When done, call close() to free them
     * cleanly.
     * \ingroup functions_dmeapis
     */
    synchronized public void close() {
        if (isShutdown()) {
            return;
        }
        Log.d(TAG, "MatchingEngine closing.");
        stopEdgeEvents();
        if (mEdgeEventsConnection != null) {
            mEdgeEventsConnection.close();
        }
        mEdgeEventsConnection = null;
        mEdgeEventBus = null;

        // Kill ExecutorService.
        if (!externalExecutor && threadpool != null) {
            threadpool.shutdown();
        }

        mSessionCookie = null;
        mTokenServerToken = null;
        mTokenServerURI = null;
        mRegisterClientReply = null;
        mContext = null;
        mNetworkManager = null;
        mAppConnectionManager = null;
        shutdown.set(true);
        Log.d(TAG, "MatchingEngine closed.");
    }

    /*!
     * This is an event bus for EdgeEvents.
     * You can specify your own ExecutorService with MatchingEgnine init.
     *
     * If you want to send a response back to the server, call getEdgeEventsConnection()
     * to access Utility functions to help with the response.
     *
     * \return The EdgeEvents bus, if any. This may be null.
     * \ingroup functions_edge_events_api
     */
    public EventBus getEdgeEventsBus() {
        // This lives outside the EdgeEventsConnect, so the eventBus can be set.
        return mEdgeEventBus;
    }

    // Application state Bundle Key.
    public static final String MATCHING_ENGINE_LOCATION_PERMISSION = "MATCHING_ENGINE_LOCATION_PERMISSION";


    static boolean mMatchingEngineLocationAllowed = false;
    /*!
     * Checks if MatchingEngineLocation is allowed
     * \return boolean
     * \ingroup functions_dmeutils
     */
    public static boolean isMatchingEngineLocationAllowed() {
        return mMatchingEngineLocationAllowed;
    }

    /*!
     * Location permissions are required to find nearest cloudlet. Developer must set MatchignEngineLocationAllowed to true in order to use MatchingEngine APIs
     * \param allowMatchingEngineLocation (boolean)
     * \ingroup functions_dmeutils
     * \section matchingengine_allow_location_usage_gdpr Example
     * \snippet MainActivity.java matchingengine_allow_location_usage_gdpr
     */
    synchronized public static void setMatchingEngineLocationAllowed(boolean allowMatchingEngineLocation) {
        mMatchingEngineLocationAllowed = allowMatchingEngineLocation;
    }

    /*!
     * Checks if WifiOnly is set
     * \return boolean
     * \ingroup functions_dmeutils
     */
    public boolean isUseWifiOnly() {
        return useOnlyWifi;
    }

    /*!
     * Sets WifiOnly.
     * If true, MatchingEngine APIs will communicate with the nearest DME based on NS1 ip lookup.
     * If false, MatchingEngine APIs will communicate with the DME based on the device's carrier information.
     * Default is false.
     * Only set to true for testing purposes. Production code should use false.
     * \param enabled (boolean)
     * \ingroup functions_dmeutils
     */
    synchronized public void setUseWifiOnly(boolean enabled) {
        useOnlyWifi = enabled;
    }

    private SubscriptionManager getSubscriptionManager(Context context) {
        return (SubscriptionManager) context.getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE);
    }

    /*!
     * \ingroup functions_dmeutils
     */
    public boolean isNetworkSwitchingEnabled() {
        return getNetworkManager().isNetworkSwitchingEnabled();
    }

    /*!
     * \ingroup functions_dmeutils
     */
    public void setNetworkSwitchingEnabled(boolean networkSwitchingEnabled) {
        getNetworkManager().setNetworkSwitchingEnabled(networkSwitchingEnabled);
    }

    /*!
     * \ingroup functions_dmeutils
     */
    public boolean isAllowSwitchIfNoSubscriberInfo() {
        return getNetworkManager().isAllowSwitchIfNoSubscriberInfo();
    }

    /*!
     * \ingroup functions_dmeutils
     */
    synchronized public void setAllowSwitchIfNoSubscriberInfo(boolean allowSwitchIfNoSubscriberInfo) {
        getNetworkManager().setAllowSwitchIfNoSubscriberInfo(allowSwitchIfNoSubscriberInfo);
    }

    /*!
     * Utility function to get the active subscription network provider list for this device as
     * known to the MatchingEngine. If it is empty, the application should use the public cloud
     * instead, as the Distributed Matching Engine may be unavailable (firewalled) from the current
     * network. Calling MatchingEngine APIs in that state will result in a
     * NetworkRequestNoSubscriptionInfoException.
     * \return List<SubscriptionInfo>
     * \ingroup functions_dmeutils
     */
    public List<SubscriptionInfo> getActiveSubscriptionInfoList() {
        List<SubscriptionInfo> subs = getNetworkManager().getActiveSubscriptionInfoList(true);
        return subs;
    }

    /*!
     * Check if Roaming Data is enabled on the System.
     * \param context (android.content.Context)
     * \return boolean
     * \ingroup functions_dmeutils
     */
    public boolean isRoamingDataEanbled(Context context) {
        boolean enabled;
        try {
            enabled = android.provider.Settings.Global.getInt(context.getContentResolver(), Settings.Global.DATA_ROAMING) == 1;
        } catch (Settings.SettingNotFoundException snfe) {
            Log.i(TAG, "android.provider.Settings.Global.DATA_ROAMING key is not present!");
            return false; // Unavailable setting.
        }

        return enabled;
    }

    public Future submit(Callable task) {
        return threadpool.submit(task);
    }

    void setSessionCookie(String sessionCookie) {
        this.mSessionCookie = sessionCookie;
    }

    String getSessionCookie() {
        return this.mSessionCookie;
    }

    RegisterClientRequest getLastRegisterClientRequest() {
        return mRegisterClientRequest;
    }

    synchronized void setLastRegisterClientRequest(AppClient.RegisterClientRequest registerRequest) {
        mRegisterClientRequest = registerRequest;
    }

    synchronized RegisterClientReply getMatchingEngineStatus() {
        return mRegisterClientReply;
    }
    synchronized void setMatchEngineStatus(AppClient.RegisterClientReply status) {
        mRegisterClientReply = status;
    }

    synchronized void setGetLocationReply(GetLocationReply locationReply) {
        mGetLocationReply = locationReply;
        mMatchEngineLocation = locationReply.getNetworkLocation();
    }

    synchronized void setVerifyLocationReply(AppClient.VerifyLocationReply locationVerify) {
        mVerifyLocationReply = locationVerify;
    }

    synchronized void setFindCloudletResponse(AppClient.FindCloudletReply reply) {
        EdgeEventsConnection edgeEventsConnection = getEdgeEventsConnection();
        if (edgeEventsConnection != null && reply != null) {
            edgeEventsConnection.setCurrentCloudlet(FindCloudletReply.newBuilder(reply).build());
        }
    }

    synchronized void setDynamicLocGroupReply(DynamicLocGroupReply reply) {
        mDynamicLocGroupReply = reply;
    }

    /*!
     * Utility method retrieves current MCC + MNC from system service.
     * \param context (android.content.Context)
     * \return String
     * \ingroup functions_dmeutils
     */
    String getMccMnc(Context context) {
        TelephonyManager telManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);

        int subId = SubscriptionManager.getDefaultDataSubscriptionId();
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return null;
        }

        return telManager.createForSubscriptionId(subId).getNetworkOperator();
    }

    /*!
     * General Device Details
     * \return HashMap<String, String>
     * \ingroup functions_dmeutils
     */
    public HashMap<String, String> getDeviceInfo() {
        HashMap<String, String> map = new HashMap<>();
        if (isShutdown()) {
            return null;
        }

        TelephonyManager telManager = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        if (telManager == null) {
            return map;
        }

        map.put("Build.VERSION.SDK_INT", Integer.toString(Build.VERSION.SDK_INT));


        if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED) {
            String ver = telManager.getDeviceSoftwareVersion();
            if (ver != null) {
                map.put("DeviceSoftwareVersion", ver);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String mc = telManager.getManufacturerCode();
            if (mc != null) {
                map.put("ManufacturerCode", mc);
            }
        }

        String niso = telManager.getNetworkCountryIso();
        if (niso != null) {
            map.put("NetworkCountryIso", niso);
        }

        String siso = telManager.getSimCountryIso();
        if (siso != null) {
            map.put("SimCountryCodeIso", siso);
        }


        NetworkManager.DataNetworkType dataType = DeviceInfoUtil.getDataNetworkType(mContext);
        map.put("DataNetworkType", dataType.name());


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            SignalStrength s = telManager.getSignalStrength();
            Integer abstractLevel = s.getLevel();
            map.put("SignalStrength", abstractLevel.toString());
        }

        map.put("PhoneType", Integer.toString(telManager.getPhoneType()));

        // Default one.
        String simOperatorName = telManager.getSimOperatorName();
        if (simOperatorName != null) {
            map.put("SimOperatorName", simOperatorName);
        }

        // Default one.
        String networkOperator = telManager.getNetworkOperatorName();
        if (networkOperator != null) {
            map.put("NetworkOperatorName", networkOperator);
        }

        return map;
    }

    Appcommon.DeviceInfoStatic getDeviceInfoStaticProto() {
        Appcommon.DeviceInfoStatic.Builder deviceInfoBuilder = Appcommon.DeviceInfoStatic.newBuilder();

        deviceInfoBuilder.setDeviceOs(DeviceInfoUtil.getDeviceOS());
        deviceInfoBuilder.setDeviceModel(DeviceInfoUtil.getDeviceModel());
        return deviceInfoBuilder.build();
    }

    Appcommon.DeviceInfoDynamic getDeviceInfoDynamicProto() {
        Appcommon.DeviceInfoDynamic.Builder deviceInfoBuilder = Appcommon.DeviceInfoDynamic.newBuilder();

        NetworkManager.DataNetworkType dataNetworkType = DeviceInfoUtil.getDataNetworkType(mContext);
        if (dataNetworkType != null) {
            deviceInfoBuilder.setDataNetworkType(dataNetworkType.name());
        }

        String carrierName = getCarrierName(mContext);
        if (carrierName != null) {
            deviceInfoBuilder.setCarrierName(carrierName);
        }
        deviceInfoBuilder.setSignalStrength(DeviceInfoUtil.getSignalStrengthLevel(mContext));
        return deviceInfoBuilder.build();
    }

    // Utility functions below:

    /*!
     * Returns the carrier's mcc+mnc which is mapped to a carrier in the backend (ie. 26201 -> GDDT).
     * MCC stands for Mobile Country Code and MNC stands for Mobile Network Code.
     * If UseWifiOnly or cellular is off + wifi is up, this will return """".
     * Empty string carrierName is the alias for any, which will search all carriers for application instances.
     * \param context (android.content.Context)
     * \return String
     * \ingroup functions_dmeutils
     */
    public String getCarrierName(Context context) {
        String mccMnc = getMccMnc(context);

        if (useOnlyWifi) {
            return "";
        }

        if (mccMnc == null) {
            Log.e(TAG, "Network Carrier name is not found on device.");
            return "";
        }
        return mccMnc;
    }

    /*!
     * GenerateDmeHostAddress
     * This will generate the dme host name based on GetMccMnc() -> "mcc-mnc.dme.mobiledgex.net".
     * If GetMccMnc fails or returns null, this will return a fallback dme host: "wifi.dme.mobiledgex.net"(this is the EU + GDDT DME).
     * This function is used by any DME APIs calls where no host and port overloads are provided.
     * \ingroup functions_dmeutils
     */
    public String generateDmeHostAddress() throws DmeDnsException {

        if (useOnlyWifi) {
            return wifiOnlyDmeHost;
        }

        TelephonyManager telManager = (TelephonyManager)mContext.getSystemService(Context.TELEPHONY_SERVICE);
        if (telManager.getSimState() != TelephonyManager.SIM_STATE_READY) {
            Log.w(TAG, "SIM is not in ready state.");
            return wifiOnlyDmeHost; // fallback to wifi, which will be geo-located.
        }

        String mccmnc = telManager.getNetworkOperator();
        if (mccmnc == null || mccmnc.isEmpty()) {
            Log.e(TAG, "No mcc-mnc string available.");
            return wifiOnlyDmeHost; // fallback to wifi, which will be geo-located.
        }

        if (mccmnc.length() < 5 || mccmnc.length() > 6) {
            throw new DmeDnsException("Retrieved mcc-mnc string is outside expected length: " + mccmnc.length());
        }

        String mcc = mccmnc.substring(0,3);
        String mnc = mccmnc.substring(3);

        String potentialDmeHost = mcc + "-" + mnc + "." + baseDmeHost;

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(potentialDmeHost);
        } catch (UnknownHostException uhe) {
            throw new DmeDnsException(("Could not find mcc-mnc.dme.mobiledgex.net DME server: " + potentialDmeHost), uhe);
        }

        if (addresses.length < 1) {
            throw new DmeDnsException(("No IP address for mcc-mnc.dme.mobiledgex.net DME server: " + potentialDmeHost));
        }

        // Return the constructed DME hostname.
        return potentialDmeHost;
    }

    /*!
     * \ingroup functions_dmeutils
     */
    public NetworkManager getNetworkManager() {
        return mNetworkManager;
    }

    void setNetworkManager(NetworkManager networkManager) {
        mNetworkManager = networkManager;
    }

    /*!
     * \ingroup functions_dmeutils
     */
    public String getPackageName(Context context) {
        String appName;
        ApplicationInfo appInfo = context.getApplicationInfo();
        return appInfo.packageName;
    }

    /*!
     * Returns the nonLocalizedLabel Application Name.
     * \param context (android.content.Context)
     * \return String
     * \ingroup functions_dmeutils
     */
    String getAppName(Context context) {
        String appName;
        ApplicationInfo appInfo = context.getApplicationInfo();
        int stringId = appInfo.labelRes;
        appName = appInfo.nonLocalizedLabel != null ? appInfo.nonLocalizedLabel.toString() : null;

        return stringId == 0 ? appName : context.getString(stringId);
    }

    /*!
     * Returns the Application Version.
     * \param context (android.content.Context)
     * \return String
     * \ingroup functions_dmeutils
     */
    String getAppVersion(Context context)
            throws PackageManager.NameNotFoundException {
        PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);

        return pInfo.versionName;
    }

    private void ensureSessionCookie() {
        if (mSessionCookie == null || mSessionCookie.equals((""))) {
            throw new IllegalArgumentException("An unexpired RegisterClient sessionCookie is required.");
        }
    }

    /*!
     * \ingroup functions_dmeutils
     */
    public void ensureSessionCookie(String sessionCookie) {
        if (sessionCookie == null || sessionCookie.equals((""))) {
            throw new IllegalArgumentException("An unexpired RegisterClient sessionCookie is required.");
        }
    }

    /*!
     * Retrieves the current default route network interface IPv4 address.
     * \return Local IPv4 address
     * \ingroup functions_dmeapis
     */
    public String getLocalIpv4() {
        String localIp = getLocalIpAny();
        if (localIp == null) {
            return null;
        }
        else if (localIp.contains(".")) {
            return localIp;
        }
        else {
            Log.d(TAG, "Local default interface is IPv6 only. Returning empty string.");
            return null;
        }
    }

    /*!
     * Retrieves the current default route network interface address.
     * \return Local IP address
     * \ingroup functions_dmeapis
     */
    public String getLocalIpAny() {
        Network net = getNetworkManager().getActiveNetwork();
        if (net == null) {
            return null;
        }
        // UDP "connect" to get the default route's local IP address.
        DatagramSocket ds = null;
        try {
            ds = new DatagramSocket();
            ds.connect(InetAddress.getByName(wifiOnlyDmeHost), getPort());
            InetAddress localInet = ds.getLocalAddress();
            String hostStr;
            if (localInet != null && (hostStr = localInet.getHostAddress()) != null) {
                return hostStr;
            }
            else {
                return null;
            }
        } catch (SocketException | UnknownHostException e) {
            e.printStackTrace();
            return null;
        }
    }

    /*!
     * Returns a builder for RegisterClientRequest. Call build() after setting
     * additional optional fields like AuthToken or Tags.
     * \param context (android.content.Context)
     * \param organizationName (String)
     * \return RegisterClientRequest.Builder
     * \exception PackageManager.NameNotFoundException
     * \ingroup functions_dmeapis
     * \section createdefregisterexample Example
     * \snippet EngineCallTest.java createdefregisterexample
     */
    public RegisterClientRequest.Builder createDefaultRegisterClientRequest(Context context,
                                                                            String organizationName)
            throws PackageManager.NameNotFoundException {

        if (!mMatchingEngineLocationAllowed) {
            Log.e(TAG, "Location Permission required to Create DefaultRegisterClientRequest. Consider using com.mobiledgex.matchingengine.util.RequestPermissions and then calling MatchingEngine.setMatchingEngineLocationAllowed(true).");
            return null;
        }
        if (context == null) {
            throw new IllegalArgumentException("MatchingEngine requires a working application context.");
        }

        if (organizationName == null || organizationName.equals("")) {
            throw new IllegalArgumentException("RegisterClientRequest requires a organization name.");
        }

        // App
        String appName = getAppName(context);
        String versionName = getAppVersion(context);

        // Invalid application name state
        if (appName == null || appName.equals("")) {
            Log.w(TAG, "Warning: No application name found! RegisterClientRequest requires an application name.");
            appName = "";
        }

        // Invalid application name state
        if (versionName == null || versionName.equals("")) {
            Log.w(TAG, "Warning: No application versionName found! RegisterClientRequest requires an application version name.");
            versionName = "";
        }

        RegisterClientRequest.Builder builder = AppClient.RegisterClientRequest.newBuilder()
                .setOrgName(organizationName);

        if (appName != null) {
            builder.setAppName(appName);
        }
        if (versionName != null) {
            builder.setAppVers(versionName);
        }

        // No carrierName is used for DME in register.
        builder.setAuthToken("");
        return builder;
    }

    /*!
     * \ingroup functions_dmeapis
     */
    public RegisterClientRequest createRegisterClientRequest(Context context, String organizationName,
                                                             String applicationName, String appVersion,
                                                             String authToken, String uniqueIdType,
                                                             String uniqueId, Map<String, String> tags)
            throws PackageManager.NameNotFoundException
    {
        if (!mMatchingEngineLocationAllowed) {
            Log.e(TAG, "Location Permission required to Create RegisterClientRequest. Consider using com.mobiledgex.matchingengine.util.RequestPermissions and then calling MatchingEngine.setMatchingEngineLocationAllowed(true).");
            return null;
        }
        if (context == null) {
            throw new IllegalArgumentException("MatchingEngine requires a working application context.");
        }
        if (organizationName == null || organizationName.equals("")) {
            throw new IllegalArgumentException("RegisterClientRequest requires a organization name.");
        }

        // App
        ApplicationInfo appInfo = context.getApplicationInfo();
        String packageLabel = "";
        if (context.getPackageManager() != null) {
            CharSequence seq = appInfo.loadLabel(context.getPackageManager());
            if (seq != null) {
                packageLabel = seq.toString();
            }
        }
        String versionName;
        String appName;
        if (applicationName == null || applicationName.equals("")) {
            appName = getAppName(context);
            appName = appName == "" ? packageLabel : appName;
        } else {
            appName = applicationName;
        }

        versionName = (appVersion == null || appVersion.isEmpty()) ? getAppVersion(context) : appVersion;

        RegisterClientRequest.Builder builder = AppClient.RegisterClientRequest.newBuilder()
                .setOrgName((organizationName == null) ? "" : organizationName)
                .setAppName(appName)
                .setAppVers(versionName)
                .setAuthToken((authToken == null) ? "" : authToken);

        if (tags != null) {
            builder.putAllTags(tags);
        }

        if (uniqueId != null && uniqueId.length() > 0 &&
                uniqueIdType != null && uniqueIdType.length() > 0) {
            builder.setUniqueIdType(uniqueIdType);
            builder.setUniqueId(uniqueId);
        }

        return builder.build();
    }

    /*!
     * Returns a builder for VerifyLocationRequest. Call build() after setting
     * additional optional fields like Tags.
     * \param context (android.content.Context)
     * \param location (android.location.Location)
     * \return VerifyLocationRequest.Builder
     * \ingroup functions_dmeapis
     * \section createdefverifylocationexample Example
     * \snippet EngineCallTest.java createdefverifylocationexample
     */
    public VerifyLocationRequest.Builder createDefaultVerifyLocationRequest(Context context,
                                                             android.location.Location location) {
        if (context == null) {
            throw new IllegalArgumentException("MatchingEngine requires a working application context.");
        }

        ensureSessionCookie();

        if (!mMatchingEngineLocationAllowed) {
            Log.e(TAG, "Location Permission required to Create DefaultVerifyLocationRequest. Consider using com.mobiledgex.matchingengine.util.RequestPermissions and then calling MatchingEngine.setMatchingEngineLocationAllowed(true).");
            return null;
        }

        if (location == null) {
            throw new IllegalArgumentException("Location parameter is required.");
        }

        String carrierName = getCarrierName(context);
        Loc aLoc = androidLocToMeLoc(location);

        VerifyLocationRequest.Builder builder = AppClient.VerifyLocationRequest.newBuilder()
                .setSessionCookie(mSessionCookie)
                .setCarrierName(carrierName)
                .setGpsLocation(aLoc); // Latest token is unknown until retrieved.
        return builder;
    }

    /*!
     * Creates a Default FindCloudletRequest. If VersionName or AppName is missing (test code),
     * the app will need to fill this in before sending to the server.
     * \param context (android.content.Context)
     * \param location (android.location.Location)
     * \return FindCloudletRequest.Builder
     * \exception PackageManager.NameNotFoundException
     * \ingroup functions_dmeapis
     * \section createdeffindcloudletexample Example
     * \snippet EngineCallTest.java createdeffindcloudletexample
     */
    public AppClient.FindCloudletRequest.Builder createDefaultFindCloudletRequest(Context context, Location location) {
        if (!mMatchingEngineLocationAllowed) {
            Log.e(TAG, "Location Permission required to Create DefaultFindCloudletRequest. Consider using com.mobiledgex.matchingengine.util.RequestPermissions and then calling MatchingEngine.setMatchingEngineLocationAllowed(true).");
            return null;
        }
        if (context == null) {
            throw new IllegalArgumentException("MatchingEngine requires a working application context.");
        }
        ensureSessionCookie();

        Loc aLoc = androidLocToMeLoc(location);
        if (mEdgeEventsConnection != null) {
            mEdgeEventsConnection.setLastLocationPosted(location);
        }


        FindCloudletRequest.Builder builder = FindCloudletRequest.newBuilder()
                .setSessionCookie(mSessionCookie)
                .setCarrierName(getCarrierName(context))
                .setGpsLocation(aLoc);
        return builder;
    }

    /*!
     * @private
     * \ingroup functions_dmeapis
     */
    public AppClient.GetLocationRequest.Builder createDefaultGetLocationRequest(Context context) {
        if (!mMatchingEngineLocationAllowed) {
            Log.e(TAG, "Location Permission required to Create DefaultGetLocationRequest. Consider using com.mobiledgex.matchingengine.util.RequestPermissions and then calling MatchingEngine.setMatchingEngineLocationAllowed(true).");
            return null;
        }
        if (context == null) {
            throw new IllegalArgumentException("MatchingEngine requires a working application context.");
        }
        ensureSessionCookie();

        return GetLocationRequest.newBuilder()
                .setSessionCookie(mSessionCookie)
                .setCarrierName(getCarrierName(context));
    }

    /*!
     * Returns a builder for AppInstListRequest. Call build() after setting
     * additional optional fields like Tags.
     * \param context (android.content.Context)
     * \param location (android.location.Location)
     * \return AppInstListRequest.Builder
     * \ingroup functions_dmeapis
     * \section createdefappinstexample Example
     * \snippet EngineCallTest.java createdefappinstexample
     */
    public AppClient.AppInstListRequest.Builder createDefaultAppInstListRequest(Context context, android.location.Location location) {
        if (!mMatchingEngineLocationAllowed) {
            Log.e(TAG, "Location Permission required to Create DefaultAppInstListRequest. Consider using com.mobiledgex.matchingengine.util.RequestPermissions and then calling MatchingEngine.setMatchingEngineLocationAllowed(true).");
            return null;
        }
        if (context == null) {
            throw new IllegalArgumentException("MatchingEngine requires a working application context.");
        }
        ensureSessionCookie();

        String carrierName = getCarrierName(context);
        Loc aLoc = androidLocToMeLoc(location);

        return AppInstListRequest.newBuilder()
                .setSessionCookie(mSessionCookie)
                .setCarrierName(carrierName)
                .setGpsLocation(aLoc);
    }

    /*!
     * Returns a builder for QosPrioritySessionCreateRequest. Call build() after setting
     * additional optional fields like IPs and ports.
     * \param context (android.content.Context)
     * \return QosPrioritySessionCreateRequest.Builder
     * \ingroup functions_dmeapis
     * \section createdefappinstexample Example
     * \snippet EngineCallTest.java createdefappinstexample
     */
    public AppClient.QosPrioritySessionCreateRequest.Builder createDefaultQosPrioritySessionCreateRequest(Context context) {
        if (!mMatchingEngineLocationAllowed) {
            Log.e(TAG, "Location Permission required to Create DefaultQosPrioritySessionCreateRequest. Consider using com.mobiledgex.matchingengine.util.RequestPermissions and then calling MatchingEngine.setMatchingEngineLocationAllowed(true).");
            return null;
        }
        if (context == null) {
            throw new IllegalArgumentException("MatchingEngine requires a working application context.");
        }
        ensureSessionCookie();

        return QosPrioritySessionCreateRequest.newBuilder()
                .setSessionCookie(mSessionCookie);
    }

    /*!
     * Returns a builder for QosPrioritySessionDeleteRequest. Call build() after setting
     * additional optional fields like session ID and profile name.
     * \param context (android.content.Context)
     * \return QosPrioritySessionDeleteRequest.Builder
     * \ingroup functions_dmeapis
     * \section createdefappinstexample Example
     * \snippet EngineCallTest.java createdefappinstexample
     */
    public AppClient.QosPrioritySessionDeleteRequest.Builder createDefaultQosPrioritySessionDeleteRequest(Context context) {
        if (!mMatchingEngineLocationAllowed) {
            Log.e(TAG, "Location Permission required to Delete DefaultQosPrioritySessionDeleteRequest. Consider using com.mobiledgex.matchingengine.util.RequestPermissions and then calling MatchingEngine.setMatchingEngineLocationAllowed(true).");
            return null;
        }
        if (context == null) {
            throw new IllegalArgumentException("MatchingEngine requires a working application context.");
        }
        ensureSessionCookie();

        return QosPrioritySessionDeleteRequest.newBuilder()
                .setSessionCookie(mSessionCookie);
    }

    /*!
     * @private
     * \ingroup functions_dmeapis
     */
    public AppClient.DynamicLocGroupRequest.Builder createDefaultDynamicLocGroupRequest(Context context, DynamicLocGroupRequest.DlgCommType commType) {
        if (!mMatchingEngineLocationAllowed) {
            Log.e(TAG, "Location Permission required to Create DefaultDynamicLocGroupRequest. Consider using com.mobiledgex.matchingengine.util.RequestPermissions and then calling MatchingEngine.setMatchingEngineLocationAllowed(true).");
            return null;
        }
        if (context == null) {
            throw new IllegalArgumentException("MatchingEngine requires a working application context.");
        }
        ensureSessionCookie();

        return DynamicLocGroupRequest.newBuilder()
                .setSessionCookie(mSessionCookie)
                .setLgId(1001L) // FIXME: NOT IMPLEMENTED
                .setCommType(commType);
    }

    /*!
     * Returns a builder for QosPositionRequest. Call build() after setting
     * additional optional fields like Tags.
     * \param requests (List<QosPosition>): List of locations to get Qos
     * \param lte_category (int): Optional
     * \param band_selection (BandSelection): Optional
     * \return QosPositionRequest.Builder
     * \ingroup functions_dmeapis
     * \section createdefqosexample Example
     * \snippet EngineCallTest.java createdefqosexample
     */
    public AppClient.QosPositionRequest.Builder createDefaultQosPositionRequest(List<QosPosition> requests, int lte_category, BandSelection band_selection) {

        if (!mMatchingEngineLocationAllowed) {
            Log.e(TAG, "Location Permission required to Create DefaultQosPositionRequest. Consider using com.mobiledgex.matchingengine.util.RequestPermissions and then calling MatchingEngine.setMatchingEngineLocationAllowed(true).");
            return null;
        }
        ensureSessionCookie();

        QosPositionRequest.Builder builder = QosPositionRequest.newBuilder();
        builder.setSessionCookie(mSessionCookie)
                .addAllPositions(requests)
                .setLteCategory(lte_category);

        if (band_selection != null) {
            builder.setBandSelection(band_selection);
        }

        return builder;
    }

    Loc androidLocToMeLoc(android.location.Location loc) {
        Loc.Builder builder = Loc.newBuilder()
                .setLatitude((loc == null) ? 0.0d : loc. getLatitude())
                .setLongitude((loc == null) ? 0.0d : loc.getLongitude())
                .setHorizontalAccuracy((loc == null) ? 0.0d :loc.getAccuracy())
                .setVerticalAccuracy(0d)
                .setAltitude((loc == null) ? 0.0d : loc.getAltitude())
                .setCourse((loc == null) ? 0.0d : loc.getBearing())
                .setSpeed((loc == null) ? 0.0d : loc.getSpeed());

        if (Build.VERSION.SDK_INT > 26) {
            builder.setVerticalAccuracy((loc == null) ? 0.0d : loc.getVerticalAccuracyMeters());
        }
        return builder.build();
    }

    /*!
     * Registers Client using blocking API call.
     * First DME API called. This will register the client with the MobiledgeX backend and
     * check to make sure that the app that the user is running exists. (ie. This confirms
     * that CreateApp in Console/Mcctl has been run successfully). RegisterClientReply
     * contains a session cookie that will be used (automatically) in later API calls.
     * It also contains a uri that will be used to get the verifyLocToken used in VerifyLocation.
     * \param request (RegisterClientRequest)
     * \param timeoutInMilliseconds (long)
     * \return RegisterClientReply
     * \exception StatusRuntimeException
     * \exception InterruptedException
     * \exception ExecutionException
     * \ingroup functions_dmeapis
     * \section registerexample Example
     * \snippet EngineCallTest.java registerexample
     */
    public RegisterClientReply registerClient(RegisterClientRequest request,
                                              long timeoutInMilliseconds)
            throws DmeDnsException, StatusRuntimeException, InterruptedException, ExecutionException {
        return registerClient(request, generateDmeHostAddress(), getPort(), timeoutInMilliseconds);
    }

    /*!
     * Registers Client using blocking API call. Allows specifying a DME host and port.
     * Only use for testing.
     * \param request (RegisterClientRequest)
     * \param host (String): Distributed Matching Engine hostname
     * \param port (int): Distributed Matching Engine port
     * \param timeoutInMilliseconds (long)
     * \return RegisterClientReply
     * \exception StatusRuntimeException
     * \exception InterruptedException
     * \exception ExecutionException
     * \ingroup functions_dmeapis
     * \section registeroverrideexample Example
     * \snippet EngineCallTest.java registeroverrideexample
     */
    public RegisterClientReply registerClient(RegisterClientRequest request,
                                              String host, int port,
                                              long timeoutInMilliseconds)
            throws StatusRuntimeException, InterruptedException, ExecutionException {
        RegisterClient registerClient = new RegisterClient(this); // Instanced, so just add host, port as field.
        registerClient.setRequest(request, host, port, timeoutInMilliseconds);

        Log.i(TAG, "DME host is: " + host);
        return registerClient.call();
    }

    /*!
     * \ingroup functions_dmeapis
     */
    public Future<RegisterClientReply> registerClientFuture(RegisterClientRequest request,
                                                            long timeoutInMilliseconds)
            throws DmeDnsException {
        return registerClientFuture(request, generateDmeHostAddress(), getPort(), timeoutInMilliseconds);
    }

    /*!
     * Registers device on the MatchingEngine server. Returns a Future.
     * \param request (RegisterClientRequest)
     * \param host (String): Distributed Matching Engine hostname
     * \param port (int): Distributed Matching Engine port
     * \param timeoutInMilliseconds (long)
     * \return Future<RegisterClientReply>
     * \ingroup functions_dmeapis
     */
    public Future<RegisterClientReply> registerClientFuture(RegisterClientRequest request,
                                                            String host, int port,
                                                            long timeoutInMilliseconds) {
        RegisterClient registerClient = new RegisterClient(this);
        registerClient.setRequest(request, host, port, timeoutInMilliseconds);
        return submit(registerClient);
    }

    /*!
     * findCloudlet finds the closest cloudlet instance as per request.
     * @param request
     * @param timeoutInMilliseconds
     * @return cloudlet URIs
     * \exception StatusRuntimeException
     * \exception InterruptedException
     * \exception ExecutionException
     * \ingroup functions_dmeapis
     * \section findcloudletexample Example
     * \snippet EngineCallTest.java findcloudletexample
     */
    public FindCloudletReply findCloudlet(FindCloudletRequest request,
                                          long timeoutInMilliseconds)
            throws DmeDnsException, StatusRuntimeException, InterruptedException, ExecutionException {
        return findCloudlet(request, generateDmeHostAddress(), getPort(), timeoutInMilliseconds, FindCloudletMode.PROXIMITY, -1);
    }

    /*!
   * findCloudlet finds the closest cloudlet instance as per request.
     * FindCloudlet returns information needed for the client app to connect to an application backend deployed through MobiledgeX.
     * If there is an application backend instance found, FindCloudetReply will contain the fqdn of the application backend and an array of AppPorts (with information specific to each application backend endpoint)
     * \param request (FindCloudletRequest)
     * \param timeoutInMilliseconds (long)
     * \param mode (FindCloudletMode)
     * \return FindCloudletReply: cloudlet URIs
     * \exception StatusRuntimeException
     * \exception InterruptedException
     * \exception ExecutionException
     * \ingroup functions_dmeapis
   */
    public FindCloudletReply findCloudlet(FindCloudletRequest request,
                                          long timeoutInMilliseconds, FindCloudletMode mode)
            throws DmeDnsException, StatusRuntimeException, InterruptedException, ExecutionException {
        return findCloudlet(request, generateDmeHostAddress(), getPort(), timeoutInMilliseconds, mode, -1);
    }

    /*!
   * findCloudlet finds the closest cloudlet instance as per request.
     * FindCloudlet overload with hardcoded DME host and port. Only use for testing.
     * \param request (FindCloudletRequest)
     * \param host (String): Distributed Matching Engine hostname
     * \param port (int): Distributed Matching Engine port
     * \param timeoutInMilliseconds (long)
     * \return FindCloudletReply: cloudlet URIs
     * \exception StatusRuntimeException
     * \ingroup functions_dmeapis
     * \section findcloudletoverrideexample Example
     * \snippet EngineCallTest.java findcloudletoverrideexample
   */
  public FindCloudletReply findCloudlet(FindCloudletRequest request,
                                        String host, int port,
                                        long timeoutInMilliseconds)
    throws StatusRuntimeException, InterruptedException, ExecutionException {
      FindCloudlet findCloudlet = new FindCloudlet(this);

      // This also needs some info for MEL.
      findCloudlet.setRequest(request, host, port, timeoutInMilliseconds, FindCloudletMode.PROXIMITY, -1);

      Log.i(TAG, "DME host is: " + host);
      return findCloudlet.call();
  }

    /*!
     * findCloudlet finds the closest cloudlet instance as per request.
     * FindCloudlet overload with hardcoded DME host and port. Only use for testing.
     * \param request (FindCloudletRequest)
     * \param host (String): Distributed Matching Engine hostname
     * \param port (int): Distributed Matching Engine port
     * \param timeoutInMilliseconds (long)
     * \param mode (FindCloudletMode): to use to find edge cloudlets.
     * \return cloudlet URIs
     * \exception StatusRuntimeException
     * \ingroup functions_dmeapis
     */
    public FindCloudletReply findCloudlet(FindCloudletRequest request,
                                          String host, int port,
                                          long timeoutInMilliseconds,
                                          FindCloudletMode mode)
            throws StatusRuntimeException, InterruptedException, ExecutionException {
        FindCloudlet findCloudlet = new FindCloudlet(this);

        // This also needs some info for MEL.
        findCloudlet.setRequest(request, host, port, timeoutInMilliseconds, mode, -1);

        Log.i(TAG, "DME host is: " + host);
        return findCloudlet.call();
    }

    /*!
     * findCloudlet finds the closest cloudlet instance as per request.
     * FindCloudlet overload with hardcoded DME host and port. Only use for testing.
     * \param request (FindCloudletRequest)
     * \param host (String): Distributed Matching Engine hostname
     * \param port (int): Distributed Matching Engine port
     * \param timeoutInMilliseconds (long)
     * \param mode (FindCloudletMode): to use to find edge cloudlets.
     * \param maxLatencyMs maximum latency in milliseconds. Do not auto-migrate if current performance mode is faster.
     * \return cloudlet URIs
     * \exception StatusRuntimeException
     * \ingroup functions_dmeapis
     */
    FindCloudletReply findCloudlet(FindCloudletRequest request,
                                          String host, int port,
                                          long timeoutInMilliseconds,
                                          FindCloudletMode mode,
                                          long maxLatencyMs)
            throws StatusRuntimeException, InterruptedException, ExecutionException {
        FindCloudlet findCloudlet = new FindCloudlet(this);

        // This also needs some info for MEL.
        findCloudlet.setRequest(request, host, port, timeoutInMilliseconds, mode, maxLatencyMs);

        Log.i(TAG, "DME host is: " + host);
        return findCloudlet.call();
    }

    /*!
     * findCloudlet finds the closest cloudlet instance as per request. Returns a Future.
     * \param request (FindCloudletReply)
     * \param timeoutInMilliseconds (long)
     * \return Future<FindCloudletReply>: cloudlet URIs Future.
     * \ingroup functions_dmeapis
     */
    public Future<FindCloudletReply> findCloudletFuture(FindCloudletRequest request,
                                          long timeoutInMilliseconds)
            throws DmeDnsException {
        return findCloudletFuture(request, generateDmeHostAddress(), getPort(), timeoutInMilliseconds, FindCloudletMode.PROXIMITY);
    }

    /*!
   * findCloudlet finds the closest cloudlet instance as per request. Returns a Future.
     * \param request (FindCloudletRequest)
     * \param timeoutInMilliseconds (long)
     * \param mode (FindCloudletMode): algorithm to use to find edge cloudlets.
     * \return Future<FindCloudletReply>: cloudlet URI Future.
     * \ingroup functions_dmeapis
   */
    public Future<FindCloudletReply> findCloudletFuture(FindCloudletRequest request,
                                                        long timeoutInMilliseconds,
                                                        FindCloudletMode mode)
        throws DmeDnsException {
            return findCloudletFuture(request, generateDmeHostAddress(), getPort(), timeoutInMilliseconds, mode);
    }

    /*!
     * findCloudletFuture finds the closest cloudlet instance as per request. Returns a Future.
     * \param request (FindCloudletRequest)
     * \param host (String): Distributed Matching Engine hostname
     * \param port (int): Distributed Matching Engine port
     * \param timeoutInMilliseconds (long)
     * \return Future<FindCloudletReply>: cloudlet URI Future.
     * \ingroup functions_dmeapis
     */
    public Future<FindCloudletReply> findCloudletFuture(FindCloudletRequest request,
                                                        String host, int port,
                                                        long timeoutInMilliseconds) {
      FindCloudlet findCloudlet = new FindCloudlet(this);
      findCloudlet.setRequest(request, host, port, timeoutInMilliseconds, FindCloudletMode.PROXIMITY, -1);
      return submit(findCloudlet);
    }

    /*!
   * findCloudletFuture finds the closest cloudlet instance as per request. Returns a Future.
     * \param request (FindCloudletRequest)
     * \param host (String): Distributed Matching Engine hostname
     * \param port (int): Distributed Matching Engine port
     * \param timeoutInMilliseconds (long)
     * \param mode (FindCloudletMode): algorithm to use to find edge cloudlets.
     * \return Future<FindCloudletReply>: cloudlet URI Future.
     * \ingroup functions_dmeapis
   */
    public Future<FindCloudletReply> findCloudletFuture(FindCloudletRequest request,
                                                        String host, int port,
                                                        long timeoutInMilliseconds,
                                                        FindCloudletMode mode) {
        FindCloudlet findCloudlet = new FindCloudlet(this);
        findCloudlet.setRequest(request, host, port, timeoutInMilliseconds, mode, -1);
        return submit(findCloudlet);
    }

    /*!
     * verifyLocation validates the client submitted information against known network
     * Makes sure that the user's location is not spoofed based on gps location.
     * Returns the Cell Tower status (CONNECTED_TO_SPECIFIED_TOWER if successful) and Gps Location status (LOC_VERIFIED if successful).
     * Also provides the distance between where the user claims to be and where carrier believes user to be (via gps and cell id) in km.
     * parameters on the subscriber network side.
     * \param request (VerifyLocationRequest)
     * \param timeoutInMilliseconds (long)
     * \return VerifyLocationReply
     * \exception StatusRuntimeException
     * \exception InterruptedException
     * \exception IOException
     * \exception ExecutionException
     * \ingroup functions_dmeapis
     * \section verifylocationexample Example
     * \snippet EngineCallTest.java verifylocationexample
     */
    public VerifyLocationReply verifyLocation(VerifyLocationRequest request,
                                             long timeoutInMilliseconds)
            throws DmeDnsException, StatusRuntimeException, InterruptedException, IOException,
                   ExecutionException {
        return verifyLocation(request, generateDmeHostAddress(), getPort(), timeoutInMilliseconds);
    }

    /*!
     * verifyLocation validates the client submitted information against known network
     * VerifyLocation overload with hardcoded DME host and port. Only use for testing.
     * parameters on the subscriber network side.
     * \param request (VerifyLocationRequest)
     * \param host (String): Distributed Matching Engine hostname
     * \param port (int): Distributed Matching Engine port
     * \param timeoutInMilliseconds (long)
     * \return VerifyLocationReply
     * \exception StatusRuntimeException
     * \exception InterruptedException
     * \exception IOException
     * \ingroup functions_dmeapis
     * \section verifylocationoverrideexample Example
     * \snippet EngineCallTest.java verifylocationoverrideexample
     */
    public VerifyLocationReply verifyLocation(VerifyLocationRequest request,
                                              String host, int port,
                                              long timeoutInMilliseconds)
            throws StatusRuntimeException, InterruptedException, IOException, ExecutionException {
        VerifyLocation verifyLocation = new VerifyLocation(this);
        verifyLocation.setRequest(request, host, port, timeoutInMilliseconds);

        Log.i(TAG, "DME host is: " + host);
        return verifyLocation.call();
    }

    /*!
     * verifyLocationFuture validates the client submitted information against known network
     * parameters on the subscriber network side. Returns a future.
     * \param request (VerifyLocationRequest)
     * \param timeoutInMilliseconds (long)
     * \return Future<VerifyLocationReply>
     * \ingroup functions_dmeapis
     */
    public Future<VerifyLocationReply> verifyLocationFuture(VerifyLocationRequest request,
                                                            long timeoutInMilliseconds)
            throws DmeDnsException {
        return verifyLocationFuture(request, generateDmeHostAddress(), getPort(), timeoutInMilliseconds);
    }

    /*!
     * verifyLocationFuture validates the client submitted information against known network
     * parameters on the subscriber network side. Returns a future.
     * \param request (VerifyLocationRequest)
     * \param host (String)
     * \param port (int)
     * \param timeoutInMilliseconds (long)
     * \return Future<VerifyLocationReply>
     * \ingroup functions_dmeapis
     */
    public Future<VerifyLocationReply> verifyLocationFuture(VerifyLocationRequest request,
                                                            String host, int port,
                                                            long timeoutInMilliseconds) {
        VerifyLocation verifyLocation = new VerifyLocation(this);
        verifyLocation.setRequest(request, host, port, timeoutInMilliseconds);
        return submit(verifyLocation);
    }

    /*!
     * @private
     * addUserToGroup is a blocking call.
     * \param request (DynamicLocGroupRequest)
     * \param timeoutInMilliseconds (long)
     * \return DynamicLocGroupReply
     * \exception InterruptedException
     * \exception ExecutionException
     * \ingroup functions_dmeapis
     */
    DynamicLocGroupReply addUserToGroup(DynamicLocGroupRequest request,
                                               long timeoutInMilliseconds)
            throws DmeDnsException, InterruptedException, ExecutionException {
        return addUserToGroup(request, generateDmeHostAddress(), getPort(), timeoutInMilliseconds);
    }

    /*!
     * @private
     * addUserToGroup is a blocking call.
     * \param request (DynamicLocGroupRequest)
     * \param host (String): Distributed Matching Engine hostname
     * \param port (int): Distributed Matching Engine port
     * \param timeoutInMilliseconds (long)
     * \return DynamicLocGroupReply
     * \ingroup functions_dmeapis
     */
    DynamicLocGroupReply addUserToGroup(DynamicLocGroupRequest request,
                                               String host, int port,
                                               long timeoutInMilliseconds)
            throws InterruptedException, ExecutionException {
        AddUserToGroup addUserToGroup = new AddUserToGroup(this);
        addUserToGroup.setRequest(request, host, port, timeoutInMilliseconds);

        Log.i(TAG, "DME host is: " + host);
        return addUserToGroup.call();
    }

    /*!
     * @private
     * addUserToGroupFuture
     * \param request (DynamicLocGroupRequest)
     * \param timeoutInMilliseconds (long)
     * \return Future<DynamicLocGroupReply>
     * \ingroup functions_dmeapis
     */
    Future<DynamicLocGroupReply> addUserToGroupFuture(DynamicLocGroupRequest request,
                                                             long timeoutInMilliseconds)
            throws DmeDnsException {
        return addUserToGroupFuture(request, generateDmeHostAddress(), getPort(), timeoutInMilliseconds);
    }

    /*!
     * @private
     * addUserToGroupFuture
     * \param request
     * \param host Distributed Matching Engine hostname
     * \param port Distributed Matching Engine port
     * \param timeoutInMilliseconds
     * \return
     * \ingroup functions_dmeapis
     */
    Future<DynamicLocGroupReply> addUserToGroupFuture(DynamicLocGroupRequest request,
                                                             String host, int port,
                                                             long timeoutInMilliseconds) {
        AddUserToGroup addUserToGroup = new AddUserToGroup(this);
        addUserToGroup.setRequest(request, host, port, timeoutInMilliseconds);
        return submit(addUserToGroup);
    }

    /*!
     * Retrieve nearby AppInsts for registered application. This is a blocking call.
     * Returns a list of the developer's backend instances deployed on the specified carrier's network.
     * If carrier was "", returns all backend instances regardless of carrier network.
     * This is used internally in FindCloudlet Performance mode to grab the list of cloudlets to test.
     * \param request (AppInstListRequest)
     * \param timeoutInMilliseconds (long)
     * \return AppInstListReply
     * \exception InterruptedException
     * \exception ExecutionException
     * \ingroup functions_dmeapis
     * \section appinstlistexample Example
     * \snippet EngineCallTest.java appinstlistexample
     */
    public AppInstListReply getAppInstList(AppInstListRequest request,
                                           long timeoutInMilliseconds)
            throws DmeDnsException, InterruptedException, ExecutionException {
        return getAppInstList(request, generateDmeHostAddress(), getPort(), timeoutInMilliseconds);
    }

    /*!
     * Retrieve nearby AppInsts for registered application. This is a blocking call.
     * GetAppInstList overload with hardcoded DME host and port. Only use for testing.
     * \param request (AppInstListRequest)
     * \param host (String)
     * \param port (int)
     * \param timeoutInMilliseconds (long)
     * \return AppInstListReply
     * \ingroup functions_dmeapis
     * \section appinstlistoverrideexample Example
     * \snippet EngineCallTest.java appinstlistoverrideexample
     */
    public AppInstListReply getAppInstList(AppInstListRequest request,
                                           String host, int port,
                                           long timeoutInMilliseconds)
            throws InterruptedException, ExecutionException {
        GetAppInstList getAppInstList = new GetAppInstList(this);
        getAppInstList.setRequest(request, host, port, timeoutInMilliseconds);

        Log.i(TAG, "DME host is: " + host);
        return getAppInstList.call();
    }

    /*!
     * Retrieve nearby AppInsts for registered application. Returns a Future.
     * \param request (AppInstListRequest)
     * \param timeoutInMilliseconds (long)
     * \return Future<AppInstListReply>
     * \ingroup functions_dmeapis
     */
    public Future<AppInstListReply> getAppInstListFuture(AppInstListRequest request,
                                                         long timeoutInMilliseconds)
            throws DmeDnsException {
        return getAppInstListFuture(request, generateDmeHostAddress(), getPort(), timeoutInMilliseconds);
    }

    /*!
     * Retrieve nearby AppInsts for registered application. Returns a Future.
     * \param request (AppInstListRequest)
     * \param host (String)
     * \param port (int)
     * \param timeoutInMilliseconds (long)
     * \return Future<AppInstListReply>
     * \ingroup functions_dmeapis
     */
    public Future<AppInstListReply> getAppInstListFuture(AppInstListRequest request,
                                                         String host, int port,
                                                         long timeoutInMilliseconds) {
        GetAppInstList getAppInstList = new GetAppInstList(this);
        getAppInstList.setRequest(request, host, port, timeoutInMilliseconds);
        return submit(getAppInstList);
    }

    /*!
     * Request QOS values from a list of PositionKPIRequests, and returns a stream Iterator of
     * predicted QOS values. Provides quality of service metrics for each location provided in qos position request
     * \param request (QosPositionRequest)
     * \param timeoutInMilliseconds (long)
     * \return ChannelIterator<QosPositionKpiReply>
     * \exception InterruptedException
     * \exception ExecutionException
     * \ingroup functions_dmeapis
     * \section qospositionexample Example
     * \snippet EngineCallTest.java qospositionexample
     */
    public ChannelIterator<QosPositionKpiReply> getQosPositionKpi(QosPositionRequest request,
                                                                  long timeoutInMilliseconds)
            throws DmeDnsException, InterruptedException, ExecutionException {
        QosPositionKpi qosPositionKpi = new QosPositionKpi(this);
        qosPositionKpi.setRequest(request,generateDmeHostAddress(), getPort(), timeoutInMilliseconds);

        Log.i(TAG, "DME host is: " + host);
        return qosPositionKpi.call();
    }

    /*!
     * Request QOS values from a list of PositionKPIRequests, and returns an asynchronous Future
     * for a stream Iterator of predicted QOS values.
     * \param request (QosPositionRequest)
     * \param timeoutInMilliseconds (long)
     * \return Future<ChannelIterator<QosPositionKpiReply>>
     * \exception InterruptedException
     * \exception ExecutionException
     * \ingroup functions_dmeapis
     */
    public Future<ChannelIterator<QosPositionKpiReply>> getQosPositionKpiFuture(QosPositionRequest request,
                                                                  long timeoutInMilliseconds)
            throws DmeDnsException {
        QosPositionKpi qosPositionKpi = new QosPositionKpi(this);
        qosPositionKpi.setRequest(request,generateDmeHostAddress(), getPort(), timeoutInMilliseconds);
        return submit(qosPositionKpi);
    }

    /*!
     * Request QOS values from a list of PositionKPIRequests, and returns a stream Iterator of
     * predicted QOS values.
     * GetQosPositionKpi overload with hardcoded DME host and port. Only use for testing.
     * \param request (QosPositionRequest)
     * \param host (String)
     * \param port (int)
     * \param timeoutInMilliseconds (long)
     * \return ChannelIterator<QosPositionKpiReply>
     * \exception InterruptedException
     * \exception ExecutionException
     * \ingroup functions_dmeapis
     * \section qospositionoverrideexample Example
     * \snippet EngineCallTest.java qospositionoverrideexample
     */
    public ChannelIterator<QosPositionKpiReply> getQosPositionKpi(QosPositionRequest request,
                                                                  String host, int port,
                                                                  long timeoutInMilliseconds)
            throws InterruptedException, ExecutionException {
        QosPositionKpi qosPositionKpi = new QosPositionKpi(this);
        qosPositionKpi.setRequest(request, host, port, timeoutInMilliseconds);

        Log.i(TAG, "DME host is: " + host);
        return qosPositionKpi.call();
    }

    /*!
     * Request QOS values from a list of PositionKPIRequests, and returns an asynchronous Future
     * for a stream Iterator of predicted QOS values.
     * \param request (QosPositionRequest)
     * \param host (String)
     * \param port (int)
     * \param timeoutInMilliseconds (long)
     * \return Future<ChannelIterator<QosPositionKpiReply>>
     * \exception InterruptedException
     * \exception ExecutionException
     * \ingroup functions_dmeapis
     */
    public Future<ChannelIterator<QosPositionKpiReply>> getQosPositionKpiFuture(QosPositionRequest request,
                                                                                String host, int port,
                                                                                long timeoutInMilliseconds) {
        QosPositionKpi qosPositionKpi = new QosPositionKpi(this);
        qosPositionKpi.setRequest(request, host, port, timeoutInMilliseconds);
        return submit(qosPositionKpi);
    }

    /*!
     * Creates a QOS priority session via the DME. Returns a QosPrioritySessionReply.
     * \param request (QosPrioritySessionCreateRequest)
     * \param host (String): Distributed Matching Engine hostname
     * \param port (int): Distributed Matching Engine port
     * \param timeoutInMilliseconds (long)
     * \return Future<QosPrioritySessionReply>
     * \exception InterruptedException
     * \exception ExecutionException
     * \ingroup functions_dmeapis
     */
    public QosPrioritySessionReply qosPrioritySessionCreate(QosPrioritySessionCreateRequest request,
                                                            String host, int port,
                                                            long timeoutInMilliseconds)
            throws ExecutionException, InterruptedException {
        QosPrioritySessionCreate qosPrioritySessionCreate = new QosPrioritySessionCreate(this);
        qosPrioritySessionCreate.setRequest(request, host, port, timeoutInMilliseconds);
        return qosPrioritySessionCreate.call();
    }

    /*!
     * Creates a QOS priority session via the DME. Returns a QosPrioritySessionReply.
     * \param request (QosPrioritySessionCreateRequest)
     * \param timeoutInMilliseconds (long)
     * \return Future<QosPrioritySessionReply>
     * \exception InterruptedException
     * \exception ExecutionException
     * \ingroup functions_dmeapis
     */
    public QosPrioritySessionReply qosPrioritySessionCreate(QosPrioritySessionCreateRequest request,
                                                            long timeoutInMilliseconds)
            throws ExecutionException, InterruptedException {
        QosPrioritySessionCreate qosPrioritySessionCreate = new QosPrioritySessionCreate(this);
        qosPrioritySessionCreate.setRequest(request, host, port, timeoutInMilliseconds);
        return qosPrioritySessionCreate.call();
    }

    /*!
     * Creates a QOS priority session via the DME. Returns a Future.
     * \param request (QosPrioritySessionCreateRequest)
     * \param timeoutInMilliseconds (long)
     * \return Future<QosPrioritySessionReply>
     * \ingroup functions_dmeapis
     */
    public Future<QosPrioritySessionReply> qosPrioritySessionCreateFuture(QosPrioritySessionCreateRequest request,
                                                                          long timeoutInMilliseconds) {
        QosPrioritySessionCreate qosPrioritySessionCreate = new QosPrioritySessionCreate(this);
        qosPrioritySessionCreate.setRequest(request, host, port, timeoutInMilliseconds);
        return submit(qosPrioritySessionCreate);
    }

    /*!
     * Creates a QOS priority session via the DME. Returns a Future.
     * \param request (QosPrioritySessionCreateRequest)
     * \param host (String): Distributed Matching Engine hostname
     * \param port (int): Distributed Matching Engine port
     * \param timeoutInMilliseconds (long)
     * \return Future<QosPrioritySessionReply>
     * \ingroup functions_dmeapis
     */
    public Future<QosPrioritySessionReply> qosPrioritySessionCreateFuture(QosPrioritySessionCreateRequest request,
                                                                          String host, int port,
                                                                          long timeoutInMilliseconds) {
        QosPrioritySessionCreate qosPrioritySessionCreate = new QosPrioritySessionCreate(this);
        qosPrioritySessionCreate.setRequest(request, host, port, timeoutInMilliseconds);
        return submit(qosPrioritySessionCreate);
    }

    /*!
     * Deletes a QOS priority session via the DME. Returns a QosPrioritySessionDeleteReply.
     * \param request (QosPrioritySessionCreateRequest)
     * \param host (String): Distributed Matching Engine hostname
     * \param port (int): Distributed Matching Engine port
     * \param timeoutInMilliseconds (long)
     * \return Future<QosPrioritySessionReply>
     * \exception InterruptedException
     * \exception ExecutionException
     * \ingroup functions_dmeapis
     */
    public QosPrioritySessionDeleteReply qosPrioritySessionDelete(QosPrioritySessionDeleteRequest request,
                                                                  String host, int port,
                                                                  long timeoutInMilliseconds)
            throws ExecutionException, InterruptedException {
        QosPrioritySessionDelete qosPrioritySessionDelete = new QosPrioritySessionDelete(this);
        qosPrioritySessionDelete.setRequest(request, host, port, timeoutInMilliseconds);
        return qosPrioritySessionDelete.call();
    }

    /*!
     * Deletes a QOS priority session via the DME. Returns a QosPrioritySessionDeleteReply.
     * \param request (QosPrioritySessionCreateRequest)
     * \param timeoutInMilliseconds (long)
     * \return Future<QosPrioritySessionReply>
     * \exception InterruptedException
     * \exception ExecutionException
     * \ingroup functions_dmeapis
     */
    public QosPrioritySessionDeleteReply qosPrioritySessionDelete(QosPrioritySessionDeleteRequest request,
                                                                  long timeoutInMilliseconds)
            throws ExecutionException, InterruptedException {
        QosPrioritySessionDelete qosPrioritySessionDelete = new QosPrioritySessionDelete(this);
        qosPrioritySessionDelete.setRequest(request, host, port, timeoutInMilliseconds);
        return qosPrioritySessionDelete.call();
    }

    /*!
     * Deletes a QOS priority session via the DME. Returns a Future.
     * \param request (QosPrioritySessionCreateRequest)
     * \param host (String): Distributed Matching Engine hostname
     * \param port (int): Distributed Matching Engine port
     * \param timeoutInMilliseconds (long)
     * \return Future<QosPrioritySessionReply>
     * \ingroup functions_dmeapis
     */
    public Future<QosPrioritySessionDeleteReply> qosPrioritySessionDeleteFuture(QosPrioritySessionDeleteRequest request,
                                                                                String host, int port,
                                                                                long timeoutInMilliseconds) {
        QosPrioritySessionDelete qosPrioritySessionDelete = new QosPrioritySessionDelete(this);
        qosPrioritySessionDelete.setRequest(request, host, port, timeoutInMilliseconds);
        return submit(qosPrioritySessionDelete);
    }

    /*!
     * Deletes a QOS priority session via the DME. Returns a Future.
     * \param request (QosPrioritySessionCreateRequest)
     * \param timeoutInMilliseconds (long)
     * \return Future<QosPrioritySessionReply>
     * \ingroup functions_dmeapis
     */
    public Future<QosPrioritySessionDeleteReply> qosPrioritySessionDeleteFuture(QosPrioritySessionDeleteRequest request,
                                                                                long timeoutInMilliseconds) {
        QosPrioritySessionDelete qosPrioritySessionDelete = new QosPrioritySessionDelete(this);
        qosPrioritySessionDelete.setRequest(request, host, port, timeoutInMilliseconds);
        return submit(qosPrioritySessionDelete);
    }

    // Combination Convenience methods:

    /*!
     * registerAndFindCloudlet with most defaults filled in.
     * Wrapper function for RegisterClient and FindCloudlet. Same functionality as calling them separately. This API cannot be used for Non-Platform APPs.
     * \param context (android.content.Context)
     * \param organizationName (String)
     * \param applicationName (String)
     * \param appVersion (String)
     * \param location (android.location.Location)
     * \param authToken (String)
     * \param tags (Map<String, String>)
     * \param mode (FindCloudletMode): FindCloudletMode performance rated mode, or proximity mode.
     * \return Future<FindCloudletReply>
     * \ingroup functions_dmeapis
     */
    public Future<FindCloudletReply> registerAndFindCloudlet(final Context context,
                                                             final String organizationName,
                                                             final String applicationName,
                                                             final String appVersion,
                                                             final Location location,
                                                             final String authToken,
                                                             final Map<String, String> tags,
                                                             final FindCloudletMode mode) {
        final MatchingEngine me = this;

        Callable<FindCloudletReply> future = new Callable<FindCloudletReply>() {
            @Override
            public FindCloudletReply call() throws Exception {
                RegisterClientRequest.Builder registerClientRequestBuilder = createDefaultRegisterClientRequest(context, organizationName)
                        .setAppName(applicationName)
                        .setAppVers(appVersion)
                        .setAuthToken(authToken);
                if (tags != null) {
                    registerClientRequestBuilder.putAllTags(tags);
                }
                RegisterClientRequest registerClientRequest = registerClientRequestBuilder.build();

                RegisterClientReply registerClientReply = me.registerClient(registerClientRequest, me.getNetworkManager().getTimeout());

                if (registerClientReply == null) {
                    return null;
                }

                FindCloudletRequest.Builder findCloudletRequestBuilder = createDefaultFindCloudletRequest(context, location);
                if (tags != null) {
                  findCloudletRequestBuilder.putAllTags(tags);
                }
                FindCloudletRequest findCloudletRequest = findCloudletRequestBuilder.build();
                FindCloudletMode useMode = mode;
                if (useMode == null) {
                    useMode = FindCloudletMode.PROXIMITY;
                }
                FindCloudletReply findCloudletReply = me.findCloudlet(findCloudletRequest, me.getNetworkManager().getTimeout(), useMode);

                return findCloudletReply;
            }
        };

        return threadpool.submit(future);
    }

    /*!
     * Register and FindCloudlet to get FindCloudletReply for cloudlet AppInsts info all at once:
     * \ingroup functions_dmeapis
     */
    public Future<FindCloudletReply> registerAndFindCloudlet(final Context context,
                                                             final String organizationName,
                                                             final String applicationName,
                                                             final String appVersion,
                                                             final Location location,
                                                             final String authToken,
                                                             final String uniqueIdType,
                                                             final String uniqueId,
                                                             final Map<String, String> tags,
                                                             final FindCloudletMode mode) {

        final MatchingEngine me = this;

        Callable<FindCloudletReply> future = new Callable<FindCloudletReply>() {
            @Override
            public FindCloudletReply call() throws Exception {
                RegisterClientRequest registerClientRequest = createRegisterClientRequest(context,
                        organizationName, applicationName, appVersion, authToken, uniqueIdType, uniqueId, tags);

                RegisterClientReply registerClientReply = me.registerClient(registerClientRequest, me.getNetworkManager().getTimeout());

                if (registerClientReply == null) {
                    return null;
                }

                FindCloudletRequest.Builder findCloudletRequestBuilder = createDefaultFindCloudletRequest(context, location);
                if (tags != null) {
                    findCloudletRequestBuilder.putAllTags(tags);
                }
                FindCloudletRequest findCloudletRequest = findCloudletRequestBuilder.build();
                FindCloudletMode useMode = mode;
                if (useMode == null) {
                    useMode = FindCloudletMode.PROXIMITY;
                }
                FindCloudletReply findCloudletReply = me.findCloudlet(findCloudletRequest, me.getNetworkManager().getTimeout(), useMode);

                return findCloudletReply;
            }
        };

        return threadpool.submit(future);
    }

    /*!
     * Register and FindCloudlet with DME host and port parameters, to get FindCloudletReply for cloudlet AppInsts info all at once:
     * \ingroup functions_dmeapis
     * \section registerandfindoverrideexample Example
     * \snippet EngineCallTest.java registerandfindoverrideexample
     */
    public Future<FindCloudletReply> registerAndFindCloudlet(final Context context,
                                                             final String host,
                                                             final int port,
                                                             final String organizationName,
                                                             final String applicationName,
                                                             final String appVersion,
                                                             final Location location,
                                                             final String authToken,
                                                             final String uniqueIdType,
                                                             final String uniqueId,
                                                             final Map<String, String> tags,
                                                             final FindCloudletMode mode) {

        final MatchingEngine me = this;

        Callable<FindCloudletReply> future = new Callable<FindCloudletReply>() {
            @Override
            public FindCloudletReply call() throws Exception {
                RegisterClientRequest registerClientRequest = createRegisterClientRequest(context,
                        organizationName, applicationName, appVersion, authToken, uniqueIdType, uniqueId, tags);

                RegisterClientReply registerClientReply = me.registerClient(registerClientRequest,
                        host, port, me.getNetworkManager().getTimeout());

                if (registerClientReply == null) {
                    return null;
                }

                FindCloudletRequest.Builder findCloudletRequestBuilder = createDefaultFindCloudletRequest(context, location);
                if (tags != null) {
                    findCloudletRequestBuilder.putAllTags(tags);
                }
                FindCloudletRequest findCloudletRequest = findCloudletRequestBuilder.build();
                FindCloudletMode useMode = mode;
                if (useMode == null) {
                    useMode = FindCloudletMode.PROXIMITY;
                }
                FindCloudletReply findCloudletReply = me.findCloudlet(findCloudletRequest,
                        host, port, me.getNetworkManager().getTimeout(), useMode, -1);

                return findCloudletReply;
            }
        };

        return threadpool.submit(future);
    }

    /*!
     * Retrieve the app connection manager associated with this MatchingEngine instance.
     * \return AppConnectionManager
     */
    public AppConnectionManager getAppConnectionManager() {
        return mAppConnectionManager;
    }

    NetTest getNetTest() {
        return mNetTest;
    }

    NetTest clearNetTest() {
        return mNetTest = new NetTest();
    }

    public boolean isThreadedPerformanceTest() {
        return threadedPerformanceTest;
    }

    public void setThreadedPerformanceTest(boolean threadedPerformanceTest) {
        this.threadedPerformanceTest = threadedPerformanceTest;
    }

    // Network Wrappers:
    //

    /*!
     * Returns if the bound Data Network for application is currently roaming or not.
     * \return boolean
     */
    @RequiresApi(api = android.os.Build.VERSION_CODES.P)
    public boolean isRoamingData() {
        return mNetworkManager.isRoamingData();
    }

    /*!
     * Returns whether Wifi is enabled on the system or not, independent of Application's network state.
     * \param context (android.content.Context)
     * \return boolean
     */
    public boolean isWiFiEnabled(Context context) {
        WifiManager wifiManager = (WifiManager)context.getSystemService(Context.WIFI_SERVICE);
        return wifiManager.isWifiEnabled();
    }

    // Returns long IPv4 (not IPv6) address, from the WiFiManager.
    long getWifiIp(Context context) {
        int ip = 0;
        if (isWiFiEnabled(context)) {
          // See if it has an IP address:
          WifiManager wifiMgr = (WifiManager)context.getSystemService(WIFI_SERVICE);
          WifiInfo wifiInfo = wifiMgr.getConnectionInfo();
          ip = wifiInfo.getIpAddress();
        }
      return ip;
    }

    /*!
     * Checks if the Carrier + Phone combination supports WiFiCalling. This does not return whether it is enabled.
     * If under roaming conditions, WiFi Calling may disable cellular network data interfaces needed by
     * cellular data only Distributed Matching Engine and Cloudlet network operations.
     *
     * \return boolean
     */
    public boolean isWiFiCallingSupported(CarrierConfigManager carrierConfigManager) {
        return mNetworkManager.isWiFiCallingSupported(carrierConfigManager);
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    void setTokenServerURI(String tokenFollowURI) {
        mTokenServerURI = tokenFollowURI;
    }

    String getTokenServerURI() {
        return mTokenServerURI;
    }

    void setTokenServerToken(String token) {
        mTokenServerToken = token;
    }

    String getTokenServerToken() {
        return mTokenServerToken;
    }


    public boolean isSSLEnabled() {
        return isSSLEnabled;
    }

    public void setSSLEnabled(boolean SSLEnabled) {
        isSSLEnabled = SSLEnabled;
    }

    /*!
     * Helper function to return a channel that handles SSL,
     * or returns a more basic ManagedChannelBuilder.
     * \param host (String)
     * \param port (int)
     * \return ManagedChannel
     */
    ManagedChannel channelPicker(String host, int port, Network network) {
        if (network == null) {
            Log.e(TAG, "Network parameter is missing!");
            throw new IllegalArgumentException("Missing required network parameter: " + network);
        }
        if (host == null) {
            Log.e(TAG, "host parameter is missing!");
            throw new IllegalArgumentException("Missing required host parameter: " + host);
        }

        MobiledgeXSSLSocketFactory mobiledgexSSLSocketFactory = (MobiledgeXSSLSocketFactory)MobiledgeXSSLSocketFactory.getDefault(network);
        if (mobiledgexSSLSocketFactory == null) {
            return null;
        }

        if (isSSLEnabled()) {
            return OkHttpChannelBuilder // Public certs only.
                    .forAddress(host, port)
                    .socketFactory(network.getSocketFactory())
                    .sslSocketFactory(mobiledgexSSLSocketFactory)
                    .build();
        } else {
            Log.e(TAG, "MatchingEngine Communications Channel is set to NOT SECURE. Non-production use only!");
            return ManagedChannelBuilder
                    .forAddress(host, port)
                    .usePlaintext()
                    .build();
        }
    }


}
