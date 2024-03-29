/*
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

import static android.content.Context.LOCATION_SERVICE;

import android.app.UiAutomation;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.test.platform.app.InstrumentationRegistry;

import android.os.SystemClock;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.Pair;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.common.eventbus.Subscribe;
import com.mobiledgex.matchingengine.edgeeventsconfig.EdgeEventsConfig;
import com.mobiledgex.matchingengine.edgeeventsconfig.FindCloudletEvent;
import com.mobiledgex.matchingengine.edgeeventsconfig.FindCloudletEventTrigger;
import com.mobiledgex.matchingengine.edgeeventsconfig.UpdateConfig;
import com.mobiledgex.matchingengine.performancemetrics.NetTest;
import com.mobiledgex.matchingengine.util.MeLocation;

import junit.framework.TestCase;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import distributed_match_engine.AppClient;
import distributed_match_engine.Appcommon;
import distributed_match_engine.LocOuterClass;

import static com.mobiledgex.matchingengine.EdgeEventsConnection.ChannelStatus.open;
import static com.mobiledgex.matchingengine.EdgeEventsConnection.ChannelStatus.opening;
import static distributed_match_engine.AppClient.FindCloudletReply.FindStatus.FIND_FOUND;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class EdgeEventsConnectionTest {
    public static final String TAG = "EdgeEventsConnectionTest";
    public static final long GRPC_TIMEOUT_MS = 15000;

    // There's no clear way to get this programmatically outside the app signing certificate, and may
    // not be required in the future.
    public static final String organizationName = "automation_dev_org";
    // Other globals:
    public static final String applicationName = "automation-sdk-porttest"; // "automation-sdk-porttest";
    public static final String appVersion = "1.0";


    public static String hostOverride = "us-qa.dme.mobiledgex.net";
    public static int portOverride = 50051;
    public static String findCloudletCarrierOverride = ""; // Allow "Any" if using "", but this likely breaks test cases.

    public boolean useHostOverride = true;

    class EventReceiver {
        ConcurrentLinkedQueue<FindCloudletEvent> responses;
        CountDownLatch latch;

        public EventReceiver() {
            responses = new ConcurrentLinkedQueue<>();
        }

        public CountDownLatch setLatch(int count) {
            return latch = new CountDownLatch(count);
        }

        @Subscribe
        void HandleEdgeEvent(FindCloudletEvent fce) {
            Log.d(TAG, "Have new Cloudlet! " + fce.newCloudlet);
            assertNotNull("Should have a Cloudlet!", fce.newCloudlet);
            assertTrue("Generic Receiver instance should get a CloserCloudlet or LatencyTooHigh trigger!",
                    fce.trigger == FindCloudletEventTrigger.CloserCloudlet ||
                    fce.trigger == FindCloudletEventTrigger.LatencyTooHigh);
            responses.add(fce);
            if (latch == null) {
                return;
            }
            latch.countDown();
        }
    }

    @Before
    public void grantPermissions() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
            uiAutomation.grantRuntimePermission(
                    InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName(),
                    "android.permission.READ_PHONE_STATE");
            uiAutomation.grantRuntimePermission(
                    InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName(),
                    "android.permission.ACCESS_COARSE_LOCATION");
            uiAutomation.grantRuntimePermission(
                    InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName(),
                    "android.permission.ACCESS_FINE_LOCATION");
        }
    }

    @Before
    public void LooperEnsure() {
        // SubscriberManager needs a thread. Start one:
        if (Looper.myLooper() == null)
            Looper.prepare();
    }

    Location edmontonLoc, montrealLoc, automationFairviewCloudlet, automationHawkinsCloudlet;
    Location [] toggleLocations;
    int toggleIndex;

    @Before
    public void locationGetTogglerSetup() {
        edmontonLoc = getTestLocation();
        edmontonLoc.setLatitude(53.5461); // Edmonton
        edmontonLoc.setLongitude(-113.4938);

        montrealLoc = getTestLocation();
        montrealLoc.setLatitude(45.5017); // Montreal
        montrealLoc.setLongitude(-73.5673);

        automationFairviewCloudlet = getTestLocation();
        automationFairviewCloudlet.setLatitude(50.110922);
        automationFairviewCloudlet.setLongitude(8.682127);

        automationHawkinsCloudlet = getTestLocation();
        automationHawkinsCloudlet.setLatitude(53.5511);
        automationHawkinsCloudlet.setLongitude(9.9937);

        toggleLocations = new Location[] {edmontonLoc, montrealLoc, automationFairviewCloudlet, automationHawkinsCloudlet};
        toggleIndex = (toggleIndex + 1) % toggleLocations.length;
    }

    public Location getTestLocation() {
        Location location = new Location("EngineCallTestLocation");
        location.setLongitude(-121.8919);
        location.setLatitude(37.3353);
        location.setTime(System.currentTimeMillis());
        location.setAccuracy((float)Math.random() * 0.00001f);
        location.setAltitude(0.1);
        location.setBearing(0.1f);
        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        location.setSpeed((float) 0.1);
        return location;
    }

    // Every call needs registration to be called first at some point.
    // Test only!
    public void registerClient(MatchingEngine me) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        AppClient.RegisterClientReply registerReply;
        AppClient.RegisterClientRequest regRequest;

        try {
            // The app version will be null, but we can build from scratch for test
            AppClient.RegisterClientRequest.Builder regRequestBuilder = AppClient.RegisterClientRequest.newBuilder()
                    .setOrgName(organizationName)
                    .setAppName(applicationName)
                    .setAppVers(appVersion);
            regRequest = regRequestBuilder.build();
            if (useHostOverride) {
                registerReply = me.registerClient(regRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                registerReply = me.registerClient(regRequest, GRPC_TIMEOUT_MS);
            }
            assertEquals("Response SessionCookie should equal MatchingEngine SessionCookie",
                    registerReply.getSessionCookie(), me.getSessionCookie());
            assertSame(registerReply.getStatus(), AppClient.ReplyStatus.RS_SUCCESS);
        } catch (DmeDnsException dde) {
            Log.e(TAG, Log.getStackTraceString(dde));
            fail("ExecutionException registering client.");
        } catch (ExecutionException ee) {
            Log.e(TAG, Log.getStackTraceString(ee));
            fail("ExecutionException registering client");
        } catch (InterruptedException ioe) {
            Log.e(TAG, Log.getStackTraceString(ioe));
            fail("InterruptedException registering client");
        }
    }

    public boolean isEmulator(Context context) {
        TelephonyManager telManager = (TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);
        String name = telManager.getNetworkOperatorName();
        return "Android".equals(name);
    }

    @Test
    public void testDefaultConfigNoChanges() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Future<AppClient.FindCloudletReply> response1;
        AppClient.FindCloudletReply findCloudletReply1;
        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);
        // This EdgeEventsConnection test requires an EdgeEvents enabled server.
        // me.setSSLEnabled(false);
        // me.setNetworkSwitchingEnabled(false);
        // attach an EdgeEventBus to receive the server response, if any (inline class):
        final List<AppClient.ServerEdgeEvent> responses = new ArrayList<>();
        final List<FindCloudletEvent> latencyNewCloudletResponses = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        class EventReceiver2 {
            @Subscribe
            void HandleEdgeEvent(AppClient.ServerEdgeEvent edgeEvent) {
                switch (edgeEvent.getEventType()) {
                    case EVENT_LATENCY_PROCESSED:
                        Log.i(TAG, "Received a latency processed response: " + edgeEvent);
                        responses.add(edgeEvent);
                        latch.countDown();
                        break;
                }
            }
            @Subscribe
            void HandleFindCloudlet(FindCloudletEvent fce) {
                latencyNewCloudletResponses.add(fce);
            }
        }
        EventReceiver2 er = new EventReceiver2();
        me.getEdgeEventsBus().register(er);
        try {
            registerClient(me);
            //enableMockLocation(context,true);

            // Cannot use the older API if overriding.
            AppClient.FindCloudletRequest findCloudletRequest = me.createDefaultFindCloudletRequest(context, edmontonLoc)
                    .setCarrierName(findCloudletCarrierOverride)
                    .build();
            EdgeEventsConfig edgeEventsConfig = me.createDefaultEdgeEventsConfig();
            if (useHostOverride) {
                response1 = me.findCloudletFuture(findCloudletRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS,
                        MatchingEngine.FindCloudletMode.PROXIMITY);
                // start on response1
                me.startEdgeEventsFuture(edgeEventsConfig);
            } else {
                response1 = me.findCloudletFuture(findCloudletRequest, GRPC_TIMEOUT_MS, MatchingEngine.FindCloudletMode.PROXIMITY);
                // start on response1
                me.startEdgeEvents(edgeEventsConfig);
            }
            findCloudletReply1 = response1.get();
            assertSame("FindCloudlet1 did not succeed!", findCloudletReply1.getStatus(), FIND_FOUND);
            latch.await((long)edgeEventsConfig.latencyUpdateConfig.updateIntervalSeconds * 4, TimeUnit.SECONDS);
            // This is actually unbounded, as the default is infinity latency Processed responses, should you wait long enough for that many to start arriving.
            long expectedNum = 1; // edgeEventsConfig.latencyUpdateConfig.maxNumberOfUpdates;
            Log.i(TAG, "EdgeEvent :  " + responses.size());
            assertTrue("Must get at LEAST [" + expectedNum + "] responses back from server.", responses.size() >= expectedNum);
            // FIXME: For this test, the location is NON-MOCKED, a MOCK location provider is required to get sensible results here, but the location timer task is going.
            //assertEquals("Must get new FindCloudlet responses back from server.", 0, latencyNewCloudletResponses.size());
            for (AppClient.ServerEdgeEvent s : responses) {
                assertTrue("Must have non-negative averages!", s.getStatistics().getAvg() >= 0f);
                Log.i(TAG, "Edge Event  :  " + s.getEventType());
            }
        } catch (DmeDnsException dde) {
            Log.e(TAG, Log.getStackTraceString(dde));
            fail("FindCloudletFuture: DmeDnsException");
        } catch (ExecutionException ee) {
            Log.e(TAG, Log.getStackTraceString(ee));
            fail("FindCloudletFuture: ExecutionExecution!");
        } catch (InterruptedException ie) {
            Log.e(TAG, Log.getStackTraceString(ie));
            fail("FindCloudletFuture: InterruptedException!");
        } finally {
            if (me != null) {
                Log.i(TAG, "Closing matching engine...");
                me.close();
                Log.i(TAG, "MatchingEngine closed for test.");
            }
        }
    }

    @Test
    public void testDeviceInfo() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AppClient.FindCloudletReply findCloudletReply1;

        MatchingEngine me = null;
        try {
            me = new MatchingEngine(context);
            me.setMatchingEngineLocationAllowed(true);
            registerClient(me);
            Location location = getTestLocation();
            // Set orgName and location, then override the rest for testing:
            AppClient.FindCloudletRequest findCloudletRequest = me.createDefaultFindCloudletRequest(context, location)
                    .setCarrierName(findCloudletCarrierOverride)
                    .build();
            if (useHostOverride) {
                findCloudletReply1 = me.findCloudlet(findCloudletRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                findCloudletReply1 = me.findCloudlet(findCloudletRequest, GRPC_TIMEOUT_MS);
            }
            assertEquals("Not successful findCloudlet", FIND_FOUND, findCloudletReply1.getStatus());

            // This should not be used directly.
            EdgeEventsConnection eec = me.getEdgeEventsConnection();

            assertNotNull("Should have an EdgeEventsConnection after FIND_FOUND", eec);

            Appcommon.DeviceInfoStatic deviceInfoStatic = me.getDeviceInfoStaticProto();
            Appcommon.DeviceInfoDynamic deviceInfoDynamic = me.getDeviceInfoDynamicProto();


            assertTrue("DeviceOS must be non-empty for device: ",
                    deviceInfoStatic.getDeviceOs() != null &&
                    deviceInfoStatic.getDeviceOs().contains("Android_Version_") &&
                    deviceInfoStatic.getDeviceOs().length() > "Android_Version_".length());

            if (isEmulator(context)) {
                assertTrue("Should have deviceModel, depends on device: ", !deviceInfoStatic.getDeviceModel().isEmpty()); // TODO: "Android SDK built for x86", etc.
            } else {
                assertTrue("Should have deviceModel, depends on device: ", !deviceInfoStatic.getDeviceModel().isEmpty());
            }
            assertTrue("Signal must be more than -1: ", deviceInfoDynamic.getSignalStrength() > -1);
            assertTrue("Carrier must be non-empty for a real device (with a SIM card): ", deviceInfoDynamic.getCarrierName() != null && !deviceInfoDynamic.getCarrierName().isEmpty());
            assertTrue("DataNetworkType must be non-empty for real device: ", deviceInfoDynamic.getDataNetworkType() != null && !deviceInfoDynamic.getDataNetworkType().isEmpty());


        } catch (Exception e) {
            e.printStackTrace();
            assertTrue("Test failed.", false);
        } finally {
            if (me != null) {
                me.close();
            }
        }
    }

    @Test
    public void testEdgeConnectionBasicFuturesFlow() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AppClient.FindCloudletReply findCloudletReply1;

        MatchingEngine me = null;
        try {
            me = new MatchingEngine(context);
            me.setMatchingEngineLocationAllowed(true);
            registerClient(me);
            Location location = getTestLocation();

            EdgeEventsConfig config = me.createDefaultEdgeEventsConfig();
            // Some of this can spuriously break the DME connection for reconnection.
            config.latencyUpdateConfig = null; // Disable. This could break the test with spurious newFindCloudlets.
            config.locationUpdateConfig.maxNumberOfUpdates = 0; // Infinity.

            // Set orgName and location, then override the rest for testing:
            AppClient.FindCloudletRequest findCloudletRequest = me.createDefaultFindCloudletRequest(context, location)
                    .setCarrierName(findCloudletCarrierOverride)
                    .build();
            if (useHostOverride) {
                findCloudletReply1 = me.findCloudlet(findCloudletRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                findCloudletReply1 = me.findCloudlet(findCloudletRequest, GRPC_TIMEOUT_MS);
            }
            assertEquals("Not successful findCloudlet", FIND_FOUND, findCloudletReply1.getStatus());

            // Probably is false, since we didn't wait.
            CompletableFuture<Boolean> startFuture = me.startEdgeEventsFuture(config);
            boolean bvalue = startFuture.getNow(false);
            assertFalse("Not enough time, likely fails", bvalue);

            // More patient get:
            boolean value = startFuture.get(GRPC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            assertTrue("Did not start. Completion failed!", value);
            assertFalse("Should throw no exceptions!", startFuture.isCompletedExceptionally());

            CompletableFuture<Boolean> restartFuture;
            boolean restarted = (restartFuture = me.restartEdgeEventsFuture()).get(GRPC_TIMEOUT_MS * 2, TimeUnit.MILLISECONDS);
            assertTrue("Should restart successfully. Completion failed!", restarted);
            Log.i(TAG, "XXX Done restart.");
            assertTrue("Should be running after restart: ",
                    open == me.getEdgeEventsConnection().channelStatus ||
                    opening == me.getEdgeEventsConnection().channelStatus);
            assertFalse("Should throw no exceptions!", restartFuture.isCompletedExceptionally());

            CompletableFuture<Boolean> stopFuture;
            boolean stopped = (stopFuture = me.stopEdgeEventsFuture()).get(GRPC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            assertTrue("Should stop successfully. Completion failed!", stopped);
            assertFalse("Should throw no exceptions!", stopFuture.isCompletedExceptionally());
            Log.i(TAG, "XXX Done stop.");
        } catch (Exception e) {
            Log.e(TAG, "Exception: " + e + ", " + e.getLocalizedMessage());
            e.printStackTrace();
            fail("Test failed.");
        } finally {
            if (me != null) {
                me.close();
            }
        }
    }

    @Test
    public void testEdgeConnectionJustWaitForOne() {
        LooperEnsure();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AppClient.FindCloudletReply findCloudletReply1;

        MatchingEngine me = null;
        try {
            me = new MatchingEngine(context);
            me.setMatchingEngineLocationAllowed(true);
            registerClient(me);
            Location location = automationHawkinsCloudlet;
            LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            FusedLocationProviderClient fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context);
            fusedLocationProviderClient.setMockMode(true);

            LocationRequest locationRequest = LocationRequest.create();
            LocationResult lastLocationResult[] = new LocationResult[1];
            LocationCallback locationCallback = new LocationCallback() {
                @Override
                public void onLocationResult(LocationResult locationResult) {
                    if (locationResult == null) {
                        return;
                    }
                    String clientLocText = "";
                    lastLocationResult[0] = locationResult;

                    for (Location location : locationResult.getLocations()) {
                        // Update UI with client location data
                        clientLocText += "[" + location.toString() + "],";
                    }
                    Log.d(TAG, "New location: " + clientLocText);
                }
            };
            fusedLocationProviderClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    Looper.getMainLooper() /* Looper */);

            // Explicit mock test provider, however the Test LocationService seems to not be working.
