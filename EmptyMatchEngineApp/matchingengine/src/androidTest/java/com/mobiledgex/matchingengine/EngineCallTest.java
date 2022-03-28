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
import com.google.common.eventbus.DeadEvent;
import com.google.common.eventbus.Subscribe;
import com.mobiledgex.matchingengine.edgeeventsconfig.FindCloudletEvent;
import com.mobiledgex.matchingengine.edgeeventsconfig.FindCloudletEventTrigger;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import distributed_match_engine.AppClient;
import distributed_match_engine.Appcommon;
import distributed_match_engine.Appcommon.AppPort;
import io.grpc.StatusRuntimeException;

import static junit.framework.TestCase.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.location.Location;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.UiThread;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import javax.net.SocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

@RunWith(AndroidJUnit4.class)
public class EngineCallTest {
    public static final String TAG = "EngineCallTest";
    public static final long GRPC_TIMEOUT_MS = 21000;

    // There's no clear way to get this programmatically outside the app signing certificate, and may
    // not be required in the future.
    public static final String organizationName = "automation_dev_org"; // May be MobiledgeX-Samples test org as well.
    // Other globals:
    public static final String applicationName = "automation-sdk-porttest";
    public static final String appVersion = "1.0";

    FusedLocationProviderClient fusedLocationClient;

    public static String hostOverride = "us-qa.dme.mobiledgex.net"; // Change this to: your local infra IP, eu-stage.dme, us-stage.dme.mobiledgex.net, etc.
    public static int portOverride = 50051;
    public static String findCloudletCarrierOverride = ""; // Allow "Any" if using "", but this likely breaks test cases.

    public boolean useHostOverride = true;
    public boolean useWifiOnly = true; // This also disables network switching, since the android default is WiFi.

    @Before
    public void LooperEnsure() {
        // SubscriberManager needs a thread. Start one:
        if (Looper.myLooper()==null)
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

    public Location getTestLocation() {
        Location location = new Location("EngineCallTestLocation");
        location.setLongitude(-121.8919);
        location.setLatitude(37.3353);
        return location;
    }

    @Test
    public void mexDisabledTest() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        //! [meconstructorexample]
        MatchingEngine me = new MatchingEngine(context);
        //! [meconstructorexample]
        me.setMatchingEngineLocationAllowed(false);
        me.setAllowSwitchIfNoSubscriberInfo(true);
        MeLocation meLoc = new MeLocation(me);

        Location loc = getTestLocation();

        try {
            enableMockLocation(context, true);
            setMockLocation(context, loc);

            Location location = meLoc.getBlocking(context, GRPC_TIMEOUT_MS);

            //! [createdefregisterexample]
            AppClient.RegisterClientRequest registerClientRequest = me.createDefaultRegisterClientRequest(context, organizationName).build();
            //! [createdefregisterexample]
            assertTrue(registerClientRequest == null);

            // Doc purposes, just create a dummy, and recreate for test.
            //! [createdeffindcloudletexample]
            AppClient.FindCloudletRequest findCloudletRequest = me.createDefaultFindCloudletRequest(context, location)
                .build();
            //! [createdeffindcloudletexample]
            assertTrue(findCloudletRequest == null);
            findCloudletRequest = me.createDefaultFindCloudletRequest(context, location)
                    .setCarrierName(findCloudletCarrierOverride)
                    .build();
            assertTrue(findCloudletRequest == null);

            AppClient.GetLocationRequest locationRequest = me.createDefaultGetLocationRequest(context).build();
            assertTrue(locationRequest == null);

            //! [createdefverifylocationexample]
            AppClient.VerifyLocationRequest verifyLocationRequest = me.createDefaultVerifyLocationRequest(context, location).build();
            //! [createdefverifylocationexample]
            assertTrue(verifyLocationRequest == null);

            //! [createdefappinstexample]
            AppClient.AppInstListRequest appInstListRequest = me.createDefaultAppInstListRequest(context, location).build();
            //! [createdefappinstexample]
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
            me.close();
            enableMockLocation(context,false);
        }
    }

