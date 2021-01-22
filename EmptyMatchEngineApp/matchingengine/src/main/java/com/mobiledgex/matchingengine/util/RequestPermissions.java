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

package com.mobiledgex.matchingengine.util;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.mobiledgex.matchingengine.R;

import java.util.ArrayList;
import java.util.List;

/*!
 * Android UI Permissions helper. Activity contexts are needed.
 * \ingroup classes_util
 */
public class RequestPermissions {
    public static final int REQUEST_MULTIPLE_PERMISSION = 1001;
    public static final String[] permissions = new String[] { // Special Enhanced security requests.
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE, // Get Phone number
    };

    public static boolean permissionsDeniedShown = false;

    public List<String> getNeededPermissions(AppCompatActivity activity) {
        List<String> permissionsNeeded = new ArrayList<>();

        for (String pStr : permissions) {
            int result = ContextCompat.checkSelfPermission(activity, pStr);
            if (result != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(pStr);
            }
        }
        return permissionsNeeded;
    }

    public void requestMultiplePermissions(AppCompatActivity activity) {
        // Check which ones missing
        List<String> permissionsNeeded = getNeededPermissions(activity);

        String[] permissionArray;
        if (!permissionsNeeded.isEmpty()) {
            permissionArray = permissionsNeeded.toArray(new String[permissionsNeeded.size()]);
        } else {
            permissionArray = permissions; // Nothing was granted. Ask for all.
        }

        ActivityCompat.requestPermissions(activity, permissionArray, REQUEST_MULTIPLE_PERMISSION);
    }

    /*!
     * Keeps asking for permissions until granted or user checks box to not asked again.
     * \param activity (AppCompatActivity)
     * \param requestCode (int)
     * \param permissions (String[])
     * \param grantResults (int[])
     */
    public void onRequestPermissionsResult(AppCompatActivity activity, int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {

        int numGranted = 0;
        boolean showWarning = false;
        for (int i = 0; i < grantResults.length; i++) {
            if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                numGranted++;
            }
        }
        showWarning = (numGranted != grantResults.length);

        if (requestCode == REQUEST_MULTIPLE_PERMISSION) {
            for (int i = 0; i < grantResults.length; i++) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(activity, permissions[i])) {
                    new ConfirmationDialog().show(activity.getSupportFragmentManager(), "dialog");
                } else if (!permissionsDeniedShown && showWarning) {
                    // Rejected, or user asks to not ask again. This may still be critical for the
                    // application, so show once.
                    String msg = activity.getResources().getString(R.string.request_permission);
                    ErrorDialog.newInstance(msg).show(activity.getSupportFragmentManager(), "errorDialog");
                }
                return;
            }
        }
    }


    /*!
     * Shows OK/Cancel confirmation dialog about needed permissions.
     */
    public static class ConfirmationDialog extends DialogFragment {

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Fragment parent = this;
            return new AlertDialog.Builder(getActivity())
                    .setMessage(R.string.request_permission)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            requestPermissions(permissions, REQUEST_MULTIPLE_PERMISSION);
                            dialog.dismiss();
                        }
                    })
                    .setNegativeButton(android.R.string.cancel,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    dialog.dismiss();
                                }
                            })
                    .create();
        }
    }

    /*!
     * Shows an error message dialog.
     */
    public static class ErrorDialog extends DialogFragment {

        private static final String ARG_MESSAGE = "message";

        public static ErrorDialog newInstance(String message) {
            ErrorDialog dialog = new ErrorDialog();
            Bundle args = new Bundle();
            args.putString(ARG_MESSAGE, message);
            dialog.setArguments(args);
            return dialog;
        }

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Activity activity = getActivity();
            return new AlertDialog.Builder(activity)
                    .setMessage(getArguments().getString(ARG_MESSAGE))
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            permissionsDeniedShown = true;
                            dialogInterface.dismiss();
                        }
                    })
                    .create();
        }

    }
}
