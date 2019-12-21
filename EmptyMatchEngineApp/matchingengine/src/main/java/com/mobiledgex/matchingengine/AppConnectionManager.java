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

import android.net.Network;

import com.squareup.okhttp.OkHttpClient;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocket;

import distributed_match_engine.AppClient;
import distributed_match_engine.AppClient.FindCloudletReply;
import distributed_match_engine.Appcommon.LProto;
import distributed_match_engine.Appcommon.AppPort;

public class AppConnectionManager {
    private final static String TAG = "AppConnectionManager";
    private NetworkManager mNetworkManager;
    private ExecutorService mExecutor;

    // TODO: Parameter to allow selecting between multiple subscription SIM cards.
    AppConnectionManager(NetworkManager networkManager, ExecutorService executor) {
        mNetworkManager = networkManager;
        mExecutor = executor;
    }

    /**
     * Utility function to determine if an edge server with the specified protocol exists in the
     * FindCloudletReply parameter.
     *
     * @param protocol
     * @param findCloudletReply
     * @return
     */
    boolean isConnectionTypeAvailable(AppClient.FindCloudletReply findCloudletReply, LProto protocol) {
        for (AppPort port : findCloudletReply.getPortsList()) {
            if (port.getProto() == protocol) {
                return true;
            }
        }
        return false;
    }

    /**
     * Utility function that returns a hashMap of TCP ports, hashed by the internal port in the \
     * reply parameter.
     * @param findCloudletReply the current locations FindCloudletReply
     * @return
     */
    public HashMap<Integer, AppPort> getUdpMap(AppClient.FindCloudletReply findCloudletReply) {
        String fqdn = findCloudletReply.getFqdn();

        if (fqdn != null && fqdn.length() == 0) {
            return null;
        }

        HashMap<Integer, AppPort> map = new HashMap<>();
        for (AppPort port : findCloudletReply.getPortsList()) {
            if (port.getProto() == LProto.L_PROTO_UDP) {
                map.put(port.getInternalPort(), port);
            }
        }
        return map;
    }

    /**
     * Utility function that returns a HashMap of TCP ports listed in the reply parameter.
     * @param findCloudletReply
     * @return
     */
    public HashMap<Integer, AppPort> getTCPMap(AppClient.FindCloudletReply findCloudletReply) {
        String fqdn = findCloudletReply.getFqdn();

        if (fqdn != null && fqdn.length() == 0) {
            return null;
        }

        HashMap<Integer, AppPort> map = new HashMap<>();
        for (AppPort port : findCloudletReply.getPortsList()) {
            if (port.getProto() == LProto.L_PROTO_TCP) {
                map.put(port.getInternalPort(), port);
            }
        }
        return map;
    }

    /**
     * Utility function that returns a hashMap of HTTP ports in the reply parameter.
     * @param findCloudletReply
     * @return
     */
    public HashMap<Integer, AppPort> getHttpMap(AppClient.FindCloudletReply findCloudletReply) {
        String fqdn = findCloudletReply.getFqdn();

        if (fqdn != null && fqdn.length() == 0) {
            return null;
        }

        HashMap<Integer, AppPort> map = new HashMap<>();
        for (AppPort port : findCloudletReply.getPortsList()) {
            if (port.getProto() == LProto.L_PROTO_HTTP) {
                map.put(port.getInternalPort(), port);
            }
        }
        return map;
    }

    /**
     * With the given FindCloudletReply, verify the AppPort and sub port in port range is good,
     * and return it.
     *
     * @param findCloudletReply
     * @param appPort
     * @param proto A supported protocol from LProto
     * @param portNum
     * @return appPort that matches spec.
     */
    public AppPort validatePublicPort(AppClient.FindCloudletReply findCloudletReply, AppPort appPort, LProto proto, int portNum) {
        AppPort found = null;
        for (AppPort aPort : findCloudletReply.getPortsList()) {
            // See if spec matches:
            if (aPort.getProto() != proto) {
                continue;
            }
            if (isValidPortNumber(appPort, portNum)) {
                found = aPort;
            }
        }
        return found;
    }

    private boolean isValidPortNumber(AppPort appPort, int port) {
        int publicPort = appPort.getPublicPort();
        int end = appPort.getEndPort();

        end = (end < publicPort) ? publicPort : end;

        if (port >= publicPort && port <= end) {
            return true;
        }
        return false;
    }

