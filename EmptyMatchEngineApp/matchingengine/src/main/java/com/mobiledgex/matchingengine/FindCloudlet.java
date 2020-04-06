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

import com.google.common.base.Stopwatch;
import com.mobiledgex.matchingengine.performancemetrics.NetTest;
import com.mobiledgex.matchingengine.performancemetrics.Site;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;

import distributed_match_engine.AppClient;
import distributed_match_engine.AppClient.FindCloudletRequest;
import distributed_match_engine.Appcommon;
import distributed_match_engine.MatchEngineApiGrpc;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

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

    private AppClient.FindCloudletReply.Builder createFindCloudletReplyFromAppInstance(AppClient.FindCloudletReply findCloudletReply, AppClient.Appinstance appinstance) {
        return AppClient.FindCloudletReply.newBuilder()
                .setVer(findCloudletReply.getVer())
                .setStatus(findCloudletReply.getStatus())
                .setFqdn(appinstance.getFqdn())
                .addAllPorts(appinstance.getPortsList())
                .addAllTags(findCloudletReply.getTagsList());
    }

    // If UDP, then ICMP must respond. TODO: Allow UDP "response"?
    private List<Site> insertAppInstances(NetTest netTest, Network network, AppClient.AppInstListReply appInstListReply) {
        int numSamples = Site.DEFAULT_NUM_SAMPLES;
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

                    Site site = null;
                    switch (appPort.getProto()) {
                        case L_PROTO_TCP: {
                            if (appPort.getPathPrefix() == null || appPort.getPathPrefix().isEmpty()) {
                                int port = appPort.getPublicPort();
                                String host = appPort.getFqdnPrefix() + appInstance.getFqdn();
                                site = new Site(network, NetTest.TestType.CONNECT, numSamples, host, port);
                            } else {
                                int port = appPort.getPublicPort();
                                String l7path = appPort.getFqdnPrefix() + appInstance.getFqdn() + ":" + port + appPort.getPathPrefix();
                                site = new Site(network, NetTest.TestType.CONNECT, numSamples, l7path);
                            }
                            break;
                        }
                        case L_PROTO_UDP: {
                            int port = appPort.getPublicPort();
                            String host = appPort.getFqdnPrefix() + appInstance.getFqdn();
                            site = new Site(network, NetTest.TestType.PING, numSamples, host, port);
                            break;
                        }
                        case L_PROTO_HTTP: {
                            int port = appPort.getPublicPort();
                            String l7path = appPort.getFqdnPrefix() + appInstance.getFqdn() + ":" + port + appPort.getPathPrefix();
                            site = new Site(network, NetTest.TestType.CONNECT, numSamples, l7path);
                            break;
                        }
                        default:
                            Log.e(TAG, "Unknown protocol: " + appPort.getProto() + "Cannot get statistics for site: " + appPort.getFqdnPrefix() + appInstance.getFqdn());
                            break;
                    }
                    if (site != null) {
                        site.setAppinstance(appInstance);
                        netTest.sites.add(site);
                    }
                }
            }
        }

        return netTest.sites;
    }

    private void rankSites(NetTest netTest, boolean threaded, long timeout, Stopwatch stopwatch) {
        if (!threaded) {
            List<Site> sites = netTest.sites;
            for (Site s : sites) {
                for (int n = 0; n < netTest.testRounds; n++) {
                    netTest.testSite(s);
                }
            }
        } else {
            // Threaded version, which might finish faster:
            ExecutorService executorService = null;
            try {
                int np = Runtime.getRuntime().availableProcessors() - 2;
                executorService = (np > 1) ? Executors.newFixedThreadPool(np) : mMatchingEngine.threadpool;

                netTest.setExecutorService(executorService);
                netTest.testSitesOnExecutor(timeout - stopwatch.elapsed(TimeUnit.MILLISECONDS));
            } catch (Exception e) {
                Log.e(TAG, "Excecution issue: " + e.getStackTrace());
            } finally {
                netTest.setExecutorService(null);
                if (executorService != null && !executorService.isShutdown()) {
                    executorService.shutdown();
                }
            }
        }
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
            if (fcreply != null) {
                mMatchingEngine.setFindCloudletResponse(fcreply);
            }

            // Check timeout, fallback:
            if (fcreply != null && stopwatch.elapsed(TimeUnit.MILLISECONDS) >= timeout) {
                return fcreply;
            }
            // No result, and Timeout:
            else if (stopwatch.elapsed(TimeUnit.MILLISECONDS) >= timeout) {
                throw new StatusRuntimeException(Status.DEADLINE_EXCEEDED);
            }

            // Have more time for AppInstList:
            // GetAppInstList, using the same FindCloudlet Request values.
            byte[] dummy = new byte[2048];
            AppClient.Tag dummyTag = AppClient.Tag.newBuilder().setType("Buffer").setData(new String(dummy)).build();

            AppClient.AppInstListRequest appInstListRequest = GetAppInstList.createFromFindCloudletRequest(mRequest)
                    // Do non-trivial transfer, stuffing Tag to do so.
                    .setCarrierName(mRequest.getCarrierName() == null ?
                            mMatchingEngine.getLastRegisterClientRequest().getCarrierName() :
                            mRequest.getCarrierName())
                    .addTags(dummyTag)
                    .build();

            AppClient.AppInstListReply appInstListReply = stub.withDeadlineAfter(remainder, TimeUnit.MILLISECONDS)
                .getAppInstList(appInstListRequest);

            // Transient state handling, just return what we had before, if it fails, a new FindCloudlet is needed anyway:
            if (appInstListReply == null || appInstListReply.getStatus() != AppClient.AppInstListReply.AIStatus.AI_SUCCESS) {
                return fcreply;
            }

            NetTest netTest = mMatchingEngine.getNetTest();
            insertAppInstances(netTest, network, appInstListReply);
            rankSites(netTest, mMatchingEngine.isThreadedPerformanceTest(), timeout, stopwatch);

            // Using default comparator for selecting the current best.
            Site bestSite = netTest.sortSites().get(0);

            AppClient.FindCloudletReply bestFindCloudletReply = createFindCloudletReplyFromAppInstance(fcreply, bestSite.appInstance)
                    .build();
            fcreply = bestFindCloudletReply;

        } finally {
            if (channel != null) {
                channel.shutdown();
                channel.awaitTermination(timeout - stopwatch.elapsed(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS);
            }
            if (nm != null) {
                nm.resetNetworkToDefault();
            }
        }

        mMatchingEngine.setFindCloudletResponse(fcreply);
        return fcreply;
    }
}
