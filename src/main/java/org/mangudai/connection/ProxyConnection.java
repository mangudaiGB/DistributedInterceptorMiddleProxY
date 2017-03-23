/*
 * Copyright (c) 2017. TechHive Software Labs, Inc - All Rights Reserved.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 * Proprietary and confidential
 */

package org.mangudai.connection;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;
import org.mangudai.filter.HttpFilter;
import org.mangudai.server.DefaultHttpProxyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngine;

/**
 * Created by neo on 21/03/17.
 */
public abstract class ProxyConnection<T extends HttpObject> extends SimpleChannelInboundHandler<Object> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProxyConnection.class);

    protected final DefaultHttpProxyServer proxyServer;
    protected final boolean runsAsSslClient;

    protected volatile ChannelHandlerContext ctx;
    protected volatile Channel channel;

    private volatile ConnectionState currentState;
    private volatile boolean tunneling = false;
    protected volatile long lastReadTime = 0;
    protected volatile SSLEngine sslEngine;

    protected ProxyConnection(ConnectionState initialState, DefaultHttpProxyServer proxyServer, boolean runsAsSslClient) {
        becomeState(initialState);
        this.proxyServer = proxyServer;
        this.runsAsSslClient = runsAsSslClient;
    }

    protected void read(Object msg) {
        LOGGER.debug("Reading: {}", msg);
        lastReadTime = System.currentTimeMillis();
        if (tunneling) {
            readRaw((ByteBuf) msg);
        } else {
            readHTTP((HttpObject) msg);
        }
    }

    private void readHTTP(HttpObject httpObject) {
        ConnectionState nextState = getCurrentState();
        switch (nextState) {
            case AWAITING_INITIAL:
                if (httpObject instanceof HttpMessage) {
                    nextState = readHTTPInitial((T) httpObject);
                } else {
                    // Similar to the AWAITING_PROXY_AUTHENTICATION case below, we may enter an AWAITING_INITIAL
                    // state if the proxy responded to an earlier request with a 502 or 504 response, or a short-circuit
                    // response from a filter. The client may have sent some chunked HttpContent associated with the request
                    // after the short-circuit response was sent. We can safely drop them.
                    LOGGER.debug("Dropping message because HTTP object was not an HttpMessage. HTTP object may be orphaned content from a short-circuited response. Message: {}", httpObject);
                }
                break;
            case AWAITING_CHUNK:
                HttpContent chunk = (HttpContent) httpObject;
                readHTTPChunk(chunk);
                nextState = chunk instanceof LastHttpContent ? ConnectionState.AWAITING_INITIAL : ConnectionState.AWAITING_CHUNK;
                break;
            case AWAITING_PROXY_AUTHENTICATION:
                if (httpObject instanceof HttpRequest) {
                    // Once we get an HttpRequest, try to process it as usual
                    nextState = readHTTPInitial((T) httpObject);
                } else {
                    // Anything that's not an HttpRequest that came in while
                    // we're pending authentication gets dropped on the floor. This
                    // can happen if the connected host already sent us some chunks
                    // (e.g. from a POST) after an initial request that turned out
                    // to require authentication.
                }
                break;
            case CONNECTING:
                LOGGER.warn("Attempted to read from connection that's in the process of connecting.  This shouldn't happen.");
                break;
            case NEGOTIATING_CONNECT:
                LOGGER.debug("Attempted to read from connection that's in the process of negotiating an HTTP CONNECT.  This is probably the LastHttpContent of a chunked CONNECT.");
                break;
            case AWAITING_CONNECT_OK:
                LOGGER.warn("AWAITING_CONNECT_OK should have been handled by ProxyToServerConnection.read()");
                break;
            case HANDSHAKING:
                LOGGER.warn("Attempted to read from connection that's in the process of handshaking.  This shouldn't happen.", channel);
                break;
            case DISCONNECT_REQUESTED:
            case DISCONNECTED:
                LOGGER.info("Ignoring message since the connection is closed or about to close");
                break;
        }
        becomeState(nextState);
    }

    protected abstract ConnectionState readHTTPInitial(T httpObject);

    protected abstract void readHTTPChunk(HttpContent chunk);

    protected abstract void readRaw(ByteBuf buf);

    void write(Object msg) {
        if (msg instanceof ReferenceCounted) {
            LOGGER.debug("Retaining reference counted message");
            ((ReferenceCounted) msg).retain();
        }
        doWrite(msg);
    }

    void doWrite(Object msg) {
        LOGGER.debug("Writing: {}", msg);

        try {
            if (msg instanceof HttpObject) {
                writeHttp((HttpObject) msg);
            } else {
                writeRaw((ByteBuf) msg);
            }
        } finally {
            LOGGER.debug("Wrote: {}", msg);
        }
    }

    protected void writeHttp(HttpObject httpObject) {
        if (httpObject instanceof LastHttpContent) {
            channel.write(httpObject);
            LOGGER.debug("Writing an empty buffer to signal the end of our chunked transfer");
            writeToChannel(Unpooled.EMPTY_BUFFER);
        } else {
            writeToChannel(httpObject);
        }
    }

    protected void writeRaw(ByteBuf buf) {
        writeToChannel(buf);
    }

    protected ChannelFuture writeToChannel(final Object msg) {
        return channel.writeAndFlush(msg);
    }

    protected void connected() {
        LOGGER.debug("Connected");
    }

    protected void disconnected() {
        becomeState(ConnectionState.DISCONNECTED);
        LOGGER.debug("Disconnected");
    }

    protected void timedOut() {
        disconnect();
    }

    protected ConnectionStep StartTunneling = new ConnectionStep(this, ConnectionState.NEGOTIATING_CONNECT) {
        @Override
        boolean shouldSuppressInitialRequest() {
            return true;
        }

        protected Future execute() {
            try {
                ChannelPipeline pipeline = ctx.pipeline();
                if (pipeline.get("encoder") != null) {
                    pipeline.remove("encoder");
                }
                if (pipeline.get("responseWrittenMonitor") != null) {
                    pipeline.remove("responseWrittenMonitor");
                }
                if (pipeline.get("decoder") != null) {
                    pipeline.remove("decoder");
                }
                if (pipeline.get("requestReadMonitor") != null) {
                    pipeline.remove("requestReadMonitor");
                }
                tunneling = true;
                return channel.newSucceededFuture();
            } catch (Throwable t) {
                return channel.newFailedFuture(t);
            }
        }
    };

    protected Future<Channel> encrypt(SSLEngine sslEngine, boolean authenticateClients) {
        return encrypt(ctx.pipeline(), sslEngine, authenticateClients);
    }

    protected Future<Channel> encrypt(ChannelPipeline pipeline,
                                      SSLEngine sslEngine,
                                      boolean authenticateClients) {
        LOGGER.debug("Enabling encryption with SSLHandler: {}", sslEngine);
        this.sslEngine = sslEngine;
        sslEngine.setUseClientMode(runsAsSslClient);
        sslEngine.setNeedClientAuth(authenticateClients);
        if (null != channel) {
            channel.config().setAutoRead(true);
        }
        SslHandler handler = new SslHandler(sslEngine);
        if(pipeline.get("ssl") == null) {
            pipeline.addFirst("ssl", handler);
        } else {
            // The second SSL handler is added to handle the case
            // where the proxy (running as MITM) has to chain with
            // another SSL enabled proxy. The second SSL handler
            // is to perform SSL with the server.
            pipeline.addAfter("ssl", "sslWithServer", handler);
        }
        return handler.handshakeFuture();
    }

    protected ConnectionStep EncryptChannel(final SSLEngine sslEngine) {

        return new ConnectionStep(this, ConnectionState.HANDSHAKING) {
            @Override
            boolean shouldExecuteOnEventLoop() {
                return false;
            }

            @Override
            protected Future<?> execute() {
                return encrypt(sslEngine, !runsAsSslClient);
            }
        };
    }

    protected void aggregateContentForFiltering(ChannelPipeline pipeline, int numberOfBytesToBuffer) {
        pipeline.addLast("inflater", new HttpContentDecompressor());
        pipeline.addLast("aggregator", new HttpObjectAggregator(numberOfBytesToBuffer));
    }

    protected void becameSaturated() {
        LOGGER.debug("Became saturated");
    }

    protected void becameWritable() {
        LOGGER.debug("Became writeable");
    }

    protected void exceptionCaught(Throwable cause) {
    }

    Future<Void> disconnect() {
        if (channel == null) {
            return null;
        } else {
            final Promise<Void> promise = channel.newPromise();
            writeToChannel(Unpooled.EMPTY_BUFFER).addListener((GenericFutureListener) (future) -> closeChannel(promise));
//            writeToChannel(Unpooled.EMPTY_BUFFER).addListener(new GenericFutureListener<Future<? super Void>>() {
//                        @Override
//                        public void operationComplete(Future<? super Void> future) throws Exception {
//                            closeChannel(promise);
//                        }
//                    });
            return promise;
        }
    }

    private void closeChannel(final Promise<Void> promise) {
        channel.close().addListener((GenericFutureListener) (future)-> {
            if (future.isSuccess())
                promise.setSuccess(null);
            else
                promise.setFailure(future.cause());
        });

//        channel.close().addListener(
//                new GenericFutureListener<Future<? super Void>>() {
//                    public void operationComplete(Future<? super Void> future) throws Exception {
//                        if (future.isSuccess()) {
//                            promise.setSuccess(null);
//                        } else {
//                            promise.setFailure(future.cause());
//                        }
//                    };
//                });
    }

    // If the channel is not writable
    protected boolean isSaturated() {
        return !this.channel.isWritable();
    }

    protected boolean isState(ConnectionState state) {
        return currentState == state;
    }

    protected boolean isConnecting() {
        return currentState.isPartOfConnectionProtocol();
    }

    protected void becomeState(ConnectionState state) {
        this.currentState = state;
    }

    protected ConnectionState getCurrentState() {
        return currentState;
    }

    public boolean isTunneling() {
        return tunneling;
    }

    public SSLEngine getSSLHandler() {
        return sslEngine;
    }

    protected void stopReading() {
        LOGGER.debug("Stopped reading");
        this.channel.config().setAutoRead(false);
    }

    protected void resumeReading() {
        LOGGER.debug("Resumed reading");
        this.channel.config().setAutoRead(true);
    }

    protected HttpFilter getHttpFilterFromProxyServer(HttpRequest httpRequest) {
        return proxyServer.getFilterFactory().filterRequest(httpRequest, ctx);
    }

    @Override
    protected final void channelRead0(ChannelHandlerContext ctx, Object msg)
            throws Exception {
        read(msg);
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        try {
            this.ctx = ctx;
            this.channel = ctx.channel();
            this.proxyServer.registerChannel(ctx.channel());
        } finally {
            super.channelRegistered(ctx);
        }
    }

    @Override
    public final void channelActive(ChannelHandlerContext ctx) throws Exception {
        try {
            connected();
        } finally {
            super.channelActive(ctx);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        try {
            disconnected();
        } finally {
            super.channelInactive(ctx);
        }
    }

    @Override
    public final void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        LOGGER.debug("Writability changed. Is writable: {}", channel.isWritable());
        try {
            if (this.channel.isWritable()) {
                becameWritable();
            } else {
                becameSaturated();
            }
        } finally {
            super.channelWritabilityChanged(ctx);
        }
    }

    @Override
    public final void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        exceptionCaught(cause);
    }

    @Override
    public final void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        try {
            if (evt instanceof IdleStateEvent) {
                LOGGER.debug("Got idle");
                timedOut();
            }
        } finally {
            super.userEventTriggered(ctx, evt);
        }
    }

    /** State Trackers **/

    @Sharable
    protected abstract class BytesReadMonitor extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            try {
                if (msg instanceof ByteBuf) {
                    bytesRead(((ByteBuf) msg).readableBytes());
                }
            } catch (Throwable t) {
                LOGGER.warn("Unable to record bytesRead", t);
            } finally {
                super.channelRead(ctx, msg);
            }
        }
        protected abstract void bytesRead(int numberOfBytes);
    }

    @Sharable
    protected abstract class RequestReadMonitor extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            try {
                if (msg instanceof HttpRequest) {
                    requestRead((HttpRequest) msg);
                }
            } catch (Throwable t) {
                LOGGER.warn("Unable to record bytesRead", t);
            } finally {
                super.channelRead(ctx, msg);
            }
        }
        protected abstract void requestRead(HttpRequest httpRequest);
    }

    @Sharable
    protected abstract class ResponseReadMonitor extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            try {
                if (msg instanceof HttpResponse) {
                    responseRead((HttpResponse) msg);
                }
            } catch (Throwable t) {
                LOGGER.warn("Unable to record bytesRead", t);
            } finally {
                super.channelRead(ctx, msg);
            }
        }
        protected abstract void responseRead(HttpResponse httpResponse);
    }

    @Sharable
    protected abstract class BytesWrittenMonitor extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            try {
                if (msg instanceof ByteBuf) {
                    bytesWritten(((ByteBuf) msg).readableBytes());
                }
            } catch (Throwable t) {
                LOGGER.warn("Unable to record bytesRead", t);
            } finally {
                super.write(ctx, msg, promise);
            }
        }

        protected abstract void bytesWritten(int numberOfBytes);
    }

    @Sharable
    protected abstract class RequestWrittenMonitor extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            HttpRequest originalRequest = null;
            if (msg instanceof HttpRequest) {
                originalRequest = (HttpRequest) msg;
            }
            if (null != originalRequest) {
                requestWriting(originalRequest);
            }
            super.write(ctx, msg, promise);
            if (null != originalRequest) {
                requestWritten(originalRequest);
            }
            if (msg instanceof HttpContent) {
                contentWritten((HttpContent) msg);
            }
        }

        protected abstract void requestWriting(HttpRequest httpRequest);

        protected abstract void requestWritten(HttpRequest httpRequest);

        protected abstract void contentWritten(HttpContent httpContent);
    }

    @Sharable
    protected abstract class ResponseWrittenMonitor extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            try {
                if (msg instanceof HttpResponse) {
                    responseWritten(((HttpResponse) msg));
                }
            } catch (Throwable t) {
                LOGGER.warn("Error while invoking responseWritten callback", t);
            } finally {
                super.write(ctx, msg, promise);
            }
        }

        protected abstract void responseWritten(HttpResponse httpResponse);
    }
}