    /**
     * Returns a Future with a TCP SSL Socket from a default SSL Socket Factory, created on
     * a cellular data network interface, where available. The created socket is already connected.
     * If the timeout is 0, it will not timeout. Socket should be closed when the socket is no
     * longer needed.
     *
     * If a network goes down, sockets created using that network also gets killed.
     *
     * @param appPort This is the AppPort you want to connect to, based on the unmapped internal
     *                port number.
     * @param portNum This is the mapped public port number of where the AppInst is actually made
     *                available in a particular cloudlet region. It may not match the appPort
     *                internal port number.
     *                If <= 0, it defaults to the first public port.
     * @param timeoutMs timeout in milliseconds. 0 for infinite.
     * @return May be null if SSL socket factory cannot be created, or if it cannot find a cellular
     *         network.
     */
    Future<SSLSocket> getTcpSslSocket(final AppClient.FindCloudletReply findCloudletReply,
                                      final AppPort appPort, final int portNum, final int timeoutMs) {
        if (!mNetworkManager.isNetworkSwitchingEnabled()) {
            return null;
        }

        Callable<SSLSocket> sslSocketCallable = new Callable<SSLSocket>() {
            @Override
            public SSLSocket call() throws Exception {
                int timeout = timeoutMs < 0 ? 0 : timeoutMs;
                int aPortNum = portNum <= 0 ? appPort.getPublicPort() : portNum;

                AppPort foundPort = validatePublicPort(findCloudletReply, appPort, LProto.L_PROTO_TCP, aPortNum);
                if (foundPort == null) {
                    throw new InvalidPortException("Cannot find portNum [" + aPortNum + "] in AppPort list");
                }
                Network net = mNetworkManager.switchToCellularInternetNetworkBlocking();

                if (net == null && mNetworkManager.isNetworkSwitchingEnabled()) {
                    return null;
                } else {
                    net = mNetworkManager.getActiveNetwork();
                }
                MobiledgeXSSLSocketFactory mobiledgexSSLSocketFactory = (MobiledgeXSSLSocketFactory)MobiledgeXSSLSocketFactory.getDefault(net);
                if (mobiledgexSSLSocketFactory == null) {
                    return null;
                }

                SSLSocket socket;

                String host = foundPort.getFqdnPrefix() + findCloudletReply.getFqdn();
                socket = (SSLSocket)mobiledgexSSLSocketFactory.createSocket(host, aPortNum);
                socket.setSoTimeout(timeout);

                mNetworkManager.resetNetworkToDefault();
                return socket;
            }
        };

        return mExecutor.submit(sslSocketCallable);
    }

    /**
     * For early development only. This creates a connected Socket. Socket should be closed when the
     * socket is no longer needed.
     *
     * If a network goes down, sockets created using that network also gets killed.
     *
     * @param findCloudletReply A FindCloudletReply for the current location.
     * @param appPort This is the AppPort you want to connect to, based on the unmapped internal
     *                port number.
     * @param portNum This is the mapped public port number of where the AppInst is actually made
     *                available in a particular cloudlet region. It may not match the appPort \
     *                internal port number.
     *                If <= 0, it defaults to the public port.
     * @param timeoutMs timeout in milliseconds. 0 for infinite.
     * @return null can be returned if the network does not exist, or if network switching is disabled.
     */
    Future<Socket> getTcpSocket(final AppClient.FindCloudletReply findCloudletReply,
                                final AppPort appPort, final int portNum, final int timeoutMs) {
        if (!mNetworkManager.isNetworkSwitchingEnabled()) {
            return null;
        }

        Callable<Socket> socketCallable = new Callable<Socket>() {
            @Override
            public Socket call() throws Exception {
                int timeout = timeoutMs < 0 ? 0 : timeoutMs;
                int aPortNum = portNum <= 0 ? appPort.getPublicPort() : portNum;

                AppPort foundPort = validatePublicPort(findCloudletReply, appPort, LProto.L_PROTO_TCP, aPortNum);
                if (foundPort == null) {
                    throw new InvalidPortException("Cannot find portNum [" + aPortNum + "] in AppPort list");
                }

                Network net = mNetworkManager.switchToCellularInternetNetworkBlocking();

                if (net == null && mNetworkManager.isNetworkSwitchingEnabled()) {
                    return null;
                } else {
                    net = mNetworkManager.getActiveNetwork();
                }

                SocketFactory sf = net.getSocketFactory();
                Socket socket = sf.createSocket();

                String host = foundPort.getFqdnPrefix() + findCloudletReply.getFqdn();
                InetSocketAddress socketAddress = new InetSocketAddress(host, aPortNum);
                socket.connect(socketAddress);
                socket.setSoTimeout(timeout);

                mNetworkManager.resetNetworkToDefault();

                return socket;
            }
        };

        return mExecutor.submit(socketCallable);
    }

