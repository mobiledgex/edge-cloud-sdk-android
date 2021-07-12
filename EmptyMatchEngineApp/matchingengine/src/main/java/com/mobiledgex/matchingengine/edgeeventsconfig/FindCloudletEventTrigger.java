package com.mobiledgex.matchingengine.edgeeventsconfig;

/*!
 * FindCloudletEventTrigger types of triggers for EdgeEventsConfig.
 * \ingroup functions_edge_events_api
 */
public enum FindCloudletEventTrigger {
    CloudletStateChanged,
    AppInstHealthChanged,
    CloudletMaintenanceStateChanged,
    LatencyTooHigh,
    CloserCloudlet,
    // Todo: CpuUsageTooHigh, AutoprovUpdate, etc.
}
