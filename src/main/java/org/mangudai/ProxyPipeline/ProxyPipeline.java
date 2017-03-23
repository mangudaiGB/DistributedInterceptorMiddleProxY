/*
 * Copyright (c) 2017. TechHive Software Labs, Inc - All Rights Reserved.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 * Proprietary and confidential
 */

package org.mangudai.ProxyPipeline;

import io.netty.handler.codec.http.HttpObject;
import org.mangudai.ssl.SSLHandler;
import org.mangudai.transport.TransportProtocol;

import java.net.InetSocketAddress;

/**
 * Created by neo on 21/03/17.
 */
public interface ProxyPipeline extends SSLHandler {
    InetSocketAddress getNextProxyAddress();
    InetSocketAddress getLocalAddress();
    TransportProtocol getTransportProtocol();
    boolean requiresEncryption();
    void filterRequest(HttpObject httpObject);
    void connectionSucceeded();
    void connectionFailed(Throwable cause);
    void disconnected();
}
