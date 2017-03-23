/*
 * Copyright (c) 2017. TechHive Software Labs, Inc - All Rights Reserved.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 * Proprietary and confidential
 */

package org.mangudai.bootstrap;

import org.mangudai.authenticator.AuthenticatorHandler;
import org.mangudai.filter.HttpFilterFactory;
import org.mangudai.mitm.MITMHandler;
import org.mangudai.network.DefaultHostResolver;
import org.mangudai.network.DnsSecServerResolver;
import org.mangudai.network.HostResolver;
import org.mangudai.server.DefaultHttpProxyServer;
import org.mangudai.server.HttpProxyServer;
import org.mangudai.ssl.SSLHandler;
import org.mangudai.state.StateTracker;
import org.mangudai.thread.ServerGroup;
import org.mangudai.thread.ThreadPoolConfiguration;
import org.mangudai.transport.TransportProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Created by neo on 21/03/17.
 */
public class DefaultProxyServerBootstrap implements ProxyServerBootstrap {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultProxyServerBootstrap.class);

    private String name = "DimpyProxy";
    private ServerGroup serverGroup = null;
    private TransportProtocol transportProtocol = TransportProtocol.TCP;
    private InetSocketAddress requestedAddress;
    private int port = 8080;
    private boolean allowLocalOnly = true;
    private SSLHandler sslHandler = null;
    private boolean authenticateSslClients = true;
    private AuthenticatorHandler authenticatorHandler = null;
    private MITMHandler mitmHandler = null;
    private HttpFilterFactory filterFactory = new HttpFilterFactory();
    private boolean transparent = false;
    private int idleConnectionTimeout = 70;
    private Collection<StateTracker> stateTrackerQueue = new ConcurrentLinkedQueue<>();
    private int connectTimeout = 40000;
    private HostResolver serverResolver = new DefaultHostResolver();
    private long readThrottleBytesPerSecond;
    private long writeThrottleBytesPerSecond;
    private InetSocketAddress localAddress;
    private String proxyAlias;
    private int clientAcceptorThreads = ServerGroup.DEFAULT_INCOMING_ACCEPTOR_THREADS;
    private int clientWorkerThreads = ServerGroup.DEFAULT_INCOMING_WORKER_THREADS;
    private int serverWorkerThreads = ServerGroup.DEFAULT_OUTGOING_WORKER_THREADS;
    private int maxInitialLineLength = MAX_INITIAL_LINE_LENGTH_DEFAULT;
    private int maxHeaderSize = MAX_HEADER_SIZE_DEFAULT;
    private int maxChunkSize = MAX_CHUNK_SIZE_DEFAULT;
    private boolean allowRequestToOriginServer = false;

    public DefaultProxyServerBootstrap() {
    }

    public DefaultProxyServerBootstrap(
            ServerGroup serverGroup,
            TransportProtocol transportProtocol,
            InetSocketAddress requestedAddress,
            SSLHandler sslHandler,
            boolean authenticateSslClients,
            AuthenticatorHandler authenticatorHandler,
            MITMHandler mitmHandler,
            HttpFilterFactory filterFactory,
            boolean transparent, int idleConnectionTimeout,
            Collection<StateTracker> stateTrackerQueue,
            int connectTimeout, HostResolver serverResolver,
            long readThrottleBytesPerSecond,
            long  writeThrottleBytesPerSecond,
            InetSocketAddress localAddress,
            String proxyAlias,
            int maxInitialLineLength,
            int maxHeaderSize,
            int maxChunkSize,
            boolean allowRequestToOriginServer) {
        this.serverGroup = serverGroup;
        this.transportProtocol = transportProtocol;
        this.requestedAddress = requestedAddress;
        this.port = requestedAddress.getPort();
        this.sslHandler = sslHandler;
        this.authenticateSslClients = authenticateSslClients;
        this.authenticatorHandler = authenticatorHandler;
        this.mitmHandler = mitmHandler;
        this.filterFactory = filterFactory;
        this.transparent = transparent;
        this.idleConnectionTimeout = idleConnectionTimeout;
        if (stateTrackerQueue != null) {
            this.stateTrackerQueue.addAll(stateTrackerQueue);
        }
        this.connectTimeout = connectTimeout;
        this.serverResolver = serverResolver;
        this.readThrottleBytesPerSecond = readThrottleBytesPerSecond;
        this.writeThrottleBytesPerSecond = writeThrottleBytesPerSecond;
        this.localAddress = localAddress;
        this.proxyAlias = proxyAlias;
        this.maxInitialLineLength = maxInitialLineLength;
        this.maxHeaderSize = maxHeaderSize;
        this.maxChunkSize = maxChunkSize;
        this.allowRequestToOriginServer = allowRequestToOriginServer;
    }

