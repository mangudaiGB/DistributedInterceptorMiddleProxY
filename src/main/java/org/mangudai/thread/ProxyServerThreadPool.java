/*
 * Copyright (c) 2017. TechHive Software Labs, Inc - All Rights Reserved.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 * Proprietary and confidential
 */

package org.mangudai.thread;

import com.google.common.collect.ImmutableList;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;

import java.nio.channels.spi.SelectorProvider;
import java.util.List;

/**
 * Created by neo on 21/03/17.
 */
public class ProxyServerThreadPool {
    private final NioEventLoopGroup clientAcceptorPool;
    private final NioEventLoopGroup clientWorkerPool;
    private final NioEventLoopGroup serverWorkerPool;

    public ProxyServerThreadPool(SelectorProvider selectorProvider, int incomingAcceptorThreads, int incomingWorkerThreads, int outgoingWorkerThreads, String serverGroupName, int serverGroupId) {
        clientAcceptorPool = new NioEventLoopGroup(incomingAcceptorThreads, new CategorizationFactory(serverGroupName, "ClientToProxyAcceptor", serverGroupId), selectorProvider);

        clientWorkerPool = new NioEventLoopGroup(incomingWorkerThreads, new CategorizationFactory(serverGroupName, "ClientToProxyWorker", serverGroupId), selectorProvider);
        clientWorkerPool.setIoRatio(90);

        serverWorkerPool = new NioEventLoopGroup(outgoingWorkerThreads, new CategorizationFactory(serverGroupName, "ProxyToServerWorker", serverGroupId), selectorProvider);
        serverWorkerPool.setIoRatio(90);
    }

    /**
     * Returns all event loops (acceptor and worker thread pools) in this pool.
     */
    public List<EventLoopGroup> getAllEventLoops() {
        return ImmutableList.<EventLoopGroup>of(clientAcceptorPool, clientWorkerPool, serverWorkerPool);
    }

    public NioEventLoopGroup getClientAcceptorPool() {
        return clientAcceptorPool;
    }

    public NioEventLoopGroup getClientWorkerPool() {
        return clientWorkerPool;
    }

    public NioEventLoopGroup getServerWorkerPool() {
        return serverWorkerPool;
    }
}
