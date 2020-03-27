/**
 * Copyright 2018-2020-2020 MobiledgeX, Inc. All rights and licenses reserved.
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

import com.google.common.base.Stopwatch;
import com.mobiledgex.matchingengine.performancemetrics.NetTest;
import com.mobiledgex.matchingengine.performancemetrics.Site;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;

import distributed_match_engine.AppClient;
import distributed_match_engine.AppClient.FindCloudletRequest;
import distributed_match_engine.Appcommon;
import distributed_match_engine.MatchEngineApiGrpc;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import static distributed_match_engine.Appcommon.LProto.L_PROTO_TCP;

public class FindCloudlet implements Callable {
    public static final String TAG = "FindCloudlet";

    private MatchingEngine mMatchingEngine;
    private FindCloudletRequest mRequest; // Singleton.
    private String mHost;
    private int mPort;
    private long mTimeoutInMilliseconds = -1;

    public FindCloudlet(MatchingEngine matchingEngine) {
        mMatchingEngine = matchingEngine;
    }

    public boolean setRequest(FindCloudletRequest request, String host, int port, long timeoutInMilliseconds) {
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
            throw new IllegalArgumentException("FindCloudlet timeout must be positive.");
        }
        mTimeoutInMilliseconds = timeoutInMilliseconds;
        return true;
    }

    private AppClient.FindCloudletReply.Builder createFindCloudletFromAppInstance(AppClient.FindCloudletReply findCloudletReply, AppClient.Appinstance appinstance) {
        return AppClient.FindCloudletReply.newBuilder()
                .setVer(findCloudletReply.getVer())
                .setStatus(findCloudletReply.getStatus())
                .setFqdn(appinstance.getFqdn())
                .addAllPorts(appinstance.getPortsList())
                .addAllTags(findCloudletReply.getTagsList());
    }

    @Override
    public AppClient.FindCloudletReply call()
            throws MissingRequestException, StatusRuntimeException, InterruptedException, ExecutionException {
        if (mRequest == null) {
            throw new MissingRequestException("Usage error: FindCloudlet does not have a request object to use MatchEngine!");
        }

        AppClient.FindCloudletReply fcreply;
        ManagedChannel channel = null;
        NetworkManager nm = null;

        Stopwatch stopwatch = Stopwatch.createUnstarted();
        long timeout = mTimeoutInMilliseconds;
        long remainder = timeout;

        try {
            nm = mMatchingEngine.getNetworkManager();
            Network network = nm.switchToCellularInternetNetworkBlocking();

            channel = mMatchingEngine.channelPicker(mHost, mPort);
            MatchEngineApiGrpc.MatchEngineApiBlockingStub stub = MatchEngineApiGrpc.newBlockingStub(channel);

            stopwatch.start();
            fcreply = stub.withDeadlineAfter(timeout, TimeUnit.MILLISECONDS)
                    .findCloudlet(mRequest);
            // Keep a copy.
            mMatchingEngine.setFindCloudletResponse(fcreply);

            // Check timeout:
            if (stopwatch.elapsed(TimeUnit.MILLISECONDS) >= timeout) {
                throw new StatusRuntimeException(Status.DEADLINE_EXCEEDED);
            }

            // GetAppInstList, using the same FindCloudlet Request values.
            byte[] dummy = new byte[2048];
            AppClient.Tag dummyTag = AppClient.Tag.newBuilder().setType("Buffer").setData(new String(dummy)).build();

            AppClient.AppInstListRequest appInstListRequest = GetAppInstList.createFromFindCloudletRequest(mRequest)
                    // Do non-trivial transfer, stuffing Tag to do so.
                    .addTags(dummyTag)
                    .build();

            AppClient.AppInstListReply appInstListReply = stub.withDeadlineAfter(remainder, TimeUnit.MILLISECONDS)
                .getAppInstList(appInstListRequest);

            // Check timeout:
            if (stopwatch.elapsed(TimeUnit.MILLISECONDS) >= timeout) {
                throw new StatusRuntimeException(Status.DEADLINE_EXCEEDED);
            }

            mMatchingEngine.getAppConnectionManager().getTCPMap(fcreply);

            // If UDP, then for test to work, server MUST respond with something to be measured.
            NetTest netTest = new NetTest(); // Just get stats and return, store for later use.

            // CloudletList --> Cloudlet --> AppInstances --> AppInstance --> Site (publicPort) --> Test.

            if (appInstListReply != null) {
                List<AppClient.CloudletLocation> cloudletsList = appInstListReply.getCloudletsList();
                for (AppClient.CloudletLocation cloudletLocation : cloudletsList) {

                    List<AppClient.Appinstance> appInstances = cloudletLocation.getAppinstancesList();
                    if (appInstances == null) {
                        continue;
                    }
                    for (AppClient.Appinstance appInstance : cloudletLocation.getAppinstancesList()) {
                        if (appInstance.getPortsCount() <= 0) {
                            continue; // Odd. Skip.
                        }
                        Appcommon.AppPort appPort = appInstance.getPorts(0);
                        // Type UDP (Ping) or TCP?
                        Appcommon.LProto proto = L_PROTO_TCP;
                        // TODO Assume TCP now:
                        mMatchingEngine.getAppConnectionManager().getTCPMap(fcreply);

                        Site site;
                        int numSamples = 5;
                        if (appPort.getPathPrefix() == null || appPort.getPathPrefix().isEmpty()) {
                            String host = appPort.getFqdnPrefix() + appInstance.getFqdn();
                            int port = appPort.getPublicPort();
                            site = new Site(network, NetTest.TestType.CONNECT, numSamples, host, port);
                            site.setAppInstance(appInstance);
                        } else {
                            String l7path = appPort.getFqdnPrefix() + appInstance.getFqdn() + appPort.getPathPrefix();
                            site = new Site(network, NetTest.TestType.CONNECT, numSamples, l7path);
                            site.setAppInstance(appInstance);
                        }
                        netTest.sites.add(site);
                    }
                    netTest.PingIntervalMS = 100;

                }
            }
            netTest.doTest(true);
            synchronized (netTest) {
                // Wait for netTest monitor notify() or notifyAll() if test run completed.
                try {
                    netTest.wait(timeout - stopwatch.elapsed(TimeUnit.MILLISECONDS));
                } catch (InterruptedException e) {
                } finally {
                    netTest.runTest = false;
                }
            }

            // Who's best (before being timing out/interrupted)? stream(), .min() is in Android 24 only. Simple average only:
            Site bestSite = netTest.sites.peek();
            for (Site s : netTest.sites) {
                if (s.average < bestSite.average) {
                    bestSite = s;
                }
            }
            // Construct a findCloudlet return;
            AppClient.FindCloudletReply bestFindCloudletReply = createFindCloudletFromAppInstance(fcreply, bestSite.appInstance)
                    .build();
            fcreply = bestFindCloudletReply;
        } finally {
            if (channel != null) {
                channel.shutdown();
                channel.awaitTermination(mTimeoutInMilliseconds, TimeUnit.MILLISECONDS);
            }
            if (nm != null) {
                nm.resetNetworkToDefault();
            }
        }

        mMatchingEngine.setFindCloudletResponse(fcreply);
        return fcreply;
    }
}