//    public DefaultProxyServerBootstrap(Properties props) {
//        this.withUseDnsSec(ProxyUtils.extractBooleanDefaultFalse(props, "dnssec"));
//        this.transparent = ProxyUtils.extractBooleanDefaultFalse(props, "transparent");
//        this.idleConnectionTimeout = ProxyUtils.extractInt(props, "idle_connection_timeout");
//        this.connectTimeout = ProxyUtils.extractInt(props, "connect_timeout", 0);
//        this.maxInitialLineLength = ProxyUtils.extractInt(props, "max_initial_line_length", MAX_INITIAL_LINE_LENGTH_DEFAULT);
//        this.maxHeaderSize = ProxyUtils.extractInt(props, "max_header_size", MAX_HEADER_SIZE_DEFAULT);
//        this.maxChunkSize = ProxyUtils.extractInt(props, "max_chunk_size", MAX_CHUNK_SIZE_DEFAULT);
//    }

    @Override
    public ProxyServerBootstrap withName(String name) {
        this.name = name;
        return this;
    }

    @Override
    public ProxyServerBootstrap withTransportProtocol(
            TransportProtocol transportProtocol) {
        this.transportProtocol = transportProtocol;
        return this;
    }

    @Override
    public ProxyServerBootstrap withAddress(InetSocketAddress address) {
        this.requestedAddress = address;
        return this;
    }

    @Override
    public ProxyServerBootstrap withPort(int port) {
        this.requestedAddress = null;
        this.port = port;
        return this;
    }

    @Override
    public ProxyServerBootstrap withNetworkInterface(InetSocketAddress inetSocketAddress) {
        this.localAddress = inetSocketAddress;
        return this;
    }

    @Override
    public ProxyServerBootstrap withProxyAlias(String alias) {
        this.proxyAlias = alias;
        return this;
    }

    @Override
    public ProxyServerBootstrap withAllowLocalOnly(
            boolean allowLocalOnly) {
        this.allowLocalOnly = allowLocalOnly;
        return this;
    }

    @Override
    public ProxyServerBootstrap withSslHandler(SSLHandler sslHandler) {
        this.sslHandler = sslHandler;
        if (this.mitmHandler != null) {
            LOGGER.warn("Enabled encrypted inbound connections with man in the middle. "
                    + "These are mutually exclusive - man in the middle will be disabled.");
            this.mitmHandler = null;
        }
        return this;
    }

    @Override
    public ProxyServerBootstrap withAuthenticateSslClients(
            boolean authenticateSslClients) {
        this.authenticateSslClients = authenticateSslClients;
        return this;
    }

    @Override
    public ProxyServerBootstrap withProxyAuthenticator(AuthenticatorHandler authenticatorHandler) {
        this.authenticatorHandler = authenticatorHandler;
        return this;
    }

