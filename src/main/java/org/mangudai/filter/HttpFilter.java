/*
 * Copyright (c) 2017. TechHive Software Labs, Inc - All Rights Reserved.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 * Proprietary and confidential
 */

package org.mangudai.filter;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;

import java.net.InetSocketAddress;

/**
 * Created by neo on 21/03/17.
 */
public interface HttpFilter {
    HttpResponse clientToProxyRequest(HttpObject httpObject);
    HttpObject proxyToClientResponse(HttpObject httpObject);
    HttpResponse proxyToServerRequest(HttpObject httpObject);
    void serverConnectionQueued();
    InetSocketAddress serverResolutionStarted(String resolvingServerHostAndPort);
    void serverResolutionFailed(String hostAndPort);
    void serverResolutionSucceeded(String serverHostAndPort, InetSocketAddress resolvedRemoteAddress);
    void serverConnectionStarted();
    void serverConnectionSSLHandshakeStarted();
    void serverConnectionFailed();
    void serverConnectionSucceeded(ChannelHandlerContext serverCtx);
    void serverRequestSending();
    void serverRequestSent();
    HttpObject serverToProxyResponse(HttpObject httpObject);
    void serverResponseTimedOut();
    void serverResponseReceiving();
    void serverResponseReceived();
}
