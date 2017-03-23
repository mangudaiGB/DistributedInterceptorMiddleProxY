/*
 * Copyright (c) 2017. TechHive Software Labs, Inc - All Rights Reserved.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 * Proprietary and confidential
 */

package org.mangudai.server;

import org.mangudai.bootstrap.ProxyServerBootstrap;

import java.net.InetSocketAddress;

/**
 * Created by neo on 21/03/17.
 */
public interface HttpProxyServer {
    int getIdleConnectionTimeout();
    void setIdleConnectionTimeout(int idleConnectionTimeout);
    int getConnectTimeout();
    void setConnectTimeout(int connectTimeoutMs);
    ProxyServerBootstrap clone();
    void stop();
    void abort();
    InetSocketAddress getListenAddress();
    void setThrottle(long readThrottleBytesPerSecond, long writeThrottleBytesPerSecond);
}
