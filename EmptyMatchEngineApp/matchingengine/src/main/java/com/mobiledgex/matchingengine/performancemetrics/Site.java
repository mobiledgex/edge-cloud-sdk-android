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

public class Site
{
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

  private static final int DEFAULT_NUM_SAMPLES = 5;

  public Site(Network network, String host, int port)
  {
    this.network = network;
    testType = NetTest.TestType.PING;
    samples = new double[DEFAULT_NUM_SAMPLES];
  }

  public Site(Network network, NetTest.TestType testType, int numSamples, String L7Path)
  {
    this.network = network;
    this.testType = testType;
    this.L7Path = L7Path;
    samples = new double[numSamples];
  }

  public Site(Network network, NetTest.TestType testType, int numSamples, String host, int port)
  {
    this.network = network;
    this.testType = testType;
    this.host = host;
    this.port = port;
    samples = new double[numSamples];
  }

  public void addSample(double time)
  {
    samples[idx] = time;
    idx++;
    if (size < samples.length) size++;
    idx = idx % samples.length;
  }

  public void recalculateStats()
  {
    double acc = 0d;
    double vsum = 0d;
    double d;
    for (int i = 0; i < size; i++)
    {
      acc += samples[i];
    }
    average = acc / size;
    for (int i = 0; i < size; i++)
    {
      d = samples[i];
      vsum += (d - average) * (d - average);
    }
    if (size > 1) {
      // Bias Corrected Sample Variance
      vsum /= (size - 1);
    }
    stddev = Math.sqrt(vsum);
  }
}
