package com.mobiledgex.matchingengine;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;

import androidx.core.app.ActivityCompat;

public class DeviceInfoUtil {
    // Statics:
    static String getDeviceOS() {
        return "Android_Version_" + Build.VERSION.SDK_INT;
    }

    static String getDeviceModel() {
        return Build.MODEL;
    }

    // Dynamic values:
    /*!
     * \return dataNetworkType object.
     */
    static NetworkManager.DataNetworkType getDataNetworkType(Context context) {
        NetworkManager.DataNetworkType dataNetworkType = null;
        TelephonyManager telManager = (TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);
        if (telManager == null) {
            return dataNetworkType;
        }
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED) {
            int nType = telManager.getDataNetworkType(); // Type Name is not visible.
            dataNetworkType = NetworkManager.DataNetworkType.intMap.get(nType);
        }
        return dataNetworkType;
    }

    /*!
     * \return Abstract signal strength level 0-4. -1 if no telephony manager.
     */
   static int getSignalStrengthLevel(Context context) {
       int level = -1;
       TelephonyManager telManager = (TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);
       if (telManager == null) {
           return level;
       }

       if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
           SignalStrength s = telManager.getSignalStrength();
           level = s.getLevel();
       }
       return level;
    }
}
