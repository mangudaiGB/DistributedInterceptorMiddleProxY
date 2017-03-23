/*
 * Copyright (c) 2017. TechHive Software Labs, Inc - All Rights Reserved.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 * Proprietary and confidential
 */

package org.mangudai.filter;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import java.net.InetSocketAddress;

/**
 * Created by neo on 21/03/17.
 */
public class HttpFilterHandler implements HttpFilter {
    public static final HttpFilterHandler NOOP_FILTER = new HttpFilterHandler(null);
    private String filterName;

    protected final HttpRequest originalRequest;
    protected final ChannelHandlerContext ctx;

    public HttpFilterHandler(HttpRequest originalRequest) {
        this(originalRequest, null);
        this.filterName = "NOOP";
    }

    public HttpFilterHandler(HttpRequest originalRequest, ChannelHandlerContext ctx) {
        this.filterName = "JIBI";
        this.originalRequest = originalRequest;
        this.ctx = ctx;
    }

    @Override
    public HttpResponse clientToProxyRequest(HttpObject httpObject) {
        return null;
    }

    @Override
    public HttpObject proxyToClientResponse(HttpObject httpObject) {
        return httpObject;
    }

    @Override
    public HttpResponse proxyToServerRequest(HttpObject httpObject) {
        return null;
    }

    @Override
    public void serverConnectionQueued() {

    }

    @Override
    public InetSocketAddress serverResolutionStarted(String resolvingServerHostAndPort) {
        return null;
    }

    @Override
    public void serverResolutionFailed(String hostAndPort) {

    }

    @Override
    public void serverResolutionSucceeded(String serverHostAndPort, InetSocketAddress resolvedRemoteAddress) {

    }

    @Override
    public void serverConnectionStarted() {

    }

    @Override
    public void serverConnectionSSLHandshakeStarted() {

    }

    @Override
    public void serverConnectionFailed() {

    }

    @Override
    public void serverConnectionSucceeded(ChannelHandlerContext serverCtx) {

    }

    @Override
    public void serverRequestSending() {

    }

    @Override
    public void serverRequestSent() {
//        System.out.println("I have got the server response");
    }

    @Override
    public HttpObject serverToProxyResponse(HttpObject httpObject) {
        if (httpObject instanceof HttpResponse) {
            HttpResponse response = (HttpResponse) httpObject;
            if (response.headers().get("Content-Type") != null && response.headers().get("Content-Type").equals("application/x-javascript")) {
                System.out.println("Jibitesh: " + response.headers().get("Content-Type"));
                System.out.println(httpObject.toString());
            }
        }

        return httpObject;
    }

    @Override
    public void serverResponseTimedOut() {

    }

    @Override
    public void serverResponseReceiving() {

    }

    @Override
    public void serverResponseReceived() {

    }
}
