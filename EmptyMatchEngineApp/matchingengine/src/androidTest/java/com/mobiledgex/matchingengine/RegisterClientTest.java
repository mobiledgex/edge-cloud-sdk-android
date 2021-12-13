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
import android.location.Location;
import android.os.Build;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.auth0.android.jwt.Claim;
import com.auth0.android.jwt.DecodeException;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.gson.JsonObject;
import com.google.protobuf.ByteString;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.auth0.android.jwt.JWT;
import com.mobiledgex.mel.MelMessaging;

import distributed_match_engine.AppClient;
import io.grpc.StatusRuntimeException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class RegisterClientTest {
  public static final String TAG = "EngineCallTest";
  public static final long GRPC_TIMEOUT_MS = 21000;

  public static final String organizationName = "MobiledgeX";
  public static final String organizationNamePlatos = "Platos";
  // Other globals:
  public static final String applicationName = "automation_api_app";
  public static final String applicationNameAuth = "automation_api_auth_app";
  public static final String applicationNamePlatos = "PlatosEnablingLayer";

  public static final String appVersion = "1.0";

  FusedLocationProviderClient fusedLocationClient;

  public static String hostOverride = "us-qa.dme.mobiledgex.net";
  public static String hostOverridePlatos = "eu-qa.dme.mobiledgex.net";

  public static int portOverride = 50051;
  public static String findCloudletCarrierOverride = "GDDT"; // Allow "Any" if using "", but this likely breaks test cases.

  public boolean useHostOverride = true;

  // "useWifiOnly = true" also disables network switching, since the android default is WiFi.
  // Must be set to true if you are running tests without a SIM card.
  public boolean useWifiOnly = true;

  String meluuid = MelMessaging.getUid();
  String uuidType = "Platos:PlatosEnablingLayer";

  private Location getTestLocation(double latitude, double longitude) {
    Location location = new Location("MobiledgeX_Test");
    location.setLatitude(latitude);
    location.setLongitude(longitude);
    return location;
  }

  public static String getSystemProperty(String property, String defaultValue) {
    try {
      Class sysPropCls = Class.forName("android.os.SystemProperties");
      Method getMethod = sysPropCls.getDeclaredMethod("get", String.class);
      String value = (String)getMethod.invoke(null, property);
      if (!TextUtils.isEmpty(value)) {
        return value;
      }
    } catch (Exception e) {
      Log.e(TAG, "Unable to read system properties.");
      e.printStackTrace();
    }
    return defaultValue;
  }

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

  @Test
  public void mexDisabledTest() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    MatchingEngine me = new MatchingEngine(context);
    //me.setUseWifiOnly(useWifiOnly);
    me.setMatchingEngineLocationAllowed(false);
    //me.setAllowSwitchIfNoSubscriberInfo(true);

    try {
      Location location = getTestLocation( 47.6062,122.3321);

      AppClient.RegisterClientRequest.Builder registerClientRequest = me.createDefaultRegisterClientRequest(context, organizationName); //.build();
      assertTrue(registerClientRequest == null);

    } catch (PackageManager.NameNotFoundException nnfe) {
      Log.e(TAG, Log.getStackTraceString(nnfe));
      assertFalse("mexDisabledTest: NameNotFoundException", true);
    } catch (StatusRuntimeException sre) {
      Log.e(TAG, Log.getStackTraceString(sre));
      assertFalse("mexDisabledTest: StatusRuntimeException!", true);
    }  catch (Exception e) {
      Log.e(TAG, "Creation of request is not supposed to succeed!");
      Log.e(TAG, Log.getStackTraceString(e));
    }
  }

  @Test
  public void registerClientTest() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    MatchingEngine me = new MatchingEngine(context);
    //me.setUseWifiOnly(useWifiOnly);
    me.setMatchingEngineLocationAllowed(true);
    //me.setAllowSwitchIfNoSubscriberInfo(true);

    AppClient.RegisterClientReply reply = null;

    try {
      Location location = getTestLocation( 47.6062,122.3321);

      AppClient.RegisterClientRequest request = me.createDefaultRegisterClientRequest(context, organizationName)
        .setAppName(applicationName)
        .setAppVers(appVersion)
        //.setCellId(getCellId(context, me))
        .build();
      if (useHostOverride) {
        reply = me.registerClient(request, hostOverride, portOverride, GRPC_TIMEOUT_MS);
      } else {
        String xx = me.generateDmeHostAddress();
        int p = me.getPort();
        reply = me.registerClient(request, me.generateDmeHostAddress(), me.getPort(), GRPC_TIMEOUT_MS);
      }

      JWT jwt = null;
      try {
        jwt = new JWT(reply.getSessionCookie());
      } catch (DecodeException e) {
        Log.e(TAG, Log.getStackTraceString(e));
        assertFalse("registerClientTest: DecodeException!", true);
      }

      // verify expire timer
      long difftime = (jwt.getExpiresAt().getTime() - jwt.getIssuedAt().getTime());
      assertEquals("Token expires failed:",24, TimeUnit.HOURS.convert(difftime, TimeUnit.MILLISECONDS));
      boolean isExpired = jwt.isExpired(10); // 10 seconds leeway
      assertTrue(!isExpired);

      // verify claim
      Claim c = jwt.getClaim("key");
      JsonObject claimJson = c.asObject(JsonObject.class);
      assertEquals("orgname doesn't match!", organizationName, claimJson.get("orgname").getAsString());
      assertEquals("appname doesn't match!", applicationName, claimJson.get("appname").getAsString());
      assertEquals("appvers doesn't match!", appVersion, claimJson.get("appvers").getAsString());
      assertEquals("uuid type doesn't match!", "dme-ksuid", claimJson.get("uniqueidtype").getAsString());
      assertEquals("uuid doesn't match!", 27, claimJson.get("uniqueid").getAsString().length());
      assertTrue(claimJson.get("peerip").getAsString().matches("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b"));

      // verify success
      Log.i(TAG, "registerReply.getSessionCookie()="+reply.getSessionCookie());
      assertTrue(reply != null);
      assertTrue(reply.getStatus() == AppClient.ReplyStatus.RS_SUCCESS);
      assertTrue( reply.getSessionCookie().length() > 0);

      // verify uuid has DME generated values since we didnt send any values in RegisterClient
      assertEquals("uuid type doesn't match!", "dme-ksuid", reply.getUniqueIdType());
      assertEquals("uuid doesn't match!", 27, reply.getUniqueId().length());
      assertEquals("uuid bytes type doesn't match!", "dme-ksuid", reply.getUniqueIdTypeBytes().toStringUtf8());
      assertEquals("uuid bytes doesn't match!", 27, reply.getUniqueIdBytes().toStringUtf8().length());

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
    }

    Log.i(TAG, "registerClientTest reply: " + reply.toString());
    assertEquals(0, reply.getVer());
    assertEquals(AppClient.ReplyStatus.RS_SUCCESS, reply.getStatus());
  }

  @Test
  public void registerClientUuidAndType() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    MatchingEngine me = new MatchingEngine(context);
    //me.setUseWifiOnly(useWifiOnly);
    me.setMatchingEngineLocationAllowed(true);
    //me.setAllowSwitchIfNoSubscriberInfo(true);


    AppClient.RegisterClientReply reply = null;

    try {
      Location location = getTestLocation( 47.6062,122.3321);

      AppClient.RegisterClientRequest request = me.createDefaultRegisterClientRequest(context, organizationName)
        .setAppName(applicationName)
        .setAppVers(appVersion)
        //.setUniqueId(uuid)
        //.setUniqueIdType("platos")
        .build();
      if (useHostOverride) {
        reply = me.registerClient(request, hostOverride, portOverride, GRPC_TIMEOUT_MS);
      } else {
        reply = me.registerClient(request, me.generateDmeHostAddress(), me.getPort(), GRPC_TIMEOUT_MS);
      }

      JWT jwt = null;
      try {
        jwt = new JWT(reply.getSessionCookie());
      } catch (DecodeException e) {
        Log.e(TAG, Log.getStackTraceString(e));
        assertFalse("registerClientTest: DecodeException!", true);
      }

      // verify expire timer
      long difftime = (jwt.getExpiresAt().getTime() - jwt.getIssuedAt().getTime());
      assertEquals("Token expires failed:",24, TimeUnit.HOURS.convert(difftime, TimeUnit.MILLISECONDS));
      boolean isExpired = jwt.isExpired(10); // 10 seconds leeway
      assertTrue(!isExpired);

      // verify claim
      Claim c = jwt.getClaim("key");
      JsonObject claimJson = c.asObject(JsonObject.class);
      assertEquals("orgname doesn't match!", organizationName, claimJson.get("orgname").getAsString());
      assertEquals("appname doesn't match!", applicationName, claimJson.get("appname").getAsString());
      assertEquals("appvers doesn't match!", appVersion, claimJson.get("appvers").getAsString());
      assertEquals("uuid type not empty!", meluuid, claimJson.get("uniqueid").getAsString());
      assertEquals("uuid not empty!", uuidType, claimJson.get("uniqueidtype").getAsString());
      assertTrue(claimJson.get("peerip").getAsString().matches("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b"));

      // verify success
      Log.i(TAG, "registerReply.getSessionCookie()="+reply.getSessionCookie());
      assertTrue(reply != null);
      assertTrue(reply.getStatus() == AppClient.ReplyStatus.RS_SUCCESS);
      assertTrue( reply.getSessionCookie().length() > 0);

      // verify UUID and Type is empty since we sent values in RegisterClient
      assertEquals("unique_id is not empty", "", reply.getUniqueId());
      assertEquals("unique_id_type is not empty", "", reply.getUniqueIdType());
      assertEquals("uuid bytes type not empty!", "", reply.getUniqueIdTypeBytes().toStringUtf8());
      assertEquals("uuid bytes not empty!", "", reply.getUniqueIdBytes().toStringUtf8());

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
    }

    Log.i(TAG, "registerClientTest reply: " + reply.toString());
    assertEquals(0, reply.getVer());
    assertEquals(AppClient.ReplyStatus.RS_SUCCESS, reply.getStatus());
  }

  @Test
  public void registerClientPlatosUuidAndType() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    MatchingEngine me = new MatchingEngine(context);
    me.setUseWifiOnly(useWifiOnly);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);

    AppClient.RegisterClientReply reply = null;

    try {
      Location location = getTestLocation( 47.6062,122.3321);

      AppClient.RegisterClientRequest request = me.createDefaultRegisterClientRequest(context, organizationNamePlatos)
        .setAppName(applicationNamePlatos)
        .setAppVers(appVersion)
        .setUniqueIdType("TestAdvertisingID")
        .setUniqueId(me.getUniqueId(context))
        .build();
      if (useHostOverride) {
        reply = me.registerClient(request, hostOverridePlatos, portOverride, GRPC_TIMEOUT_MS);
      } else {
        reply = me.registerClient(request, GRPC_TIMEOUT_MS);
      }
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
    }

    JWT jwt = null;
    try {
      jwt = new JWT(reply.getSessionCookie());
    } catch (DecodeException e) {
      Log.e(TAG, Log.getStackTraceString(e));
      assertFalse("registerClientTest: DecodeException!", true);
    }

    // verify expire timer
    long difftime = (jwt.getExpiresAt().getTime() - jwt.getIssuedAt().getTime());
    assertEquals("Token expires failed:",24, TimeUnit.HOURS.convert(difftime, TimeUnit.MILLISECONDS));
    boolean isExpired = jwt.isExpired(10); // 10 seconds leeway
    assertTrue(!isExpired);

    // verify claim
    Claim c = jwt.getClaim("key");
    JsonObject claimJson = c.asObject(JsonObject.class);
    assertEquals("orgname doesn't match!", organizationNamePlatos, claimJson.get("orgname").getAsString());
    assertEquals("appname doesn't match!", applicationNamePlatos, claimJson.get("appname").getAsString());
    assertEquals("appvers doesn't match!", appVersion, claimJson.get("appvers").getAsString());
    assertEquals("uuid type in claim doesn't match!", "applicationInstallId", claimJson.get("uniqueidtype").getAsString());
    assertEquals("uuid in claim doesn't match!", me.getUniqueId(context), claimJson.get("uniqueid").getAsString());
    assertTrue(claimJson.get("peerip").getAsString().matches("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b"));

    Log.i(TAG, "registerClientFutureTest() response: " + reply.toString());
    assertEquals(0, reply.getVer());
    assertEquals(AppClient.ReplyStatus.RS_SUCCESS, reply.getStatus());

    // verify uuid and type is empty since we sent values in RegisterClient
    assertEquals("uuid type doesn't match!", "", reply.getUniqueIdType());
    assertEquals("uuid doesn't match!", "", reply.getUniqueId());
    assertEquals("uuid bytes type doesn't match!", "", reply.getUniqueIdTypeBytes().toStringUtf8());
    assertEquals("uuid bytes doesn't match!", "", reply.getUniqueIdBytes().toStringUtf8());
  }

  @Test
  public void registerClientPlatosUuidAndTypeBytes() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    MatchingEngine me = new MatchingEngine(context);
    me.setUseWifiOnly(useWifiOnly);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);

    AppClient.RegisterClientReply reply = null;

    try {
      Location location = getTestLocation( 47.6062,122.3321);

      AppClient.RegisterClientRequest request = me.createDefaultRegisterClientRequest(context, organizationNamePlatos)
        .setAppNameBytes(ByteString.copyFromUtf8(applicationNamePlatos))
        .setAppVersBytes(ByteString.copyFromUtf8(appVersion))
        .setUniqueIdTypeBytes(ByteString.copyFromUtf8("applicationInstallId"))
        .setUniqueIdBytes(ByteString.copyFromUtf8(me.getUniqueId(context)))
        .build();
      if (useHostOverride) {
        reply = me.registerClient(request, hostOverridePlatos, portOverride, GRPC_TIMEOUT_MS);
      } else {
        reply = me.registerClient(request, GRPC_TIMEOUT_MS);
      }
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
    }

    JWT jwt = null;
    try {
      jwt = new JWT(reply.getSessionCookie());
    } catch (DecodeException e) {
      Log.e(TAG, Log.getStackTraceString(e));
      assertFalse("registerClientTest: DecodeException!", true);
    }

    // verify expire timer
    long difftime = (jwt.getExpiresAt().getTime() - jwt.getIssuedAt().getTime());
    assertEquals("Token expires failed:",24, TimeUnit.HOURS.convert(difftime, TimeUnit.MILLISECONDS));
    boolean isExpired = jwt.isExpired(10); // 10 seconds leeway
    assertTrue(!isExpired);

    // verify claim
    Claim c = jwt.getClaim("key");
    JsonObject claimJson = c.asObject(JsonObject.class);
    assertEquals("orgname doesn't match!", organizationNamePlatos, claimJson.get("orgname").getAsString());
    assertEquals("appname doesn't match!", applicationNamePlatos, claimJson.get("appname").getAsString());
    assertEquals("appvers doesn't match!", appVersion, claimJson.get("appvers").getAsString());
    assertEquals("uuid type in claim doesn't match!", "applicationInstallId", claimJson.get("uniqueidtype").getAsString());
    assertEquals("uuid in claim doesn't match!", me.getUniqueId(context), claimJson.get("uniqueid").getAsString());
    assertTrue(claimJson.get("peerip").getAsString().matches("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b"));

    Log.i(TAG, "registerClientFutureTest() response: " + reply.toString());
    assertEquals(0, reply.getVer());
    assertEquals(AppClient.ReplyStatus.RS_SUCCESS, reply.getStatus());

    // verify uuid and type is empty since we sent values in RegisterClient
    assertEquals("uuid type doesn't match!", "", reply.getUniqueIdType());
    assertEquals("uuid doesn't match!", "", reply.getUniqueId());
    assertEquals("uuid bytes type doesn't match!", "", reply.getUniqueIdTypeBytes().toStringUtf8());
    assertEquals("uuid bytes doesn't match!", "", reply.getUniqueIdBytes().toStringUtf8());
  }

  @Test
  public void registerClientPlatosAppEmptyUuidAndType() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    MatchingEngine me = new MatchingEngine(context);
    me.setUseWifiOnly(useWifiOnly);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);

    AppClient.RegisterClientReply reply = null;

    try {
      Location location = getTestLocation( 47.6062,122.3321);

      AppClient.RegisterClientRequest request = me.createDefaultRegisterClientRequest(context, organizationNamePlatos)
        .setAppName(applicationNamePlatos)
        .setAppVers(appVersion)
        .setUniqueIdType("")
        .setUniqueId("")
        .build();
      if (useHostOverride) {
        reply = me.registerClient(request, hostOverridePlatos, portOverride, GRPC_TIMEOUT_MS);
      } else {
        reply = me.registerClient(request, GRPC_TIMEOUT_MS);
      }
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
    }

    JWT jwt = null;
    try {
      jwt = new JWT(reply.getSessionCookie());
    } catch (DecodeException e) {
      Log.e(TAG, Log.getStackTraceString(e));
      assertFalse("registerClientTest: DecodeException!", true);
    }

    // verify expire timer
    long difftime = (jwt.getExpiresAt().getTime() - jwt.getIssuedAt().getTime());
    assertEquals("Token expires failed:",24, TimeUnit.HOURS.convert(difftime, TimeUnit.MILLISECONDS));
    boolean isExpired = jwt.isExpired(10); // 10 seconds leeway
    assertTrue(!isExpired);

    // verify claim
    Claim c = jwt.getClaim("key");
    JsonObject claimJson = c.asObject(JsonObject.class);
    assertEquals("orgname doesn't match!", organizationNamePlatos, claimJson.get("orgname").getAsString());
    assertEquals("appname doesn't match!", applicationNamePlatos, claimJson.get("appname").getAsString());
    assertEquals("appvers doesn't match!", appVersion, claimJson.get("appvers").getAsString());
    assertEquals("uuid type in claim doesn't match!", organizationNamePlatos+":"+applicationNamePlatos, claimJson.get("uniqueidtype").getAsString());
    assertEquals("uuid in claim doesn't match!", 27, claimJson.get("uniqueid").getAsString().length());
    assertTrue(claimJson.get("peerip").getAsString().matches("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b"));

    Log.i(TAG, "registerClientTest() response: " + reply.toString());
    assertEquals(0, reply.getVer());
    assertEquals(AppClient.ReplyStatus.RS_SUCCESS, reply.getStatus());

    // verify uuid and type is org:app since we sent app is platos app
    assertEquals("uuid type doesn't match!", organizationNamePlatos+":"+applicationNamePlatos, reply.getUniqueIdType());
    assertEquals("uuid doesn't match!", 27, reply.getUniqueId().length());
    assertEquals("uuid bytes type doesn't match!", organizationNamePlatos+":"+applicationNamePlatos, reply.getUniqueIdTypeBytes().toStringUtf8());
    assertEquals("uuid bytes doesn't match!", 27, reply.getUniqueIdBytes().toStringUtf8().length());
  }

  @Test
  public void registerClientPlatosAppNoUuidAndType() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    MatchingEngine me = new MatchingEngine(context);
    me.setUseWifiOnly(useWifiOnly);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);

    AppClient.RegisterClientReply reply = null;

    try {
      Location location = getTestLocation( 47.6062,122.3321);

      AppClient.RegisterClientRequest request = me.createDefaultRegisterClientRequest(context, organizationNamePlatos)
        .setAppName(applicationNamePlatos)
        .setAppVers(appVersion)
        .build();
      if (useHostOverride) {
        reply = me.registerClient(request, hostOverridePlatos, portOverride, GRPC_TIMEOUT_MS);
      } else {
        reply = me.registerClient(request, GRPC_TIMEOUT_MS);
      }
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
    }

    JWT jwt = null;
    try {
      jwt = new JWT(reply.getSessionCookie());
    } catch (DecodeException e) {
      Log.e(TAG, Log.getStackTraceString(e));
      assertFalse("registerClientTest: DecodeException!", true);
    }

    // verify expire timer
    long difftime = (jwt.getExpiresAt().getTime() - jwt.getIssuedAt().getTime());
    assertEquals("Token expires failed:",24, TimeUnit.HOURS.convert(difftime, TimeUnit.MILLISECONDS));
    boolean isExpired = jwt.isExpired(10); // 10 seconds leeway
    assertTrue(!isExpired);

    // verify claim
    Claim c = jwt.getClaim("key");
    JsonObject claimJson = c.asObject(JsonObject.class);
    assertEquals("orgname doesn't match!", organizationNamePlatos, claimJson.get("orgname").getAsString());
    assertEquals("appname doesn't match!", applicationNamePlatos, claimJson.get("appname").getAsString());
    assertEquals("appvers doesn't match!", appVersion, claimJson.get("appvers").getAsString());
    assertEquals("uuid type in claim doesn't match!", organizationNamePlatos+":"+applicationNamePlatos, claimJson.get("uniqueidtype").getAsString());
    assertEquals("uuid in claim doesn't match!", 27, claimJson.get("uniqueid").getAsString().length());
    assertTrue(claimJson.get("peerip").getAsString().matches("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b"));

    Log.i(TAG, "registerClientTest() response: " + reply.toString());
    assertEquals(0, reply.getVer());
    assertEquals(AppClient.ReplyStatus.RS_SUCCESS, reply.getStatus());

    // verify uuid and type is org:app since we sent app is platos app
    assertEquals("uuid type doesn't match!", organizationNamePlatos+":"+applicationNamePlatos, reply.getUniqueIdType());
    assertEquals("uuid doesn't match!", 27, reply.getUniqueId().length());
    assertEquals("uuid bytes type doesn't match!", organizationNamePlatos+":"+applicationNamePlatos, reply.getUniqueIdTypeBytes().toStringUtf8());
    assertEquals("uuid bytes doesn't match!", 27, reply.getUniqueIdBytes().toStringUtf8().length());
  }

  @Test
  public void registerClientPlatosUuidOnly() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    MatchingEngine me = new MatchingEngine(context);
    me.setUseWifiOnly(useWifiOnly);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);

    AppClient.RegisterClientReply reply = null;

    try {
      Location location = getTestLocation( 47.6062,122.3321);

      AppClient.RegisterClientRequest request = me.createDefaultRegisterClientRequest(context, organizationNamePlatos)
        .setAppName(applicationNamePlatos)
        .setAppVers(appVersion)
        .setUniqueId("xxxx")
        .build();

      if (useHostOverride) {
        reply = me.registerClient(request, hostOverridePlatos, portOverride, GRPC_TIMEOUT_MS);
      } else {
        reply = me.registerClient(request, GRPC_TIMEOUT_MS);
      }

      assertFalse("registerClient was successful!", true);

    } catch (PackageManager.NameNotFoundException nnfe) {
      Log.e(TAG, Log.getStackTraceString(nnfe));
      assertFalse("ExecutionException registering using PackageManager.", true);
    } catch (DmeDnsException dde) {
      Log.e(TAG, Log.getStackTraceString(dde));
      assertFalse("ExecutionException registering client.", true);
    } catch (StatusRuntimeException sre) {
      Log.e(TAG, Log.getStackTraceString(sre));
      assertEquals("INVALID_ARGUMENT: Both, or none of UniqueId and UniqueIdType should be set", sre.getLocalizedMessage());
    } catch (IllegalArgumentException iae) {
      Log.e(TAG, Log.getStackTraceString(iae));
      // This is expected when OrgName is empty.
      assertEquals("RegisterClientRequest requires a organization name.", iae.getLocalizedMessage());
    } catch (ExecutionException ee) {
      Log.e(TAG, Log.getStackTraceString(ee));
      assertFalse("registerClientFutureTest: ExecutionException!", true);
    } catch (InterruptedException ie) {
      Log.e(TAG, Log.getStackTraceString(ie));
      assertFalse("registerClientFutureTest: InterruptedException!", true);
    }
  }

  @Test
  public void registerClientPlatosUuidTypeOnly() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    MatchingEngine me = new MatchingEngine(context);
    me.setUseWifiOnly(useWifiOnly);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);

    AppClient.RegisterClientReply reply = null;

    try {
      Location location = getTestLocation( 47.6062,122.3321);

      AppClient.RegisterClientRequest request = me.createDefaultRegisterClientRequest(context, organizationNamePlatos)
        .setAppName(applicationNamePlatos)
        .setAppVers(appVersion)
        .setUniqueIdType("xxxx")
        .build();

      if (useHostOverride) {
        reply = me.registerClient(request, hostOverridePlatos, portOverride, GRPC_TIMEOUT_MS);
      } else {
        reply = me.registerClient(request, GRPC_TIMEOUT_MS);
      }

      assertFalse("registerClient was successful!", true);

    } catch (PackageManager.NameNotFoundException nnfe) {
      Log.e(TAG, Log.getStackTraceString(nnfe));
      assertFalse("ExecutionException registering using PackageManager.", true);
    } catch (DmeDnsException dde) {
      Log.e(TAG, Log.getStackTraceString(dde));
      assertFalse("ExecutionException registering client.", true);
    } catch (StatusRuntimeException sre) {
      Log.e(TAG, Log.getStackTraceString(sre));
      assertEquals("INVALID_ARGUMENT: Both, or none of UniqueId and UniqueIdType should be set", sre.getLocalizedMessage());
    } catch (IllegalArgumentException iae) {
      Log.e(TAG, Log.getStackTraceString(iae));
      // This is expected when OrgName is empty.
      assertEquals("RegisterClientRequest requires a organization name.", iae.getLocalizedMessage());
    } catch (ExecutionException ee) {
      Log.e(TAG, Log.getStackTraceString(ee));
      assertFalse("registerClientFutureTest: ExecutionException!", true);
    } catch (InterruptedException ie) {
      Log.e(TAG, Log.getStackTraceString(ie));
      assertFalse("registerClientFutureTest: InterruptedException!", true);
    }
  }

  @Test
  public void registerClientNoContext() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    MatchingEngine me = new MatchingEngine(context);
    me.setUseWifiOnly(useWifiOnly);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);

    AppClient.RegisterClientReply reply = null;

    try {
      AppClient.RegisterClientRequest request = me.createDefaultRegisterClientRequest(null,"")
        .setAppName(applicationName)
        .setAppVers("")
        .build();

    } catch (PackageManager.NameNotFoundException nnfe) {
      Log.e(TAG, Log.getStackTraceString(nnfe));
      assertFalse("ExecutionException registering using PackageManager.", true);
    } catch (StatusRuntimeException sre) {
      Log.e(TAG, Log.getStackTraceString(sre));
      // This is expected when AppVers is empty.
      assertEquals("INVALID_ARGUMENT: AppVers cannot be empty", sre.getLocalizedMessage());
    } catch (IllegalArgumentException iae) {
      Log.e(TAG, Log.getStackTraceString(iae));
      // This is expected when context is null
      assertEquals("MatchingEngine requires a working application context.", iae.getLocalizedMessage());
    }
  }

  @Test
  public void registerClientEmptyOrgName() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    MatchingEngine me = new MatchingEngine(context);
    me.setUseWifiOnly(useWifiOnly);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);

    AppClient.RegisterClientReply reply = null;

    try {
      Location location = getTestLocation( 47.6062,122.3321);

      AppClient.RegisterClientRequest request = me.createDefaultRegisterClientRequest(context, "")
        .setAppName(applicationName)
        .setAppVers(appVersion)
        .build();
    } catch (PackageManager.NameNotFoundException nnfe) {
      Log.e(TAG, Log.getStackTraceString(nnfe));
      assertFalse("ExecutionException registering using PackageManager.", true);
    } catch (StatusRuntimeException sre) {
      Log.e(TAG, Log.getStackTraceString(sre));
      assertFalse("registerClientTest: StatusRuntimeException!", true);
    } catch (IllegalArgumentException iae) {
      Log.e(TAG, Log.getStackTraceString(iae));
      // This is expected when OrgName is empty.
      assertEquals("RegisterClientRequest requires a organization name.", iae.getLocalizedMessage());
    }
  }

  @Test
  public void registerClientNoOrgName() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    MatchingEngine me = new MatchingEngine(context);
    me.setUseWifiOnly(useWifiOnly);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);

    AppClient.RegisterClientReply reply = null;

    try {
      Location location = getTestLocation( 47.6062,122.3321);

      AppClient.RegisterClientRequest request = me.createDefaultRegisterClientRequest(context, null)
        .setAppName(applicationName)
        .setAppVers(appVersion)
        .build();
    } catch (PackageManager.NameNotFoundException nnfe) {
      Log.e(TAG, Log.getStackTraceString(nnfe));
      assertFalse("ExecutionException registering using PackageManager.", true);
    } catch (StatusRuntimeException sre) {
      Log.e(TAG, Log.getStackTraceString(sre));
      assertFalse("registerClientTest: StatusRuntimeException!", true);
    } catch (IllegalArgumentException iae) {
      Log.e(TAG, Log.getStackTraceString(iae));
      // This is expected when OrgName is empty.
      assertEquals("RegisterClientRequest requires a organization name.", iae.getLocalizedMessage());
    }
  }

  @Test
  public void registerClientEmptyAppVersion() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    MatchingEngine me = new MatchingEngine(context);
    me.setUseWifiOnly(useWifiOnly);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);

    AppClient.RegisterClientReply reply = null;

    try {
      AppClient.RegisterClientRequest request = me.createDefaultRegisterClientRequest(context, organizationName)
        .setAppName(applicationName)
        .setAppVers("")
        .build();
      if (useHostOverride) {
        reply = me.registerClient(request, hostOverride, portOverride, GRPC_TIMEOUT_MS);
      } else {
        reply = me.registerClient(request, me.generateDmeHostAddress(), me.getPort(), GRPC_TIMEOUT_MS);
      }

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
      // This is expected when AppVers is empty.
      assertEquals("INVALID_ARGUMENT: AppVers cannot be empty", sre.getLocalizedMessage());
    } catch (InterruptedException ie) {
      Log.e(TAG, Log.getStackTraceString(ie));
      assertFalse("registerClientTest: InterruptedException!", true);
    }
  }

  @Test
  public void registerClientEmptyAppName() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    MatchingEngine me = new MatchingEngine(context);
    me.setUseWifiOnly(useWifiOnly);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);

    AppClient.RegisterClientReply reply = null;

    try {
      AppClient.RegisterClientRequest request = me.createDefaultRegisterClientRequest(context, organizationName)
        .setAppName("")
        .setAppVers("1.0")
        .build();
      if (useHostOverride) {
        reply = me.registerClient(request, hostOverride, portOverride, GRPC_TIMEOUT_MS);
      } else {
        reply = me.registerClient(request, me.generateDmeHostAddress(), me.getPort(), GRPC_TIMEOUT_MS);
      }

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
      // This is expected when AppVers is empty.
      assertEquals("INVALID_ARGUMENT: AppName cannot be empty", sre.getLocalizedMessage());
    } catch (InterruptedException ie) {
      Log.e(TAG, Log.getStackTraceString(ie));
      assertFalse("registerClientTest: InterruptedException!", true);
    }
  }

  @Test
  public void registerClientEmptyAuth() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    MatchingEngine me = new MatchingEngine(context);
    me.setUseWifiOnly(useWifiOnly);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);

    AppClient.RegisterClientReply reply = null;

    try {
      AppClient.RegisterClientRequest request = me.createDefaultRegisterClientRequest(context, organizationName)
        .setAppName(applicationNameAuth)
        .setAppVers(appVersion)
        .build();
      if (useHostOverride) {
        reply = me.registerClient(request, hostOverride, portOverride, GRPC_TIMEOUT_MS);
      } else {
        reply = me.registerClient(request, me.generateDmeHostAddress(), me.getPort(), GRPC_TIMEOUT_MS);
      }

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
      // This is expected when AppVers is empty.
      assertEquals("INVALID_ARGUMENT: No authtoken received", sre.getLocalizedMessage());
    } catch (InterruptedException ie) {
      Log.e(TAG, Log.getStackTraceString(ie));
      assertFalse("registerClientTest: InterruptedException!", true);
    }
  }

  @Test
  public void registerClientBadOrgName() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    MatchingEngine me = new MatchingEngine(context);
    me.setUseWifiOnly(useWifiOnly);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);

    AppClient.RegisterClientReply reply = null;

    try {
      AppClient.RegisterClientRequest request = me.createDefaultRegisterClientRequest(context, "badorg")
        .setAppName(applicationName)
        .setAppVers(appVersion)
        .build();
      if (useHostOverride) {
        reply = me.registerClient(request, hostOverride, portOverride, GRPC_TIMEOUT_MS);
      } else {
        reply = me.registerClient(request, me.generateDmeHostAddress(), me.getPort(), GRPC_TIMEOUT_MS);
      }

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
      assertEquals("NOT_FOUND: app not found", sre.getLocalizedMessage());
    } catch (InterruptedException ie) {
      Log.e(TAG, Log.getStackTraceString(ie));
      assertFalse("registerClientTest: InterruptedException!", true);
    }
  }

  @Test
  public void registerClientBadAppVersionOrgName() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    MatchingEngine me = new MatchingEngine(context);
    me.setUseWifiOnly(useWifiOnly);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);

    AppClient.RegisterClientReply reply = null;

    try {
      AppClient.RegisterClientRequest request = me.createDefaultRegisterClientRequest(context, "badorg")
        .setAppName("badapp")
        .setAppVers("badversion")
        .build();
      if (useHostOverride) {
        reply = me.registerClient(request, hostOverride, portOverride, GRPC_TIMEOUT_MS);
      } else {
        reply = me.registerClient(request, me.generateDmeHostAddress(), me.getPort(), GRPC_TIMEOUT_MS);
      }

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
      assertEquals("NOT_FOUND: app not found", sre.getLocalizedMessage());
    } catch (InterruptedException ie) {
      Log.e(TAG, Log.getStackTraceString(ie));
      assertFalse("registerClientTest: InterruptedException!", true);
    }
  }

  @Test
  public void registerClientBadAppName() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    MatchingEngine me = new MatchingEngine(context);
    me.setUseWifiOnly(useWifiOnly);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);

    AppClient.RegisterClientReply reply = null;

    try {
      AppClient.RegisterClientRequest request = me.createDefaultRegisterClientRequest(context, organizationName)
        .setAppName("Leon's Bogus App")
        .setAppVers(appVersion)
        .build();
      if (useHostOverride) {
        reply = me.registerClient(request, hostOverride, portOverride, GRPC_TIMEOUT_MS);
      } else {
        reply = me.registerClient(request, me.generateDmeHostAddress(), me.getPort(), GRPC_TIMEOUT_MS);
      }
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
      // This is expected when appName is wrong.
      assertEquals("NOT_FOUND: app not found", sre.getLocalizedMessage());
    } catch (InterruptedException ie) {
      Log.e(TAG, Log.getStackTraceString(ie));
      assertFalse("registerClientTest: InterruptedException!", true);
    }
  }

  @Test
  public void registerClientBadAppVersion() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    MatchingEngine me = new MatchingEngine(context);
    me.setUseWifiOnly(useWifiOnly);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);

    AppClient.RegisterClientReply reply = null;

    try {
      AppClient.RegisterClientRequest request = me.createDefaultRegisterClientRequest(context, organizationName)
        .setAppName(applicationName)
        .setAppVers("-999")
        .build();
      if (useHostOverride) {
        reply = me.registerClient(request, hostOverride, portOverride, GRPC_TIMEOUT_MS);
      } else {
        reply = me.registerClient(request, me.generateDmeHostAddress(), me.getPort(), GRPC_TIMEOUT_MS);
      }
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
      // This is expected when appVersion is wrong.
      assertEquals("NOT_FOUND: app not found", sre.getLocalizedMessage());
    } catch (InterruptedException ie) {
      Log.e(TAG, Log.getStackTraceString(ie));
      assertFalse("registerClientTest: InterruptedException!", true);
    }
  }

  @Test
  public void registerClientFutureTest() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    MatchingEngine me = new MatchingEngine(context);
    me.setUseWifiOnly(useWifiOnly);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);

    Future<AppClient.RegisterClientReply> registerReplyFuture;
    AppClient.RegisterClientReply reply = null;

    try {
      Location location = getTestLocation( 47.6062,122.3321);

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
    }

    JWT jwt = null;
    try {
      jwt = new JWT(reply.getSessionCookie());
    } catch (DecodeException e) {
      Log.e(TAG, Log.getStackTraceString(e));
      assertFalse("registerClientTest: DecodeException!", true);
    }

    // verify expire timer
    long difftime = (jwt.getExpiresAt().getTime() - jwt.getIssuedAt().getTime());
    assertEquals("Token expires failed:",24, TimeUnit.HOURS.convert(difftime, TimeUnit.MILLISECONDS));
    boolean isExpired = jwt.isExpired(10); // 10 seconds leeway
    assertTrue(!isExpired);

    // verify claim
    Claim c = jwt.getClaim("key");
    JsonObject claimJson = c.asObject(JsonObject.class);
    assertEquals("orgname doesn't match!", organizationName, claimJson.get("orgname").getAsString());
    assertEquals("appname doesn't match!", applicationName, claimJson.get("appname").getAsString());
    assertEquals("appvers doesn't match!", appVersion, claimJson.get("appvers").getAsString());
    assertEquals("uuid type in claim doesn't match!", "dme-ksuid", claimJson.get("uniqueidtype").getAsString());
    assertEquals("uuid in claim doesn't match!", 27, claimJson.get("uniqueid").getAsString().length());
    assertTrue(claimJson.get("peerip").getAsString().matches("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b"));

    // TODO: Validate JWT
    Log.i(TAG, "registerClientFutureTest() response: " + reply.toString());
    assertEquals(0, reply.getVer());
    assertEquals(AppClient.ReplyStatus.RS_SUCCESS, reply.getStatus());

    // verify uuid and type is empty since we sent values in RegisterClient
    assertEquals("uuid type doesn't match!", "dme-ksuid", reply.getUniqueIdType());
    assertEquals("uuid doesn't match!", 27, reply.getUniqueId().length());
    assertEquals("uuid bytes type doesn't match!", "dme-ksuid", reply.getUniqueIdTypeBytes().toStringUtf8());
    assertEquals("uuid bytes doesn't match!", 27, reply.getUniqueIdBytes().toStringUtf8().length());
  }

  @Test
  public void registerClientFutureUuidAndType() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    MatchingEngine me = new MatchingEngine(context);
    me.setUseWifiOnly(useWifiOnly);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);

    Future<AppClient.RegisterClientReply> registerReplyFuture;
    AppClient.RegisterClientReply reply = null;

    try {
      Location location = getTestLocation( 47.6062,122.3321);

      AppClient.RegisterClientRequest request = me.createDefaultRegisterClientRequest(context, organizationName)
        .setAppName(applicationName)
        .setAppVers(appVersion)
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
    }

    JWT jwt = null;
    try {
      jwt = new JWT(reply.getSessionCookie());
    } catch (DecodeException e) {
      Log.e(TAG, Log.getStackTraceString(e));
      assertFalse("registerClientTest: DecodeException!", true);
    }

    // verify expire timer
    long difftime = (jwt.getExpiresAt().getTime() - jwt.getIssuedAt().getTime());
    assertEquals("Token expires failed:",24, TimeUnit.HOURS.convert(difftime, TimeUnit.MILLISECONDS));
    boolean isExpired = jwt.isExpired(10); // 10 seconds leeway
    assertTrue(!isExpired);

    // verify claim
    Claim c = jwt.getClaim("key");
    JsonObject claimJson = c.asObject(JsonObject.class);
    assertEquals("orgname doesn't match!", organizationName, claimJson.get("orgname").getAsString());
    assertEquals("appname doesn't match!", applicationName, claimJson.get("appname").getAsString());
    assertEquals("appvers doesn't match!", appVersion, claimJson.get("appvers").getAsString());
    assertEquals("uuid type in claim doesn't match!", "applicationInstallId", claimJson.get("uniqueidtype").getAsString());
    assertEquals("uuid in claim doesn't match!", me.getUniqueId(context), claimJson.get("uniqueid").getAsString());
    assertTrue(claimJson.get("peerip").getAsString().matches("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b"));

    // TODO: Validate JWT
    Log.i(TAG, "registerClientFutureTest() response: " + reply.toString());
    assertEquals(0, reply.getVer());
    assertEquals(AppClient.ReplyStatus.RS_SUCCESS, reply.getStatus());

    // verify uuid and type is empty since we sent values in RegisterClient
    assertEquals("uuid type doesn't match!", "", reply.getUniqueIdType());
    assertEquals("uuid doesn't match!", "", reply.getUniqueId());
    assertEquals("uuid bytes type doesn't match!", "", reply.getUniqueIdTypeBytes().toStringUtf8());
    assertEquals("uuid bytes doesn't match!", "", reply.getUniqueIdBytes().toStringUtf8());
  }

  @Test
  public void registerClientFuturePlatosUuidAndType() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    MatchingEngine me = new MatchingEngine(context);
    me.setUseWifiOnly(useWifiOnly);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);

    Future<AppClient.RegisterClientReply> registerReplyFuture;
    AppClient.RegisterClientReply reply = null;

    try {
      Location location = getTestLocation( 47.6062,122.3321);

      AppClient.RegisterClientRequest request = me.createDefaultRegisterClientRequest(context, organizationNamePlatos)
        .setAppName(applicationNamePlatos)
        .setAppVers(appVersion)
        .setUniqueIdType("applicationInstallId")
        .setUniqueId(me.getUniqueId(context))
        .build();
      if (useHostOverride) {
        registerReplyFuture = me.registerClientFuture(request, hostOverridePlatos, portOverride, GRPC_TIMEOUT_MS);
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
    }

    JWT jwt = null;
    try {
      jwt = new JWT(reply.getSessionCookie());
    } catch (DecodeException e) {
      Log.e(TAG, Log.getStackTraceString(e));
      assertFalse("registerClientTest: DecodeException!", true);
    }

    // verify expire timer
    long difftime = (jwt.getExpiresAt().getTime() - jwt.getIssuedAt().getTime());
    assertEquals("Token expires failed:",24, TimeUnit.HOURS.convert(difftime, TimeUnit.MILLISECONDS));
    boolean isExpired = jwt.isExpired(10); // 10 seconds leeway
    assertTrue(!isExpired);

    // verify claim
    Claim c = jwt.getClaim("key");
    JsonObject claimJson = c.asObject(JsonObject.class);
    assertEquals("orgname doesn't match!", organizationNamePlatos, claimJson.get("orgname").getAsString());
    assertEquals("appname doesn't match!", applicationNamePlatos, claimJson.get("appname").getAsString());
    assertEquals("appvers doesn't match!", appVersion, claimJson.get("appvers").getAsString());
    assertEquals("uuid type in claim doesn't match!", "applicationInstallId", claimJson.get("uniqueidtype").getAsString());
    assertEquals("uuid in claim doesn't match!", me.getUniqueId(context), claimJson.get("uniqueid").getAsString());
    assertTrue(claimJson.get("peerip").getAsString().matches("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b"));

    // TODO: Validate JWT
    Log.i(TAG, "registerClientFutureTest() response: " + reply.toString());
    assertEquals(0, reply.getVer());
    assertEquals(AppClient.ReplyStatus.RS_SUCCESS, reply.getStatus());

    // verify uuid and type is empty since we sent values in RegisterClient
    assertEquals("uuid type doesn't match!", "", reply.getUniqueIdType());
    assertEquals("uuid doesn't match!", "", reply.getUniqueId());
    assertEquals("uuid bytes type doesn't match!", "", reply.getUniqueIdTypeBytes().toStringUtf8());
    assertEquals("uuid bytes doesn't match!", "", reply.getUniqueIdBytes().toStringUtf8());
  }

  @Test
  public void registerClientFuturePlatosAppEmptyUuidAndType() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    MatchingEngine me = new MatchingEngine(context);
    me.setUseWifiOnly(useWifiOnly);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);

    Future<AppClient.RegisterClientReply> registerReplyFuture;
    AppClient.RegisterClientReply reply = null;

    try {
      Location location = getTestLocation( 47.6062,122.3321);

      AppClient.RegisterClientRequest request = me.createDefaultRegisterClientRequest(context, organizationNamePlatos)
        .setAppName(applicationNamePlatos)
        .setAppVers(appVersion)
        .setUniqueIdType("")
        .setUniqueId("")
        .build();
      if (useHostOverride) {
        registerReplyFuture = me.registerClientFuture(request, hostOverridePlatos, portOverride, GRPC_TIMEOUT_MS);
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
    }

    JWT jwt = null;
    try {
      jwt = new JWT(reply.getSessionCookie());
    } catch (DecodeException e) {
      Log.e(TAG, Log.getStackTraceString(e));
      assertFalse("registerClientTest: DecodeException!", true);
    }

    // verify expire timer
    long difftime = (jwt.getExpiresAt().getTime() - jwt.getIssuedAt().getTime());
    assertEquals("Token expires failed:",24, TimeUnit.HOURS.convert(difftime, TimeUnit.MILLISECONDS));
    boolean isExpired = jwt.isExpired(10); // 10 seconds leeway
    assertTrue(!isExpired);

    // verify claim
    Claim c = jwt.getClaim("key");
    JsonObject claimJson = c.asObject(JsonObject.class);
    assertEquals("orgname doesn't match!", organizationNamePlatos, claimJson.get("orgname").getAsString());
    assertEquals("appname doesn't match!", applicationNamePlatos, claimJson.get("appname").getAsString());
    assertEquals("appvers doesn't match!", appVersion, claimJson.get("appvers").getAsString());
    assertEquals("uuid type in claim doesn't match!", organizationNamePlatos+":"+applicationNamePlatos, claimJson.get("uniqueidtype").getAsString());
    assertEquals("uuid in claim doesn't match!", 27, claimJson.get("uniqueid").getAsString().length());
    assertTrue(claimJson.get("peerip").getAsString().matches("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b"));

    Log.i(TAG, "registerClientFutureTest() response: " + reply.toString());
    assertEquals(0, reply.getVer());
    assertEquals(AppClient.ReplyStatus.RS_SUCCESS, reply.getStatus());

    // verify uuid and type is org:app since we sent app is platos app
    assertEquals("uuid type doesn't match!", organizationNamePlatos+":"+applicationNamePlatos, reply.getUniqueIdType());
    assertEquals("uuid doesn't match!", 27, reply.getUniqueId().length());
    assertEquals("uuid bytes type doesn't match!", organizationNamePlatos+":"+applicationNamePlatos, reply.getUniqueIdTypeBytes().toStringUtf8());
    assertEquals("uuid bytes doesn't match!", 27, reply.getUniqueIdBytes().toStringUtf8().length());
  }

  @Test
  public void registerClientFuturePlatosAppNoUuidAndType() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    MatchingEngine me = new MatchingEngine(context);
    me.setUseWifiOnly(useWifiOnly);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);

    Future<AppClient.RegisterClientReply> registerReplyFuture;
    AppClient.RegisterClientReply reply = null;

    try {
      Location location = getTestLocation( 47.6062,122.3321);

      AppClient.RegisterClientRequest request = me.createDefaultRegisterClientRequest(context, organizationNamePlatos)
        .setAppName(applicationNamePlatos)
        .setAppVers(appVersion)
        .build();
      if (useHostOverride) {
        registerReplyFuture = me.registerClientFuture(request, hostOverridePlatos, portOverride, GRPC_TIMEOUT_MS);
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
    }

    JWT jwt = null;
    try {
      jwt = new JWT(reply.getSessionCookie());
    } catch (DecodeException e) {
      Log.e(TAG, Log.getStackTraceString(e));
      assertFalse("registerClientTest: DecodeException!", true);
    }

    // verify expire timer
    long difftime = (jwt.getExpiresAt().getTime() - jwt.getIssuedAt().getTime());
    assertEquals("Token expires failed:",24, TimeUnit.HOURS.convert(difftime, TimeUnit.MILLISECONDS));
    boolean isExpired = jwt.isExpired(10); // 10 seconds leeway
    assertTrue(!isExpired);

    // verify claim
    Claim c = jwt.getClaim("key");
    JsonObject claimJson = c.asObject(JsonObject.class);
    assertEquals("orgname doesn't match!", organizationNamePlatos, claimJson.get("orgname").getAsString());
    assertEquals("appname doesn't match!", applicationNamePlatos, claimJson.get("appname").getAsString());
    assertEquals("appvers doesn't match!", appVersion, claimJson.get("appvers").getAsString());
    assertEquals("uuid type in claim doesn't match!", organizationNamePlatos+":"+applicationNamePlatos, claimJson.get("uniqueidtype").getAsString());
    assertEquals("uuid in claim doesn't match!", 27, claimJson.get("uniqueid").getAsString().length());
    assertTrue(claimJson.get("peerip").getAsString().matches("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b"));

    Log.i(TAG, "registerClientFutureTest() response: " + reply.toString());
    assertEquals(0, reply.getVer());
    assertEquals(AppClient.ReplyStatus.RS_SUCCESS, reply.getStatus());

    // verify uuid and type is org:app since we sent app is platos app
    assertEquals("uuid type doesn't match!", organizationNamePlatos+":"+applicationNamePlatos, reply.getUniqueIdType());
    assertEquals("uuid doesn't match!", 27, reply.getUniqueId().length());
    assertEquals("uuid bytes type doesn't match!", organizationNamePlatos+":"+applicationNamePlatos, reply.getUniqueIdTypeBytes().toStringUtf8());
    assertEquals("uuid bytes doesn't match!", 27, reply.getUniqueIdBytes().toStringUtf8().length());
  }

  @Test
  public void registerClientFuturePlatosUuidOnly() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    MatchingEngine me = new MatchingEngine(context);
    me.setUseWifiOnly(useWifiOnly);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);

    Future<AppClient.RegisterClientReply> registerReplyFuture;
    AppClient.RegisterClientReply reply = null;

    try {
      Location location = getTestLocation( 47.6062,122.3321);

      AppClient.RegisterClientRequest request = me.createDefaultRegisterClientRequest(context, organizationNamePlatos)
        .setAppName(applicationNamePlatos)
        .setAppVers(appVersion)
        .setUniqueId("xxxx")
        .build();

      if (useHostOverride) {
        registerReplyFuture = me.registerClientFuture(request, hostOverridePlatos, portOverride, GRPC_TIMEOUT_MS);
      } else {
        registerReplyFuture = me.registerClientFuture(request, GRPC_TIMEOUT_MS);
      }
      reply = registerReplyFuture.get();

      assertFalse("registerClient was successful!", true);

    } catch (PackageManager.NameNotFoundException nnfe) {
      Log.e(TAG, Log.getStackTraceString(nnfe));
      assertFalse("ExecutionException registering using PackageManager.", true);
    } catch (DmeDnsException dde) {
      Log.e(TAG, Log.getStackTraceString(dde));
      assertFalse("ExecutionException registering client.", true);
    } catch (StatusRuntimeException sre) {
      Log.e(TAG, Log.getStackTraceString(sre));
      assertFalse("registerClientFutureTest: StatusRuntimeException!", true);
    } catch (IllegalArgumentException iae) {
      Log.e(TAG, Log.getStackTraceString(iae));
      assertFalse("registerClientFutureTest: IllegalArgumentException!", true);
    } catch (ExecutionException ee) {
      Log.e(TAG, Log.getStackTraceString(ee));
      assertEquals("io.grpc.StatusRuntimeException: INVALID_ARGUMENT: Both, or none of UniqueId and UniqueIdType should be set", ee.getLocalizedMessage());
    } catch (InterruptedException ie) {
      Log.e(TAG, Log.getStackTraceString(ie));
      assertFalse("registerClientFutureTest: InterruptedException!", true);
    }
  }

  @Test
  public void registerClientFuturePlatosUuidTypeOnly() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    MatchingEngine me = new MatchingEngine(context);
    me.setUseWifiOnly(useWifiOnly);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);

    Future<AppClient.RegisterClientReply> registerReplyFuture;
    AppClient.RegisterClientReply reply = null;

    try {
      Location location = getTestLocation( 47.6062,122.3321);

      AppClient.RegisterClientRequest request = me.createDefaultRegisterClientRequest(context, organizationNamePlatos)
        .setAppName(applicationNamePlatos)
        .setAppVers(appVersion)
        .setUniqueIdType("xxxx")
        .build();

      if (useHostOverride) {
        registerReplyFuture = me.registerClientFuture(request, hostOverridePlatos, portOverride, GRPC_TIMEOUT_MS);
      } else {
        registerReplyFuture = me.registerClientFuture(request, GRPC_TIMEOUT_MS);
      }
      reply = registerReplyFuture.get();

      assertFalse("registerClient was successful!", true);

    } catch (PackageManager.NameNotFoundException nnfe) {
      Log.e(TAG, Log.getStackTraceString(nnfe));
      assertFalse("ExecutionException registering using PackageManager.", true);
    } catch (DmeDnsException dde) {
      Log.e(TAG, Log.getStackTraceString(dde));
      assertFalse("ExecutionException registering client.", true);
    } catch (StatusRuntimeException sre) {
      Log.e(TAG, Log.getStackTraceString(sre));
      assertEquals("INVALID_ARGUMENT: Both, or none of UniqueId and UniqueIdType should be set", sre.getLocalizedMessage());
    } catch (IllegalArgumentException iae) {
      Log.e(TAG, Log.getStackTraceString(iae));
      // This is expected when OrgName is empty.
      assertEquals("RegisterClientRequest requires a organization name.", iae.getLocalizedMessage());
    } catch (ExecutionException ee) {
      Log.e(TAG, Log.getStackTraceString(ee));
      assertEquals("io.grpc.StatusRuntimeException: INVALID_ARGUMENT: Both, or none of UniqueId and UniqueIdType should be set", ee.getLocalizedMessage());
    } catch (InterruptedException ie) {
      Log.e(TAG, Log.getStackTraceString(ie));
      assertFalse("registerClientFutureTest: InterruptedException!", true);
    }
  }

  @Test
  public void registerClientFutureEmptyAppVersion() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    MatchingEngine me = new MatchingEngine(context);
    me.setUseWifiOnly(useWifiOnly);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);

    Future<AppClient.RegisterClientReply> registerReplyFuture;
    AppClient.RegisterClientReply reply = null;

    try {
      AppClient.RegisterClientRequest request = me.createDefaultRegisterClientRequest(context, organizationName)
        .setAppName(applicationName)
        .setAppVers("")
        .build();
      if (useHostOverride) {
        registerReplyFuture = me.registerClientFuture(request, hostOverride, portOverride, GRPC_TIMEOUT_MS);
      } else {
        registerReplyFuture = me.registerClientFuture(request, me.generateDmeHostAddress(), me.getPort(), GRPC_TIMEOUT_MS);
      }
      reply = registerReplyFuture.get();
      assert(reply != null);

    } catch (PackageManager.NameNotFoundException nnfe) {
      Log.e(TAG, Log.getStackTraceString(nnfe));
      assertFalse("ExecutionException registering using PackageManager.", true);
    } catch (DmeDnsException dde) {
      Log.e(TAG, Log.getStackTraceString(dde));
      assertFalse("registerClientTest: DmeDnsException!", true);
    } catch (ExecutionException ee) {
      Log.e(TAG, Log.getStackTraceString(ee));
      assertEquals("io.grpc.StatusRuntimeException: INVALID_ARGUMENT: AppVers cannot be empty", ee.getLocalizedMessage());
    } catch (InterruptedException ie) {
      Log.e(TAG, Log.getStackTraceString(ie));
      assertFalse("registerClientTest: InterruptedException!", true);
    }
  }

  @Test
  public void registerClientFutureEmptyAppName() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    MatchingEngine me = new MatchingEngine(context);
    me.setUseWifiOnly(useWifiOnly);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);

    Future<AppClient.RegisterClientReply> registerReplyFuture;
    AppClient.RegisterClientReply reply = null;

    try {
      AppClient.RegisterClientRequest request = me.createDefaultRegisterClientRequest(context, organizationName)
        .setAppName("")
        .setAppVers("1.0")
        .build();
      if (useHostOverride) {
        registerReplyFuture = me.registerClientFuture(request, hostOverride, portOverride, GRPC_TIMEOUT_MS);
      } else {
        registerReplyFuture = me.registerClientFuture(request, me.generateDmeHostAddress(), me.getPort(), GRPC_TIMEOUT_MS);
      }

      reply = registerReplyFuture.get();
      assert(reply != null);

    } catch (PackageManager.NameNotFoundException nnfe) {
      Log.e(TAG, Log.getStackTraceString(nnfe));
      assertFalse("ExecutionException registering using PackageManager.", true);
    } catch (DmeDnsException dde) {
      Log.e(TAG, Log.getStackTraceString(dde));
      assertFalse("registerClientTest: DmeDnsException!", true);
    } catch (ExecutionException ee) {
      Log.e(TAG, Log.getStackTraceString(ee));
      assertEquals("io.grpc.StatusRuntimeException: INVALID_ARGUMENT: AppName cannot be empty", ee.getLocalizedMessage());
    } catch (InterruptedException ie) {
      Log.e(TAG, Log.getStackTraceString(ie));
      assertFalse("registerClientTest: InterruptedException!", true);
    }
  }

  @Test
  public void registerClientFutureEmptyAuth() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    MatchingEngine me = new MatchingEngine(context);
    me.setUseWifiOnly(useWifiOnly);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);

    Future<AppClient.RegisterClientReply> registerReplyFuture;
    AppClient.RegisterClientReply reply = null;

    try {
      AppClient.RegisterClientRequest request = me.createDefaultRegisterClientRequest(context, organizationName)
        .setAppName(applicationNameAuth)
        .setAppVers(appVersion)
        .build();
      if (useHostOverride) {
        registerReplyFuture = me.registerClientFuture(request, hostOverride, portOverride, GRPC_TIMEOUT_MS);
      } else {
        registerReplyFuture = me.registerClientFuture(request, me.generateDmeHostAddress(), me.getPort(), GRPC_TIMEOUT_MS);
      }

      reply = registerReplyFuture.get();
      assert(reply != null);

    } catch (PackageManager.NameNotFoundException nnfe) {
      Log.e(TAG, Log.getStackTraceString(nnfe));
      assertFalse("ExecutionException registering using PackageManager.", true);
    } catch (DmeDnsException dde) {
      Log.e(TAG, Log.getStackTraceString(dde));
      assertFalse("registerClientTest: DmeDnsException!", true);
    } catch (ExecutionException ee) {
      Log.e(TAG, Log.getStackTraceString(ee));
      assertEquals("io.grpc.StatusRuntimeException: INVALID_ARGUMENT: No authtoken received", ee.getLocalizedMessage());
    } catch (InterruptedException ie) {
      Log.e(TAG, Log.getStackTraceString(ie));
      assertFalse("registerClientTest: InterruptedException!", true);
    }
  }

  @Test
  public void registerClientFutureBadOrgName() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    MatchingEngine me = new MatchingEngine(context);
    me.setUseWifiOnly(useWifiOnly);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);

    Future<AppClient.RegisterClientReply> registerReplyFuture;
    AppClient.RegisterClientReply reply = null;

    try {
      AppClient.RegisterClientRequest request = me.createDefaultRegisterClientRequest(context, "badorg")
        .setAppName(applicationName)
        .setAppVers(appVersion)
        .build();
      if (useHostOverride) {
        registerReplyFuture = me.registerClientFuture(request, hostOverride, portOverride, GRPC_TIMEOUT_MS);
      } else {
        registerReplyFuture = me.registerClientFuture(request, me.generateDmeHostAddress(), me.getPort(), GRPC_TIMEOUT_MS);
      }

      reply = registerReplyFuture.get();
      assert(reply != null);

    } catch (PackageManager.NameNotFoundException nnfe) {
      Log.e(TAG, Log.getStackTraceString(nnfe));
      assertFalse("ExecutionException registering using PackageManager.", true);
    } catch (DmeDnsException dde) {
      Log.e(TAG, Log.getStackTraceString(dde));
      assertFalse("registerClientTest: DmeDnsException!", true);
    } catch (ExecutionException ee) {
      Log.e(TAG, Log.getStackTraceString(ee));
      assertEquals("io.grpc.StatusRuntimeException: NOT_FOUND: app not found", ee.getLocalizedMessage());
    } catch (InterruptedException ie) {
      Log.e(TAG, Log.getStackTraceString(ie));
      assertFalse("registerClientTest: InterruptedException!", true);
    }
  }

  @Test
  public void registerClientFutureBadAppVersionOrgName() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    MatchingEngine me = new MatchingEngine(context);
    me.setUseWifiOnly(useWifiOnly);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);

    Future<AppClient.RegisterClientReply> registerReplyFuture;
    AppClient.RegisterClientReply reply = null;

    try {
      AppClient.RegisterClientRequest request = me.createDefaultRegisterClientRequest(context, "badorg")
        .setAppName("badapp")
        .setAppVers("badversion")
        .build();
      if (useHostOverride) {
        registerReplyFuture = me.registerClientFuture(request, hostOverride, portOverride, GRPC_TIMEOUT_MS);
      } else {
        registerReplyFuture = me.registerClientFuture(request, me.generateDmeHostAddress(), me.getPort(), GRPC_TIMEOUT_MS);
      }

      reply = registerReplyFuture.get();
      assert(reply != null);

    } catch (PackageManager.NameNotFoundException nnfe) {
      Log.e(TAG, Log.getStackTraceString(nnfe));
      assertFalse("ExecutionException registering using PackageManager.", true);
    } catch (DmeDnsException dde) {
      Log.e(TAG, Log.getStackTraceString(dde));
      assertFalse("registerClientTest: DmeDnsException!", true);
    } catch (ExecutionException ee) {
      Log.e(TAG, Log.getStackTraceString(ee));
      assertEquals("io.grpc.StatusRuntimeException: NOT_FOUND: app not found", ee.getLocalizedMessage());
    } catch (InterruptedException ie) {
      Log.e(TAG, Log.getStackTraceString(ie));
      assertFalse("registerClientTest: InterruptedException!", true);
    }
  }

  @Test
  public void registerClientFutureBadAppName() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    MatchingEngine me = new MatchingEngine(context);
    me.setUseWifiOnly(useWifiOnly);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);

    Future<AppClient.RegisterClientReply> registerReplyFuture;
    AppClient.RegisterClientReply reply = null;

    try {
      AppClient.RegisterClientRequest request = me.createDefaultRegisterClientRequest(context, organizationName)
        .setAppName("Leon's Bogus App")
        .setAppVers(appVersion)
        .build();
      if (useHostOverride) {
        registerReplyFuture = me.registerClientFuture(request, hostOverride, portOverride, GRPC_TIMEOUT_MS);
      } else {
        registerReplyFuture = me.registerClientFuture(request, me.generateDmeHostAddress(), me.getPort(), GRPC_TIMEOUT_MS);
      }

      reply = registerReplyFuture.get();
      assert(reply != null);

    } catch (PackageManager.NameNotFoundException nnfe) {
      Log.e(TAG, Log.getStackTraceString(nnfe));
      assertFalse("ExecutionException registering using PackageManager.", true);
    } catch (DmeDnsException dde) {
      Log.e(TAG, Log.getStackTraceString(dde));
      assertFalse("registerClientTest: DmeDnsException!", true);
    } catch (ExecutionException ee) {
      Log.e(TAG, Log.getStackTraceString(ee));
      assertEquals("io.grpc.StatusRuntimeException: NOT_FOUND: app not found", ee.getLocalizedMessage());
    } catch (InterruptedException ie) {
      Log.e(TAG, Log.getStackTraceString(ie));
      assertFalse("registerClientTest: InterruptedException!", true);
    }
  }

  @Test
  public void registerClientFutureBadAppVersion() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    MatchingEngine me = new MatchingEngine(context);
    me.setUseWifiOnly(useWifiOnly);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);

    Future<AppClient.RegisterClientReply> registerReplyFuture;
    AppClient.RegisterClientReply reply = null;

    try {
      AppClient.RegisterClientRequest request = me.createDefaultRegisterClientRequest(context, organizationName)
        .setAppName(applicationName)
        .setAppVers("-999")
        .build();
      if (useHostOverride) {
        registerReplyFuture = me.registerClientFuture(request, hostOverride, portOverride, GRPC_TIMEOUT_MS);
      } else {
        registerReplyFuture = me.registerClientFuture
          (request, me.generateDmeHostAddress(), me.getPort(), GRPC_TIMEOUT_MS);
      }

      reply = registerReplyFuture.get();
      assert(reply != null);

    } catch (PackageManager.NameNotFoundException nnfe) {
      Log.e(TAG, Log.getStackTraceString(nnfe));
      assertFalse("ExecutionException registering using PackageManager.", true);
    } catch (DmeDnsException dde) {
      Log.e(TAG, Log.getStackTraceString(dde));
      assertFalse("registerClientTest: DmeDnsException!", true);
    } catch (ExecutionException ee) {
      Log.e(TAG, Log.getStackTraceString(ee));
      assertEquals("io.grpc.StatusRuntimeException: NOT_FOUND: app not found", ee.getLocalizedMessage());
    } catch (InterruptedException ie) {
      Log.e(TAG, Log.getStackTraceString(ie));
      assertFalse("registerClientTest: InterruptedException!", true);
    }
  }


}

