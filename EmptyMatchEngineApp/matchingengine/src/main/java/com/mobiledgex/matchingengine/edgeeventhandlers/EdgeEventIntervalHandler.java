package com.mobiledgex.matchingengine.edgeeventhandlers;

import java.util.Timer;

public abstract class EdgeEventIntervalHandler {
    long getNumberOfTimesExecuted = 0;
    Timer timer;

    public long getNumberOfTimesExecuted() {
        return getNumberOfTimesExecuted;
    }

    public void cancel() {
        if (timer != null) {
            timer.cancel();
        }
    }

}
