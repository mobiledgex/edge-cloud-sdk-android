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

import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Parcel;
import android.os.PersistableBundle;
import androidx.annotation.RequiresApi;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;

import java.sql.Time;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static android.telephony.CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL;

/*!
 * NetworkManager handles Network Interface and Network connectivity utilities
 * \ingroup classes_util
 */
public class NetworkManager extends SubscriptionManager.OnSubscriptionsChangedListener {

    public static final String TAG = "NetworkManager";
    public static NetworkManager mNetworkManager;

    private ConnectivityManager mConnectivityManager;
    private SubscriptionManager mSubscriptionManager;
    private List<SubscriptionInfo> mActiveSubscriptionInfoList;

    private boolean mWaitingForLink = false;
    private final Object mWaitForActiveNetwork = new Object();
    private long mNetworkActiveTimeoutMilliseconds = 5000;
    private final Object mSyncObject = new Object();
    private long mTimeoutInMilliseconds = 10000;

    private Network mNetwork;
    private NetworkRequest mDefaultRequest;

    private ExecutorService mThreadPool;
    private boolean mNetworkSwitchingEnabled = false;
    private boolean mSSLEnabled = true;
    private boolean mAllowSwitchIfNoSubscriberInfo = false;

    // Source: https://developer.android.com/reference/android/telephony/TelephonyManager
    public enum DataNetworkType {
        NETWORK_TYPE_1xRTT(7),
        NETWORK_TYPE_CDMA(4),
        NETWORK_TYPE_EDGE(2),
        NETWORK_TYPE_EHRPD(14),
        NETWORK_TYPE_EVDO_0(5),
        NETWORK_TYPE_EVDO_A(6),
        NETWORK_TYPE_EVDO_B(12),
        NETWORK_TYPE_GPRS(1),
        NETWORK_TYPE_GSM(16),
        NETWORK_TYPE_HSDPA(8),
        NETWORK_TYPE_HSPA(10),
        NETWORK_TYPE_HSPAP(15),
        NETWORK_TYPE_HSUPA(9),
        NETWORK_TYPE_IDEN(11),
        NETWORK_TYPE_IWLAN(18),
        NETWORK_TYPE_LTE(13),
        NETWORK_TYPE_NR(20),
        NETWORK_TYPE_TD_SCDMA(17),
        NETWORK_TYPE_UMTS(3),
        NETWORK_TYPE_UNKNOWN(0);

        private int value;
        public static Map<Integer, DataNetworkType> intMap;

        static {
            intMap = new HashMap<Integer, DataNetworkType>();
            for (DataNetworkType d : DataNetworkType.values()) {
                intMap.put(d.getValue(), d);
            }
        }

        DataNetworkType(int val) {
            this.value = val;
        }
        public int getValue() {
            return value;
        }
    }

    public boolean isNetworkSwitchingEnabled() {
        return mNetworkSwitchingEnabled;
    }

    synchronized public void setNetworkSwitchingEnabled(boolean networkSwitchingEnabled) {
        this.mNetworkSwitchingEnabled = networkSwitchingEnabled;
    }

    /*!
     * Some network operations can only work on a single network type, and must wait for a suitable
     * network. This serializes those calls. The app should create separate queues to manage
     * usage otherwise if a particular parallel use pattern is needed.
     * \param callable (Callable)
     * \param networkRequest (NetworkRequest)
     */
    synchronized void runOnNetwork(Callable callable, NetworkRequest networkRequest)
            throws InterruptedException, ExecutionException {
        if (mNetworkSwitchingEnabled == false) {
            Log.e(TAG, "NetworkManager is disabled.");
            return;
        }

        try {
            switchToNetworkBlocking(networkRequest, false);
            Future<Callable> future = mThreadPool.submit(callable);
            future.get(mTimeoutInMilliseconds, TimeUnit.MILLISECONDS);
            resetNetworkToDefault();
        } catch (TimeoutException timeoutException) {
            throw new ExecutionException(timeoutException);
        }
    }

