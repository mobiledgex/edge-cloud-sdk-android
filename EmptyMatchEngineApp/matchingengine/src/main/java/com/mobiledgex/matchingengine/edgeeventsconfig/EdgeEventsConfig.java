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

import android.util.Log;

import com.mobiledgex.matchingengine.MatchingEngine;
import com.mobiledgex.matchingengine.performancemetrics.NetTest;

import java.util.EnumSet;

/*!
 * EdgeEventsConfig is used to configure EdgeEvent parameters.
 * \ingroup functions_edge_events_api
 */
public class EdgeEventsConfig {
    private static final String TAG = "EdgeEventsConfig";

    // Configure how to send events
    public int latencyInternalPort; //!< port information for latency testing. This is the AppPort's internal port, not public mapped port for current AppInst. Use 0 for selecting the first available port, favoring TCP.
    public int reconnectDelayMs; // !< reconnect delay in milliseconds.
    public NetTest.TestType latencyTestType = NetTest.TestType.CONNECT; //!< TCP connect default. Use ping for UDP.
    public UpdateConfig latencyUpdateConfig; //!< config for latency updates
    public UpdateConfig locationUpdateConfig; //!< config for gps location updates

    // Configure how to respond to events
    public double latencyThresholdTrigger; //!< latency threshold in ms when new FindCloudlet is triggered
    public MatchingEngine.FindCloudletMode latencyTriggerTestMode;
    public float performanceSwitchMargin; //!< Average performance must be by better by this latency margin (0 to 1.0f) before notifying of switch.
    public EnumSet<FindCloudletEventTrigger> triggers; //!< events that application wants a new find cloudlet for

    public EdgeEventsConfig() {
        latencyInternalPort = 0;
        reconnectDelayMs = 1000;
        latencyTestType = NetTest.TestType.CONNECT;
        latencyThresholdTrigger = 50;
        latencyTriggerTestMode = MatchingEngine.FindCloudletMode.PERFORMANCE;
        performanceSwitchMargin = 0.05f;
        triggers = EnumSet.allOf(FindCloudletEventTrigger.class);

        // Sane defaults, onTrigger, and once.
        latencyUpdateConfig = new UpdateConfig();
        locationUpdateConfig = new UpdateConfig();
    }

    /*!
     * copy constructor.
     *
     * \param edgeEventsConfig an existing EdgeEvents config.
     */
    public EdgeEventsConfig(EdgeEventsConfig edgeEventsConfig) {
        latencyInternalPort = edgeEventsConfig.latencyInternalPort;
        latencyTestType = edgeEventsConfig.latencyTestType;
        latencyThresholdTrigger = edgeEventsConfig.latencyThresholdTrigger;
        latencyTriggerTestMode = edgeEventsConfig.latencyTriggerTestMode;
        performanceSwitchMargin = Math.abs(edgeEventsConfig.performanceSwitchMargin);

        if (performanceSwitchMargin > 1f) {
            Log.w(TAG, "Performance Switch Margins must be less than 1.0f! Setting to 0.05f");
            performanceSwitchMargin = 0.05f;
        }

        if (edgeEventsConfig.triggers == null) {
            triggers = EnumSet.allOf(FindCloudletEventTrigger.class);
        } else if (edgeEventsConfig.triggers.size() >= 0) {
            triggers = EnumSet.copyOf(edgeEventsConfig.triggers);
        }

        // Sane defaults
        if (edgeEventsConfig.latencyUpdateConfig != null) {
            latencyUpdateConfig = new UpdateConfig(edgeEventsConfig.latencyUpdateConfig);
        }
        if (edgeEventsConfig.locationUpdateConfig != null) {
            locationUpdateConfig = new UpdateConfig(edgeEventsConfig.locationUpdateConfig);
        }
    }

    /*!
     * \return A DefaultEdgeEvents config profile.
     */
    public static EdgeEventsConfig createDefaultEdgeEventsConfig() {
        EdgeEventsConfig eeConfig = new EdgeEventsConfig();
        eeConfig.latencyThresholdTrigger = 50;

        eeConfig.latencyUpdateConfig.updateIntervalSeconds = 30;
        eeConfig.latencyUpdateConfig.updatePattern = UpdateConfig.UpdatePattern.onInterval;

        // This one will require location to be posted to the EdgeEvents state machine
        // by the Android location handler. Then, it posts to EdgeEvents that result at this interval.
        eeConfig.locationUpdateConfig.updateIntervalSeconds = 30;
        eeConfig.locationUpdateConfig.updatePattern = UpdateConfig.UpdatePattern.onInterval;

        return eeConfig;
    }

    //![exampleedgeeventsconfig]
    /*!
     * Helper util method to create a useful config for EdgeEventsConfig.
     *
     * \param latencyUpdateIntervalSeconds time in seconds between attempts to test edge server latency.
     * \param locationUpdateIntervalSeconds time in seconds between attempts to inform the attached DME the current client GPS location.
     * \param latencyThresholdTriggerMs specifies the minimum acceptable tested performance, before informing client with a new EdgeEvent.
     *
     * \section edgeeventssubscribertemplate Example
     * \snippet EdgeEventsConfig.java edgeeventssubscribertemplate
     */
    public static EdgeEventsConfig createDefaultEdgeEventsConfig(double latencyUpdateIntervalSeconds,
                                                                 double locationUpdateIntervalSeconds,
                                                                 double latencyThresholdTriggerMs,
                                                                 int latencyInternalPort) {
        EdgeEventsConfig eeConfig = new EdgeEventsConfig();
        eeConfig.latencyThresholdTrigger = latencyThresholdTriggerMs;
        eeConfig.latencyInternalPort = latencyInternalPort;

        eeConfig.latencyUpdateConfig.updateIntervalSeconds = latencyUpdateIntervalSeconds;
        eeConfig.latencyUpdateConfig.updatePattern = UpdateConfig.UpdatePattern.onInterval;

        // This one will require location to be posted to the EdgeEvents state machine
        // by the Android location handler. Then, it posts that result at this interval.
        eeConfig.locationUpdateConfig.updateIntervalSeconds = locationUpdateIntervalSeconds;
        eeConfig.locationUpdateConfig.updatePattern = UpdateConfig.UpdatePattern.onInterval;

        return eeConfig;
    }
    //![exampleedgeeventsconfig]

    // Not JSON!
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append(TAG + ": ");
        sb.append("{");
        sb.append("hashCode: " + hashCode());
        sb.append(", latencyInternalPort: " + latencyInternalPort);
        sb.append(", latencyTestType: " + latencyTestType);
        sb.append(", latencyThresholdTrigger: " + latencyThresholdTrigger);
        sb.append(", latencyTriggerTestMode: " + latencyTriggerTestMode);
        sb.append(", performanceSwitchMargin: " + performanceSwitchMargin);
        sb.append(", triggers: " + triggers);
        sb.append(", latencyUpdateConfig: " + latencyUpdateConfig);
        sb.append(", locationUpdateConfig: " + locationUpdateConfig);
        sb.append("}");
        sb.append("}");
        return sb.toString();
    }
}

