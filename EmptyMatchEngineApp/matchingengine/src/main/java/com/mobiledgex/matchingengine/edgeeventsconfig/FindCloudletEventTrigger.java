package com.mobiledgex.matchingengine.edgeeventsconfig;

public enum FindCloudletEventTrigger {
    CloudletStateChanged,
    AppInstHealthChanged,
    CloudletMaintenanceStateChanged,
    LatencyTooHigh,
    CloserCloudlet,
    // Todo: CpuUsageTooHigh, AutoprovUpdate, etc.
}
