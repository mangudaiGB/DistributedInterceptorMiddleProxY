/*
 * Copyright (c) 2017. TechHive Software Labs, Inc - All Rights Reserved.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 * Proprietary and confidential
 */

package org.mangudai.server;

import io.netty.bootstrap.*;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.ChannelGroupFuture;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.udt.nio.NioUdtProvider;
import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.mangudai.authenticator.AuthenticatorHandler;
import org.mangudai.bootstrap.DefaultProxyServerBootstrap;
import org.mangudai.bootstrap.ProxyServerBootstrap;
import org.mangudai.connection.ClientConnection;
import org.mangudai.filter.HttpFilterFactory;
import org.mangudai.mitm.MITMHandler;
import org.mangudai.network.HostResolver;
import org.mangudai.ssl.SSLHandler;
import org.mangudai.state.StateTracker;
import org.mangudai.thread.ServerGroup;
import org.mangudai.transport.TransportProtocol;
import org.mangudai.transport.UnknownTransportProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created by neo on 21/03/17.
 */
public class DefaultHttpProxyServer implements HttpProxyServer {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultHttpProxyServer.class);

    private static final long TRAFFIC_SHAPING_CHECK_INTERVAL_MS = 250L;
    private static final int MAX_INITIAL_LINE_LENGTH_DEFAULT = 8192;
    private static final int MAX_HEADER_SIZE_DEFAULT = 8192*2;
    private static final int MAX_CHUNK_SIZE_DEFAULT = 8192*2;

    private static final String FALLBACK_PROXY_ALIAS = "DimpyProxy";
    private final ServerGroup serverGroup;
    private final TransportProtocol transportProtocol;
    private final InetSocketAddress requestedAddress;
    private volatile InetSocketAddress localAddress;
    private volatile InetSocketAddress boundAddress;
    private final SSLHandler sslHandler;
    private final boolean authenticateSslClients;
    private final AuthenticatorHandler authenticatorHandler;
    private final MITMHandler mitmHandler;
    private final HttpFilterFactory filterFactory;
    private final boolean transparent;
    private volatile int connectTimeout;
    private volatile int idleConnectionTimeout;
    private final HostResolver serverResolver;
    private volatile GlobalTrafficShapingHandler globalTrafficShapingHandler;
    private final int maxInitialLineLength;
    private final int maxHeaderSize;
    private final int maxChunkSize;
    private final boolean allowRequestsToOriginServer;

    private final String proxyAlias;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final Collection<StateTracker> stateTrackerQueue = new ConcurrentLinkedQueue<>();
    private final ChannelGroup allChannels = new DefaultChannelGroup("Dimpy-Proxy-Server", GlobalEventExecutor.INSTANCE);

    /**
     * Shutdown hook to shutdown this proxy server.
     */
    private final Thread jvmShutdownHook = new Thread(() -> abort(), "Dimpy-JVM-shutdown-hook");

