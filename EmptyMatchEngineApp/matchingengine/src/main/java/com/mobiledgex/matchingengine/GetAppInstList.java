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

import android.net.Network;
import android.util.Log;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import distributed_match_engine.AppClient;
import distributed_match_engine.AppClient.AppInstListRequest;
import distributed_match_engine.AppClient.AppInstListReply;
import distributed_match_engine.MatchEngineApiGrpc;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;

public class GetAppInstList implements Callable {
    public static final String TAG = "GetAppInstList";

    private MatchingEngine mMatchingEngine;
    private AppInstListRequest mRequest;
    private String mHost;
    private int mPort;
    private long mTimeoutInMilliseconds = -1;

    GetAppInstList(MatchingEngine matchingEngine) {
        mMatchingEngine = matchingEngine;
    }

    public boolean setRequest(AppInstListRequest request,
                              String host,
                              int port, long timeoutInMilliseconds) {
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
        mHost = host;
        mPort = port;
        mRequest = request;

        if (timeoutInMilliseconds <= 0) {
            throw new IllegalArgumentException("GetAppInstList() timeout must be positive.");
        }
        mTimeoutInMilliseconds = timeoutInMilliseconds;
        return true;
    }

    static AppInstListRequest.Builder createFromFindCloudletRequest(AppClient.FindCloudletRequest findCloudletRequest) {
        AppClient.AppInstListRequest.Builder appInstListRequestBuilder = AppClient.AppInstListRequest.newBuilder();

        if (findCloudletRequest.getVer() > 0) {
            appInstListRequestBuilder.setVer(findCloudletRequest.getVer());
        }
        if (findCloudletRequest.getSessionCookie() != null) {
            appInstListRequestBuilder.setSessionCookie(findCloudletRequest.getSessionCookie());
        }

        if (findCloudletRequest.getCarrierName() != null) {
            appInstListRequestBuilder.setCarrierName(findCloudletRequest.getCarrierName());
        }
        if (findCloudletRequest.hasGpsLocation()) {
            appInstListRequestBuilder.setGpsLocation(findCloudletRequest.getGpsLocation());
        }
        if (findCloudletRequest.getCellId() > 0) {
            appInstListRequestBuilder.setCellId(findCloudletRequest.getCellId());
        }
        if (findCloudletRequest.getTagsCount() > 0) {
            appInstListRequestBuilder.putAllTags(findCloudletRequest.getTagsMap());
        }

        return appInstListRequestBuilder;
    }

    @Override
    public AppInstListReply call()
            throws MissingRequestException, StatusRuntimeException, InterruptedException, ExecutionException {
        if (mRequest == null) {
            throw new MissingRequestException("Usage error: GetAppInstList does not have a request object!");
        }

        AppInstListReply reply;
        ManagedChannel channel = null;
        NetworkManager nm = null;
        try {
            nm = mMatchingEngine.getNetworkManager();
            Network network = nm.getCellularNetworkOrWifiBlocking(false, mMatchingEngine.getMccMnc(mMatchingEngine.mContext));

            channel = mMatchingEngine.channelPicker(mHost, mPort, network);
            MatchEngineApiGrpc.MatchEngineApiBlockingStub stub = MatchEngineApiGrpc.newBlockingStub(channel);

            reply = stub.withDeadlineAfter(mTimeoutInMilliseconds, TimeUnit.MILLISECONDS)
                    .getAppInstList(mRequest);
        } finally {
            if (channel != null) {
                channel.shutdown();
                channel.awaitTermination(mTimeoutInMilliseconds, TimeUnit.MILLISECONDS);
            }
        }
        mRequest = null;

        int ver;
        if (reply != null) {
            ver = reply.getVer();
            Log.d(TAG, "Version of AppInstListReply: " + ver);
        }

        return reply;
    }
}
