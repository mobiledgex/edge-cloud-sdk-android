/**
 * Copyright 2019 MobiledgeX, Inc. All rights and licenses reserved.
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

package com.mobiledgex.matchingengine;

import android.content.Context;
import android.location.Location;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;

import distributed_match_engine.AppClient;
import distributed_match_engine.LocOuterClass;
import distributed_match_engine.AppClient.QosPosition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MockUtils {
    private final static String TAG = "MockUtils";

    public static String getCarrierName(Context context) {
        TelephonyManager telManager = (TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);
        String networkOperatorName = telManager.getNetworkOperatorName();
        return networkOperatorName;
    }

    public static Location createLocation(String provider, double longitude, double latitude) {
        Location loc = new Location(provider);
        loc.setLongitude(longitude);
        loc.setLatitude(latitude);
        loc.setTime(System.currentTimeMillis());
        return loc;
    }

    public static ArrayList<QosPosition> createQosPositionArray(Location firstLocation, double direction_degrees, double totalDistanceKm, double increment) {
        // Create a bunch of locations to get QOS information. Server is to be proxied by the DME server.
        ArrayList<QosPosition> positions = new ArrayList<>();

        double kmPerDegreeLong = 111.32; // at Equator
        double kmPerDegreeLat = 110.57; // at Equator
        double addLongitude = (Math.cos(direction_degrees/(Math.PI/180)) * increment) / kmPerDegreeLong;
        double addLatitude = (Math.sin(direction_degrees/(Math.PI/180)) * increment) / kmPerDegreeLat;
        double i = 0d;
        double longitude = firstLocation.getLongitude();
        double latitude = firstLocation.getLatitude();

        long id = 1;

        while (i < totalDistanceKm) {
            longitude += addLongitude;
            latitude += addLatitude;
            i += increment;

            // FIXME: No time is attached to GPS location, as that breaks the server!
            LocOuterClass.Loc loc = LocOuterClass.Loc.newBuilder()
                    .setLongitude(longitude)
                    .setLatitude(latitude)
                    .build();

            QosPosition np = AppClient.QosPosition.newBuilder()
                    .setPositionid(id++)
                    .setGpsLocation(loc)
                    .build();

            positions.add(np);
        }

        return positions;
    }

    public static LocOuterClass.Loc androidToMessageLoc(Location location) {
        return LocOuterClass.Loc.newBuilder()
                .setLatitude(location.getLatitude())
                .setLongitude(location.getLongitude())
                .setTimestamp(LocOuterClass.Timestamp.newBuilder()
                        .setSeconds(System.currentTimeMillis()/1000)
                        .build())
                .build();
    }

    public static AppClient.RegisterClientRequest createMockRegisterClientRequest(String developerName,
                                                                           String appName,
                                                                           MatchingEngine me) {
        return AppClient.RegisterClientRequest.newBuilder()
                .setVer(0)
                .setDevName(developerName) // From signing certificate?
                .setAppName(appName)
                .setAppVers("1.0")
                .build();
    }

    public static AppClient.FindCloudletRequest createMockFindCloudletRequest(String networkOperatorName, MatchingEngine me, Location location) {
        return AppClient.FindCloudletRequest.newBuilder()
                .setVer(0)
                .setSessionCookie(me.getSessionCookie() == null ? "" : me.getSessionCookie())
                .setCarrierName(networkOperatorName)
                .setGpsLocation(androidToMessageLoc(location))
                .build();
    }

    public static AppClient.VerifyLocationRequest createMockVerifyLocationRequest(String networkOperatorName, MatchingEngine me, Location location) {

        return AppClient.VerifyLocationRequest.newBuilder()
                .setVer(0)
                .setSessionCookie(me.getSessionCookie() == null ? "" : me.getSessionCookie())
                .setCarrierName(networkOperatorName)
                .setGpsLocation(androidToMessageLoc(location))
                .setVerifyLocToken(me.getTokenServerToken() == null ? "" : me.getTokenServerToken())
                .build();

    }

    public static AppClient.GetLocationRequest createMockGetLocationRequest(String networkOperatorName, MatchingEngine me) {
        return AppClient.GetLocationRequest.newBuilder()
                .setVer(0)
                .setSessionCookie(me.getSessionCookie() == null ? "" : me.getSessionCookie())
                .setCarrierName(networkOperatorName)
                .build();
    }

    public static AppClient.AppInstListRequest createMockAppInstListRequest(String networkOperatorName, MatchingEngine me, Location location) {
        return AppClient.AppInstListRequest.newBuilder()
                .setVer(0)
                .setSessionCookie(me.getSessionCookie() == null ? "" : me.getSessionCookie())
                .setCarrierName(networkOperatorName)
                .setGpsLocation(androidToMessageLoc(location))
                .build();
    }

    public static AppClient.DynamicLocGroupRequest createMockDynamicLocGroupRequest(MatchingEngine me, String userData) {

        return AppClient.DynamicLocGroupRequest.newBuilder()
                .setVer(0)
                .setSessionCookie(me.getSessionCookie() == null ? "" : me.getSessionCookie())
                .setLgId(1)
                .setCommType(AppClient.DynamicLocGroupRequest.DlgCommType.DLG_SECURE)
                .setUserData(userData == null ? "" : userData)
                .build();
    }
}
