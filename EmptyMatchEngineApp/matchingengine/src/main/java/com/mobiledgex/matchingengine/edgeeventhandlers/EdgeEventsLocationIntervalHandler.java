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

import java.util.TimerTask;

public class EdgeEventsLocationIntervalHandler extends EdgeEventsIntervalHandler {
    public final static String TAG = "EdgeEventsLocationIntervalHandler";
    private MatchingEngine me;

    public EdgeEventsLocationIntervalHandler(MatchingEngine matchingEngine, UpdateConfig config) {
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
            Log.w(TAG, "Seconds cannot be zero or negative. Defaulting to " +
                    UpdateConfig.UPDATE_INTERVAL_SECONDS_DEFAULT+ " seconds.");
            cfg.updateIntervalSeconds = UpdateConfig.UPDATE_INTERVAL_SECONDS_DEFAULT;
        }

        timer.schedule(new LocationTask(cfg),
                (long)(cfg.updateIntervalSeconds * 1000), // inital delay
                (long)(cfg.updateIntervalSeconds * 1000)); // milliseconds interval
    }

    private class LocationTask extends TimerTask {

        UpdateConfig ceConfig;
        Location location = null;

        LocationTask(UpdateConfig updateConfig) {
            ceConfig = updateConfig;
            getNumberOfTimesExecuted = 0;
        }

        public void run() {
            if (getNumberOfTimesExecuted < ceConfig.maxNumberOfUpdates || ceConfig.maxNumberOfUpdates <= 0) {
                getNumberOfTimesExecuted++;
                EdgeEventsConnection edgeEventsConnection = me.getEdgeEventsConnection();
                if (edgeEventsConnection == null) {
                    Log.w(TAG, "EdgeEventsConnection is not currently available.");
                    return;
                }
                // Permissions required, and could return null.
                location = edgeEventsConnection.getLocation();

                edgeEventsConnection.postLocationUpdate(location);
                if (ceConfig.updatePattern == UpdateConfig.UpdatePattern.onStart) {
                    Log.i(TAG, "OnStart LocationUpdate fired.");
                    cancel();
                }
            } else {
                Log.i(TAG, "Timer task complete.");
                cancel(); // Tests done.
            }
        }
    }
}