/*

            try {
                locationManager.addTestProvider(LocationManager.GPS_PROVIDER, false,
                       false, false, false, false, true, true, 0, 5);
                locationManager.addTestProvider(LocationManager.NETWORK_PROVIDER, false,
                        false, false, false, false, true, true, 0, 5);
            } catch (IllegalArgumentException iae) {
                // Might already exist. Just use the named provider then.
                iae.printStackTrace();
            }
            try {
                locationManager.removeTestProvider(LocationManager.GPS_PROVIDER);
                locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
                locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location);
            } catch (IllegalArgumentException iae) {

            }
            try {
                locationManager.removeTestProvider(LocationManager.NETWORK_PROVIDER);
                locationManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
                locationManager.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, location);
            } catch (IllegalArgumentException iae) {

            }
*/

            TimerTask task = new TimerTask() {
                @Override
                public void run() {
                    locationGetTogglerSetup();
                    Location loc = toggleLocations[toggleIndex];
                    Log.d(TAG, "location set to : " + loc);
                    fusedLocationProviderClient.setMockLocation(loc);
                    //locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc);
                    //locationManager.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, loc);
                }
            };
            Timer timer = new Timer();
            timer.schedule(task, 1, 400);

            CountDownLatch latch = new CountDownLatch(2);
            ConcurrentLinkedQueue<AppClient.ServerEdgeEvent> responses = new ConcurrentLinkedQueue<>();
            ConcurrentLinkedQueue<FindCloudletEvent> latencyNewCloudletResponses = new ConcurrentLinkedQueue<>();
            class EventReceiver2 {
                @Subscribe
                void HandleEdgeEvent(AppClient.ServerEdgeEvent edgeEvent) {
                    switch (edgeEvent.getEventType()) {
                        case EVENT_LATENCY_PROCESSED:
                            Log.i(TAG, "Received a latency processed response: " + edgeEvent);
                            responses.add(edgeEvent);
                            latch.countDown();
                            break;
                    }
                }

                @Subscribe
                void HandleFindCloudlet(FindCloudletEvent fce) {
                    latencyNewCloudletResponses.add(fce);
                }
            }
            EventReceiver2 er = new EventReceiver2();
            me.getEdgeEventsBus().register(er);

            EdgeEventsConfig config = me.createDefaultEdgeEventsConfig();
            // Some of this can spuriously break the DME connection for reconnection.
            config.latencyUpdateConfig = null; // Disable. This could break the test with spurious newFindCloudlets.
            config.locationUpdateConfig.maxNumberOfUpdates = 0; // Infinity for onStart, should result in 1 call and response.
            config.locationUpdateConfig.updatePattern = UpdateConfig.UpdatePattern.onInterval;
            config.locationUpdateConfig.updateIntervalSeconds = 7;
            config.locationUpdateConfig.maxNumberOfUpdates = 99;

            AppClient.FindCloudletRequest findCloudletRequest = me.createDefaultFindCloudletRequest(context, location)
                    .setCarrierName(findCloudletCarrierOverride)
                    .build();
            if (useHostOverride) {
                findCloudletReply1 = me.findCloudlet(findCloudletRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                findCloudletReply1 = me.findCloudlet(findCloudletRequest, GRPC_TIMEOUT_MS);
            }
            assertEquals("Not successful findCloudlet", FIND_FOUND, findCloudletReply1.getStatus());

            // Probably is false, since we didn't wait.
            CompletableFuture<Boolean>  startFuture = me.startEdgeEventsFuture(config);
            boolean bvalue = startFuture.getNow(false);
            assertFalse("Not enough time, likely fails", bvalue);

            // More patient get:
            boolean value = startFuture.get(GRPC_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            assertTrue("Did not start. Completion failed!", value);
            assertFalse("Should throw no exceptions!", startFuture.isCompletedExceptionally());

            // For this to work, mock location updates need to work.
            latch.await(GRPC_TIMEOUT_MS * 999, TimeUnit.MILLISECONDS);
            assertEquals("Expected only one for OnStart!", 1, latch.getCount());

            Thread.sleep((long)config.locationUpdateConfig.updateIntervalSeconds * 1000l);
            Log.i(TAG, "Done stop.");
        } catch (Exception e) {
            Log.e(TAG, "Exception: " + e + ", " + e.getLocalizedMessage());
            e.printStackTrace();
            fail("Test failed.");
        } finally {
            if (me != null) {
                me.close();
            }
        }
    }

    @Test
    public void testEdgeConnectionBasicCompletionFuturesFlow() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AppClient.FindCloudletReply findCloudletReply1;

        MatchingEngine me = null;
        try {
            me = new MatchingEngine(context);
            me.setMatchingEngineLocationAllowed(true);
            registerClient(me);
            Location location = getTestLocation();
            // Set orgName and location, then override the rest for testing:
            AppClient.FindCloudletRequest findCloudletRequest = me.createDefaultFindCloudletRequest(context, location)
                    .setCarrierName(findCloudletCarrierOverride)
                    .build();
            if (useHostOverride) {
                findCloudletReply1 = me.findCloudlet(findCloudletRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                findCloudletReply1 = me.findCloudlet(findCloudletRequest, GRPC_TIMEOUT_MS);
            }
            assertEquals("Not successful findCloudlet", FIND_FOUND, findCloudletReply1.getStatus());

            // Chain. This is rather ugly without Lambda sugar, but generates compatible bytecode.
            // Java Language level MUST be set to 8 (from 2017, gradle 3.0+, Android Studio 3.0+).
            final MatchingEngine finalMe = me;
            Log.i(TAG, "XXX Starting EdgeEvents CompletableFuture chain test");
            CompletableFuture<Boolean> chainFuture = me.startEdgeEventsFuture(me.createDefaultEdgeEventsConfig())
                    .thenCompose( b -> {
                        if (!b) {
                            throw new CompletionException(new IllegalStateException("Start failed"));
                        }
                        Log.i(TAG, "XXX RestartEdgeEvents Completion test");
                        return finalMe.restartEdgeEventsFuture();
                    } )
                    .thenCompose( b -> {
                        if (!b) {
                            throw new CompletionException(new IllegalStateException("Restart failed"));
                        }
                        Log.i(TAG, "XXX StopEdgeEvents Completion test");
                        return finalMe.stopEdgeEventsFuture();
                    })
                    .thenApply( b -> {
                        if (!b) {
                            throw new CompletionException(new IllegalStateException("Stop failed"));
                        }
                        Log.i(TAG, "Done!");
                        return b;
                    })
                    .exceptionally( ee -> {
                        // DmeDnsException might happen during reconnection, for example.
                        Log.e(TAG, ee.getLocalizedMessage());
                        ee.printStackTrace();
                        return false;
                    });

            try {
                // 4 "pseudo" timeouts plus GRPC await close.
                boolean chainValue = chainFuture.get(GRPC_TIMEOUT_MS * 7, TimeUnit.MILLISECONDS);
                assertTrue("Chain failure", chainValue);
            } catch (CompletionException ce) {
                fail("Should not be here. Reason for failure: " + ce.getMessage());
            }
            Log.i(TAG, "Test of chain done.");
        } catch (Exception e) {
            Log.e(TAG, "Exception: "  + e + ", " + e.getLocalizedMessage());
            e.printStackTrace();
            fail("Test failed.");
        } finally {
            if (me != null) {
                Log.i(TAG, "Closing test case...");
                me.close();
                Log.i(TAG, "Closed test case.");
            }
        }
    }

    // FIXME: Need Mock location to force location changes for Location EdgeEventsConfig.

    @Test
    public void LocationUtilEdgeEventsTest() {
        LooperEnsure();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);
        EventReceiver er = new EventReceiver();
        me.getEdgeEventsBus().register(er); // Resettable latch.

        Future < AppClient.FindCloudletReply > response1;
        AppClient.FindCloudletReply findCloudletReply1;

        try {
            registerClient(me);

            EdgeEventsConfig config = me.createDefaultEdgeEventsConfig();
            config.locationUpdateConfig.maxNumberOfUpdates = 0;
            // Latency too high will trigger more findCloudlets. We're not testing that.
            config.latencyUpdateConfig = null;
            config.locationUpdateConfig = null;

            // Cannot use the older API if overriding.
            AppClient.FindCloudletRequest findCloudletRequest = me.createDefaultFindCloudletRequest(me.mContext, automationFairviewCloudlet)
                    .setCarrierName(findCloudletCarrierOverride)
                    .build();

            if (useHostOverride) {
                response1 = me.findCloudletFuture(findCloudletRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS,
                        MatchingEngine.FindCloudletMode.PROXIMITY);
                me.startEdgeEvents(config);
            } else {
                response1 = me.findCloudletFuture(findCloudletRequest, GRPC_TIMEOUT_MS, MatchingEngine.FindCloudletMode.PROXIMITY);
            }
            me.startEdgeEvents(config);

            findCloudletReply1 = response1.get();

            assertSame("FindCloudlet1 did not succeed!", findCloudletReply1.getStatus(), FIND_FOUND);

            me.getEdgeEventsConnection().postLocationUpdate(automationHawkinsCloudlet);
            er.setLatch(1);
            er.latch.await(GRPC_TIMEOUT_MS * 3, TimeUnit.MILLISECONDS);
            assertEquals("Wrong number of responses", 1, er.responses.size());

            me.getEdgeEventsConnection().postLocationUpdate(automationFairviewCloudlet);
            er.setLatch(1);
            er.latch.await(GRPC_TIMEOUT_MS * 3, TimeUnit.MILLISECONDS);
            assertEquals("Wrong number of responses", 2, er.responses.size());

            me.getEdgeEventsConnection().postLocationUpdate(automationHawkinsCloudlet);
            er.setLatch(1);
            er.latch.await(GRPC_TIMEOUT_MS * 3, TimeUnit.MILLISECONDS);
            assertEquals("Wrong number of responses", 3, er.responses.size());

            me.getEdgeEventsBus().unregister(er);

            er.responses.clear();
            // NewCloudlets may kill the event connection due to auto terminate.
            Assert.assertTrue(me.mEnableEdgeEvents);

            // Default. If you need different test behavior, use the parameterized version. This might be raced against the new DME reconnect() call underneath.
            EdgeEventsConnection edgeEventsConnection = me.getEdgeEventsConnection();
            TestCase.assertNotNull(edgeEventsConnection);

            // No longer registered.
            edgeEventsConnection.postLocationUpdate(edmontonLoc);
            er.setLatch(1);
            er.latch.await(GRPC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            Assert.assertTrue("No responses expected over edge event bus!", er.responses.isEmpty());

        } catch (DmeDnsException dde) {
            Log.e(TAG, Log.getStackTraceString(dde));
            fail("FindCloudletFuture: DmeDnsException");
        } catch (ExecutionException ee) {
            Log.e(TAG, Log.getStackTraceString(ee));
            fail("FindCloudletFuture: ExecutionExecution!");
        } catch (InterruptedException ie) {
            Log.e(TAG, Log.getStackTraceString(ie));
            fail("FindCloudletFuture: InterruptedException!");
        } finally {
            me.close();
        }
    }

    @Test
    public void LocationUtilEdgeEventsTestNoAutoMigration() {
        LooperEnsure();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);
        EventReceiver er = new EventReceiver();
        me.getEdgeEventsBus().register(er); // Resettable latch.

        Future < AppClient.FindCloudletReply > response1;
        AppClient.FindCloudletReply findCloudletReply1;

        try {
            registerClient(me);

            Log.w(TAG, "Setting test to NOT autoMigrate DME connection");
            me.setAutoMigrateEdgeEventsConnection(false);

            // Cannot use the older API if overriding.
            // Mocks needed to prevent real locaiton messing with results;
            EdgeEventsConfig config = me.createDefaultEdgeEventsConfig();
            config.locationUpdateConfig = null; // No tasks at all, must test under test control.
            config.latencyUpdateConfig = null;

            AppClient.FindCloudletRequest findCloudletRequest = me.createDefaultFindCloudletRequest(context, automationFairviewCloudlet)
                    .setCarrierName(findCloudletCarrierOverride)
                    .build();

            if (useHostOverride) {
                response1 = me.findCloudletFuture(findCloudletRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS,
                        MatchingEngine.FindCloudletMode.PROXIMITY);
            } else {
                response1 = me.findCloudletFuture(findCloudletRequest, GRPC_TIMEOUT_MS, MatchingEngine.FindCloudletMode.PROXIMITY);
            }
            me.startEdgeEvents(config);

            findCloudletReply1 = response1.get();

            assertSame("FindCloudlet1 did not succeed!",  AppClient.FindCloudletReply.FindStatus.FIND_FOUND, findCloudletReply1.getStatus());

            me.getEdgeEventsConnection().postLocationUpdate(automationHawkinsCloudlet);
            er.setLatch(1);
            er.latch.await(GRPC_TIMEOUT_MS * 3, TimeUnit.MILLISECONDS);
            me.switchedToNextCloudlet(); // This is just an alias to the blocking version of restartEdgeEvents()/restartEdgeEventsFuture.

            assertEquals("Wrong number of responses", 1, er.responses.size());

            me.getEdgeEventsConnection().postLocationUpdate(automationFairviewCloudlet);
            er.setLatch(1);
            er.latch.await(GRPC_TIMEOUT_MS * 3, TimeUnit.MILLISECONDS);
            me.switchedToNextCloudlet();
            assertEquals("Wrong number of responses", 2, er.responses.size());

            me.getEdgeEventsConnection().postLocationUpdate(automationHawkinsCloudlet);
            er.setLatch(1);
            er.latch.await(GRPC_TIMEOUT_MS * 3, TimeUnit.MILLISECONDS);
            me.switchedToNextCloudlet();
            assertEquals("Wrong number of responses", 3, er.responses.size());

            // Switch off notifications.
            Log.d(TAG, "Unregistering events...");
            me.getEdgeEventsBus().unregister(er);

            er.responses.clear();
            // NewCloudlets may kill the event connection due to auto terminate.
            Assert.assertTrue(me.mEnableEdgeEvents);

            // Default. If you need different test behavior, use the parameterized version. This might be raced against the new DME reconnect() call underneath.
            EdgeEventsConnection edgeEventsConnection = me.getEdgeEventsConnection();
            TestCase.assertNotNull(edgeEventsConnection);

            // No longer registered.
            edgeEventsConnection.postLocationUpdate(edmontonLoc);
            er.setLatch(1);
            er.latch.await(GRPC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            me.switchedToNextCloudlet();
            Assert.assertTrue("No responses expected over edge event bus!", er.responses.isEmpty());

        } catch (DmeDnsException dde) {
            Log.e(TAG, Log.getStackTraceString(dde));
            fail("FindCloudletFuture: DmeDnsException");
        } catch (ExecutionException ee) {
            Log.e(TAG, Log.getStackTraceString(ee));
            fail("FindCloudletFuture: ExecutionExecution!");
        } catch (InterruptedException ie) {
            Log.e(TAG, Log.getStackTraceString(ie));
            fail("FindCloudletFuture: InterruptedException!");
        } finally {
            me.close();
        }
    }

    @Test
    public void testEdgeEventsTest_LatencyTooHigh() {
        LooperEnsure();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);
        class EventReceiver2 {
            ConcurrentLinkedQueue<FindCloudletEvent> responses;
            ConcurrentLinkedQueue<EdgeEventsConnection.EdgeEventsError> errors;
            CountDownLatch latch;

            public EventReceiver2() {
                responses = new ConcurrentLinkedQueue<>();
                errors = new ConcurrentLinkedQueue<>();
            }

            public CountDownLatch setLatch(int count) {
                return latch = new CountDownLatch(count);
            }

            @Subscribe
            void HandleEdgeEvent(FindCloudletEvent fce) {
                Log.d(TAG, "Have new Cloudlet! " + fce.newCloudlet);
                assertNotNull("Should have a Cloudlet!", fce.newCloudlet);
                assertTrue("Should be a LatencyTooHigh trigger!",fce.trigger == FindCloudletEventTrigger.LatencyTooHigh);
                responses.add(fce);
                if (latch == null) {
                    return;
                }
                latch.countDown();
            }

            @Subscribe
            void HandleEdgeEvent(EdgeEventsConnection.EdgeEventsError error) {
                assertTrue("Should be a LatencyTooHigh still best trigger!", error == EdgeEventsConnection.EdgeEventsError.eventTriggeredButCurrentCloudletIsBest);
                errors.add(error);
                if (latch == null) {
                    return;
                }
                latch.countDown();
            }
        }
        EventReceiver2 er = new EventReceiver2();
        me.getEdgeEventsBus().register(er); // Resettable latch.

        Future<AppClient.FindCloudletReply> response1;
        AppClient.FindCloudletReply findCloudletReply1;

        try {
            registerClient(me);

            // Cannot use the older API if overriding.
            // Mocks needed to prevent real locaiton messing with results;
            EdgeEventsConfig config = me.createDefaultEdgeEventsConfig();
            config.locationUpdateConfig = null; // Don't want anything.
            config.latencyUpdateConfig.maxNumberOfUpdates = 4; // num <= 0 means "infinity".
            config.latencyUpdateConfig.updateIntervalSeconds = 5; // also sets initial delay.
            config.latencyThresholdTrigger = 10; // Likely very low for our test servers.

            AppClient.FindCloudletRequest findCloudletRequest = me.createDefaultFindCloudletRequest(context, edmontonLoc)
                    .setCarrierName(findCloudletCarrierOverride)
                    .build();

            if (useHostOverride) {
                response1 = me.findCloudletFuture(findCloudletRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS,
                        MatchingEngine.FindCloudletMode.PROXIMITY);
            } else {
                response1 = me.findCloudletFuture(findCloudletRequest, GRPC_TIMEOUT_MS, MatchingEngine.FindCloudletMode.PROXIMITY);
            }
            me.startEdgeEvents(config);

            findCloudletReply1 = response1.get();

            assertSame("FindCloudlet1 did not succeed!",  AppClient.FindCloudletReply.FindStatus.FIND_FOUND, findCloudletReply1.getStatus());

            // Waiting for 1 (or more).
            er.setLatch((int)config.latencyUpdateConfig.maxNumberOfUpdates);
            er.latch.await(GRPC_TIMEOUT_MS * 6, TimeUnit.MILLISECONDS);

            // Performance avg = 10ms will trigger a Performance FindCloudlet, even if no such server exists. This *could* find a faster server still from that test.
            assertEquals("Expected that the configured latency is too high!", 0, er.latch.getCount());
            if (er.errors.peek() != EdgeEventsConnection.EdgeEventsError.eventTriggeredButCurrentCloudletIsBest) {
                // Performance might have found a better cloudlet, even if they both are far exceeding the unavailable min spec:
                assertTrue("Response must be too high, and if here, test must have randomly found a new cloudlet in the response, per performance measurement, and configured margins: ", er.responses.peek().newCloudlet.getFqdn() != null);
            } else {
                assertEquals("Response must be too high, which could be the same server if measured, and thus still best.", EdgeEventsConnection.EdgeEventsError.eventTriggeredButCurrentCloudletIsBest, er.errors.peek());
            }

        } catch (DmeDnsException dde) {
            Log.e(TAG, Log.getStackTraceString(dde));
            fail("FindCloudletFuture: DmeDnsException");
        } catch (ExecutionException ee) {
            Log.e(TAG, Log.getStackTraceString(ee));
            fail("FindCloudletFuture: ExecutionExecution!");
        } catch (InterruptedException ie) {
            Log.e(TAG, Log.getStackTraceString(ie));
            fail("FindCloudletFuture: InterruptedException!");
        } finally {
            me.close();
        }
    }

    @Test
    public void testEdgeEventsTest_Not_LatencyTooHigh() {
        LooperEnsure();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);

        class EventReceiver2 {
            ConcurrentLinkedQueue<FindCloudletEvent> responses;
            CountDownLatch latch;

            public EventReceiver2() {
                responses = new ConcurrentLinkedQueue<>();
            }

            public CountDownLatch setLatch(int count) {
                return latch = new CountDownLatch(count);
            }

            @Subscribe
            void HandleEdgeEvent(FindCloudletEvent fce) {
                Log.d(TAG, "Have new Cloudlet! " + fce.newCloudlet);
                assertNotNull("Should have a Cloudlet!", fce.newCloudlet);
                assertTrue("Should be a LatencyTooHigh trigger!",fce.trigger == FindCloudletEventTrigger.LatencyTooHigh);
                responses.add(fce);
                if (latch == null) {
                    return;
                }
                latch.countDown();
            }
        }
        EventReceiver2 er = new EventReceiver2();
        me.getEdgeEventsBus().register(er); // Resettable latch.

        Future <AppClient.FindCloudletReply> response1;
        AppClient.FindCloudletReply findCloudletReply1;

        try {
            registerClient(me);

            // Cannot use the older API if overriding.
            // Mocks needed to prevent real locaiton messing with results;
            EdgeEventsConfig config = me.createDefaultEdgeEventsConfig();
            config.locationUpdateConfig.maxNumberOfUpdates = 0;
            config.latencyUpdateConfig.maxNumberOfUpdates = 1;
            config.latencyUpdateConfig.updateIntervalSeconds = 5; // This is also initial delay seconds day.
            config.latencyThresholdTrigger = 300; // Likely very high for our test servers.

            AppClient.FindCloudletRequest findCloudletRequest = me.createDefaultFindCloudletRequest(context, edmontonLoc)
                    .setCarrierName(findCloudletCarrierOverride)
                    .build();

            if (useHostOverride) {
                response1 = me.findCloudletFuture(findCloudletRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS,
                        MatchingEngine.FindCloudletMode.PROXIMITY);
            } else {
                response1 = me.findCloudletFuture(findCloudletRequest, GRPC_TIMEOUT_MS, MatchingEngine.FindCloudletMode.PROXIMITY);
            }
            me.startEdgeEvents(config);

            findCloudletReply1 = response1.get();

            assertSame("FindCloudlet1 did not succeed!",  AppClient.FindCloudletReply.FindStatus.FIND_FOUND, findCloudletReply1.getStatus());

            // Waiting for 1 (or more).
            int latchSize = 1;
            er.setLatch(latchSize);
            er.latch.await(GRPC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            assertEquals("Should not have triggered too high latency! Should still be the original value set!", latchSize, er.latch.getCount());

        } catch (DmeDnsException dde) {
            Log.e(TAG, Log.getStackTraceString(dde));
            fail("FindCloudletFuture: DmeDnsException");
        } catch (ExecutionException ee) {
            Log.e(TAG, Log.getStackTraceString(ee));
            fail("FindCloudletFuture: ExecutionExecution!");
        } catch (InterruptedException ie) {
            Log.e(TAG, Log.getStackTraceString(ie));
            fail("FindCloudletFuture: InterruptedException!");
        } finally {
            me.close();
        }
    }


    @Test
    public void LatencyUtilEdgeEventsTest() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Future<AppClient.FindCloudletReply> response1;
        AppClient.FindCloudletReply findCloudletReply1;
        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);

        // This EdgeEventsConnection test requires an EdgeEvents enabled server.
        // me.setSSLEnabled(false);
        // me.setNetworkSwitchingEnabled(false);

        // attach an EdgeEventBus to receive the server response, if any (inline class):
        final List<AppClient.ServerEdgeEvent> responses = new ArrayList<>();
        final List<FindCloudletEvent> latencyNewCloudletResponses = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);
        class EventReceiver2 {
            @Subscribe
            void HandleEdgeEvent(AppClient.ServerEdgeEvent edgeEvent) {
                switch (edgeEvent.getEventType()) {
                    case EVENT_LATENCY_PROCESSED:
                        Log.i(TAG, "Received a latency processed response: " + edgeEvent);
                        responses.add(edgeEvent);
                        latch.countDown();
                        break;
                }
            }

            @Subscribe
            void HandleFindCloudlet(FindCloudletEvent fce) {
                latencyNewCloudletResponses.add(fce);
            }
        }
        EventReceiver2 er = new EventReceiver2();
        me.getEdgeEventsBus().register(er);

        try {
            Location location = getTestLocation(); // Test needs this configurable in a sensible way.
            registerClient(me);

            // Cannot use the older API if overriding.
            AppClient.FindCloudletRequest findCloudletRequest = me.createDefaultFindCloudletRequest(context, location)
                    .setCarrierName(findCloudletCarrierOverride)
                    .build();

            EdgeEventsConfig edgeEventsConfig = me.createDefaultEdgeEventsConfig(
                    5,
                    5,
                    500,
                    2016);
            edgeEventsConfig.latencyTestType = NetTest.TestType.CONNECT;
            edgeEventsConfig.latencyUpdateConfig.maxNumberOfUpdates++; //  We want 1. 0 is actually infinity.

            if (useHostOverride) {
                response1 = me.findCloudletFuture(findCloudletRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS,
                        MatchingEngine.FindCloudletMode.PROXIMITY);
            } else {
                response1 = me.findCloudletFuture(findCloudletRequest, GRPC_TIMEOUT_MS, MatchingEngine.FindCloudletMode.PROXIMITY);
            }
            me.startEdgeEvents(edgeEventsConfig);
            findCloudletReply1 = response1.get();


            assertSame("FindCloudlet1 did not succeed!", findCloudletReply1.getStatus(), FIND_FOUND);
            assertNotNull("Must have configured edgeEvents in test to USE edge events functions.", me.mEdgeEventsConfig);

            latch.await(GRPC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            long expectedNum = edgeEventsConfig.latencyUpdateConfig.maxNumberOfUpdates;
            assertEquals("Must get [" + expectedNum + "] responses back from server.", expectedNum, responses.size());
            // FIXME: For this test, the location is NON-MOCKED, a MOCK location provider is required to get sensible results here, but the location timer task is going.
            //assertEquals("Must get new FindCloudlet responses back from server.", 0, latencyNewCloudletResponses.size());

            for (AppClient.ServerEdgeEvent s : responses) {
                assertTrue("Must have non-negative averages!", s.getStatistics().getAvg() >= 0f);
            }

        } catch (DmeDnsException dde) {
            Log.e(TAG, Log.getStackTraceString(dde));
            fail("FindCloudletFuture: DmeDnsException");
        } catch (ExecutionException ee) {
            Log.e(TAG, Log.getStackTraceString(ee));
            fail("FindCloudletFuture: ExecutionExecution!");
        } catch (InterruptedException ie) {
            Log.e(TAG, Log.getStackTraceString(ie));
            fail("FindCloudletFuture: InterruptedException!");
        } finally {
            if (me != null) {
                Log.i(TAG, "Closing matching engine...");
                me.close();
                Log.i(TAG, "MatchingEngine closed for test.");
            }
        }
    }

    @Test
    public void LatencyUtilEdgeEventsTest_BadPort() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Future<AppClient.FindCloudletReply> response1;
        AppClient.FindCloudletReply findCloudletReply1;
        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);

        // This EdgeEventsConnection test requires an EdgeEvents enabled server.
        // me.setSSLEnabled(false);
        // me.setNetworkSwitchingEnabled(false);

        // attach an EdgeEventBus to receive the server response, if any (inline class):
        final List<AppClient.ServerEdgeEvent> responses = new ArrayList<>();
        final List<FindCloudletEvent> latencyNewCloudletResponses = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);
        class EventReceiver2 {
            @Subscribe
            void HandleEdgeEvent(AppClient.ServerEdgeEvent edgeEvent) {
                switch (edgeEvent.getEventType()) {
                    case EVENT_LATENCY_PROCESSED:
                        Log.i(TAG, "Received a latency processed response: " + edgeEvent);
                        responses.add(edgeEvent);
                        latch.countDown();
                        break;
                }
            }

            @Subscribe
            void HandleFindCloudlet(FindCloudletEvent fce) {
                latencyNewCloudletResponses.add(fce);
            }
        }
        EventReceiver2 er = new EventReceiver2();
        me.getEdgeEventsBus().register(er);

        try {
            Location location = getTestLocation(); // Test needs this configurable in a sensible way.
            registerClient(me);

            // Cannot use the older API if overriding.
            AppClient.FindCloudletRequest findCloudletRequest = me.createDefaultFindCloudletRequest(context, location)
                    .setCarrierName(findCloudletCarrierOverride)
                    .build();

            EdgeEventsConfig edgeEventsConfig = me.createDefaultEdgeEventsConfig(
                    5,
                    5,
                    500,
                    2085);
            edgeEventsConfig.latencyTestType = NetTest.TestType.CONNECT;
            edgeEventsConfig.latencyUpdateConfig.maxNumberOfUpdates++; //  We want 1. 0 is actually infinity.

            if (useHostOverride) {
                response1 = me.findCloudletFuture(findCloudletRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS,
                        MatchingEngine.FindCloudletMode.PROXIMITY);
            } else {
                response1 = me.findCloudletFuture(findCloudletRequest, GRPC_TIMEOUT_MS, MatchingEngine.FindCloudletMode.PROXIMITY);
            }
            me.startEdgeEvents(edgeEventsConfig);
            findCloudletReply1 = response1.get();


            assertSame("FindCloudlet1 did not succeed!", findCloudletReply1.getStatus(), FIND_FOUND);
            assertNotNull("Must have configured edgeEvents in test to USE edge events functions.", me.mEdgeEventsConfig);

            latch.await(GRPC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            long expectedNum = 0;
            assertEquals("Must get [" + expectedNum + "] responses back from server.", expectedNum, responses.size());
        } catch (DmeDnsException dde) {
            Log.e(TAG, Log.getStackTraceString(dde));
            fail("FindCloudletFuture: DmeDnsException");
        } catch (ExecutionException ee) {
            Log.e(TAG, Log.getStackTraceString(ee));
            fail("FindCloudletFuture: ExecutionExecution!");
        } catch (InterruptedException ie) {
            Log.e(TAG, Log.getStackTraceString(ie));
            fail("FindCloudletFuture: InterruptedException!");
        } finally {
            if (me != null) {
                Log.i(TAG, "Closing matching engine...");
                me.close();
                Log.i(TAG, "MatchingEngine closed for test.");
            }
        }
    }
}