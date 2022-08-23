/**
 * Copyright 2018-2022 MobiledgeX, Inc. All rights and licenses reserved.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.UiAutomation;
import android.content.Context;
import android.os.Build;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import distributed_match_engine.AppClient;
import distributed_match_engine.Appcommon;
import distributed_match_engine.Qos;
import distributed_match_engine.SessionOuterClass;
import io.grpc.StatusRuntimeException;

@RunWith(AndroidJUnit4.class)
public class QosPrioritySessionTest {
  /*
  These tests are run against a development environment DME in non-TLS mode.
  Edit edge-cloud-infra/e2e-tests/setups/local_multi.yml, and add the following
  to the envvars section for dme1:
  PUBLIC_ENDPOINT_TLS: false
  In edge-cloud-infra, execute "make test-start" to bring up the environment.
  This also brings up the QOS API server simulator, which is needed for the tests to run.
   */
  public static final String TAG = "QosPrioritySessionTest";
  public static final long GRPC_TIMEOUT_MS = 10000;

  public static final String organizationName = "AcmeAppCo";
  public static final String applicationName = "autoprovapp";
  public static final String appVersion = "1.0";

  public static String hostOverride = "192.168.1.86"; // Set to IP where the DME is running.

  public static int portOverride = 50051;
  public boolean useHostOverride = true;

  static String qosSesId = "";

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

  // Every call needs registration to be called first at some point.
  // Test only!
  public void registerClient(MatchingEngine me) {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    SessionOuterClass.RegisterClientReply registerReply;
    SessionOuterClass.RegisterClientRequest registerClientRequest;

    try {
      // The app version will be null, but we can build from scratch for test
      SessionOuterClass.RegisterClientRequest.Builder regRequestBuilder = SessionOuterClass.RegisterClientRequest.newBuilder()
              .setOrgName(organizationName)
              .setAppName(applicationName)
              .setAppVers(appVersion);
      registerClientRequest = regRequestBuilder.build();
      Log.i(TAG, "registerClientRequest="+registerClientRequest);
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
      assertTrue(registerReply.getStatus() == Appcommon.ReplyStatus.RS_SUCCESS);
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
  public void qosPrioritySessionCreateFutureTest() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    MatchingEngine me = new MatchingEngine(context);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);
    me.setSSLEnabled(false);

    try {
      registerClient(me);

      int port = 8008;
      int duration = 300;
      String ipUserEquipment = "192.168.0.1";
      String ipApplicationServer = "127.0.0.1";
      String protocol = "TCP";
      String profile = "QOS_LOW_LATENCY";

      Qos.QosPrioritySessionCreateRequest.Builder builder = me.createDefaultQosPrioritySessionCreateRequest(context);
      builder.setPortApplicationServer("8008");
      builder.setIpUserEquipment(ipUserEquipment);
      builder.setIpApplicationServer(ipApplicationServer);
      builder.setProtocolIn(Qos.QosSessionProtocol.valueOf(protocol));
      builder.setProfile(Qos.QosSessionProfile.valueOf(profile));
      builder.setSessionDuration(duration);

      Qos.QosPrioritySessionCreateRequest qosPrioritySessionCreateRequest;
      Future<Qos.QosPrioritySessionReply> qosPrioritySessionReplyFuture;

      qosPrioritySessionCreateRequest = builder.build();

      if (useHostOverride) {
        //! [qosprioritysessioncreatefutureoverrideexample]
        qosPrioritySessionReplyFuture = me.qosPrioritySessionCreateFuture(qosPrioritySessionCreateRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
        //! [qosprioritysessioncreatefutureoverrideexample]
      } else {
        //! [qosprioritysessioncreatefutureexample]
        qosPrioritySessionReplyFuture = me.qosPrioritySessionCreateFuture(qosPrioritySessionCreateRequest, GRPC_TIMEOUT_MS);
        //! [qosprioritysessioncreatefutureexample]
      }
      Qos.QosPrioritySessionReply qosPrioritySessionReply = qosPrioritySessionReplyFuture.get();
      Log.i(TAG, "mQosPrioritySessionReply.getSessionId()="+ qosPrioritySessionReply.getSessionId()+" mQosPrioritySessionReply.getHttpStatus()="+ qosPrioritySessionReply.getHttpStatus());

      assert (qosPrioritySessionReply != null);
      assertEquals(0, qosPrioritySessionReply.getVer());
      assertEquals(profile, qosPrioritySessionReply.getProfile().name());
      assertEquals(duration, qosPrioritySessionReply.getSessionDuration());
      assert (qosPrioritySessionReply.getSessionId() != "");
      qosSesId = qosPrioritySessionReply.getSessionId();

    } catch (ExecutionException ee) {
      Log.e(TAG, Log.getStackTraceString(ee));
      assertFalse("QosPrioritySessionCreate: ExecutionExecution!", true);
    } catch (StatusRuntimeException sre) {
      Log.e(TAG, Log.getStackTraceString(sre));
      assertFalse("QosPrioritySessionCreate: StatusRuntimeException!", true);
    } catch (InterruptedException ie) {
      Log.e(TAG, Log.getStackTraceString(ie));
      assertFalse("QosPrioritySessionCreate: InterruptedException!", true);
    }
  }

  @Test
  public void qosPrioritySessionCreateTest() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    MatchingEngine me = new MatchingEngine(context);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);
    me.setSSLEnabled(false);

    try {
      registerClient(me);

      int port = 8008;
      int duration = 300;
      String ipUserEquipment = "192.168.0.1";
      String ipApplicationServer = "127.0.0.1";
      String protocol = "TCP";
      String profile = "QOS_LOW_LATENCY";

      Qos.QosPrioritySessionCreateRequest.Builder builder = me.createDefaultQosPrioritySessionCreateRequest(context);
      builder.setPortApplicationServer(""+port);
      builder.setIpUserEquipment(ipUserEquipment);
      builder.setIpApplicationServer(ipApplicationServer);
      builder.setProtocolIn(Qos.QosSessionProtocol.valueOf(protocol));
      builder.setProfile(Qos.QosSessionProfile.valueOf(profile));
      builder.setSessionDuration(duration);

      Qos.QosPrioritySessionCreateRequest qosPrioritySessionCreateRequest;

      qosPrioritySessionCreateRequest = builder.build();

      Qos.QosPrioritySessionReply qosPrioritySessionReply;

      if (useHostOverride) {
        //! [qosprioritysessioncreatefutureoverrideexample]
        qosPrioritySessionReply = me.qosPrioritySessionCreate(qosPrioritySessionCreateRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
        //! [qosprioritysessioncreatefutureoverrideexample]
      } else {
        //! [qosprioritysessioncreatefutureexample]
        qosPrioritySessionReply = me.qosPrioritySessionCreate(qosPrioritySessionCreateRequest, GRPC_TIMEOUT_MS);
        //! [qosprioritysessioncreatefutureexample]
      }
      Log.i(TAG, "mQosPrioritySessionReply.getSessionId()="+ qosPrioritySessionReply.getSessionId()+" mQosPrioritySessionReply.getHttpStatus()="+ qosPrioritySessionReply.getHttpStatus());

      assert (qosPrioritySessionReply != null);
      assertEquals(0, qosPrioritySessionReply.getVer());
      assertEquals(profile, qosPrioritySessionReply.getProfile().name());
      assertEquals(duration, qosPrioritySessionReply.getSessionDuration());
      assert (qosPrioritySessionReply.getSessionId() != "");
      qosSesId = qosPrioritySessionReply.getSessionId();

    } catch (ExecutionException ee) {
      Log.e(TAG, Log.getStackTraceString(ee));
      assertFalse("QosPrioritySessionCreate: ExecutionExecution!", true);
    } catch (StatusRuntimeException sre) {
      Log.e(TAG, Log.getStackTraceString(sre));
      assertFalse("QosPrioritySessionCreate: StatusRuntimeException!", true);
    } catch (InterruptedException ie) {
      Log.e(TAG, Log.getStackTraceString(ie));
      assertFalse("QosPrioritySessionCreate: InterruptedException!", true);
    }
  }

  @Test
  public void qosPrioritySessionCreateNoRegisterTest() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    MatchingEngine me = new MatchingEngine(context);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);
    me.setSSLEnabled(false);

    try {
      Qos.QosPrioritySessionCreateRequest.Builder builder = me.createDefaultQosPrioritySessionCreateRequest(context);
    } catch (IllegalArgumentException iae) {
      Log.e(TAG, Log.getStackTraceString(iae));
      assertEquals(iae.getMessage(), ("An unexpired RegisterClient sessionCookie is required."));
    } catch (StatusRuntimeException sre) {
      Log.e(TAG, Log.getStackTraceString(sre));
      assertFalse("QosPrioritySessionCreate: StatusRuntimeException!", true);
    }
  }

  @Test
  public void qosPrioritySessionDeleteFutureTest() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    MatchingEngine me = new MatchingEngine(context);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);
    me.setSSLEnabled(false);

    try {
      registerClient(me);

      String protocol = "TCP";
      String profile = "QOS_LOW_LATENCY";

      Qos.QosPrioritySessionDeleteRequest.Builder builder = me.createDefaultQosPrioritySessionDeleteRequest(context);
      builder.setProfile(Qos.QosSessionProfile.valueOf(profile));
      builder.setSessionId(qosSesId);

      Qos.QosPrioritySessionDeleteRequest qosPrioritySessionDeleteRequest;
      Future<Qos.QosPrioritySessionDeleteReply> qosPrioritySessionDeleteReplyFuture;

      qosPrioritySessionDeleteRequest = builder.build();

      if (useHostOverride) {
        //! [qosprioritysessiondeletefutureoverrideexample]
        qosPrioritySessionDeleteReplyFuture = me.qosPrioritySessionDeleteFuture(qosPrioritySessionDeleteRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
        //! [qosprioritysessiondeletefutureoverrideexample]
      } else {
        //! [qosprioritysessiondeletefutureexample]
        qosPrioritySessionDeleteReplyFuture = me.qosPrioritySessionDeleteFuture(qosPrioritySessionDeleteRequest, GRPC_TIMEOUT_MS);
        //! [qosprioritysessiondeletefutureexample]
      }
      Qos.QosPrioritySessionDeleteReply qosPrioritySessionDeleteReply = qosPrioritySessionDeleteReplyFuture.get();
      Log.i(TAG, "qosPrioritySessionDeleteReply.getStatus()="+ qosPrioritySessionDeleteReply.getStatus());

      assert (qosPrioritySessionDeleteReply != null);
      assertEquals(0, qosPrioritySessionDeleteReply.getVer());
      assertEquals("QDEL_DELETED", qosPrioritySessionDeleteReply.getStatus().name());

    } catch (ExecutionException ee) {
      Log.e(TAG, Log.getStackTraceString(ee));
      assertFalse("QosPrioritySessionDelete: ExecutionExecution!", true);
    } catch (StatusRuntimeException sre) {
      Log.e(TAG, Log.getStackTraceString(sre));
      assertFalse("QosPrioritySessionDelete: StatusRuntimeException!", true);
    } catch (InterruptedException ie) {
      Log.e(TAG, Log.getStackTraceString(ie));
      assertFalse("QosPrioritySessionDelete: InterruptedException!", true);
    }
  }

  @Test
  public void qosPrioritySessionDeleteTest() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    MatchingEngine me = new MatchingEngine(context);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);
    me.setSSLEnabled(false);

    try {
      registerClient(me);

      String protocol = "TCP";
      String profile = "QOS_LOW_LATENCY";

      Qos.QosPrioritySessionDeleteRequest.Builder builder = me.createDefaultQosPrioritySessionDeleteRequest(context);
      builder.setProfile(Qos.QosSessionProfile.valueOf(profile));
      builder.setSessionId(qosSesId);
      Qos.QosPrioritySessionDeleteRequest qosPrioritySessionDeleteRequest = builder.build();

      Qos.QosPrioritySessionDeleteReply qosPrioritySessionDeleteReply;

      if (useHostOverride) {
        //! [qosprioritysessiondeletefutureoverrideexample]
        qosPrioritySessionDeleteReply = me.qosPrioritySessionDelete(qosPrioritySessionDeleteRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
        //! [qosprioritysessiondeletefutureoverrideexample]
      } else {
        //! [qosprioritysessiondeletefutureexample]
        qosPrioritySessionDeleteReply = me.qosPrioritySessionDelete(qosPrioritySessionDeleteRequest, GRPC_TIMEOUT_MS);
        //! [qosprioritysessiondeletefutureexample]
      }
      Log.i(TAG, "qosPrioritySessionDeleteReply.getStatus()="+ qosPrioritySessionDeleteReply.getStatus());

      assert (qosPrioritySessionDeleteReply != null);
      assertEquals(0, qosPrioritySessionDeleteReply.getVer());
      assertEquals("QDEL_DELETED", qosPrioritySessionDeleteReply.getStatus().name());

    } catch (ExecutionException ee) {
      Log.e(TAG, Log.getStackTraceString(ee));
      assertFalse("QosPrioritySessionDelete: ExecutionExecution!", true);
    } catch (StatusRuntimeException sre) {
      Log.e(TAG, Log.getStackTraceString(sre));
      assertFalse("QosPrioritySessionDelete: StatusRuntimeException!", true);
    } catch (InterruptedException ie) {
      Log.e(TAG, Log.getStackTraceString(ie));
      assertFalse("QosPrioritySessionDelete: InterruptedException!", true);
    }
  }

  @Test
  public void qosPrioritySessionDeleteNonExistentTest() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    MatchingEngine me = new MatchingEngine(context);
    me.setMatchingEngineLocationAllowed(true);
    me.setAllowSwitchIfNoSubscriberInfo(true);
    me.setSSLEnabled(false);

    try {
      registerClient(me);

      String protocol = "TCP";
      String profile = "QOS_LOW_LATENCY";

      Qos.QosPrioritySessionDeleteRequest.Builder builder = me.createDefaultQosPrioritySessionDeleteRequest(context);
      builder.setProfile(Qos.QosSessionProfile.valueOf(profile));
      builder.setSessionId("this-is-a-bad-session-id");
      Qos.QosPrioritySessionDeleteRequest qosPrioritySessionDeleteRequest = builder.build();

      Qos.QosPrioritySessionDeleteReply qosPrioritySessionDeleteReply;

      if (useHostOverride) {
        //! [qosprioritysessiondeletefutureoverrideexample]
        qosPrioritySessionDeleteReply = me.qosPrioritySessionDelete(qosPrioritySessionDeleteRequest, hostOverride, portOverride, GRPC_TIMEOUT_MS);
        //! [qosprioritysessiondeletefutureoverrideexample]
      } else {
        //! [qosprioritysessiondeletefutureexample]
        qosPrioritySessionDeleteReply = me.qosPrioritySessionDelete(qosPrioritySessionDeleteRequest, GRPC_TIMEOUT_MS);
        //! [qosprioritysessiondeletefutureexample]
      }
      Log.i(TAG, "qosPrioritySessionDeleteReply.getStatus()="+ qosPrioritySessionDeleteReply.getStatus());

      assert (qosPrioritySessionDeleteReply != null);
      assertEquals(0, qosPrioritySessionDeleteReply.getVer());
      assertEquals("QDEL_NOT_FOUND", qosPrioritySessionDeleteReply.getStatus().name());

    } catch (ExecutionException ee) {
      Log.e(TAG, Log.getStackTraceString(ee));
      assertFalse("QosPrioritySessionDelete: ExecutionExecution!", true);
    } catch (StatusRuntimeException sre) {
      Log.e(TAG, Log.getStackTraceString(sre));
      assertFalse("QosPrioritySessionDelete: StatusRuntimeException!", true);
    } catch (InterruptedException ie) {
      Log.e(TAG, Log.getStackTraceString(ie));
      assertFalse("QosPrioritySessionDelete: InterruptedException!", true);
    }
  }
}

