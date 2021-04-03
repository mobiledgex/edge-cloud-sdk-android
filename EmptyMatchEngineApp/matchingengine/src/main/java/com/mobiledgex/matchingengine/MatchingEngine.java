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
import android.provider.Settings;

import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;

import android.telephony.CarrierConfigManager;
import android.telephony.CellIdentityCdma;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellIdentityTdscdma;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellInfo;
import android.telephony.CellInfoCdma;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellInfoTdscdma;
import android.telephony.CellInfoWcdma;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
import distributed_match_engine.AppClient.BandSelection;
import distributed_match_engine.AppClient.AppOfficialFqdnReply;

import distributed_match_engine.AppClient.DynamicLocGroupRequest;
import distributed_match_engine.AppClient.DynamicLocGroupReply;

import distributed_match_engine.LocOuterClass;
import distributed_match_engine.LocOuterClass.Loc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.okhttp.OkHttpChannelBuilder;

import android.content.pm.PackageInfo;
import android.util.Log;
import android.util.Pair;

import com.google.common.eventbus.AsyncEventBus;
import com.google.common.eventbus.EventBus;
import com.mobiledgex.matchingengine.edgeeventsconfig.ClientEventsConfig;
import com.mobiledgex.matchingengine.edgeeventsconfig.EdgeEventsConfig;
import com.mobiledgex.matchingengine.performancemetrics.NetTest;
import com.mobiledgex.mel.MelMessaging;

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
    FindCloudletReply mFindCloudletReply;
    private VerifyLocationReply mVerifyLocationReply;
    private GetLocationReply mGetLocationReply;
    private DynamicLocGroupReply mDynamicLocGroupReply;
    private AppOfficialFqdnReply mAppOfficialFqdnReply;

    private LocOuterClass.Loc mMatchEngineLocation;

    private boolean isSSLEnabled = true;
    private boolean useOnlyWifi = false;

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
        mEnableEdgeEvents = true;
        mEdgeEventBus = new AsyncEventBus(threadpool);

        if (MelMessaging.isMelEnabled()) {
            // Updates and sends for MEL status:
            MelMessaging.sendForMelStatus(context, getAppName(context));
        }
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
        mEnableEdgeEvents = true;
        mEdgeEventBus = new AsyncEventBus(executorService);

        if (MelMessaging.isMelEnabled()) {
            // Updates and sends for MEL status:
            MelMessaging.sendForMelStatus(context, getAppName(context));
        }
    }


    public boolean isEnableEdgeEvents() {
        return mEnableEdgeEvents;
    }

    public void setEnableEdgeEvents(boolean enableEdgeEvents) {
        this.mEnableEdgeEvents = enableEdgeEvents;
    }

    // Default EdgeEvents config:
    public void setEdgeEventsConfig(EdgeEventsConfig edgeEventsConfig) {
        this.mEdgeEventsConfig = edgeEventsConfig;
    }

    /*!
     * This eventBus instance is a duplicate handler, but for the managed EventBus state machine so
     * it doesn't interfere with DeadEvents handling.
     */
    private EventBus mDefaultEdgeEventsBus;


    private boolean autoMigrateEdgeEventsConnection = true;
    /*!
     * Automatically switched EdgeEventsConnection
     * \return boolean value whether the EdgeEventsConnection is migrated automatically.
     */
    public boolean isAutoMigrateEdgeEventsConnection() {
        return autoMigrateEdgeEventsConnection;
    }
    /*!
     * When you switch AppInsts between Cloudlets, the EdgeEventsConnection should also migrate.
     * If set to false, when notified of a newCLoudlet availability, call "switchedToNewFindCloudlet()
     * to indicate the app has finally migrated to the new cloudlet.
     */
    public void setAutoMigrateEdgeEventsConnection(boolean autoMigrateEdgeEventsConnection) {
        this.autoMigrateEdgeEventsConnection = autoMigrateEdgeEventsConnection;
    }

    /*!
     * Helper util to create a useful config.
     */
    public EdgeEventsConfig createDefaultEdgeEventsConfig() {

        return EdgeEventsConfig.createDefaultEdgeEventsConfig();
    }
    public EdgeEventsConfig createDefaultEdgeEventsConfig(double latencyUpdateIntervalSeconds,
                                                          double locationUpdateIntervalSeconds,
                                                          double latencyThresholdTriggerMs,
                                                          ClientEventsConfig.UpdatePattern updatePattern) {
        return EdgeEventsConfig.createDefaultEdgeEventsConfig(latencyUpdateIntervalSeconds, locationUpdateIntervalSeconds, latencyThresholdTriggerMs, updatePattern);
    }

    /*!
     * startsEdgeEvents() as soon as a FindCloudletReply is FIND_FOUND with the EdgeEventsConfig given.
     * If you want to handle the EdgeEvents with a custom handler, call getEdgeEventsBus(),
     * register your class, and subscribe to either these event objects:
     *
     *   - FindCloudletEvent - new cloudlet found and is available for your app to migrate to when ready.
     *   - ServerEdgeEvent - all raw events.
     *
     * \param edgeEventsConfig a events profile on how to monitor the edgeConnection state. null to use defaults.
     */
    public boolean startEdgeEvents(EdgeEventsConfig edgeEventsConfig) {
        return startEdgeEvents(null, 0, null, edgeEventsConfig);
    }

    // Do not use in production. DME will likely change.
    boolean mAppInitiatedRunEdgeEvents = false;
    synchronized public boolean startEdgeEvents(String dmeHost, int dmePort, Network network, EdgeEventsConfig edgeEventsConfig) {
        mAppInitiatedRunEdgeEvents = true;
        if (edgeEventsConfig == null) {
            Log.w(TAG, "Using default EdgeEventsConfig.");
            this.mEdgeEventsConfig = EdgeEventsConfig.createDefaultEdgeEventsConfig();
        } else {
            this.mEdgeEventsConfig = edgeEventsConfig;
        }
        // This is an exposed path to start/restart EdgeEvents, state check everything.
        if (!validateEdgeEventsConfig(edgeEventsConfig)) {
            return false; // NOT started.
        }

        // Start, if not already, the edgeEvents connection:
        if (mEdgeEventsConnection == null || mEdgeEventsConnection.isShutdown()) {
            mEdgeEventsConnection = getEdgeEventsConnection(dmeHost, dmePort, network, mFindCloudletReply.getEdgeEventsCookie(), edgeEventsConfig);
            Log.i(TAG, "EdgeEventsConnection is now started.");
        } else {
            Log.i(TAG, "EdgeEventsConnection is already running Stop, before starting a new one.");
        }

        return true;
    }

    synchronized public boolean stopEdgeEvents() {
        mAppInitiatedRunEdgeEvents = false;
        if (mEdgeEventsConnection == null || mEdgeEventsConnection.isShutdown()) {
            Log.i(TAG, "EdgeEventsConnection is already stopped.");
            return false;
        }
        mEdgeEventsConnection.close();
        return true;
    }

    /**
     * validate prior to creating a EdgeEventsConnection outside FindCloudlet auto creation.
     * @param edgeEventsConfig
     * @return
     */
    private boolean validateEdgeEventsConfig(EdgeEventsConfig edgeEventsConfig) {
        if (mEnableEdgeEvents == false) {
            Log.w(TAG, "EdgeEvents is set to disabled.");
            return false;
        }

        if (!MatchingEngine.isMatchingEngineLocationAllowed()) {
            Log.w(TAG, "MobiledgeX Location services are disabled. Reduced functionality. EdgeEvents can only receive server push events.");
        }

        if (!mEnableEdgeEvents) {
            Log.w(TAG, "MobiledgeX EdgeEvents are disabled. Reduced functionality.");
            return false;
        }

        if (mFindCloudletReply == null) {
            Log.i(TAG, "No initial find cloudlet found yet.");
            return false;
        }
        if (mFindCloudletReply.getEdgeEventsCookie() == null) {
            Log.e(TAG, "This DME edge server doesn't seem to be compatible.");
            return false;
        }

        if (mFindCloudletReply.getStatus() != FindCloudletReply.FindStatus.FIND_FOUND) {
            Log.e(TAG, "This app is not in known FIND_FOUND state");
            return false;
        }

        return true;
    }

    /*!
     * Gets or re-establishes a connection to the DME, and returns an EdgeEventsConnection singleton.
     * If you want to receive events, register your class that has a @Subscribe annotation with a
     * ServerEdgeEvent method parameter.
     *
     * \param dmeHost
     * \param port
     * \param network
     * \param edgeEventsCookie This events session cookie part of FindClooudletReply for the app's
     *                         resolved edge AppInst.
     * \return a connected EdgeEventsConnection instance
     */
    EdgeEventsConnection getEdgeEventsConnection(String dmeHost, int dmePort, Network network, String edgeEventsCookie, EdgeEventsConfig edgeEventsConfig) {
        if (!mEnableEdgeEvents) {
            Log.d(TAG, "EdgeEvents has been disabled for this MatchingEngine. Enable to receive EdgeEvents states for your app.");
            return null;
        }

        // Clean:
        if (mEdgeEventsConnection != null && !mEdgeEventsConnection.isShutdown()) {
            if (edgeEventsConfig != null) {
                mEdgeEventsConfig = edgeEventsConfig; // For future use, existing connection will keep config.
            }
            return mEdgeEventsConnection;
        }

        // Open:
        mEdgeEventsConnection = new EdgeEventsConnection(this, dmeHost, dmePort, network, edgeEventsConfig);

        // Attach our EventBus instance to use
        mEdgeEventsConnection.setEdgeEventsBus(mEdgeEventBus);

        // app or sdk default, edgeEvents is enabled, so run that config, always.
        mEdgeEventsConnection.runEdgeEvents();

        return mEdgeEventsConnection;
    }

    boolean closeEdgeEventsConnection() {
        if (mEdgeEventsConnection != null) {
            mEdgeEventsConnection.close();
            mEdgeEventsConnection = null;
            return true;
        }
        return false;
    }
    public EdgeEventsConnection getEdgeEventsConnection() {
        return mEdgeEventsConnection;
    }

    /**
     * MatchingEngine contains some long lived resources.
     */
    public void close() {
        if (getEdgeEventsConnection() != null) {
            getEdgeEventsConnection().sendTerminate();
        }

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
    }

    /*!
     * This is an event bus for EdgeEvents.
     * You can specify your own ExecutorService with MatchingEgnine init.
     *
     * If you want to send a response back to the server, call getEdgeEventsConnection()
     * to access Utility functions to help with the response.
     *
     * \return
     * \ingroup functions_dmeutils
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
     */
    public static void setMatchingEngineLocationAllowed(boolean allowMatchingEngineLocation) {
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
    public void setUseWifiOnly(boolean enabled) {
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
    public void setAllowSwitchIfNoSubscriberInfo(boolean allowSwitchIfNoSubscriberInfo) {
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
        mFindCloudletReply = reply;
    }

    synchronized void setDynamicLocGroupReply(DynamicLocGroupReply reply) {
        mDynamicLocGroupReply = reply;
    }

    synchronized void setAppOfficialFqdnReply(AppClient.AppOfficialFqdnReply reply) {
        mAppOfficialFqdnReply = reply;
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

        if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED) {
            int nType = telManager.getDataNetworkType(); // Type Name is not visible.
            NetworkManager.DataNetworkType dataType = NetworkManager.DataNetworkType.intMap.get(nType);
            map.put("DataNetworkType", dataType.name());
        }

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

    /*!
     * Returns the carrier's mcc+mnc which is mapped to a carrier in the backend (ie. 26201 -> TDG).
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
     * Optional Parameter cellular ID. This may be different between Cellular type (LTE, 5G, etc.)
     * \param context (android.content.Context)
     * \return List<Pair<String, Long>>: List of CellInfo simpleNames with the corresponding normalized long CellId. Could
     *         be empty.
     * \throws SecurityException if GET_PHONE_STATE missing.
     * \ingroup functions_dmeutils
     */
    @Deprecated
    public List<Pair<String, Long>> retrieveCellId(Context context) throws SecurityException {
        TelephonyManager telManager = (TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);

        List<Pair<String, Long>> list = new ArrayList<>();
        long cid;
        for (CellInfo cellInfo : telManager.getAllCellInfo()) {
            Pair<String, Long> cellIdentityPair = null;
            if (!cellInfo.isRegistered()) {
                continue;
            }
            try {
                if (Build.VERSION.SDK_INT >= 29 && cellInfo instanceof CellInfoNr) { // Q
                    CellIdentityNr cellIdentityNr = (CellIdentityNr)((CellInfoNr)cellInfo).getCellIdentity();
                    cid = cellIdentityNr.getNci();
                    cellIdentityPair = new Pair(cellIdentityNr.getClass().getSimpleName(), cid);
                } else if (Build.VERSION.SDK_INT >= 29 && cellInfo instanceof CellInfoTdscdma) {
                    CellIdentityTdscdma cellIdentityTdscdma = ((CellInfoTdscdma)cellInfo).getCellIdentity();
                    cid = cellIdentityTdscdma.getCid();
                    cellIdentityPair = new Pair(cellIdentityTdscdma.getClass().getSimpleName(), cid);
                } else if (cellInfo instanceof CellInfoLte) {
                    CellIdentityLte cellIdentityLte = ((CellInfoLte)cellInfo).getCellIdentity();
                    cid = cellIdentityLte.getCi();
                    cellIdentityPair = new Pair(cellIdentityLte.getClass().getSimpleName(), cid);
                } else if (cellInfo instanceof CellInfoGsm) {
                    CellIdentityGsm cellIdentityGsm = ((CellInfoGsm)cellInfo).getCellIdentity();
                    cid = cellIdentityGsm.getCid();
                    cellIdentityPair = new Pair(cellIdentityGsm.getClass().getSimpleName(), cid);
                } else if (cellInfo instanceof CellInfoWcdma) {
                    CellIdentityWcdma cellIdentityWcdma = ((CellInfoWcdma)cellInfo).getCellIdentity();
                    cid = cellIdentityWcdma.getCid();
                    cellIdentityPair = new Pair(cellIdentityWcdma.getClass().getSimpleName(), cid);
                } else if (cellInfo instanceof CellInfoCdma) {
                    CellIdentityCdma cellIdentityCdma = ((CellInfoCdma)cellInfo).getCellIdentity();
                    cid = cellIdentityCdma.getBasestationId();
                    cellIdentityPair = new Pair(cellIdentityCdma.getClass().getSimpleName(), cid);
                }

                if (cellIdentityPair != null) {
                    list.add(cellIdentityPair);
                }
            } catch (NullPointerException npe) {
                continue;
            }
        }

        return list;
    }

    public String HashSha512(String aString) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-512");

        if (aString != null) {
            byte[] encodedHash = digest.digest(aString.getBytes(StandardCharsets.UTF_8));
            StringBuffer sb = new StringBuffer();
            // Construct unsigned byte hex version (lower case).
            for (byte b : encodedHash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }

        return null;
    }

    String getHashedAndroidId(Context context) throws NoSuchAlgorithmException{
        String id;
        id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        return HashSha512(id);
    }

    // Returns a HEX String of a HASHED unique ads identifer. Or null if ID not found. Do not cache value.
    String getUniqueId(Context context) {
        String uuid = null;

        try {
            uuid = getHashedAndroidId(context);
        } catch (NoSuchAlgorithmException nsae) {
            Log.e(TAG, "Hash algorithm missing. Cannot create hashed ID." + nsae.getStackTrace());
            uuid = null;
        }

        return uuid;
    }

    /*!
     * GenerateDmeHostAddress
     * This will generate the dme host name based on GetMccMnc() -> "mcc-mnc.dme.mobiledgex.net".
     * If GetMccMnc fails or returns null, this will return a fallback dme host: "wifi.dme.mobiledgex.net"(this is the EU + TDG DME).
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
        builder.setAuthToken("")
                .setCellId(0);
                return builder;
    }

    /*!
     * \ingroup functions_dmeapis
     */
    public RegisterClientRequest createRegisterClientRequest(Context context, String organizationName,
                                                             String applicationName, String appVersion,
                                                             String authToken,
                                                             int cellId, String uniqueIdType,
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
        PackageInfo pInfo;
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
                .setAuthToken((authToken == null) ? "" : authToken)
                .setCellId(cellId);

        if (tags != null) {
            builder.putAllTags(tags);
        }

        if (uniqueId != null && uniqueId.length() > 0) {
            builder.setUniqueIdType(uniqueIdType); // Let server handle it, should not be null.
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

        List<Pair<String, Long>> ids = retrieveCellId(context);
        long cellId = 0;
        if (ids.size() > 0) {
            // FIXME: Need a preference, as we can't guess here.
            if (ids.size() > 0) {
                cellId = ids.get(0).second;
            }
        }

        VerifyLocationRequest.Builder builder = AppClient.VerifyLocationRequest.newBuilder()
                .setSessionCookie(mSessionCookie)
                .setCarrierName(carrierName)
                .setGpsLocation(aLoc) // Latest token is unknown until retrieved.
                .setCellId((int)cellId);
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

        return FindCloudletRequest.newBuilder()
                .setSessionCookie(mSessionCookie)
                .setCarrierName(getCarrierName(context))
                .setGpsLocation(aLoc)
                .setCellId(0);
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
                .setCarrierName(getCarrierName(context))
                .setCellId(0);
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
                .setGpsLocation(aLoc)
                .setCellId(0);
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
                .setCommType(commType)
                .setCellId(0);
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
                .setLteCategory(lte_category)
                .setCellId(0);

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
        return findCloudlet(request, generateDmeHostAddress(), getPort(), timeoutInMilliseconds, FindCloudletMode.PROXIMITY);
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
      return findCloudlet(request, generateDmeHostAddress(), getPort(), timeoutInMilliseconds, mode);
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
      findCloudlet.setRequest(request, host, port, timeoutInMilliseconds, FindCloudletMode.PROXIMITY);

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
        findCloudlet.setRequest(request, host, port, timeoutInMilliseconds, mode);

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
      findCloudlet.setRequest(request, host, port, timeoutInMilliseconds, FindCloudletMode.PROXIMITY);
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
        findCloudlet.setRequest(request, host, port, timeoutInMilliseconds, mode);
        return submit(findCloudlet);
    }

    /*!
     * verifyLocation validates the client submitted information against known network
     * Makes sure that the user's location is not spoofed based on cellID and gps location.
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
     * \param cellId (int)
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
                                                             final int cellId,
                                                             final Map<String, String> tags,
                                                             final FindCloudletMode mode) {
        final MatchingEngine me = this;

        Callable<FindCloudletReply> future = new Callable<FindCloudletReply>() {
            @Override
            public FindCloudletReply call() throws Exception {
                RegisterClientRequest.Builder registerClientRequestBuilder = createDefaultRegisterClientRequest(context, organizationName)
                        .setAppName(applicationName)
                        .setAppVers(appVersion)
                        .setAuthToken(authToken)
                        .setCellId(cellId);
                if (tags != null) {
                    registerClientRequestBuilder.putAllTags(tags);
                }
                RegisterClientRequest registerClientRequest = registerClientRequestBuilder.build();

                RegisterClientReply registerClientReply = me.registerClient(registerClientRequest, me.getNetworkManager().getTimeout());

                if (registerClientReply == null) {
                    return null;
                }

                FindCloudletRequest.Builder findCloudletRequestBuilder = createDefaultFindCloudletRequest(context, location)
                    .setCellId(cellId);
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
                                                             final int cellId,
                                                             final String uniqueIdType,
                                                             final String uniqueId,
                                                             final Map<String, String> tags,
                                                             final FindCloudletMode mode) {

        final MatchingEngine me = this;

        Callable<FindCloudletReply> future = new Callable<FindCloudletReply>() {
            @Override
            public FindCloudletReply call() throws Exception {
                RegisterClientRequest registerClientRequest = createRegisterClientRequest(context,
                        organizationName, applicationName, appVersion, authToken, cellId, uniqueIdType, uniqueId, tags);

                RegisterClientReply registerClientReply = me.registerClient(registerClientRequest, me.getNetworkManager().getTimeout());

                if (registerClientReply == null) {
                    return null;
                }

                FindCloudletRequest.Builder findCloudletRequestBuilder = createDefaultFindCloudletRequest(context, location)
                        .setCellId(cellId);
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
                                                             final int cellId,
                                                             final String uniqueIdType,
                                                             final String uniqueId,
                                                             final Map<String, String> tags,
                                                             final FindCloudletMode mode) {

        final MatchingEngine me = this;

        Callable<FindCloudletReply> future = new Callable<FindCloudletReply>() {
            @Override
            public FindCloudletReply call() throws Exception {
                RegisterClientRequest registerClientRequest = createRegisterClientRequest(context,
                        organizationName, applicationName, appVersion, authToken, cellId, uniqueIdType, uniqueId, tags);

                RegisterClientReply registerClientReply = me.registerClient(registerClientRequest,
                        host, port, me.getNetworkManager().getTimeout());

                if (registerClientReply == null) {
                    return null;
                }

                FindCloudletRequest.Builder findCloudletRequestBuilder = createDefaultFindCloudletRequest(context, location)
                        .setCellId(cellId);
                if (tags != null) {
                    findCloudletRequestBuilder.putAllTags(tags);
                }
                FindCloudletRequest findCloudletRequest = findCloudletRequestBuilder.build();
                FindCloudletMode useMode = mode;
                if (useMode == null) {
                    useMode = FindCloudletMode.PROXIMITY;
                }
                FindCloudletReply findCloudletReply = me.findCloudlet(findCloudletRequest,
                        host, port, me.getNetworkManager().getTimeout(), useMode);

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
