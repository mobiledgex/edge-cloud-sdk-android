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
import com.mobiledgex.mel.MelMessaging;

import java.net.InetAddress;
import java.net.UnknownHostException;
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
    private FindCloudletRequest mRequest;
    private String mHost;
    private int mPort;
    private long mTimeoutInMilliseconds = -1;
    private MatchingEngine.FindCloudletMode mMode;

    public FindCloudlet(MatchingEngine matchingEngine) {
        mMatchingEngine = matchingEngine;
    }

    public boolean setRequest(FindCloudletRequest request, String host, int port, long timeoutInMilliseconds, MatchingEngine.FindCloudletMode mode) {
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
        mMode = mode;

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
    private void insertAppInstances(NetTest netTest, Network network, AppClient.AppInstListReply appInstListReply) {
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
                        netTest.addSite(site);
                    }
                }
            }
        }

        return;
    }

    private void rankSites(NetTest netTest, boolean threaded, long timeout, Stopwatch stopwatch) {
        if (!threaded) {
            netTest.testSites(timeout - stopwatch.elapsed((TimeUnit.MILLISECONDS)));
        } else {
            // Threaded version, which might finish faster:
            ExecutorService executorService = null;
            try {
                int np = Runtime.getRuntime().availableProcessors() - 2;
                executorService = (np > 1) ? Executors.newFixedThreadPool(np) : mMatchingEngine.threadpool;

                netTest.setExecutorService(executorService);
                netTest.testSitesOnExecutor(timeout - stopwatch.elapsed(TimeUnit.MILLISECONDS));
            } catch (Exception e) {
                // Allow continuation.
                Log.e(TAG, "Threaded Excecution issue testing site performance: " + "Cause: " + e.getCause() + "Stack: " + e.getStackTrace());
            } finally {
                netTest.setExecutorService(null);
                if (executorService != null && !executorService.isShutdown()) {
                    executorService.shutdown();
                }
            }
        }
    }

    private AppClient.FindCloudletReply FindCloudletWithMode()
        throws InterruptedException, ExecutionException{

        AppClient.FindCloudletReply fcreply;
        ManagedChannel channel = null;
        NetworkManager nm = null;

        Stopwatch stopwatch = Stopwatch.createUnstarted();
        long timeout = mTimeoutInMilliseconds;
        long remainder = timeout;

        try {
            nm = mMatchingEngine.getNetworkManager();
            Network network = nm.getCellularNetworkOrWifiBlocking(false, mMatchingEngine.getMccMnc(mMatchingEngine.mContext));

            channel = mMatchingEngine.channelPicker(mHost, mPort, network);
            MatchEngineApiGrpc.MatchEngineApiBlockingStub stub = MatchEngineApiGrpc.newBlockingStub(channel);

            stopwatch.start();
            fcreply = stub.withDeadlineAfter(timeout, TimeUnit.MILLISECONDS)
                .findCloudlet(mRequest);

            // Keep a copy.
            if (fcreply != null) {
                mMatchingEngine.setFindCloudletResponse(fcreply);
            }

            if (mMode == MatchingEngine.FindCloudletMode.PROXIMITY) {
                return fcreply;
            }

            // Check timeout, fallback:
            if (fcreply != null && stopwatch.elapsed(TimeUnit.MILLISECONDS) >= timeout) {
                return fcreply;
            }
            if (fcreply.getStatus() != AppClient.FindCloudletReply.FindStatus.FIND_FOUND) {
                return fcreply; // No AppInst was found. Just return.
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
            Site bestSite = netTest.bestSite();

            AppClient.FindCloudletReply bestFindCloudletReply = createFindCloudletReplyFromAppInstance(fcreply, bestSite.appInstance)
              .build();
            fcreply = bestFindCloudletReply;

        } finally {
            if (channel != null) {
                channel.shutdown();
                channel.awaitTermination(timeout - stopwatch.elapsed(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS);
            }
        }

        mMatchingEngine.setFindCloudletResponse(fcreply);
        return fcreply;
    }

    // Mel Mode, token or not, get the official FQDN:
    private AppClient.FindCloudletReply FindCloudletMelMode(final long remainderMs)
        throws ExecutionException, InterruptedException {

        AppClient.FindCloudletReply fcReply;
        ManagedChannel channel = null;
        NetworkManager nm;

        Stopwatch stopwatch = Stopwatch.createUnstarted();
        try {
            nm = mMatchingEngine.getNetworkManager();
            Network network = nm.getCellularNetworkOrWifiBlocking(false, mMatchingEngine.getMccMnc(mMatchingEngine.mContext));

            final AppClient.AppOfficialFqdnRequest appOfficialFqdnRequest = AppClient.AppOfficialFqdnRequest.newBuilder()
                .setSessionCookie(mRequest.getSessionCookie())
                .setGpsLocation(mRequest.getGpsLocation())
                .build();

            channel = mMatchingEngine.channelPicker(mHost, mPort, network);
            final MatchEngineApiGrpc.MatchEngineApiBlockingStub stub = MatchEngineApiGrpc.newBlockingStub(channel);

            AppClient.AppOfficialFqdnReply reply = stub.withDeadlineAfter(remainderMs, TimeUnit.MILLISECONDS)
                .getAppOfficialFqdn(appOfficialFqdnRequest);

            // Status Conversion:
            AppClient.FindCloudletReply.FindStatus fcStatus = reply.getStatus() == AppClient.AppOfficialFqdnReply.AOFStatus.AOF_SUCCESS ?
                AppClient.FindCloudletReply.FindStatus.FIND_FOUND : AppClient.FindCloudletReply.FindStatus.FIND_NOTFOUND;

            // Create a very basic FindCloudletReply from AppOfficialFqdn reply:
            fcReply = AppClient.FindCloudletReply.newBuilder()
                .setFqdn(reply.getAppOfficialFqdn()) // Straight copy.
                .setStatus(fcStatus)
                .addPorts(Appcommon.AppPort.newBuilder().build()) // Port is unknown here.
                .build();

            mMatchingEngine.setFindCloudletResponse(fcReply);
            mMatchingEngine.setAppOfficialFqdnReply(reply); // has client location token


            // Let MEL platform know the client location token:
            MelMessaging.sendSetToken(
                mMatchingEngine.mContext,
                reply.getClientToken(),
                mMatchingEngine.getLastRegisterClientRequest().getAppName());

        } finally {
            if (channel != null) {
                channel.shutdown();
                channel.awaitTermination(remainderMs - stopwatch.elapsed(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS);
            }
        }



      return fcReply;
    }

    @Override
    public AppClient.FindCloudletReply call()
            throws MissingRequestException, StatusRuntimeException, InterruptedException, ExecutionException {
        if (mRequest == null) {
            throw new MissingRequestException("Usage error: FindCloudlet does not have a request object to use MatchEngine!");
        }
        // Because we're rebuilding messages, check early:
        mMatchingEngine.ensureSessionCookie(mRequest.getSessionCookie());

        AppClient.FindCloudletReply fcReply;

        // Is Wifi Enabled, and has IP?
        Stopwatch stopwatch = Stopwatch.createStarted();
        long ip = mMatchingEngine.getWifiIp(mMatchingEngine.mContext);



        if (MelMessaging.isMelEnabled() && ip == 0) { // MEL is Cellular only. No WiFi.
            // MEL is enabled, alternate findCloudlet behavior:
            fcReply = FindCloudletMelMode(mTimeoutInMilliseconds);

            // Fall back to Proximity mode if Mel Mode DNS resolve fails for whatever reason:
            fcReply = handleMelFallback(fcReply, stopwatch);
        } else {
            fcReply = FindCloudletWithMode(); // Regular FindCloudlet.
        }

        mMatchingEngine.setFindCloudletResponse(fcReply);
        return fcReply;
    }

    private AppClient.FindCloudletReply handleMelFallback(AppClient.FindCloudletReply fcReply, Stopwatch stopwatch)
        throws InterruptedException, ExecutionException {

        String appOfficialFqdnHost = fcReply.getFqdn();
        // Handle NULL:
        try {
            if (appOfficialFqdnHost == null) {
              throw new UnknownHostException("Host is null!");
            }
        } catch (UnknownHostException uhe) {
            Log.w(TAG, "Public AppOfficialFqdn DNS resolve FAILURE for: " + appOfficialFqdnHost);
            fcReply = FindCloudletWithMode();
            return fcReply; // As-is.
        }

        // Handle MEL DNS Proxy, this does not have proper feedback of MEL errors or Server misconfiguration:
        boolean found = false;
        synchronized (appOfficialFqdnHost) {
            Stopwatch dnsStopwatch = Stopwatch.createStarted();
            while (stopwatch.elapsed(TimeUnit.MILLISECONDS) < mTimeoutInMilliseconds) {
                try {
                    InetAddress address = InetAddress.getByName(appOfficialFqdnHost);
                    Log.d(TAG, "Public AppOfficialFqdn DNS resolved : " + address.getHostAddress() + "elapsed time in ms: " + dnsStopwatch.elapsed(TimeUnit.MILLISECONDS));
                    found = true;
                    break;
                } catch (UnknownHostException uhe) {
                    Log.w(TAG, "Public AppOfficialFqdn DNS resolve FAILURE for: " + appOfficialFqdnHost);
                }
                appOfficialFqdnHost.wait(300);
            }
        }
        if (found && !appOfficialFqdnHost.isEmpty()) {
            return fcReply;
        } else {
            fcReply = FindCloudletWithMode();
            return fcReply;
        }
    }
}
