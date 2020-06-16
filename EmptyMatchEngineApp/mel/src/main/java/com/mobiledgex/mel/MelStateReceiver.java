/**
 * Copyright 2018-2020 MobiledgeX, Inc. All rights and licenses reserved.
 * MobiledgeX, Inc. 156 2nd Street #408, San Francisco, CA 94105
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mobiledgex.mel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;

import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;

import static com.mobiledgex.mel.MelMessaging.ACTION_SET_TOKEN;
import static com.mobiledgex.mel.MelMessaging.EXTRA_PARAM_TOKEN;

public class MelStateReceiver extends BroadcastReceiver {
    private static final String TAG = "MelStateReceiver";

    // These are discovered system states.
    public static String versionReg = "";
    public static String version;
    public static boolean isMelEnabled = false;
    public static @NotNull String client_token = "";
    public static @NotNull String appCookie = "";
    public static @NotNull String uid = "";

    static {
        updateRegistrationState();
    }
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Action: " + intent.getAction());
        switch (intent.getAction()) {
            case ACTION_SET_TOKEN: {
                client_token = intent.getStringExtra(EXTRA_PARAM_TOKEN);
                Log.d(TAG, "currentToken: " + client_token);
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
        if (verregArr[1].equals("1")) { // 1 = success, 2 = fail/wifi was enabled?
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
            if (!TextUtils.isEmpty(value)) {
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