    public static NetworkManager getInstance(ConnectivityManager connectivityManager, SubscriptionManager subscriptionManager) {
        if (mNetworkManager == null) {
            mNetworkManager = new NetworkManager(connectivityManager, subscriptionManager);
        }
        return mNetworkManager;
    }

    public static NetworkManager getInstance(ConnectivityManager connectivityManager, SubscriptionManager subscriptionManager, ExecutorService executorService) {
        if (mNetworkManager == null) {
            mNetworkManager = new NetworkManager(connectivityManager, subscriptionManager, executorService);
        }
        return mNetworkManager;
    }

    private NetworkManager(ConnectivityManager connectivityManager, SubscriptionManager subscriptionManager) {

        mConnectivityManager = connectivityManager;
        mSubscriptionManager = subscriptionManager;
        mSubscriptionManager.addOnSubscriptionsChangedListener(this);
        mThreadPool = Executors.newSingleThreadExecutor();
    }

    private NetworkManager(ConnectivityManager connectivityManager, SubscriptionManager subscriptionManager, ExecutorService executorService) {
        mConnectivityManager = connectivityManager;
        mSubscriptionManager = subscriptionManager;
        mSubscriptionManager.addOnSubscriptionsChangedListener(this);
        mThreadPool = executorService;
    }

    /*!
     * Listener that gets the current SubscriptionInfo list.
     *
     * throws security exception if the revocable READ_PHONE_STATE (or hasCarrierPrivilages)
     * is missing.
     *
     * \exception SecurityException
     */
    @Override
    synchronized public void onSubscriptionsChanged() throws SecurityException {
        // Store it for inspection later.
        mActiveSubscriptionInfoList = mSubscriptionManager.getActiveSubscriptionInfoList();
    }

    public void setTimeout(long timeoutInMilliseconds) {
        if (timeoutInMilliseconds < 1) {
            throw new IllegalArgumentException("Network Switching Timeout should be greater than 0ms.");
        }
        mTimeoutInMilliseconds = timeoutInMilliseconds;
    }

    public long getTimeout() {
        return mTimeoutInMilliseconds;
    }

    public long getNetworkActiveTimeoutMilliseconds() {
        return mNetworkActiveTimeoutMilliseconds;
    }

    public void setNetworkActiveTimeoutMilliseconds(long networkActiveTimeoutMilliseconds) {
        if (networkActiveTimeoutMilliseconds < 0) {
            throw new IllegalArgumentException("networkActiveTimeoutMilliseconds should be greater than 0ms.");
        }
        this.mNetworkActiveTimeoutMilliseconds = networkActiveTimeoutMilliseconds;
    }

    public NetworkRequest getCellularNetworkRequest() {
        NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        return networkRequest;
    }

    public NetworkRequest getWifiNetworkRequest() {
        NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        return networkRequest;
    }

