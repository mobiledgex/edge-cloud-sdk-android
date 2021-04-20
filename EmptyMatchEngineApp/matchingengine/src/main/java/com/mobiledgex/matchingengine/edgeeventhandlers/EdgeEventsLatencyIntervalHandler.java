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
import com.mobiledgex.matchingengine.edgeeventsconfig.ClientEventsConfig;
import com.mobiledgex.matchingengine.performancemetrics.NetTest;

import java.util.Timer;
import java.util.TimerTask;

public class EdgeEventsLatencyIntervalHandler extends EdgeEventsIntervalHandler {

    public final static String TAG = "EdgeEventsLocationIntervalHandler";
    private MatchingEngine me;

    String host;
    int publicPort;
    NetTest.TestType testType;

    public EdgeEventsLatencyIntervalHandler(MatchingEngine matchingEngine, String host, int publicPort, NetTest.TestType testType, ClientEventsConfig config) {
        super();
        me = matchingEngine;
        this.host = host;
        this.publicPort = publicPort;
        this.testType = testType;
        ClientEventsConfig cfg = new ClientEventsConfig(config);

        if (me == null) {
            throw new IllegalArgumentException("MatchingEngine cannot be null!");
        }
        if (config.updateIntervalSeconds <= 0) {
            Log.w(TAG, "Seconds cannot be negative. Defaulting to 30 seconds.");

            cfg.updateIntervalSeconds = 30;
        }
        timer.schedule(new EdgeEventsLatencyIntervalHandler.LatencyTask(cfg),
                0, // initial delay
                (long)(cfg.updateIntervalSeconds * 1000)); // milliseconds interval
    }

    private class LatencyTask extends TimerTask {
        ClientEventsConfig ceConfig;
        Location location = null;

        LatencyTask(ClientEventsConfig clientEventsConfig) {
            ceConfig = clientEventsConfig;
            getNumberOfTimesExecuted = 0;
        }

        public void run() {
            if (getNumberOfTimesExecuted < ceConfig.maxNumberOfUpdates) {
                getNumberOfTimesExecuted++;
                EdgeEventsConnection edgeEventsConnection = me.getEdgeEventsConnection();
                if (edgeEventsConnection == null) {
                    Log.w(TAG, "EdgeEventsConnection is not currently available");
                    return;
                }
                // By config:
                switch (testType) {
                    case PING:
                        edgeEventsConnection.testPingAndPostLatencyUpdate(host, location, ceConfig.maxNumberOfUpdates);
                        break;
                    case CONNECT:
                        edgeEventsConnection.testConnectAndPostLatencyUpdate(host, publicPort, location, ceConfig.maxNumberOfUpdates);
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