//    public static ProxyServerBootstrap bootstrapFromFile(String path) {
//        final File propsFile = new File(path);
//        Properties props = new Properties();
//
//        if (propsFile.isFile()) {
//            try (InputStream is = new FileInputStream(propsFile)) {
//                props.load(is);
//            } catch (final IOException e) {
//                LOGGER.warn("Could not load props file?", e);
//            }
//        }
//
//        return new DefaultProxyServerBootstrap(props);
//    }

    public DefaultHttpProxyServer(ServerGroup serverGroup,
                                   TransportProtocol transportProtocol,
                                   InetSocketAddress requestedAddress,
                                   SSLHandler sslHandler,
                                   boolean authenticateSslClients,
                                   AuthenticatorHandler authenticatorHandler,
                                   MITMHandler mitmHandler,
                                   HttpFilterFactory filterFactory,
                                   boolean transparent,
                                   int idleConnectionTimeout,
                                   Collection<StateTracker> stateTrackerQueue,
                                   int connectTimeout,
                                   HostResolver serverResolver,
                                   long readThrottleBytesPerSecond,
                                   long writeThrottleBytesPerSecond,
                                   InetSocketAddress localAddress,
                                   String proxyAlias,
                                   int maxInitialLineLength,
                                   int maxHeaderSize,
                                   int maxChunkSize,
                                   boolean allowRequestsToOriginServer) {
        this.serverGroup = serverGroup;
        this.transportProtocol = transportProtocol;
        this.requestedAddress = requestedAddress;
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

        if (writeThrottleBytesPerSecond > 0 || readThrottleBytesPerSecond > 0) {
            this.globalTrafficShapingHandler = createGlobalTrafficShapingHandler(transportProtocol, readThrottleBytesPerSecond, writeThrottleBytesPerSecond);
        } else {
            this.globalTrafficShapingHandler = null;
        }
        this.localAddress = localAddress;

        if (proxyAlias == null) {
            // attempt to resolve the name of the local machine. if it cannot be resolved, use the fallback name.
            String hostname = "";
            try {
                hostname = InetAddress.getLocalHost().getHostName();
            } catch (Exception ex) {
                hostname = FALLBACK_PROXY_ALIAS;
            }
            this.proxyAlias = hostname;
        } else {
            this.proxyAlias = proxyAlias;
        }
        this.maxInitialLineLength = maxInitialLineLength;
        this.maxHeaderSize = maxHeaderSize;
        this.maxChunkSize = maxChunkSize;
        this.allowRequestsToOriginServer = allowRequestsToOriginServer;
    }

    private GlobalTrafficShapingHandler createGlobalTrafficShapingHandler(TransportProtocol transportProtocol, long readThrottleBytesPerSecond, long writeThrottleBytesPerSecond) {
        EventLoopGroup proxyToServerEventLoop = this.getProxyToServerWorkerFor(transportProtocol);
        return new GlobalTrafficShapingHandler(proxyToServerEventLoop,
                writeThrottleBytesPerSecond,
                readThrottleBytesPerSecond,
                TRAFFIC_SHAPING_CHECK_INTERVAL_MS,
                Long.MAX_VALUE);
    }

    public boolean isTransparent() {
        return transparent;
    }

    @Override
    public int getIdleConnectionTimeout() {
        return idleConnectionTimeout;
    }

    @Override
    public void setIdleConnectionTimeout(int idleConnectionTimeout) {
        this.idleConnectionTimeout = idleConnectionTimeout;
    }

    @Override
    public int getConnectTimeout() {
        return connectTimeout;
    }

    @Override
    public void setConnectTimeout(int connectTimeoutMs) {
        this.connectTimeout = connectTimeoutMs;
    }

    public HostResolver getServerResolver() {
        return serverResolver;
    }

    public InetSocketAddress getLocalAddress() {
        return localAddress;
    }

    @Override
    public InetSocketAddress getListenAddress() {
        return boundAddress;
    }

    @Override
    public void setThrottle(long readThrottleBytesPerSecond, long writeThrottleBytesPerSecond) {
        if (globalTrafficShapingHandler != null) {
            globalTrafficShapingHandler.configure(writeThrottleBytesPerSecond, readThrottleBytesPerSecond);
        } else {
            // don't create a GlobalTrafficShapingHandler if throttling was not enabled and is still not enabled
            if (readThrottleBytesPerSecond > 0 || writeThrottleBytesPerSecond > 0) {
                globalTrafficShapingHandler = createGlobalTrafficShapingHandler(transportProtocol, readThrottleBytesPerSecond, writeThrottleBytesPerSecond);
            }
        }
    }

    public long getReadThrottle() {
        return globalTrafficShapingHandler.getReadLimit();
    }

    public long getWriteThrottle() {
        return globalTrafficShapingHandler.getWriteLimit();
    }

    public int getMaxInitialLineLength() {
        return maxInitialLineLength;
    }

    public int getMaxHeaderSize() {
        return maxHeaderSize;
    }

    public int getMaxChunkSize() {
        return maxChunkSize;
    }

    public boolean isAllowRequestsToOriginServer() {
        return allowRequestsToOriginServer;
    }

    @Override
    public ProxyServerBootstrap clone() {
        return new DefaultProxyServerBootstrap(serverGroup,
                transportProtocol,
                new InetSocketAddress(requestedAddress.getAddress(),
                        requestedAddress.getPort() == 0 ? 0 : requestedAddress.getPort() + 1),
                sslHandler,
                authenticateSslClients,
                authenticatorHandler,
                mitmHandler,
                filterFactory,
                transparent,
                idleConnectionTimeout,
                stateTrackerQueue,
                connectTimeout,
                serverResolver,
                globalTrafficShapingHandler != null ? globalTrafficShapingHandler.getReadLimit() : 0,
                globalTrafficShapingHandler != null ? globalTrafficShapingHandler.getWriteLimit() : 0,
                localAddress,
                proxyAlias,
                maxInitialLineLength,
                maxHeaderSize,
                maxChunkSize,
                allowRequestsToOriginServer);
    }

    @Override
    public void stop() {
        doStop(true);
    }

    @Override
    public void abort() {
        doStop(false);
    }

    protected void doStop(boolean graceful) {
        // only stop the server if it hasn't already been stopped
        if (stopped.compareAndSet(false, true)) {
            if (graceful) {
                LOGGER.info("Shutting down proxy server gracefully");
            } else {
                LOGGER.info("Shutting down proxy server immediately (non-graceful)");
            }

            closeAllChannels(graceful);

            serverGroup.unregisterProxyServer(this, graceful);

            // remove the shutdown hook that was added when the proxy was started, since it has now been stopped
            try {
                Runtime.getRuntime().removeShutdownHook(jvmShutdownHook);
            } catch (IllegalStateException e) {
                // ignore -- IllegalStateException means the VM is already shutting down
            }

            LOGGER.info("Done shutting down proxy server");
        }
    }

    public void registerChannel(Channel channel) {
        allChannels.add(channel);
    }

    protected void closeAllChannels(boolean graceful) {
        LOGGER.info("Closing all channels " + (graceful ? "(graceful)" : "(non-graceful)"));

        ChannelGroupFuture future = allChannels.close();

        // if this is a graceful shutdown, log any channel closing failures. if this isn't a graceful shutdown, ignore them.
        if (graceful) {
            try {
                future.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();

                LOGGER.warn("Interrupted while waiting for channels to shut down gracefully.");
            }

            if (!future.isSuccess()) {
                for (ChannelFuture cf : future) {
                    if (!cf.isSuccess()) {
                        LOGGER.info("Unable to close channel.  Cause of failure for {} is {}", cf.channel(), cf.cause());
                    }
                }
            }
        }
    }

    public HttpProxyServer start() {
        if (!serverGroup.isStopped()) {
            LOGGER.info("Starting proxy at address: " + this.requestedAddress);

            serverGroup.registerProxyServer(this);

            doStart();
        } else {
            throw new IllegalStateException("Attempted to start proxy, but proxy's server group is already stopped");
        }

        return this;
    }

    private void doStart() {
        ServerBootstrap serverBootstrap = new ServerBootstrap().group(
                serverGroup.getClientToProxyAcceptorPoolForTransport(transportProtocol),
                serverGroup.getClientToProxyWorkerPoolForTransport(transportProtocol));

        ChannelInitializer<Channel> initializer = new ChannelInitializer<Channel>() {
            protected void initChannel(Channel ch) throws Exception {
                new ClientConnection( DefaultHttpProxyServer.this, sslHandler, authenticateSslClients,
                        ch.pipeline(), globalTrafficShapingHandler);
            }
        };
        switch (transportProtocol) {
            case TCP:
                LOGGER.info("Proxy listening with TCP transport");
                serverBootstrap.channelFactory(new io.netty.bootstrap.ChannelFactory<ServerChannel>() {
                    @Override
                    public ServerChannel newChannel() {
                        return new NioServerSocketChannel();
                    }
                });
                break;
            case UDT:
                LOGGER.info("Proxy listening with UDT transport");
                serverBootstrap.channelFactory(NioUdtProvider.BYTE_ACCEPTOR)
                        .option(ChannelOption.SO_BACKLOG, 10)
                        .option(ChannelOption.SO_REUSEADDR, true);
                break;
            default:
                throw new UnknownTransportProtocolException(transportProtocol);
        }
        serverBootstrap.childHandler(initializer);
        ChannelFuture future = serverBootstrap.bind(requestedAddress)
                .addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future)
                            throws Exception {
                        if (future.isSuccess()) {
                            registerChannel(future.channel());
                        }
                    }
                }).awaitUninterruptibly();

        Throwable cause = future.cause();
        if (cause != null) {
            throw new RuntimeException(cause);
        }

        this.boundAddress = ((InetSocketAddress) future.channel().localAddress());
        LOGGER.info("Proxy started at address: " + this.boundAddress);

        Runtime.getRuntime().addShutdownHook(jvmShutdownHook);
    }

    public MITMHandler getMitmManager() {
        return mitmHandler;
    }

    public SSLHandler getSslEngineSource() {
        return sslHandler;
    }

    public AuthenticatorHandler getAuthenticatorHandler() {
        return authenticatorHandler;
    }

    public HttpFilterFactory getFilterFactory() {
        return filterFactory;
    }

    public Collection<StateTracker> getStateTrackerQueue() {
        return stateTrackerQueue;
    }

    public String getProxyAlias() {
        return proxyAlias;
    }


    public EventLoopGroup getProxyToServerWorkerFor(TransportProtocol transportProtocol) {
        return serverGroup.getProxyToServerWorkerPoolForTransport(transportProtocol);
    }

}
