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
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;

import com.google.android.gms.location.FusedLocationProviderClient;
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
import java.util.concurrent.Future;

import distributed_match_engine.AppClient;
import distributed_match_engine.Appcommon.AppPort;
import io.grpc.StatusRuntimeException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.location.Location;
import android.util.Log;

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
                    InstrumentationRegistry.getTargetContext().getPackageName(),
                    "android.permission.READ_PHONE_STATE");
            uiAutomation.grantRuntimePermission(
                    InstrumentationRegistry.getTargetContext().getPackageName(),
                    "android.permission.ACCESS_COARSE_LOCATION");
            uiAutomation.grantRuntimePermission(
                    InstrumentationRegistry.getTargetContext().getPackageName(),
                    "android.permission.ACCESS_FINE_LOCATION"
            );
        }
    }
    // Mini test of wifi only:
    @Test
    public void testWiFiOnly() {
        useWifiOnly = true;

        Context context = InstrumentationRegistry.getTargetContext();
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
        Context context = InstrumentationRegistry.getTargetContext();

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
        try {
            Thread.sleep(100); // Give Mock a bit of time to take effect.
        } catch (InterruptedException ie) {
            throw ie;
        }
        fusedLocationClient.flushLocations();
    }

    @Test
    public void mexDisabledTest() {
        Context context = InstrumentationRegistry.getTargetContext();
        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(false);
        me.setAllowSwitchIfNoSubscriberInfo(true);
        MeLocation meLoc = new MeLocation(me);

        Location loc = MockUtils.createLocation("mexDisabledTest", 122.3321, 47.6062);

        try {
            enableMockLocation(context, true);
            setMockLocation(context, loc);
            synchronized (meLoc) {
                meLoc.wait(1000);
            }
            Location location = meLoc.getBlocking(context, GRPC_TIMEOUT_MS);

            AppClient.RegisterClientRequest registerClientRequest = me.createDefaultRegisterClientRequest(context, organizationName).build();
            assertTrue(registerClientRequest == null);

            AppClient.FindCloudletRequest findCloudletRequest;
            findCloudletRequest = me.createDefaultFindCloudletRequest(context, location).build();
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
        Context context = InstrumentationRegistry.getTargetContext();

        AppClient.RegisterClientReply registerReply;
        AppClient.RegisterClientRequest regRequest;

        try {
            // The app version will be null, but we can build from scratch for test
            regRequest = AppClient.RegisterClientRequest.newBuilder()
                    .setCarrierName(me.retrieveNetworkCarrierName(context))
                    .setOrgName(organizationName)
                    .setAppName(applicationName)
                    .setAppVers(appVersion)
                    .setCellId(me.retrieveCellId(context).get(0).second.intValue())
                    .setUniqueIdType("applicationInstallId")
                    .setUniqueId(me.getUniqueId(context))
                    .build();
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
        Context context = InstrumentationRegistry.getTargetContext();
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
            synchronized (meLoc) {
                meLoc.wait(1000);
            }
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
                reply = me.registerClient(request, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                reply = me.registerClient(request, me.generateDmeHostAddress(), me.getPort(), GRPC_TIMEOUT_MS);
            }
            assert (reply != null);
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
        Context context = InstrumentationRegistry.getTargetContext();
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
            synchronized (meLoc) {
                meLoc.wait(1000);
            }
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
        Context context = InstrumentationRegistry.getTargetContext();
        AppClient.FindCloudletReply findCloudletReply = null;
        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);
        MeLocation meLoc = new MeLocation(me);

        Location loc = MockUtils.createLocation("findCloudletTest", 122.3321, 47.6062);

        try {
            enableMockLocation(context, true);
            setMockLocation(context, loc);
            synchronized (meLoc) {
                meLoc.wait(1000);
            }
            Location location = meLoc.getBlocking(context, GRPC_TIMEOUT_MS);

            String carrierName = me.retrieveNetworkCarrierName(context);
            registerClient(me);

            // Set orgName and location, then override the rest for testing:
            AppClient.FindCloudletRequest findCloudletRequest = me.createDefaultFindCloudletRequest(context, location)
                .build();
            if (useHostOverride) {
                findCloudletReply = me.findCloudlet(findCloudletRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                findCloudletReply = me.findCloudlet(findCloudletRequest, GRPC_TIMEOUT_MS);
            }

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

        if (findCloudletReply != null) {
            // Temporary.
            assertEquals("App's expected test cloudlet FQDN doesn't match.", "mobiledgexmobiledgexsdkdemo20.mexdemo-app-cluster.us-los-angeles.gcp.mobiledgex.net", findCloudletReply.getFqdn());
        } else {
            assertFalse("No findCloudlet response!", false);
        }
    }

    @Test
    public void findCloudletTestSetSomeorgNameAppOptionals() {
        Context context = InstrumentationRegistry.getTargetContext();
        AppClient.RegisterClientReply registerClientReply = null;
        AppClient.FindCloudletReply findCloudletReply = null;
        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);
        MeLocation meLoc = new MeLocation(me);

        Location loc = MockUtils.createLocation("findCloudletTestSetSomeorgNameAppOptionals", 122.3321, 47.6062);

        boolean expectedExceptionHit = false;
        try {
            enableMockLocation(context, true);
            setMockLocation(context, loc);
            synchronized (meLoc) {
                meLoc.wait(1000);
            }
            Location location = meLoc.getBlocking(context, GRPC_TIMEOUT_MS);

            String carrierName = me.retrieveNetworkCarrierName(context);
            registerClient(me);

            // Set NO orgName, then override the rest for testing:
            AppClient.FindCloudletRequest findCloudletRequest = me.createDefaultFindCloudletRequest(context, location)
                    .setAppName(applicationName)
                    .setAppVers(appVersion)
                    .build();
            if (useHostOverride) {
                findCloudletReply = me.findCloudlet(findCloudletRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                findCloudletReply = me.findCloudlet(findCloudletRequest, GRPC_TIMEOUT_MS);
            }

            assertTrue(findCloudletReply != null);
            assertTrue(findCloudletReply.getStatus() == AppClient.FindCloudletReply.FindStatus.FIND_NOTFOUND);
        }
        catch (PackageManager.NameNotFoundException nnfe){
            Log.e(TAG, nnfe.getMessage());
            Log.e(TAG, Log.getStackTraceString(nnfe));
            assertFalse("FindCloudlet: NameNotFoundException", true);
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

        assertTrue("FindCloudlet: Expected StatusRunTimeException about 'NO PERMISSION'", expectedExceptionHit);
    }

    @Test
    public void findCloudletTestSetAllOptionalDevAppNameVers() {
        Context context = InstrumentationRegistry.getTargetContext();
        AppClient.RegisterClientReply registerClientReply = null;
        AppClient.FindCloudletReply findCloudletReply = null;
        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);
        MeLocation meLoc = new MeLocation(me);

        Location loc = MockUtils.createLocation("findCloudletTestSetAllOptionalDevAppNameVers", 122.3321, 47.6062);

        boolean expectedExceptionHit = false;
        try {
            enableMockLocation(context, true);
            setMockLocation(context, loc);
            synchronized (meLoc) {
                meLoc.wait(1000);
            }
            Location location = meLoc.getBlocking(context, GRPC_TIMEOUT_MS);

            String carrierName = me.retrieveNetworkCarrierName(context);
            registerClient(me);

            // Set All orgName, appName, AppVers:
            AppClient.FindCloudletRequest findCloudletRequest = me.createDefaultFindCloudletRequest(context, location)
                    .setCarrierName(carrierName)
                    .setOrgName(organizationName)
                    .setAppName(applicationName)
                    .setAppVers(appVersion)
                    .build();
            if (useHostOverride) {
                findCloudletReply = me.findCloudlet(findCloudletRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                findCloudletReply = me.findCloudlet(findCloudletRequest, GRPC_TIMEOUT_MS);
            }

            assertTrue(findCloudletReply != null);
        }
        catch (PackageManager.NameNotFoundException nnfe){
            Log.e(TAG, nnfe.getMessage());
            Log.e(TAG, Log.getStackTraceString(nnfe));
            assertFalse("FindCloudlet: NameNotFoundException", true);
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
        Context context = InstrumentationRegistry.getTargetContext();
        Future<AppClient.FindCloudletReply> response;
        AppClient.FindCloudletReply result = null;
        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);
        MeLocation meLoc = new MeLocation(me);

        Location loc = MockUtils.createLocation("findCloudletTest", 122.3321, 47.6062);

        try {
            enableMockLocation(context, true);
            setMockLocation(context, loc);
            synchronized (meLoc) {
                meLoc.wait(1000);
            }
            Location location = meLoc.getBlocking(context, 10000);

            String carrierName = me.retrieveNetworkCarrierName(context);
            registerClient(me);

            AppClient.FindCloudletRequest findCloudletRequest = me.createFindCloudletRequest(
                    context, carrierName, location, 0, null);
            if (useHostOverride) {
                response = me.findCloudletFuture(findCloudletRequest, hostOverride, portOverride, 10000);
            } else {
                response = me.findCloudletFuture(findCloudletRequest, 10000);
            }
            result = response.get();
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

        // Temporary.
        assertEquals("Fully qualified domain name not expected.", "mobiledgexmobiledgexsdkdemo20.mexdemo-app-cluster.us-los-angeles.gcp.mobiledgex.net", result.getFqdn());

    }

    @Test
    public void verifyLocationTest() {
        Context context = InstrumentationRegistry.getTargetContext();

        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);
        MeLocation meLoc = new MeLocation(me);
        AppClient.VerifyLocationReply verifyLocationReply = null;

        try {
            enableMockLocation(context, true);
            Location mockLoc = MockUtils.createLocation("verifyLocationTest", 122.3321, 47.6062);
            setMockLocation(context, mockLoc);
            synchronized (meLoc) {
                meLoc.wait(1000);
            }
            Location location = meLoc.getBlocking(context, GRPC_TIMEOUT_MS);

            String carrierName = me.retrieveNetworkCarrierName(context);
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
        Context context = InstrumentationRegistry.getTargetContext();

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
            synchronized(mockLoc) {
                mockLoc.wait(1000);
            }
            Location location = meLoc.getBlocking(context, GRPC_TIMEOUT_MS);

            String carrierName = me.retrieveNetworkCarrierName(context);
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
        Context context = InstrumentationRegistry.getTargetContext();
        enableMockLocation(context,true);

        Location mockLoc = MockUtils.createLocation("verifyMockedLocationTest_NorthPole", 90d, 1d);


        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);
        MeLocation meLoc = new MeLocation(me);

        AppClient.VerifyLocationReply verifyLocationReply = null;
        try {
            setMockLocation(context, mockLoc); // North Pole.
            synchronized (meLoc) {
                meLoc.wait(1000);
            }
            Location location = meLoc.getBlocking(context, GRPC_TIMEOUT_MS);
            assertFalse(location == null);

            String carrierName = me.retrieveNetworkCarrierName(context);
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
        Context context = InstrumentationRegistry.getTargetContext();
        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);
        MeLocation meLoc = new MeLocation(me);
        Location location;
        AppClient.GetLocationReply getLocationReply = null;

        enableMockLocation(context,true);
        Location loc = MockUtils.createLocation("getLocationTest", 122.3321, 47.6062);

        String carrierName = me.retrieveNetworkCarrierName(context);
        try {
            setMockLocation(context, loc);
            synchronized (meLoc) {
                meLoc.wait(1000);
            }
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
        Context context = InstrumentationRegistry.getTargetContext();
        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);

        MeLocation meLoc = new MeLocation(me);
        Location location;
        Future<AppClient.GetLocationReply> getLocationReplyFuture;
        AppClient.GetLocationReply getLocationReply = null;

        enableMockLocation(context,true);
        Location loc = MockUtils.createLocation("getLocationTest", 122.3321, 47.6062);

        String carrierName = me.retrieveNetworkCarrierName(context);
        try {
            // Directly create request for testing:
            // Passed in Location (which is a callback interface)
            setMockLocation(context, loc);
            synchronized (meLoc) {
                meLoc.wait(1000);
            }
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
        Context context = InstrumentationRegistry.getContext();

        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);

        AppClient.DynamicLocGroupReply dynamicLocGroupReply = null;

        enableMockLocation(context,true);
        Location location = MockUtils.createLocation("createDynamicLocationGroupAddTest", 122.3321, 47.6062);
        MeLocation meLoc = new MeLocation(me);

        String carrierName = me.retrieveNetworkCarrierName(context);
        try {
            setMockLocation(context, location);
            synchronized (meLoc) {
                meLoc.wait(1000);
            }
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
        Context context = InstrumentationRegistry.getContext();

        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);

        AppClient.DynamicLocGroupReply dynamicLocGroupReply = null;

        enableMockLocation(context,true);
        Location location = MockUtils.createLocation("createDynamicLocationGroupAddTest", 122.3321, 47.6062);
        MeLocation meLoc = new MeLocation(me);

        String carrierName = me.retrieveNetworkCarrierName(context);
        try {
            setMockLocation(context, location);
            synchronized (meLoc) {
                meLoc.wait(1000);
            }
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
        Context context = InstrumentationRegistry.getContext();

        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);

        AppClient.AppInstListReply appInstListReply = null;

        enableMockLocation(context,true);
        Location location = MockUtils.createLocation("getCloudletListTest", 122.3321, 50.1109);
        MeLocation meLoc = new MeLocation(me);

        try {
            setMockLocation(context, location);
            synchronized (meLoc) {
                meLoc.wait(1000);
            }
            location = meLoc.getBlocking(context, GRPC_TIMEOUT_MS);
            assertFalse("Mock'ed Location is missing!", location == null);

            registerClient(me);
            AppClient.AppInstListRequest appInstListRequest;
            AppClient.AppInstListReply list;
            appInstListRequest  = me.createDefaultAppInstListRequest(context, location)
                    .build();
            if (useHostOverride) {
                list = me.getAppInstList(appInstListRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                list = me.getAppInstList(appInstListRequest, GRPC_TIMEOUT_MS);
            }

            assertEquals(0, list.getVer());
            assertEquals(AppClient.AppInstListReply.AIStatus.AI_SUCCESS, list.getStatus());
            assertEquals(4, list.getCloudletsCount()); // NOTE: This is entirely test server dependent.
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
        Context context = InstrumentationRegistry.getContext();

        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);

        enableMockLocation(context,true);
        Location location = MockUtils.createLocation("getAppInstListFutureTest", 122.3321, 47.6062);
        MeLocation meLoc = new MeLocation(me);

        try {
            setMockLocation(context, location);
            synchronized (meLoc) {
                meLoc.wait(1000);
            }
            location = meLoc.getBlocking(context, GRPC_TIMEOUT_MS);
            assertFalse("Mock'ed Location is missing!", location == null);

            registerClient(me);
            AppClient.AppInstListRequest appInstListRequest = me.createDefaultAppInstListRequest(context, location)
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
            assertEquals(4, list.getCloudletsCount()); // NOTE: This is entirely test server dependent.
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
        Context context = InstrumentationRegistry.getContext();

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

            AppClient.QosPositionRequest request = me.createQoSPositionRequest(kpiRequests, 0, null, 0, null);
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
        Context context = InstrumentationRegistry.getContext();

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

            AppClient.QosPositionRequest request = me.createQoSPositionRequest(kpiRequests, 0, null, 0, null);
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
        Context context = InstrumentationRegistry.getContext();

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
                    .setCarrierName(me.retrieveNetworkCarrierName(context))
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
            synchronized (meLoc) {
                meLoc.wait(1000); // Give some time for system to pick up change.
            }
            Location location = meLoc.getBlocking(context, GRPC_TIMEOUT_MS);

            // Defaults:
            AppClient.FindCloudletRequest findCloudletRequest = me.createDefaultFindCloudletRequest(context, location)
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

        } catch (PackageManager.NameNotFoundException nnfe){
            Log.e(TAG, nnfe.getMessage());
            Log.e(TAG, Log.getStackTraceString(nnfe));
            assertFalse("FindCloudlet: NameNotFoundException", true);
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
        Context context = InstrumentationRegistry.getContext();

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
            synchronized (mockLoc) {
                mockLoc.wait(1000);
            }
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
        Context context = InstrumentationRegistry.getContext();

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
            Object aMon = new Object(); // Some arbitrary object Monitor.
            synchronized (aMon) {
                aMon.wait(1000);
            }
            Location location = meLoc.getBlocking(context, GRPC_TIMEOUT_MS);

            AppClient.FindCloudletRequest findCloudletRequest = me.createDefaultFindCloudletRequest(context, location)
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
        Context context = InstrumentationRegistry.getTargetContext();
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
            Object aMon = new Object(); // Some arbitrary object Monitor.
            synchronized (aMon) {
                aMon.wait(1000);
            }
            location = meLoc.getBlocking(context, GRPC_TIMEOUT_MS);
            assertTrue("Required location is null!", location != null);

            Future<AppClient.FindCloudletReply> findCloudletReplyFuture = me.registerAndFindCloudlet(context, hostOverride, portOverride,
                    organizationName, appName,
                    appVersion, carrierName, location, "",
                    0, null, null, null); // FIXME: These parameters should be overloaded or optional.
            // Just wait:
            AppClient.FindCloudletReply findCloudletReply = findCloudletReplyFuture.get();
            HashMap<Integer, AppPort> appTcpPortMap = appConnectionManager.getTCPMap(findCloudletReply);
            AppPort appPort = appTcpPortMap.get(3001);
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
        Context context = InstrumentationRegistry.getContext();

        MatchingEngine me = new MatchingEngine(context);

        // Under test, there is no app manifest or name.
        assertTrue(me.getAppName(context) == null);
    }

    @Test
    public void getAppVersionNameTest() {
        Context context = InstrumentationRegistry.getContext();

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
        Context context = InstrumentationRegistry.getTargetContext();
        AppClient.RegisterClientReply registerClientReply = null;
        AppClient.FindCloudletReply findCloudletReply = null;
        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);
        MeLocation meLoc = new MeLocation(me);

        Location loc = MockUtils.createLocation("findCloudletTest", 122.3321, 47.6062);

        try {
            enableMockLocation(context, true);
            setMockLocation(context, loc);
            synchronized (meLoc) {
                meLoc.wait(1000);
            }
            Location location = meLoc.getBlocking(context, GRPC_TIMEOUT_MS);

            String carrierName = me.retrieveNetworkCarrierName(context);
            registerClient(me);

            // Set orgName and location, then override the rest for testing:
            AppClient.FindCloudletRequest findCloudletRequest = me.createDefaultFindCloudletRequest(context, location)
                    .build();
            if (useHostOverride) {
                findCloudletReply = me.findCloudlet(findCloudletRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
            } else {
                findCloudletReply = me.findCloudlet(findCloudletRequest, GRPC_TIMEOUT_MS);
            }

            // Grab netTest, and use it:
            NetTest netTest = me.getNetTest();
            Network network = me.getNetworkManager().getActiveNetwork();
            netTest.sites.add(new Site(network, NetTest.TestType.CONNECT, 5, "https://mobiledgex.com"));

            // Test numSample times, all sites in round robin style, at PingInterval time.
            for (int n = 0; n < netTest.numSamples; n++) {
                for (Site s : netTest.sites) {
                    netTest.testSite(s);
                }
            }
            // Using default comparator for selecting the current best.
            Site bestSite = netTest.sortSites().get(0);

            // Is it really "best"?
            int count = 0;
            for (Site s : netTest.sites) {
                // Fail count.
                if (bestSite.average > s.average) {
                    count++;
                } if (bestSite.average == s.average && bestSite.stddev > s.stddev) {
                    count++;
                }
            }
            Log.d(TAG, "Fastest site: " + bestSite.host + ":" + bestSite.port);
            assertTrue("There were faster sites on this list. Count: " + count, count == 0);

        } catch (PackageManager.NameNotFoundException nnfe) {
            Log.e(TAG, nnfe.getMessage());
            Log.i(TAG, Log.getStackTraceString(nnfe));
            assertFalse("NetTestAPItest: Package Info is missing!", true);
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

}

