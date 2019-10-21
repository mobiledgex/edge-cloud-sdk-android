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
import java.net.Socket;
import java.security.SecureRandom;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import distributed_match_engine.AppClient;
import distributed_match_engine.Appcommon;

public class AppConnectionManager {
    private NetworkManager mNetworkManager;
    private ExecutorService mExecutor;

    MobiledgeXSSLSocketFactory mobiledgexSSLSocketFactory = null;

    public class HostAndPort {
        public String host;
        public int port;
        public HostAndPort(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    // TODO: Parameter to allow selecting between multiple subscription SIM cards.
    AppConnectionManager(NetworkManager networkManager, ExecutorService executor) {
        mNetworkManager = networkManager;
        mExecutor = executor;
    }

    /**
     * Utility function to determine if an edge server with the specificed protocol exists in the
     * FindCloudletReply parameter.
     *
     * @param protocol
     * @param findCloudletReply
     * @return
     */
    boolean isConnectionTypeAvailable(Appcommon.LProto protocol, AppClient.FindCloudletReply findCloudletReply) {
        for (Appcommon.AppPort port : findCloudletReply.getPortsList()) {
            if (port.getProto() == protocol) {
                return true;
            }
        }
        return false;
    }

    /**
     * Utility function that returns a list of TCP ports listed. This does NOT generate the
     * ports until endPort in the list, just the first instance.
     * @param findCloudletReply
     * @return
     */
    public ArrayList<HostAndPort> getUdpList(AppClient.FindCloudletReply findCloudletReply) {
        String fqdn = findCloudletReply.getFqdn();

        if (fqdn != null && fqdn.length() == 0) {
            return null;
        }

        ArrayList<HostAndPort> hostList = new ArrayList<HostAndPort>();
        for (Appcommon.AppPort port : findCloudletReply.getPortsList()) {
            if (port.getProto() == Appcommon.LProto.L_PROTO_UDP) {
                String prefix = port.getFqdnPrefix();
                String host;
                if (port.getFqdnPrefix() != null) {
                    host = prefix + fqdn;
                } else {
                    host = fqdn;
                }
                hostList.add(new HostAndPort(host, port.getPublicPort()));
            }
        }
        return hostList;
    }

    /**
     * Utility function that returns a list of TCP ports listed. This does NOT generate the
     * ports until endPort in the list, just the first instance.
     * @param findCloudletReply
     * @return
     */
    public ArrayList<HostAndPort> getTCPList(AppClient.FindCloudletReply findCloudletReply) {
        String fqdn = findCloudletReply.getFqdn();

        if (fqdn != null && fqdn.length() == 0) {
            return null;
        }

        ArrayList<HostAndPort> hostList = new ArrayList<HostAndPort>();
        for (Appcommon.AppPort port : findCloudletReply.getPortsList()) {
            if (port.getProto() == Appcommon.LProto.L_PROTO_TCP) {
                String prefix = port.getFqdnPrefix();
                String host;
                if (port.getFqdnPrefix() != null) {
                    host = prefix + fqdn;
                } else {
                    host = fqdn;
                }
                hostList.add(new HostAndPort(host, port.getPublicPort()));
            }
        }
        return hostList;
    }

    /**
     * Utility function that returns a list of HTTP ports listed. This does NOT generate the
     * ports until endPort in the list, just the first instance.
     * @param findCloudletReply
     * @return
     */
    public ArrayList<HostAndPort> getHttpList(AppClient.FindCloudletReply findCloudletReply) {
        String fqdn = findCloudletReply.getFqdn();

        if (fqdn != null && fqdn.length() == 0) {
            return null;
        }

        ArrayList<HostAndPort> hostList = new ArrayList<HostAndPort>();
        for (Appcommon.AppPort port : findCloudletReply.getPortsList()) {
            if (port.getProto() == Appcommon.LProto.L_PROTO_HTTP) {
                String prefix = port.getFqdnPrefix();
                String host;
                if (port.getFqdnPrefix() != null) {
                    host = prefix + fqdn;
                } else {
                    host = fqdn;
                }
                hostList.add(new HostAndPort(host, port.getPublicPort()));
            }
        }
        return hostList;
    }

    /**
     * Returns a Future with a TCP SSL Socket from a default SSL Socket Factory, created on
     * a cellular data network, where available.
     * If a network goes down, sockets created using that network also gets killed.
     * @param host hostname constructed from a FindCloudletReply result.
     * @param port port number should be the one from a FindCloudletReply result.
     * @return
     */
    Future<SSLSocket> getTcpSslSocket(final String host, final int port) {
        if (!mNetworkManager.isNetworkSwitchingEnabled()) {
            return null;
        }

        Callable<SSLSocket> sslSocketCallable = new Callable<SSLSocket>() {
            @Override
            public SSLSocket call() throws Exception {

                Future<Network> networkFuture = mNetworkManager.switchToCellularInternetNetworkFuture();
                Network net = networkFuture.get(); // Await.

                if (net == null && mNetworkManager.isNetworkSwitchingEnabled()) {
                    return null;
                }
                else { // Going with the default device network.
                    net = mNetworkManager.getActiveNetwork();
                }
                mobiledgexSSLSocketFactory = (MobiledgeXSSLSocketFactory)MobiledgeXSSLSocketFactory.getDefault(net);

                if (host == null || port < 0) {
                    // not connected socket.
                    return (SSLSocket)mobiledgexSSLSocketFactory.createSocket();
                } else {
                    return (SSLSocket)mobiledgexSSLSocketFactory.createSocket(host, port);
                }
            }
        };

        return mExecutor.submit(sslSocketCallable);
    }

    /**
     * For early development only. Edge cloudlets have SSL sockets by default.
     * @param host
     * @param port
     * @return
     */
    Future<Socket> getTcpSocket(final String host, final int port) {
        Callable<Socket> socketCallable = new Callable<Socket>() {
            @Override
            public Socket call() throws Exception {

                Future<Network> networkFuture = mNetworkManager.switchToCellularInternetNetworkFuture();
                Network net = networkFuture.get(); // Blocks.

                if (net == null && mNetworkManager.isNetworkSwitchingEnabled()) {
                    return null;
                }
                else { // Going with the default device network.
                    net = mNetworkManager.getActiveNetwork();
                }

                SocketFactory sf = net.getSocketFactory();
                if (host == null || port < 0) {
                    // not connected socket.
                    Socket socket = sf.createSocket();
                    net.bindSocket(socket);
                    return socket;
                } else {
                    // Connected socket.
                    Socket socket = sf.createSocket(host, port);
                    return socket;
                }
            }
        };

        return mExecutor.submit(socketCallable);
    }

    Future<DatagramSocket> getUdpSocket() {
        Callable<DatagramSocket> socketCallable = new Callable<DatagramSocket>() {
            @Override
            public DatagramSocket call() throws Exception {

                Future<Network> networkFuture = mNetworkManager.switchToCellularInternetNetworkFuture();
                Network net = networkFuture.get(); // Blocks.

                if (net == null && mNetworkManager.isNetworkSwitchingEnabled()) {
                    return null;
                }
                else { // Going with the default device network.
                    net = mNetworkManager.getActiveNetwork();
                }

                DatagramSocket ds = new DatagramSocket();
                net.bindSocket(ds);

                return ds;
            }
        };

        return mExecutor.submit(socketCallable);
    }

    /**
     * Http Connection via OkHttpClient object, over a cellular network interface. Null is returned
     * if a requested cellular network is not available, not allowed. It is connected immediately.
     *
     * Convenience method. Get the network from NetworkManager, and set the SSLSocket factory
     * for different communication protocols.
     * @return
     */
    Future<OkHttpClient> getHttpClient() {
        if (!mNetworkManager.isNetworkSwitchingEnabled()) {
            return null;
        }

        Callable<OkHttpClient> socketCallable = new Callable<OkHttpClient>() {
            @Override
            public OkHttpClient call() throws Exception {

                Future<Network> networkFuture = mNetworkManager.switchToCellularInternetNetworkFuture();
                Network net = networkFuture.get(); // Blocks.

                if (net == null && mNetworkManager.isNetworkSwitchingEnabled()) {
                    return null;
                }
                else { // Going with the default device network.
                    net = mNetworkManager.getActiveNetwork();
                }

                OkHttpClient client = new OkHttpClient();
                client.setConnectTimeout(mNetworkManager.getTimeout(), TimeUnit.MILLISECONDS);

                SocketFactory sf = net.getSocketFactory();
                client.setSocketFactory(sf);

                mobiledgexSSLSocketFactory.setNetwork(net);
                client.setSslSocketFactory(mobiledgexSSLSocketFactory);

                return client;
            }
        };

        return mExecutor.submit(socketCallable);
    }

}
