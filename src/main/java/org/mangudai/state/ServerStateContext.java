/*
 * Copyright (c) 2017. TechHive Software Labs, Inc - All Rights Reserved.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 * Proprietary and confidential
 */

package org.mangudai.state;

import org.mangudai.connection.ClientConnection;
import org.mangudai.connection.ServerConnection;

import javax.net.ssl.SSLSession;
import java.net.InetSocketAddress;

/**
 * Created by neo on 21/03/17.
 */
public class ServerStateContext extends ClientStateContext {
    private final String serverHostAndPort;

    public ServerStateContext(ClientConnection clientConnection, ServerConnection serverConnection) {
        super(clientConnection);
        this.serverHostAndPort = serverConnection.getServerHostAndPort();
    }
}
