package com.mobiledgex.matchingengine.edgeeventhandlers;

import java.util.Timer;

public abstract class EdgeEventsIntervalHandler {
    long getNumberOfTimesExecuted = 0;
    Timer timer;
    private boolean isDone = false;

    public EdgeEventsIntervalHandler() {
        timer = new Timer();
    }

    synchronized public void cancel() {
        if (isDone) {
            return;
        }
        if (timer != null) {
            timer.cancel();
            isDone = true;
        }
    }

    synchronized public boolean isDone() {
        if (timer == null) {
            return isDone = true;
        }
        return isDone;
    }
}
