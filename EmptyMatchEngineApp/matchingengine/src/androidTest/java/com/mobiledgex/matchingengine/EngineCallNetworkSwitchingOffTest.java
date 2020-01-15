/**
 * Copyright 2019 MobiledgeX, Inc. All rights and licenses reserved.
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
import android.location.Location;
import android.os.Build;
import android.os.Looper;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.util.Log;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.mobiledgex.matchingengine.util.MeLocation;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import javax.net.ssl.SSLPeerUnverifiedException;

import distributed_match_engine.AppClient;
import io.grpc.StatusRuntimeException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class EngineCallNetworkSwitchingOffTest {
    public static final String TAG = "EngineCallTest";
    public static final long GRPC_TIMEOUT_MS = 15000;

    // There's no clear way to get this programmatically outside the app signing certificate, and may
    // not be required in the future.
    public static final String developerName = "MobiledgeX";
    public static final String applicationName = "MobiledgeX SDK Demo";

    FusedLocationProviderClient fusedLocationClient;

    public static String hostOverride = "sdkdemo.dme.mobiledgex.net";
    public static int portOverride = 50051;

    public boolean useHostOverride = true;

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


    // Every call needs registration to be called first at some point.
    public void registerClient(MatchingEngine me) {
        AppClient.RegisterClientReply registerReply;
        AppClient.RegisterClientRequest regRequest;
        regRequest = MockUtils.createMockRegisterClientRequest(developerName, applicationName, me);
        try {
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

    /**
     * This test disabled networking. This test will only ever pass if the DME server accepts
     * non-cellular communications.
     */
    @Test
    public void meNetworkingDisabledTest() {
        Context context = InstrumentationRegistry.getTargetContext();
        MatchingEngine me = new MatchingEngine(context);
        me.setNetworkSwitchingEnabled(false);
        me.setMatchingEngineLocationAllowed(true);
        me.setAllowSwitchIfNoSubscriberInfo(true);

        AppClient.RegisterClientReply registerClientReply = null;
        try {
            AppClient.RegisterClientRequest registerClientRequest = MockUtils.createMockRegisterClientRequest(
                    developerName,
                    applicationName,
                    me);

            registerClientReply = me.registerClient(registerClientRequest, GRPC_TIMEOUT_MS);
            if (registerClientReply.getStatus() != AppClient.ReplyStatus.RS_SUCCESS) {
                assertFalse("mexNetworkDisabledTest: registerClient somehow succeeded!", true);
            }
        } catch (DmeDnsException dde) {
            Log.e(TAG, Log.getStackTraceString(dde));
            assertFalse("meNetworkingDisabledTest: DmeDnsException", true);
        } catch (ExecutionException ee) {
            Log.e(TAG, Log.getStackTraceString(ee));
            assertFalse("meNetworkingDisabledTest: ExecutionException!", true);
        } catch (StatusRuntimeException sre) {
            Log.e(TAG, Log.getStackTraceString(sre));
            assertTrue("mexNetworkDisabledTest: registerClient non-null, and somehow succeeded!",registerClientReply == null);
        } catch (InterruptedException ie) {
            Log.e(TAG, Log.getStackTraceString(ie));
            assertFalse("meNetworkingDisabledTest: InterruptedException!", true);
        } finally {
            enableMockLocation(context,false);
            me.setNetworkSwitchingEnabled(true);
        }
    }

}