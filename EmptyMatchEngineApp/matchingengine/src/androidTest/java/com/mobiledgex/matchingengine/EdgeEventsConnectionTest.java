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

import android.app.UiAutomation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Network;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Looper;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.Pair;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.mobiledgex.matchingengine.edgeeventsconfig.EdgeEventsConfig;
import com.mobiledgex.matchingengine.performancemetrics.NetTest;
import com.mobiledgex.matchingengine.performancemetrics.Site;
import com.mobiledgex.matchingengine.util.MeLocation;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import distributed_match_engine.AppClient;
import distributed_match_engine.Appcommon;
import io.grpc.StatusRuntimeException;

import static distributed_match_engine.AppClient.FindCloudletReply.FindStatus.FIND_FOUND;
import static junit.framework.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

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



    @Before
    public void LooperEnsure() {
        // SubscriberManager needs a thread. Start one:
        if (Looper.myLooper() == null)
            Looper.prepare();
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
            Assert.assertTrue(registerReply.getStatus() == AppClient.ReplyStatus.RS_SUCCESS);
        } catch (DmeDnsException dde) {
            Log.e(TAG, Log.getStackTraceString(dde));
            Assert.assertTrue("ExecutionException registering client.", false);
        } catch (ExecutionException ee) {
            Log.e(TAG, Log.getStackTraceString(ee));
            Assert.assertTrue("ExecutionException registering client", false);
        } catch (InterruptedException ioe) {
            Log.e(TAG, Log.getStackTraceString(ioe));
            Assert.assertTrue("InterruptedException registering client", false);
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

        try {
            MatchingEngine me = new MatchingEngine(context);
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
            assertTrue("Should have networkType, depends on device: ", !deviceInfo.getDataNetworkType().isEmpty());
            assertTrue("Should have deviceOS, depends on device: ", !deviceInfo.getDeviceOs().isEmpty());
            assertTrue("Should have a signal strength for last known network, depends on device: ", deviceInfo.getSignalStrength() >= 0);
        } catch (Exception e) {
            e.printStackTrace();
            assertTrue("Test failed.", false);
        }
    }

    @Test
    public void testEdgeConnectionBasicFuturesFlow() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AppClient.FindCloudletReply findCloudletReply1;

        try {
            MatchingEngine me = new MatchingEngine(context);
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

            // Probably is false, since we didn't wait.
            CompletableFuture<Boolean> startFuture;
            boolean bvalue = (startFuture = me.startEdgeEventsFuture(me.createDefaultEdgeEventsConfig())).getNow(false);
            assertFalse("Not enough time, likely fails", bvalue);
            boolean value = me.startEdgeEventsFuture(me.createDefaultEdgeEventsConfig()).get(GRPC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            assertTrue("Did not start. Completion failed!", value);
            assertFalse("Should throw no exceptions!", startFuture.isCompletedExceptionally());

            CompletableFuture<Boolean> restartFuture;
            bvalue = (me.startEdgeEventsFuture(me.createDefaultEdgeEventsConfig())).getNow(false);
            assertFalse("Not enough time, likely fails", bvalue);
            boolean restarted = (restartFuture = me.restartEdgeEventsFuture()).get(GRPC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            assertTrue("Should restart successfully. Completion failed!", restarted);
            assertFalse("Should throw no exceptions!", restartFuture.isCompletedExceptionally());

            CompletableFuture<Boolean> stopFuture;
            bvalue = (me.startEdgeEventsFuture(me.createDefaultEdgeEventsConfig())).getNow(false);
            assertFalse("Not enough time, likely fails", bvalue);
            boolean stopped = (stopFuture = me.restartEdgeEventsFuture()).get(GRPC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            assertTrue("Should stop successfully. Completion failed!", stopped);
            assertFalse("Should throw no exceptions!", stopFuture.isCompletedExceptionally());

            // Chain. This is rather ugly without lamda sugar, but generates compatible bytecode.
            // Java Language level MUST be set to 8 (from 2017, gradle 3.0+, Android Studio 3.0+).
            final MatchingEngine finalMe = me;
            CompletableFuture<Boolean> chainFuture = me.startEdgeEventsFuture(me.createDefaultEdgeEventsConfig())
                    .thenCompose( b -> me.restartEdgeEventsFuture() )
                    .thenCompose( b -> me.stopEdgeEventsFuture() )
                    .thenApply( b -> {System.out.println("Done!"); return true; });

            try {
                boolean chainValue = chainFuture.get(GRPC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                assertEquals("Chain failure", true, chainValue);
            } catch (CompletionException ce) {
                assertFalse("Should not be here. Reason for failure: " + ce.getMessage(), false);
            }

            boolean exceptionallyComplete = me.restartEdgeEventsFuture().isCompletedExceptionally();
            assertFalse("Should be a clean run.", exceptionallyComplete);

            Log.i(TAG, "Test of chain done.");

      } catch (Exception e) {
            e.printStackTrace();
            assertTrue("Test failed.", false);
        }
    }
}