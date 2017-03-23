/*
 * Copyright (c) 2017. TechHive Software Labs, Inc - All Rights Reserved.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 * Proprietary and confidential
 */

package org.mangudai.thread;

import io.netty.channel.EventLoopGroup;
import org.mangudai.server.HttpProxyServer;
import org.mangudai.transport.TransportProtocol;
import org.mangudai.transport.UnknownTransportProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by neo on 21/03/17.
 */
public class ServerGroup {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerGroup.class);

    public static final int DEFAULT_INCOMING_ACCEPTOR_THREADS = 2;
    public static final int DEFAULT_INCOMING_WORKER_THREADS = 8;
    public static final int DEFAULT_OUTGOING_WORKER_THREADS = 8;
    private static final AtomicInteger serverGroupCount = new AtomicInteger(0);

    private final String name;
    private final int serverGroupId;
    private final int incomingAcceptorThreads;
    private final int incomingWorkerThreads;
    private final int outgoingWorkerThreads;

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    public final List<HttpProxyServer> registeredServers = new ArrayList<HttpProxyServer>(1);
    private final EnumMap<TransportProtocol, ProxyServerThreadPool> protocolThreadPools = new EnumMap<TransportProtocol, ProxyServerThreadPool>(TransportProtocol.class);
    private static final EnumMap<TransportProtocol, SelectorProvider> TRANSPORT_PROTOCOL_SELECTOR_PROVIDERS = new EnumMap<TransportProtocol, SelectorProvider>(TransportProtocol.class);
    private final Object THREAD_POOL_INIT_LOCK = new Object();
    private final Object SERVER_REGISTRATION_LOCK = new Object();

    static {
        TRANSPORT_PROTOCOL_SELECTOR_PROVIDERS.put(TransportProtocol.TCP, SelectorProvider.provider());
        // Other transport level protocols like UDP and UDT go here
    }


    public ServerGroup(String name, int incomingAcceptorThreads, int incomingWorkerThreads, int outgoingWorkerThreads) {
        this.name = name;
        this.serverGroupId = serverGroupCount.getAndIncrement();
        this.incomingAcceptorThreads = incomingAcceptorThreads;
        this.incomingWorkerThreads = incomingWorkerThreads;
        this.outgoingWorkerThreads = outgoingWorkerThreads;
    }

    private ProxyServerThreadPool getThreadPoolsForProtocol(TransportProtocol protocol) {
        // if the thread pools have not been initialized for this protocol, initialize them
        if (protocolThreadPools.get(protocol) == null) {
            synchronized (THREAD_POOL_INIT_LOCK) {
                if (protocolThreadPools.get(protocol) == null) {
                    LOGGER.debug("Initializing thread pools for {} with {} acceptor threads, {} incoming worker threads, and {} outgoing worker threads",
                            protocol, incomingAcceptorThreads, incomingWorkerThreads, outgoingWorkerThreads);

                    SelectorProvider selectorProvider = TRANSPORT_PROTOCOL_SELECTOR_PROVIDERS.get(protocol);
                    if (selectorProvider == null) {
                        throw new UnknownTransportProtocolException(protocol);
                    }

                    ProxyServerThreadPool threadPools = new ProxyServerThreadPool(selectorProvider,
                            incomingAcceptorThreads,
                            incomingWorkerThreads,
                            outgoingWorkerThreads,
                            name,
                            serverGroupId);
                    protocolThreadPools.put(protocol, threadPools);
                }
            }
        }
        return protocolThreadPools.get(protocol);
    }

    public void registerProxyServer(HttpProxyServer proxyServer) {
        synchronized (SERVER_REGISTRATION_LOCK) {
            registeredServers.add(proxyServer);
        }
    }

    public void unregisterProxyServer(HttpProxyServer proxyServer, boolean graceful) {
        synchronized (SERVER_REGISTRATION_LOCK) {
            boolean wasRegistered = registeredServers.remove(proxyServer);
            if (!wasRegistered) {
                LOGGER.warn("Attempted to unregister proxy server from ServerGroup that it was not registered with. Was the proxy unregistered twice?");
            }

            if (registeredServers.isEmpty()) {
                LOGGER.debug("Proxy server unregistered from ServerGroup. No proxy servers remain registered, so shutting down ServerGroup.");

                shutdown(graceful);
            } else {
                LOGGER.debug("Proxy server unregistered from ServerGroup. Not shutting down ServerGroup ({} proxy servers remain registered).", registeredServers.size());
            }
        }
    }

    private void shutdown(boolean graceful) {
        if (!stopped.compareAndSet(false, true)) {
            LOGGER.info("Shutdown requested, but ServerGroup is already stopped. Doing nothing.");

            return;
        }
        LOGGER.info("Shutting down server group event loops " + (graceful ? "(graceful)" : "(non-graceful)"));
        // loop through all event loops managed by this server group. this includes acceptor and worker event loops
        // for both TCP and UDP transport protocols.
        List<EventLoopGroup> allEventLoopGroups = new ArrayList<EventLoopGroup>();
        for (ProxyServerThreadPool threadPools : protocolThreadPools.values()) {
            allEventLoopGroups.addAll(threadPools.getAllEventLoops());
        }
        for (EventLoopGroup group : allEventLoopGroups) {
            if (graceful) {
                group.shutdownGracefully();
            } else {
                group.shutdownGracefully(0, 0, TimeUnit.SECONDS);
            }
        }
        if (graceful) {
            for (EventLoopGroup group : allEventLoopGroups) {
                try {
                    group.awaitTermination(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();

                    LOGGER.warn("Interrupted while shutting down event loop");
                }
            }
        }
        LOGGER.debug("Done shutting down server group");
    }

    public EventLoopGroup getClientToProxyAcceptorPoolForTransport(TransportProtocol protocol) {
        return getThreadPoolsForProtocol(protocol).getClientAcceptorPool();
    }

    public EventLoopGroup getClientToProxyWorkerPoolForTransport(TransportProtocol protocol) {
        return getThreadPoolsForProtocol(protocol).getClientWorkerPool();
    }

    public EventLoopGroup getProxyToServerWorkerPoolForTransport(TransportProtocol protocol) {
        return getThreadPoolsForProtocol(protocol).getServerWorkerPool();
    }

    public boolean isStopped() {
        return stopped.get();
    }
}
