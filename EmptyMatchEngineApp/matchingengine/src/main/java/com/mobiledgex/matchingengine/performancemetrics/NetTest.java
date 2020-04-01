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


package com.mobiledgex.matchingengine.performancemetrics;

import android.net.Network;
import android.util.Log;

import com.google.common.base.Stopwatch;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.mobiledgex.matchingengine.MobiledgeXSSLSocketFactory;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

import javax.net.SocketFactory;


public class NetTest
{
    public static final String TAG = "NetTest";
    public int testRounds = 5;

    public enum TestType
    {
        PING,
        CONNECT,
    }

    public boolean runTest;

    private Thread testThread;
    public int TestIntervalMS = 100;
    public int TestTimeoutMS = 2000;

    /**
     * Synchronized List of Sites.
     */
    public List<Site> sites;

    /**
     * Simple default comparator for Site.
     * @return
     */
    public Comparator<Site> getDefaultSiteComparator() {
        return new Comparator<Site>() {
            @Override
            public int compare(Site o1, Site o2) {
                if (o1.average < o2.average) {
                    return -1;
                }
                if (o1.average > o2.average) {
                    return 1;
                }

                if (o1.stddev < o2.stddev) {
                    return -1;
                }
                if (o1.stddev > o2.stddev) {
                    return 1;
                }
                else {
                    return 0;
                }
            }
        };
    }

    public Comparator<Site> siteComparator;
    private ExecutorService mExecutorService;

    public NetTest()
    {
        sites = Collections.synchronizedList(new ArrayList<Site>());
        siteComparator = getDefaultSiteComparator();
    }

    /**
     * Set the executorService to use if using the async Future versions.
     * @param executorService
     * @return
     */
    public ExecutorService setExecutorService(ExecutorService executorService) {
        return this.mExecutorService = executorService;
    }

    private OkHttpClient getHttpClientOnNetwork(Network sourceNetwork) {
        OkHttpClient httpClient;
        MobiledgeXSSLSocketFactory mobiledgexSSLSocketFactory = (MobiledgeXSSLSocketFactory)MobiledgeXSSLSocketFactory.getDefault(sourceNetwork);

        // TODO: GetConnection to connect from a particular network interface endpoint
        httpClient = new OkHttpClient();
        httpClient.setConnectTimeout(TestTimeoutMS, TimeUnit.MILLISECONDS);
        // Read write Timeouts are on defaults.

        httpClient.setSslSocketFactory(mobiledgexSSLSocketFactory);
        httpClient.setSocketFactory(sourceNetwork.getSocketFactory());
        return httpClient;
    }

    /**
     * Create a client and connect/disconnect on a raw TCP server port from a device network Interface.
     * @param site
     * @return
     */
    public long ConnectAndDisconnectHostAndPort(Site site)
    {
        Network sourceNetwork = site.network;
        SocketFactory sf = sourceNetwork.getSocketFactory();
        long elapsed = 0;
        Stopwatch stopWatch = Stopwatch.createUnstarted();

        Socket s = null;
        try {
            stopWatch.start();
            s = sf.createSocket();
            SocketAddress socketAddress = new InetSocketAddress(site.host, site.port);
            s.connect(socketAddress, TestTimeoutMS);
            elapsed = stopWatch.stop().elapsed(TimeUnit.MILLISECONDS);
        }
        catch (IOException ioe) {
            elapsed = -1;
        } finally {
            try {
                if (s != null) {
                    s.close();
                }
            } catch (IOException ioe){
                // Done.
            }
        }

        return elapsed;
    }

    /**
     * Test and gather stats on site using TCP connection to host.
     * @param site
     * @return
     */
    public long ConnectAndDisconnect(Site site)
    {
        Response result;

        try {
            Stopwatch stopWatch;

            Request request = new Request.Builder()
                    .url(site.L7Path)
                    .get()
                    .build();

            OkHttpClient httpClient;
            if (site.network != null) {
                httpClient = getHttpClientOnNetwork(site.network);
            } else {
                httpClient = new OkHttpClient();
                httpClient.setConnectTimeout(TestTimeoutMS, TimeUnit.MILLISECONDS);
            }

            // The nature of this app specific GET API call is to expect some kind of
            // stateless empty body return also 200 OK.
            stopWatch = Stopwatch.createStarted();
            result = httpClient.newCall(request).execute();
            long elapsed = stopWatch.stop().elapsed(TimeUnit.MILLISECONDS);

            if (result.isSuccessful()) {
                return elapsed;
            }

        } catch (IOException ioe) {
            ioe.printStackTrace();
            return -1;
        }
        // Error, GET on L7 Path didn't return success.
        return -1;
    }

