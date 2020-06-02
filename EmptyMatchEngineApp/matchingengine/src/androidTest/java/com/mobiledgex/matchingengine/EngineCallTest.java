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

import android.app.UiAutomation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Network;
import android.os.Environment;
import android.os.Looper;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.common.base.Stopwatch;
import com.mobiledgex.matchingengine.performancemetrics.NetTest;
import com.mobiledgex.matchingengine.performancemetrics.Site;
import com.mobiledgex.matchingengine.util.MeLocation;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;

import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;

import android.os.Build;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import distributed_match_engine.AppClient;
import distributed_match_engine.Appcommon.AppPort;
import io.grpc.StatusRuntimeException;

import static junit.framework.TestCase.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.location.Location;
import android.util.Log;
import android.util.Pair;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import javax.net.ssl.SSLPeerUnverifiedException;

@RunWith(AndroidJUnit4.class)
public class EngineCallTest {
    public static final String TAG = "EngineCallTest";
    public static final long GRPC_TIMEOUT_MS = 21000;

    // There's no clear way to get this programmatically outside the app signing certificate, and may
    // not be required in the future.
    public static final String organizationName = "MobiledgeX";
    // Other globals:
    public static final String applicationName = "MobiledgeX SDK Demo";
    public static final String appVersion = "2.0";

    FusedLocationProviderClient fusedLocationClient;

    public static String hostOverride = "wifi.dme.mobiledgex.net";
    public static int portOverride = 50051;
    public static String findCloudletCarrierOverride = "GDDT"; // Allow "Any" if using "", but this likely breaks test cases.

    public boolean useHostOverride = true;
    public boolean useWifiOnly = true; // This also disables network switching, since the android default is WiFi.