    @Test
    public void constructorTests() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MatchingEngine me = new MatchingEngine(context);
    }

    // Every call needs registration to be called first at some point.
    // Test only!
    public void registerClient(MatchingEngine me) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        AppClient.RegisterClientReply registerReply;
        AppClient.RegisterClientRequest registerClientRequest;

        try {
            // The app version will be null, but we can build from scratch for test
            AppClient.RegisterClientRequest.Builder regRequestBuilder = AppClient.RegisterClientRequest.newBuilder()
                    .setOrgName(organizationName)
                    .setAppName(applicationName)
                    .setAppVers(appVersion);
            registerClientRequest = regRequestBuilder.build();
            if (useHostOverride) {
                //! [registeroverrideexample]
                registerReply = me.registerClient(registerClientRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
                //! [registeroverrideexample]
            } else {
                //! [registerexample]
                registerReply = me.registerClient(registerClientRequest, GRPC_TIMEOUT_MS);
                //! [registerexample]
            }
            assertEquals("Response SessionCookie should equal MatchingEngine SessionCookie",
                    registerReply.getSessionCookie(), me.getSessionCookie());
            assertTrue(registerReply.getStatus() == AppClient.ReplyStatus.RS_SUCCESS);
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
    public void registerClientWithUniqueIdTest() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);

        AppClient.RegisterClientReply reply = null;

        try {
            AppClient.RegisterClientRequest request = me.createRegisterClientRequest(
                    context,
                    organizationName,
                    applicationName,
                    appVersion,
                    null,
                    "someUniqueType",
                    "someUniqueId",
                    null);
            assertEquals(request.getUniqueIdType(), "someUniqueType");
            assertEquals(request.getUniqueId(), "someUniqueId");
            if (useHostOverride) {
                reply = me.registerClient(request, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                reply = me.registerClient(request, me.generateDmeHostAddress(), me.getPort(), GRPC_TIMEOUT_MS);
            }
            assertTrue(reply != null);
            assertTrue(reply.getStatus() == AppClient.ReplyStatus.RS_SUCCESS);
            //assertTrue( !reply.getUniqueId().isEmpty());
            assertTrue( reply.getSessionCookie().length() > 0);
            assertEquals("Sessions must be equal.", reply.getSessionCookie(), me.getSessionCookie());
        } catch (PackageManager.NameNotFoundException nnfe) {
            Log.e(TAG, Log.getStackTraceString(nnfe));
            assertFalse("ExecutionException registering using PackageManager.", true);
        } catch (DmeDnsException dde) {
            Log.e(TAG, Log.getStackTraceString(dde));
            assertFalse("registerClientTest: DmeDnsException!", true);
        } catch (ExecutionException ee) {
            Log.e(TAG, "Reason: " + ee.getLocalizedMessage());
            Log.e(TAG, Log.getStackTraceString(ee));
            assertFalse("registerClientTest: ExecutionException!", true);
        } catch (StatusRuntimeException sre) {
            Log.e(TAG, Log.getStackTraceString(sre));
            assertFalse("registerClientTest: StatusRuntimeException!", true);
        } catch (InterruptedException ie) {
            Log.e(TAG, Log.getStackTraceString(ie));
            assertFalse("registerClientTest: InterruptedException!", true);
        } finally {
            me.close();
            enableMockLocation(context,false);
        }

        // Temporary.
        Log.i(TAG, "registerClientTest reply: " + reply.toString());
        assertEquals(0, reply.getVer());
        assertEquals(AppClient.ReplyStatus.RS_SUCCESS, reply.getStatus());
    }

    @Test
    public void registerClientTest() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);

        AppClient.RegisterClientReply reply = null;

        try {
            AppClient.RegisterClientRequest request = me.createDefaultRegisterClientRequest(context, organizationName)
                    .setAppName(applicationName)
                    .setAppVers(appVersion)
                    .build();
            if (useHostOverride) {
                reply = me.registerClient(request, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                reply = me.registerClient(request, me.generateDmeHostAddress(), me.getPort(), GRPC_TIMEOUT_MS);
            }
            assertTrue(reply != null);
            assertTrue(reply.getStatus() == AppClient.ReplyStatus.RS_SUCCESS);
            //assertTrue( !reply.getUniqueId().isEmpty());
            assertTrue( reply.getSessionCookie().length() > 0);
            assertEquals("Sessions must be equal.", reply.getSessionCookie(), me.getSessionCookie());
        } catch (PackageManager.NameNotFoundException nnfe) {
            Log.e(TAG, Log.getStackTraceString(nnfe));
            assertFalse("ExecutionException registering using PackageManager.", true);
        } catch (DmeDnsException dde) {
            Log.e(TAG, Log.getStackTraceString(dde));
            assertFalse("registerClientTest: DmeDnsException!", true);
        } catch (ExecutionException ee) {
            Log.e(TAG, "Reason: " + ee.getLocalizedMessage());
            Log.e(TAG, Log.getStackTraceString(ee));
            assertFalse("registerClientTest: ExecutionException!", true);
        } catch (StatusRuntimeException sre) {
            Log.e(TAG, Log.getStackTraceString(sre));
            assertFalse("registerClientTest: StatusRuntimeException!", true);
        } catch (InterruptedException ie) {
            Log.e(TAG, Log.getStackTraceString(ie));
            assertFalse("registerClientTest: InterruptedException!", true);
        } finally {
            me.close();
            enableMockLocation(context,false);
        }

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

        Future<AppClient.RegisterClientReply> registerReplyFuture;
        AppClient.RegisterClientReply reply = null;

        try {
            AppClient.RegisterClientRequest request = me.createDefaultRegisterClientRequest(context, organizationName)
                    .setAppName(applicationName)
                    .setAppVers(appVersion)
                    .build();
            if (useHostOverride) {
                registerReplyFuture = me.registerClientFuture(request, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                registerReplyFuture = me.registerClientFuture(request, GRPC_TIMEOUT_MS);
            }
            reply = registerReplyFuture.get();
            assert(reply != null);
            assertEquals("Sessions must be equal.", reply.getSessionCookie(), me.getSessionCookie());
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
            me.close();
            enableMockLocation(context,false);
        }

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

        Location location = getTestLocation();

        try {
            registerClient(me);

            // Set orgName and location, then override the rest for testing:
            AppClient.FindCloudletRequest findCloudletRequest = me.createDefaultFindCloudletRequest(context, location)
                .setCarrierName(findCloudletCarrierOverride)
                .build();
            if (useHostOverride) {
                //! [findcloudletoverrideexample]
                findCloudletReply1 = me.findCloudlet(findCloudletRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
                //! [findcloudletoverrideexample]
            } else {
                //! [findcloudletexample]
                findCloudletReply1 = me.findCloudlet(findCloudletRequest, GRPC_TIMEOUT_MS);
                //! [findcloudletexample]
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
            assertNotNull("FindCloudletReply1 is null!", findCloudletReply1);
            assertNotNull("FindCloudletReply2 is null!", findCloudletReply2);
            assertTrue(findCloudletReply1.getStatus().equals(AppClient.FindCloudletReply.FindStatus.FIND_FOUND));
            assertTrue(findCloudletReply2.getStatus().equals(AppClient.FindCloudletReply.FindStatus.FIND_FOUND));

            // Connect:
            int internalAppInstPortNum = 2016;
            //! [construct_host_and_port]
            if (findCloudletReply1.getStatus() == AppClient.FindCloudletReply.FindStatus.FIND_FOUND) {
                // The edge server host and port can be constructed with the following utility code:
                String host = me.getAppConnectionManager().getHost(findCloudletReply1, internalAppInstPortNum);
                int port = me.getAppConnectionManager().getPublicPort(findCloudletReply1, internalAppInstPortNum);
                Log.i(TAG, "Host: " + host + ", Port: " + port);
            }
            //! [construct_host_and_port]
            assertNotNull(findCloudletReply1.getCloudletLocation());
            assertNotNull(findCloudletReply2.getCloudletLocation());

            NetTest netTest = me.getNetTest();
            if (!findCloudletReply1.getFqdn().equals(findCloudletReply2.getFqdn())) {
                Site site1 = netTest.getSite(findCloudletReply1.getPorts(0).getFqdnPrefix() + findCloudletReply1.getFqdn());
                Site site2 = netTest.getSite(findCloudletReply2.getPorts(0).getFqdnPrefix() + findCloudletReply2.getFqdn());
                double margin = Math.abs(site1.average-site2.average)/site2.average;
                assertTrue("Winner Not within 15% margin: " + margin, margin < .15d);
            }

            // Might also fail, since the network is not under test control:
            assertEquals("App's expected test cloudlet FQDN doesn't match.", "automationhawkinscloudlet.gddt.mobiledgex.net", findCloudletReply1.getFqdn());
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
            me.close();
            enableMockLocation(context,false);
        }
    }

    // This test only tests "" any, and not subject to the global override.
    @Test
    public void findCloudletTestSetCarrierNameAnyOverride() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AppClient.FindCloudletReply findCloudletReply = null;
        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);
        Location location = getTestLocation();

        boolean expectedExceptionHit = false;
        try {
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
            me.close();
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

        try {
            Location location = getTestLocation();

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
            me.close();
            enableMockLocation(context,false);
        }


        assertNotNull("FindCloudletReply1 is null!", findCloudletReply1);
        assertNotNull("FindCloudletReply2 is null!", findCloudletReply2);

        assertEquals("FindCloudletReply1 is not found!", AppClient.FindCloudletReply.FindStatus.FIND_FOUND, findCloudletReply1.getStatus());
        assertEquals("FindCloudletReply2 is not found!", AppClient.FindCloudletReply.FindStatus.FIND_FOUND, findCloudletReply2.getStatus());


        assertNotNull(findCloudletReply1.getCloudletLocation());
        assertNotNull(findCloudletReply2.getCloudletLocation());

        NetTest netTest = me.getNetTest();
        if (!findCloudletReply1.getFqdn().equals(findCloudletReply2.getFqdn())) {
            // This is very, very specific to the server setup (TCP is preferred, at index 1)
            Site site1 = netTest.getSite(findCloudletReply1.getPorts(1).getFqdnPrefix() + findCloudletReply1.getFqdn());
            Site site2 = netTest.getSite(findCloudletReply2.getPorts(1).getFqdnPrefix() + findCloudletReply2.getFqdn());
            double margin = Math.abs(site1.average-site2.average)/site2.average;
            assertTrue("Winner Not within 15% margin: " + margin, margin < .15d);
        }

        // Might also fail, since the network is not under test control:
        assertEquals("App's expected test cloudlet FQDN doesn't match.", "automationhawkinscloudlet.gddt.mobiledgex.net", findCloudletReply1.getFqdn());
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
            Location location = getTestLocation();

            String carrierName = me.getCarrierName(context);
            registerClient(me);

            AppClient.VerifyLocationRequest verifyLocationRequest = me.createDefaultVerifyLocationRequest(context, location)
                    .setCarrierName(carrierName)
                    .build();

            if (useHostOverride) {
                //! [verifylocationoverrideexample]
                verifyLocationReply = me.verifyLocation(verifyLocationRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
                //! [verifylocationoverrideexample]
            } else {
                //! [verifylocationexample]
                verifyLocationReply = me.verifyLocation(verifyLocationRequest, GRPC_TIMEOUT_MS);
                //! [verifylocationexample]
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
        AppClient.VerifyLocationReply verifyLocationReply = null;
        Future<AppClient.VerifyLocationReply> verifyLocationReplyFuture = null;

        try {
            Location location = getTestLocation();

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
            me.close();
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
        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);

        AppClient.VerifyLocationReply verifyLocationReply = null;
        try {
            Location location = getTestLocation();

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
            me.close();
            enableMockLocation(context,false);
        }

        // Temporary.
        assertEquals(0, verifyLocationReply.getVer());
        assertEquals(AppClient.VerifyLocationReply.TowerStatus.TOWER_UNKNOWN, verifyLocationReply.getTowerStatus());
        assertEquals(AppClient.VerifyLocationReply.GPSLocationStatus.LOC_ROAMING_COUNTRY_MATCH, verifyLocationReply.getGpsLocationStatus()); // Based on test data.

    }

    @Test
    public void dynamicLocationGroupAddTest() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);

        AppClient.DynamicLocGroupReply dynamicLocGroupReply = null;
        Location loc = getTestLocation();

        String carrierName = me.getCarrierName(context);
        try {
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
            me.close();
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

        Location loc = getTestLocation();
        String carrierName = me.getCarrierName(context);
        try {
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
            me.close();
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

        Location loc = getTestLocation();

        try {
            registerClient(me);
            AppClient.AppInstListRequest appInstListRequest;
            AppClient.AppInstListReply list;
            appInstListRequest  = me.createDefaultAppInstListRequest(context, loc)
                    .setCarrierName(findCloudletCarrierOverride)
                    .build();
            if (useHostOverride) {
                //! [appinstlistoverrideexample]
                list = me.getAppInstList(appInstListRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
                //! [appinstlistoverrideexample]
            } else {
                //! [appinstlistexample]
                list = me.getAppInstList(appInstListRequest, GRPC_TIMEOUT_MS);
                //! [appinstlistexample]
            }

            assertEquals(0, list.getVer());
            assertEquals(AppClient.AppInstListReply.AIStatus.AI_SUCCESS, list.getStatus());
            assertEquals(2, list.getCloudletsCount()); // NOTE: This is entirely test server dependent.
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
            me.close();
            enableMockLocation(context,false);
        }
    }

    @Test
    public void getAppInstListFutureTest() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);

        Location location = getTestLocation();

        try {
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
            assertEquals(2, list.getCloudletsCount()); // NOTE: This is entirely test server dependent.
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
            me.close();
            enableMockLocation(context,false);
        }
    }

    @Test
    public void getQosPositionKpiTest() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);

        Location location = getTestLocation();

        ChannelIterator<AppClient.QosPositionKpiReply> responseIterator = null;
        try {
            registerClient(me);

            double totalDistanceKm = 20;
            double increment = 0.1;
            double direction = 45d;

            ArrayList<AppClient.QosPosition> kpiRequests = MockUtils.createQosPositionArray(location, direction, totalDistanceKm, increment);

            //! [createdefqosexample]
            AppClient.QosPositionRequest request = me.createDefaultQosPositionRequest(kpiRequests, 0, null).build();
            //! [createdefqosexample]
            assertFalse("SessionCookie must not be empty.", request.getSessionCookie().isEmpty());

            if (useHostOverride) {
                //! [qospositionoverrideexample]
                responseIterator = me.getQosPositionKpi(request, hostOverride, portOverride, GRPC_TIMEOUT_MS);
                //! [qospositionoverrideexample]
            } else {
                //! [qospositionexample]
                responseIterator = me.getQosPositionKpi(request, GRPC_TIMEOUT_MS);
                //! [qospositionexample]
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
            me.close();
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

        Location location = getTestLocation();
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
            me.close();
            enableMockLocation(context,false);
        }

    }

    private void closeSocket(Socket s) {
        if (s != null && !s.isClosed()) {
            try {
                s.close();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                s = null;
            }
        }
    }

    @Test
    public void FindCloudletPortMappingsTest() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        MatchingEngine me = new MatchingEngine(context);
        AppConnectionManager appConnect = me.getAppConnectionManager();
        me.setMatchingEngineLocationAllowed(true);

        Future<AppClient.FindCloudletReply> freply = me.registerAndFindCloudlet(context, hostOverride, portOverride,
                organizationName, applicationName, appVersion, getTestLocation(),null, null, null,null, MatchingEngine.FindCloudletMode.PROXIMITY);

        // Construct a FindCloudlet Reply for testing.
        AppPort port = AppPort.newBuilder()
                .setPublicPort(3000)
                .setInternalPort(8008)
                .setEndPort(8010)
                .setProto(Appcommon.LProto.L_PROTO_TCP)
                .build();

        AppPort uport = AppPort.newBuilder()
                .setPublicPort(3000)
                .setInternalPort(8008)
                .setEndPort(8010)
                .setProto(Appcommon.LProto.L_PROTO_UDP)
                .build();

        AppPort portRealSSL = AppPort.newBuilder()
                .setPublicPort(2015)
                .setInternalPort(2015)
                .setEndPort(0)
                .setProto(Appcommon.LProto.L_PROTO_TCP)
                .build();

        AppPort portRealNotSSL = AppPort.newBuilder()
                .setPublicPort(8085)
                .setInternalPort(8085)
                .setEndPort(0)
                .setProto(Appcommon.LProto.L_PROTO_TCP)
                .build();

        // A dummy FindCloudletReply:
        AppClient.FindCloudletReply reply = null;
        try {
            reply = freply.get();
        } catch (ExecutionException e) {
            e.printStackTrace();
            assertFalse("Unexpected Exception hit: " + e.getMessage(), true);
        } catch (InterruptedException e) {
            e.printStackTrace();
            assertFalse("Unexpected Exception hit: " + e.getMessage(), true);
        }

        //! [gettcpsocketexample]
        Future<Socket> sf = null;
        Socket s = null;
        try {
            sf = me.getAppConnectionManager().getTcpSocket(reply, port, 4000, 10000);
            s = sf.get();
        } catch (ExecutionException ee) {
            assertTrue("Expected invalid port", ee.getCause() instanceof InvalidPortException);
        } catch (Exception e) {
            assertFalse("Unexpected Exception hit: " + e.getMessage(), true);
        } finally {
            closeSocket(s);
        }
        //! [gettcpsocketexample]


        try {
            sf = me.getAppConnectionManager().getTcpSocket(reply, port, 3000, 10000);
            sf.get();
        } catch (ExecutionException ee) {
            // Expected invalid port.
            assertTrue("Expected invalid port", ee.getCause() instanceof InvalidPortException);
        } catch (Exception e) {
            assertFalse("Unexpected Exception hit: " + e.getMessage(), true);
        } finally {
            closeSocket(s);
        }

        try {
            // Real reply ports, bad appPort chosen:
            sf = me.getAppConnectionManager().getTcpSocket(reply, port, 4000, 10000);
            sf.get();
            assertTrue("Not connected!", s.isConnected() == true);
        } catch (ExecutionException ee) {
            // Expected invalid port.
            assertTrue("Expected invalid port", ee.getCause() instanceof InvalidPortException);
        } catch (Exception e) {
            assertFalse("Unexpected Exception hit: " + e.getMessage(), true);
        } finally {
            closeSocket(s);
        }

        try {
            // Ranged port is valid, but it doesn't actually exist!
            sf = me.getAppConnectionManager().getTcpSocket(reply, port, 8009, 10000);
            s = sf.get(); // Wrong port, and we know it doesn't exist.
            assertTrue("Not connected!", s.isConnected() == false);
        } catch (ExecutionException ee) {
            assertTrue("Expected Connection Exception!", ee.getCause() instanceof ConnectException);
        } catch (InterruptedException ie) {
            assertFalse("Not expected InterruptedException: " + ie.getMessage(), true);
        } finally {
            closeSocket(s);
        }

        // The autoconnect will fail, as the timeout won't happen on read(), which is never.
        // Wait for timeout.
        try {
            sf = me.getAppConnectionManager().getTcpSocket(reply, port, 8011, 10000);
            s = sf.get();
            assertTrue("Not connected!", s.isConnected() == false);
            s.close();
        } catch (Exception e) {
            // 8011 isn't valid.
            assertTrue("Expected invalid port", e.getCause() instanceof InvalidPortException);
        } finally {
            closeSocket(s);
        }

        try {
            // Need a real port in order to connect test:
            int resolvedPort = me.getAppConnectionManager().getPort(portRealSSL, 0);
            assertEquals("Ports should match!", resolvedPort, portRealSSL.getPublicPort());
            int publicPort = me.getAppConnectionManager().getPort(portRealSSL, 2015);
            assertEquals("Ports not the same!", publicPort, portRealSSL.getPublicPort()); // Identity.

            // This one needs a real server:
            String url = me.getAppConnectionManager().createUrl(reply, portRealNotSSL, 8085, "http", "/automation.html");
            Future<OkHttpClient> httpClientFuture = me.getAppConnectionManager().getHttpClient(5000);
            OkHttpClient httpClient = httpClientFuture.get();
            Request request = new Request.Builder()
                    .url(url)
                    .build();

            Response response = httpClient.newCall(request).execute();
            assertTrue("Failed connect!", response.isSuccessful());
        } catch (Exception e) {
            assertFalse("Exception hit: " + e.getMessage(), false);
        }

        // This is...actually quite invalid, but just exercising code.
        //! [getudpsocketexample]
        try {
            Future<DatagramSocket> dsf = me.getAppConnectionManager().getUdpSocket(reply, uport, 8008, 10000);
            DatagramSocket ds = dsf.get(); // Wrong port, and we know it doesn't exist.
            assertTrue("Not bound!", ds.isBound() == true);
        } catch (ExecutionException ee) {
            // 8009 isn't valid.
            assertTrue("Expected Connection Exception!", ee.getCause() instanceof ConnectException);
        } catch (InterruptedException ie) {
            assertFalse("Not expected InterruptedException: " + ie.getMessage(), true);
        }
        //! [getudpsocketexample]

        // Slowest test one last in this test stream (apparently takes a while to fail to non-exported port/blackhole).
        try {
            sf = me.getAppConnectionManager().getTcpSocket(reply, port, 8009, 10000);
            s = sf.get(); // Wrong port, and we know it doesn't exist.
            assertTrue("Not connected!", s.isConnected() == false);
        } catch (ExecutionException ee) {
            // 8009 isn't valid.
            assertTrue("Expected Connection Exception!", ee.getCause() instanceof ConnectException);
        } catch (InterruptedException ie) {
            assertFalse("Not expected InterruptedException: " + ie.getMessage(), true);
        } finally {
            closeSocket(s);
        }

    }

    /**
     * Tests the MatchingEngine SDK supplied TCP connection to the edge cloudlet.
     *
     * This is a raw stream to a test ping/pong server, so there are no explicit message delimiters.
     */
    @Test
    public void appConnectionTestTcp001() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        MatchingEngine me = new MatchingEngine(context);
        AppConnectionManager appConnect = me.getAppConnectionManager();
        me.setMatchingEngineLocationAllowed(true);
        Socket s = null;
        BufferedOutputStream bos = null;
        BufferedInputStream bis = null;
        try {
            registerClient(me);

            Location location = getTestLocation();
            // Defaults:
            AppClient.FindCloudletRequest findCloudletRequest = me.createDefaultFindCloudletRequest(context, location)
                    .setCarrierName(findCloudletCarrierOverride)
                    .build();

            AppClient.FindCloudletReply findCloudletReply;
            if (useHostOverride) {
                findCloudletReply = me.findCloudlet(findCloudletRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                findCloudletReply = me.findCloudlet(findCloudletRequest, GRPC_TIMEOUT_MS);
            }

            assertTrue(findCloudletReply.getStatus() == AppClient.FindCloudletReply.FindStatus.FIND_FOUND);
            // Just using first one. This depends entirely on the server design.

            List<AppPort> appPorts = findCloudletReply.getPortsList();
            assertTrue("AppPorts is null", appPorts != null);
            assertTrue("AppPorts is empty!", appPorts.size() > 0);

            HashMap<Integer, AppPort> portMap = appConnect.getTCPMap(findCloudletReply);
            // Unencrypted port:
            AppPort one = portMap.get(2016); // This internal port depends entirely the AppInst configuration/Docker image.
            assertTrue("EndPort is expected to be 0 for this AppInst", one.getEndPort() == 0 );
            // The actual mapped Public port, or one between getPublicPort() to getEndPort(), inclusive.
            Future<Socket> fs = appConnect.getTcpSocket(findCloudletReply, one, one.getPublicPort(), (int)GRPC_TIMEOUT_MS);
            s = fs.get(); // Nothing to do. Await value.

            assertTrue("Not connected!", s.isConnected());

            // Interface bound TCP socket.

            try {
                bos = new BufferedOutputStream(s.getOutputStream());
                String rawpost = "ping";
                bos.write(rawpost.getBytes());
                bos.flush();

                Object aMon = new Object(); // Some arbitrary object Monitor.
                synchronized (aMon) {
                    aMon.wait(200);
                }
                bis = new BufferedInputStream(s.getInputStream());
                assertTrue("The InputStream not alive?", s.isInputShutdown() == false);
                int available = bis.available();
                assertTrue("No bytes available in response.", available > 0); // Probably true.

                byte[] b = new byte[available];
                int numRead = bis.read(b);
                assertTrue("Didn't get response!", numRead > 0);

                String output = new String(b, StandardCharsets.UTF_8);
                // Not an http client, so we're just going to get the substring of something stable:
                boolean found = output.indexOf("pong") != -1 ? true : false;

                assertEquals("Didn't find response data [" + rawpost + "] in response!", "pong", output);

            } catch (IOException ioe) {
                Log.e(TAG, Log.getStackTraceString(ioe));
                assertTrue("Failed to get stream for socket!", false);
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
        }
    }

    /**
     * Tests the MatchingEngine SDK supplied TCP SSL connection to the edge cloudlet.
     *
     * This is a raw TLS stream to a test ping/pong server, so there are no explicit message delimiters.
     */
    @Test
    public void appConnectionTestTcp002() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        MatchingEngine me = new MatchingEngine(context);
        AppConnectionManager appConnect = me.getAppConnectionManager();
        me.setMatchingEngineLocationAllowed(true);
        Socket s = null;
        try {
            // NOTE: A self signed test server and DME will not work!
            registerClient(me);

            Location location = getTestLocation();
            // Defaults:
            AppClient.FindCloudletRequest findCloudletRequest = me.createDefaultFindCloudletRequest(context, location)
                    .setCarrierName(findCloudletCarrierOverride)
                    .build();

            AppClient.FindCloudletReply findCloudletReply;
            if (useHostOverride) {
                findCloudletReply = me.findCloudlet(findCloudletRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                findCloudletReply = me.findCloudlet(findCloudletRequest, GRPC_TIMEOUT_MS);
            }

            assertTrue(findCloudletReply != null);
            assertTrue(findCloudletReply.getStatus() == AppClient.FindCloudletReply.FindStatus.FIND_FOUND);
            // Just using first one. This depends entirely on the server design.

            List<AppPort> appPorts = findCloudletReply.getPortsList();
            assertTrue("AppPorts is null", appPorts != null);
            assertTrue("AppPorts is empty!", appPorts.size() > 0);

            HashMap<Integer, AppPort> portMap = appConnect.getTCPMap(findCloudletReply);
            // Encrypted port:
            AppPort one = portMap.get(2015); // This internal port depends entirely the AppInst configuration/Docker image.
            assertTrue("EndPort is expected to be 0 for this AppInst", one.getEndPort() == 0 );
            // The actual mapped Public port, or one between getPublicPort() to getEndPort(), inclusive.
            Future<SSLSocket> fs = appConnect.getTcpSslSocket(findCloudletReply, one, one.getPublicPort(), (int)GRPC_TIMEOUT_MS);
            s = fs.get(); // Nothing to do. Await value.

            String host = appConnect.getHost(findCloudletReply, one);
            System.out.print(host);

            assertTrue("Not connected!", s.isConnected());

            // Interface bound TCP socket.

            try {
                OutputStream sos = s.getOutputStream();
                String rawpost = "ping";
                sos.write(rawpost.getBytes(StandardCharsets.UTF_8));
                sos.flush();

                assertTrue("The InputStream not alive?", s.isInputShutdown() == false);
                InputStream sis = s.getInputStream();

                byte[] b = new byte[100];
                int numRead = sis.read(b);
                assertTrue("Didn't get response!", numRead > 0);

                String output = new String(b, 0, numRead, StandardCharsets.US_ASCII);
                // Not an http client, so we're just going to get the substring of something stable:
                boolean found = output.indexOf("pong") != -1 ? true : false;

                assertEquals("Didn't find response data [" + rawpost + "] in response! Response Found: " + found, "pong", output);

            } catch (IOException ioe) {
                Log.e(TAG, Log.getStackTraceString(ioe));
                assertTrue("Failed to get stream for socket!", false);
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
                if (s != null && !s.isClosed()) {
                    s.close();
                }
            } catch (IOException ioe) {
                assertFalse("IO Exceptions trying to close socket.", true);
            }
            enableMockLocation(context, false);
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

        try {
            registerClient(me);
            String responseBodyTest =
                    "<html>" +
                            "\n   <head>" +
                            "\n      <title>Automation test server</title>" +
                            "\n   </head>" +
                            "\n   <body>" +
                            "\n      <p>test server is running</p>" +
                            "\n   </body>" +
                            "\n</html>\n";

            Location location = getTestLocation();

            AppClient.FindCloudletRequest findCloudletRequest = me.createDefaultFindCloudletRequest(context, location)
                    .setCarrierName(findCloudletCarrierOverride)
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
            //! [gethttpclientexample]
            Future<OkHttpClient> httpClientFuture = null;
            httpClientFuture = appConnect.getHttpClient((int) GRPC_TIMEOUT_MS);
            //! [gethttpclientexample]
            assertTrue("HttpClientFuture is NULL!", httpClientFuture != null);

            // FIXME: UI Console exposes HTTP as TCP only, so the test here uses getTcpList().
            String url = null;

            assertTrue(findCloudletReply.getStatus() == AppClient.FindCloudletReply.FindStatus.FIND_FOUND);

            HashMap<Integer, AppPort> portMap = appConnect.getTCPMap(findCloudletReply);
            // Choose the TCP port, and we happen to know our server is on one port only: 3001.
            AppPort one = portMap.get(8085);
            assertTrue("Did not find server! ", one != null);
            //! [createurlexample]
            String protocol = one.getTls() ? "https" : "http";
            url = appConnect.createUrl(findCloudletReply, one, one.getPublicPort(), protocol, null);
            //! [createurlexample]
            url += "/automation.html";

            // Interface bound TCP socket, has default timeout equal to NetworkManager.
            OkHttpClient httpClient = httpClientFuture.get();
            MediaType JSON = MediaType.parse("application/json; charset=utf-8");

            Request request = new Request.Builder()
                    .url(url)
                    .build();

            Response response = httpClient.newCall(request).execute();
            String output = response.body().string();
            boolean found = output.indexOf(responseBodyTest) != -1 ? true : false;
            assertTrue("Didn't find json data [" + responseBodyTest + "] in response!", found == true);


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
            me.close();
            enableMockLocation(context,false);
        }
    }

    @Test
    public void testRegisterAndFindCloudlet_001() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MatchingEngine me = new MatchingEngine(context);
        me.setEnableEdgeEvents(false);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);
        // Do not enable WiFi only, by default, it also disables SSL and network switching: It's test only, but breaks this test!

        AppConnectionManager appConnectionManager = me.getAppConnectionManager();

        Location location = getTestLocation();
        Socket socket = null;
        try {
            //! [registerandfindoverrideexample]
            Future<AppClient.FindCloudletReply> findCloudletReplyFuture = me.registerAndFindCloudlet(context, hostOverride, portOverride,
                    organizationName, applicationName,
                    appVersion, location, "",
                    null, null, null, MatchingEngine.FindCloudletMode.PROXIMITY); // FIXME: These parameters should be overloaded or optional.
            //! [registerandfindoverrideexample]
            // Just wait:
            AppClient.FindCloudletReply findCloudletReply = findCloudletReplyFuture.get();
            assertTrue("Could not find an appInst: " + findCloudletReply.getStatus(), findCloudletReply.getStatus() == AppClient.FindCloudletReply.FindStatus.FIND_FOUND);
            HashMap<Integer, AppPort> appTcpPortMap = appConnectionManager.getTCPMap(findCloudletReply);
            AppPort appPort = appTcpPortMap.get(findCloudletReply.getPorts(0).getInternalPort());

            assertTrue(appPort != null); // There should be at least one for a connection to be made.
            // Allow some flexibility test AppInst:
            assertTrue("We should have TLS transport the AppInst for this test.", appPort.getTls());
            Future<SSLSocket> socketFuture = me.getAppConnectionManager().getTcpSslSocket(findCloudletReply, appPort, appPort.getPublicPort(), (int) GRPC_TIMEOUT_MS);
            socket = socketFuture.get();
            assertTrue("Socket should have been created!", socket != null);
            assertTrue("SSL Socket must be connected!", socket.isConnected());

            assertTrue("FindCloudletReply failed!", findCloudletReply != null);
        } catch (ExecutionException ee) {
            Log.i(TAG, Log.getStackTraceString(ee));
            assertFalse("testRegisterAndFindCloudlet_001: ExecutionException!" + ee.getCause(), true);
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
            me.close();
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
        Location location = getTestLocation();

        try {
            registerClient(me);

            AppClient.FindCloudletRequest findCloudletRequest = me.createDefaultFindCloudletRequest(context, location)
                    .setCarrierName(findCloudletCarrierOverride)
                    .build();
            if (useHostOverride) {
                findCloudletReply = me.findCloudlet(findCloudletRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS, MatchingEngine.FindCloudletMode.PERFORMANCE);
            } else {
                findCloudletReply = me.findCloudlet(findCloudletRequest, GRPC_TIMEOUT_MS, MatchingEngine.FindCloudletMode.PERFORMANCE);
            }

            NetTest netTest = me.getNetTest(); // Engine test only. Do not use.
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

