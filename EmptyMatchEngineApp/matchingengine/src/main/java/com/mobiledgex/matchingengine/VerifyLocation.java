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
import android.util.Log;

import com.squareup.okhttp.Headers;
import com.squareup.okhttp.HttpUrl;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import distributed_match_engine.AppClient;
import distributed_match_engine.AppClient.VerifyLocationRequest;
import distributed_match_engine.AppClient.VerifyLocationReply;
import distributed_match_engine.MatchEngineApiGrpc;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;

public class VerifyLocation implements Callable {
    public static final String TAG = "VerifyLocation";

    private MatchingEngine mMatchingEngine;
    private VerifyLocationRequest mRequest; // Singleton.
    private String mHost;
    private int mPort;
    private long mTimeoutInMilliseconds = -1;

    VerifyLocation(MatchingEngine matchingEngine) {
        mMatchingEngine = matchingEngine;
    }

    public boolean setRequest(VerifyLocationRequest request,
                              String host, int port, long timeoutInMilliseconds) {
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
            throw new IllegalArgumentException("VerifyLocation timeout must be positive.");
        }
        mTimeoutInMilliseconds = timeoutInMilliseconds;
        return true;
    }

    private String getToken() throws IOException {
        String token;

        OkHttpClient httpClient = new OkHttpClient();
        httpClient.setFollowSslRedirects(false);
        httpClient.setFollowRedirects(false);

        Response response = null;
        try {
            Request request = new Request.Builder()
                    .url(mMatchingEngine.getTokenServerURI())
                    .build();

            response = httpClient.newCall(request).execute();
            if (!response.isRedirect()) {
                throw new IllegalStateException("Expected a redirect!");
            } else {
                Headers headers = response.headers();
                String locationHeaderUrl = headers.get("Location");
                if (locationHeaderUrl == null) {
                    throw new IllegalStateException("Required Location Header Missing.");
                }
                HttpUrl url = HttpUrl.parse(locationHeaderUrl);
                token = url.queryParameter("dt-id");
                if (token == null) {
                    throw new IllegalStateException("Required Token ID Missing");
                }
            }
        } finally {
            if (response != null) {
                response.body().close();
            }
        }

        return token;
    }

    private VerifyLocationRequest addTokenToRequest(String token) {
        VerifyLocationRequest tokenizedRequest = AppClient.VerifyLocationRequest.newBuilder()
                .setVer(mRequest.getVer())
                .setSessionCookie(mRequest.getSessionCookie())
                .setCarrierName(mRequest.getCarrierName())
                .setGpsLocation(mRequest.getGpsLocation())
                .setVerifyLocToken(token)
                .build();
        return tokenizedRequest;
    }

    @Override
    public VerifyLocationReply call()
            throws MissingRequestException, StatusRuntimeException,
                   IOException, InterruptedException, ExecutionException {
        if (mRequest == null) {
            throw new MissingRequestException("Usage error: VerifyLocation does not have a request object to make location verification call!");
        }
        VerifyLocationRequest grpcRequest;

        // Make One time use of HTTP Request to Token Server:
        String token = getToken(); // This token is short lived.
        grpcRequest = addTokenToRequest(token);

        VerifyLocationReply reply;
        ManagedChannel channel = null;
        try {
            // Make One time use of HTTP Request to Token Server:
            NetworkManager nm = mMatchingEngine.getNetworkManager();
            Network network = nm.getCellularNetworkOrWifiBlocking(false, mMatchingEngine.getMccMnc(mMatchingEngine.mContext));

            channel = mMatchingEngine.channelPicker(mHost, mPort, network);
            MatchEngineApiGrpc.MatchEngineApiBlockingStub stub = MatchEngineApiGrpc.newBlockingStub(channel);

            reply = stub.withDeadlineAfter(mTimeoutInMilliseconds, TimeUnit.MILLISECONDS)
                    .verifyLocation(grpcRequest);
        } catch (Exception e) {
            Log.e(TAG, "Exception during VerifyLocation: " + e.getMessage());
            throw e;
        } finally {
            if (channel != null) {
                channel.shutdown();
                channel.awaitTermination(mTimeoutInMilliseconds, TimeUnit.MILLISECONDS);
            }
        }
        mRequest = null;

        // FIXME: Reply TBD.
        int ver = -1;
        if (reply != null) {
            ver = reply.getVer();
            Log.d(TAG, "Version of VerifyLocationReply: " + ver);
        }

        mMatchingEngine.setTokenServerToken(token);
        mMatchingEngine.setVerifyLocationReply(reply);
        return reply;
    }
}
