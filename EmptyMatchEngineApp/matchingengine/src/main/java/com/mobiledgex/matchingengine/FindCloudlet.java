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

import com.google.common.base.Stopwatch;
import com.mobiledgex.matchingengine.performancemetrics.NetTest;
import com.mobiledgex.matchingengine.performancemetrics.Site;
import com.mobiledgex.mel.MelMessaging;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
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
    private long mMaximumLatencyMs = -1;
    private MatchingEngine.FindCloudletMode mMode;

    private boolean mDoLatencyMigration = false;

    public FindCloudlet(MatchingEngine matchingEngine) {
        mMatchingEngine = matchingEngine;
    }

    public boolean setRequest(FindCloudletRequest request, String host, int port, long timeoutInMilliseconds, MatchingEngine.FindCloudletMode mode, long maxLatencyInMilliseconds) {
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
        mMaximumLatencyMs = maxLatencyInMilliseconds;

        if (timeoutInMilliseconds <= 0) {
            throw new IllegalArgumentException("FindCloudlet timeout must be positive.");
        }
        mTimeoutInMilliseconds = timeoutInMilliseconds;
        return true;
    }

    private AppClient.FindCloudletReply.Builder createFindCloudletReplyFromBestSite(AppClient.AppInstListReply reply, Site bestSite) {
        if (bestSite != null) {
            AppClient.FindCloudletReply.Builder builder = AppClient.FindCloudletReply.newBuilder()
                    .setVer(reply.getVer())
                    .setStatus(AppClient.FindCloudletReply.FindStatus.FIND_FOUND)
                    .setFqdn(bestSite.appInstance.getFqdn())
                    .setCloudletLocation(bestSite.cloudlet_location)
                    .addAllPorts(bestSite.appInstance.getPortsList())
                    .setEdgeEventsCookie(bestSite.appInstance.getEdgeEventsCookie());
            if (reply != null && reply.getTagsMap() != null) {
                builder.putAllTags(reply.getTagsMap());
            }
            return builder;
        }
        return null;
    }

    private Appcommon.AppPort getAppInstancePort(AppClient.Appinstance appinstance, int internalPort) {
        Appcommon.AppPort appPort = null;
        for (Appcommon.AppPort aPort : appinstance.getPortsList()) {
            int end = aPort.getEndPort() == 0 ? aPort.getInternalPort() : aPort.getEndPort();
            int range = end - aPort.getInternalPort();
            boolean valid = internalPort - aPort.getInternalPort() <= range;
            if (valid) {
                appPort = aPort;
                break;
            }
        }
        return appPort;
    }

    private Appcommon.AppPort getOneAppPort(AppClient.Appinstance appInstance) {
        Appcommon.AppPort appPort = null;
        for (Appcommon.AppPort aPort : appInstance.getPortsList()) {
            if (aPort.getProto() == Appcommon.LProto.L_PROTO_UDP) {
                if (appPort == null) {
                    appPort = aPort;
                }
                continue;
            }
            if (aPort.getProto() == Appcommon.LProto.L_PROTO_TCP) {
                // Stop on first TCP.
                appPort = aPort;
                break;
            }

        }
        return appPort;
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
                    // Favor Connect of each appInst.
                    Appcommon.AppPort appPort;
                    int internalPort = mMatchingEngine.mEdgeEventsConfig == null ? 0 : mMatchingEngine.mEdgeEventsConfig.latencyInternalPort;
                    if (internalPort == 0) {
                        appPort = getOneAppPort(appInstance);
                    } else {
                        appPort = getAppInstancePort(appInstance, internalPort);
                    }

                    if (appPort == null) {
                        continue;
                    }

                    Site site = null;
                    switch (appPort.getProto()) {
                        case L_PROTO_TCP: {
                            int port = appPort.getPublicPort();
                            String host = appPort.getFqdnPrefix() + appInstance.getFqdn();
                            site = new Site(network, NetTest.TestType.CONNECT, numSamples, host, port);
                            break;
                        }
                        case L_PROTO_UDP: {
                            int port = appPort.getPublicPort();
                            String host = appPort.getFqdnPrefix() + appInstance.getFqdn();
                            site = new Site(network, NetTest.TestType.PING, numSamples, host, port);
                            break;
                        }
                        default:
                            Log.e(TAG, "Unknown protocol: " + appPort.getProto() + "Cannot get statistics for site: " + appPort.getFqdnPrefix() + appInstance.getFqdn());
                            break;
                    }
                    if (site != null) {
                        site.setAppinstance(appInstance);
                        site.setCloudletLocation(cloudletLocation.getGpsLocation());
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
                Log.e(TAG, "Threaded Execution issue testing site performance: " + "Cause: " + e.getCause() + "Stack: " + e.getStackTrace());
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

            if (mMode == MatchingEngine.FindCloudletMode.PROXIMITY) {
                fcreply = stub.withDeadlineAfter(timeout, TimeUnit.MILLISECONDS)
                    .findCloudlet(mRequest);
                if (fcreply != null) {
                    mMatchingEngine.setFindCloudletResponse(fcreply);
                }
                return fcreply;
            }

            // Remaining mode(s) is Performance:

            // GetAppInstList, using the same FindCloudlet Request values.
            AppClient.AppInstListRequest appInstListRequest = GetAppInstList.createFromFindCloudletRequest(mRequest)
                // Do non-trivial transfer, stuffing Tag to do so.
                .setCarrierName(mRequest.getCarrierName() == null ?
                  mMatchingEngine.getLastRegisterClientRequest().getCarrierName() :
                  mRequest.getCarrierName())
                .putTags("Buffer", new String(new byte[2048]))
                .build();

            AppClient.AppInstListReply appInstListReply = stub.withDeadlineAfter(remainder, TimeUnit.MILLISECONDS)
              .getAppInstList(appInstListRequest);

            // Transient state handling, just return what we had before, if it fails, a new FindCloudlet is needed anyway:
            if (appInstListReply == null || appInstListReply.getStatus() != AppClient.AppInstListReply.AIStatus.AI_SUCCESS) {
                return AppClient.FindCloudletReply.newBuilder()
                        .setStatus(AppClient.FindCloudletReply.FindStatus.FIND_NOTFOUND)
                        .build();
            }

            remainder = timeout - stopwatch.elapsed(TimeUnit.MILLISECONDS);
            if (remainder <= 0) {
                throw new StatusRuntimeException(Status.DEADLINE_EXCEEDED);
            }

            // Performance test the new list:
            NetTest netTest = mMatchingEngine.clearNetTest();

            insertAppInstances(netTest, network, appInstListReply);
            rankSites(netTest, mMatchingEngine.isThreadedPerformanceTest(), remainder, stopwatch);

            // Using default comparator for selecting the current best.
            Site bestSite = netTest.bestSite();

            AppClient.FindCloudletReply bestFindCloudletReply = createFindCloudletReplyFromBestSite(appInstListReply, bestSite)
                .build();
            fcreply = bestFindCloudletReply;

            // If average is better, allow migration.
            if (bestSite.hasSuccessfulTests() &&
                    bestSite.average < mMaximumLatencyMs &&
                    mMaximumLatencyMs >= 0) {
                mDoLatencyMigration = true;
            } else {
                Log.i(TAG, "Performance tests did not find a better cloudlet.");
                return fcreply;
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception during FindCloudlet: " + e.getMessage());
            throw e;
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
            // Do not fall back to WiFI for MEL mode.
            Network network = nm.getCellularNetworkBlocking(false);

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

            // Copy ports into MEL:
            List<Appcommon.AppPort> portList = new ArrayList<>();
            for (Appcommon.AppPort aPort : reply.getPortsList()) {
                Appcommon.AppPort port = Appcommon.AppPort.newBuilder(aPort)
                        .setPublicPort(aPort.getPublicPort() == 0 ? aPort.getInternalPort() : aPort.getPublicPort())
                        .build();
                portList.add(port);
            }
            // Compatibility with mel unaware clients, if empty, give app something to iterate on (to find no public port, use known port):
            if (portList.size() == 0) {
                portList.add(Appcommon.AppPort.newBuilder().build());
            }

            // Create a very basic FindCloudletReply from AppOfficialFqdn reply:
            fcReply = AppClient.FindCloudletReply.newBuilder()
                .setFqdn(reply.getAppOfficialFqdn()) // Straight copy.
                .setStatus(fcStatus)
                .addAllPorts(portList)
                .build();

            mMatchingEngine.setFindCloudletResponse(fcReply);
            mMatchingEngine.setAppOfficialFqdnReply(reply); // has client location token

            // Let MEL platform know the client location token:
            MelMessaging.sendSetToken(
                mMatchingEngine.mContext,
                reply.getClientToken(),
                mMatchingEngine.getLastRegisterClientRequest().getAppName());

        } catch (Exception e) {
            Log.e(TAG, "Exception during FindCloudlet: " + e.getMessage());
            throw e;
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

        Network network = mMatchingEngine.getNetworkManager()
                .getCellularNetworkOrWifiBlocking(
                        false,
                        mMatchingEngine.getMccMnc(mMatchingEngine.mContext));

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

        // Create message channel for DME EdgeEvents:
        if (!mDoLatencyMigration && mMode == MatchingEngine.FindCloudletMode.PERFORMANCE) {
            Log.d(TAG, "Cloudlet performance wasn't better. Not auto-migrating and returning nothing.");
            fcReply = null;
        } else if (fcReply != null && fcReply.getStatus() == AppClient.FindCloudletReply.FindStatus.FIND_FOUND) {
            try {
                // The engine is allowed to use a default config, should the config be null.
                mMatchingEngine.startEdgeEventsInternal(mHost, mPort, network, mMatchingEngine.mEdgeEventsConfig);
            } catch (Exception e) {
                // Non fatal, but print an error. No background events available.
                Log.e(TAG, "Configured EdgeEventsConfig background tasks cannot be started. Exception was: " + e.getMessage());
                e.printStackTrace();
                if (mMatchingEngine.getEdgeEventsBus() != null) {
                    mMatchingEngine.getEdgeEventsBus().post(EdgeEventsConnection.EdgeEventsError.invalidEdgeEventsSetup);
                }
            }
        }
        if (fcReply != null) {
            mMatchingEngine.setFindCloudletResponse(fcReply);
        }
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

        // We need the edge Events cookie, MEL or not.
        AppClient.FindCloudletReply normalModeFc;
        normalModeFc = FindCloudletWithMode();

        if (found && !appOfficialFqdnHost.isEmpty()) {
            fcReply = AppClient.FindCloudletReply.newBuilder(fcReply)
                    .setEdgeEventsCookie(
                            normalModeFc.getEdgeEventsCookie()
                    ).build();
            return fcReply;
        } else {
            return normalModeFc;
        }
    }
}
