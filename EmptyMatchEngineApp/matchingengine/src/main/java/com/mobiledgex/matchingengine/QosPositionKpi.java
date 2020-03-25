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

import java.util.Iterator;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;

import distributed_match_engine.MatchEngineApiGrpc;
import distributed_match_engine.AppClient;
import distributed_match_engine.AppClient.QosPositionRequest;
import distributed_match_engine.AppClient.QosPositionKpiReply;

public class QosPositionKpi implements Callable {
    public static final String TAG = "QueryQosKpi";
    private MatchingEngine mMatchingEngine;
    private AppClient.QosPositionRequest mQosPositionKpiRequest;
    private String mHost;
    private int mPort;
    private long mTimeoutInMilliseconds;

    QosPositionKpi(MatchingEngine matchingEngine) {
        mMatchingEngine = matchingEngine;
    }

    boolean setRequest(QosPositionRequest qosPositionKpiRequest, String host, int port, long timeoutInMilliseconds) throws IllegalArgumentException {
        if (!mMatchingEngine.isMatchingEngineLocationAllowed()) {
            Log.e(TAG, "MobiledgeX location is disabled.");
            mQosPositionKpiRequest = null;
            return false;
        }

        if (qosPositionKpiRequest == null) {
            throw new IllegalArgumentException("Missing " + TAG + " Argument!");
        }

        if (qosPositionKpiRequest.getPositionsCount() == 0) {
            throw new IllegalArgumentException("PredictiveQos Request missing entries!");
        }

        if (host == null || host.equals("")) {
            throw new IllegalArgumentException("Host destination is required.");
        }
        if (port < 0) {
            throw new IllegalArgumentException("Port number must be positive.");
        }
        if (timeoutInMilliseconds <= 0) {
            throw new IllegalArgumentException("PredictiveQos Request timeout must be positive.");
        }
        mTimeoutInMilliseconds = timeoutInMilliseconds;

        mHost = host;
        mPort = port == 0 ? mMatchingEngine.getPort() : port; // Using engine default port.

        mQosPositionKpiRequest = qosPositionKpiRequest;

        return true;
    }

    @Override
    public ChannelIterator<QosPositionKpiReply> call() throws MissingRequestException, StatusRuntimeException, InterruptedException, ExecutionException {
        if (mQosPositionKpiRequest == null) {
            throw new MissingRequestException("Usage error: QueryQosKpi does not have a request object to use MatchingEngine!");
        }

        Iterator<QosPositionKpiReply> response;
        ManagedChannel channel;
        NetworkManager nm = null;
        try {
            nm = mMatchingEngine.getNetworkManager();
            Network network = nm.switchToCellularInternetNetworkBlocking();

            channel = mMatchingEngine.channelPicker(mHost, mPort, network);
            MatchEngineApiGrpc.MatchEngineApiBlockingStub stub = MatchEngineApiGrpc.newBlockingStub(channel);

            response = stub.withDeadlineAfter(mTimeoutInMilliseconds, TimeUnit.MILLISECONDS)
                    .getQosPositionKpi(mQosPositionKpiRequest);
        } finally {
            if (nm != null) {
                //nm.resetNetworkToDefault();
            }
        }

        return new ChannelIterator<>(channel, response);
    }
}
