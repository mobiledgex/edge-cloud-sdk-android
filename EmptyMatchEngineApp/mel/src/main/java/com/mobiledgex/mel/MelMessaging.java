package com.mobiledgex.mel;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

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

    // FIXME: without this, one can only broadcast (!).
    private static ComponentName melServiceComponentName = mockServiceComponentName;

    // Action Filters to declare on the Service side (TBD):
    public static final String ACTION_SET_TOKEN = "com.mobiledgex.intent.action.SET_TOKEN"; // SEC name confirmed.
    public static final String ACTION_SEND_COOKIES = "com.mobiledgex.intent.action.SEND_COOKIES"; // SEC name confirmed.

    // Parcel Keys TODO: Rename keys.
    public static final String EXTRA_PARAM_TOKEN = "com.mobiledgex.intent.extra.PARAM_TOKEN"; // SEC name confirmed.
    public static final String EXTRA_PARAM_COOKIE = "cookies"; // SEC name confirmed.
    public static final String EXTRA_PARAM_APP_NAME_KEY = "app_name"; // SEC name confirmed.

    private static MelStateReceiver mMelStateReceiver;

    /**
     * Convenience status check. TODO: Remove.
     * @return
     */
    static public boolean isMelReady() {
        if (mMelStateReceiver == null) {
            return false;
        }

        if (mMelStateReceiver.isMelEnabled && !mMelStateReceiver.uid.isEmpty()) {
            return true;
        }
        return false;
    }

    /**
     * Convenience status check, updated at time of call.
     * @return
     */
    static public boolean isMelEnabled() {
        return mMelStateReceiver.updateRegistrationState();
    }

    static public String getMelVersion() {
        mMelStateReceiver.updateRegistrationState();
        return mMelStateReceiver.version;
    }

  /**
   * TODO: MelMessaging still needs the device UID from device register. Initialized once per
   * device. Property name based on document.
   */
    static public String getUid() {
        mMelStateReceiver.uid = MelStateReceiver.getSystemProperty("sec.mel.uuid", "");
        return mMelStateReceiver.uid;
    }

    /**
     * Need a definition for this message.
     * @param appName
     * @return
     */
    static public String getCookie(String appName) {
        // Intent Response is actually a global property value to read, not message.
        String appCookies = MelStateReceiver.getSystemProperty("sec.mel.regi-status", "");
        String[] apps = appCookies.split(",");
        if (apps != null && apps.length != 0) {
            for(String app : apps) {
                if(app.equals(appName)) {
                    // But it's only registered or not...we need that cookie for Register.
                    MelStateReceiver.appCookie = "";
                    return MelStateReceiver.appCookie;
                }
            }
        }
        return null;
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
        IntentFilter filter;

        filter = new IntentFilter(ACTION_SEND_COOKIES);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        context.registerReceiver(mMelStateReceiver, filter);

        //filter = new IntentFilter(ACTION_SET_LOCATION_TOKEN);
        //filter.addCategory(Intent.CATEGORY_DEFAULT);
        //context.registerReceiver(mMelStateReceiver, filter);
    }

    // FIXME: Set a dummy token if not already set on system.
    // Intents fired need the caller to return control to activity to start processing.
    // Poll for completion.
    public static void sendForMelStatus(final Context context, String appName) {
        if (mMelStateReceiver == null) {
            registerReceivers(context);
        }

        getUid();

        // Does this check if our app is registered on platform?
        sendGetAppRegStatus(context, appName);
    }

    // For use in PlatformFindCloudlet.
    public static void sendSetToken(Context context, String token, String appName) {
        Intent intent = new Intent(ACTION_SET_TOKEN);
        intent.putExtra(EXTRA_PARAM_APP_NAME_KEY, appName);
        intent.putExtra(EXTRA_PARAM_TOKEN, token);

        try {
            context.sendBroadcast(intent);
        } catch (IllegalStateException ise) {
            Log.i(TAG, "sendSetLocationToken cannot send." + ise.getMessage());
        }
    }

    // FIXME: Why get and receive this? Current known usage is to populate a property to read reg app status.
    public static void sendGetAppRegStatus(Context context, String appName) {
        Intent intent = new Intent(ACTION_SEND_COOKIES);
        intent.setAction(ACTION_SEND_COOKIES);
        intent.putExtra(EXTRA_PARAM_APP_NAME_KEY, appName);

        try {
            context.sendBroadcast(intent);
        } catch (IllegalStateException ise) {
            Log.i(TAG, "sendGetUuidToken cannot send." + ise.getMessage());
        }
    }
}

