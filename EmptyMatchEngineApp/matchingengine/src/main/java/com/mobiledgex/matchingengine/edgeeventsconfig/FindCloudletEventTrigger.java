package com.mobiledgex.matchingengine.edgeeventsconfig;

public enum FindCloudletEventTrigger {
    CloudletStateChanged,
    AppInstHealthChanged,
    CloudletMaintenanceStateChanged,
    LatencyTooHigh,
    // Todo: CpuUsageTooHigh, AutoprovUpdate, etc.
}
