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

package com.mobiledgex.matchingengine;

import android.net.Network;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.NoSuchAlgorithmException;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

/*!
 * A MobiledgeX SSLSocketFactory that originates from a particular network interface that is
 * powered up. This is a thin wrapper over the default SSLContext's SSLSocketFactory "SocketFactory"
 * implementation.
 * \ingroup classes
 */
public final class MobiledgeXSSLSocketFactory extends SSLSocketFactory {

    private static Network mNetwork;
    private static SSLContext mSSLContext = null;
    private static MobiledgeXSSLSocketFactory singleton;

    public class MissingNetworkException extends Exception {
        public MissingNetworkException(String msg) {
            super(msg);
        }

        public MissingNetworkException(String msg, Exception innerException) {
            super(msg, innerException);
        }
    }

    private MobiledgeXSSLSocketFactory(Network network) {
        mNetwork = network;
    }

    public static Network setNetwork(Network network) {
        mNetwork = network;
        return mNetwork;
    }

    /*!
     * Check return. May be null if SSL is missing.
     * \return SocketFactory
     */
    public static synchronized SocketFactory getDefault(Network network) {
        if (mSSLContext == null) {
            try {
                mSSLContext = SSLContext.getDefault();
            } catch (NoSuchAlgorithmException nsae){
                // SSL is missing from platform!
                nsae.printStackTrace();
                mSSLContext = null;
            }
        }

        mNetwork = network; // Replace.
        if (singleton == null) {
            singleton = new MobiledgeXSSLSocketFactory(network);
        }
        return singleton;
    }

    /*!
     * Returns a socket, bound to a particular network. It is not connected to an endpoint.
     * \return Socket
     * \exception IOException
     */
    private Socket createNetworkBoundSocket() throws IOException {
        if (mNetwork == null) {
            throw new IOException(
                "MobiledgeXSSLSocketFactory Network is not set",
                new MissingNetworkException("Network required for creating network bound sockets.")
            );
        }
        Socket socket = new Socket();
        mNetwork.bindSocket(socket);
        return socket;
    }

    /*!
     * Returns a network bound SSLSocket
     * \return Socket
     * \exception IOException
     */
    @Override
    public Socket createSocket() throws IOException {
        return createNetworkBoundSocket();
    }

    /*!
     * Returns a network bound SSLSocket
     * \param host (String)
     * \param port (int)
     * \return Socket
     * \exception IOException
     */
    @Override
    public Socket createSocket(String host, int port) throws IOException {
        Socket raw = createNetworkBoundSocket();
        // Now connect(), then upgrade to SSL:
        raw.connect(new InetSocketAddress(host, port));
        Socket socket = createSocket(raw, host, port, true);
        return socket;
    }

    /*!
     * Returns a network bound SSLSocket
     * \param address (InetAddress)
     * \param port (int)
     * \return Socket
     * \exception IOException
     */
    @Override
    public Socket createSocket(InetAddress address, int port)
            throws IOException {
        Socket raw = createNetworkBoundSocket();
        String host = address.getHostName(); // Reverse lookup. SSL host verification needed.
        raw.connect(new InetSocketAddress(host, port));
        Socket socket = createSocket(raw, host, port, true);
        return socket;
    }

    /*!
     * Returns a network bound SSLSocket. Interface override requirement. Conflicting goals. Client
     * address is that of the bound network.
     * \param host (String)
     * \param port (int)
     * \param clientAddress (InetAddress)
     * \param clientPort (int)
     * \return Socket
     * \exception IOException
     */
    @Override
    public Socket createSocket(String host, int port,
                               InetAddress clientAddress, int clientPort)
            throws IOException {
        return createSocket(host, port);
    }

    /*!
     * Returns a network bound SSLSocket. Interface override requirement. Conflicting goals. Client
     * address is that of the bound network.
     * \param address (InetAddress)
     * \param port (int)
     * \param clientAddress (InetAddress)
     * \param clientPort (int)
     * \return Socket
     * \exception IOException
     */
    @Override
    public Socket createSocket(InetAddress address, int port,
                               InetAddress clientAddress, int clientPort)
            throws IOException {
        return createSocket(address, port);
    }

    /*!
     * Returns a network bound SSLSocket
     * \param socket (Socket)
     * \param host (String)
     * \param port (int)
     * \param autoClose (boolean)
     * \return Socket
     * \exception IOException
     */
    @Override
    public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
        if (!socket.isBound()) {
            throw new IOException("Socket must be bound to a network first before upgrade to SSL!");
        }
        return mSSLContext.getSocketFactory().createSocket(socket, host, port, autoClose);
    }

    // Passthroughs:
    @Override
    public String [] getDefaultCipherSuites() {
        return mSSLContext.getServerSocketFactory().getDefaultCipherSuites();
    }

    @Override
    public String [] getSupportedCipherSuites() {
        return mSSLContext.getServerSocketFactory().getSupportedCipherSuites();
    }
}
