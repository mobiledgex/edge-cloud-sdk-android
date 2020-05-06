package com.mobiledgex.mel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;

import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;

import static com.mobiledgex.mel.MelMessaging.ACTION_IS_MEL_ENABLED;
import static com.mobiledgex.mel.MelMessaging.ACTION_SEND_COOKIES;
import static com.mobiledgex.mel.MelMessaging.ACTION_SET_LOCATION_TOKEN;
import static com.mobiledgex.mel.MelMessaging.EXTRA_PARAM_IS_MEL_ENABLED;
import static com.mobiledgex.mel.MelMessaging.EXTRA_PARAM_COOKIE;
import static com.mobiledgex.mel.MelMessaging.EXTRA_PARAM_LOCATION_TOKEN;

public class MelStateReceiver extends BroadcastReceiver {
    private static final String TAG = "MelStateReceiver";

    // These are discovered system states.
    public static String versionReg = "";
    public static String version;
    public static boolean isMelEnabled = getSystemPropertyBoolean("sec.mel.enabled", false);
    public static @NotNull String client_location_token = "";
    public static @NotNull String appCookie = "";

    static {
        updateRegistrationState();
    }
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Action: " + intent.getAction());
        switch (intent.getAction()) {
            case ACTION_IS_MEL_ENABLED: {
                isMelEnabled = intent.getBooleanExtra(EXTRA_PARAM_IS_MEL_ENABLED, false);
                Log.d(TAG, "isMelEnabled: " + isMelEnabled);
                break;
            }
            case ACTION_SET_LOCATION_TOKEN: {
                client_location_token = intent.getStringExtra(EXTRA_PARAM_LOCATION_TOKEN);
                Log.d(TAG, "currentToken: " + client_location_token);
                break;
            }
            case ACTION_SEND_COOKIES: {
                appCookie = intent.getStringExtra(EXTRA_PARAM_COOKIE);
                Log.d(TAG, "Got Token: " + appCookie);
                break;
            }
        }
    }

    public static boolean updateRegistrationState() {
        versionReg = getSystemProperty("sec.mel.version", "");
        if (versionReg == "") {
            return false;
        }
        String [] verregArr = versionReg.split("-");
        if (verregArr == null || verregArr.length < 2) {
            return false;
        }
        version = verregArr[0];
        if (verregArr[1].equals("regi")) { // or unregi.
            isMelEnabled = true;
        } else {
            isMelEnabled = false;
        }
        return isMelEnabled;
    }

    public static String getSystemProperty(String property, String defaultValue) {
        try {
            Class sysPropCls = Class.forName("android.os.SystemProperties");
            Method getMethod = sysPropCls.getDeclaredMethod("get", String.class);
            String value = (String)getMethod.invoke(null, property);
            if (TextUtils.isEmpty(value)) {
                return value;
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to read system properties.");
            e.printStackTrace();
        }
        return defaultValue;
    }

    public static boolean getSystemPropertyBoolean(String property, boolean defaultValue) {
       return Boolean.getBoolean(getSystemProperty(property, Boolean.toString(defaultValue)));
    }
}
