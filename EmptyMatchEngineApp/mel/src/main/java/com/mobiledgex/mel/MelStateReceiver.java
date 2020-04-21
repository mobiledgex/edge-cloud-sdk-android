package com.mobiledgex.mel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.jetbrains.annotations.NotNull;

import static com.mobiledgex.mel.MelMessaging.ACTION_IS_MEL;
import static com.mobiledgex.mel.MelMessaging.ACTION_GET_UUID;
import static com.mobiledgex.mel.MelMessaging.ACTION_SET_LOCATION_TOKEN;
import static com.mobiledgex.mel.MelMessaging.EXTRA_PARAM_IS_MEL;
import static com.mobiledgex.mel.MelMessaging.EXTRA_PARAM_UUID;
import static com.mobiledgex.mel.MelMessaging.EXTRA_PARAM_LOCATION_TOKEN;

public class MelStateReceiver extends BroadcastReceiver {
    private static final String TAG = "MelStateReceiver";

    // These are discovered system states.
    public static boolean isMelEnabled = false;
    public static @NotNull String client_location_token = "";
    public static @NotNull String uuid = "";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Action: " + intent.getAction());
        switch (intent.getAction()) {
            case ACTION_IS_MEL: {
                isMelEnabled = intent.getBooleanExtra(EXTRA_PARAM_IS_MEL, false);
                Log.d(TAG, "isMelEnabled: " + isMelEnabled);
                break;
            }
            case ACTION_SET_LOCATION_TOKEN: {
                client_location_token = intent.getStringExtra(EXTRA_PARAM_LOCATION_TOKEN);
                Log.d(TAG, "currentToken: " + client_location_token);
                break;
            }
            case ACTION_GET_UUID: {
                uuid = intent.getStringExtra(EXTRA_PARAM_UUID);
                Log.d(TAG, "Got Token: " + uuid);
                break;
            }
        }
    }
}
