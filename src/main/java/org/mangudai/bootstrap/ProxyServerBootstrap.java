/*
 * Copyright (c) 2017. TechHive Software Labs, Inc - All Rights Reserved.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 * Proprietary and confidential
 */

package org.mangudai.bootstrap;

import org.mangudai.authenticator.AuthenticatorHandler;
import org.mangudai.filter.HttpFilterFactory;
import org.mangudai.filter.HttpFilterHandler;
import org.mangudai.mitm.MITMHandler;
import org.mangudai.network.HostResolver;
import org.mangudai.server.HttpProxyServer;
import org.mangudai.ssl.SSLHandler;
import org.mangudai.state.StateTracker;
import org.mangudai.thread.ThreadPoolConfiguration;
import org.mangudai.transport.TransportProtocol;

import java.net.InetSocketAddress;

/**
 * Created by neo on 21/03/17.
 */
public interface ProxyServerBootstrap {
    int MAX_INITIAL_LINE_LENGTH_DEFAULT = 8192;
    int MAX_HEADER_SIZE_DEFAULT = 8192*2;
    int MAX_CHUNK_SIZE_DEFAULT = 8192*2;

    ProxyServerBootstrap withName(String name);
    ProxyServerBootstrap withTransportProtocol(TransportProtocol transportProtocol);
    ProxyServerBootstrap withAddress(InetSocketAddress address);
    ProxyServerBootstrap withPort(int port);
    ProxyServerBootstrap withAllowLocalOnly(boolean allowLocalOnly);
    ProxyServerBootstrap withSslHandler(SSLHandler sslHandler);
    ProxyServerBootstrap withAuthenticateSslClients(boolean authenticateSslClients);
    ProxyServerBootstrap withProxyAuthenticator(AuthenticatorHandler proxyAuthenticator);
    ProxyServerBootstrap withManInTheMiddle(MITMHandler mitmManager);
    ProxyServerBootstrap withFilterFactory(HttpFilterFactory filterFactory);
    ProxyServerBootstrap withUseDnsSec(boolean useDnsSec);
    ProxyServerBootstrap withTransparent(boolean transparent);
    ProxyServerBootstrap withIdleConnectionTimeout(int idleConnectionTimeout);
    ProxyServerBootstrap withConnectTimeout(int connectTimeout);
    ProxyServerBootstrap withServerResolver(HostResolver serverResolver);
    ProxyServerBootstrap plusStateTracker(StateTracker stateTracker);
    ProxyServerBootstrap withThrottling(long readThrottleBytesPerSecond, long writeThrottleBytesPerSecond);
    ProxyServerBootstrap withNetworkInterface(InetSocketAddress inetSocketAddress);
    ProxyServerBootstrap withMaxInitialLineLength(int maxInitialLineLength);
    ProxyServerBootstrap withMaxHeaderSize(int maxHeaderSize);
    ProxyServerBootstrap withMaxChunkSize(int maxChunkSize);
    ProxyServerBootstrap withAllowRequestToOriginServer(boolean allowRequestToOriginServer);
    ProxyServerBootstrap withProxyAlias(String alias);
    HttpProxyServer start();
    ProxyServerBootstrap withThreadPoolConfiguration(ThreadPoolConfiguration configuration);
}
