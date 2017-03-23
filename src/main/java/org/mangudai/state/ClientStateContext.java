/*
 * Copyright (c) 2017. TechHive Software Labs, Inc - All Rights Reserved.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 * Proprietary and confidential
 */

package org.mangudai.state;

import org.mangudai.connection.ClientConnection;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import java.net.InetSocketAddress;

/**
 * Created by neo on 21/03/17.
 */
public class ClientStateContext {
    private final InetSocketAddress clientAddress;
    private final SSLSession clientSSLSession;

    public ClientStateContext(ClientConnection clientConnection) {
        this.clientAddress = clientConnection.getClientAddress();
        SSLEngine sslEngine = clientConnection.getSSLHandler();
        this.clientSSLSession = sslEngine != null ? sslEngine.getSession() : null;
    }

    public InetSocketAddress getClientAddress() {
        return clientAddress;
    }

    public SSLSession getClientSSLSession() {
        return clientSSLSession;
    }
}
