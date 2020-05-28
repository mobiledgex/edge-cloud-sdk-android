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

package com.mobiledgex.sdkdemo;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.location.Location;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionInfo;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.mobiledgex.matchingengine.DmeDnsException;
import com.mobiledgex.matchingengine.MatchingEngine;
import com.mobiledgex.matchingengine.NetworkManager;
import com.mobiledgex.matchingengine.NetworkRequestTimeoutException;
import com.mobiledgex.matchingengine.performancemetrics.NetTest;
import com.mobiledgex.matchingengine.performancemetrics.Site;
import com.mobiledgex.matchingengine.util.RequestPermissions;
import com.mobiledgex.mel.MelMessaging;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

import distributed_match_engine.AppClient;
import distributed_match_engine.Appcommon;
import io.grpc.StatusRuntimeException;

public class MainActivity extends AppCompatActivity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "MainActivity";

    private String someText = null;

    private RequestPermissions mRpUtil;
    private MatchingEngine mMatchingEngine;
    private NetTest netTest;
    private FusedLocationProviderClient mFusedLocationClient;

    private LocationCallback mLocationCallback;
    private LocationRequest mLocationRequest;
    private boolean mDoLocationUpdates;

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
        MatchingEngine.setMatchingEngineLocationAllowed(matchingEngineLocationAllowed);

        // Watch allowed preference:
        prefs.registerOnSharedPreferenceChangeListener(this);


        // Client side FusedLocation updates.
        mDoLocationUpdates = true;

        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        mLocationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) {
                    return;
                }
                String clientLocText = "";
                for (Location location : locationResult.getLocations()) {
                    // Update UI with client location data
                    clientLocText += "[" + location.toString() + "]";
                }
                TextView tv = findViewById(R.id.client_location_content);
                tv.setText(clientLocText);
            };
        };

        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                doEnhancedLocationVerification();
            }
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

        if (mMatchingEngine == null) {
            // Permissions available. Create a MobiledgeX MatchingEngine instance (could also use Application wide instance).
            mMatchingEngine = new MatchingEngine(this);
        }

        if (mDoLocationUpdates) {
            startLocationUpdates();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        stopLocationUpdates();
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        super.onSaveInstanceState(savedInstanceState);
        savedInstanceState.putBoolean(MatchingEngine.MATCHING_ENGINE_LOCATION_PERMISSION, MatchingEngine.isMatchingEngineLocationAllowed());
    }

    @Override
    public void onRestoreInstanceState(Bundle restoreInstanceState) {
        super.onRestoreInstanceState(restoreInstanceState);
        if (restoreInstanceState != null) {
            MatchingEngine.setMatchingEngineLocationAllowed(restoreInstanceState.getBoolean(MatchingEngine.MATCHING_ENGINE_LOCATION_PERMISSION));
        }
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
            mFusedLocationClient.requestLocationUpdates(mLocationRequest,mLocationCallback,
                    null /* Looper */);
        } catch (SecurityException se) {
            se.printStackTrace();
            Log.i(TAG, "App should Request location permissions during onCreate().");
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
        mFusedLocationClient.getLastLocation().addOnCompleteListener(new OnCompleteListener<Location>() {
            @Override
            public void onComplete(Task<Location> task) {
                if (task.isSuccessful() && task.getResult() != null) {
                    doEnhancedLocationUpdateInBackground(task, ctx);
                } else {
                    Log.w(TAG, "getLastLocation:exception", task.getException());
                    someText = "Last location not found, or has never been used. Location cannot be verified using 'getLastLocation()'. " +
                            "Use the requestLocationUpdates() instead if applicable for location verification.";
                    TextView tv = findViewById(R.id.mobiledgex_verified_location_content);
                    tv.setText(someText);
                }
            }
        });
    }

    private void doEnhancedLocationUpdateInBackground(final Task<Location> aTask, final AppCompatActivity ctx) {
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                Location location = aTask.getResult();
                location.setLatitude(49d);
                location.setLongitude(-123d);
                // Location found. Create a request:
                try {
                    someText = "";
                    // Switch entire process over to cellular for application use.
                    //mMatchingEngine.getNetworkManager().switchToCellularInternetNetworkBlocking();

                    // If no carrierName, or active Subscription networks, the app should use the public cloud instead.
                    List<SubscriptionInfo> subList = mMatchingEngine.getActiveSubscriptionInfoList();
                    if (subList != null && subList.size() > 0) {
                        for(SubscriptionInfo info: subList) {
                            if (info.getCarrierName().equals("Android")) {
                                someText += "Emulator Active Subscription Network: " + info.toString() + "\n";
                            } else {
                                someText += "Active Subscription network: " + info.toString() + "\n";
                            }
                        }
                        mMatchingEngine.setNetworkSwitchingEnabled(true);
                        NetworkManager networkManager = mMatchingEngine.getNetworkManager();
                    } else {
                        // This example will continue to execute anyway, as Demo DME may still be reachable to discover nearby edge cloudlets.
                        someText += "No active cellular networks: app should use public cloud instead of the edgecloudlet at this time.\n";
                        mMatchingEngine.setNetworkSwitchingEnabled(false);
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
                        dmeHostAddress = mMatchingEngine.generateDmeHostAddress();
                        someText += "(e)SIM card based DME address: " + dmeHostAddress + "\n";
                    } catch (DmeDnsException dde){
                        someText += dde.getMessage();
                        // Here, being unable to register to the Edge infrastructure, app should
                        // fall back to public cloud server. Edge is not available.
                        // For Demo app, we use the wifi dme server to continue to MobiledgeX.
                        dmeHostAddress = MatchingEngine.wifiOnlyDmeHost;
                    }
                    dmeHostAddress = "us-mexdemo." + MatchingEngine.baseDmeHost;
                    //mMatchingEngine.setUseWifiOnly(true);
                    //dmeHostAddress = mMatchingEngine.generateDmeHostAddress();


                    int port = mMatchingEngine.getPort(); // Keep same port.

                    String orgName = "MobiledgeX"; // Always supplied by application, and in the MobiledgeX web admin console.
                    // For illustration, the matching engine can be used to programatically get the name of your application details
                    // so it can go to the correct appInst version. That AppInst on the server side must match the application
                    // version or else it won't be found and cannot be used.
                    String appName = mMatchingEngine.getAppName(ctx); // AppName must be added to the MobiledgeX web admin console.
                    appName = "MobiledgeX SDK Demo"; // override with a known registered appName.
                    String appVers = "2.0"; // override the version of that known registered app.

                    // There is also createDefaultRegisterClientRequest() to get a Builder class to fill in optional parameters
                    // like AuthToken or Tag key value pairs.
                    AppClient.RegisterClientRequest registerClientRequest =
                            mMatchingEngine.createDefaultRegisterClientRequest(ctx, orgName)
                              //.setCarrierName("cerust")
                              .setAppName(appName)
                              .setAppVers(appVers)
                              .build();
                    Log.i(TAG, "registerclient request is " + registerClientRequest);

                    AppClient.RegisterClientReply registerClientReply =
                            mMatchingEngine.registerClient(registerClientRequest,
                                    /*dmeHostAddress, port, */ 10000);
                    Log.i(TAG, "RegisterReply status is " + registerClientReply.getStatus());

                    if (registerClientReply.getStatus() != AppClient.ReplyStatus.RS_SUCCESS) {
                        someText += "Registration Failed. Error: " + registerClientReply.getStatus();
                        TextView tv = findViewById(R.id.mobiledgex_verified_location_content);
                        tv.setText(someText);
                        return;
                    }

                    // Find the closest cloudlet for your application to use. (Blocking call, or use findCloudletFuture)
                    // There is also createDefaultFindClouldletRequest() to get a Builder class to fill in optional parameters.
                    AppClient.FindCloudletRequest findCloudletRequest =
                            mMatchingEngine.createDefaultFindCloudletRequest(ctx, location)
                                .setCarrierName("cerust")
                                .build();
                    AppClient.FindCloudletReply closestCloudlet = mMatchingEngine.findCloudlet(findCloudletRequest,
                            dmeHostAddress, port, 10000);
                    Log.i(TAG, "closest Cloudlet is " + closestCloudlet);

                    AppClient.VerifyLocationRequest verifyRequest =
                            mMatchingEngine.createDefaultVerifyLocationRequest(ctx, location)
                                .build();
                    Log.i(TAG, "verifyRequest is " + verifyRequest);
                    if (verifyRequest != null) {
                        // Location Verification (Blocking, or use verifyLocationFuture):
                        AppClient.VerifyLocationReply verifiedLocation =
                                mMatchingEngine.verifyLocation(verifyRequest, /*dmeHostAddress, port, */10000);
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
                                aPort.getPathPrefix(),
                                aPort.getEndPort());

                            //String l7Url = mMatchingEngine.getAppConnectionManager().createUrl(closestCloudlet, aPort, aPort.getPublicPort());

                            String host = aPort.getFqdnPrefix() + closestCloudlet.getFqdn();
                            int serverport = aPort.getPublicPort();

                            OkHttpClient client = new OkHttpClient(); //mMatchingEngine.getAppConnectionManager().getHttpClient(10000).get();

                            Request request = new Request.Builder()
                              .url("http://" + host + ":8008")
                              .build();
                            Response response = client.newCall(request).execute();
                            String out = response.toString();
                            System.out.println(response.toString());

                            // Test from a particular network path. Here, the active one is Celluar since we switched the whole process over earlier.
                            Site site = new Site(mMatchingEngine.getNetworkManager().getActiveNetwork(), NetTest.TestType.CONNECT, 5, host, serverport);
                            netTest.addSite(site);
                        }

                        someText += "[Cloudlet App Ports: [" + portListStr + "]\n";

                        String appInstListText = "";
                        AppClient.AppInstListRequest appInstListRequest =
                                mMatchingEngine.createDefaultAppInstListRequest(ctx, location).build();

                        AppClient.AppInstListReply appInstListReply = mMatchingEngine.getAppInstList(
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
                            appInstListText += "]]";
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

                    someText += "[Is WiFi Enabled: " + mMatchingEngine.isWiFiEnabled(ctx) + "]\n";

                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        someText += "[Is Roaming Data Enabled: " + mMatchingEngine.isRoamingData() + "]\n";
                    } else {
                        someText += "[Roaming Data status unknown.]\n";
                    }

                    CarrierConfigManager carrierConfigManager = ctx.getSystemService(CarrierConfigManager.class);
                    someText += "[Enabling WiFi Calling could disable Cellular Data if on a Roaming Network!\nWiFi Calling  Support Status: "
                            + mMatchingEngine.isWiFiCallingSupported(carrierConfigManager) + "]\n";


                    // Background thread. Post update to the UI thread:
                    ctx.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            TextView tv = findViewById(R.id.mobiledgex_verified_location_content);
                            tv.setText(someText);
                        }
                    });

                    // Set network back to last default one, if desired:
                    mMatchingEngine.getNetworkManager().resetNetworkToDefault();
                } catch (DmeDnsException | ExecutionException | StatusRuntimeException e) {
                    Log.e(TAG, e.getMessage());
                    Log.e(TAG, Log.getStackTraceString(e));
                    if (e.getCause() instanceof NetworkRequestTimeoutException) {
                        String causeMessage = e.getCause().getMessage();
                        someText = "Network connection failed: " + causeMessage;
                        Log.e(TAG, someText);
                        // Handle network error with failover logic. MobiledgeX MatchingEngine requests over cellular is needed to talk to the DME.
                        ctx.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                TextView tv = findViewById(R.id.mobiledgex_verified_location_content);
                                tv.setText(someText);
                            }
                        });
                    }
                } catch (IOException | InterruptedException | IllegalArgumentException | Resources.NotFoundException | PackageManager.NameNotFoundException e) {
                    MelMessaging.getCookie("MobiledgeX SDK Demo"); // Import MEL messaging.
                    Log.e(TAG, e.getMessage());
                    Log.e(TAG, Log.getStackTraceString(e));
                }
            }
        });
    }
}
