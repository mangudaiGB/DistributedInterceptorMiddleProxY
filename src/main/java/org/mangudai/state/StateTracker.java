/*
 * Copyright (c) 2017. TechHive Software Labs, Inc - All Rights Reserved.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 * Proprietary and confidential
 */

package org.mangudai.state;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import javax.net.ssl.SSLSession;
import java.net.InetSocketAddress;

/**
 * Created by neo on 21/03/17.
 */
public interface StateTracker {
    void clientConnected(InetSocketAddress clientAddress);
    void clientSSLHandshakeSucceeded(InetSocketAddress clientAddress, SSLSession sslSession);
    void clientDisconnected(InetSocketAddress clientAddress, SSLSession sslSession);
    void bytesReceivedFromClient(ClientStateContext clientContext, int numberOfBytes);
    void requestReceivedFromClient(ClientStateContext clientContext, HttpRequest httpRequest);
    void bytesSentToClient(ClientStateContext clientContext, int numberOfBytes);
    void responseSentToClient(ClientStateContext clientContext, HttpResponse httpResponse);
    void bytesSentToServer(ServerStateContext serverContext, int numberOfBytes);
    void requestSentToServer(ServerStateContext serverContext, HttpRequest httpRequest);
    void bytesReceivedFromServer(ServerStateContext serverContext, int numberOfBytes);
    void responseReceivedFromServer(ServerStateContext serverContext, HttpResponse httpResponse);
}
