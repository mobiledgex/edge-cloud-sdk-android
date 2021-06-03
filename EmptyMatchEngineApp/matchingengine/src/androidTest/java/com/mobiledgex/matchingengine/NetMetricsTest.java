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
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Network;
import android.os.Looper;
import androidx.test.platform.app.InstrumentationRegistry;
import android.util.Log;

import com.mobiledgex.matchingengine.performancemetrics.NetTest;
import com.mobiledgex.matchingengine.performancemetrics.Site;

import org.junit.Assert;
import org.junit.Test;
import org.junit.Before;

import java.util.List;
import java.util.concurrent.ExecutionException;

import distributed_match_engine.AppClient;

import static android.content.pm.PackageManager.*;
import static com.mobiledgex.matchingengine.performancemetrics.NetTest.TestType.PING;
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
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);

        Network network = me.getNetworkManager().getActiveNetwork();

        Location location = new Location("EngineCallTestLocation");
        location.setLongitude(-122.3321);
        location.setLatitude(47.6062);

        NetTest netTest = new NetTest();
        AppClient.RegisterClientRequest.Builder registerClientRequestBuilder = null;
        try {
            registerClientRequestBuilder = me.createDefaultRegisterClientRequest(context, "MobiledgeX-Samples")
                    .setAppName("ComputerVision-GPU")
                    .setAppVers("2.2");
            AppClient.RegisterClientRequest req = registerClientRequestBuilder.build();

            AppClient.RegisterClientReply regReply = me.registerClient(req, 10000);
            AppClient.AppInstListRequest  appInstListRequest = me.createDefaultAppInstListRequest(context, location)
                    .setCarrierName("")
                    .build();

            AppClient.AppInstListReply appInstListReply = me.getAppInstList(appInstListRequest, 10000);
            for (AppClient.CloudletLocation cloudletLoc : appInstListReply.getCloudletsList()) {
                for (AppClient.Appinstance appInst : cloudletLoc.getAppinstancesList()) {
                    String host = appInst.getPortsList().get(0).getFqdnPrefix() + appInst.getFqdn(); // +  appInst.getPortsList().get(0).getPathPrefix();
                    int port = 8008;
                    Site site = new Site(me.getNetworkManager().getActiveNetwork(), NetTest.TestType.CONNECT, 5, host, port);
                    netTest.addSite(site);
                }
            }
        } catch (DmeDnsException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            Assert.assertFalse(String.valueOf(e.getStackTrace()), true);
        } catch (NameNotFoundException pmnf) {
            Assert.assertTrue("Missing package manager!", false);
        }


        List<Site> sites = netTest.sortedSiteList();
        assertEquals("Site list size wrong!", 2, sites.size());

        Site bestSite;
        netTest.testSites(TimeoutMS);
        bestSite = netTest.bestSite();
        Site site2 = netTest.sortedSiteList().get(0);
        Log.d(TAG, "Host expected: " + site2.host + ", avg: " + site2.average + ", got: " + "Got best site: " + bestSite.host + ", avg: " + bestSite.average);
        if (!site2.host.equals(bestSite.host)) {
            assertTrue("Serial winner Not within 10% margin: ", Math.abs(bestSite.average-site2.average)/site2.average < 0.1d);
        } else {
            assertEquals("Test expectation is vancouver-main wins: ", site2.host, netTest.bestSite().host);
        }

        netTest.testSitesOnExecutor(TimeoutMS);
        bestSite = netTest.bestSite();
        // Not switching the site. It's supposed to be usually the same "best" server, mostly.
        Log.d(TAG, "Host expected: " + site2.host + ", avg: " + site2.average + ", got: " + "Got best site: " + bestSite.host + ", avg: " + bestSite.average);
        if (!site2.host.equals(bestSite.host)) {
            assertTrue("Threaded winner Not within 10% margin: ", Math.abs(bestSite.average-site2.average)/site2.average < 0.1d);
        } else {
            Log.d(TAG, "Got best site: " + netTest.bestSite().host);
            assertEquals("Test expectation is vancouver-main wins: ", site2.host, netTest.bestSite().host);
        }
        // Might fail:
        assertEquals("cv-gpu-cluster.montreal-pitfield.telus.mobiledgex.net", bestSite.host);

    }

    @Test
    public void testMetrics2() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);

        Network network = me.getNetworkManager().getActiveNetwork();

        Location location = new Location("EngineCallTestLocation");
        location.setLongitude(-122.3321);
        location.setLatitude(47.6062);

        NetTest netTest = new NetTest();
        AppClient.RegisterClientRequest.Builder registerClientRequestBuilder = null;
        try {
            registerClientRequestBuilder = me.createDefaultRegisterClientRequest(context, "MobiledgeX-Samples")
                    .setAppName("ComputerVision-GPU")
                    .setAppVers("2.2");
            AppClient.RegisterClientRequest req = registerClientRequestBuilder.build();

            AppClient.RegisterClientReply regReply = me.registerClient(req, 10000);
            AppClient.AppInstListRequest  appInstListRequest = me.createDefaultAppInstListRequest(context, location)
                    .setCarrierName("")
                    .build();

            AppClient.AppInstListReply appInstListReply = me.getAppInstList(appInstListRequest, 10000);
            for (AppClient.CloudletLocation cloudletLoc : appInstListReply.getCloudletsList()) {
                for (AppClient.Appinstance appInst : cloudletLoc.getAppinstancesList()) {
                    String host = appInst.getPortsList().get(0).getFqdnPrefix() + appInst.getFqdn();
                    int port = 8008;
                    Site site = new Site(context, NetTest.TestType.CONNECT, 5, host, port);
                    netTest.addSite(site);
                }
            }
        } catch (DmeDnsException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            Assert.assertFalse(String.valueOf(e.getStackTrace()), true);
        } catch (NameNotFoundException pmnf) {
            Assert.assertTrue("Missing package manager!", false);
        }


        List<Site> sites = netTest.sortedSiteList();
        assertEquals("Site list size wrong!", 2, sites.size());

        Site bestSite;
        netTest.testSites(TimeoutMS);
        bestSite = netTest.bestSite();
        Site site2 = netTest.sortedSiteList().get(0);
        Log.d(TAG, "Host expected: " + site2.host + ", avg: " + site2.average + ", got: " + "Got best site: " + bestSite.host + ", avg: " + bestSite.average);
        if (!site2.host.equals(bestSite.host)) {
            assertTrue("Serial winner Not within 10% margin: ", Math.abs(bestSite.average-site2.average)/site2.average < 0.1d);
        } else {
            assertEquals("Test expectation is vancouver-main wins: ", site2.host, netTest.bestSite().host);
        }

        netTest.testSitesOnExecutor(TimeoutMS);
        bestSite = netTest.bestSite();
        // Not switching the site. It's supposed to be usually the same "best" server, mostly.
        Log.d(TAG, "Host expected: " + site2.host + ", avg: " + site2.average + ", got: " + "Got best site: " + bestSite.host + ", avg: " + bestSite.average);
        if (!site2.host.equals(bestSite.host)) {
            assertTrue("Threaded winner Not within 10% margin: ", Math.abs(bestSite.average-site2.average)/site2.average < 0.10d);
        } else {
            Log.d(TAG, "Got best site: " + netTest.bestSite().host);
            assertEquals("Test expectation is vancouver-main site wins: ", site2.host, netTest.bestSite().host);
        }
        // Might fail:
        assertEquals("cv-gpu-cluster.montreal-pitfield.telus.mobiledgex.net", bestSite.host);

    }

    // Mainly for documenation usage:
    @Test
    public void testMetrics3() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MatchingEngine me = new MatchingEngine(context);
        me.setMatchingEngineLocationAllowed(true);
        me.setUseWifiOnly(true);

        Network network = me.getNetworkManager().getActiveNetwork();

        Location location = new Location("EngineCallTestLocation");
        location.setLongitude(-122.3321);
        location.setLatitude(47.6062);

        //! [nettest]
        NetTest netTest = new NetTest();
        AppClient.RegisterClientRequest.Builder registerClientRequestBuilder = null;
        try {
            registerClientRequestBuilder = me.createDefaultRegisterClientRequest(context, "MobiledgeX-Samples")
                    .setAppName("EdgeMultiplay")
                    .setAppVers("1.2");
            AppClient.RegisterClientRequest req = registerClientRequestBuilder.build();

            AppClient.RegisterClientReply regReply = me.registerClient(req,10000);
            AppClient.FindCloudletRequest findCloudletRequest = me.createDefaultFindCloudletRequest(context, location)
                    .setCarrierName("")
                    .build();

            int internalPort = 7776; // This is the un-remapped port of the App definition. See the app's console definition.
            AppClient.FindCloudletReply findCloudletReply = me.findCloudlet(findCloudletRequest, 10000);
            String host = me.getAppConnectionManager().getHost(findCloudletReply, internalPort); // internal port to host lookup.
            int port = me.getAppConnectionManager().getPublicPort(findCloudletReply, internalPort); // internal port to port lookup.
            Site site = new Site(context, NetTest.TestType.CONNECT, 5, host, port);
            netTest.addSite(site);
        } catch (DmeDnsException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            Assert.assertFalse(String.valueOf(e.getStackTrace()), true);
        } catch (NameNotFoundException pmnf) {
            Assert.assertTrue("Missing package manager!", false);
        }

        List<Site> sites = netTest.sortedSiteList();
        netTest.testSites(TimeoutMS);
        List<Site> sortedSites = netTest.sortedSiteList();
        for (Site s : sortedSites) {
            Log.i(TAG, "Test average: " + s.average);
        }
        //! [nettest]
        assertEquals("Site list size wrong!", 1, sites.size());

    }

    @Test
    public void testMinSample1() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        double samples[] = {Double.POSITIVE_INFINITY, -47d, 500, -3, -1};
        int numSamples = samples.length;
        Site site = new Site(context, PING, numSamples, "aHost", 9999);

        for (double d : samples) {
            site.addSample(d);
        }
        assertEquals("Not correct minimum!", -47d, site.min(), .0001d);
    }

    @Test
    public void testMinSample2() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        double samples[] = {Double.MAX_VALUE, -47d, 500, -3, -50};
        int numSamples = samples.length;
        Site site = new Site(context, PING, numSamples, "aHost", 9999);

        for (double d : samples) {
            site.addSample(d);
        }
        assertEquals("Not correct minimum!", -50d, site.min(), .0001d);
    }

    @Test
    public void testMaxSample1() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        double samples[] = {Double.NEGATIVE_INFINITY, -47d, 500, -3, -1, 800d};
        int numSamples = samples.length;
        Site site = new Site(context, PING, numSamples, "aHost", 9999);

        for (double d : samples) {
            site.addSample(d);
        }
        assertEquals("Not correct maximum!", 800d, site.max(), .0001d);
    }

    @Test
    public void testMaxSample2() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        double samples[] = {Double.MIN_VALUE, -47d, 500, -3, 0, 800d};
        int numSamples = samples.length;
        Site site = new Site(context, PING, numSamples, "aHost", 9999);

        for (double d : samples) {
            site.addSample(d);
        }
        assertEquals("Not correct maximum!", 800d, site.max(), .0001d);
    }
}
