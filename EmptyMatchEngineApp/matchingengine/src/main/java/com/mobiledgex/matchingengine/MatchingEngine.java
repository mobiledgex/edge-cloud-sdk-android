/**
 * Copyright 2018-2020 MobiledgeX, Inc. All rights and licenses reserved.
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
import android.provider.Settings.Secure;
import androidx.annotation.RequiresApi;
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
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;


import java.util.ArrayList;
import java.util.List;
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


import com.mobiledgex.matchingengine.performancemetrics.NetTest;
import com.mobiledgex.mel.MelMessaging;

import static android.content.Context.TELEPHONY_SUBSCRIPTION_SERVICE;
import static android.content.Context.WIFI_SERVICE;

public class MatchingEngine {
    public static final String TAG = "MatchingEngine";
    public static final String baseDmeHost = "dme.mobiledgex.net";
    public static final String WIFIHOST = "wifi";
    public static final String wifiOnlyDmeHost =  WIFIHOST + "." + baseDmeHost; // Demo mode only.
    private String host = baseDmeHost;
    private NetworkManager mNetworkManager;
    private AppConnectionManager mAppConnectionManager;
    private int port = 50051;

    // A threadpool for all the MatchEngine API callable interfaces:
    final ExecutorService threadpool;

    // State info for engine
    private String mSessionCookie; // TODO: Session Map lookup for multiple Edge Apps.
    private String mTokenServerURI;
    private String mTokenServerToken;

    private RegisterClientRequest mRegisterClientRequest;
    private RegisterClientReply mRegisterClientReply;
    private FindCloudletReply mFindCloudletReply;
    private VerifyLocationReply mVerifyLocationReply;
    private GetLocationReply mGetLocationReply;
    private DynamicLocGroupReply mDynamicLocGroupReply;
    private AppOfficialFqdnReply mAppOfficialFqdnReply;

    private LocOuterClass.Loc mMatchEngineLocation;

    private boolean isSSLEnabled = true;
    private boolean useOnlyWifi = false;

    public enum FindCloudletMode {
        PROXIMITY,
        PERFORMANCE
    }

    Context mContext;
    private NetTest mNetTest;
    private boolean threadedPerformanceTest = false;

    public MatchingEngine(Context context) {
        threadpool = Executors.newSingleThreadExecutor();
        ConnectivityManager connectivityManager = context.getSystemService(ConnectivityManager.class);
        mNetworkManager = NetworkManager.getInstance(connectivityManager, getSubscriptionManager(context));
        mAppConnectionManager = new AppConnectionManager(mNetworkManager, threadpool);
        mContext = context;
        mNetTest = new NetTest();
        if (MelMessaging.isMelEnabled()) {
            // Updates and sends for MEL status:
            MelMessaging.sendForMelStatus(context, getAppName(context));
        }
    }
    public MatchingEngine(Context context, ExecutorService executorService) {
        threadpool = executorService;
        ConnectivityManager connectivityManager = context.getSystemService(ConnectivityManager.class);
        mNetworkManager = NetworkManager.getInstance(connectivityManager, getSubscriptionManager(context), threadpool);
        mAppConnectionManager = new AppConnectionManager(mNetworkManager, threadpool);
        mContext = context;
        mNetTest = new NetTest();
        if (MelMessaging.isMelEnabled()) {
          // Updates and sends for MEL status:
          MelMessaging.sendForMelStatus(context, getAppName(context));
        }
    }

    // Application state Bundle Key.
    public static final String MATCHING_ENGINE_LOCATION_PERMISSION = "MATCHING_ENGINE_LOCATION_PERMISSION";
    private static boolean mMatchingEngineLocationAllowed = false;

    public static boolean isMatchingEngineLocationAllowed() {
        return mMatchingEngineLocationAllowed;
    }

    public static void setMatchingEngineLocationAllowed(boolean allowMatchingEngineLocation) {
        mMatchingEngineLocationAllowed = allowMatchingEngineLocation;
    }

    public boolean isUseWifiOnly() {
        return useOnlyWifi;
    }

    public void setUseWifiOnly(boolean enabled) {
        useOnlyWifi = enabled;
        setNetworkSwitchingEnabled(!useOnlyWifi);
    }

    private SubscriptionManager getSubscriptionManager(Context context) {
        return (SubscriptionManager)context.getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE);
    }

    public boolean isNetworkSwitchingEnabled() {
        return getNetworkManager().isNetworkSwitchingEnabled();
    }

    public void setNetworkSwitchingEnabled(boolean networkSwitchingEnabled) {
        getNetworkManager().setNetworkSwitchingEnabled(networkSwitchingEnabled);
    }

    public boolean isAllowSwitchIfNoSubscriberInfo() {
        return getNetworkManager().isAllowSwitchIfNoSubscriberInfo();
    }

    public void setAllowSwitchIfNoSubscriberInfo(boolean allowSwitchIfNoSubscriberInfo) {
        getNetworkManager().setAllowSwitchIfNoSubscriberInfo(allowSwitchIfNoSubscriberInfo);
    }

    /**
     * Utility function to get the active subscription network provider list for this device as
     * known to the MatchingEngine. If it is empty, the application should use the public cloud
     * instead, as the Distributed Matching Engine may be unavailable (firewalled) from the current
     * network. Calling MatchingEngine APIs in that state will result in a
     * NetworkRequestNoSubscriptionInfoException.
     *
     * @return
     */
    public List<SubscriptionInfo> getActiveSubscriptionInfoList() {
        List<SubscriptionInfo> subs = getNetworkManager().getActiveSubscriptionInfoList(true);
        return subs;
    }

    /**
     * Check if Roaming Data is enabled on the System.
     * @return
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
    void setLastRegisterClientRequest(AppClient.RegisterClientRequest registerRequest) {
        mRegisterClientRequest = registerRequest;
    }

    void setMatchEngineStatus(AppClient.RegisterClientReply status) {
        mRegisterClientReply = status;
    }

    void setGetLocationReply(GetLocationReply locationReply) {
        mGetLocationReply = locationReply;
        mMatchEngineLocation = locationReply.getNetworkLocation();
    }

    void setVerifyLocationReply(AppClient.VerifyLocationReply locationVerify) {
        mVerifyLocationReply = locationVerify;
    }

    void setFindCloudletResponse(AppClient.FindCloudletReply reply) {
        mFindCloudletReply = reply;
    }

    void setDynamicLocGroupReply(DynamicLocGroupReply reply) {
        mDynamicLocGroupReply = reply;
    }

    void setAppOfficialFqdnReply(AppClient.AppOfficialFqdnReply reply) {
        mAppOfficialFqdnReply = reply;
    }

    /**
     * Utility method retrieves current MCC + MNC from system service.
     * @param context
     * @return
     */
    String getMccMnc(Context context) {
        TelephonyManager telManager = (TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);

        int subId = SubscriptionManager.getDefaultDataSubscriptionId();
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
          return null;
        }

        return telManager.createForSubscriptionId(subId).getNetworkOperator();
    }

    /**
     * Utility method retrieves current network CarrierName from system service. Returns "" if carrier name is not found.
     * @param context
     * @return
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

    /**
     * Optional Parameter cellular ID. This may be different between Cellular type (LTE, 5G, etc.)
     * @param context
     * @return Hashmap of CellInfo simpleNames with the corresponding normalized long CellId. Could
     *         be empty.
     * @throws SecurityException if GET_PHONE_STATE missing.
     */
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

    String getUniqueId(Context context) {
        String uuid;
        uuid = Secure.getString(context.getContentResolver(),
                Secure.ANDROID_ID);
        Log.d(TAG, "uuid is " + uuid);
        return uuid;
    }

    /**
     * Returns the MobiledgeX Distributed Match Engine server hostname the SDK client should first
     * contact.
     * @return
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

    public NetworkManager getNetworkManager() {
        return mNetworkManager;
    }

    void setNetworkManager(NetworkManager networkManager) {
        mNetworkManager = networkManager;
    }


    public String getPackageName(Context context) {
        String appName;
        ApplicationInfo appInfo = context.getApplicationInfo();
        return appInfo.packageName;
    }

    /**
     * Returns the nonLocalizedLabel Application Name.
     *
     * @param context
     * @return May return null.
     */
    String getAppName(Context context) {
        String appName;
        ApplicationInfo appInfo = context.getApplicationInfo();
        int stringId = appInfo.labelRes;
        appName = appInfo.nonLocalizedLabel != null ? appInfo.nonLocalizedLabel.toString() : null;

        return stringId == 0 ? appName : context.getString(stringId);
    }

    /**
     * Returns the Application Version.
     *
     * @param context
     * @return May return null.
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

    public void ensureSessionCookie(String sessionCookie) {
        if (sessionCookie == null || sessionCookie.equals((""))) {
            throw new IllegalArgumentException("An unexpired RegisterClient sessionCookie is required.");
        }
    }

    /**
     * Returns a builder for RegisterClientRequest. Call build() after setting
     * additional optional fields like AuthToken or Tags.
     *
     * @param context
     * @param organizationName
     * @return
     * @throws PackageManager.NameNotFoundException
     */
    public RegisterClientRequest.Builder createDefaultRegisterClientRequest(Context context,
                                                                            String organizationName)
            throws PackageManager.NameNotFoundException {

        if (!mMatchingEngineLocationAllowed) {
            Log.e(TAG, "Create RegisterClientRequest disabled. Matching engine is not configured to allow use.");
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

    public RegisterClientRequest createRegisterClientRequest(Context context, String organizationName,
                                                             String applicationName, String appVersion,
                                                             String authToken,
                                                             int cellId, String uniqueIdType,
                                                             String uniqueId, List<AppClient.Tag> tags)
            throws PackageManager.NameNotFoundException
    {
        if (!mMatchingEngineLocationAllowed) {
            Log.e(TAG, "Create RegisterClientRequest disabled. Matching engine is not configured to allow use.");
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
            builder.addAllTags(tags);
        }

        if (uniqueId != null && uniqueId.length() > 0) {
            builder.setUniqueIdType(uniqueIdType); // Let server handle it, should not be null.
            builder.setUniqueId(uniqueId);
        }

        return builder.build();
    }

    public VerifyLocationRequest.Builder createDefaultVerifyLocationRequest(Context context,
                                                             android.location.Location location) {
        if (context == null) {
            throw new IllegalArgumentException("MatchingEngine requires a working application context.");
        }

        ensureSessionCookie();

        if (!mMatchingEngineLocationAllowed) {
            Log.d(TAG, "Create Request disabled. Matching engine is not configured to allow use.");
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

    /**
     * Creates a Default FindCloudletRequest. If VersionName or AppName is missing (test code),
     * the app will need to fill this in before sending to the server.
     * @param context Activity Context
     * @param location GPS location
     * @return
     * @throws PackageManager.NameNotFoundException
     */
    public AppClient.FindCloudletRequest.Builder createDefaultFindCloudletRequest(Context context, Location location) {
        if (!mMatchingEngineLocationAllowed) {
            Log.d(TAG, "Create Request disabled. Matching engine is not configured to allow use.");
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

    public AppClient.GetLocationRequest.Builder createDefaultGetLocationRequest(Context context) {
        if (!mMatchingEngineLocationAllowed) {
            Log.d(TAG, "Create Request disabled. Matching engine is not configured to allow use.");
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

    public AppClient.AppInstListRequest.Builder createDefaultAppInstListRequest(Context context, android.location.Location location) {
        if (!mMatchingEngineLocationAllowed) {
            Log.d(TAG, "Create Request disabled. Matching engine is not configured to allow use.");
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

    public AppClient.DynamicLocGroupRequest.Builder createDefaultDynamicLocGroupRequest(Context context, DynamicLocGroupRequest.DlgCommType commType) {
        if (!mMatchingEngineLocationAllowed) {
            Log.d(TAG, "Create Request disabled. Matching engine is not configured to allow use.");
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

    public AppClient.QosPositionRequest.Builder createDefaultQosPositionRequest(List<QosPosition> requests, int lte_category, BandSelection band_selection) {

        if (!mMatchingEngineLocationAllowed) {
            Log.d(TAG, "Create Request disabled. Matching engine is not configured to allow use.");
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

    private Loc androidLocToMeLoc(android.location.Location loc) {
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

    /**
     * Registers Client using blocking API call.
     * @param request
     * @param timeoutInMilliseconds
     * @return
     * @throws StatusRuntimeException
     * @throws InterruptedException
     * @throws ExecutionException
     */
    public RegisterClientReply registerClient(RegisterClientRequest request,
                                              long timeoutInMilliseconds)
            throws DmeDnsException, StatusRuntimeException, InterruptedException, ExecutionException {
        return registerClient(request, generateDmeHostAddress(), getPort(), timeoutInMilliseconds);
    }
    /**
     * Registers Client using blocking API call. Allows specifying a DME host and port.
     * @param request
     * @param host Distributed Matching Engine hostname
     * @param port Distributed Matching Engine port
     * @param timeoutInMilliseconds
     * @return
     * @throws StatusRuntimeException
     * @throws InterruptedException
     * @throws ExecutionException
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

    public Future<RegisterClientReply> registerClientFuture(RegisterClientRequest request,
                                                            long timeoutInMilliseconds)
            throws DmeDnsException {
        return registerClientFuture(request, generateDmeHostAddress(), getPort(), timeoutInMilliseconds);
    }
    /**
     * Registers device on the MatchingEngine server. Returns a Future.
     * @param request
     * @param host Distributed Matching Engine hostname
     * @param port Distributed Matching Engine port
     * @param timeoutInMilliseconds
     * @return
     */
    public Future<RegisterClientReply> registerClientFuture(RegisterClientRequest request,
                                                            String host, int port,
                                                            long timeoutInMilliseconds) {
        RegisterClient registerClient = new RegisterClient(this);
        registerClient.setRequest(request, host, port, timeoutInMilliseconds);
        return submit(registerClient);
    }

    /**
     * findCloudlet finds the closest cloudlet instance as per request.
     * @param request
     * @param timeoutInMilliseconds
     * @return cloudlet URIs
     * @throws StatusRuntimeException
     * @throws InterruptedException
     * @throws ExecutionException
     */
    public FindCloudletReply findCloudlet(FindCloudletRequest request,
                                          long timeoutInMilliseconds)
            throws DmeDnsException, StatusRuntimeException, InterruptedException, ExecutionException {
        return findCloudlet(request, generateDmeHostAddress(), getPort(), timeoutInMilliseconds, FindCloudletMode.PROXIMITY);
    }
  /**
   * findCloudlet finds the closest cloudlet instance as per request.
   * @param request
   * @param timeoutInMilliseconds
   * @return cloudlet URIs
   * @throws StatusRuntimeException
   * @throws InterruptedException
   * @throws ExecutionException
   */
  public FindCloudletReply findCloudlet(FindCloudletRequest request,
                                        long timeoutInMilliseconds, FindCloudletMode mode)
          throws DmeDnsException, StatusRuntimeException, InterruptedException, ExecutionException {
      return findCloudlet(request, generateDmeHostAddress(), getPort(), timeoutInMilliseconds, mode);
  }

  /**
   * findCloudlet finds the closest cloudlet instance as per request.
   * @param request
   * @param host Distributed Matching Engine hostname
   * @param port Distributed Matching Engine port
   * @param timeoutInMilliseconds
   * @return cloudlet URIs
   * @throws StatusRuntimeException
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
    /**
     * findCloudlet finds the closest cloudlet instance as per request.
     * @param request
     * @param host Distributed Matching Engine hostname
     * @param port Distributed Matching Engine port
     * @param timeoutInMilliseconds
     * @param mode FindCloudletMode to use to find edge cloudlets.
     * @return cloudlet URIs
     * @throws StatusRuntimeException
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

    /**
     * findCloudlet finds the closest cloudlet instance as per request. Returns a Future.
     * @param request
     * @param timeoutInMilliseconds
     * @return cloudlet URIs Future.
     */
    public Future<FindCloudletReply> findCloudletFuture(FindCloudletRequest request,
                                          long timeoutInMilliseconds)
            throws DmeDnsException {
        return findCloudletFuture(request, generateDmeHostAddress(), getPort(), timeoutInMilliseconds, FindCloudletMode.PROXIMITY);
    }

  /**
   * findCloudlet finds the closest cloudlet instance as per request. Returns a Future.
   * @param request
   * @param timeoutInMilliseconds
   * @param mode algorithm to use to find edge cloudlets.
   * @return cloudlet URI Future.
   */
    public Future<FindCloudletReply> findCloudletFuture(FindCloudletRequest request,
                                                        long timeoutInMilliseconds,
                                                        FindCloudletMode mode)
        throws DmeDnsException {
            return findCloudletFuture(request, generateDmeHostAddress(), getPort(), timeoutInMilliseconds, mode);
    }

    /**
     * findCloudletFuture finds the closest cloudlet instance as per request. Returns a Future.
     * @param request
     * @param host Distributed Matching Engine hostname
     * @param port Distributed Matching Engine port
     * @param timeoutInMilliseconds
     * @return cloudlet URI Future.
     */
    public Future<FindCloudletReply> findCloudletFuture(FindCloudletRequest request,
                                                        String host, int port,
                                                        long timeoutInMilliseconds) {
      FindCloudlet findCloudlet = new FindCloudlet(this);
      findCloudlet.setRequest(request, host, port, timeoutInMilliseconds, FindCloudletMode.PROXIMITY);
      return submit(findCloudlet);
    }

  /**
   * findCloudletFuture finds the closest cloudlet instance as per request. Returns a Future.
   * @param request
   * @param host Distributed Matching Engine hostname
   * @param port Distributed Matching Engine port
   * @param timeoutInMilliseconds
   * @param mode algorithm to use to find edge cloudlets.
   * @return cloudlet URI Future.
   */
    public Future<FindCloudletReply> findCloudletFuture(FindCloudletRequest request,
                                                        String host, int port,
                                                        long timeoutInMilliseconds,
                                                        FindCloudletMode mode) {
        FindCloudlet findCloudlet = new FindCloudlet(this);
        findCloudlet.setRequest(request, host, port, timeoutInMilliseconds, mode);
        return submit(findCloudlet);
    }

    /**
     * verifyLocationFuture validates the client submitted information against known network
     * parameters on the subscriber network side.
     * @param request
     * @param timeoutInMilliseconds
     * @return
     * @throws StatusRuntimeException
     * @throws InterruptedException
     * @throws IOException
     * @throws ExecutionException
     */
    public VerifyLocationReply verifyLocation(VerifyLocationRequest request,
                                             long timeoutInMilliseconds)
            throws DmeDnsException, StatusRuntimeException, InterruptedException, IOException,
                   ExecutionException {
        return verifyLocation(request, generateDmeHostAddress(), getPort(), timeoutInMilliseconds);
    }
    /**
     * verifyLocationFuture validates the client submitted information against known network
     * parameters on the subscriber network side.
     * @param request
     * @param host Distributed Matching Engine hostname
     * @param port Distributed Matching Engine port
     * @param timeoutInMilliseconds
     * @return boolean validated or not.
     * @throws StatusRuntimeException
     * @throws InterruptedException
     * @throws IOException
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

    /**
     * verifyLocationFuture validates the client submitted information against known network
     * parameters on the subscriber network side. Returns a future.
     * @param request
     * @param timeoutInMilliseconds
     * @return
     */
    public Future<VerifyLocationReply> verifyLocationFuture(VerifyLocationRequest request,
                                                            long timeoutInMilliseconds)
            throws DmeDnsException {
        return verifyLocationFuture(request, generateDmeHostAddress(), getPort(), timeoutInMilliseconds);
    }
    /**
     * verifyLocationFuture validates the client submitted information against known network
     * parameters on the subscriber network side. Returns a future.
     * @param request
     * @return Future<Boolean> validated or not.
     */
    public Future<VerifyLocationReply> verifyLocationFuture(VerifyLocationRequest request,
                                                            String host, int port,
                                                            long timeoutInMilliseconds) {
        VerifyLocation verifyLocation = new VerifyLocation(this);
        verifyLocation.setRequest(request, host, port, timeoutInMilliseconds);
        return submit(verifyLocation);
    }

    /**
     * addUserToGroup is a blocking call.
     * @param request
     * @param timeoutInMilliseconds
     * @return
     * @throws InterruptedException
     * @throws ExecutionException
     */
    DynamicLocGroupReply addUserToGroup(DynamicLocGroupRequest request,
                                               long timeoutInMilliseconds)
            throws DmeDnsException, InterruptedException, ExecutionException {
        return addUserToGroup(request, generateDmeHostAddress(), getPort(), timeoutInMilliseconds);
    }
    /**
     * addUserToGroup is a blocking call.
     * @param request
     * @param host Distributed Matching Engine hostname
     * @param port Distributed Matching Engine port
     * @param timeoutInMilliseconds
     * @return
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

    /**
     * addUserToGroupFuture
     * @param request
     * @param timeoutInMilliseconds
     * @return
     */
    Future<DynamicLocGroupReply> addUserToGroupFuture(DynamicLocGroupRequest request,
                                                             long timeoutInMilliseconds)
            throws DmeDnsException {
        return addUserToGroupFuture(request, generateDmeHostAddress(), getPort(), timeoutInMilliseconds);
    }
    /**
     * addUserToGroupFuture
     * @param request
     * @param host Distributed Matching Engine hostname
     * @param port Distributed Matching Engine port
     * @param timeoutInMilliseconds
     * @return
     */
    Future<DynamicLocGroupReply> addUserToGroupFuture(DynamicLocGroupRequest request,
                                                             String host, int port,
                                                             long timeoutInMilliseconds) {
        AddUserToGroup addUserToGroup = new AddUserToGroup(this);
        addUserToGroup.setRequest(request, host, port, timeoutInMilliseconds);
        return submit(addUserToGroup);
    }

    /**
     * Retrieve nearby AppInsts for registered application. This is a blocking call.
     * @param request
     * @param timeoutInMilliseconds
     * @return
     * @throws InterruptedException
     * @throws ExecutionException
     */
    public AppInstListReply getAppInstList(AppInstListRequest request,
                                           long timeoutInMilliseconds)
            throws DmeDnsException, InterruptedException, ExecutionException {
        return getAppInstList(request, generateDmeHostAddress(), getPort(), timeoutInMilliseconds);
    }

    /**
     * Retrieve nearby AppInsts for registered application. This is a blocking call.
     * @param request
     * @param timeoutInMilliseconds
     * @return
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


    /**
     * Retrieve nearby AppInsts for registered application. Returns a Future.
     * @param request
     * @param timeoutInMilliseconds
     * @return
     */
    public Future<AppInstListReply> getAppInstListFuture(AppInstListRequest request,
                                                         long timeoutInMilliseconds)
            throws DmeDnsException {
        return getAppInstListFuture(request, generateDmeHostAddress(), getPort(), timeoutInMilliseconds);
    }
    /**
     * Retrieve nearby AppInsts for registered application. Returns a Future.
     * @param request
     * @param host
     * @param port
     * @param timeoutInMilliseconds
     * @return
     */
    public Future<AppInstListReply> getAppInstListFuture(AppInstListRequest request,
                                                         String host, int port,
                                                         long timeoutInMilliseconds) {
        GetAppInstList getAppInstList = new GetAppInstList(this);
        getAppInstList.setRequest(request, host, port, timeoutInMilliseconds);
        return submit(getAppInstList);
    }


    /**
     * Request QOS values from a list of PositionKPIRequests, and returns a stream Iterator of
     * predicted QOS values.
     * @param request
     * @param timeoutInMilliseconds
     * @return
     * @throws InterruptedException
     * @throws ExecutionException
     */
    public ChannelIterator<QosPositionKpiReply> getQosPositionKpi(QosPositionRequest request,
                                                                  long timeoutInMilliseconds)
            throws DmeDnsException, InterruptedException, ExecutionException {
        QosPositionKpi qosPositionKpi = new QosPositionKpi(this);
        qosPositionKpi.setRequest(request,generateDmeHostAddress(), getPort(), timeoutInMilliseconds);

        Log.i(TAG, "DME host is: " + host);
        return qosPositionKpi.call();
    }

    /**
     * Request QOS values from a list of PositionKPIRequests, and returns an asynchronous Future
     * for a stream Iterator of predicted QOS values.
     * @param request
     * @param timeoutInMilliseconds
     * @return
     * @throws InterruptedException
     * @throws ExecutionException
     */
    public Future<ChannelIterator<QosPositionKpiReply>> getQosPositionKpiFuture(QosPositionRequest request,
                                                                  long timeoutInMilliseconds)
            throws DmeDnsException {
        QosPositionKpi qosPositionKpi = new QosPositionKpi(this);
        qosPositionKpi.setRequest(request,generateDmeHostAddress(), getPort(), timeoutInMilliseconds);
        return submit(qosPositionKpi);
    }
    /**
     * Request QOS values from a list of PositionKPIRequests, and returns a stream Iterator of
     * predicted QOS values.
     * @param request
     * @param host
     * @param port
     * @param timeoutInMilliseconds
     * @return
     * @throws InterruptedException
     * @throws ExecutionException
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

    /**
     * Request QOS values from a list of PositionKPIRequests, and returns an asynchronous Future
     * for a stream Iterator of predicted QOS values.
     *
     * @param request
     * @param host
     * @param port
     * @param timeoutInMilliseconds
     * @return
     * @throws InterruptedException
     * @throws ExecutionException
     */
    public Future<ChannelIterator<QosPositionKpiReply>> getQosPositionKpiFuture(QosPositionRequest request,
                                                                                String host, int port,
                                                                                long timeoutInMilliseconds) {
        QosPositionKpi qosPositionKpi = new QosPositionKpi(this);
        qosPositionKpi.setRequest(request, host, port, timeoutInMilliseconds);
        return submit(qosPositionKpi);
    }

    // Combination Convenience methods:

    /**
     * registerAndFindCloudlet with most defaults filled in.
     * @param context
     * @param organizationName
     * @param applicationName
     * @param appVersion
     * @param location
     * @param authToken
     * @param cellId
     * @param tags
     * @param mode FindCloudletMode performance rated mode, or proximity mode.
     * @return
     */
    public Future<FindCloudletReply> registerAndFindCloudlet(final Context context,
                                                             final String organizationName,
                                                             final String applicationName,
                                                             final String appVersion,
                                                             final Location location,
                                                             final String authToken,
                                                             final int cellId,
                                                             final List<AppClient.Tag> tags,
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
                    registerClientRequestBuilder.addAllTags(tags);
                }
                RegisterClientRequest registerClientRequest = registerClientRequestBuilder.build();

                RegisterClientReply registerClientReply = me.registerClient(registerClientRequest, me.getNetworkManager().getTimeout());

                if (registerClientReply == null) {
                    return null;
                }

                FindCloudletRequest.Builder findCloudletRequestBuilder = createDefaultFindCloudletRequest(context, location)
                    .setCellId(cellId);
                if (tags != null) {
                  findCloudletRequestBuilder.addAllTags(tags);
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

    /**
     * Register and FindCloudlet to get FindCloudletReply for cloudlet AppInsts info all at once:
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
                                                             final List<AppClient.Tag> tags,
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
                    findCloudletRequestBuilder.addAllTags(tags);
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

    /**
     * Register and FindCloudlet with DME host and port parameters, to get FindCloudletReply for cloudlet AppInsts info all at once:
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
                                                             final List<AppClient.Tag> tags,
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
                    findCloudletRequestBuilder.addAllTags(tags);
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

    /**
     * Retrieve the app connection manager associated with this MatchingEngine instance.
     * @return
     */
    public AppConnectionManager getAppConnectionManager() {
        return mAppConnectionManager;
    }

    NetTest getNetTest() {
        return mNetTest;
    }

    public boolean isThreadedPerformanceTest() {
        return threadedPerformanceTest;
    }

    public void setThreadedPerformanceTest(boolean threadedPerformanceTest) {
        this.threadedPerformanceTest = threadedPerformanceTest;
    }

    // Network Wrappers:
    //

    /**
     * Returns if the bound Data Network for application is currently roaming or not.
     * @return
     */
    @RequiresApi(api = android.os.Build.VERSION_CODES.P)
    public boolean isRoamingData() {
        return mNetworkManager.isRoamingData();
    }

    /**
     * Returns whether Wifi is enabled on the system or not, independent of Application's network state.
     * @param context
     * @return
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

    /**
     * Checks if the Carrier + Phone combination supports WiFiCalling. This does not return whether it is enabled.
     * If under roaming conditions, WiFi Calling may disable cellular network data interfaces needed by
     * cellular data only Distributed Matching Engine and Cloudlet network operations.
     *
     * @return
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

    /**
     * Helper function to return a channel that handles SSL,
     * or returns a more basic ManagedChannelBuilder.
     * @param host
     * @param port
     * @return
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
            return ManagedChannelBuilder
                    .forAddress(host, port)
                    .usePlaintext()
                    .build();
        }
    }
}
