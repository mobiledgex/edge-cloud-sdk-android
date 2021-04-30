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

import android.content.Context;
import android.location.Location;
import android.os.Looper;
import androidx.test.platform.app.InstrumentationRegistry;

import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.Pair;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.common.eventbus.Subscribe;
import com.mobiledgex.matchingengine.edgeeventsconfig.ClientEventsConfig;
import com.mobiledgex.matchingengine.edgeeventsconfig.EdgeEventsConfig;
import com.mobiledgex.matchingengine.edgeeventsconfig.FindCloudletEvent;
import com.mobiledgex.matchingengine.edgeeventsconfig.FindCloudletEventTrigger;
import com.mobiledgex.matchingengine.performancemetrics.NetTest;
import com.mobiledgex.matchingengine.performancemetrics.Site;

import junit.framework.TestCase;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import distributed_match_engine.AppClient;
import distributed_match_engine.Appcommon;

import static distributed_match_engine.AppClient.FindCloudletReply.FindStatus.FIND_FOUND;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class EdgeEventsConnectionTest {
    public static final String TAG = "EdgeEventsConnectionTest";
    public static final long GRPC_TIMEOUT_MS = 10000;

    // There's no clear way to get this programmatically outside the app signing certificate, and may
    // not be required in the future.
    public static final String organizationName = "MobiledgeX-Samples";
    // Other globals:
    public static final String applicationName = "sdktest";
    public static final String appVersion = "9.0";

    FusedLocationProviderClient fusedLocationClient;

    public static String hostOverride = "wifi.dme.mobiledgex.net";
    public static int portOverride = 50051;
    public static String findCloudletCarrierOverride = "TELUS"; // Allow "Any" if using "", but this likely breaks test cases.

    public boolean useHostOverride = true;
    public boolean useWifiOnly = true; // This also disables network switching, since the android default is WiFi.


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
            assertSame("Should be a CloserCloudlet trigger!", fce.trigger, FindCloudletEventTrigger.CloserCloudlet);
            responses.add(fce);
            if (latch == null) {
                return;
            }
            latch.countDown();
        }
    }

    @Before
    public void LooperEnsure() {
        // SubscriberManager needs a thread. Start one:
        if (Looper.myLooper() == null)
            Looper.prepare();
    }

    Location edmontonLoc, montrealLoc;
    Location [] toggleLocations;
    int locationIndex = 0;

    @Before
    public void locationGetTogglerSetup() {
        edmontonLoc = getTestLocation();
        edmontonLoc.setLatitude(53.5461); // Edmonton
        edmontonLoc.setLongitude(-113.4938);

        montrealLoc = getTestLocation();
        montrealLoc.setLatitude(45.5017); // Montreal
        montrealLoc.setLongitude(-73.5673);

        toggleLocations = new Location[] {edmontonLoc, montrealLoc};
    }

    public Location getTestLocation() {
        Location location = new Location("EngineCallTestLocation");
        location.setLongitude(-121.8919);
        location.setLatitude(37.3353);
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
            List<Pair<String, Long>> ids = me.retrieveCellId(context);
            AppClient.RegisterClientRequest.Builder regRequestBuilder = AppClient.RegisterClientRequest.newBuilder()
                    .setOrgName(organizationName)
                    .setAppName(applicationName)
                    .setAppVers(appVersion);
            if (ids.size() > 0) {
                regRequestBuilder.setCellId(me.retrieveCellId(context).get(0).second.intValue());
            }
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

            Appcommon.DeviceInfo deviceInfo = me.getDeviceInfoProto();

            if (isEmulator(context)) {
                assertTrue("Should have deviceModel, depends on device: ", "".equals(deviceInfo.getDeviceModel()));
            } else {
                assertTrue("Should have deviceModel, depends on device: ", !deviceInfo.getDeviceModel().isEmpty());
            }
            assertFalse("Should have networkType, depends on device: ", deviceInfo.getDataNetworkType().isEmpty());
            assertFalse("Should have deviceOS, depends on device: ", deviceInfo.getDeviceOs().isEmpty());
            assertTrue("Should have a signal strength for last known network, depends on device: ", deviceInfo.getSignalStrength() >= 0);
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
            config.latencyUpdateConfig.maxNumberOfUpdates = 0;
            config.locationUpdateConfig.maxNumberOfUpdates = 0;

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
            boolean restarted = (restartFuture = me.restartEdgeEventsFuture()).get(GRPC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            assertTrue("Should restart successfully. Completion failed!", restarted);
            assertFalse("Should throw no exceptions!", restartFuture.isCompletedExceptionally());

            CompletableFuture<Boolean> stopFuture;
            boolean stopped = (stopFuture = me.stopEdgeEventsFuture()).get(GRPC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            assertTrue("Should stop successfully. Completion failed!", stopped);
            assertFalse("Should throw no exceptions!", stopFuture.isCompletedExceptionally());
        } catch (Exception e) {
            Log.e(TAG, "Exception type: " + e.getCause() + ", " + e.getLocalizedMessage());
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
            Log.e(TAG, "Exception type: " + e.getCause() + ", " + e.getLocalizedMessage());
            e.printStackTrace();
            fail("Test failed.");
        } finally {
            if (me != null) {
                me.close();
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
            Location location = getTestLocation();
            registerClient(me);

            EdgeEventsConfig config = me.createDefaultEdgeEventsConfig();
            config.locationUpdateConfig.maxNumberOfUpdates = 0;

            // Cannot use the older API if overriding.
            AppClient.FindCloudletRequest findCloudletRequest = me.createDefaultFindCloudletRequest(me.mContext, edmontonLoc)
                    .setCarrierName(findCloudletCarrierOverride)
                    .build();

            if (useHostOverride) {
                response1 = me.findCloudletFuture(findCloudletRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS,
                        MatchingEngine.FindCloudletMode.PROXIMITY);
                // start on response1
                me.startEdgeEvents(hostOverride, portOverride, null, config);
            } else {
                response1 = me.findCloudletFuture(findCloudletRequest, GRPC_TIMEOUT_MS, MatchingEngine.FindCloudletMode.PROXIMITY);
                // start on response1
                // Do not use the futures version, if not on thread. get() will deadlock thread.
                me.startEdgeEvents(config);
            }

            findCloudletReply1 = response1.get();

            assertSame("FindCloudlet1 did not succeed!", findCloudletReply1.getStatus(), FIND_FOUND);

            me.getEdgeEventsConnection().postLocationUpdate(montrealLoc);
            er.setLatch(1);
            er.latch.await(20, TimeUnit.SECONDS);
            assertEquals("Wrong number of responses", 1, er.responses.size());

            me.getEdgeEventsConnection().postLocationUpdate(edmontonLoc);
            er.setLatch(1);
            er.latch.await(20, TimeUnit.SECONDS);
            assertEquals("Wrong number of responses", 2, er.responses.size());

            me.getEdgeEventsConnection().postLocationUpdate(montrealLoc);
            er.setLatch(1);
            er.latch.await(20, TimeUnit.SECONDS);
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
            er.latch.await(5, TimeUnit.SECONDS);
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
            Location location = getTestLocation();
            registerClient(me);

            me.setAutoMigrateEdgeEventsConnection(false);

            // Cannot use the older API if overriding.
            // Mocks needed to prevent real locaiton messing with results;
            EdgeEventsConfig config = me.createDefaultEdgeEventsConfig();
            config.locationUpdateConfig.maxNumberOfUpdates = 0;
            config.latencyUpdateConfig.maxNumberOfUpdates = 0;

            AppClient.FindCloudletRequest findCloudletRequest = me.createDefaultFindCloudletRequest(context, edmontonLoc)
                    .setCarrierName(findCloudletCarrierOverride)
                    .build();

            if (useHostOverride) {
                response1 = me.findCloudletFuture(findCloudletRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS,
                        MatchingEngine.FindCloudletMode.PROXIMITY);
                // start on response1
                me.startEdgeEvents(hostOverride, portOverride, null, config);
            } else {
                response1 = me.findCloudletFuture(findCloudletRequest, GRPC_TIMEOUT_MS, MatchingEngine.FindCloudletMode.PROXIMITY);
                // start on response1
                // Do not use the futures version, if not on thread. get() will deadlock thread.
                me.startEdgeEvents(config);
            }

            findCloudletReply1 = response1.get();

            assertSame("FindCloudlet1 did not succeed!",  AppClient.FindCloudletReply.FindStatus.FIND_FOUND, findCloudletReply1.getStatus());

            Log.w(TAG, "Setting test to NOT autoMigrate DME connection");


            me.getEdgeEventsConnection().postLocationUpdate(montrealLoc);
            er.setLatch(1);
            er.latch.await(20, TimeUnit.SECONDS);
            me.switchedToNextCloudlet(); // This is just an alias to the blocking version of restartEdgeEvents()/restartEdgeEventsFuture.

            assertEquals("Wrong number of responses", 1, er.responses.size());

            me.getEdgeEventsConnection().postLocationUpdate(edmontonLoc);
            er.setLatch(1);
            er.latch.await(20, TimeUnit.SECONDS);
            me.switchedToNextCloudlet();
            assertEquals("Wrong number of responses", 2, er.responses.size());

            me.getEdgeEventsConnection().postLocationUpdate(montrealLoc);
            er.setLatch(1);
            er.latch.await(20, TimeUnit.SECONDS);
            me.switchedToNextCloudlet();
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
            er.latch.await(5, TimeUnit.SECONDS);
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
                    50,
                    ClientEventsConfig.UpdatePattern.onInterval);
            edgeEventsConfig.latencyInternalPort = 2016;
            edgeEventsConfig.latencyTestType = NetTest.TestType.CONNECT;
            edgeEventsConfig.latencyUpdateConfig.maxNumberOfUpdates++; // Use a non-specific value, that's 1 higher for tests.

            if (useHostOverride) {
                response1 = me.findCloudletFuture(findCloudletRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS,
                        MatchingEngine.FindCloudletMode.PROXIMITY);
                // start on response1
                me.startEdgeEvents(hostOverride, portOverride, null, edgeEventsConfig);
            } else {
                response1 = me.findCloudletFuture(findCloudletRequest, GRPC_TIMEOUT_MS, MatchingEngine.FindCloudletMode.PROXIMITY);
                // start on response1
                me.startEdgeEvents(edgeEventsConfig);
            }
            findCloudletReply1 = response1.get();


            assertSame("FindCloudlet1 did not succeed!", findCloudletReply1.getStatus(), FIND_FOUND);
            assertNotNull("Must have configured edgeEvents in test to USE edge events functions.", me.mEdgeEventsConfig);

            latch.await(20, TimeUnit.SECONDS);
            int expectedNum = edgeEventsConfig.latencyUpdateConfig.maxNumberOfUpdates;
            assertEquals("Must get [" + expectedNum + "] responses back from server.", expectedNum, responses.size());
            // FIXME: For this test, the location is NON-MOCKED, a MOCK location provider is required to get sensible results here, but the location timer task is going.
            //assertEquals("Must get new FindCloudlet responses back from server.", 0, latencyNewCloudletResponses.size());

            for (AppClient.ServerEdgeEvent s : responses) {
                Assert.assertTrue("Must have non-negative averages!", s.getStatistics().getAvg() >= 0f);
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
                me.close();
            }
        }
    }
}