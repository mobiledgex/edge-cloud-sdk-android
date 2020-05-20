package com.mobiledgex.mel;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

public class MelMessaging {
    final private static String TAG = "MelMessaging";

    // TODO: Finalize every field name and keys and value format below
    // FIXME: Presumptive name of target service class object is not yet known.

    // For MEL, the class is known only by prior knowledge. Mock Service name:
    //public static final String cls = "com.mobiledgex.mel.MELIntentService"; // Fully qualified class name target.
    //public static final String pkg = "com.mobiledgex.mel"; // package.
    public static final String cls = "com.sec.android.mec.mecagent.receivers.MobiledgexReceiver"; // Fully qualified class name target.
    public static final String pkg = "com.sec.android.mec.mecagent"; // package.

    // Action Filters to declare on the Service side (TBD):
    public static final String ACTION_SET_TOKEN = "com.mobiledgex.intent.action.SET_TOKEN"; // SEC name confirmed.
    public static final String ACTION_SEND_COOKIES = "com.mobiledgex.intent.action.SEND_COOKIES"; // SEC name confirmed.

    // Parcel Keys TODO: Rename keys.
    public static final String EXTRA_PARAM_TOKEN = "com.mobiledgex.intent.extra.PARAM_TOKEN"; // SEC name confirmed.
    public static final String EXTRA_PARAM_COOKIE = "cookies"; // SEC name confirmed.
    public static final String EXTRA_PARAM_APP_NAME_KEY = "app_name"; // SEC name confirmed.

    // Test only:
    private static boolean listenForAppStatus = false;
    private static MelStateReceiver mMelStateReceiver = null; // If registered, use this to unregister.

    /**
     * Convenience status check, updated at time of call.
     * @return
     */
    static public boolean isMelEnabled() {
        return MelStateReceiver.updateRegistrationState();
    }

    static public String getMelVersion() {
        MelStateReceiver.updateRegistrationState();
        return MelStateReceiver.version;
    }

  /**
   * TODO: MelMessaging still needs the device UID from device register. Initialized once per
   * device. Property name based on document.
   */
    static public String getUid() {
        MelStateReceiver.uid = MelStateReceiver.getSystemProperty("sec.mel.uuid", "");
        Log.i(TAG, "getUid is returning: " + MelStateReceiver.uid);
        return MelStateReceiver.uid;
    }

    /**
     * Need a definition for this message.
     * @param appName
     * @return
     */
    static public String getCookie(String appName) {
        // Intent Response is actually a global property value to read, not message.
        String appCookies = MelStateReceiver.getSystemProperty("sec.mel.send_token_applist", "");
        String[] apps = appCookies.split(",");
        if (apps != null && apps.length != 0) {
            for(String app : apps) {
                if(app.equals(appName)) {
                    // But it's only registered or not.
                    MelStateReceiver.appCookie = app;
                    return MelStateReceiver.appCookie;
                }
            }
        }
        return null;
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
    }

    static public void unregisterReceivers(Context context) {
        if (mMelStateReceiver != null && context != null) {
          context.unregisterReceiver(mMelStateReceiver);
        }
    }

    // FIXME: Set a dummy token if not already set on system.
    // Intents fired need the caller to return control to activity to start processing.
    // Poll for completion.
    public static void sendForMelStatus(final Context context, String appName) {
        if (mMelStateReceiver == null && listenForAppStatus == true) {
            registerReceivers(context);
        } else {
            unregisterReceivers(context);
        }

        getUid();

        // Does this check if our app is registered on platform?
        sendGetAppRegStatus(context, appName);
    }

    // For use in PlatformFindCloudlet.
    public static String sendSetToken(Context context, String token, String appName) {
        Intent intent = new Intent(ACTION_SET_TOKEN);
        intent.setClassName(pkg, cls);
        intent.putExtra(EXTRA_PARAM_APP_NAME_KEY, appName);
        intent.putExtra(EXTRA_PARAM_TOKEN, token);

        try {
            // Debug only, as info.
            Log.i(TAG, "About to send this token: " + token);
            context.sendBroadcast(intent);
            return token;
        } catch (IllegalStateException ise) {
            Log.i(TAG, "sendSetToken cannot send." + ise.getMessage());
        }
        return "";
    }

    // FIXME: Why get and receive this? Current known usage is to populate a property to read reg app status.
    public static void sendGetAppRegStatus(Context context, String appName) {
        Intent intent = new Intent(ACTION_SEND_COOKIES);
        intent.setClassName(pkg, cls);
        intent.setAction(ACTION_SEND_COOKIES);
        intent.putExtra(EXTRA_PARAM_APP_NAME_KEY, appName);

        try {
            Log.i(TAG, "sendGetAppRegStatus triggered.");
            context.sendBroadcast(intent);
        } catch (IllegalStateException ise) {
            Log.i(TAG, "sendGetAppRegStatus cannot send." + ise.getMessage());
        }
    }
}