    public NetworkRequest getBluetoothNetworkRequest() {
        NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_BLUETOOTH)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        return networkRequest;
    }

    public NetworkRequest getEthernetNetworkRequest() {
        NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        return networkRequest;
    }

    public NetworkRequest getWiFiAwareNetworkRequest() {
        NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        return networkRequest;
    }

    /*!
     * Returns the current active network, independent of what the NetworkManager is doing.
     */
    public Network getActiveNetwork() {
        return mConnectivityManager.getActiveNetwork();
    }

    // This Roaming Data value is un-reliable except under a new NetworkCapabilities Key in API 28.
    @RequiresApi(api = android.os.Build.VERSION_CODES.P)
    boolean isRoamingData() {
        Network activeNetwork = mConnectivityManager.getActiveNetwork();
        if (activeNetwork == null) {
            return false;
        }
        NetworkCapabilities caps = mConnectivityManager.getNetworkCapabilities(activeNetwork);
        if (caps == null) {
            return false;
        }

        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);
    }

    /*!
     * Checks if the Carrier + Phone combination supports WiFiCalling. This is a supports value, it
     * does not return whether or not it is enabled.
     */
    boolean isWiFiCallingSupported(CarrierConfigManager carrierConfigManager) {
        PersistableBundle configBundle = carrierConfigManager.getConfig();

        boolean isWifiCalling = configBundle.getBoolean(KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL);
        return isWifiCalling;
    }

    /*!
     * Sets the request for the default network to reset to. It does not modify the network.
     */
    public void setDefaulNetwork(NetworkRequest defaultNetworkRequest) {
        mDefaultRequest = defaultNetworkRequest;
    }

    /*!
     * Reset process level default network.
     */
    public void resetNetworkToDefault() throws InterruptedException, ExecutionException {
        if (mNetworkSwitchingEnabled == false) {
            Log.e(TAG, "NetworkManager is disabled.");
            return;
        }

        if (mDefaultRequest == null) {
            mConnectivityManager.bindProcessToNetwork(null);
        } else {
            try {
                switchToNetworkBlocking(mDefaultRequest, true);
            } catch (TimeoutException timeoutException) {
                throw new ExecutionException(timeoutException);
            }
        }

        NetworkCapabilities networkCapabilities = mConnectivityManager.getNetworkCapabilities(mConnectivityManager.getActiveNetwork());
        logTransportCapabilities(networkCapabilities);
    }

    public boolean isSSLEnabled() {
        return mSSLEnabled;
    }

    synchronized public List<SubscriptionInfo> getActiveSubscriptionInfoList(boolean clone) throws SecurityException {
        if (mActiveSubscriptionInfoList == null) {
             mActiveSubscriptionInfoList = mSubscriptionManager.getActiveSubscriptionInfoList();
        }

        if (clone == false) {
            return mActiveSubscriptionInfoList;
        }

        List<SubscriptionInfo> subInfoList = new ArrayList<>();
        for (SubscriptionInfo info : mActiveSubscriptionInfoList) {
            Parcel p = Parcel.obtain();
            p.writeValue(info);
            p.setDataPosition(0);
            SubscriptionInfo copy = (SubscriptionInfo)p.readValue(SubscriptionInfo.class.getClassLoader());
            p.recycle();
            subInfoList.add(copy);
        }
        return subInfoList;
    }

    public boolean isAllowSwitchIfNoSubscriberInfo() {
        return mAllowSwitchIfNoSubscriberInfo;
    }

    synchronized public void setAllowSwitchIfNoSubscriberInfo(boolean allowSwitchIfNoSubscriberInfo) {
        this.mAllowSwitchIfNoSubscriberInfo = allowSwitchIfNoSubscriberInfo;
    }

    class NetworkSwitcherCallable implements Callable {
        NetworkRequest mNetworkRequest;
        boolean activeListenerAdded = false;
        boolean bindProcess = false;
        final long start = System.currentTimeMillis();

        NetworkSwitcherCallable(NetworkRequest networkRequest) {
            mNetworkRequest = networkRequest;
            this.bindProcess = false;
        }
        NetworkSwitcherCallable(NetworkRequest networkRequest, boolean bindProcess) {
            mNetworkRequest = networkRequest;
            this.bindProcess = bindProcess;
        }
        @Override
        public Network call() throws InterruptedException, NetworkRequestTimeoutException, NetworkRequestNoSubscriptionInfoException {
            if (mNetworkSwitchingEnabled == false) {
                Log.i(TAG, "NetworkManager is disabled.");
                if (mNetwork == null) {
                    mNetwork = mConnectivityManager.getActiveNetwork();
                }

                if (mNetwork != null) {
                    return mNetwork;
                }
                // If there is no active network, attempt to switch despite preference to avoid it.
            }

            // If the target is cellular, and there's no subscriptions, just return.
            // On API < 28, one cannot check at this point in the code. Before making the cellular
            // Request, inspect the activeSubscriptionInfoList for available subscription networks.
            // If there are none, the requested network will very likely not succeed.
            // Early exit if API >= 28.
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                mActiveSubscriptionInfoList = getActiveSubscriptionInfoList(false);
                if (mNetworkRequest.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    mAllowSwitchIfNoSubscriberInfo == false &&
                    (mActiveSubscriptionInfoList == null || mActiveSubscriptionInfoList.size() == 0)) {

                    String msg = "There are no data subscriptions for the requested network switch.";
                    Log.e(TAG, msg);
                    throw new NetworkRequestNoSubscriptionInfoException(msg);
                }
            }

            final ConnectivityManager.OnNetworkActiveListener activeListener = new ConnectivityManager.OnNetworkActiveListener() {
                @Override
                public void onNetworkActive() {
                    synchronized (mWaitForActiveNetwork) {
                        mWaitForActiveNetwork.notify();
                        long elapsed = System.currentTimeMillis() - start;
                        Log.d(TAG, "Network Switch Time Wait total: " + elapsed);
                    }
                }
            };
            try {
                synchronized (mSyncObject) {
                    mWaitingForLink = true;
                }

                ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        Log.i(TAG, "requestNetwork onAvailable(), binding process to network? " + bindProcess);
                        if (bindProcess) {
                            mConnectivityManager.bindProcessToNetwork(network);
                        }
                        activeListenerAdded = true;
                        mConnectivityManager.addDefaultNetworkActiveListener(activeListener);
                        mNetwork = network;
                    }

                    @Override
                    public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
                        Log.d(TAG, "requestNetwork onCapabilitiesChanged(): " + network.toString() + "Capabilities: " + networkCapabilities.toString());
                        logTransportCapabilities(networkCapabilities);
                    }

                    @Override
                    public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                        Log.d(TAG, "requestNetwork onLinkPropertiesChanged(): " + network.toString());
                        Log.i(TAG, " -- linkProperties: " + linkProperties.getRoutes());
                        synchronized (mSyncObject) {
                            mWaitingForLink = false;
                            mSyncObject.notify();
                        }
                    }

                    @Override
                    public void onLosing(Network network, int maxMsToLive) {
                        Log.i(TAG, "requestNetwork onLosing(): " + network.toString());
                    }

                    @Override
                    public void onLost(Network network) {
                        // unbind lost network.
                        if (bindProcess) {
                            mConnectivityManager.bindProcessToNetwork(null);
                        }
                        Log.i(TAG, "requestNetwork onLost(): " + network.toString());
                    }

                };
                mConnectivityManager.requestNetwork(mNetworkRequest, networkCallback);

                // Wait for availability.
                synchronized (mSyncObject) {
                    long timeStart = System.currentTimeMillis();
                    long elapsed;
                    while (mWaitingForLink == true &&
                            (elapsed = System.currentTimeMillis() - timeStart) < mTimeoutInMilliseconds) {
                        mSyncObject.wait(mTimeoutInMilliseconds - elapsed);
                    }
                    if (mWaitingForLink) {
                        // Timed out while waiting for available network.
                        NetworkCapabilities networkCapabilities = mConnectivityManager.getNetworkCapabilities(mConnectivityManager.getActiveNetwork());
                        mConnectivityManager.unregisterNetworkCallback(networkCallback);
                        mNetwork = null;
                        mNetworkRequest = null;
                        logTransportCapabilities(networkCapabilities);

                        // It is possible to start the switch, and find that there are no current networks during the switch.
                        boolean cellRequest = networkCapabilities != null && networkCapabilities.hasCapability(NetworkCapabilities.TRANSPORT_CELLULAR);
                        if (cellRequest) {
                            if (mActiveSubscriptionInfoList == null || mActiveSubscriptionInfoList.size() == 0) {
                                String msg = "There are no data subscriptions for the requested network switch.";
                                Log.e(TAG, msg);
                                throw new NetworkRequestNoSubscriptionInfoException(msg);
                            }
                        }

                        throw new NetworkRequestTimeoutException("NetworkRequest timed out with no availability.");
                    }
                    elapsed = System.currentTimeMillis() - timeStart;
                    Log.i(TAG, "Elapsed time waiting for link: " + elapsed);
                    mNetworkRequest = null;
                }

                // Network is available, and link is up, but may not be active yet.
                if (!mConnectivityManager.isDefaultNetworkActive()) {
                    synchronized (mWaitForActiveNetwork) {
                        mWaitForActiveNetwork.wait(mNetworkActiveTimeoutMilliseconds);
                    }
                }
            } finally {
                if (activeListenerAdded) {
                    mConnectivityManager.removeDefaultNetworkActiveListener(activeListener);
                }
            }

            if (android.os.Build.VERSION.SDK_INT >= 28) {
                if (isRoamingData()) {
                    Log.i(TAG, "Network Roaming Data Status: " + isRoamingData());
                }
            }
            return mNetwork;
        }
    }

    private void logTransportCapabilities(NetworkCapabilities networkCapabilities) {
        if (networkCapabilities == null) {
            Log.w(TAG, " -- Network Capabilities: None/Null!");
            return;
        }
        Log.d(TAG, " -- networkCapabilities: TRANSPORT_CELLULAR: " + networkCapabilities.hasCapability(NetworkCapabilities.TRANSPORT_CELLULAR));

        Log.d(TAG, " -- networkCapabilities: NET_CAPABILITY_TRUSTED: " + networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED));
        Log.d(TAG, " -- networkCapabilities: NET_CAPABILITY_VALIDATED: " + networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
        Log.d(TAG, " -- networkCapabilities: NET_CAPABILITY_INTERNET: " + networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            Log.i(TAG, " -- is Roaming Data: " + isRoamingData());
        } else {
            Log.i(TAG, " -- is Roaming Data: UNKNOWN");
        }
    }

    public boolean isNetworkInternetCellularDataCapable(Network network) {
        boolean hasDataCellCapabilities = false;

        if (mConnectivityManager != null) {
            if (network != null) {
                NetworkCapabilities networkCapabilities = mConnectivityManager.getNetworkCapabilities(network);
                if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    hasDataCellCapabilities = true;
                }
            }
        }
        return hasDataCellCapabilities;
    }

    public boolean isCurrentNetworkInternetCellularDataCapable() {
        boolean hasDataCellCapabilities = false;

        if (mConnectivityManager != null) {
            Network network = mConnectivityManager.getBoundNetworkForProcess();
            if (network == null) {
                network = mConnectivityManager.getActiveNetwork();
            }
            if (network != null) {
                NetworkCapabilities networkCapabilities = mConnectivityManager.getNetworkCapabilities(network);
                if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    hasDataCellCapabilities = true;
                }
            }
        }
        return hasDataCellCapabilities;
    }

    /*!
     * Wrapper function to get, if possible, to a Cellular Data Network connection. This isn't instant. Callback interface.
     */
    synchronized public void requestCellularNetwork(ConnectivityManager.NetworkCallback networkCallback) {
        boolean isCellularData = isCurrentNetworkInternetCellularDataCapable();
        if (isCellularData) {
            return; // Nothing to do, have cellular data
        }

        NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        switchToNetwork(networkRequest, networkCallback);
    }

    synchronized public Network getCellularNetworkBlocking(boolean bindProcess) throws InterruptedException, ExecutionException {
        if (mNetwork == null) {
            mNetwork = mConnectivityManager.getActiveNetwork();
        }
        boolean isCellularData = isCurrentNetworkInternetCellularDataCapable();
        if (isCellularData) {
            return mNetwork;
        }

        try {
            NetworkRequest request = getCellularNetworkRequest();
            mNetwork = switchToNetworkBlocking(request, bindProcess);
        } catch (TimeoutException timeoutException) {
            throw new ExecutionException(timeoutException);
        }
        return mNetwork;
    }

    synchronized public Network getCellularNetworkOrWifiBlocking(boolean bindProcess, String currentMccMnc) throws InterruptedException, ExecutionException {

        if (currentMccMnc != null && !currentMccMnc.isEmpty()) {
            try {
                mNetwork = getCellularNetworkBlocking(bindProcess);
            } catch (ExecutionException ee) {
                // Cellular failed. Try WiFi:
                Log.e(TAG, "Cellular Switch failed. Reason: " + ee.getLocalizedMessage() + ". Trying Wifi... ");
                try {
                    mNetwork = switchToNetworkBlocking(getWifiNetworkRequest(), bindProcess);
                } catch (TimeoutException timeoutException) {
                    throw new ExecutionException(timeoutException); // rethrow.
                }
            }
        } else {
            // Cellular is not available. Let's try wifi:
            Log.i(TAG, "Cellular is not present. Trying Wifi... ");
            try {
                mNetwork = switchToNetworkBlocking(getWifiNetworkRequest(), bindProcess);
            } catch (TimeoutException timeoutException) {
                throw new ExecutionException(timeoutException); // rethrow.
            }
        }

        return mNetwork;
    }

    /*!
     * Switch entire process to a Cellular network type. This is a synchronous call.
     */
    synchronized public Network switchToCellularInternetNetworkBlocking() throws InterruptedException, ExecutionException {
        if (mNetwork == null) {
            mNetwork = mConnectivityManager.getActiveNetwork();
        }
        boolean isCellularData = isCurrentNetworkInternetCellularDataCapable();
        if (isCellularData) {
            return mNetwork;
        }

        try {
            NetworkRequest request = getCellularNetworkRequest();
            mNetwork = switchToNetworkBlocking(request, true);
        } catch (TimeoutException timeoutException) {
            throw new ExecutionException(timeoutException);
        }
        return mNetwork;
    }

    /*!
     * Switch to a particular network type. Returns a Future.
     */
    synchronized public Future<Network> switchToCellularInternetNetworkFuture() {
        NetworkRequest networkRequest = getCellularNetworkRequest();
        Future<Network> cellNetworkFuture;

        cellNetworkFuture = mThreadPool.submit(new NetworkSwitcherCallable(networkRequest, true));
        return cellNetworkFuture;
    }

    /*!
     * Switch to a particular network type in a blocking fashion for synchronous execution blocks.
     */
    synchronized public Network switchToNetworkBlocking(NetworkRequest networkRequest, boolean bindProcess) throws InterruptedException, ExecutionException, TimeoutException {
        Future<Network> networkFuture;

        try {
            networkFuture = mThreadPool.submit(new NetworkSwitcherCallable(networkRequest, bindProcess));
            return networkFuture.get(mTimeoutInMilliseconds, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeoutException) {
            Log.e(TAG, "Cannot switch to network. Reason: " + timeoutException.getLocalizedMessage());
            throw timeoutException;
        }
    }

    /*!
     * Switch entire process to a network using Callbacks. The callback onAvailable(Network) will notify availability.
     * \param networkRequest (NetworkRequest)
     * \param networkCallback (ConnectivityManager.NetworkCallback)
     */
    synchronized public void switchToNetwork(NetworkRequest networkRequest, final ConnectivityManager.NetworkCallback networkCallback) {
        mConnectivityManager.requestNetwork(networkRequest, new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.d(TAG, "requestNetwork onAvailable().");

                mNetwork = network;

                mConnectivityManager.bindProcessToNetwork(network);
                if (networkCallback == null) {
                    networkCallback.onAvailable(network);
                }
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
                logTransportCapabilities(networkCapabilities);
                if (networkCallback == null) {
                    networkCallback.onCapabilitiesChanged(network, networkCapabilities);
                }
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                Log.d(TAG, "requestNetwork onLinkPropertiesChanged()");
                Log.d(TAG, " -- linkProperties: " + linkProperties.getRoutes());
                if (networkCallback == null) {
                    networkCallback.onLinkPropertiesChanged(network, linkProperties);
                }
            }

            @Override
            public void onLosing(Network network, int maxMsToLive) {
                Log.d(TAG, "requestNetwork onLosing()");
                if (networkCallback == null) {
                    networkCallback.onLosing(network, maxMsToLive);
                }
            }

            @Override
            public void onLost(Network network) {
                // unbind from process, lost network.
                mConnectivityManager.bindProcessToNetwork(null);
                Log.d(TAG, "requestNetwork onLost()");
                if (networkCallback == null) {
                    networkCallback.onLost(network);
                }
            }
        });
    }
}
