package com.mobiledgex.matchingengine.performancemetrics;

import android.util.Log;

import com.google.common.base.Stopwatch;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import distributed_match_engine.AppClient;
import distributed_match_engine.LocOuterClass;

/*
 * In place bandwidth calculator (UDP).
 *
 * Bandwidth determination needs a bit of history.
 * (bits per second needs 1) to start assumptions and 2) status updates to start adjusting).
 * With no limits to bandwidth, there needs to be a ramp up amount (and strategy to do
 * so, as well as a ramp down in case of ECNStatus signaled CE congestion encountered.
 *
 * As the router/network path only signals "congestion encountered", it is offering
 * no additional details as to what that bandwidth should be. Ideally, one would like to go
 * as fast as possible given moment to moment ECN signaling, but before packets are dropped
 * too often.
 *
 * And of course, all this ideally will beat TCP slow start.
 */
public class EcnCalculator {
    private static final String TAG = "EcnCalculator";

    public double bandwidth = 0d;
    public static final int clearThreshold = 100; // If the data hasn't been refreshed in some time, start over from scratch.
    public static final double RAMP_UP_SPEED = 200000d;
    public float minimumBandwidth = 50000f; // Floor bandwidth, if below this, the connection might not be usable anymore.

    public static final float rampUpScale = 0.2f;
    public static final float ecnCeScaleDown = 0.9f;

    private int idx = 0;
    int capacity = 3;
    ArrayList<Double> aggregateBandwidth = new ArrayList<>(capacity);

    // Statistics:
    private double averageBandwidth = 0;
    private long sendIntervalMs = 5000; // ms;

    private LocOuterClass.Timestamp sampleStartTs;
    private Stopwatch staleDataTimer = Stopwatch.createUnstarted();
    private Stopwatch sendTimer = Stopwatch.createUnstarted();

    private long ce_count = 0;
    private long num_packets = 0;

    public EcnCalculator() {
        Log.d(TAG, "New ECNCalculator.");
    }

    public LocOuterClass.Timestamp getTimestampFromMs(long milliseconds) {
        long seconds = milliseconds / 1000;
        long remainder_ms = milliseconds - (seconds * 1000);
        int ns = (int)remainder_ms * 10^6;

        return LocOuterClass.Timestamp.newBuilder()
                .setSeconds(seconds)
                .setNanos(ns)
                .build();
    }

    public LocOuterClass.Timestamp getCurrentTimestamp() {
        return getTimestampFromMs(System.currentTimeMillis());
    }

    public void resetSendTimer() {
        sampleStartTs = getCurrentTimestamp();
        sendTimer.reset();
        sendTimer.start();
        ce_count = 0;
        num_packets = 0;
    }

    private void resetForStaleTimer() {
        sampleStartTs = getCurrentTimestamp();
        sendTimer.reset();
        sendTimer.start();
        ce_count = 0;
        num_packets = 0;

        aggregateBandwidth.clear();
        averageBandwidth = 0d;
        bandwidth = RAMP_UP_SPEED;

        staleDataTimer.reset();
        staleDataTimer.start();
    }

    private void staleTimerKeepAlive() {
        staleDataTimer.reset();
        staleDataTimer.start();
    }

    public boolean isShouldSend() {
        if (sendTimer.elapsed(TimeUnit.MILLISECONDS) >= sendIntervalMs) {
            return true;
        }
        return false;
    }

    boolean isStaleTimer() {
        if (staleDataTimer.elapsed(TimeUnit.MILLISECONDS) > clearThreshold) {
            return true;
        }
        return false;
    }

    void resetSamplePeriod() {
        sampleStartTs = getCurrentTimestamp();
        ce_count = 0;
        num_packets = 0;
    }

    // This sort of assumes a stream of data, not data that comes in bursts.
    public AppClient.ECNStatus Update(int ecn) {
        AppClient.ECNBit ecnBit;
        try {
            ecnBit = AppClient.ECNBit.forNumber(ecn);
        } catch (IllegalArgumentException iae) {
            Log.e(TAG, iae.getMessage());
            return null;
        }

        if (isStaleTimer()) {
            resetForStaleTimer();
        }
        staleTimerKeepAlive();

        // CE packet stats
        if (ecn == AppClient.ECNBit.CE.getNumber()) {
            ce_count++;
        }
        num_packets++;

        // Update stats:
        if (ecnBit == AppClient.ECNBit.CE) {
            bandwidth *= ecnCeScaleDown;
            if (bandwidth <= minimumBandwidth) {
                bandwidth = minimumBandwidth;
            }
        }
        else if (ecnBit == AppClient.ECNBit.ECT_0 || ecnBit == AppClient.ECNBit.ECT_1) {
            bandwidth += (1 + rampUpScale) * RAMP_UP_SPEED;   // That is, not exponentially faster, but there are no maximums either.
        }
        else {
            // Not supported (anymore?)
            Log.d(TAG, "connection claims ECN marking is unsupported.");
        }

        // Minimum guard:
        if (bandwidth <= minimumBandwidth) {
            bandwidth = minimumBandwidth;
        }

        // sample(s) update (and historical samples)
        aggregateBandwidth.add(idx, bandwidth);
        idx = (idx + 1) % capacity;
        double num = bandwidth + (averageBandwidth * (aggregateBandwidth.size() - 1));
        averageBandwidth = num / aggregateBandwidth.size();

        return AppClient.ECNStatus.newBuilder()
                .setBandwidth(averageBandwidth)
                .setEcnBit(ecnBit)
                .setStrategy(AppClient.ECNStrategy.STRATEGY_1)
                .setSampleStart(sampleStartTs)
                .setSampleEnd(getCurrentTimestamp())
                .setNumCe(ce_count)
                .setNumPackets(num_packets)
                .build();
    }
}
