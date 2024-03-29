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

package com.mobiledgex.sdkdemo;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.location.Location;
import android.os.Bundle;
import android.os.Looper;
import android.preference.PreferenceManager;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionInfo;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.TextView;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.Task;
import com.google.common.eventbus.Subscribe;
import com.mobiledgex.matchingengine.DmeDnsException;
import com.mobiledgex.matchingengine.EdgeEventsConnection;
import com.mobiledgex.matchingengine.MatchingEngine;
import com.mobiledgex.matchingengine.NetworkRequestTimeoutException;
import com.mobiledgex.matchingengine.edgeeventsconfig.EdgeEventsConfig;
import com.mobiledgex.matchingengine.edgeeventsconfig.FindCloudletEvent;
import com.mobiledgex.matchingengine.performancemetrics.NetTest;
import com.mobiledgex.matchingengine.performancemetrics.Site;
import com.mobiledgex.matchingengine.util.RequestPermissions;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import distributed_match_engine.AppClient;
import distributed_match_engine.Appcommon;
import io.grpc.StatusRuntimeException;

public class MainActivity extends AppCompatActivity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "MainActivity";

    private String someText = null;
    private AppCompatActivity ctx = this;

    private RequestPermissions mRpUtil;
    private MatchingEngine me;
    private NetTest netTest;
    private EdgeEventsSubscriber mEdgeEventsSubscriber;

    private AppClient.FindCloudletReply mLastFindCloudlet;
    private FusedLocationProviderClient mFusedLocationClient;

    private int internalPort = 2016;

    private LocationCallback mLocationCallback;
    private LocationRequest mLocationRequest;
    private LocationResult mLastLocationResult;
    private boolean mDoLocationUpdates;

    Location automationFairviewCloudlet = new Location("appProvided");
    Location automationHawkinsCloudlet = new Location("appProvided");
    Location edmontonLoc = new Location("appProvided");
    Location montrealLoc = new Location("appProvided");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /**
         * MatchingEngine APIs require special user approved permissions to READ_PHONE_STATE and
         * one of the following:
         * ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION.
         *
         * The example RequestPermissions utility creates a UI dialog, if needed.
         *
         * You can do this anywhere, MainApplication.onActivityResumed(), or a subset of permissions
         * onResume() on each Activity.
         *
         * Permissions must exist prior to API usage to avoid SecurityExceptions.
         */
        mRpUtil = new RequestPermissions();
        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        mLocationRequest = new LocationRequest();

        // Restore app's matching engine location preference, defaulting to false:
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        PreferenceManager.setDefaultValues(this, R.xml.location_preferences, false);

        boolean matchingEngineLocationAllowed = prefs.getBoolean(getResources()
                .getString(R.string.preference_matching_engine_location_verification),
                false);
        //! [matchingengine_allow_location_usage_gdpr]
        MatchingEngine.setMatchingEngineLocationAllowed(matchingEngineLocationAllowed);
        //! [matchingengine_allow_location_usage_gdpr]

        // Watch allowed preference:
        prefs.registerOnSharedPreferenceChangeListener(this);

        // Client side FusedLocation updates.
        mDoLocationUpdates = true;

        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        automationFairviewCloudlet.setLatitude(50.110922);
        automationFairviewCloudlet.setLongitude(8.682127);

        automationHawkinsCloudlet.setLatitude(53.5511);
        automationHawkinsCloudlet.setLongitude(9.9937);

        edmontonLoc.setLatitude(53.5461); // Edmonton
        edmontonLoc.setLongitude(-113.4938);

        montrealLoc.setLatitude(45.5017); // Montreal
        montrealLoc.setLongitude(-73.5673);

        //! [basic_location_handler_example]
        mLocationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) {
                    return;
                }
                String clientLocText = "";
                mLastLocationResult = locationResult;
                // Post into edgeEvents updater:
                me.getEdgeEventsConnectionFuture()
                        .thenApply(connection -> {
                            if (connection != null) {
                                connection.postLocationUpdate(locationResult.getLastLocation());
                            }
                            return null;
                        });

                for (Location location : locationResult.getLocations()) {
                    // Update UI with client location data
                    clientLocText += "[" + location.toString() + "]";
                }
                TextView tv = findViewById(R.id.client_location_content);
                tv.setText(clientLocText);
            }
        };
        //! [basic_location_handler_example]

        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(view -> doEnhancedLocationVerification());

        // Some test buttons (that run on the UI thread, but should really be on a background thread or completableFuture).
        Button edmontonButton = findViewById(R.id.btnLocationEdmonton);
        edmontonButton.setOnClickListener(v -> {
            // Post location update:
            me.getEdgeEventsConnectionFuture()
                    .thenApply(connection -> {
                        if (connection != null) {
                            connection.postLocationUpdate(automationFairviewCloudlet);
                        }
                        return null;
                    });
        });
        Button montrealButton = findViewById(R.id.btnLocatioMontreal);
        montrealButton.setOnClickListener(v -> {
            // Post into matching engine:
            me.getEdgeEventsConnectionFuture()
                    .thenApply(connection -> {
                        if (connection != null) {
                            connection.postLocationUpdate(automationHawkinsCloudlet);
                        }
                        return null;
                    });
        });


        Toolbar myToolbar = findViewById(R.id.toolbar);
        setSupportActionBar(myToolbar);

        // Open dialog for MobiledgeX MatchingEngine if this is the first time the app is created:
        String firstTimeUsePrefKey = getResources().getString(R.string.preference_first_time_use);
        boolean firstTimeUse = prefs.getBoolean(firstTimeUsePrefKey, true);

        if (firstTimeUse) {
            Intent intent = new Intent(this, FirstTimeUseActivity.class);
            startActivity(intent);
        }

        netTest = new NetTest();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        if (id == R.id.action_settings) {

            // Open "Settings" UI
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);

            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onResume() {
        super.onResume();

        if (mRpUtil.getNeededPermissions(this).size() > 0) {
            // Opens a UI. When it returns, onResume() is called again.
            mRpUtil.requestMultiplePermissions(this);
            return;
        }

        if (me == null) {
            //! [matchingengine_constructor]
            // Permissions must be available. Check permissions upon OnResume(). Create a MobiledgeX MatchingEngine instance.
            me = new MatchingEngine(this);
            //! [matchingengine_constructor]

            //! [enable_edgeevents]
            // Register the class subscribing to EdgeEvents to the EdgeEventsBus (Guava EventBus interface).
            me.setEnableEdgeEvents(true); // default is true.
            //! [enable_edgeevents]

            //! [edgeevents_subscriber_setup_example]
            mEdgeEventsSubscriber = new EdgeEventsSubscriber();
            me.getEdgeEventsBus().register(mEdgeEventsSubscriber);

            // set a default config.
            // There is also a parameterized version to further customize.
            EdgeEventsConfig backgroundEdgeEventsConfig = me.createDefaultEdgeEventsConfig();
            backgroundEdgeEventsConfig.latencyTestType = NetTest.TestType.CONNECT;

            // This is the internal port, that has not been remapped to a public port for a particular appInst.
            backgroundEdgeEventsConfig.latencyInternalPort = 0; // 3765; // 0 will favor the first TCP port if found for connect test.
            // Latency config. There is also a very similar location update config.
            backgroundEdgeEventsConfig.latencyUpdateConfig.maxNumberOfUpdates = 0; // Default is 0, which means test forever.
            backgroundEdgeEventsConfig.latencyUpdateConfig.updateIntervalSeconds = 7; // The default is 30.
            backgroundEdgeEventsConfig.latencyThresholdTrigger = 186;
            //! [edgeevents_subscriber_setup_example]

            //! [startedgeevents_example]
            me.startEdgeEventsFuture(backgroundEdgeEventsConfig);
            //! [startedgeevents_example]
        }

        if (mDoLocationUpdates) {
            startLocationUpdates();
        }
    }

    //! [me_cleanup]
    /*!
     * \section meconstructor_example Cleanup
     * \snipppet MainActivity.java meconstructor_example
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (me != null) {
            me.getEdgeEventsBus().unregister(mEdgeEventsSubscriber);
            me.close();
            me = null;
        }
        mEdgeEventsSubscriber = null;
        ctx = null;
    }
    // [me_cleanup]


    //! [edgeevents_subscriber_template]
    // (Guava EventBus Interface)
    // This class encapsulates what an app might implement to watch for edge events. Not every event
    // needs to be implemented. If you just want FindCloudlet, just @Subscribe to FindCloudletEvent.
    //
    class EdgeEventsSubscriber {
        // Subscribe to error handlers!
        @Subscribe
        public void onMessageEvent(EdgeEventsConnection.EdgeEventsError error) {
            Log.d(TAG, "EdgeEvents error reported, reason: " + error);

            someText = error.toString();
            updateText(ctx, someText);
            // Check the App's EdgeEventsConfig and logs.
        }

        // Subscribe to FindCloudletEvent updates to appInst. Reasons to do so include
        // the AppInst Health and Latency spec for this application.
        @Subscribe
        public void onMessageEvent(FindCloudletEvent findCloudletEvent) {
            Log.i(TAG, "Cloudlet update, reason: " + findCloudletEvent.trigger);

            // Connect to new Cloudlet in the event here, preferably in a background task.
            Log.i(TAG, "Cloudlet: " + findCloudletEvent.newCloudlet);

            someText = findCloudletEvent.newCloudlet.toString();
            updateText(ctx, someText);

            // If MatchingEngine.setAutoMigrateEdgeEventsConnection() has been set to false,
            // let MatchingEngine know with switchedToNextCloudlet() so the new cloudlet can
            // maintain the edge connection.
        }

        // Subscribe to ServerEdgeEvents!
        //
        // Optional. If you set this event handler, the app will take ownership of raw
        // events off the EdgeEventBus so a custom handler can be written in to fit your application
        // use case better.
        //
        // To optionally post messages to the DME, use MatchingEngine's EdgeEventsConnection.
        //@Subscribe
        public void onMessageEvent(AppClient.ServerEdgeEvent event) {
            someText = event.toString();
            updateText(ctx, someText);
            switch (event.getEventType()) {
                case EVENT_INIT_CONNECTION:
                    System.out.println("Received Init response: " + event);
                    break;
                case EVENT_APPINST_HEALTH:
                    System.out.println("Received: AppInst Health: " + event);
                    handleAppInstHealth(event);
                    break;
                case EVENT_CLOUDLET_STATE:
                    System.out.println("Received: Cloutlet State event: " + event);
                    handleCloudletState(event);
                    break;
                case EVENT_CLOUDLET_MAINTENANCE:
                    System.out.println("Received: Cloutlet Maintenance event." + event);
                    handleCloudletMaintenance(event);
                    break;
                case EVENT_LATENCY_PROCESSED:
                    System.out.println("Received: Latency has been processed on server: " + event);
                    break;
                case EVENT_LATENCY_REQUEST:
                    System.out.println("Received: Latency has been requested to be tested (client perspective): " + event);
                    handleLatencyRequest(event);
                    break;
                case EVENT_CLOUDLET_UPDATE:
                    System.out.println("Received: Server pushed a new FindCloudletReply to switch to: " + event);
                    handleFindCloudletServerPush(event);
                    break;
                case EVENT_ERROR:
                    Log.d(TAG,"Received: An edgeEvents error: " + event.getErrorMsg());
                    break;
                case EVENT_UNKNOWN:
                    System.out.println("Received UnknownEvent.");
                    break;
                default:
                    System.out.println("Event Received: " + event.getEventType());
            }
        }
        //! [edgeevents_subscriber_template]


        /**
         * These are app specific handlers for the app to make effective.
         */

        /*!
         * \section edgeevents_subscriber_template Example FindCloudlet Handler
         * \snippet MainActivity.java edgeevents_subscriber_template
         */
        void handleFindCloudletServerPush(AppClient.ServerEdgeEvent event) {
            // In a real app:
            // Sync any user app data
            // switch servers
            // restore state to continue.

            // Just print:
            AppClient.FindCloudletReply reply = event.getNewCloudlet();

            HashMap<Integer, Appcommon.AppPort> ports = me.getAppConnectionManager().getTCPMap(mLastFindCloudlet);
            Appcommon.AppPort aPort = ports.get(internalPort);
            if (aPort == null) {
                Log.e(TAG, "wrong port configuration.");
                return;
            }
            int publicPort = aPort.getPublicPort();
            String url = me.getAppConnectionManager().createUrl(reply, aPort, publicPort, "https", "");

            someText += "New FindCloudlet path is: " + url;

            // And set it for use later.
            mLastFindCloudlet = reply;
        }

        /*!
         * \section edgeevents_subscriber_template Example AppInstHealthHandler
         * \snippet MainActivity.java edgeevents_subscriber_template
         */
        void handleAppInstHealth(AppClient.ServerEdgeEvent event) {
            if (event.getEventType() != AppClient.ServerEdgeEvent.ServerEventType.EVENT_APPINST_HEALTH) {
                return;
            }

            switch (event.getHealthCheck()) {
                case HEALTH_CHECK_ROOTLB_OFFLINE:
                case HEALTH_CHECK_SERVER_FAIL:
                    doEnhancedLocationVerification();
                    break;
                case HEALTH_CHECK_OK:
                    System.out.println("AppInst Health is OK");
                    break;
                case UNRECOGNIZED:
                    // fall through
                default:
                    System.out.println("AppInst Health event: " + event.getHealthCheck());
            }
        }

        /*!
         * \section edgeevents_subscriber_template Example Cloudlet Maintenance Handler
         * \snippet MainActivity.java edgeevents_subscriber_template
         */
        void handleCloudletMaintenance(AppClient.ServerEdgeEvent event) {
            if (event.getEventType() != AppClient.ServerEdgeEvent.ServerEventType.EVENT_CLOUDLET_MAINTENANCE) {
                return;
            }

            switch (event.getMaintenanceState()) {
                case NORMAL_OPERATION:
                    System.out.println("Maintenance state is all good!");
                    break;
                default:
                    System.out.println("Server maintenance: " + event.getMaintenanceState());
            }
        }

        /*!
         * \section edgeevents_subscriber_template Example Cloudlet State Handler
         * \snippet MainActivity.java edgeevents_subscriber_template
         */
        void handleCloudletState(AppClient.ServerEdgeEvent event) {
            if (event.getEventType() != AppClient.ServerEdgeEvent.ServerEventType.EVENT_CLOUDLET_STATE) {
                return;
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
                    // Timer Retry or just retry.
                    doEnhancedLocationVerification();
                    break;
                case CLOUDLET_STATE_NEED_SYNC:
                    System.out.println("Cloudlet data needs to sync.");
                    break;
                default:
                    System.out.println("Not handled");
            }
        }

        /*!
         * Example of a custom handler for EVENT_LATENCY_REQUEST message.
         *
         * Alternatively, submit a custom task to the Tasks API, which uses the previously submitted
         * EdgeEventsConfig. See EdgeEventsConnection APIs:
         *  - addEdgeEventsIntervalTask
         *  - removeEdgeEventsIntervalTask
         */
        void handleLatencyRequest(AppClient.ServerEdgeEvent event) {
            if (event.getEventType() != AppClient.ServerEdgeEvent.ServerEventType.EVENT_LATENCY_REQUEST) {
                return;
            }
            CompletableFuture<Void> latencyTestFuture = CompletableFuture.runAsync(() -> {
                try {
                    // NetTest
                    // Local copy:
                    NetTest netTest = new NetTest();

                    // Need a connected AppInst that the app is aware of to test with this code.
                    if (mLastFindCloudlet == null) {
                        return;
                    }

                    // Assuming some knowledge of your own internal un-remapped server port
                    // discover, and test with the PerformanceMetrics API:
                    int publicPort;
                    HashMap<Integer, Appcommon.AppPort> ports = me.getAppConnectionManager().getTCPMap(mLastFindCloudlet);
                    Appcommon.AppPort anAppPort = ports.get(internalPort);
                    if (anAppPort == null) {
                        System.out.println("Your expected server (or port) doesn't seem to be here!");
                    }

                    // Test with default network in use:
                    publicPort = anAppPort.getPublicPort();
                    String host = me.getAppConnectionManager().getHost(mLastFindCloudlet, anAppPort);

                    Site site = new Site(getApplicationContext(), NetTest.TestType.CONNECT, 5, host, publicPort);
                    netTest.addSite(site);
                    netTest.testSites(netTest.TestTimeoutMS); // Test the one we just added.

                    if (me.isShutdown()) {
                        return;
                    }

                    me.getEdgeEventsConnection().postLatencyUpdate(netTest.getSite(host),
                            mLastLocationResult == null ? null : mLastLocationResult.getLastLocation());

                    // Post with Ping Util:
                    me.getEdgeEventsConnection().testPingAndPostLatencyUpdate(mLastLocationResult.getLastLocation());

                    // Post with Connect Util:
                    me.getEdgeEventsConnection().testConnectAndPostLatencyUpdate(mLastLocationResult.getLastLocation());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        stopLocationUpdates();
        if (me != null) {
            me.close();
            me = null;
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle savedInstanceState) {
        super.onSaveInstanceState(savedInstanceState);
        savedInstanceState.putBoolean(MatchingEngine.MATCHING_ENGINE_LOCATION_PERMISSION, MatchingEngine.isMatchingEngineLocationAllowed());
    }

    @Override
    public void onRestoreInstanceState(@NonNull Bundle restoreInstanceState) {
        super.onRestoreInstanceState(restoreInstanceState);
        MatchingEngine.setMatchingEngineLocationAllowed(restoreInstanceState.getBoolean(MatchingEngine.MATCHING_ENGINE_LOCATION_PERMISSION));
    }

    /**
     * See documentation for Google's FusedLocationProviderClient for additional usage information.
     */
    private void startLocationUpdates() {
        // As of Android 23, permissions can be asked for while the app is still running.
        if (mRpUtil.getNeededPermissions(this).size() > 0) {
            return;
        }

        try {
            if (mFusedLocationClient == null) {
                mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
            }
            mFusedLocationClient.requestLocationUpdates(
                    mLocationRequest,
                    mLocationCallback,
                    Looper.getMainLooper() /* Looper */);
        } catch (SecurityException se) {
            Log.e(TAG, "SecurityException message: " + se.getLocalizedMessage());
            se.printStackTrace();
            Log.i(TAG, "App should Request location permissions during onResume() or onCreate().");
        }
    }

    private void stopLocationUpdates() {
        mFusedLocationClient.removeLocationUpdates(mLocationCallback);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        final String prefKeyAllowMatchingEngine = getResources().getString(R.string.preference_matching_engine_location_verification);

        if (key.equals(prefKeyAllowMatchingEngine)) {
            boolean locationAllowed = sharedPreferences.getBoolean(prefKeyAllowMatchingEngine, false);
            MatchingEngine.setMatchingEngineLocationAllowed(locationAllowed);
        }
    }

    public void doEnhancedLocationVerification() throws SecurityException {
        final AppCompatActivity ctx = this;

        // Run in the background and post text results to the UI thread.
        mFusedLocationClient.getLastLocation().addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null) {
                doEnhancedLocationUpdateInBackground(task, ctx);
            } else {
                Log.w(TAG, "getLastLocation:exception", task.getException());
                someText = "Last location not found, or has never been used. Location cannot be verified using 'getLastLocation()'. " +
                        "Use the requestLocationUpdates() instead if applicable for location verification.";
                TextView tv = findViewById(R.id.mobiledgex_verified_location_content);
                tv.setText(someText);
            }
        });
    }

    private void runFlow(Task<Location> locationTask, AppCompatActivity ctx) {
        Location location = locationTask.getResult();
        if (location == null) {
            Log.e(TAG, "Missing location. Cannot update.");
            return;
        }
        // Location found. Create a request:
        try {
            someText = "";
            // Switch entire process over to cellular for application use.
            //mMatchingEngine.getNetworkManager().switchToCellularInternetNetworkBlocking();
            //String adId = mMatchingEngine.GetHashedAdvertisingID(ctx);

            // If no carrierName, or active Subscription networks, the app should use the public cloud instead.
            List<SubscriptionInfo> subList = me.getActiveSubscriptionInfoList();
            if (subList != null && subList.size() > 0) {
                for (SubscriptionInfo info: subList) {
                    CharSequence carrierName = info.getCarrierName();
                    if (carrierName != null && carrierName.equals("Android")) {
                        someText += "Emulator Active Subscription Network: " + info.toString() + "\n";
                    } else {
                        someText += "Active Subscription network: " + info.toString() + "\n";
                    }
                }
                //mMatchingEngine.setNetworkSwitchingEnabled(true);
            } else {
                // This example will continue to execute anyway, as Demo DME may still be reachable to discover nearby edge cloudlets.
                someText += "No active cellular networks: app should use public cloud instead of the edgecloudlet at this time.\n";
            }

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
            boolean locationVerificationAllowed = prefs.getBoolean(getResources().getString(R.string.preference_matching_engine_location_verification), false);

            //String carrierName = mMatchingEngine.getCarrierName(ctx); // Regular use case
            String carrierName = "TELUS";                                         // Override carrierName
            if (carrierName == null) {
                someText += "No carrier Info!\n";
            }

            // It's possible the Generated DME DNS host doesn't exist yet for your SIM.
            String dmeHostAddress;
            try {
                dmeHostAddress = me.generateDmeHostAddress();
                someText += "(e)SIM card based DME address: " + dmeHostAddress + "\n";
            } catch (DmeDnsException dde){
                someText += dde.getMessage();
                // Here, being unable to register to the Edge infrastructure, app should
                // fall back to public cloud server. Edge is not available.
                // For Demo app, we use the wifi dme server to continue to MobiledgeX.
                dmeHostAddress = MatchingEngine.wifiOnlyDmeHost;
            }
            dmeHostAddress = "us-qa." + MatchingEngine.baseDmeHost;
            //me.setUseWifiOnly(true);
            me.setSSLEnabled(true);
            //mMatchingEngine.setNetworkSwitchingEnabled(true);
            //dmeHostAddress = mMatchingEngine.generateDmeHostAddress();
            //dmeHostAddress = "192.168.1.172";

            int port = me.getPort(); // Keep same port.

            String orgName = "automation_dev_org"; // Always supplied by application, and in the MobiledgeX web admin console.
            // For illustration, the matching engine can be used to programmatically get the name of your application details
            // so it can go to the correct appInst version. That AppInst on the server side must match the application
            // version or else it won't be found and cannot be used.
            String appName = "automation-sdk-porttest"; // AppName must be added to the MobiledgeX web admin console.
            String appVers = "1.0"; // override the version of that known registered app.

            // Use createDefaultRegisterClientRequest() to get a Builder class to fill in optional parameters
            // like AuthToken or Tag key value pairs.
            AppClient.RegisterClientRequest registerClientRequest =
                    me.createDefaultRegisterClientRequest(ctx, orgName)
                            //.setCarrierName("cerust")
                            .setAppName(appName)
                            .setAppVers(appVers)
                            .build();
            Log.i(TAG, "registerclient request is " + registerClientRequest);

            // This exercises a threadpool that can have a dependent call depth larger than 1
            AppClient.RegisterClientReply registerClientReply;
            Future<AppClient.RegisterClientReply> registerClientReplyFuture =
                    me.registerClientFuture(registerClientRequest,
                            dmeHostAddress, port, 10000);
            registerClientReply = registerClientReplyFuture.get();

            Log.i(TAG, "RegisterReply status is " + registerClientReply.getStatus());

            if (registerClientReply.getStatus() != AppClient.ReplyStatus.RS_SUCCESS) {
                someText += "Registration Failed. Error: " + registerClientReply.getStatus();
                return;
            }

            // Find the closest cloudlet for your application to use. (Blocking call, or use findCloudletFuture)
            // There is also createDefaultFindClouldletRequest() to get a Builder class to fill in optional parameters.
            AppClient.FindCloudletRequest findCloudletRequest =
                    me.createDefaultFindCloudletRequest(ctx, automationHawkinsCloudlet) // location)
                            .setCarrierName("")
                            .build();
            AppClient.FindCloudletReply closestCloudlet = me.findCloudlet(findCloudletRequest,
                    dmeHostAddress, port, 10000);
            Log.i(TAG, "closest Cloudlet is " + closestCloudlet);
            mLastFindCloudlet = closestCloudlet;
            //me.startEdgeEventsFuture(me.createDefaultEdgeEventsConfig());

            if (closestCloudlet.getStatus() != AppClient.FindCloudletReply.FindStatus.FIND_FOUND) {
                someText += "Cloudlet not found!";
                return;
            }

            // This is a legal point to keep posting edgeEvents updates, as the EdgeEventBus
            // should now be initalized, unless disabled.

            registerClientReplyFuture =
                    me.registerClientFuture(registerClientRequest,
                            dmeHostAddress, port, 10000);
            registerClientReply = registerClientReplyFuture.get();
            Log.i(TAG, "Register status: " + registerClientReply.getStatus());
            AppClient.VerifyLocationRequest verifyRequest =
                    me.createDefaultVerifyLocationRequest(ctx, location)
                            .build();
            Log.i(TAG, "verifyRequest is " + verifyRequest);

            if (verifyRequest != null) {
                // Location Verification (Blocking, or use verifyLocationFuture):
                AppClient.VerifyLocationReply verifiedLocation =
                        me.verifyLocation(verifyRequest, dmeHostAddress, port, 10000);
                Log.i(TAG, "VerifyLocationReply is " + verifiedLocation);

                someText += "[Location Verified: Tower: " + verifiedLocation.getTowerStatus() +
                        ", GPS LocationStatus: " + verifiedLocation.getGpsLocationStatus() +
                        ", Location Accuracy: " + verifiedLocation.getGpsLocationAccuracyKm() + " ]\n";
                List<distributed_match_engine.Appcommon.AppPort> ports = closestCloudlet.getPortsList();
                String portListStr = "";
                boolean first = true;
                String appPortFormat = "{Protocol: %d, Container Port: %d, External Port: %d, Path Prefix: '%s'}";
                for (Appcommon.AppPort aPort : ports) {
                    if (!first) {
                        portListStr += ", ";

                    }
                    portListStr += String.format(Locale.getDefault(), appPortFormat,
                            aPort.getProto().getNumber(),
                            aPort.getInternalPort(),
                            aPort.getPublicPort(),
                            aPort.getEndPort());

                    String host = aPort.getFqdnPrefix() + closestCloudlet.getFqdn();
                    int knownPort = 2015;
                    int serverPort = aPort.getPublicPort() == 0 ? knownPort : aPort.getPublicPort();

                    OkHttpClient client = new OkHttpClient(); //mMatchingEngine.getAppConnectionManager().getHttpClient(10000).get();

                    // Our example server might not like random connections to non-existing /test.
                    String api = serverPort == knownPort ? "/test" : "";
                    Response response = null;
                    try {
                        Request request = new Request.Builder()
                                .url("http://" + host + ":" + serverPort + api)
                                .build();
                        response = client.newCall(request).execute();
                        someText += "[Test Server response: " + response.toString() + "]\n";
                    } catch (IOException | IllegalStateException e) {
                        someText += "[Error connecting to host: " + host + ", port: " + serverPort + ", api: " + api + ", Reason: " + e.getMessage() + "]\n";
                    } finally {
                        if (response != null) {
                            response.body().close();
                        }
                    }

                    // Test from a particular network path. Here, the active one is Cellular since we switched the whole process over earlier.
                    Site site = new Site(me.getNetworkManager().getActiveNetwork(), NetTest.TestType.CONNECT, 5, host, serverPort);
                    netTest.addSite(site);
                }

                someText += "[Cloudlet App Ports: [" + portListStr + "]\n";

                String appInstListText = "";
                AppClient.AppInstListRequest appInstListRequest =
                        me.createDefaultAppInstListRequest(ctx, location).build();

                AppClient.AppInstListReply appInstListReply = me.getAppInstList(
                        appInstListRequest, dmeHostAddress, port, 10000);

                for (AppClient.CloudletLocation cloudletLocation : appInstListReply.getCloudletsList()) {
                    String location_carrierName = cloudletLocation.getCarrierName();
                    String location_cloudletName = cloudletLocation.getCloudletName();
                    double location_distance = cloudletLocation.getDistance();

                    appInstListText += "[CloudletLocation: CarrierName: " + location_carrierName;
                    appInstListText += ", CloudletName: " + location_cloudletName;
                    appInstListText += ", Distance: " + location_distance;
                    appInstListText += " , AppInstances: [";
                    for (AppClient.Appinstance appinstance : cloudletLocation.getAppinstancesList()) {
                        appInstListText += "Name: " + appinstance.getAppName()
                                + ", Version: " + appinstance.getAppVers()
                                + ", FQDN: " + appinstance.getFqdn()
                                + ", Ports: " + appinstance.getPortsList().toString();

                    }
                    appInstListText += "]]\n";
                }
                if (!appInstListText.isEmpty()) {
                    someText += appInstListText;
                }
            } else {
                someText = "Cannot create request object.\n";
                if (!locationVerificationAllowed) {
                    someText += " Reason: Enhanced location is disabled.\n";
                }
            }

            someText += "[Is WiFi Enabled: " + me.isWiFiEnabled(ctx) + "]\n";

            if (android.os.Build.VERSION.SDK_INT >= 28) {
                someText += "[Is Roaming Data Enabled: " + me.isRoamingData() + "]\n";
            } else {
                someText += "[Roaming Data status unknown.]\n";
            }

            CarrierConfigManager carrierConfigManager = ctx.getSystemService(CarrierConfigManager.class);
            someText += "[Enabling WiFi Calling could disable Cellular Data if on a Roaming Network!\nWiFi Calling  Support Status: "
                    + me.isWiFiCallingSupported(carrierConfigManager) + "]\n";


            // Background thread. Post update to the UI thread:
            updateText(ctx, someText);

            // Set network back to last default one, if desired:
            me.getNetworkManager().resetNetworkToDefault();
        } catch (/*DmeDnsException |*/ ExecutionException | StatusRuntimeException e) {
            Log.e(TAG, e.getMessage());
            Log.e(TAG, Log.getStackTraceString(e));
            if (e.getCause() instanceof NetworkRequestTimeoutException) {
                String causeMessage = e.getCause().getMessage();
                someText = "Network connection failed: " + causeMessage;
                Log.e(TAG, someText);
                // Handle network error with failover logic. MobiledgeX MatchingEngine requests over cellular is needed to talk to the DME.
                updateText(ctx, someText);
            }
        } catch (InterruptedException | IllegalArgumentException | Resources.NotFoundException | PackageManager.NameNotFoundException e) {
            someText += "Exception failure: " + e.getMessage();
            updateText(ctx, someText);
            Log.e(TAG, Log.getStackTraceString(e));
        } catch (Exception e) {
            someText += "Exception failure: " + e.getMessage() + ": ";
            updateText(ctx, someText);
            e.printStackTrace();
        } finally {
            updateText(ctx, someText);
        }
    }

    private void updateText(AppCompatActivity ctx, String text) {
        if (ctx == null) {
            return;
        }
        ctx.runOnUiThread(() -> {
            TextView tv = findViewById(R.id.mobiledgex_verified_location_content);
            tv.setText(text);
        });
    }

    private void doEnhancedLocationUpdateInBackground(final Task<Location> locationTask, final AppCompatActivity ctx) {
        ExecutorService service = Executors.newCachedThreadPool();
        CompletableFuture.runAsync(() -> runFlow(locationTask, ctx), service);
    }
}
