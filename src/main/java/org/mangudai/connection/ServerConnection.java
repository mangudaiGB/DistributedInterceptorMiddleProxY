/*
 * Copyright (c) 2017. TechHive Software Labs, Inc - All Rights Reserved.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 * Proprietary and confidential
 */

package org.mangudai.connection;

import com.google.common.net.HostAndPort;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ChannelFactory;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.udt.nio.NioUdtProvider;
import io.netty.handler.codec.http.*;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.mangudai.filter.HttpFilter;
import org.mangudai.mitm.MITMHandler;
import org.mangudai.server.DefaultHttpProxyServer;
import org.mangudai.state.ServerStateContext;
import org.mangudai.state.StateTracker;
import org.mangudai.transport.TransportProtocol;
import org.mangudai.transport.UnknownTransportProtocolException;
import org.mangudai.util.Utility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLProtocolException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.RejectedExecutionException;

/**
 * Created by neo on 21/03/17.
 */

@ChannelHandler.Sharable
public class ServerConnection extends ProxyConnection<HttpResponse> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerConnection.class);

    private final ClientConnection clientConnection;
    private final ServerConnection serverConnection = this;
    private volatile TransportProtocol transportProtocol;
    private volatile InetSocketAddress remoteAddress;
    private volatile InetSocketAddress localAddress;
    private final String serverHostAndPort;
    private volatile HttpFilter currentFilter;
    private volatile ConnectionProtocol connectionProtocol;
    private volatile boolean disableSni = false;
    private final Object connectLock = new Object();
    private volatile HttpRequest initialRequest;
    private volatile HttpRequest currentHttpRequest;
    private volatile HttpResponse currentHttpResponse;
    private volatile GlobalTrafficShapingHandler trafficHandler;

    private static final int MINIMUM_RECV_BUFFER_SIZE_BYTES = 64;

    static ServerConnection create(DefaultHttpProxyServer proxyServer,
                                          ClientConnection clientConnection,
                                          String serverHostAndPort,
                                          HttpFilter initialFilter,
                                          HttpRequest initialHttpRequest,
                                          GlobalTrafficShapingHandler globalTrafficShapingHandler) throws UnknownHostException {

        return new ServerConnection(proxyServer,
                clientConnection,
                serverHostAndPort,
                initialFilter,
                globalTrafficShapingHandler);
    }

    private ServerConnection( DefaultHttpProxyServer proxyServer, ClientConnection clientConnection, String serverHostAndPort,
            HttpFilter initialFilter, GlobalTrafficShapingHandler globalTrafficShapingHandler) throws UnknownHostException {
        super(ConnectionState.DISCONNECTED, proxyServer, true);
        this.clientConnection = clientConnection;
        this.serverHostAndPort = serverHostAndPort;
        this.trafficHandler = globalTrafficShapingHandler;
        this.currentFilter = initialFilter;
        currentFilter.serverConnectionQueued();
        setupConnectionParameters();
    }

    @Override
    protected void read(Object msg) {
        if (isConnecting()) {
            LOGGER.debug("In the middle of connecting, forwarding message to connection flow: {}", msg);
            this.connectionProtocol.read(msg);
        } else {
            super.read(msg);
        }
    }

    @Override
    protected ConnectionState readHTTPInitial(HttpResponse httpResponse) {
        LOGGER.debug("Received raw response: {}", httpResponse);

        if (httpResponse.getDecoderResult().isFailure()) {
            LOGGER.debug("Could not parse response from server. Decoder result: {}", httpResponse.getDecoderResult().toString());

            // create a "substitute" Bad Gateway response from the server, since we couldn't understand what the actual
            // response from the server was. set the keep-alive on the substitute response to false so the proxy closes
            // the connection to the server, since we don't know what state the server thinks the connection is in.
            FullHttpResponse substituteResponse = Utility.createFullHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.BAD_GATEWAY,
                    "Unable to parse response from server");
            HttpHeaders.setKeepAlive(substituteResponse, false);
            httpResponse = substituteResponse;
        }
        currentFilter.serverResponseReceiving();
        rememberCurrentResponse(httpResponse);
        respondWith(httpResponse);

        if (Utility.isChunked(httpResponse)) {
            return ConnectionState.AWAITING_CHUNK;
        } else {
            currentFilter.serverResponseReceived();
            return ConnectionState.AWAITING_INITIAL;
        }
    }

    @Override
    protected void readHTTPChunk(HttpContent chunk) {
        respondWith(chunk);
    }

    @Override
    protected void readRaw(ByteBuf buf) {
        clientConnection.write(buf);
    }

    private class HeadAwareHttpResponseDecoder extends HttpResponseDecoder {

        public HeadAwareHttpResponseDecoder(int maxInitialLineLength, int maxHeaderSize, int maxChunkSize) {
            super(maxInitialLineLength, maxHeaderSize, maxChunkSize);
        }

        @Override
        protected boolean isContentAlwaysEmpty(HttpMessage httpMessage) {
            // The current HTTP Request can be null when this proxy is
            // negotiating a CONNECT request with a chained proxy
            // while it is running as a MITM. Since the response to a
            // CONNECT request does not have any content, we return true.
            if(currentHttpRequest == null) {
                return true;
            } else {
                return Utility.isHEAD(currentHttpRequest) || super.isContentAlwaysEmpty(httpMessage);
            }
        }
    }

    void write(Object msg, HttpFilter filter) {
        this.currentFilter = filter;
        write(msg);
    }

    @Override
    void write(Object msg) {
        LOGGER.debug("Requested write of {}", msg);
        if (msg instanceof ReferenceCounted) {
            LOGGER.debug("Retaining reference counted message");
            ((ReferenceCounted) msg).retain();
        }
        if (isState(ConnectionState.DISCONNECTED) && msg instanceof HttpRequest) {
            LOGGER.debug("Currently disconnected, connect and then write the message");
            connectAndWrite((HttpRequest) msg);
        } else {
            if (isConnecting()) {
                synchronized (connectLock) {
                    if (isConnecting()) {
                        LOGGER.debug("Attempted to write while still in the process of connecting, waiting for connection.");
                        clientConnection.stopReading();
                        try {
                            connectLock.wait(30000);
                        } catch (InterruptedException ie) {
                            LOGGER.warn("Interrupted while waiting for connect monitor");
                        }
                    }
                }
            }
            // only write this message if a connection was established and is not in the process of disconnecting or
            // already disconnected
            if (isConnecting() || getCurrentState().isDisconnectingOrDisconnected()) {
                LOGGER.debug("Connection failed or timed out while waiting to write message to server. Message will be discarded: {}", msg);
                return;
            }
            LOGGER.debug("Using existing connection to: {}", remoteAddress);
            doWrite(msg);
        }
    }

    @Override
    protected void writeHttp(HttpObject httpObject) {
        if (httpObject instanceof HttpRequest) {
            HttpRequest httpRequest = (HttpRequest) httpObject;
            currentHttpRequest = httpRequest;
        }
        super.writeHttp(httpObject);
    }

    @Override
    protected void becomeState(ConnectionState newState) {
        // Report connection status to HttpFilters
        if (getCurrentState() == ConnectionState.DISCONNECTED && newState == ConnectionState.CONNECTING) {
            currentFilter.serverConnectionStarted();
        } else if (getCurrentState() == ConnectionState.CONNECTING) {
            if (newState == ConnectionState.HANDSHAKING) {
                currentFilter.serverConnectionSSLHandshakeStarted();
            } else if (newState == ConnectionState.AWAITING_INITIAL) {
                currentFilter.serverConnectionSucceeded(ctx);
            } else if (newState == ConnectionState.DISCONNECTED) {
                currentFilter.serverConnectionFailed();
            }
        } else if (getCurrentState() == ConnectionState.HANDSHAKING) {
            if (newState == ConnectionState.AWAITING_INITIAL) {
                currentFilter.serverConnectionSucceeded(ctx);
            } else if (newState == ConnectionState.DISCONNECTED) {
                currentFilter.serverConnectionFailed();
            }
        } else if (getCurrentState() == ConnectionState.AWAITING_CHUNK
                && newState != ConnectionState.AWAITING_CHUNK) {
            currentFilter.serverResponseReceived();
        }

        super.becomeState(newState);
    }

    @Override
    protected void becameSaturated() {
        super.becameSaturated();
        this.clientConnection.serverBecameSaturated(this);
    }

    @Override
    protected void becameWritable() {
        super.becameWritable();
        this.clientConnection.serverBecameWriteable(this);
    }

    @Override
    protected void timedOut() {
        super.timedOut();
        clientConnection.timedOut(this);
    }

    @Override
    protected void disconnected() {
        super.disconnected();
        clientConnection.serverDisconnected(this);
    }

    @Override
    protected void exceptionCaught(Throwable cause) {
        try {
            if (cause instanceof IOException) {
                // IOExceptions are expected errors, for example when a server drops the connection. rather than flood
                // the logs with stack traces for these expected exceptions, log the message at the INFO level and the
                // stack trace at the DEBUG level.
                LOGGER.info("An IOException occurred on ProxyToServerConnection: " + cause.getMessage());
                LOGGER.debug("An IOException occurred on ProxyToServerConnection", cause);
            } else if (cause instanceof RejectedExecutionException) {
                LOGGER.info("An executor rejected a read or write operation on the ProxyToServerConnection (this is normal if the proxy is shutting down). Message: " + cause.getMessage());
                LOGGER.debug("A RejectedExecutionException occurred on ProxyToServerConnection", cause);
            } else {
                LOGGER.error("Caught an exception on ProxyToServerConnection", cause);
            }
        } finally {
            if (!isState(ConnectionState.DISCONNECTED)) {
                LOGGER.info("Disconnecting open connection to server");
                disconnect();
            }
        }
    }

    public TransportProtocol getTransportProtocol() {
        return transportProtocol;
    }

    public InetSocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    public String getServerHostAndPort() {
        return serverHostAndPort;
    }

    public HttpRequest getInitialRequest() {
        return initialRequest;
    }

    @Override
    protected HttpFilter getHttpFilterFromProxyServer(HttpRequest httpRequest) {
        return currentFilter;
    }

    private void rememberCurrentResponse(HttpResponse response) {
        LOGGER.debug("Remembering the current response.");
        // We need to make a copy here because the response will be
        // modified in various ways before we need to do things like
        // analyze response headers for whether or not to close the
        // connection (which may not happen for a while for large, chunked
        // responses, for example).
        currentHttpResponse = Utility.copyMutableResponseFields(response);
    }

    private void respondWith(HttpObject httpObject) {
        clientConnection.respond(this, currentFilter, currentHttpRequest, currentHttpResponse, httpObject);
    }

    private void connectAndWrite(HttpRequest initialRequest) {
        LOGGER.debug("Starting new connection to: {}", remoteAddress);
        // Remember our initial request so that we can write it after connecting
        this.initialRequest = initialRequest;
        initializeConnectionFlow();
        connectionProtocol.start();
    }

    private void initializeConnectionFlow() {
        this.connectionProtocol = new ConnectionProtocol(clientConnection, this, connectLock).then(connectChannel);

        if (Utility.isCONNECT(initialRequest)) {
            // If we're chaining, forward the CONNECT request
            MITMHandler mitmHandler = proxyServer.getMitmManager();
            boolean isMitmEnabled = mitmHandler != null;
            if (isMitmEnabled) {
                // When MITM is enabled and when chained proxy is set up, remoteAddress
                // will be the chained proxy's address. So we use serverHostAndPort
                // which is the end server's address.
                HostAndPort parsedHostAndPort = HostAndPort.fromString(serverHostAndPort);

                // SNI may be disabled for this request due to a previous failed attempt to connect to the server
                // with SNI enabled.
                if (disableSni) {
                    connectionProtocol.then(serverConnection.EncryptChannel(proxyServer.getMitmManager().serverSslEngine()));
                } else {
                    connectionProtocol.then(
                            serverConnection.EncryptChannel(
                                    proxyServer.getMitmManager().serverSslEngine(parsedHostAndPort.getHostText(), parsedHostAndPort.getPort())));
                }

                connectionProtocol
                        .then(clientConnection.RespondCONNECTSuccessful)
                        .then(serverConnection.MitmEncryptClientChannel);
            } else {
                connectionProtocol.then(serverConnection.StartTunneling)
                        .then(clientConnection.RespondCONNECTSuccessful)
                        .then(clientConnection.StartTunneling);
            }
        }
    }

    /**
     * Opens the socket connection.
     */
    private ConnectionStep connectChannel = new ConnectionStep(this, ConnectionState.CONNECTING) {
        @Override
        boolean shouldExecuteOnEventLoop() {
            return false;
        }
        @Override
        protected Future<?> execute() {
            Bootstrap cb = new Bootstrap().group(proxyServer.getProxyToServerWorkerFor(transportProtocol));
            switch (transportProtocol) {
                case TCP:
                    LOGGER.debug("Connecting to server with TCP");
                    cb.channelFactory(new ChannelFactory<Channel>() {
                        @Override
                        public Channel newChannel() {
                            return new NioSocketChannel();
                        }
                    });
                    break;
                case UDT:
                    LOGGER.debug("Connecting to server with UDT");
                    cb.channelFactory(NioUdtProvider.BYTE_CONNECTOR).option(ChannelOption.SO_REUSEADDR, true);
                    break;
                default:
                    throw new UnknownTransportProtocolException(transportProtocol);
            }

            cb.handler(new ChannelInitializer<Channel>() {
                protected void initChannel(Channel ch) throws Exception {
                    initChannelPipeline(ch.pipeline(), initialRequest);
                }
            });
            cb.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, proxyServer.getConnectTimeout());

            if (localAddress != null) {
                return cb.connect(remoteAddress, localAddress);
            } else {
                return cb.connect(remoteAddress);
            }
        }
    };

    private ConnectionStep MitmEncryptClientChannel = new ConnectionStep(this, ConnectionState.HANDSHAKING) {
        @Override
        boolean shouldExecuteOnEventLoop() {
            return false;
        }

        @Override
        boolean shouldSuppressInitialRequest() {
            return true;
        }

        @Override
        protected Future<?> execute() {
            return clientConnection
                    .encrypt(proxyServer.getMitmManager()
                            .clientSslEngineFor(initialRequest, sslEngine.getSession()), false)
                    .addListener((GenericFutureListener) (future) -> {
                        if (future.isSuccess())
                            clientConnection.setMitming(true);
                    });
        }
    };

    protected boolean connectionFailed(Throwable cause)
            throws UnknownHostException {
        // unlike a browser, java throws an exception when receiving an unrecognized_name TLS warning, even if the server
        // sends back a valid certificate for the expected host. we can retry the connection without SNI to allow the proxy
        // to connect to these misconfigured hosts. we should only retry the connection without SNI if the connection
        // failure happened when SNI was enabled, to prevent never-ending connection attempts due to SNI warnings.
        if (!disableSni && cause instanceof SSLProtocolException) {
            // unfortunately java does not expose the specific TLS alert number (112), so we have to look for the
            // unrecognized_name string in the exception's message
            if (cause.getMessage() != null && cause.getMessage().contains("unrecognized_name")) {
                LOGGER.debug("Failed to connect to server due to an unrecognized_name SSL warning. Retrying connection without SNI.");
                // disable SNI, re-setup the connection, and restart the connection flow
                disableSni = true;
                resetConnectionForRetry();
                connectAndWrite(initialRequest);
                return true;
            }
        }

        // the connection issue wasn't due to an unrecognized_name error, or the connection attempt failed even after
        // disabling SNI. before falling back to a chained proxy, re-enable SNI.
        disableSni = false;
        // no chained proxy fallback or other retry mechanism available
        return false;
    }

    private void resetConnectionForRetry() throws UnknownHostException {
        // Remove ourselves as handler on the old context
        this.ctx.pipeline().remove(this);
        this.ctx.close();
        this.ctx = null;

        this.setupConnectionParameters();
    }

    private void setupConnectionParameters() throws UnknownHostException {
        this.transportProtocol = TransportProtocol.TCP;
        // Report DNS resolution to HttpFilters
        this.remoteAddress = this.currentFilter.serverResolutionStarted(serverHostAndPort);
        // save the hostname and port of the unresolved address in hostAndPort, in case name resolution fails
        String hostAndPort = null;
        try {
            if (this.remoteAddress == null) {
                hostAndPort = serverHostAndPort;
                this.remoteAddress = addressFor(serverHostAndPort, proxyServer);
            } else if (this.remoteAddress.isUnresolved()) {
                // filter returned an unresolved address, so resolve it using the proxy server's resolver
                hostAndPort = HostAndPort.fromParts(this.remoteAddress.getHostName(), this.remoteAddress.getPort()).toString();
                this.remoteAddress = proxyServer.getServerResolver().resolve(this.remoteAddress.getHostName(), this.remoteAddress.getPort());
            }
        } catch (UnknownHostException e) {
            // unable to resolve the hostname to an IP address. notify the filters of the failure before allowing the
            // exception to bubble up.
            this.currentFilter.serverResolutionFailed(hostAndPort);
            throw e;
        }
        this.currentFilter.serverResolutionSucceeded(serverHostAndPort, this.remoteAddress);
        this.localAddress = proxyServer.getLocalAddress();
    }

    private void initChannelPipeline(ChannelPipeline pipeline, HttpRequest httpRequest) {
        if (trafficHandler != null) {
            pipeline.addLast("global-traffic-shaping", trafficHandler);
        }
        pipeline.addLast("bytesReadMonitor", bytesReadMonitor);
        pipeline.addLast("bytesWrittenMonitor", bytesWrittenMonitor);
        pipeline.addLast("encoder", new HttpRequestEncoder());
        pipeline.addLast("decoder", new HeadAwareHttpResponseDecoder(proxyServer.getMaxInitialLineLength(),
                proxyServer.getMaxHeaderSize(), proxyServer.getMaxChunkSize()));
        // Enable aggregation for filtering if necessary
        int numberOfBytesToBuffer = proxyServer.getFilterFactory().getMaximumResponseBufferSizeInBytes();
        if (numberOfBytesToBuffer > 0) {
            aggregateContentForFiltering(pipeline, numberOfBytesToBuffer);
        }
        pipeline.addLast("responseReadMonitor", responseReadMonitor);
        pipeline.addLast("requestWrittenMonitor", requestWrittenMonitor);
        // Set idle timeout
        pipeline.addLast( "idle", new IdleStateHandler(0, 0, proxyServer.getIdleConnectionTimeout()));
        pipeline.addLast("handler", this);
    }

    void connectionSucceeded(boolean shouldForwardInitialRequest) {
        becomeState(ConnectionState.AWAITING_INITIAL);
        clientConnection.serverConnectionSucceeded(this, shouldForwardInitialRequest);
        if (shouldForwardInitialRequest) {
            LOGGER.debug("Writing initial request: {}", initialRequest);
            write(initialRequest);
        } else {
            LOGGER.debug("Dropping initial request: {}", initialRequest);
        }

        // we're now done with the initialRequest: it's either been forwarded to the upstream server (HTTP requests), or
        // completely dropped (HTTPS CONNECTs). if the initialRequest is reference counted (typically because the HttpObjectAggregator is in
        // the pipeline to generate FullHttpRequests), we need to manually release it to avoid a memory leak.
        if (initialRequest instanceof ReferenceCounted) {
            ((ReferenceCounted)initialRequest).release();
        }
    }

    public static InetSocketAddress addressFor(String hostAndPort, DefaultHttpProxyServer proxyServer)
            throws UnknownHostException {
        HostAndPort parsedHostAndPort;
        try {
            parsedHostAndPort = HostAndPort.fromString(hostAndPort);
        } catch (IllegalArgumentException e) {
            // we couldn't understand the hostAndPort string, so there is no way we can resolve it.
            throw new UnknownHostException(hostAndPort);
        }

        String host = parsedHostAndPort.getHostText();
        int port = parsedHostAndPort.getPortOrDefault(80);

        return proxyServer.getServerResolver().resolve(host, port);
    }

    private final BytesReadMonitor bytesReadMonitor = new BytesReadMonitor() {
        @Override
        protected void bytesRead(int numberOfBytes) {
            ServerStateContext serverContext = new ServerStateContext(clientConnection, ServerConnection.this);
            for (StateTracker tracker : proxyServer.getStateTrackerQueue()) {
                tracker.bytesReceivedFromServer(serverContext, numberOfBytes);
            }
        }
    };

    private ResponseReadMonitor responseReadMonitor = new ResponseReadMonitor() {
        @Override
        protected void responseRead(HttpResponse httpResponse) {
            ServerStateContext serverStateContext = new ServerStateContext(clientConnection, ServerConnection.this);
            for (StateTracker tracker : proxyServer.getStateTrackerQueue()) {
                tracker.responseReceivedFromServer(serverStateContext, httpResponse);
            }
        }
    };

    private BytesWrittenMonitor bytesWrittenMonitor = new BytesWrittenMonitor() {
        @Override
        protected void bytesWritten(int numberOfBytes) {
            ServerStateContext serverStateContext = new ServerStateContext(clientConnection, ServerConnection.this);
            for (StateTracker tracker : proxyServer.getStateTrackerQueue()) {
                tracker.bytesSentToServer(serverStateContext, numberOfBytes);
            }
        }
    };

    private RequestWrittenMonitor requestWrittenMonitor = new RequestWrittenMonitor() {
        @Override
        protected void requestWriting(HttpRequest httpRequest) {
            ServerStateContext flowContext = new ServerStateContext(clientConnection, ServerConnection.this);
            try {
                for (StateTracker tracker : proxyServer.getStateTrackerQueue()) {
                    tracker.requestSentToServer(flowContext, httpRequest);
                }
            } catch (Throwable t) {
                LOGGER.warn("Error while invoking ActivityTracker on request", t);
            }
            currentFilter.serverRequestSending();
        }

        @Override
        protected void requestWritten(HttpRequest httpRequest) {
        }

        @Override
        protected void contentWritten(HttpContent httpContent) {
            if (httpContent instanceof LastHttpContent) {
                currentFilter.serverRequestSent();
            }
        }
    };
}