    @Before
    public void LooperEnsure() {
        // SubscriberManager needs a thread. Start one:
        if (Looper.myLooper()==null)
            Looper.prepare();
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
                    "android.permission.ACCESS_FINE_LOCATION"
            );
        }
    }
    // Mini test of wifi only:
    @Test
    public void testWiFiOnly() {
        useWifiOnly = true;

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MatchingEngine me = new MatchingEngine(context);
        me.setUseWifiOnly(useWifiOnly);
        assertEquals(true, me.isUseWifiOnly());
        String overrideHost = "";
        try {
            overrideHost = me.generateDmeHostAddress();
        } catch (DmeDnsException dde) {
            assertTrue("Cannot set to use WiFi! DNS failure!", false);
        }
        assertEquals(me.wifiOnlyDmeHost, overrideHost);
        me.setUseWifiOnly(useWifiOnly = false);
        assertEquals(false, me.isUseWifiOnly());
    }

    private void copyFile(File source, File destination) {
        FileChannel inputChannel = null;
        FileChannel outputChannel = null;
        try {
            inputChannel = new FileInputStream(source).getChannel();
            outputChannel = new FileOutputStream(destination).getChannel();
            outputChannel.transferFrom(inputChannel, 0, inputChannel.size());
        } catch (IOException ioe) {
            assertFalse(ioe.getMessage(), true);
        } finally {
            try {
                if (inputChannel != null) {
                    inputChannel.close();
                }
            } catch (IOException ioe) {
                ioe.printStackTrace();
            }
            try {
                if (outputChannel != null) {
                    outputChannel.close();
                }
            } catch (IOException ioe) {
                ioe.printStackTrace();
            }

        }
    }

    public void installTestCertificates() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        // Open and write some certs the test "App" needs.
        File filesDir = context.getFilesDir();
        File externalFilesDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS).getPath());

        // Read test only certs from Downloads folder, and copy them to filesDir.
        String [] fileNames = {
                "mex-ca.crt",
                "mex-client.crt",
                "mex-client.key"
        };
        for (int i = 0; i < fileNames.length; i++) {
            File srcFile = new File(externalFilesDir.getAbsolutePath() + "/" + fileNames[i]);
            File destFile = new File(filesDir.getAbsolutePath() + "/" + fileNames[i]);
            copyFile(srcFile, destFile);
        }

    }

    /**
     * Enable or Disable MockLocation.
     * @param context
     * @param enableMock
     * @return
     */
    public boolean enableMockLocation(Context context, boolean enableMock) {
        if (fusedLocationClient == null) {
            fusedLocationClient = new FusedLocationProviderClient(context);
        }
        if (enableMock == false) {
            fusedLocationClient.setMockMode(false);
            return false;
        } else {
            fusedLocationClient.setMockMode(true);
            return true;
        }
    }

    /**
     * Utility Func. Single point mock location, fills in some extra fields. Does not calculate speed, nor update interval.
     * @param context
     * @param location
     */
    public void setMockLocation(Context context, Location location) throws InterruptedException {
        if (fusedLocationClient == null) {
            fusedLocationClient = new FusedLocationProviderClient(context);
        }

        location.setTime(System.currentTimeMillis());
        location.setElapsedRealtimeNanos(1000);
        location.setAccuracy(3f);
        fusedLocationClient.setMockLocation(location);
        synchronized (location) {
            try {
                location.wait(1500); // Give Mock a bit of time to take effect.
            } catch (InterruptedException ie) {
                throw ie;
            }
        }
        fusedLocationClient.flushLocations();
    }

    @Test
    public void mexDisabledTest() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(false);
        me.setAllowSwitchIfNoSubscriberInfo(true);
        MeLocation meLoc = new MeLocation(me);

        Location loc = MockUtils.createLocation("mexDisabledTest", 122.3321, 47.6062);

        try {
            enableMockLocation(context, true);
            setMockLocation(context, loc);

            Location location = meLoc.getBlocking(context, GRPC_TIMEOUT_MS);

            AppClient.RegisterClientRequest registerClientRequest = me.createDefaultRegisterClientRequest(context, organizationName).build();
            assertTrue(registerClientRequest == null);

            AppClient.FindCloudletRequest findCloudletRequest;
            findCloudletRequest = me.createDefaultFindCloudletRequest(context, location)
                .setCarrierName(findCloudletCarrierOverride)
                .build();
            assertTrue(findCloudletRequest == null);

            AppClient.GetLocationRequest locationRequest = me.createDefaultGetLocationRequest(context).build();
            assertTrue(locationRequest == null);

            AppClient.VerifyLocationRequest verifyLocationRequest = me.createDefaultVerifyLocationRequest(context, location).build();
            assertTrue(verifyLocationRequest == null);

            AppClient.AppInstListRequest appInstListRequest = me.createDefaultAppInstListRequest(context, location).build();
            assertTrue(appInstListRequest == null);

        } catch (PackageManager.NameNotFoundException nnfe) {
            Log.e(TAG, Log.getStackTraceString(nnfe));
            assertFalse("mexDisabledTest: NameNotFoundException", true);
        } catch (ExecutionException ee) {
            Log.e(TAG, Log.getStackTraceString(ee));
            assertFalse("mexDisabledTest: ExecutionException!", true);
        } catch (StatusRuntimeException sre) {
            Log.e(TAG, Log.getStackTraceString(sre));
            assertFalse("mexDisabledTest: StatusRuntimeException!", true);
        } catch (InterruptedException ie) {
            Log.e(TAG, Log.getStackTraceString(ie));
            assertFalse("mexDisabledTest: InterruptedException!", true);
        }  catch (Exception e) {
            Log.e(TAG, "Creation of request is not supposed to succeed!");
            Log.e(TAG, Log.getStackTraceString(e));
        } finally {
            enableMockLocation(context,false);
        }
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
        } catch (DmeDnsException dde) {
            Log.e(TAG, Log.getStackTraceString(dde));
            assertTrue("ExecutionException registering client.", false);
        } catch (ExecutionException ee) {
            Log.e(TAG, Log.getStackTraceString(ee));
            assertTrue("ExecutionException registering client", false);
        } catch (InterruptedException ioe) {
            Log.e(TAG, Log.getStackTraceString(ioe));
            assertTrue("InterruptedException registering client", false);
        }
    }

    @Test
    public void registerClientTest() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);

        MeLocation meLoc = new MeLocation(me);
        Location location;
        AppClient.RegisterClientReply reply = null;
        String appName = applicationName;

        enableMockLocation(context,true);
        Location loc = MockUtils.createLocation("registerClientTest", 122.3321, 47.6062);

        try {
            setMockLocation(context, loc);

            location = meLoc.getBlocking(context, GRPC_TIMEOUT_MS);
            assertFalse(location == null);

            AppClient.RegisterClientRequest request = me.createDefaultRegisterClientRequest(context, organizationName)
                    .setAppName(applicationName)
                    .setAppVers(appVersion)
                    .setCellId(me.retrieveCellId(context).get(0).second.intValue())
                    .build();
            if (useHostOverride) {
                reply = me.registerClient(request, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                reply = me.registerClient(request, me.generateDmeHostAddress(), me.getPort(), GRPC_TIMEOUT_MS);
            }
            assertTrue(reply != null);
            assertTrue(reply.getStatus() == AppClient.ReplyStatus.RS_SUCCESS);
            assertTrue( !reply.getUniqueId().isEmpty());
            assertTrue( reply.getSessionCookie().length() > 0);
        } catch (PackageManager.NameNotFoundException nnfe) {
            Log.e(TAG, Log.getStackTraceString(nnfe));
            assertFalse("ExecutionException registering using PackageManager.", true);
        } catch (DmeDnsException dde) {
            Log.e(TAG, Log.getStackTraceString(dde));
            assertFalse("registerClientTest: DmeDnsException!", true);
        } catch (ExecutionException ee) {
            Log.e(TAG, Log.getStackTraceString(ee));
            assertFalse("registerClientTest: ExecutionException!", true);
        } catch (StatusRuntimeException sre) {
            Log.e(TAG, Log.getStackTraceString(sre));
            assertFalse("registerClientTest: StatusRuntimeException!", true);
        } catch (InterruptedException ie) {
            Log.e(TAG, Log.getStackTraceString(ie));
            assertFalse("registerClientTest: InterruptedException!", true);
        } finally {
            enableMockLocation(context,false);
        }


        assertEquals("Sessions must be equal.", reply.getSessionCookie(), me.getSessionCookie());
        // Temporary.
        Log.i(TAG, "registerClientTest reply: " + reply.toString());
        assertEquals(0, reply.getVer());
        assertEquals(AppClient.ReplyStatus.RS_SUCCESS, reply.getStatus());
    }

    @Test
    public void registerClientFutureTest() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);

        MeLocation meLoc = new MeLocation(me);
        Location location;
        Future<AppClient.RegisterClientReply> registerReplyFuture;
        AppClient.RegisterClientReply reply = null;

        enableMockLocation(context,true);
        Location loc = MockUtils.createLocation("RegisterClientFutureTest", 122.3321, 47.6062);

        try {
            setMockLocation(context, loc);

            location = meLoc.getBlocking(context, GRPC_TIMEOUT_MS);
            assertFalse(location == null);

            AppClient.RegisterClientRequest request = me.createDefaultRegisterClientRequest(context, organizationName)
                    .setAppName(applicationName)
                    .setAppVers(appVersion)
                    .setCellId(me.retrieveCellId(context).get(0).second.intValue())
                    .setUniqueIdType("applicationInstallId")
                    .setUniqueId(me.getUniqueId(context))
                    .build();
            if (useHostOverride) {
                registerReplyFuture = me.registerClientFuture(request, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                registerReplyFuture = me.registerClientFuture(request, GRPC_TIMEOUT_MS);
            }
            reply = registerReplyFuture.get();
            assert(reply != null);
        } catch (PackageManager.NameNotFoundException nnfe) {
            Log.e(TAG, Log.getStackTraceString(nnfe));
            assertFalse("ExecutionException registering using PackageManager.", true);
        } catch (DmeDnsException dde) {
            Log.e(TAG, Log.getStackTraceString(dde));
            assertFalse("ExecutionException registering client.", true);
        } catch (ExecutionException ee) {
            Log.e(TAG, Log.getStackTraceString(ee));
            assertFalse("registerClientFutureTest: ExecutionException!", true);
        } catch (InterruptedException ie) {
            Log.e(TAG, Log.getStackTraceString(ie));
            assertFalse("registerClientFutureTest: InterruptedException!", true);
        } finally {
            enableMockLocation(context,false);
        }

        assertEquals("Sessions must be equal.", reply.getSessionCookie(), me.getSessionCookie());
        // Temporary.
        Log.i(TAG, "registerClientFutureTest() response: " + reply.toString());
        assertEquals(0, reply.getVer());
        assertEquals(AppClient.ReplyStatus.RS_SUCCESS, reply.getStatus());
    }

    @Test
    public void findCloudletTest() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AppClient.FindCloudletReply findCloudletReply1 = null;
        AppClient.FindCloudletReply findCloudletReply2 = null;
        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);
        MeLocation meLoc = new MeLocation(me);

        Location loc = MockUtils.createLocation("findCloudletTest", 122.3321, 47.6062);

        try {
            enableMockLocation(context, true);
            setMockLocation(context, loc);

            Location location = meLoc.getBlocking(context, GRPC_TIMEOUT_MS);

            String carrierName = me.getCarrierName(context);
            registerClient(me);

            // Set orgName and location, then override the rest for testing:
            AppClient.FindCloudletRequest findCloudletRequest = me.createDefaultFindCloudletRequest(context, location)
                .setCarrierName(findCloudletCarrierOverride)
                .build();
            if (useHostOverride) {
                findCloudletReply1 = me.findCloudlet(findCloudletRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                findCloudletReply1 = me.findCloudlet(findCloudletRequest, GRPC_TIMEOUT_MS);
            }

            long size1 = me.getNetTest().sortedSiteList().size();

            // Second try:
            me.setThreadedPerformanceTest(true);
            if (useHostOverride) {
                findCloudletReply2 = me.findCloudlet(findCloudletRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                findCloudletReply2 = me.findCloudlet(findCloudletRequest, GRPC_TIMEOUT_MS);
            }

            long size2 = me.getNetTest().sortedSiteList().size();

            assertEquals("Sizes should match!", size1, size2);

        } catch (DmeDnsException dde) {
            Log.e(TAG, Log.getStackTraceString(dde));
            assertFalse("FindCloudlet: DmeDnsException", true);
        } catch (ExecutionException ee) {
            Log.e(TAG, Log.getStackTraceString(ee));
            assertFalse("FindCloudlet: ExecutionException!", true);
        } catch (StatusRuntimeException sre) {
            Log.e(TAG, sre.getMessage());
            Log.e(TAG, Log.getStackTraceString(sre));
            assertFalse("FindCloudlet: StatusRunTimeException!", true);
        } catch (InterruptedException ie) {
            Log.e(TAG, Log.getStackTraceString(ie));
            assertFalse("FindCloudlet: InterruptedException!", true);
        } finally {
            enableMockLocation(context,false);
        }

        assertNotNull("FindCloudletReply1 is null!", findCloudletReply1);
        assertNotNull("FindCloudletReply2 is null!", findCloudletReply2);

        NetTest netTest = me.getNetTest();
        if (!findCloudletReply1.getFqdn().equals(findCloudletReply2.getFqdn())) {
            Site site1 = netTest.getSite(findCloudletReply1.getPorts(0).getFqdnPrefix() + findCloudletReply1.getFqdn());
            Site site2 = netTest.getSite(findCloudletReply2.getPorts(0).getFqdnPrefix() + findCloudletReply2.getFqdn());
            double margin = Math.abs(site1.average-site2.average)/site2.average;
            assertTrue("Winner Not within 15% margin: " + margin, margin < .15d);
        }


        // Might also fail, since the network is not under test control:
        assertEquals("App's expected test cloudlet FQDN doesn't match.", "sdkdemo-app-cluster.fairview-main.gddt.mobiledgex.net", findCloudletReply1.getFqdn());
    }

    // This test only tests "" any, and not subject to the global override.
    @Test
    public void findCloudletTestSetCarrierNameAnyOverride() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AppClient.FindCloudletReply findCloudletReply = null;
        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);
        MeLocation meLoc = new MeLocation(me);

        Location loc = MockUtils.createLocation("findCloudletTestSetCarrierNameOverride", 122.3321, 47.6062);

        boolean expectedExceptionHit = false;
        try {
            enableMockLocation(context, true);
            setMockLocation(context, loc);

            Location location = meLoc.getBlocking(context, GRPC_TIMEOUT_MS);

            String carrierName = me.getCarrierName(context);
            registerClient(me);

            // Set NO carrier name, as if there's no SIM card. This should tell DME to return
            // any edge AppInst from any carrier, for this app, version, orgName keyset.
            AppClient.FindCloudletRequest findCloudletRequest2 = me.createDefaultFindCloudletRequest(context, location)
                .setCarrierName("")
                .build();
            if (useHostOverride) {
              findCloudletReply = me.findCloudlet(findCloudletRequest2, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
              findCloudletReply = me.findCloudlet(findCloudletRequest2, GRPC_TIMEOUT_MS);
            }
            assertTrue(findCloudletReply != null);
            assertTrue(findCloudletReply.getStatus().equals(AppClient.FindCloudletReply.FindStatus.FIND_FOUND));
        } catch (DmeDnsException dde) {
            Log.e(TAG, Log.getStackTraceString(dde));
            assertFalse("FindCloudlet: DmeDnsException", true);
        } catch (ExecutionException ee) {
            Log.e(TAG, Log.getStackTraceString(ee));
            assertFalse("FindCloudlet: ExecutionException!", true);
        } catch (StatusRuntimeException sre) {
            /* This is expected! */
            Log.e(TAG, sre.getMessage());
            Log.e(TAG, Log.getStackTraceString(sre));
            expectedExceptionHit = true;
            assertTrue("FindCloudlet: Expected StatusRunTimeException!", true);
        } catch (InterruptedException ie) {
            Log.e(TAG, Log.getStackTraceString(ie));
            assertFalse("FindCloudlet: InterruptedException!", true);
        } finally {
            enableMockLocation(context,false);
        }

        assertFalse("findCloudletTestSetAllOptionalDevAppNameVers: NO Expected StatusRunTimeException about 'NO PERMISSION'", expectedExceptionHit);
    }

    @Test
    public void findCloudletFutureTest() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Future<AppClient.FindCloudletReply> response;
        AppClient.FindCloudletReply findCloudletReply1 = null;
        AppClient.FindCloudletReply findCloudletReply2 = null;
        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);
        MeLocation meLoc = new MeLocation(me);

        Location loc = MockUtils.createLocation("findCloudletTest", 122.3321, 47.6062);

        try {
            enableMockLocation(context, true);
            setMockLocation(context, loc);

            Location location = meLoc.getBlocking(context, 10000);

            registerClient(me);

            // Cannot use the older API if overriding.
            AppClient.FindCloudletRequest findCloudletRequest = me.createDefaultFindCloudletRequest(context, location)
                .setCarrierName(findCloudletCarrierOverride)
                .build();

            if (useHostOverride) {
                response = me.findCloudletFuture(findCloudletRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS,
                  MatchingEngine.FindCloudletMode.PERFORMANCE);
            } else {
                response = me.findCloudletFuture(findCloudletRequest, 10000);
            }
            findCloudletReply1 = response.get();
            long size1 = me.getNetTest().sortedSiteList().size();

            // Second try:
            me.setThreadedPerformanceTest(true);
            if (useHostOverride) {
                response = me.findCloudletFuture(findCloudletRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS,
                  MatchingEngine.FindCloudletMode.PERFORMANCE);
            } else {
                response = me.findCloudletFuture(findCloudletRequest, GRPC_TIMEOUT_MS);
            }
            findCloudletReply2 = response.get();
            long size2 = me.getNetTest().sortedSiteList().size();

            assertEquals("Sizes should match!", size1, size2);
        } catch (DmeDnsException dde) {
            Log.e(TAG, Log.getStackTraceString(dde));
            assertFalse("FindCloudletFuture: DmeDnsException", true);
        } catch (ExecutionException ee) {
            Log.e(TAG, Log.getStackTraceString(ee));
            assertFalse("FindCloudletFuture: ExecutionExecution!", true);
        } catch (InterruptedException ie) {
            Log.e(TAG, Log.getStackTraceString(ie));
            assertFalse("FindCloudletFuture: InterruptedException!", true);
        } finally {
            enableMockLocation(context,false);
        }


        assertNotNull("FindCloudletReply1 is null!", findCloudletReply1);
        assertNotNull("FindCloudletReply2 is null!", findCloudletReply2);

        NetTest netTest = me.getNetTest();
        if (!findCloudletReply1.getFqdn().equals(findCloudletReply2.getFqdn())) {
            Site site1 = netTest.getSite(findCloudletReply1.getPorts(0).getFqdnPrefix() + findCloudletReply1.getFqdn());
            Site site2 = netTest.getSite(findCloudletReply2.getPorts(0).getFqdnPrefix() + findCloudletReply2.getFqdn());
            double margin = Math.abs(site1.average-site2.average)/site2.average;
            assertTrue("Winner Not within 15% margin: " + margin, margin < .15d);
        }

        // Might also fail, since the network is not under test control:
        assertEquals("App's expected test cloudlet FQDN doesn't match.", "sdkdemo-app-cluster.fairview-main.gddt.mobiledgex.net", findCloudletReply1.getFqdn());
    }

    @Test
    public void verifyLocationTest() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);
        MeLocation meLoc = new MeLocation(me);
        AppClient.VerifyLocationReply verifyLocationReply = null;

        try {
            enableMockLocation(context, true);
            Location mockLoc = MockUtils.createLocation("verifyLocationTest", 122.3321, 47.6062);
            setMockLocation(context, mockLoc);

            Location location = meLoc.getBlocking(context, GRPC_TIMEOUT_MS);

            String carrierName = me.getCarrierName(context);
            registerClient(me);

            AppClient.VerifyLocationRequest verifyLocationRequest = me.createDefaultVerifyLocationRequest(context, location)
                    .setCarrierName(carrierName)
                    .build();

            if (useHostOverride) {
                verifyLocationReply = me.verifyLocation(verifyLocationRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                verifyLocationReply = me.verifyLocation(verifyLocationRequest, GRPC_TIMEOUT_MS);
            }
            assert (verifyLocationReply != null);
        } catch (DmeDnsException dde) {
            Log.e(TAG, Log.getStackTraceString(dde));
            assertFalse("VerifyLocation: DmeDnsException", true);
        } catch (IOException ioe) {
            Log.e(TAG, Log.getStackTraceString(ioe));
            assertFalse("VerifyLocation: IOException!", true);
        } catch (ExecutionException ee) {
            Log.e(TAG, Log.getStackTraceString(ee));
            assertFalse("VerifyLocation: ExecutionExecution!", true);
        } catch (StatusRuntimeException sre) {
            Log.e(TAG, Log.getStackTraceString(sre));
            assertFalse("VerifyLocation: StatusRuntimeException!", true);
        } catch (InterruptedException ie) {
            Log.e(TAG, Log.getStackTraceString(ie));
            assertFalse("VerifyLocation: InterruptedException!", true);
        } finally {
            enableMockLocation(context, false);
        }


        // Temporary.
        assertEquals(0, verifyLocationReply.getVer());
        assertEquals(AppClient.VerifyLocationReply.TowerStatus.TOWER_UNKNOWN, verifyLocationReply.getTowerStatus());
        assertEquals(AppClient.VerifyLocationReply.GPSLocationStatus.LOC_ROAMING_COUNTRY_MATCH, verifyLocationReply.getGpsLocationStatus());
    }

    @Test
    public void verifyLocationFutureTest() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);
        MeLocation meLoc = new MeLocation(me);
        AppClient.VerifyLocationReply verifyLocationReply = null;
        Future<AppClient.VerifyLocationReply> verifyLocationReplyFuture = null;

        try {
            enableMockLocation(context, true);
            Location mockLoc = MockUtils.createLocation("verifyLocationFutureTest", 122.3321, 47.6062);
            setMockLocation(context, mockLoc);

            Location location = meLoc.getBlocking(context, GRPC_TIMEOUT_MS);

            String carrierName = me.getCarrierName(context);
            registerClient(me);
            AppClient.VerifyLocationRequest verifyLocationRequest = me.createDefaultVerifyLocationRequest(context, location)
                    .setCarrierName(carrierName)
                    .build();
            if (useHostOverride) {
                verifyLocationReplyFuture = me.verifyLocationFuture(verifyLocationRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                verifyLocationReplyFuture = me.verifyLocationFuture(verifyLocationRequest, GRPC_TIMEOUT_MS);
            }
            verifyLocationReply = verifyLocationReplyFuture.get();
        } catch (DmeDnsException dde) {
            Log.e(TAG, Log.getStackTraceString(dde));
            assertFalse("verifyLocationFutureTest: DmeDnsException", true);
        } catch (ExecutionException ee) {
            Log.e(TAG, Log.getStackTraceString(ee));
            assertFalse("verifyLocationFutureTest: ExecutionException Failed!", true);
        } catch (InterruptedException ie) {
            Log.e(TAG, Log.getStackTraceString(ie));
            assertFalse("verifyLocationFutureTest: InterruptedException!", true);
        } finally {
            enableMockLocation(context,false);
        }


        // Temporary.
        assertEquals(0, verifyLocationReply.getVer());
        assertEquals(AppClient.VerifyLocationReply.TowerStatus.TOWER_UNKNOWN, verifyLocationReply.getTowerStatus());
        assertEquals(AppClient.VerifyLocationReply.GPSLocationStatus.LOC_ROAMING_COUNTRY_MATCH, verifyLocationReply.getGpsLocationStatus());
    }


    /**
     * Mocked Location test. Note that responsibility of verifying location is in the MatchingEngine
     * on the server side, not client side.
     */
    @Test
    public void verifyMockedLocationTest_NorthPole() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        enableMockLocation(context,true);

        Location mockLoc = MockUtils.createLocation("verifyMockedLocationTest_NorthPole", 90d, 1d);


        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);
        MeLocation meLoc = new MeLocation(me);

        AppClient.VerifyLocationReply verifyLocationReply = null;
        try {
            setMockLocation(context, mockLoc); // North Pole.

            Location location = meLoc.getBlocking(context, GRPC_TIMEOUT_MS);
            assertFalse(location == null);

            String carrierName = me.getCarrierName(context);
            registerClient(me);
            AppClient.VerifyLocationRequest verifyLocationRequest = me.createDefaultVerifyLocationRequest(context, location)
                    .setCarrierName(carrierName)
                    .build();
            if (useHostOverride) {
                verifyLocationReply = me.verifyLocation(verifyLocationRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                verifyLocationReply = me.verifyLocation(verifyLocationRequest, GRPC_TIMEOUT_MS);
            }
            assert(verifyLocationReply != null);
        } catch (DmeDnsException dde) {
            Log.e(TAG, Log.getStackTraceString(dde));
            assertFalse("verifyMockedLocationTest_NorthPole: DmeDnsException", true);
        } catch (IOException ioe) {
            Log.e(TAG, Log.getStackTraceString(ioe));
            assertFalse("verifyMockedLocationTest_NorthPole: IOException!", true);
        } catch (ExecutionException ee) {
            Log.e(TAG, Log.getStackTraceString(ee));
            assertFalse("verifyMockedLocationTest_NorthPole: ExecutionException!", true);
        } catch (InterruptedException ie) {
            Log.e(TAG, Log.getStackTraceString(ie));
            assertFalse("verifyMockedLocationTest_NorthPole: InterruptedException!", true);
        } finally {
            enableMockLocation(context,false);
        }

        // Temporary.
        assertEquals(0, verifyLocationReply.getVer());
        assertEquals(AppClient.VerifyLocationReply.TowerStatus.TOWER_UNKNOWN, verifyLocationReply.getTowerStatus());
        assertEquals(AppClient.VerifyLocationReply.GPSLocationStatus.LOC_ROAMING_COUNTRY_MATCH, verifyLocationReply.getGpsLocationStatus()); // Based on test data.

    }

    @Test
    public void getLocationTest() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);
        MeLocation meLoc = new MeLocation(me);
        Location location;
        AppClient.GetLocationReply getLocationReply = null;

        enableMockLocation(context,true);
        Location loc = MockUtils.createLocation("getLocationTest", 122.3321, 47.6062);

        String carrierName = me.getCarrierName(context);
        try {
            setMockLocation(context, loc);

            location = meLoc.getBlocking(context, GRPC_TIMEOUT_MS);
            assertFalse(location == null);


            registerClient(me);
            AppClient.GetLocationRequest getLocationRequest = me.createDefaultGetLocationRequest(context)
                    .build();

            if (useHostOverride) {
                getLocationReply = me.getLocation(getLocationRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                getLocationReply = me.getLocation(context, getLocationRequest, GRPC_TIMEOUT_MS);
            }
            assert(getLocationReply != null);
        } catch (DmeDnsException dde) {
            Log.e(TAG, Log.getStackTraceString(dde));
            assertFalse("getLocationTest: DmeDnsException", true);
        } catch (ExecutionException ee) {
            Log.e(TAG, Log.getStackTraceString(ee));
            assertFalse("getLocationTest: ExecutionExecution!", true);
        } catch (StatusRuntimeException sre) {
            Log.e(TAG, Log.getStackTraceString(sre));
            assertFalse("getLocationTest: StatusRuntimeException Failed!", true);
        } catch (InterruptedException ie) {
            Log.e(TAG, Log.getStackTraceString(ie));
            assertFalse("getLocationTest: InterruptedException!", true);
        } finally {
            enableMockLocation(context,false);
        }

        // Temporary.
        Log.i(TAG, "getLocation() response: " + getLocationReply.toString());
        assertEquals(0, getLocationReply.getVer());

        assertEquals(carrierName, getLocationReply.getCarrierName());
        assertEquals(AppClient.GetLocationReply.LocStatus.LOC_FOUND, getLocationReply.getStatus());

        assertEquals(0, getLocationReply.getTower());
        // FIXME: Server is currently a pure echo of client location.
        assertEquals((int) loc.getLatitude(), (int) getLocationReply.getNetworkLocation().getLatitude());
        assertEquals((int) loc.getLongitude(), (int) getLocationReply.getNetworkLocation().getLongitude());
    }

    @Test
    public void getLocationFutureTest() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);

        MeLocation meLoc = new MeLocation(me);
        Location location;
        Future<AppClient.GetLocationReply> getLocationReplyFuture;
        AppClient.GetLocationReply getLocationReply = null;

        enableMockLocation(context,true);
        Location loc = MockUtils.createLocation("getLocationTest", 122.3321, 47.6062);

        String carrierName = me.getCarrierName(context);
        try {
            // Directly create request for testing:
            // Passed in Location (which is a callback interface)
            setMockLocation(context, loc);

            location = meLoc.getBlocking(context, GRPC_TIMEOUT_MS);
            assertFalse(location == null);


            registerClient(me);
            AppClient.GetLocationRequest getLocationRequest = me.createDefaultGetLocationRequest(context)
                    .build();

            if (useHostOverride) {
                getLocationReplyFuture = me.getLocationFuture(getLocationRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                getLocationReplyFuture = me.getLocationFuture(context, getLocationRequest, GRPC_TIMEOUT_MS);
            }
            getLocationReply = getLocationReplyFuture.get();
            assert(getLocationReply != null);
        } catch (DmeDnsException dde) {
            Log.e(TAG, Log.getStackTraceString(dde));
            assertFalse("getLocationFutureTest: DmeDnsException", true);
        } catch (ExecutionException ee) {
            Log.e(TAG, Log.getStackTraceString(ee));
            assertFalse("getLocationFutureTest: ExecutionException!", true);
        } catch (InterruptedException ie) {
            Log.e(TAG, Log.getStackTraceString(ie));
            assertFalse("getLocationFutureTest: InterruptedException!", true);
        } finally {
            enableMockLocation(context,false);
        }

        // Temporary.
        Log.i(TAG, "getLocationFutureTest() response: " + getLocationReply.toString());
        assertEquals(0, getLocationReply.getVer());
        assertEquals(carrierName, getLocationReply.getCarrierName());
        assertEquals(AppClient.GetLocationReply.LocStatus.LOC_FOUND, getLocationReply.getStatus());

        assertEquals(getLocationReply.getTower(), 0);
        // FIXME: Server is currently a pure echo of client location.
        assertEquals((int) loc.getLatitude(), (int) getLocationReply.getNetworkLocation().getLatitude());
        assertEquals((int) loc.getLongitude(), (int) getLocationReply.getNetworkLocation().getLongitude());

        assertEquals("Carriers must match", carrierName, getLocationReply.getCarrierName());
    }

    @Test
    public void dynamicLocationGroupAddTest() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);

        AppClient.DynamicLocGroupReply dynamicLocGroupReply = null;

        enableMockLocation(context,true);
        Location location = MockUtils.createLocation("createDynamicLocationGroupAddTest", 122.3321, 47.6062);
        MeLocation meLoc = new MeLocation(me);

        String carrierName = me.getCarrierName(context);
        try {
            setMockLocation(context, location);

            location = meLoc.getBlocking(context, GRPC_TIMEOUT_MS);
            assertFalse(location == null);

            registerClient(me);

            // FIXME: Need groupId source.
            long groupId = 1001L;
            AppClient.DynamicLocGroupRequest dynamicLocGroupRequest = me.createDefaultDynamicLocGroupRequest(
                    context,
                    AppClient.DynamicLocGroupRequest.DlgCommType.DLG_SECURE)
                    .build();

            if (useHostOverride) {
                dynamicLocGroupReply = me.addUserToGroup(dynamicLocGroupRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                dynamicLocGroupReply = me.addUserToGroup(dynamicLocGroupRequest, GRPC_TIMEOUT_MS);
            }
            assertTrue("DynamicLocation Group Add should return: ME_SUCCESS", dynamicLocGroupReply.getStatus() == AppClient.ReplyStatus.RS_SUCCESS);
            assertTrue("Group cookie result.", dynamicLocGroupReply.getGroupCookie().equals("")); // FIXME: This GroupCookie should have a value.

        } catch (DmeDnsException dde) {
            Log.e(TAG, Log.getStackTraceString(dde));
            assertFalse("dynamicLocationGroupAddTest: DmeDnsException", true);
        } catch (ExecutionException ee) {
            Log.e(TAG, Log.getStackTraceString(ee));
            assertFalse("dynamicLocationGroupAddTest: ExecutionException!", true);
        } catch (StatusRuntimeException sre) {
            Log.e(TAG, Log.getStackTraceString(sre));
            assertFalse("dynamicLocationGroupAddTest: StatusRuntimeException!", true);
        } catch (InterruptedException ie) {
            Log.e(TAG, Log.getStackTraceString(ie));
            assertFalse("dynamicLocationGroupAddTest: InterruptedException!", true);
        } finally {
            enableMockLocation(context,false);
        }

        assertEquals("Dynamic GroupCookie must match", "", dynamicLocGroupReply.getGroupCookie());
    }

    @Test
    public void dynamicLocationGroupAddFutureTest() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);

        AppClient.DynamicLocGroupReply dynamicLocGroupReply = null;

        enableMockLocation(context,true);
        Location location = MockUtils.createLocation("createDynamicLocationGroupAddTest", 122.3321, 47.6062);
        MeLocation meLoc = new MeLocation(me);

        String carrierName = me.getCarrierName(context);
        try {
            setMockLocation(context, location);

            location = meLoc.getBlocking(context, GRPC_TIMEOUT_MS);
            assertFalse(location == null);

            registerClient(me);

            // FIXME: Need groupId source.
            long groupId = 1001L;
            AppClient.DynamicLocGroupRequest dynamicLocGroupRequest = me.createDefaultDynamicLocGroupRequest(
                    context,
                    AppClient.DynamicLocGroupRequest.DlgCommType.DLG_SECURE)
                    .build();

            Future<AppClient.DynamicLocGroupReply> responseFuture;
            if (useHostOverride) {
                responseFuture = me.addUserToGroupFuture(dynamicLocGroupRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                responseFuture = me.addUserToGroupFuture(dynamicLocGroupRequest, GRPC_TIMEOUT_MS);
            }
            dynamicLocGroupReply = responseFuture.get();
            assertTrue("DynamicLocation Group Add should return: ME_SUCCESS", dynamicLocGroupReply.getStatus() == AppClient.ReplyStatus.RS_SUCCESS);
            assertTrue("Group cookie result.", dynamicLocGroupReply.getGroupCookie().equals("")); // FIXME: This GroupCookie should have a value.
        } catch (DmeDnsException dde) {
            Log.e(TAG, Log.getStackTraceString(dde));
            assertFalse("dynamicLocationGroupAddFutureTest: DmeDnsException", true);
        } catch (ExecutionException ee) {
            Log.e(TAG, Log.getStackTraceString(ee));
            assertFalse("dynamicLocationGroupAddFutureTest: ExecutionException!", true);
        } catch (StatusRuntimeException sre) {
            Log.e(TAG, Log.getStackTraceString(sre));
            assertFalse("dynamicLocationGroupAddFutureTest: StatusRuntimeException!", true);
        } catch (InterruptedException ie) {
            Log.e(TAG, Log.getStackTraceString(ie));
            assertFalse("dynamicLocationGroupAddFutureTest: InterruptedException!", true);
        } finally {
            enableMockLocation(context,false);
        }

        // Temporary.
        assertEquals("SessionCookies must match", "", dynamicLocGroupReply.getGroupCookie());
    }

    @Test
    public void getAppInstListTest() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);

        AppClient.AppInstListReply appInstListReply = null;

        enableMockLocation(context,true);
        Location location = MockUtils.createLocation("getCloudletListTest", 122.3321, 50.1109);
        MeLocation meLoc = new MeLocation(me);

        try {
            setMockLocation(context, location);

            location = meLoc.getBlocking(context, GRPC_TIMEOUT_MS);
            assertFalse("Mock'ed Location is missing!", location == null);

            registerClient(me);
            AppClient.AppInstListRequest appInstListRequest;
            AppClient.AppInstListReply list;
            appInstListRequest  = me.createDefaultAppInstListRequest(context, location)
                    .setCarrierName(findCloudletCarrierOverride)
                    .build();
            if (useHostOverride) {
                list = me.getAppInstList(appInstListRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                list = me.getAppInstList(appInstListRequest, GRPC_TIMEOUT_MS);
            }

            assertEquals(0, list.getVer());
            assertEquals(AppClient.AppInstListReply.AIStatus.AI_SUCCESS, list.getStatus());
            assertEquals(3, list.getCloudletsCount()); // NOTE: This is entirely test server dependent.
            for (int i = 0; i < list.getCloudletsCount(); i++) {
                Log.v(TAG, "Cloudlet: " + list.getCloudlets(i).toString());
            }

        } catch (DmeDnsException dde) {
            Log.e(TAG, Log.getStackTraceString(dde));
            assertFalse("getAppInstListTest: DmeDnsException", true);
        } catch (ExecutionException ee) {
            Log.i(TAG, Log.getStackTraceString(ee));
            assertFalse("getAppInstListTest: ExecutionException!", true);
        } catch (StatusRuntimeException sre) {
            Log.i(TAG, Log.getStackTraceString(sre));
            Log.i(TAG, sre.getMessage());
            assertFalse("getAppInstListTest: StatusRuntimeException!", true);
        } catch (InterruptedException ie) {
            Log.i(TAG, Log.getStackTraceString(ie));
            assertFalse("getAppInstListTest: InterruptedException!", true);
        } finally {
            enableMockLocation(context,false);
        }
    }

    @Test
    public void getAppInstListFutureTest() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);

        enableMockLocation(context,true);
        Location location = MockUtils.createLocation("getAppInstListFutureTest", 122.3321, 47.6062);
        MeLocation meLoc = new MeLocation(me);

        try {
            setMockLocation(context, location);

            location = meLoc.getBlocking(context, GRPC_TIMEOUT_MS);
            assertFalse("Mock'ed Location is missing!", location == null);

            registerClient(me);
            AppClient.AppInstListRequest appInstListRequest = me.createDefaultAppInstListRequest(context, location)
                    .setCarrierName(findCloudletCarrierOverride)
                    .build();

            Future<AppClient.AppInstListReply> listFuture;
            if (useHostOverride) {
                listFuture = me.getAppInstListFuture(appInstListRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                listFuture = me.getAppInstListFuture(appInstListRequest, GRPC_TIMEOUT_MS);
            }
            AppClient.AppInstListReply list = listFuture.get();

            assertEquals(0, list.getVer());
            assertEquals(AppClient.AppInstListReply.AIStatus.AI_SUCCESS, list.getStatus());
            assertEquals(3, list.getCloudletsCount()); // NOTE: This is entirely test server dependent.
            for (int i = 0; i < list.getCloudletsCount(); i++) {
                Log.v(TAG, "Cloudlet: " + list.getCloudlets(i).toString());
            }

        } catch (DmeDnsException dde) {
            Log.e(TAG, Log.getStackTraceString(dde));
            assertFalse("getAppInstListFutureTest: DmeDnsException", true);
        } catch (ExecutionException ee) {
            Log.i(TAG, Log.getStackTraceString(ee));
            assertFalse("getAppInstListFutureTest: ExecutionException!", true);
        } catch (StatusRuntimeException sre) {
            Log.i(TAG, sre.getMessage());
            Log.i(TAG, Log.getStackTraceString(sre));
            assertFalse("getAppInstListFutureTest: StatusRuntimeException!", true);
        } catch (InterruptedException ie) {
            Log.i(TAG, Log.getStackTraceString(ie));
            assertFalse("getAppInstListFutureTest: InterruptedException!", true);
        } finally {
            enableMockLocation(context,false);
        }
    }

    @Test
    public void getQosPositionKpiTest() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);

        enableMockLocation(context,true);
        // The test must use a location where data exists on QOS server.
        Location location = MockUtils.createLocation("getQosPositionKpiTest", 8.5821, 50.11);

        ChannelIterator<AppClient.QosPositionKpiReply> responseIterator = null;
        try {
            registerClient(me);

            double totalDistanceKm = 20;
            double increment = 0.1;
            double direction = 45d;

            ArrayList<AppClient.QosPosition> kpiRequests = MockUtils.createQosPositionArray(location, direction, totalDistanceKm, increment);

            AppClient.QosPositionRequest request = me.createDefaultQosPositionRequest(kpiRequests, 0, null).build();
            assertFalse("SessionCookie must not be empty.", request.getSessionCookie().isEmpty());

            if (useHostOverride) {
                responseIterator = me.getQosPositionKpi(request, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                responseIterator = me.getQosPositionKpi(request, GRPC_TIMEOUT_MS);
            }
            // A stream of QosPositionKpiReply(s), with a non-stream block of responses.
            long total = 0;
            while (responseIterator.hasNext()) {
                AppClient.QosPositionKpiReply aR = responseIterator.next();
                for (int i = 0; i < aR.getPositionResultsCount(); i++) {
                    System.out.println(aR.getPositionResults(i));
                }
                total += aR.getPositionResultsCount();
            }
            responseIterator.shutdown();
            assertEquals((long)(kpiRequests.size()), total);
        } catch (DmeDnsException dde) {
            Log.i(TAG, Log.getStackTraceString(dde));
            assertFalse("queryQosKpiTest: DmeDnsException!", true);
        } catch (ExecutionException ee) {
            Log.i(TAG, Log.getStackTraceString(ee));
            assertFalse("queryQosKpiTest: ExecutionException!", true);
        } catch (StatusRuntimeException sre) {
            Log.i(TAG, sre.getMessage());
            Log.i(TAG, Log.getStackTraceString(sre));
            assertFalse("queryQosKpiTest: StatusRuntimeException!", true);
        } catch (InterruptedException ie) {
            Log.i(TAG, Log.getStackTraceString(ie));
            assertFalse("queryQosKpiTest: InterruptedException!", true);
        } finally {
            enableMockLocation(context,false);
            if (responseIterator != null) {
                responseIterator.shutdown();
            }
        }

    }

    @Test
    public void getQosPositionKpiFutureTest() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);

        enableMockLocation(context,true);
        // The test must use a location where data exists on QOS server.
        Location location = MockUtils.createLocation("getQosPositionKpiTest", 8.5821, 50.11);

        try {
            registerClient(me);

            double totalDistanceKm = 20;
            double increment = 0.1;
            double direction = 45d;

            ArrayList<AppClient.QosPosition> kpiRequests = MockUtils.createQosPositionArray(location, direction, totalDistanceKm, increment);

            AppClient.BandSelection bandSelection = AppClient.BandSelection.newBuilder().build();
            AppClient.QosPositionRequest request = me.createDefaultQosPositionRequest(kpiRequests, 0, bandSelection).build();
            assertFalse("SessionCookie must not be empty.", request.getSessionCookie().isEmpty());

            Future<ChannelIterator<AppClient.QosPositionKpiReply>> replyFuture = null;
            if (useHostOverride) {
                replyFuture = me.getQosPositionKpiFuture(request, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                replyFuture = me.getQosPositionKpiFuture(request, GRPC_TIMEOUT_MS);
            }
            // A stream of QosPositionKpiReply(s), with a non-stream block of responses.
            // Wait for value with get().
            ChannelIterator<AppClient.QosPositionKpiReply> responseIterator = replyFuture.get();
            long total = 0;
            while (responseIterator.hasNext()) {
                AppClient.QosPositionKpiReply aR = responseIterator.next();
                for (int i = 0; i < aR.getPositionResultsCount(); i++) {
                    System.out.println(aR.getPositionResults(i));
                }
                total += aR.getPositionResultsCount();
            }
            responseIterator.shutdown();
            assertEquals((long)(kpiRequests.size()), total);
        } catch (DmeDnsException dde) {
            Log.i(TAG, Log.getStackTraceString(dde));
            assertFalse("getQosPositionKpiFutureTest: DmeDnsException!", true);
        } catch (ExecutionException ee) {
            Log.i(TAG, Log.getStackTraceString(ee));
            assertFalse("getQosPositionKpiFutureTest: ExecutionException!", true);
        } catch (StatusRuntimeException sre) {
            Log.i(TAG, sre.getMessage());
            Log.i(TAG, Log.getStackTraceString(sre));
            assertFalse("getQosPositionKpiFutureTest: StatusRuntimeException!", true);
        } catch (InterruptedException ie) {
            Log.i(TAG, Log.getStackTraceString(ie));
            assertFalse("getQosPositionKpiFutureTest: InterruptedException!", true);
        } finally {
            enableMockLocation(context,false);
        }

    }

    /**
     * Tests the MatchingEngine SDK supplied TCP connection to the edge cloudlet.
     *
     * This is a raw stream to a test echo server, so there are no explicit message delimiters.
     */
    @Test
    public void appConnectionTestTcp001() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        MatchingEngine me = new MatchingEngine(context);
        AppConnectionManager appConnect = me.getAppConnectionManager();

        enableMockLocation(context, true);

        Socket s = null;
        BufferedOutputStream bos = null;
        BufferedInputStream bis = null;
        try {
            // Test against Http Echo.
            String carrierName = "GDDT";
            String appName = "HttpEcho";
            String orgName = "MobiledgeX";
            String appVersion = "20191204";

            // Exercise and override the default:
            // The app version will be null, but we can build from scratch for test
            AppClient.RegisterClientRequest regRequest = AppClient.RegisterClientRequest.newBuilder()
                    .setCarrierName(me.getCarrierName(context))
                    .setOrgName(orgName)
                    .setAppName(appName)
                    .setAppVers(appVersion)
                    .build();

            AppClient.RegisterClientReply registerClientReply;
            if (true) {
                registerClientReply = me.registerClient(regRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                registerClientReply = me.registerClient(regRequest, GRPC_TIMEOUT_MS);
            }
            assertTrue("Register did not succeed for HttpEcho appInst", registerClientReply.getStatus() == AppClient.ReplyStatus.RS_SUCCESS);

            MeLocation meLoc = new MeLocation(me);
            assertTrue("Missing Location!", meLoc != null);

            enableMockLocation(context, true);
            Location mockLoc = MockUtils.createLocation("appConnectionTestTcp001", 122.3321, 47.6062);
            Location loc = MockUtils.createLocation("registerClientTest", 122.3321, 47.6062);
            setMockLocation(context, mockLoc);

            Location location = meLoc.getBlocking(context, GRPC_TIMEOUT_MS);

            // Defaults:
            AppClient.FindCloudletRequest findCloudletRequest = me.createDefaultFindCloudletRequest(context, location)
                    .setCarrierName(findCloudletCarrierOverride)
                    .build();

            AppClient.FindCloudletReply findCloudletReply;
            if (true) {
                findCloudletReply = me.findCloudlet(findCloudletRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                findCloudletReply = me.findCloudlet(findCloudletRequest, GRPC_TIMEOUT_MS);
            }

            // Just using first one. This depends entirely on the server design.
            List<AppPort> appPorts = findCloudletReply.getPortsList();
            assertTrue("AppPorts is null", appPorts != null);
            assertTrue("AppPorts is empty!", appPorts.size() > 0);

            HashMap<Integer, AppPort> portMap = appConnect.getTCPMap(findCloudletReply);
            AppPort one = portMap.get(3001); // This internal port depends entirely the AppInst configuration/Docker image.

            assertTrue("EndPort is expected to be 0 for this AppInst", one.getEndPort() == 0 );
            // The actual mapped Public port, or one between getPublicPort() to getEndPort(), inclusive.
            Future<Socket> fs = appConnect.getTcpSocket(findCloudletReply, one, one.getPublicPort(), (int)GRPC_TIMEOUT_MS);

            // Interface bound TCP socket.
            s = fs.get(); // Nothing to do. Await value.
            try {
                bos = new BufferedOutputStream(s.getOutputStream());
                String data = "{\"Data\": \"food\"}";
                String rawpost = "POST / HTTP/1.1\r\n" +
                        "Host: 10.227.66.62:3000\r\n" +
                        "User-Agent: curl/7.54.0\r\n" +
                        "Accept: */*\r\n" +
                        "Content-Length: " + data.length() + "\r\n" +
                        "Content-Type: application/json\r\n" +
                        "\r\n" + data;
                bos.write(rawpost.getBytes());
                bos.flush();

                Object aMon = new Object(); // Some arbitrary object Monitor.
                synchronized (aMon) {
                    aMon.wait(1000);
                }

                bis = new BufferedInputStream(s.getInputStream());
                int available = bis.available();
                assertTrue("No bytes available in response.", available > 0); // Probably true.

                byte[] b = new byte[4096];
                int numRead = bis.read(b);
                assertTrue("Didn't get response!", numRead > 0);

                String output = new String(b);
                // Not an http client, so we're just going to get the substring of something stable:
                boolean found = output.indexOf("food") != -1 ? true : false;;
                assertTrue("Didn't find json data [" + data + "] in response!", found == true);

            } catch (IOException ioe) {
                assertTrue("Failed to get output stream for socket!", false);
            }

        } catch (DmeDnsException dde) {
            Log.e(TAG, Log.getStackTraceString(dde));
            assertFalse("appConnectionTestTcp001: DmeDnsException", true);
        } catch (ExecutionException ee) {
            Log.i(TAG, Log.getStackTraceString(ee));
            assertFalse("appConnectionTestTcp001: ExecutionException!", true);
        } catch (StatusRuntimeException sre) {
            Log.i(TAG, sre.getMessage());
            Log.i(TAG, Log.getStackTraceString(sre));
            assertFalse("appConnectionTestTcp001: StatusRuntimeException!", true);
        } catch (InterruptedException ie) {
            Log.i(TAG, Log.getStackTraceString(ie));
            assertFalse("appConnectionTestTcp001: InterruptedException!", true);
        } finally {
            try {
                if (bis != null) {
                    bis.close();
                }
                if (bos != null) {
                    bos.close();
                }
                if (s != null) {
                    s.close();
                }
            } catch (IOException ioe) {
                assertFalse("IO Exceptions trying to close socket.", true);
            }
            enableMockLocation(context, false);
            me.setNetworkSwitchingEnabled(true);
        }
    }

    /**
     * Tests the MatchingEngine SDK supplied HTTP connection to the edge cloudlet. FIXME: TLS Test with certs.
     */

    @Test
    public void appConnectionTestTcp002() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        MatchingEngine me = new MatchingEngine(context);
        AppConnectionManager appConnect = me.getAppConnectionManager();
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);

        enableMockLocation(context,true);

        OkHttpClient httpClient = null;
        // Test against Http Echo.
        String carrierName = "GDDT";
        String appName = "HttpEcho";
        String orgName = "MobiledgeX";
        String appVersion = "20191204";
        try {
            String data = "{\"Data\": \"food\"}";

            AppClient.RegisterClientRequest req = me.createDefaultRegisterClientRequest(context, orgName)
                    .setCarrierName(carrierName)
                    .setAppName(appName)
                    .setAppVers(appVersion)
                    .build();

            AppClient.RegisterClientReply registerReply;
            // FIXME: Need/want a secondary cloudlet for this AppInst test.
            if (true) {
                registerReply = me.registerClient(req, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                registerReply = me.registerClient(req, GRPC_TIMEOUT_MS);
            }
            assertTrue("Register did not succeed for HttpEcho appInst", registerReply.getStatus() == AppClient.ReplyStatus.RS_SUCCESS);

            MeLocation meLoc = new MeLocation(me);
            assertTrue("Missing Location!", meLoc != null);

            enableMockLocation(context, true);
            Location mockLoc = MockUtils.createLocation("verifyLocationFutureTest", 122.3321, 47.6062);
            setMockLocation(context, mockLoc);

            Location location = meLoc.getBlocking(context, GRPC_TIMEOUT_MS);

            AppClient.FindCloudletRequest findCloudletRequest = me.createDefaultFindCloudletRequest(context, location)
                    .setCarrierName(carrierName)
                    .build();
            assertEquals("Session cookies don't match!", registerReply.getSessionCookie(), findCloudletRequest.getSessionCookie());

            AppClient.FindCloudletReply findCloudletReply;
            if (true) {
                findCloudletReply = me.findCloudlet(findCloudletRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                findCloudletReply = me.findCloudlet(findCloudletRequest, GRPC_TIMEOUT_MS);
            }

            // SSL:
            Future<OkHttpClient> httpClientFuture = null;
            httpClientFuture = appConnect.getHttpClient((int) GRPC_TIMEOUT_MS);

            // FIXME: UI Console exposes HTTP as TCP only, so test here use getTcpMap().
            String url = null;
            assertTrue("No AppPorts!", findCloudletReply.getPortsCount() > 0);
            HashMap<Integer, AppPort> portMap = appConnect.getTCPMap(findCloudletReply);
            // Choose the port that we happen to know the internal port for, 3001.
            AppPort one = portMap.get(3001);

            url = appConnect.createUrl(findCloudletReply, one, one.getPublicPort());
            assertTrue("URL for server seems very incorrect. ", url != null && url.length() > "http://:".length());

            assertFalse("Failed to get an SSL Socket!", httpClientFuture == null);

            // Interface bound TCP socket, has default timeout equal to NetworkManager.
            httpClient = httpClientFuture.get();
            MediaType JSON = MediaType.parse("application/json; charset=utf-8");


            RequestBody body = RequestBody.create(JSON, data);
            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();

            Response response = httpClient.newCall(request).execute();
            String output = response.body().string();
            boolean found = output.indexOf("food") !=-1 ? true : false;;
            assertTrue("Didn't find json data [" + data + "] in response!", found == true);
        } catch (PackageManager.NameNotFoundException nnfe) {

        } catch (IOException ioe) {
            Log.e(TAG, Log.getStackTraceString(ioe));
            assertFalse("appConnectionTestTcp002: IOException", true);
        } catch (DmeDnsException dde) {
            Log.e(TAG, Log.getStackTraceString(dde));
            assertFalse("appConnectionTestTcp002: DmeDnsException", true);
        } catch (ExecutionException ee) {
            Log.i(TAG, Log.getStackTraceString(ee));
            assertFalse("appConnectionTestTcp002: ExecutionException!", true);
        } catch (StatusRuntimeException sre) {
            Log.i(TAG, sre.getMessage());
            Log.i(TAG, Log.getStackTraceString(sre));
            assertFalse("appConnectionTestTcp002: StatusRuntimeException!", true);
        } catch (InterruptedException ie) {
            Log.i(TAG, Log.getStackTraceString(ie));
            assertFalse("appConnectionTestTcp002: InterruptedException!", true);
        } finally {
            enableMockLocation(context,false);
        }
    }

    /**
     * NOTE: HttpEcho may only be installed on wifi.dme domain
     */
    @Test
    public void appConnectionTestTcp_Http_001() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        MatchingEngine me = new MatchingEngine(context);
        AppConnectionManager appConnect = me.getAppConnectionManager();
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);

        enableMockLocation(context,true);

        try {
            String data = "{\"Data\": \"food\"}";
            String carrierName = "GDDT";
            String orgName = "MobiledgeX";
            String appName = "HttpEcho";
            String appVersion = "20191204";

            AppClient.RegisterClientRequest req = me.createDefaultRegisterClientRequest(context, orgName)
                    .setCarrierName(carrierName)
                    .setAppName(appName)
                    .setAppVers(appVersion)
                    .build();
            AppClient.RegisterClientReply registerClientReply;
            // FIXME: Need/want a secondary cloudlet for this AppInst test.
            if (true) {
                registerClientReply = me.registerClient(req, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                registerClientReply = me.registerClient(req, GRPC_TIMEOUT_MS);
            }
            assertTrue("Register did not succeed for HttpEcho appInst", registerClientReply.getStatus() == AppClient.ReplyStatus.RS_SUCCESS);

            MeLocation meLoc = new MeLocation(me);
            assertTrue("Missing Location!", meLoc != null);

            Location mockLoc = MockUtils.createLocation("verifyLocationFutureTest", 122.3321, 47.6062);
            setMockLocation(context, mockLoc);

            Location location = meLoc.getBlocking(context, GRPC_TIMEOUT_MS);

            AppClient.FindCloudletRequest findCloudletRequest = me.createDefaultFindCloudletRequest(context, location)
                    .setCarrierName(carrierName)
                    .build();
            assertEquals("Response SessionCookie should equal MatchingEngine SessionCookie",
                    me.getSessionCookie(), findCloudletRequest.getSessionCookie());
            AppClient.FindCloudletReply findCloudletReply;
            if (true) {
                findCloudletReply = me.findCloudlet(findCloudletRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                findCloudletReply = me.findCloudlet(findCloudletRequest, GRPC_TIMEOUT_MS);
            }

            // SSL:
            Future<OkHttpClient> httpClientFuture = null;
            httpClientFuture = appConnect.getHttpClient(GRPC_TIMEOUT_MS);
            assertTrue("HttpClientFuture is NULL!", httpClientFuture != null);

            // FIXME: UI Console exposes HTTP as TCP only, so the test here uses getTcpList().
            String url = null;
            HashMap<Integer, AppPort> portMap = appConnect.getTCPMap(findCloudletReply);
            // Choose the TCP port, and we happen to know our server is on one port only: 3001.
            AppPort one = portMap.get(3001);
            assertTrue("Did not find server! ", one != null);
            url = appConnect.createUrl(findCloudletReply, one, one.getPublicPort());

            assertTrue("URL for server seems very incorrect. ", url != null && url.length() > "http://:".length());

            // Interface bound TCP socket, has default timeout equal to NetworkManager.
            OkHttpClient httpClient = httpClientFuture.get();
            MediaType JSON = MediaType.parse("application/json; charset=utf-8");


            RequestBody body = RequestBody.create(JSON, data);
            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();

            Response response = httpClient.newCall(request).execute();
            String output = response.body().string();
            boolean found = output.indexOf("food") != -1 ? true : false;
            assertTrue("Didn't find json data [" + data + "] in response!", found == true);


            Request mobiledgeXSiteRequest = new Request.Builder()
                    .url("https://mobiledgex.com")
                    .build();
            Response mexSiteResponse = httpClient.newCall(mobiledgeXSiteRequest).execute();
            int httpStatus = mexSiteResponse.code();
            assertEquals("Did not reach our home site. Status: ", 200, httpStatus);

            // This certificate goes to artifactory.mobiledgex.net, it *should* fail, but "connect" with
            // HTTP Status 200 OK.
            boolean failedVerification = false;
            mobiledgeXSiteRequest = new Request.Builder()
                    .url("https://mobiledgex.net")
                    .build();
            try {
                mexSiteResponse = httpClient.newCall(mobiledgeXSiteRequest).execute();
            } catch (SSLPeerUnverifiedException e) {
                failedVerification = true;
                httpStatus = mexSiteResponse.code();
                assertEquals("Should fail SSL Host verification, but still be 200 OK. Status: ", 200, httpStatus);
            }
            assertTrue("Did not fail hostname SSL verification!", failedVerification);
        } catch (PackageManager.NameNotFoundException nnfe) {
            Log.e(TAG, nnfe.getMessage());
            Log.i(TAG, Log.getStackTraceString(nnfe));
            assertFalse("appConnectionTestTcp001: Package Info is missing!", true);
        } catch (IOException ioe) {
            Log.e(TAG, Log.getStackTraceString(ioe));
            assertFalse("appConnectionTestTcp001: IOException", true);
        } catch (DmeDnsException dde) {
            Log.e(TAG, Log.getStackTraceString(dde));
            assertFalse("appConnectionTestTcp001: DmeDnsException", true);
        } catch (ExecutionException ee) {
            Log.i(TAG, Log.getStackTraceString(ee));
            assertFalse("appConnectionTestTcp001: ExecutionException!", true);
        } catch (StatusRuntimeException sre) {
            Log.e(TAG, sre.getMessage());
            Log.i(TAG, Log.getStackTraceString(sre));
            assertFalse("appConnectionTestTcp001: StatusRuntimeException!", true);
        } catch (InterruptedException ie) {
            Log.i(TAG, Log.getStackTraceString(ie));
            assertFalse("appConnectionTestTcp001: InterruptedException!", true);
        } finally {
            enableMockLocation(context,false);
        }
    }

    @Test
    public void testRegisterAndFindCloudlet_001() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);

        AppConnectionManager appConnectionManager = me.getAppConnectionManager();

        MeLocation meLoc = new MeLocation(me);
        Location location;
        AppClient.RegisterClientReply registerClientReply = null;
        String carrierName = "GDDT";
        String organizationName = "MobiledgeX";
        String appName = "HttpEcho";
        String appVersion = "20191204";

        enableMockLocation(context,true);
        Location loc = MockUtils.createLocation("registerClientTest", 122.3321, 47.6062);

        Socket socket = null;
        try {
            setMockLocation(context, loc);

            location = meLoc.getBlocking(context, GRPC_TIMEOUT_MS);
            assertTrue("Required location is null!", location != null);

            Future<AppClient.FindCloudletReply> findCloudletReplyFuture = me.registerAndFindCloudlet(context, hostOverride, portOverride,
                    organizationName, appName,
                    appVersion, location, "",
                    0, null, null, null); // FIXME: These parameters should be overloaded or optional.
            // Just wait:
            AppClient.FindCloudletReply findCloudletReply = findCloudletReplyFuture.get();
            HashMap<Integer, AppPort> appTcpPortMap = appConnectionManager.getTCPMap(findCloudletReply);
            AppPort appPort = appTcpPortMap.get(3001);
            assertTrue(appPort != null); // There should be at least one for a connection to be made.
            Future<Socket> socketFuture = me.getAppConnectionManager().getTcpSocket(findCloudletReply, appPort, appPort.getPublicPort(), (int)GRPC_TIMEOUT_MS);
            socket = socketFuture.get();

            assertTrue("FindCloudletReply failed!", findCloudletReply != null);
        } catch (ExecutionException ee) {
            Log.i(TAG, Log.getStackTraceString(ee));
            assertFalse("testRegisterAndFindCloudlet_001: ExecutionException!", true);
        } catch (StatusRuntimeException sre) {
            Log.i(TAG, Log.getStackTraceString(sre));
            assertFalse("testRegisterAndFindCloudlet_001: StatusRuntimeException!", true);
        } catch (InterruptedException ie) {
            Log.i(TAG, Log.getStackTraceString(ie));
            assertFalse("testRegisterAndFindCloudlet_001: InterruptedException!", true);
        } finally {
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException ioe) {
                ioe.printStackTrace();
            }
            enableMockLocation(context,false);
        }
    }

    @Test
    public void getAppNameTest() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        MatchingEngine me = new MatchingEngine(context);

        // Under test, there is no app manifest or name.
        assertTrue(me.getAppName(context) == null);
    }

    @Test
    public void getAppVersionNameTest() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        MatchingEngine me = new MatchingEngine(context);
        try {
            assertTrue(me.getAppVersion(context) == null);
        } catch (PackageManager.NameNotFoundException nnfe) {
            assertFalse("Should not be here: " + nnfe.getMessage(), false);
        }
    }

    @Test
    public void NetTestAPItest() {
        // Setup as usual, then grab netTest from MatchingEngine, and add well known test sites. Get the best one, test wise.
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AppClient.FindCloudletReply findCloudletReply = null;
        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);
        MeLocation meLoc = new MeLocation(me);

        Location loc = MockUtils.createLocation("findCloudletTest", 122.3321, 47.6062);

        try {
            enableMockLocation(context, true);
            setMockLocation(context, loc);

            Location location = meLoc.getBlocking(context, GRPC_TIMEOUT_MS);

            registerClient(me);

            AppClient.FindCloudletRequest findCloudletRequest = me.createDefaultFindCloudletRequest(context, location)
                    .setCarrierName(findCloudletCarrierOverride)
                    .build();
            if (useHostOverride) {
                findCloudletReply = me.findCloudlet(findCloudletRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS, MatchingEngine.FindCloudletMode.PERFORMANCE);
            } else {
                findCloudletReply = me.findCloudlet(findCloudletRequest, GRPC_TIMEOUT_MS, MatchingEngine.FindCloudletMode.PERFORMANCE);
            }

            NetTest netTest = me.getNetTest();
            netTest.testRounds = 10;
            Network network = me.getNetworkManager().getActiveNetwork();

            Log.d(TAG, "Executor version testing...");


            // Threaded version unit test of executor:
            ExecutorService executorService = null;
            long threadRun = 0;
            try {
                executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
                netTest.setExecutorService(executorService);
                Stopwatch stopWatch = Stopwatch.createStarted();
                netTest.testSitesOnExecutor(GRPC_TIMEOUT_MS);
                threadRun = stopWatch.stop().elapsed(TimeUnit.MILLISECONDS);
                Log.d(TAG, "Threads, Time to run: " + threadRun);
            } finally {
                netTest.setExecutorService(null);
                if (executorService != null && !executorService.isShutdown()) {
                    executorService.shutdown();
                }
            }
            Site bestSite1 = netTest.bestSite();

            Log.d(TAG, "Simple for loop...");
            // Test numSample times, all sites in round robin style, at PingInterval time.
            Stopwatch stopWatch = Stopwatch.createStarted();
            for (Site s : netTest.sortedSiteList()) {
                for (int n = 0; n < s.samples.length; n++) {
                    netTest.testSite(s);
                }
            }

            long size1 = netTest.sortedSiteList().size();

            long serialRun = stopWatch.stop().elapsed(TimeUnit.MILLISECONDS);
            Log.d(TAG, "Loop, Time to run: " + serialRun);
            // Using default comparator for selecting the current best.
            Site bestSite2 = netTest.bestSite();

            // Some criteria in case a site is pretty close in performance:
            if (bestSite1.host != bestSite2.host) {
                double diff = bestSite1.average - bestSite2.average;
                if (diff/bestSite2.average > 0.15d) { // % arbitrary.
                    assertEquals("The best site, should usually agree in a certain time span if nothing changed.", bestSite1.host, bestSite2.host);
                }
            }
            long size2 = netTest.sortedSiteList().size();

            assertEquals("Test sizes should have been the same!", size1, size2);
            assertTrue("Simple serial run was faster than threaded run!", serialRun > threadRun);

            Site bestSite = bestSite2;
            // Comparator results: Is it really "best"?
            int count = 0;
            for (Site s : netTest.sortedSiteList()) {
                // Fail count.
                if (s.average == 0) {
                    continue; // Not really a live site (private tested size).
                }
                if (bestSite.average > s.average) {
                    count++;
                } if (bestSite.average == s.average && bestSite.stddev > s.stddev) {
                    count++;
                }
            }

            Log.d(TAG, "Fastest site: " + bestSite.host + ":" + bestSite.port);
            assertTrue("There were faster sites on this list. Count: " + count, count == 0);

        } catch (DmeDnsException dde) {
            Log.e(TAG, Log.getStackTraceString(dde));
            assertFalse("NetTestAPItest: DmeDnsException", true);
        }  catch (ExecutionException ee) {
            Log.i(TAG, Log.getStackTraceString(ee));
            assertFalse("NetTestAPItest: ExecutionException!", true);
        } catch (StatusRuntimeException sre) {
            Log.i(TAG, Log.getStackTraceString(sre));
            assertFalse("NetTestAPItest: StatusRuntimeException!", true);
        } catch (InterruptedException ie) {
            Log.i(TAG, Log.getStackTraceString(ie));
            assertFalse("NetTestAPItest: InterruptedException!", true);
        }
    }

    @Test
    public void NoRegisterFindCloudlet() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);

        Location loc = MockUtils.createLocation("findCloudletTest", 122.3321, 47.6062);
        try {
            AppClient.FindCloudletRequest.Builder requestBuilder = me.createDefaultFindCloudletRequest(context, loc);
            assertFalse("We should not be here, expected an user error and illegal engine state.", true);
        } catch (IllegalArgumentException iae) {
            Log.i(TAG, Log.getStackTraceString(iae));
            // Expected to be here. Success.
        }
    }

    @Test
    public void NoRegisterVerifyLocation() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);

        Location loc = MockUtils.createLocation("findCloudletTest", 122.3321, 47.6062);
        try {
            AppClient.VerifyLocationRequest.Builder requestBuilder = me.createDefaultVerifyLocationRequest(context, loc);
            assertFalse("We should not be here, expected an user error and illegal engine state.", true);
        } catch (IllegalArgumentException iae) {
            Log.i(TAG, Log.getStackTraceString(iae));
            // Expected to be here. Success.
        }
    }


    @Test
    public void NoRegisterAppInstList() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);


        Location loc = MockUtils.createLocation("findCloudletTest", 122.3321, 47.6062);
        try {
            AppClient.AppInstListRequest.Builder requestBuilder = me.createDefaultAppInstListRequest(context, loc);
            assertFalse("We should not be here, expected an user error and illegal engine state.", true);
        } catch (IllegalArgumentException iae) {
            Log.i(TAG, Log.getStackTraceString(iae));
            // Expected to be here. Success.
        }
    }

    @Test
    public void NoRegisterOtherAPIs() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);

        try {
            AppClient.QosPosition qos = AppClient.QosPosition.newBuilder().build();
            List<AppClient.QosPosition> list = new ArrayList<>();
            list.add(qos);
            AppClient.BandSelection bandSelection = AppClient.BandSelection.newBuilder().build();
            AppClient.QosPositionRequest.Builder requestBuilder = me.createDefaultQosPositionRequest(list, 0, bandSelection);
            assertFalse("We should not be here, expected an user error and illegal engine state.", true);
        } catch (IllegalArgumentException iae) {
            Log.i(TAG, Log.getStackTraceString(iae));
            // Expected to be here. Success.
        }
    }
}

