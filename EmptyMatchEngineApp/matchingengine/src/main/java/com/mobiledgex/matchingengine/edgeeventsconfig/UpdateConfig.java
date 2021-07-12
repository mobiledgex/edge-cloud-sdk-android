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

/*!
 * UpdateConfig configures how scheduled timers for EdgeEvens will be triggered.
 * \ingroup functions_edge_events_api
 */
public class UpdateConfig {
    private static final String TAG = "UpdateConfig";

    public UpdatePattern updatePattern; //!< The update pattern to use for scheduled interval timer task
    public double updateIntervalSeconds; //!< in seconds
    public long maxNumberOfUpdates; //!< limits number of updates per scheduled interval timer task

    public enum UpdatePattern {
        onStart,
        onTrigger,
        onInterval,
    }

    public UpdateConfig() {
        updatePattern = UpdatePattern.onTrigger;
        updateIntervalSeconds = 30;
        maxNumberOfUpdates = 0; // <= 0 means "infinity".
    }

    public UpdateConfig(UpdateConfig updateConfig) {
        updatePattern = updateConfig.updatePattern;
        updateIntervalSeconds = updateConfig.updateIntervalSeconds;
        maxNumberOfUpdates = updateConfig.maxNumberOfUpdates;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append(TAG + ": ");
        sb.append("{");
        sb.append(" hashCode: " + hashCode());
        sb.append(", updatePattern: " + updatePattern);
        sb.append(", updateIntervalSeconds: " + updateIntervalSeconds);
        sb.append(", maxNumberOfUpdates: " + maxNumberOfUpdates);
        sb.append("}");
        sb.append("}");
        return sb.toString();
    }
}
