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

import com.mobiledgex.matchingengine.MatchingEngine;
import com.mobiledgex.matchingengine.edgeeventsconfig.ClientEventsConfig;

import java.util.TimerTask;

public class EdgeEventsLocationIntervalHandler extends EdgeEventsIntervalHandler {
    public final static String TAG = "EdgeEventsLocationIntervalHandler";
    private MatchingEngine me;

    public EdgeEventsLocationIntervalHandler(MatchingEngine matchingEngine, ClientEventsConfig config) {
        super();
        me = matchingEngine;
        ClientEventsConfig cfg = new ClientEventsConfig(config);

        if (me == null) {
            throw new IllegalArgumentException("MatchingEngine cannot be null!");
        }
        if (config.updateIntervalSeconds <= 0) {
            Log.w(TAG, "Seconds cannot be negative. Defaulting to 30 seconds.");

            cfg.updateIntervalSeconds = 30;
        }

        timer.schedule(new LocationTask(cfg),
                0, // inital delay
                (long)(cfg.updateIntervalSeconds * 1000)); // milliseconds interval
    }

    private class LocationTask extends TimerTask {

        ClientEventsConfig ceConfig;
        Location location = null;

        LocationTask(ClientEventsConfig clientEventsConfig) {
            ceConfig = clientEventsConfig;
            getNumberOfTimesExecuted = 0;
        }

        public void run() {
            if (getNumberOfTimesExecuted < ceConfig.maxNumberOfUpdates) {
                getNumberOfTimesExecuted++;
                if (me.isMatchingEngineLocationAllowed() && me.getEdgeEventsConnection() != null && !me.getEdgeEventsConnection().isShutdown()) {
                    location = me.getEdgeEventsConnection().getLocation();
                } else {
                    Log.w(TAG, "Location is currently not available or disabled.");
                }
                me.getEdgeEventsConnection().postLocationUpdate(location);
            } else {
                Log.i(TAG, "Timer task complete.");
                cancel(); // Tests done.
            }
        }
    }
}
