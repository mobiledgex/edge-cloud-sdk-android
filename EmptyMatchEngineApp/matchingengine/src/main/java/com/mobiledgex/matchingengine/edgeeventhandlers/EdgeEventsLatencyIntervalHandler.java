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
package com.mobiledgex.matchingengine.edgeeventhandlers;

import android.location.Location;
import android.util.Log;

import com.mobiledgex.matchingengine.EdgeEventsConnection;
import com.mobiledgex.matchingengine.MatchingEngine;
import com.mobiledgex.matchingengine.edgeeventsconfig.UpdateConfig;
import com.mobiledgex.matchingengine.performancemetrics.NetTest;

import java.util.TimerTask;

import distributed_match_engine.AppClient;

public class EdgeEventsLatencyIntervalHandler extends EdgeEventsIntervalHandler {

    public final static String TAG = "EdgeEventsLocationIntervalHandler";
    private MatchingEngine me;

    String host;
    int publicPort;

    public EdgeEventsLatencyIntervalHandler(MatchingEngine matchingEngine, NetTest.TestType testType, UpdateConfig config) {
        super();
        me = matchingEngine;
        if (me == null) {
            throw new IllegalArgumentException("MatchingEngine cannot be null!");
        }
        if (config == null) {
            throw new IllegalArgumentException("Config cannot be null!");
        }
        UpdateConfig cfg = new UpdateConfig(config);

        if (config.updateIntervalSeconds <= 0) {
            Log.w(TAG, "Seconds cannot be negative. Defaulting to 30 seconds.");

            cfg.updateIntervalSeconds = 30;
        }
        timer.schedule(new EdgeEventsLatencyIntervalHandler.LatencyTask(testType, cfg),
                (long)(cfg.updateIntervalSeconds * 1000), // initial delay
                (long)(cfg.updateIntervalSeconds * 1000)); // milliseconds interval
    }

    private class LatencyTask extends TimerTask {
        UpdateConfig ceConfig;
        Location location = null;

        NetTest.TestType testType;

        LatencyTask(NetTest.TestType testType, UpdateConfig updateConfig) {
            ceConfig = updateConfig;
            getNumberOfTimesExecuted = 0;
            this.testType = testType;
        }

        public void run() {
            AppClient.FindCloudletReply lastFc = me.getLastFindCloudletReply();
            if (lastFc == null) {
                return;
            }

            if (getNumberOfTimesExecuted < ceConfig.maxNumberOfUpdates || ceConfig.maxNumberOfUpdates <= 0) {
                getNumberOfTimesExecuted++;
                EdgeEventsConnection edgeEventsConnection = me.getEdgeEventsConnection();
                if (edgeEventsConnection == null) {
                    Log.w(TAG, "EdgeEventsConnection is not currently available");
                    return;
                }

                // Permissions required, and could return null.
                location = edgeEventsConnection.getLocation();
                // By config:
                switch (testType) {
                    case PING:
                        edgeEventsConnection.testPingAndPostLatencyUpdate(location);
                        break;
                    case CONNECT:
                        edgeEventsConnection.testConnectAndPostLatencyUpdate(location);
                        break;
                    default:
                        Log.e(TAG, "Unexpected test type: " + testType);
                }
            } else {
                Log.i(TAG, "Timer task complete.");
                cancel(); // Tests done.
            }
        }
    }
}
