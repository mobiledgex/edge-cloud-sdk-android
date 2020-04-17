package com.mobiledgex.mel;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public class MelMessaging {
    final private static String TAG = "MelMessaging";

    // TODO: Finalize every field name and keys and value format below
    // FIXME: Presumptive name of target service class object is not yet known.

    // For MEL, the class is known only by prior knowledge. Mock Service name:
    public static final String cls = "com.mobiledgex.mel.MELIntentService"; // Fully qualified class name target.
    public static final String pkg = "com.mobiledgex.mel"; // package.
    private static ComponentName mockServiceComponentName = new ComponentName(pkg, cls);

    private static ComponentName melServiceComponentName = mockServiceComponentName;

    // Action Filters to declare on the Service side (TBD):
    public static final String ACTION_SET_LOCATION_TOKEN = "com.mobiledgex.mel.action.SET_LOCATION_TOKEN";
    public static final String ACTION_GET_UUID = "com.mobiledgex.mel.action.GET_UUID";
    public static final String ACTION_IS_MEL = "com.mobiledgex.mel.action.IS_MEL";

    // TODO: Rename parameters
    public static final String EXTRA_PARAM_LOCATION_TOKEN = "com.mobiledgex.mel.extra.PARAM_LOCATION_TOKEN";
    public static final String EXTRA_PARAM_UUID = "com.mobiledgex.mel.extra.PARAM_UUID";
    public static final String EXTRA_PARAM_IS_MEL = "com.mobiledgex.mel.extra.PARAM_IS_MEL";

    // Parcel Keys
    public static final String APP_NAME_KEY = "app_name";

    private static MelStateReceiver mMelStateReceiver;

    /**
     * Convenience status check.
     * @return
     */
    static public boolean isMelReady() {
        if (mMelStateReceiver == null) {
            return false;
        }

        if (mMelStateReceiver.isMelEnabled && !mMelStateReceiver.uuid.isEmpty()) {
            return true;
        }
        return false;
    }

    /**
     * Convenience status check.
     * @return
     */
    static public boolean isMelEnabled() {
        return mMelStateReceiver.isMelEnabled;
    }

    static public String getUuid() {
        return mMelStateReceiver.uuid;
    }

    static public String getLocationToken() {
      return mMelStateReceiver.client_location_token;
    }

    /**
     * Waits for MEL ready status up to timeoutMs. Intents should be fired off and processing
     * before waiting for status.
     * @param timeoutMs
     * @return
     */
    static public boolean isMelReady(long timeoutMs) {
        // After control returns, check MEL state:
        final long timeout = timeoutMs;
        boolean isReady = false;

        Future<Boolean> future = CompletableFuture.supplyAsync(new Supplier<Boolean>() {
            @Override
            public Boolean get() {
                long start = System.currentTimeMillis();
                Object mutex = new Object();
                while (!MelMessaging.isMelReady()) {
                    if (System.currentTimeMillis() - start < timeout) {
                        synchronized (mutex) {
                            try {
                                mutex.wait(16);
                            } catch (InterruptedException ie) {
                            }
                        }
                    }
                }
                return MelMessaging.isMelReady();
            }
        });
        try {
            isReady = future.get(timeout, TimeUnit.MILLISECONDS);
            Log.d(TAG, "IsReady? " + isReady);
        } catch (ExecutionException | TimeoutException | InterruptedException e) {
            Log.d(TAG, "Exception during finding MEL ready state: " + e.getMessage() + ", Stack: " + e.getStackTrace());
        }
        return isReady;
    }

    static void registerReceivers(Context context) {
        if (mMelStateReceiver == null) {
            mMelStateReceiver = new MelStateReceiver();
        }
        // Register Receivers
        IntentFilter filter = new IntentFilter(ACTION_IS_MEL);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        context.registerReceiver(mMelStateReceiver, filter);

        filter = new IntentFilter(ACTION_GET_UUID);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        context.registerReceiver(mMelStateReceiver, filter);

        filter = new IntentFilter(ACTION_SET_LOCATION_TOKEN);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        context.registerReceiver(mMelStateReceiver, filter);
    }

    // FIXME: Set a dummy token if not already set on system.
    // Intents fired need the caller to return control to activity to start processing.
    // Poll for completion.
    public static void sendForMelStatus(final Context context, String appName) {
        if (mMelStateReceiver == null) {
            registerReceivers(context);
        }

        if (!MelStateReceiver.isMelEnabled) {
            sendIsMelEnabled(context);

            sendSetLocationToken(context, UUID.randomUUID().toString(), appName);
            sendGetUuidToken(context, appName);
        }
    }

    public static void sendSetLocationToken(Context context, String token, String appName) {
        Intent intent = new Intent();

        intent.setComponent(melServiceComponentName);
        intent.setAction(ACTION_SET_LOCATION_TOKEN);
        intent.putExtra(APP_NAME_KEY, appName);
        intent.putExtra(EXTRA_PARAM_LOCATION_TOKEN, token);

        context.startService(intent);
    }

  /**
   * Get UID, provided MEL is enabled.
   * @param context
   */
  public static void sendGetUuidToken(Context context, String appName) {

        Intent intent = new Intent();

        intent.setComponent(melServiceComponentName);
        intent.setAction(ACTION_GET_UUID);
        intent.putExtra(APP_NAME_KEY, appName);

        context.startService(intent);
    }

    static PendingIntent pIntent;
    public static void sendIsMelEnabled(Context context) {
        // Future for each, and a instanced receiver?
        Intent intent = new Intent();
        intent.setComponent(melServiceComponentName);
        intent.setAction(ACTION_IS_MEL);

        pIntent = PendingIntent.getService(context, 123, intent, PendingIntent.FLAG_ONE_SHOT);
        try {
            pIntent.send();
        } catch (PendingIntent.CanceledException pce) {
            Log.d(TAG, "Cancelled.");
        }
       // context.startService(intent);
    }
}

