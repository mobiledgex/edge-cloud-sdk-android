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

import android.net.Network;
import android.os.Build;
import android.util.Log;

import java.util.HashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import distributed_match_engine.AppClient;
import distributed_match_engine.MatchEngineApiGrpc;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;

public class RegisterClient implements Callable {
    public static final String TAG = "RegisterClient";

    private MatchingEngine mMatchingEngine;
    private AppClient.RegisterClientRequest mRequest;
    private String mHost = null;
    private int mPort = 0;
    private long mTimeoutInMilliseconds = -1;

    RegisterClient(MatchingEngine matchingEngine) {
        mMatchingEngine = matchingEngine;
    }

    public boolean setRequest(AppClient.RegisterClientRequest request, String host, int port, long timeoutInMilliseconds) {
        if (request == null) {
            throw new IllegalArgumentException("Request object must not be null.");
        } else if (!MatchingEngine.isMatchingEngineLocationAllowed()) {
            Log.e(TAG, "MatchingEngine location is disabled.");
            mRequest = null;
            return false;
        }

        if (host == null || host.equals("")) {
            return false;
        }
        mHost = host;
        mPort = port;
        mRequest = request;

        if (timeoutInMilliseconds <= 0) {
            throw new IllegalArgumentException("RegisterClient() timeout must be positive.");
        }
        mTimeoutInMilliseconds = timeoutInMilliseconds;
        return true;
    }

    private AppClient.RegisterClientRequest.Builder appendDeviceDetails(AppClient.RegisterClientRequest.Builder builder) {
        HashMap<String, String> map = mMatchingEngine.getDeviceInfo();
        builder.putAllTags(map);
        return builder;
    }

    @Override
    public AppClient.RegisterClientReply call() throws MissingRequestException, StatusRuntimeException, InterruptedException, ExecutionException {
        if (mRequest == null) {
            throw new MissingRequestException("Usage error: RegisterClient() does not have a request object to make call!");
        }

        AppClient.RegisterClientReply reply;
        ManagedChannel channel = null;
        NetworkManager nm;
        Network network;
        try {
            nm = mMatchingEngine.getNetworkManager();
            network = nm.getCellularNetworkOrWifiBlocking(false, mMatchingEngine.getMccMnc(mMatchingEngine.mContext));

            channel = mMatchingEngine.channelPicker(mHost, mPort, network);
            MatchEngineApiGrpc.MatchEngineApiBlockingStub stub = MatchEngineApiGrpc.newBlockingStub(channel);

            AppClient.RegisterClientRequest.Builder builder = AppClient.RegisterClientRequest.newBuilder(mRequest);
            appendDeviceDetails(builder)
                    .build();

            reply = stub.withDeadlineAfter(mTimeoutInMilliseconds, TimeUnit.MILLISECONDS)
                    .registerClient(mRequest);
        } catch (Exception e) {
            Log.e(TAG, "Exception during RegisterClient. DME Server used: " + mHost + ", carrierName: " + mRequest.getCarrierName() + ", appName: " + mRequest.getAppName() + ", appVersion: " + mRequest.getAppVers() + ", organizationName: " + mRequest.getOrgName() + ", Message: " + e.getMessage());
            if (e.getMessage() != null && e.getMessage().contains("NOT_FOUND")) {
                Log.e(TAG, "Please check that the appName, appVersion, and orgName correspond to a valid app definition on MobiledgeX.");
            }
            throw e;
        } finally {
            if (channel != null) {
                channel.shutdown();
                channel.awaitTermination(mTimeoutInMilliseconds, TimeUnit.MILLISECONDS);
            }
        }

        int ver;
        if (reply != null) {
            ver = reply.getVer();
            Log.d(TAG, "Version of Match_Engine_Status: " + ver);
        }

        mMatchingEngine.setSessionCookie(reply.getSessionCookie());
        mMatchingEngine.setTokenServerURI(reply.getTokenServerUri());

        mMatchingEngine.setLastRegisterClientRequest(mRequest);
        mMatchingEngine.setMatchEngineStatus(reply);

        mRequest = null;
        mHost = null;
        mPort = 0;

        return reply;
    }
}