//    @Override
//    public ProxyServerBootstrap withChainProxyManager(ChainedProxyManager chainProxyManager) {
//        this.chainProxyManager = chainProxyManager;
//        return this;
//    }

    @Override
    public ProxyServerBootstrap withManInTheMiddle(MITMHandler mitmHandler) {
        this.mitmHandler = mitmHandler;
        if (this.sslHandler != null) {
            LOGGER.warn("Enabled man in the middle with encrypted inbound connections. "
                    + "These are mutually exclusive - encrypted inbound connections will be disabled.");
            this.sslHandler = null;
        }
        return this;
    }

    @Override
    public ProxyServerBootstrap withFilterFactory(HttpFilterFactory filterFactory) {
        this.filterFactory = filterFactory;
        return this;
    }

    @Override
    public ProxyServerBootstrap withUseDnsSec(boolean useDnsSec) {
        if (useDnsSec) {
            this.serverResolver = new DnsSecServerResolver();
        } else {
            this.serverResolver = new DefaultHostResolver();
        }
        return this;
    }

    @Override
    public ProxyServerBootstrap withTransparent(boolean transparent) {
        this.transparent = transparent;
        return this;
    }

    @Override
    public ProxyServerBootstrap withIdleConnectionTimeout(int idleConnectionTimeout) {
        this.idleConnectionTimeout = idleConnectionTimeout;
        return this;
    }

    @Override
    public ProxyServerBootstrap withConnectTimeout(int connectTimeout) {
        this.connectTimeout = connectTimeout;
        return this;
    }

    @Override
    public ProxyServerBootstrap withServerResolver(HostResolver serverResolver) {
        this.serverResolver = serverResolver;
        return this;
    }

    @Override
    public ProxyServerBootstrap plusStateTracker(StateTracker stateTracker) {
        stateTrackerQueue.add(stateTracker);
        return this;
    }

    @Override
    public ProxyServerBootstrap withThrottling(long readThrottleBytesPerSecond, long writeThrottleBytesPerSecond) {
        this.readThrottleBytesPerSecond = readThrottleBytesPerSecond;
        this.writeThrottleBytesPerSecond = writeThrottleBytesPerSecond;
        return this;
    }

    @Override
    public ProxyServerBootstrap withMaxInitialLineLength(int maxInitialLineLength){
        this.maxInitialLineLength = maxInitialLineLength;
        return this;
    }

    @Override
    public ProxyServerBootstrap withMaxHeaderSize(int maxHeaderSize){
        this.maxHeaderSize = maxHeaderSize;
        return this;
    }

    @Override
    public ProxyServerBootstrap withMaxChunkSize(int maxChunkSize){
        this.maxChunkSize = maxChunkSize;
        return this;
    }

    @Override
    public ProxyServerBootstrap withAllowRequestToOriginServer(boolean allowRequestToOriginServer) {
        this.allowRequestToOriginServer = allowRequestToOriginServer;
        return this;
    }

    @Override
    public HttpProxyServer start() {
        return build().start();
    }

    @Override
    public ProxyServerBootstrap withThreadPoolConfiguration(ThreadPoolConfiguration configuration) {
        this.clientAcceptorThreads = configuration.getAcceptorThreads();
        this.clientWorkerThreads = configuration.getClientToProxyWorkerThreads();
        this.serverWorkerThreads = configuration.getProxyToServerWorkerThreads();
        return this;
    }

    private DefaultHttpProxyServer build() {
        final ServerGroup serverGroup;

        if (this.serverGroup != null) {
            serverGroup = this.serverGroup;
        }
        else {
            serverGroup = new ServerGroup(name, clientAcceptorThreads, clientWorkerThreads, serverWorkerThreads);
        }

        return new DefaultHttpProxyServer(serverGroup,
                transportProtocol, determineListenAddress(),
                sslHandler, authenticateSslClients,
                authenticatorHandler, mitmHandler,
                filterFactory, transparent,
                idleConnectionTimeout, stateTrackerQueue, connectTimeout,
                serverResolver, readThrottleBytesPerSecond, writeThrottleBytesPerSecond,
                localAddress, proxyAlias, maxInitialLineLength, maxHeaderSize, maxChunkSize,
                allowRequestToOriginServer);
    }

    private InetSocketAddress determineListenAddress() {
        if (requestedAddress != null) {
            return requestedAddress;
        } else {
            if (allowLocalOnly) {
                return new InetSocketAddress("127.0.0.1", port);
            } else {
                return new InetSocketAddress(port);
            }
        }
    }
}
