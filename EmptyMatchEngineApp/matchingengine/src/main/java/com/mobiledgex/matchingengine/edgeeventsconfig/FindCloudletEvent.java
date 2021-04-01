package com.mobiledgex.matchingengine.edgeeventsconfig;

import com.mobiledgex.matchingengine.FindCloudlet;

import distributed_match_engine.AppClient;

public class FindCloudletEvent {
    public FindCloudletEventTrigger trigger;
    public AppClient.FindCloudletReply newCloudlet;

    public FindCloudletEvent(AppClient.FindCloudletReply newCloudlet, FindCloudletEventTrigger trigger) {
        this.trigger = trigger;
        this.newCloudlet = newCloudlet;
    }
}