    /**
     * Basic ICMP ping. Does not set source network interface, it just pings to see if it is reachable along current default route.
     * @param site
     * @return
     */
    public long Ping(Site site)
    {
        InetAddress inetAddress;
        try {
            inetAddress = InetAddress.getByName(site.host);
        } catch (UnknownHostException uhe) {
            return -1;
        }

        long elapsedMS = 0;

        try {
            Stopwatch stopWatch;
            // Ping:
            stopWatch = Stopwatch.createStarted();
            // SE Linux audit fails if the pinging from a specific network interface.
            Log.e(TAG, "PING is for use if on a single active network only. Raw socket ping to internet from a particular NetworkInterface is not permitted.");
            boolean reachable = inetAddress.isReachable(TestTimeoutMS);
            if (reachable) {
                elapsedMS = stopWatch.stop().elapsed(TimeUnit.MILLISECONDS);
            } else {
                elapsedMS = -1;
            }
        } catch (IOException ioe) {
            Log.d(TAG, "IOException trying to ping: " + site.host + "Stack: " + ioe.getMessage());
            return -1;
        } catch (Exception e) {
            return -1;
        }

        return elapsedMS;
    }

    public boolean doTest(boolean enable)
    {
        if (runTest == true && enable == true)
        {
            return true;
        }

        runTest = enable;
        if (runTest)
        {
            testThread = new Thread() {
                @Override
                public void run() {
                    // Exits on runTest == false;
                    RunNetTest();
                }
            };
            testThread.start();
        }
        else
        {
            try {
                testThread.join(TestTimeoutMS);
            } catch (InterruptedException ie) {
                // Nothing to do.
            } finally {
                testThread = null;
            }
        }
        return runTest;
    }

    /**
     * Sort sites for gathered performance stats based on default Comparator.
     * @return
     */
    public List<Site> sortSites() {
        return sortSites(siteComparator);
    }

    /**
     * Sort sites for gathered performance stats based on comparator parameter.
     * @param comparator
     * @return
     */
    public List<Site> sortSites(Comparator<Site> comparator) {
        Collections.sort(sites, comparator);
        return sites;
    }

    public double testSite(Site site) {
        double elapsed = -1;
        String msg = null;
        switch (site.testType) {
            case CONNECT:
                if (site.L7Path == null) // Simple host and port.
                {
                    elapsed = ConnectAndDisconnectHostAndPort(site);
                    msg = "site host: " + site.host + ", port: " + site.port + ", round-trip: " + elapsed + ", average:  " + site.average + ", stddev: " + site.stddev + ", from net interface id: " + site.network.toString();
                } else {
                    elapsed = ConnectAndDisconnect(site);
                    msg = "site L7Path: " + site.L7Path + ", round-trip: " + elapsed + ", average:  " + site.average + ", stddev: " + site.stddev + ", from net interface id: " + site.network.toString();

                }
                break;
            case PING: {
                elapsed = Ping(site);
                msg = "site host: " + site.host + ", port: " + site.port + ", round-trip: " + elapsed + ", average:  " + site.average + ", stddev: " + site.stddev;
            }
            break;
        }
        site.lastPingMs = elapsed;
        if (elapsed >= 0) {
            site.addSample(elapsed);
            site.recalculateStats();
            Log.d(TAG, msg);
        }
        return elapsed;
    }

    /**
     * Round robin parallel test of sites added to NetTest over on executorService configured in NetTest.
     * @return
     */
    public void testSitesOnExecutor(long TimeoutMS) {
        Stopwatch testStopwatch = Stopwatch.createStarted();

        for (final Site s: sites) {
            if (TimeoutMS - testStopwatch.elapsed(TimeUnit.MILLISECONDS) < 0) {
                Log.d(TAG, "Timeout hit.");
                return;
            }

            // Create some CompletableFutures per round of sites:
            CompletableFuture<Double>[] cfArray = new CompletableFuture[testRounds];
            int idx = 0;
            for (int n = 0; n < testRounds; n++) {
                CompletableFuture<Double> future;
                if (mExecutorService == null) {
                     future = CompletableFuture.supplyAsync(new Supplier<Double>() {
                        @Override
                        public Double get() {
                            return testSite(s);
                        }
                    });
                } else {
                    future = CompletableFuture.supplyAsync(new Supplier<Double>() {
                        @Override
                        public Double get() {
                            return testSite(s);
                        }
                    }, mExecutorService);
                }
                cfArray[idx++] = future;
            }

            // Wait for all to complete:
            CompletableFuture.allOf(cfArray).join(); // Every test has TimeoutMS.
        }
    }

    // Basic utility function to connect and disconnect from any TCP port.
    public void RunNetTest()
    {
        while (runTest) {
            double elapsed = -1d;
            try {
                synchronized (sites) {
                    for (Site site : sites) {
                        testSite(site);
                    }
                    sites.notifyAll(); // Notify all netTests that's waiting() a test run cycle is done.
                    // Must run inside a thread:
                    Thread.sleep(TestIntervalMS);
                }
            }
            catch (Exception ie) {
                // Nothing.
                Log.e(TAG, "Exception during test");
            }
        }
    }

}

