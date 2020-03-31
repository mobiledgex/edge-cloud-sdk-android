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

package com.mobiledgex.matchingengine.performancemetrics;

import android.net.Network;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.mobiledgex.matchingengine.MobiledgeXSSLSocketFactory;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

import javax.net.SocketFactory;


public class NetTest
{
    public static final String TAG = "NetTest";
    public int numSamples = 5;

    public enum TestType
    {
        PING,
        CONNECT,
    }

    class Stopwatch {
        long elapsed;
        long startts;
        long endts;

        long reset() {
            return elapsed = 0;
        }
        long start() {
            startts = System.currentTimeMillis();
            return startts;
        }

        long stop() {
            endts = System.currentTimeMillis();
            elapsed = endts - startts;
            return elapsed;
        }

        long elapsed() {
            return elapsed;
        }

    }
    private Stopwatch stopWatch;

    public boolean runTest;

    private Thread pingThread;
    public int PingIntervalMS = 5000;
    public int TestTimeoutMS = 5000;
    public int ConnectTimeoutMS = 5000;

    /**
     * Synchronized List of Sites.
     */
    public List<Site> sites;

    /**
     * Simple default comparator for Site.
     * @return
     */
    public Comparator<Site> getDefaultComparator() {
        if (defaultComparator == null) {
            defaultComparator = new Comparator<Site>() {
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
                    else return 0;
                }
            };
        }
        return defaultComparator;
    }

    public Comparator<Site> defaultComparator;

    public NetTest()
    {
        stopWatch = new Stopwatch();
        sites = Collections.synchronizedList(new ArrayList<Site>());
        defaultComparator = getDefaultComparator();
    }

    private OkHttpClient getHttpClientOnNetwork(Network sourceNetwork) {
        OkHttpClient httpClient;
        MobiledgeXSSLSocketFactory mobiledgexSSLSocketFactory = (MobiledgeXSSLSocketFactory)MobiledgeXSSLSocketFactory.getDefault(sourceNetwork);

        // TODO: GetConnection to connect from a particular network interface endpoint
        httpClient = new OkHttpClient();
        httpClient.setConnectTimeout(ConnectTimeoutMS, TimeUnit.MILLISECONDS);
        // Read write Timeouts are on defaults.

        httpClient.setSslSocketFactory(mobiledgexSSLSocketFactory);
        httpClient.setSocketFactory(sourceNetwork.getSocketFactory());
        return httpClient;
    }

    // Create a client and connect/disconnect on a raw TCP server port from a device network Interface.
    // Not quite ping ICMP.
    public long ConnectAndDisconnectHostAndPort(Site site)
    {
        Network sourceNetwork = site.network;
        SocketFactory sf = sourceNetwork.getSocketFactory();
        long elapsed = 0;
        stopWatch.reset();

        Socket s = null;
        try {
            stopWatch.start();
            s = sf.createSocket(site.host, site.port);
            elapsed = stopWatch.stop();
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

    // Create a client and connect/disconnect from a device network Interface to a particular test
    // site.
    public long ConnectAndDisconnect(Site site)
    {
        Response result;

        try {
            stopWatch.reset();

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
            stopWatch.start();
            result = httpClient.newCall(request).execute();
            long elapsed = stopWatch.stop();

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

    // Basic ICMP ping. Does not set source network interface, it just pings to see if it is reachable along current default route.
    public long Ping(Site site)
    {
        InetAddress inetAddress = null;
        try {
            inetAddress = InetAddress.getByName(site.host);
        } catch (UnknownHostException uhe) {
            return -1;
        }

        long elapsedMS = 0;

        try {
            stopWatch.reset();
            stopWatch.start();
            // Ping:
            if (inetAddress.isReachable(TestTimeoutMS)) {
                elapsedMS = stopWatch.stop();
            }
            else {
                elapsedMS = -1;
            }
        } catch (IOException ioe) {
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
            pingThread = new Thread() {
                @Override
                public void run() {
                    // Exits on runTest == false;
                    RunNetTest();
                }
            };
            pingThread.start();
        }
        else
        {
            try {
                pingThread.join(PingIntervalMS);
            } catch (InterruptedException ie) {
                // Nothing to do.
            } finally {
                pingThread = null;
            }
        }
        return runTest;
    }

    /**
     * Sort sites for gathered performance stats based on default Comparator.
     * @return
     */
    public List<Site> sortSites() {
        return sortSites(defaultComparator);
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

    // Basic utility function to connect and disconnect from any TCP port.
    public void RunNetTest()
    {
        while (runTest) {
            double elapsed = -1d;
            try {
                synchronized (this) {
                    for (Site site : sites) {
                        switch (site.testType) {
                            case CONNECT:
                                if (site.L7Path == null) // Simple host and port.
                                {
                                    elapsed = ConnectAndDisconnectHostAndPort(site);
                                    Log.d(TAG, "site host: " + site.host + ", port: " + site.port + ", round-trip: " + elapsed + ", average:  " + site.average + ", stddev: " + site.stddev + ", from net interface id: " + site.network.toString());
                                } else // Use L7 Path.
                                {
                                    elapsed = ConnectAndDisconnect(site);
                                    Log.d(TAG, "site L7Path: " + site.L7Path + ", round-trip: " + elapsed + ", average:  " + site.average + ", stddev: " + site.stddev + ", from net interface id: " + site.network.toString());

                                }
                                break;
                            case PING: {
                                elapsed = Ping(site);
                                Log.d(TAG, "site host: " + site.host + ", port: " + site.port + ", round-trip: " + elapsed + ", average:  " + site.average + ", stddev: " + site.stddev);
                            }
                            break;
                        }
                        site.lastPingMs = elapsed;
                        if (elapsed >= 0) {
                            site.addSample(elapsed);
                            site.recalculateStats();
                        }

                    }
                    this.notifyAll(); // Notify all netTests that's waiting() a test run cycle is done. Those waiting can sort the Collection.
                    // Must run inside a thread:
                    //Thread.sleep(PingIntervalMS);
                }
            }
            catch (Exception ie) {
                // Nothing.
                Log.e(TAG, "Exception during test");
            }
        }
    }

}