    /**
     * Returns a UDP socket bound and connected to cellular interface. Socket should be closed
     * when the socket is no longer needed.
     *
     * If a network goes down, sockets created using that network also gets killed.
     *
     * @param findCloudletReply A FindCloudletReply for the current location.
     * @param appPort This is the AppPort you want to connect to, based on the unmapped internal
     *                port number.
     * @param portNum This is the mapped public port number of where the AppInst is actually made
     *                available in a particular cloudlet region. It may not match the appPort
     *                internal port number.
     *                If <= 0, it defaults to the first public port.
     * @param timeoutMs timeout in milliseconds. 0 for infinite.
     * @return null can be returned if the network does not exist, or if network switching is disabled.
     */
    Future<DatagramSocket> getUdpSocket(final AppClient.FindCloudletReply findCloudletReply,
                                        final AppPort appPort, final int portNum, final int timeoutMs) {
        Callable<DatagramSocket> socketCallable = new Callable<DatagramSocket>() {
            @Override
            public DatagramSocket call() throws Exception {
                int timeout = timeoutMs < 0 ? 0 : timeoutMs;
                int aPortNum = portNum <= 0 ? appPort.getPublicPort() : portNum;

                AppPort foundPort = validatePublicPort(findCloudletReply, appPort, LProto.L_PROTO_TCP, aPortNum);
                if (foundPort == null) {
                    throw new InvalidPortException("Cannot find " + aPortNum + "in AppPort list");
                }

                Network net = mNetworkManager.switchToCellularInternetNetworkBlocking();

                if (net == null && mNetworkManager.isNetworkSwitchingEnabled()) {
                    return null;
                } else {
                    net = mNetworkManager.getActiveNetwork();
                }

                DatagramSocket ds = new DatagramSocket();
                net.bindSocket(ds);

                String host = foundPort.getFqdnPrefix() + findCloudletReply.getFqdn();
                InetSocketAddress socketAddress = new InetSocketAddress(host, aPortNum);
                ds.setSoTimeout(timeout);
                ds.connect(socketAddress);

                mNetworkManager.resetNetworkToDefault();

                return ds;
            }
        };

        return mExecutor.submit(socketCallable);
    }

    /**
     * Returns an HttpClient via OkHttpClient object, over a cellular network interface. Null is returned
     * if a requested cellular network is not available or not allowed.
     *
     * Convenience method. Get the network from NetworkManager, and set the SSLSocket factory
     * for different communication protocols.
     *
     * @param timeoutMs connect timeout in milliseconds.
     * @return null can be returned if the network does not exist, if network switching is disabled,
     *         of if a SSL Socket Factory cannot be created.
     */
    Future<OkHttpClient> getHttpClient(final long timeoutMs) {
        Callable<OkHttpClient> socketCallable = new Callable<OkHttpClient>() {
            @Override
            public OkHttpClient call() throws Exception {

                Network net = mNetworkManager.switchToCellularInternetNetworkBlocking();

                if (net == null && mNetworkManager.isNetworkSwitchingEnabled()) {
                    return null;
                } else {
                    net = mNetworkManager.getActiveNetwork();
                }
                MobiledgeXSSLSocketFactory mobiledgexSSLSocketFactory = (MobiledgeXSSLSocketFactory)MobiledgeXSSLSocketFactory.getDefault(net);
                if (mobiledgexSSLSocketFactory == null) {
                    return null;
                }

                OkHttpClient client = new OkHttpClient();
                client.setConnectTimeout(mNetworkManager.getTimeout(), TimeUnit.MILLISECONDS);

                SocketFactory sf = net.getSocketFactory();
                client.setSocketFactory(sf);

                client.setSslSocketFactory(mobiledgexSSLSocketFactory);
                client.setConnectTimeout(timeoutMs, TimeUnit.MILLISECONDS);

                mNetworkManager.resetNetworkToDefault();

                return client;
            }
        };

        return mExecutor.submit(socketCallable);
    }

    /**
     * Convenience method to create, from an AppPort, the http prefix URL of an particular
     * location's AppInst.
     *
     * FIXME: Using L_PROTO_TCP for HTTP.
     *
     * @param findCloudletReply A FindCloudletReply for the current location.
     * @param appPort This is the AppPort you want to connect to, based on the unmapped internal
     *                port number.
     * @param portNum This is the mapped public port number of where the AppInst is actually made
     *                available in a particular cloudlet region. It may not match the appPort
     *                internal port number.
     *                If <= 0, it defaults to the first public port.
     * @return completed URL, or null if invalid.
     */
    public String createUrl(FindCloudletReply findCloudletReply, AppPort appPort, int portNum) {
        int aPortNum = portNum <= 0 ? appPort.getPublicPort() : portNum;
        AppPort foundPort = validatePublicPort(findCloudletReply, appPort, LProto.L_PROTO_TCP, aPortNum);
        if (foundPort == null) {
            return null;
        }
        String url = "http://" +
                appPort.getFqdnPrefix() +
                findCloudletReply.getFqdn() +
                ":" +
                aPortNum +
                appPort.getPathPrefix();

        return url;
    };


}
