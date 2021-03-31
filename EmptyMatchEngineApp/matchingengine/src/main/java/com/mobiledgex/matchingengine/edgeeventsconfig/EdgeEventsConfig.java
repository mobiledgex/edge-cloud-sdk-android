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

package com.mobiledgex.matchingengine.edgeeventsconfig;

import com.mobiledgex.matchingengine.performancemetrics.NetTest;

public class EdgeEventsConfig {
    // Configure how to send events
    public int latencyPort; // port information for latency testing, internal port, not public port for current AppInst.
    public NetTest.TestType testType = NetTest.TestType.PING; // TCP connect. Use ping for UDP.
    public ClientEventsConfig latencyUpdateConfig; // config for latency updates
    public ClientEventsConfig gpsUpdateConfig;// config for gps location updates

    // Configure how to respond to events
    public double latencyThresholdTrigger; // latency threshold in ms when new FindCloudlet is triggered
    public FindCloudletEventTrigger newFindCloudletEventTriggers[];// events that application wants a new find cloudlet for

    // Defaults:
    public EdgeEventsConfig() {
        latencyPort = 0; // implicit Ping only.
        latencyThresholdTrigger = 100f;
        newFindCloudletEventTriggers = new FindCloudletEventTrigger[] {
                FindCloudletEventTrigger.CloudletStateChanged, FindCloudletEventTrigger.LatencyTooHigh
        };
    }
}

