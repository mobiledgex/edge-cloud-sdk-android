package com.mobiledgex.mel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.jetbrains.annotations.NotNull;

import static com.mobiledgex.mel.MelMessaging.ACTION_GET_TOKEN;
import static com.mobiledgex.mel.MelMessaging.ACTION_IS_MEL;
import static com.mobiledgex.mel.MelMessaging.ACTION_SET_TOKEN;
import static com.mobiledgex.mel.MelMessaging.EXTRA_PARAM_IS_MEL;
import static com.mobiledgex.mel.MelMessaging.EXTRA_PARAM_TOKEN;


public class MelStateReceiver extends BroadcastReceiver {
    private static final String TAG = "MelStateReceiver";

    // These are discovered system states.
    public static boolean isMelEnabled = false;
    public static @NotNull String token = "";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Action: " + intent.getAction());
        switch (intent.getAction()) {
            case ACTION_IS_MEL: {
                isMelEnabled = intent.getBooleanExtra(EXTRA_PARAM_IS_MEL, false);
                Log.d(TAG, "isMelEnabled: " + isMelEnabled);
                break;
            }
            case ACTION_SET_TOKEN: {
                token = intent.getStringExtra(EXTRA_PARAM_TOKEN);
                Log.d(TAG, "currentToken: " + token);
                break;
            }
            case ACTION_GET_TOKEN: {
                String token = intent.getStringExtra(EXTRA_PARAM_TOKEN);
                Log.d(TAG, "Got Token: " + token);
                break;
            }
        }
    }
}
