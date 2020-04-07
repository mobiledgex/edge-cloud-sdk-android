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

import android.util.Log;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import distributed_match_engine.AppClient.GetLocationRequest;
import distributed_match_engine.AppClient.GetLocationReply;
import distributed_match_engine.MatchEngineApiGrpc;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;

public class GetLocation implements Callable {
    public static final String TAG = "GetLocation";

    private MatchingEngine mMatchingEngine;
    private GetLocationRequest mRequest;
    private String mHost;
    private int mPort;
    private long mTimeoutInMilliseconds = -1;

    GetLocation(MatchingEngine matchingEngine) {
        mMatchingEngine = matchingEngine;
    }

    public boolean setRequest(GetLocationRequest request, String host, int port,
                              long timeoutInMilliseconds) {
        if (request == null) {
            throw new IllegalArgumentException("Request object must not be null.");
        } else if (!mMatchingEngine.isMatchingEngineLocationAllowed()) {
            Log.e(TAG, "MatchingEngine location is disabled.");
            mRequest = null;
            return false;
        }

        if (host == null || host.equals("")) {
            return false;
        }

        mRequest = request;
        mHost = host;
        mPort = port;

        if (timeoutInMilliseconds <= 0) {
            throw new IllegalArgumentException("GetLocation() timeout must be positive.");
        }
        mTimeoutInMilliseconds = timeoutInMilliseconds;
        return true;
    }

    @Override
    public GetLocationReply call()
            throws MissingRequestException, StatusRuntimeException, InterruptedException, ExecutionException {
        if (mRequest == null) {
            throw new MissingRequestException("Usage error: GetLocation does not have a request object to make location verification call!");
        }

        GetLocationReply reply = null;
        ManagedChannel channel = null;
        NetworkManager nm = null;
        try {
            nm = mMatchingEngine.getNetworkManager();
            nm.switchToCellularInternetNetworkBlocking();

            channel = mMatchingEngine.channelPicker(mHost, mPort);
            MatchEngineApiGrpc.MatchEngineApiBlockingStub stub = MatchEngineApiGrpc.newBlockingStub(channel);

            reply = stub.withDeadlineAfter(mTimeoutInMilliseconds, TimeUnit.MILLISECONDS)
                    .getLocation(mRequest);
        } finally {
            if (channel != null) {
                channel.shutdown();
                channel.awaitTermination(mTimeoutInMilliseconds, TimeUnit.MILLISECONDS);
            }
            if (nm != null) {
                nm.resetNetworkToDefault();
            }
        }
        mRequest = null;

        int ver;
        if (reply != null) {
            ver = reply.getVer();
            Log.d(TAG, "Version of GetLocationReply: " + ver);
        }

        mMatchingEngine.setGetLocationReply(reply);
        return reply;
    }
}
