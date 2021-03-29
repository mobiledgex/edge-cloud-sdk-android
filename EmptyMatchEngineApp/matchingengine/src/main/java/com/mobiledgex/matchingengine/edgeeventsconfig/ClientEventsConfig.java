package com.mobiledgex.matchingengine.edgeeventsconfig;

public class ClientEventsConfig {
    public UpdatePattern updatePattern;
    public double updateInterval; // in seconds
    public int numberOfUpdates;

    public enum UpdatePattern {
        onStart,
        onTrigger,
        onInterval,
    }
}
