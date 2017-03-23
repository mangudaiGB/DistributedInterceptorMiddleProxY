/*
 * Copyright (c) 2017. TechHive Software Labs, Inc - All Rights Reserved.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 * Proprietary and confidential
 */

package org.mangudai.filter;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import org.mangudai.util.ServerProperties;
import org.mangudai.util.Utility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Date;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by neo on 21/03/17.
 */
public class HttpFilterHandler implements HttpFilter {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpFilterHandler.class);

    public static final HttpFilterHandler NOOP_FILTER = new HttpFilterHandler(null);
    private String filterName;
    private String fileName;
    private String fileRegularExpression;
    private Pattern pattern;
    protected final HttpRequest originalRequest;
    protected final ChannelHandlerContext ctx;

    public HttpFilterHandler(HttpRequest originalRequest) {
        this(originalRequest, null);
        this.filterName = "NOOP";
    }

    public HttpFilterHandler(HttpRequest originalRequest, ChannelHandlerContext ctx) {
        this.filterName = "filereplace";
        this.originalRequest = originalRequest;
        this.ctx = ctx;
//        Properties properties = new Properties();
//        properties.load(this.getClass().getClassLoader().getResourceAsStream("filter.properties"));
//        this.fileName = properties.getProperty("filter.filereplace.filename");
//        this.fileRegularExpression = properties.getProperty("filter.filereplace.expression");
        this.fileName = ServerProperties.INSTANCE.getProperty("filter.filereplace.filename");
        this.fileRegularExpression = ServerProperties.INSTANCE.getProperty("filter.filereplace.expression");
        pattern = Pattern.compile(fileRegularExpression);
//        } catch (IOException e) {
//            LOGGER.error("Error reading the properties file", e);
//        }

    }

    @Override
    public HttpResponse clientToProxyRequest(HttpObject httpObject) {
        if (httpObject != null && httpObject instanceof DefaultHttpRequest) {
            HttpRequest request = (HttpRequest) httpObject;
//            Matcher matcher = this.pattern.matcher(request.uri());
//            if (matcher.matches()) {
            if (request.uri().contains(fileRegularExpression)) {
                FullHttpResponse response = Utility.createFullHttpResponseJSFile(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, this.fileName);
                HttpHeaders.setDate(response, new Date());
                LOGGER.debug("Replacing the file:- " + fileRegularExpression + " with custom file from:- " + fileName);
                LOGGER.debug(request.toString());
                return response;
            }
        }
        return null;
    }

    @Override
    public HttpObject proxyToClientResponse(HttpObject httpObject) {
        return httpObject;
    }

    @Override
    public HttpResponse proxyToServerRequest(HttpObject httpObject) {
//        System.out.println("/**********Inside ProxyToServerRequest************/");
//        System.out.println(httpObject.toString());
//        System.out.println("/**********Done ProxyToServerRequest**************/");
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
//        System.out.println("/**********Inside ServerToProxyResponse************/");
//        if (httpObject instanceof HttpResponse) {
//            HttpResponse response = (HttpResponse) httpObject;
//            if (response.headers().get("Content-Type") != null && response.headers().get("Content-Type").equals("application/x-javascript")) {
//                System.out.println("Jibitesh: " + response.headers().get("Content-Type"));
//                System.out.println(httpObject.toString());
//            }
//        }
//        System.out.println("/**********Done ServerToProxyResponse************/");
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

    public String getFilterName() {
        return filterName;
    }

    public void setFilterName(String filterName) {
        this.filterName = filterName;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileRegularExpression() {
        return fileRegularExpression;
    }

    public void setFileRegularExpression(String fileRegularExpression) {
        this.fileRegularExpression = fileRegularExpression;
    }
}
