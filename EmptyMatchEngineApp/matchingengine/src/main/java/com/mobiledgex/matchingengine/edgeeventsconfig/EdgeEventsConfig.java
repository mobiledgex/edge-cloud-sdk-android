package com.mobiledgex.matchingengine.edgeeventsconfig;

import java.util.ArrayList;

// Oy.
enum FindCloudletEvent {
    CloudletStateChanged,
    AppInstHealthChanged,
    CloudletMaintenanceStateChanged,
    LatencyTooHigh,
    // Todo: CpuUsageTooHigh, AutoprovUpdate, etc.
}

public class EdgeEventsConfig {
    // Configure how to send events
    public int latencyPort; // port information for latency testing
    public ClientEventsConfig latencyUpdateConfig; // config for latency updates
    public ClientEventsConfig gpsUpdateConfig;// config for gps location updates

    // Configure how to respond to events
    public double latencyThresholdTrigger; // latency threshold in ms when new FindCloudlet is triggered
    public FindCloudletEvent newFindCloudletEvents[];// events that application wants a new find cloudlet for

    // Defaults:
    public EdgeEventsConfig() {
        latencyPort = 0; // implicityly Ping only.
        latencyThresholdTrigger = 100f;
        newFindCloudletEvents = new FindCloudletEvent[] {
                FindCloudletEvent.CloudletStateChanged
        };
    }
}

