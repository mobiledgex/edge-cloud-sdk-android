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

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.util.Log;

import distributed_match_engine.AppClient;
import distributed_match_engine.LocOuterClass;

/*!
 * Utility class used in NetTest to test specified host+port
 * \ingroup classes_util
 */
public class Site
{
    private final static String TAG = "Site";
    public Network network;

    public String host;
    public int port;
    public String L7Path; // This may be load balanced.
    public double lastPingMs;

    public NetTest.TestType testType;

    int idx;
    int size;
    public double[] samples;

    public double average;
    public double stddev;

    public AppClient.Appinstance appInstance;
    public LocOuterClass.Loc cloudlet_location;

    public static final int DEFAULT_NUM_SAMPLES = 3;

    /*!
     * Create a Site instance from the default network interface. Once set, it does not change automatically.
     * \param context Application Context
     * \param testType (NetTest.TestType)
     * \param numSamples (int)
     * \param host (String)
     * \param port (int)
     */
    public Site(Context context, NetTest.TestType testType, int numSamples, String host, int port)
    {
        ConnectivityManager connectivityManager = context.getSystemService(ConnectivityManager.class);
        this.network = connectivityManager.getActiveNetwork();
        this.testType = testType;
        this.host = host;
        this.port = port;
        if (numSamples <= 0) {
            numSamples = DEFAULT_NUM_SAMPLES;
        }
        samples = new double[numSamples];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = -1;
        }
    }

    /*!
     * Test performance from a specific network interface. This interface may not be available at the time
     * of test.
     * \param network (Network)
     * \param testType (NetTest.TestType)
     * \param numSamples (int)
     * \param host (String)
     * \param port (int)
     */
    public Site(Network network, NetTest.TestType testType, int numSamples, String host, int port)
    {
        this.network = network;
        this.testType = testType;
        this.host = host;
        this.port = port;
        if (numSamples <= 0) {
            numSamples = DEFAULT_NUM_SAMPLES;
        }
        samples = new double[numSamples];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = -1;
        }
    }

    /*!
     * Appinstance data for the site.
     */
    public AppClient.Appinstance setAppinstance(AppClient.Appinstance appinstance) {
        return this.appInstance = appinstance;
    }

    /*!
     * GPS Cloudlet location
     */
    public LocOuterClass.Loc setCloudletLocation(LocOuterClass.Loc cloudlet_location) {
        return this.cloudlet_location = cloudlet_location;
    }

    public void addSample(double time)
    {
        samples[idx] = time;
        idx++;
        if (size < samples.length) size++;
        idx = idx % samples.length;
    }

    public boolean hasSuccessfulTests() {
        return size > 0;
    }

    public void recalculateStats()
    {
        double acc = 0d;
        double vsum = 0d;
        double d;

        for (int i = 0; i < size; i++) {
            acc += samples[i];
        }
        average = acc / size;
        for (int i = 0; i < size; i++) {
            d = samples[i];
            vsum += (d - average) * (d - average);
        }
        if (size > 1) {
            // Bias Corrected Sample Variance
            vsum /= (size - 1);
        }
        stddev = Math.sqrt(vsum);
    }

    /*!
     * Calculate and then return minimum sample value.
     * \return Minimum sample value. NaN if there are no samples.
     */
    public double min() {
        double min = Double.POSITIVE_INFINITY;
        if (size <= 0) {
            Log.e(TAG, "There are no samples to get minimum sample!");
            return Double.NaN;
        }
        for (int i = 0; i < size; i++) {
            if (samples[i] < min) {
                min = samples[i];
            }
        }
        return min;
    }

    /*!
     * Calculate and then return maximum sample value.
     * \return Maximum sample value. NaN if there are no samples.
     */
    public double max() {
        double max = Double.NEGATIVE_INFINITY;
        if (size <= 0) {
            Log.e(TAG, "There are no samples to get maximum sample!");
            return Double.NaN;
        }
        for (int i = 0; i < size; i++) {
            if (samples[i] > max) {
                max = samples[i];
            }
        }
        return max;
    }

    public boolean sameSite(Site o) {

        if (L7Path != null && android.text.TextUtils.equals(L7Path, o.L7Path)) {
            return true;
        }

        if (android.text.TextUtils.equals(host, o.host) && port == o.port) {
            return true;
        }

        return false;
    }
}
