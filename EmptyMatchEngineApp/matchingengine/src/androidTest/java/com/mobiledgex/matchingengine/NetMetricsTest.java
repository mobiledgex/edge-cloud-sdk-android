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
import android.content.Context;
import android.net.Network;
import android.os.Looper;
import androidx.test.platform.app.InstrumentationRegistry;
import android.util.Log;

import com.mobiledgex.matchingengine.performancemetrics.NetTest;
import com.mobiledgex.matchingengine.performancemetrics.Site;

import org.junit.Test;
import org.junit.Before;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class NetMetricsTest {
    final static String TAG = "NetMetricsTest";
    final long TimeoutMS = 10000;

    @Before
    public void LooperEnsure() {
        // SubscriberManager needs a thread. Start one:
        if (Looper.myLooper()==null)
            Looper.prepare();
    }

    @Test
    public void testMetrics1() {
        NetTest netTest = new NetTest();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MatchingEngine me = new MatchingEngine(context);

        Network network = me.getNetworkManager().getActiveNetwork();

        // These sites can go stale! Ensure a the site data structure has correct insertion and test properties:
        Site site1 = new Site(network, NetTest.TestType.CONNECT, Site.DEFAULT_NUM_SAMPLES, "mobiledgexsdkdemo-tcp.sdkdemo-app-cluster.sunnydale-main.gddt.mobiledgex.net", 8008);
        Site site2 = new Site(network, NetTest.TestType.CONNECT, Site.DEFAULT_NUM_SAMPLES, "mobiledgexsdkdemo-tcp.sdkdemo-app-cluster.fairview-main.gddt.mobiledgex.net", 8008);
        Site site3 = new Site(network, NetTest.TestType.CONNECT, Site.DEFAULT_NUM_SAMPLES, "mobiledgexsdkdemo-tcp.sdkdemo-app-cluster.beacon-main.gddt.mobiledgex.net", 8008);

        Site dup = new Site(network, NetTest.TestType.CONNECT, Site.DEFAULT_NUM_SAMPLES, "mobiledgexsdkdemo-tcp.sdkdemo-app-cluster.sunnydale-main.gddt.mobiledgex.net", 8008);


        netTest.addSite(site1);
        netTest.addSite(site2);
        netTest.addSite(site3);
        netTest.addSite(dup);

        List<Site> sites = netTest.sortedSiteList();
        assertEquals("Site list size wrong!", 3, sites.size());


        Site bestSite;
        // Emulator WiFi unit test only! (and still unstable)
        // The expectation is on a cellular network, net test needs to be paired with a dummy DME call
        // with some 2K of data to prime the network to send data.
        netTest.testSites(TimeoutMS);
        bestSite = netTest.bestSite();
        Log.d(TAG, "Host expected: " + site2.host + ", avg: " + site2.average + ", got: " + "Got best site: " + bestSite.host + ", avg: " + bestSite.average);
        if (!site2.host.equals(bestSite.host)) {
            assertTrue("Serial winner Not within 10% margin: ", Math.abs(bestSite.average-site2.average)/site2.average < 0.1d);
        } else {
            assertEquals("Test expectation is fairview wins: ", site2.host, netTest.bestSite().host);
        }

        netTest.testSitesOnExecutor(TimeoutMS);
        bestSite = netTest.bestSite();
        Log.d(TAG, "Host expected: " + site2.host + ", avg: " + site2.average + ", got: " + "Got best site: " + bestSite.host + ", avg: " + bestSite.average);
        if (!site2.host.equals(bestSite.host)) {
            assertTrue("Threaded winner Not within 10% margin: ", Math.abs(bestSite.average-site2.average)/site2.average < 0.1d);
        } else {
            Log.d(TAG, "Got best site: " + netTest.bestSite().host);
            assertEquals("Test expectation is fairview wins: ", site2.host, netTest.bestSite().host);
        }
    }
}
