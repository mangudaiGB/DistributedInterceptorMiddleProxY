/*
 * Copyright (c) 2017. TechHive Software Labs, Inc - All Rights Reserved.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 * Proprietary and confidential
 */

package org.mangudai.connection;

import com.google.common.io.BaseEncoding;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.*;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.apache.commons.lang3.StringUtils;
import org.mangudai.authenticator.AuthenticatorHandler;
import org.mangudai.filter.HttpFilter;
import org.mangudai.filter.HttpFilterHandler;
import org.mangudai.server.DefaultHttpProxyServer;
import org.mangudai.ssl.SSLHandler;
import org.mangudai.state.ClientStateContext;
import org.mangudai.state.ServerStateContext;
import org.mangudai.state.StateTracker;
import org.mangudai.util.Utility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Created by neo on 21/03/17.
 */
public class ClientConnection extends ProxyConnection<HttpRequest> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientConnection.class);

    private static final HttpResponseStatus CONNECTION_ESTABLISHED = new HttpResponseStatus(200, "Connection established");
    private static final String LOWERCASE_TRANSFER_ENCODING_HEADER = HttpHeaders.Names.TRANSFER_ENCODING.toLowerCase(Locale.US);
    private static final Pattern HTTP_SCHEME = Pattern.compile("^http://.*", Pattern.CASE_INSENSITIVE);
    private final Map<String, ServerConnection> serverConnectionsByHostAndPort = new ConcurrentHashMap<>();
    private final AtomicInteger numberOfCurrentlyConnectingServers = new AtomicInteger(0);
    private final AtomicInteger numberOfCurrentlyConnectedServers = new AtomicInteger(0);
    private final AtomicInteger numberOfReusedServerConnections = new AtomicInteger(0);
    private volatile ServerConnection currentServerConnection;
    private volatile HttpFilter currentFilters = HttpFilterHandler.NOOP_FILTER;
    private volatile SSLSession clientSslSession;
    private volatile boolean mitming = false;
    private AtomicBoolean authenticated = new AtomicBoolean();
    private final GlobalTrafficShapingHandler globalTrafficShapingHandler;
    private volatile HttpRequest currentRequest;

    public ClientConnection(final DefaultHttpProxyServer proxyServer, SSLHandler sslHandler, boolean authenticateClients,
            ChannelPipeline pipeline, GlobalTrafficShapingHandler globalTrafficShapingHandler) {

        super(ConnectionState.AWAITING_INITIAL, proxyServer, false);
        initChannelPipeline(pipeline);

        if (sslHandler != null) {
            LOGGER.debug("Enabling encryption of traffic from client to proxy");

            encrypt(pipeline, sslHandler.newSslEngine(), authenticateClients)
                    .addListener(
                            new GenericFutureListener<Future<? super Channel>>() {
                                @Override
                                public void operationComplete(Future<? super Channel> future) throws Exception {
                                    if (future.isSuccess()) {
                                        clientSslSession = sslEngine.getSession();
                                        recordClientSSLHandshakeSucceeded();
                                    }
                                }
                            });
        }
        this.globalTrafficShapingHandler = globalTrafficShapingHandler;
        LOGGER.debug("Created ClientToProxyConnection");
    }

    @Override
    protected ConnectionState readHTTPInitial(HttpRequest httpRequest) {
        LOGGER.debug("Received raw request: {}", httpRequest);

        // if we cannot parse the request, immediately return a 400 and close the connection, since we do not know what state
        // the client thinks the connection is in
        if (httpRequest.getDecoderResult().isFailure()) {
            LOGGER.debug("Could not parse request from client. Decoder result: {}", httpRequest.getDecoderResult().toString());
            FullHttpResponse response = Utility.createFullHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.BAD_REQUEST,
                    "Unable to parse HTTP request");
            HttpHeaders.setKeepAlive(response, false);
            respondWithShortCircuitResponse(response);
            return ConnectionState.DISCONNECT_REQUESTED;
        }

        boolean authenticationRequired = authenticationRequired(httpRequest);

        if (authenticationRequired) {
            LOGGER.debug("Not authenticated!!");
            return ConnectionState.AWAITING_PROXY_AUTHENTICATION;
        } else {
            return doReadHTTPInitial(httpRequest);
        }
    }

    private ConnectionState doReadHTTPInitial(HttpRequest httpRequest) {
        // Make a copy of the original request
        this.currentRequest = copy(httpRequest);

        // Set up our filters based on the original request. If the HttpFiltersSource returns null (meaning the request/response
        // should not be filtered), fall back to the default no-op filter source.
        HttpFilter filterInstance = proxyServer.getFilterFactory().filterRequest(currentRequest, ctx);
        if (filterInstance != null) {
            currentFilters = filterInstance;
        } else {
            currentFilters = HttpFilterHandler.NOOP_FILTER;
        }

        // Send the request through the clientToProxyRequest filter, and respond with the short-circuit response if required
        HttpResponse clientToProxyFilterResponse = currentFilters.clientToProxyRequest(httpRequest);

        if (clientToProxyFilterResponse != null) {
            LOGGER.debug("Responding to client with short-circuit response from filter: {}", clientToProxyFilterResponse);

            boolean keepAlive = respondWithShortCircuitResponse(clientToProxyFilterResponse);
            if (keepAlive) {
                return ConnectionState.AWAITING_INITIAL;
            } else {
                return ConnectionState.DISCONNECT_REQUESTED;
            }
        }

        // if origin-form requests are not explicitly enabled, short-circuit requests that treat the proxy as the
        // origin server, to avoid infinite loops
        if (!proxyServer.isAllowRequestsToOriginServer() && isRequestToOriginServer(httpRequest)) {
            boolean keepAlive = writeBadRequest(httpRequest);
            if (keepAlive) {
                return ConnectionState.AWAITING_INITIAL;
            } else {
                return ConnectionState.DISCONNECT_REQUESTED;
            }
        }

        // Identify our server and chained proxy
        String serverHostAndPort = identifyHostAndPort(httpRequest);

        LOGGER.debug("Ensuring that hostAndPort are available in {}", httpRequest.getUri());
        if (serverHostAndPort == null || StringUtils.isBlank(serverHostAndPort)) {
            LOGGER.warn("No host and port found in {}", httpRequest.getUri());
            boolean keepAlive = writeBadGateway(httpRequest);
            if (keepAlive) {
                return ConnectionState.AWAITING_INITIAL;
            } else {
                return ConnectionState.DISCONNECT_REQUESTED;
            }
        }

        LOGGER.debug("Finding ProxyToServerConnection for: {}", serverHostAndPort);
        currentServerConnection = isMitming() || isTunneling() ? this.currentServerConnection : this.serverConnectionsByHostAndPort.get(serverHostAndPort);

        boolean newConnectionRequired = false;
        if (Utility.isCONNECT(httpRequest)) {
            LOGGER.debug("Not reusing existing ProxyToServerConnection because request is a CONNECT for: {}", serverHostAndPort);
            newConnectionRequired = true;
        } else if (currentServerConnection == null) {
            LOGGER.debug("Didn't find existing ProxyToServerConnection for: {}",
                    serverHostAndPort);
            newConnectionRequired = true;
        }

        if (newConnectionRequired) {
            try {
                currentServerConnection = ServerConnection.create(
                        proxyServer,
                        this,
                        serverHostAndPort,
                        currentFilters,
                        httpRequest,
                        globalTrafficShapingHandler);
                if (currentServerConnection == null) {
                    LOGGER.debug("Unable to create server connection, probably no chained proxies available");
                    boolean keepAlive = writeBadGateway(httpRequest);
                    resumeReading();
                    if (keepAlive) {
                        return ConnectionState.AWAITING_INITIAL;
                    } else {
                        return ConnectionState.DISCONNECT_REQUESTED;
                    }
                }
                // Remember the connection for later
                serverConnectionsByHostAndPort.put(serverHostAndPort, currentServerConnection);
            } catch (UnknownHostException uhe) {
                LOGGER.info("Bad Host {}", httpRequest.getUri());
                boolean keepAlive = writeBadGateway(httpRequest);
                resumeReading();
                if (keepAlive) {
                    return ConnectionState.AWAITING_INITIAL;
                } else {
                    return ConnectionState.DISCONNECT_REQUESTED;
                }
            }
        } else {
            LOGGER.debug("Reusing existing server connection: {}", currentServerConnection);
            numberOfReusedServerConnections.incrementAndGet();
        }

        modifyRequestHeadersToReflectProxying(httpRequest);
        HttpResponse proxyToServerFilterResponse = currentFilters.proxyToServerRequest(httpRequest);
        if (proxyToServerFilterResponse != null) {
            LOGGER.debug("Responding to client with short-circuit response from filter: {}", proxyToServerFilterResponse);

            boolean keepAlive = respondWithShortCircuitResponse(proxyToServerFilterResponse);
            if (keepAlive) {
                return ConnectionState.AWAITING_INITIAL;
            } else {
                return ConnectionState.DISCONNECT_REQUESTED;
            }
        }

        LOGGER.debug("Writing request to ProxyToServerConnection");
        currentServerConnection.write(httpRequest, currentFilters);

        // Figure out our next state
        if (Utility.isCONNECT(httpRequest)) {
            return ConnectionState.NEGOTIATING_CONNECT;
        } else if (Utility.isChunked(httpRequest)) {
            return ConnectionState.AWAITING_CHUNK;
        } else {
            return ConnectionState.AWAITING_INITIAL;
        }
    }

    private boolean isRequestToOriginServer(HttpRequest httpRequest) {
        // while MITMing, all HTTPS requests are requests to the origin server, since the client does not know
        // the request is being MITM'd by the proxy
        if (httpRequest.getMethod() == HttpMethod.CONNECT || isMitming()) {
            return false;
        }

        // direct requests to the proxy have the path only without a scheme
        String uri = httpRequest.getUri();
        return !HTTP_SCHEME.matcher(uri).matches();
    }

    @Override
    protected void readHTTPChunk(HttpContent chunk) {
        currentFilters.clientToProxyRequest(chunk);
        currentFilters.proxyToServerRequest(chunk);
        currentServerConnection.write(chunk);
    }

    @Override
    protected void readRaw(ByteBuf buf) {
        currentServerConnection.write(buf);
    }

    void respond(ServerConnection serverConnection, HttpFilter filter,
                 HttpRequest currentHttpRequest, HttpResponse currentHttpResponse,
                 HttpObject httpObject) {
        // we are sending a response to the client, so we are done handling this request
        this.currentRequest = null;

        httpObject = filter.serverToProxyResponse(httpObject);
        if (httpObject == null) {
            forceDisconnect(serverConnection);
            return;
        }

        if (httpObject instanceof HttpResponse) {
            HttpResponse httpResponse = (HttpResponse) httpObject;

            // if this HttpResponse does not have any means of signaling the end of the message body other than closing
            // the connection, convert the message to a "Transfer-Encoding: chunked" HTTP response. This avoids the need
            // to close the client connection to indicate the end of the message. (Responses to HEAD requests "must be" empty.)
            if (!Utility.isHEAD(currentHttpRequest) && !Utility.isResponseSelfTerminating(httpResponse)) {
                // if this is not a FullHttpResponse,  duplicate the HttpResponse from the server before sending it to
                // the client. this allows us to set the Transfer-Encoding to chunked without interfering with netty's
                // handling of the response from the server. if we modify the original HttpResponse from the server,
                // netty will not generate the appropriate LastHttpContent when it detects the connection closure from
                // the server (see HttpObjectDecoder#decodeLast). (This does not apply to FullHttpResponses, for which
                // netty already generates the empty final chunk when Transfer-Encoding is chunked.)
                if (!(httpResponse instanceof FullHttpResponse)) {
                    HttpResponse duplicateResponse = Utility.duplicateHttpResponse(httpResponse);

                    // set the httpObject and httpResponse to the duplicated response, to allow all other standard processing
                    // (filtering, header modification for proxying, etc.) to be applied.
                    httpObject = httpResponse = duplicateResponse;
                }

                HttpHeaders.setTransferEncodingChunked(httpResponse);
            }

            fixHttpVersionHeaderIfNecessary(httpResponse);
            modifyResponseHeadersToReflectProxying(httpResponse);
        }

        httpObject = filter.proxyToClientResponse(httpObject);
        if (httpObject == null) {
            forceDisconnect(serverConnection);
            return;
        }
        write(httpObject);
        if (Utility.isLastChunk(httpObject)) {
            writeEmptyBuffer();
        }
        closeConnectionsAfterWriteIfNecessary(serverConnection, currentHttpRequest, currentHttpResponse, httpObject);
    }

    ConnectionStep RespondCONNECTSuccessful = new ConnectionStep(this, ConnectionState.NEGOTIATING_CONNECT) {
        @Override
        boolean shouldSuppressInitialRequest() {
            return true;
        }

        protected Future<?> execute() {
            LOGGER.debug("Responding with CONNECT successful");
            HttpResponse response = Utility.createFullHttpResponse(HttpVersion.HTTP_1_1, CONNECTION_ESTABLISHED);
            response.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
            Utility.addVia(response, proxyServer.getProxyAlias());
            return writeToChannel(response);
        }
    };

    @Override
    protected void connected() {
        super.connected();
        becomeState(ConnectionState.AWAITING_INITIAL);
        recordClientConnected();
    }

    void timedOut(ServerConnection serverConnection) {
        if (currentServerConnection == serverConnection && this.lastReadTime > currentServerConnection.lastReadTime) {
            // the idle timeout fired on the active server connection. send a timeout response to the client.
            LOGGER.warn("Server timed out: {}", currentServerConnection);
            currentFilters.serverResponseTimedOut();
            writeGatewayTimeout(currentRequest);
        }
    }

    @Override
    protected void timedOut() {
        // idle timeout fired on the client channel. if we aren't waiting on a response from a server, hang up
        if (currentServerConnection == null || this.lastReadTime <= currentServerConnection.lastReadTime) {
            super.timedOut();
        }
    }

    @Override
    protected void disconnected() {
        super.disconnected();
        for (ServerConnection serverConnection : serverConnectionsByHostAndPort.values()) {
            serverConnection.disconnect();
        }
        recordClientDisconnected();
    }

    protected void serverConnectionProtocolStarted(ServerConnection serverConnection) {
        stopReading();
        this.numberOfCurrentlyConnectingServers.incrementAndGet();
    }

    protected void serverConnectionSucceeded(ServerConnection serverConnection, boolean shouldForwardInitialRequest) {
        LOGGER.debug("Connection to server succeeded: {}", serverConnection.getRemoteAddress());
        resumeReadingIfNecessary();
        becomeState(shouldForwardInitialRequest ? getCurrentState() : ConnectionState.AWAITING_INITIAL);
        numberOfCurrentlyConnectedServers.incrementAndGet();
    }

    protected boolean serverConnectionFailed(ServerConnection serverConnection, ConnectionState lastStateBeforeFailure, Throwable cause) {
        resumeReadingIfNecessary();
        HttpRequest initialRequest = serverConnection.getInitialRequest();
        try {
            boolean retrying = serverConnection.connectionFailed(cause);
            if (retrying) {
                LOGGER.debug("Failed to connect to upstream server or chained proxy. Retrying connection. Last state before failure: {}", lastStateBeforeFailure, cause);
                return true;
            } else {
                LOGGER.debug(
                        "Connection to upstream server or chained proxy failed: {}.  Last state before failure: {}",
                        serverConnection.getRemoteAddress(),
                        lastStateBeforeFailure,
                        cause);
                connectionFailedUnrecoverably(initialRequest, serverConnection);
                return false;
            }
        } catch (UnknownHostException uhe) {
            connectionFailedUnrecoverably(initialRequest, serverConnection);
            return false;
        }
    }

    private void connectionFailedUnrecoverably(HttpRequest initialRequest, ServerConnection serverConnection) {
        // the connection to the server failed, so disconnect the server and remove the ProxyToServerConnection from the
        // map of open server connections
        serverConnection.disconnect();
        this.serverConnectionsByHostAndPort.remove(serverConnection.getServerHostAndPort());

        boolean keepAlive = writeBadGateway(initialRequest);
        if (keepAlive) {
            becomeState(ConnectionState.AWAITING_INITIAL);
        } else {
            becomeState(ConnectionState.DISCONNECT_REQUESTED);
        }
    }

    private void resumeReadingIfNecessary() {
        if (this.numberOfCurrentlyConnectingServers.decrementAndGet() == 0) {
            LOGGER.debug("All servers have finished attempting to connect, resuming reading from client.");
            resumeReading();
        }
    }

    protected void serverDisconnected(ServerConnection serverConnection) {
        numberOfCurrentlyConnectedServers.decrementAndGet();

        // for non-SSL connections, do not disconnect the client from the proxy, even if this was the last server connection.
        // this allows clients to continue to use the open connection to the proxy to make future requests. for SSL
        // connections, whether we are tunneling or MITMing, we need to disconnect the client because there is always
        // exactly one ClientToProxyConnection per ProxyToServerConnection, and vice versa.
        if (isTunneling() || isMitming()) {
            disconnect();
        }
    }

    @Override
    synchronized protected void becameSaturated() {
        super.becameSaturated();
        for (ServerConnection serverConnection : serverConnectionsByHostAndPort.values()) {
            synchronized (serverConnection) {
                if (this.isSaturated()) {
                    serverConnection.stopReading();
                }
            }
        }
    }

    @Override
    synchronized protected void becameWritable() {
        super.becameWritable();
        for (ServerConnection serverConnection : serverConnectionsByHostAndPort.values()) {
            synchronized (serverConnection) {
                if (!this.isSaturated()) {
                    serverConnection.resumeReading();
                }
            }
        }
    }

    synchronized protected void serverBecameSaturated(ServerConnection serverConnection) {
        if (serverConnection.isSaturated()) {
            LOGGER.info("Connection to server became saturated, stopping reading");
            stopReading();
        }
    }

    synchronized protected void serverBecameWriteable(ServerConnection serverConnection) {
        boolean anyServersSaturated = false;
        for (ServerConnection otherServerConnection : serverConnectionsByHostAndPort.values()) {
            if (otherServerConnection.isSaturated()) {
                anyServersSaturated = true;
                break;
            }
        }
        if (!anyServersSaturated) {
            LOGGER.info("All server connections writeable, resuming reading");
            resumeReading();
        }
    }

    @Override
    protected void exceptionCaught(Throwable cause) {
        try {
            if (cause instanceof IOException) {
                // IOExceptions are expected errors, for example when a browser is killed and aborts a connection.
                // rather than flood the logs with stack traces for these expected exceptions, we log the message at the
                // INFO level and the stack trace at the DEBUG level.
                LOGGER.info("An IOException occurred on ClientToProxyConnection: " + cause.getMessage());
                LOGGER.debug("An IOException occurred on ClientToProxyConnection", cause);
            } else if (cause instanceof RejectedExecutionException) {
                LOGGER.info("An executor rejected a read or write operation on the ClientToProxyConnection (this is normal if the proxy is shutting down). Message: " + cause.getMessage());
                LOGGER.debug("A RejectedExecutionException occurred on ClientToProxyConnection", cause);
            } else {
                LOGGER.error("Caught an exception on ClientToProxyConnection", cause);
            }
        } finally {
            // always disconnect the client when an exception occurs on the channel
            disconnect();
        }
    }

    private void initChannelPipeline(ChannelPipeline pipeline) {
        LOGGER.debug("Configuring ChannelPipeline");

        pipeline.addLast("bytesReadMonitor", bytesReadMonitor);
        pipeline.addLast("bytesWrittenMonitor", bytesWrittenMonitor);

        pipeline.addLast("encoder", new HttpResponseEncoder());
        // We want to allow longer request lines, headers, and chunks
        // respectively.
        pipeline.addLast("decoder", new HttpRequestDecoder(proxyServer.getMaxInitialLineLength(), proxyServer.getMaxHeaderSize(), proxyServer.getMaxChunkSize()));

        // Enable aggregation for filtering if necessary
        int numberOfBytesToBuffer = proxyServer.getFilterFactory().getMaximumRequestBufferSizeInBytes();
        if (numberOfBytesToBuffer > 0) {
            aggregateContentForFiltering(pipeline, numberOfBytesToBuffer);
        }
        pipeline.addLast("requestReadMonitor", requestReadMonitor);
        pipeline.addLast("responseWrittenMonitor", responseWrittenMonitor);
        pipeline.addLast( "idle", new IdleStateHandler(0, 0, proxyServer.getIdleConnectionTimeout()));
        pipeline.addLast("handler", this);
    }

    private void closeConnectionsAfterWriteIfNecessary(ServerConnection serverConnection,
            HttpRequest currentHttpRequest, HttpResponse currentHttpResponse, HttpObject httpObject) {
        boolean closeServerConnection = shouldCloseServerConnection(currentHttpRequest, currentHttpResponse, httpObject);
        boolean closeClientConnection = shouldCloseClientConnection(currentHttpRequest, currentHttpResponse, httpObject);

        if (closeServerConnection) {
            LOGGER.debug("Closing remote connection after writing to client");
            serverConnection.disconnect();
        }

        if (closeClientConnection) {
            LOGGER.debug("Closing connection to client after writes");
            disconnect();
        }
    }

    private void forceDisconnect(ServerConnection serverConnection) {
        LOGGER.debug("Forcing disconnect");
        serverConnection.disconnect();
        disconnect();
    }

    private boolean shouldCloseClientConnection(HttpRequest req, HttpResponse res, HttpObject httpObject) {
        if (Utility.isChunked(res)) {
            // If the response is chunked, we want to return false unless it's
            // the last chunk. If it is the last chunk, then we want to pass
            // through to the same close semantics we'd otherwise use.
            if (httpObject != null) {
                if (!Utility.isLastChunk(httpObject)) {
                    String uri = null;
                    if (req != null) {
                        uri = req.getUri();
                    }
                    LOGGER.debug("Not closing client connection on middle chunk for {}", uri);
                    return false;
                } else {
                    LOGGER.debug("Handling last chunk. Using normal client connection closing rules.");
                }
            }
        }

        if (!HttpHeaders.isKeepAlive(req)) {
            LOGGER.debug("Closing client connection since request is not keep alive: {}", req);
            // Here we simply want to close the connection because the
            // client itself has requested it be closed in the request.
            return true;
        }

        // ignore the response's keep-alive; we can keep this client connection open as long as the client allows it.
        LOGGER.debug("Not closing client connection for request: {}", req);
        return false;
    }

    private boolean shouldCloseServerConnection(HttpRequest req, HttpResponse res, HttpObject msg) {
        if (Utility.isChunked(res)) {
            // If the response is chunked, we want to return false unless it's
            // the last chunk. If it is the last chunk, then we want to pass
            // through to the same close semantics we'd otherwise use.
            if (msg != null) {
                if (!Utility.isLastChunk(msg)) {
                    String uri = null;
                    if (req != null) {
                        uri = req.getUri();
                    }
                    LOGGER.debug("Not closing server connection on middle chunk for {}", uri);
                    return false;
                } else {
                    LOGGER.debug("Handling last chunk. Using normal server connection closing rules.");
                }
            }
        }

        // ignore the request's keep-alive; we can keep this server connection open as long as the server allows it.

        if (!HttpHeaders.isKeepAlive(res)) {
            LOGGER.debug("Closing server connection since response is not keep alive: {}", res);
            // In this case, we want to honor the Connection: close header
            // from the remote server and close that connection. We don't
            // necessarily want to close the connection to the client, however
            // as it's possible it has other connections open.
            return true;
        }

        LOGGER.debug("Not closing server connection for response: {}", res);
        return false;
    }

    private boolean authenticationRequired(HttpRequest request) {
        if (authenticated.get()) {
            return false;
        }
        final AuthenticatorHandler authenticator = proxyServer.getAuthenticatorHandler();
        if (authenticator == null)
            return false;
        if (!request.headers().contains(HttpHeaders.Names.PROXY_AUTHORIZATION)) {
            writeAuthenticationRequired(authenticator.getRealm());
            return true;
        }
        List<String> values = request.headers().getAll(HttpHeaders.Names.PROXY_AUTHORIZATION);
        String fullValue = values.iterator().next();
        String value = StringUtils.substringAfter(fullValue, "Basic ").trim();
        byte[] decodedValue = BaseEncoding.base64().decode(value);
        String decodedString = new String(decodedValue, Charset.forName("UTF-8"));
        String userName = StringUtils.substringBefore(decodedString, ":");
        String password = StringUtils.substringAfter(decodedString, ":");
        if (!authenticator.authenticate(userName, password)) {
            writeAuthenticationRequired(authenticator.getRealm());
            return true;
        }
        LOGGER.debug("Got proxy authorization!");
        // We need to remove the header before sending the request on.
        String authentication = request.headers().get(HttpHeaders.Names.PROXY_AUTHORIZATION);
        LOGGER.debug(authentication);
        request.headers().remove(HttpHeaders.Names.PROXY_AUTHORIZATION);
        authenticated.set(true);
        return false;
    }

    private void writeAuthenticationRequired(String realm) {
        String body = "<!DOCTYPE HTML \"-//IETF//DTD HTML 2.0//EN\">\n"
                + "<html><head>\n"
                + "<title>407 Proxy Authentication Required</title>\n"
                + "</head><body>\n"
                + "<h1>Proxy Authentication Required</h1>\n"
                + "<p>This server could not verify that you\n"
                + "are authorized to access the document\n"
                + "requested.  Either you supplied the wrong\n"
                + "credentials (e.g., bad password), or your\n"
                + "browser doesn't understand how to supply\n"
                + "the credentials required.</p>\n" + "</body></html>\n";
        FullHttpResponse response = Utility.createFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.PROXY_AUTHENTICATION_REQUIRED, body);
        HttpHeaders.setDate(response, new Date());
        response.headers().set("Proxy-Authenticate",
                "Basic realm=\"" + (realm == null ? "Restricted Files" : realm) + "\"");
        write(response);
    }

    private HttpRequest copy(HttpRequest original) {
        if (original instanceof FullHttpRequest) {
            return ((FullHttpRequest) original).copy();
        } else {
            HttpRequest request = new DefaultHttpRequest(original.getProtocolVersion(),
                    original.getMethod(), original.getUri());
            request.headers().set(original.headers());
            return request;
        }
    }

    private void fixHttpVersionHeaderIfNecessary(HttpResponse httpResponse) {
        String te = httpResponse.headers().get(HttpHeaders.Names.TRANSFER_ENCODING);
        if (StringUtils.isNotBlank(te)
                && te.equalsIgnoreCase(HttpHeaders.Values.CHUNKED)) {
            if (httpResponse.getProtocolVersion() != HttpVersion.HTTP_1_1) {
                LOGGER.debug("Fixing HTTP version.");
                httpResponse.setProtocolVersion(HttpVersion.HTTP_1_1);
            }
        }
    }

    private void modifyRequestHeadersToReflectProxying(HttpRequest httpRequest) {
        LOGGER.debug("Modifying request for proxy chaining");
        // Strip host from uri
        String uri = httpRequest.getUri();
        String adjustedUri = Utility.stripHost(uri);
        LOGGER.debug("Stripped host from uri: {}    yielding: {}", uri, adjustedUri);
        httpRequest.setUri(adjustedUri);
//        if (!currentServerConnection.hasUpstreamChainedProxy()) {
//
//        }
        if (!proxyServer.isTransparent()) {
            LOGGER.debug("Modifying request headers for proxying");
            HttpHeaders headers = httpRequest.headers();
            // Remove sdch from encodings we accept since we can't decode it.
            Utility.removeSdchEncoding(headers);
            switchProxyConnectionHeader(headers);
            stripConnectionTokens(headers);
            stripHopByHopHeaders(headers);
            Utility.addVia(httpRequest, proxyServer.getProxyAlias());
        }
    }

    private void modifyResponseHeadersToReflectProxying(HttpResponse httpResponse) {
        if (!proxyServer.isTransparent()) {
            HttpHeaders headers = httpResponse.headers();
            stripConnectionTokens(headers);
            stripHopByHopHeaders(headers);
            Utility.addVia(httpResponse, proxyServer.getProxyAlias());

            /*
             * RFC2616 Section 14.18
             *
             * A received message that does not have a Date header field MUST be
             * assigned one by the recipient if the message will be cached by
             * that recipient or gatewayed via a protocol which requires a Date.
             */
            if (!headers.contains(HttpHeaders.Names.DATE)) {
                HttpHeaders.setDate(httpResponse, new Date());
            }
        }
    }

    private void switchProxyConnectionHeader(HttpHeaders headers) {
        String proxyConnectionKey = "Proxy-Connection";
        if (headers.contains(proxyConnectionKey)) {
            String header = headers.get(proxyConnectionKey);
            headers.remove(proxyConnectionKey);
            headers.set(HttpHeaders.Names.CONNECTION, header);
        }
    }

    private void stripConnectionTokens(HttpHeaders headers) {
        if (headers.contains(HttpHeaders.Names.CONNECTION)) {
            for (String headerValue : headers.getAll(HttpHeaders.Names.CONNECTION)) {
                for (String connectionToken : Utility.splitCommaSeparatedHeaderValues(headerValue)) {
                    // do not strip out the Transfer-Encoding header if it is specified in the Connection header, since LittleProxy does not
                    // normally modify the Transfer-Encoding of the message.
                    if (!LOWERCASE_TRANSFER_ENCODING_HEADER.equals(connectionToken.toLowerCase(Locale.US))) {
                        headers.remove(connectionToken);
                    }
                }
            }
        }
    }

    private void stripHopByHopHeaders(HttpHeaders headers) {
        Set<String> headerNames = headers.names();
        for (String headerName : headerNames) {
            if (Utility.shouldRemoveHopByHopHeader(headerName)) {
                headers.remove(headerName);
            }
        }
    }

    private boolean writeBadGateway(HttpRequest httpRequest) {
        String body = "Bad Gateway: " + httpRequest.getUri();
        FullHttpResponse response = Utility.createFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_GATEWAY, body);
        if (Utility.isHEAD(httpRequest)) {
            // don't allow any body content in response to a HEAD request
            response.content().clear();
        }
        return respondWithShortCircuitResponse(response);
    }

    private boolean writeBadRequest(HttpRequest httpRequest) {
        String body = "Bad Request to URI: " + httpRequest.getUri();
        FullHttpResponse response = Utility.createFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST, body);
        if (Utility.isHEAD(httpRequest)) {
            // don't allow any body content in response to a HEAD request
            response.content().clear();
        }
        return respondWithShortCircuitResponse(response);
    }

    private boolean writeGatewayTimeout(HttpRequest httpRequest) {
        String body = "Gateway Timeout";
        FullHttpResponse response = Utility.createFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.GATEWAY_TIMEOUT, body);
        if (httpRequest != null && Utility.isHEAD(httpRequest)) {
            // don't allow any body content in response to a HEAD request
            response.content().clear();
        }
        return respondWithShortCircuitResponse(response);
    }

    private boolean respondWithShortCircuitResponse(HttpResponse httpResponse) {
        // we are sending a response to the client, so we are done handling this request
        this.currentRequest = null;

        HttpResponse filteredResponse = (HttpResponse) currentFilters.proxyToClientResponse(httpResponse);
        if (filteredResponse == null) {
            disconnect();
            return false;
        }
        boolean isKeepAlive = HttpHeaders.isKeepAlive(httpResponse);
        int statusCode = httpResponse.getStatus().code();
        if (statusCode != HttpResponseStatus.BAD_GATEWAY.code() && statusCode != HttpResponseStatus.GATEWAY_TIMEOUT.code()) {
            modifyResponseHeadersToReflectProxying(httpResponse);
        }
        HttpHeaders.setKeepAlive(httpResponse, isKeepAlive);
        write(httpResponse);
        if (Utility.isLastChunk(httpResponse)) {
            writeEmptyBuffer();
        }
        if (!HttpHeaders.isKeepAlive(httpResponse)) {
            disconnect();
            return false;
        }
        return true;
    }

    private String identifyHostAndPort(HttpRequest httpRequest) {
        String hostAndPort = Utility.parseHostAndPort(httpRequest);
        if (StringUtils.isBlank(hostAndPort)) {
            List<String> hosts = httpRequest.headers().getAll(
                    HttpHeaders.Names.HOST);
            if (hosts != null && !hosts.isEmpty()) {
                hostAndPort = hosts.get(0);
            }
        }
        return hostAndPort;
    }

    private void writeEmptyBuffer() {
        write(Unpooled.EMPTY_BUFFER);
    }

    public boolean isMitming() {
        return mitming;
    }

    protected void setMitming(boolean isMitming) {
        this.mitming = isMitming;
    }

    private final BytesReadMonitor bytesReadMonitor = new BytesReadMonitor() {
        @Override
        protected void bytesRead(int numberOfBytes) {
            ClientStateContext clientContext = getContext();
            for (StateTracker tracker : proxyServer.getStateTrackerQueue()) {
                tracker.bytesReceivedFromClient(clientContext, numberOfBytes);
            }
        }
    };

    private RequestReadMonitor requestReadMonitor = new RequestReadMonitor() {
        @Override
        protected void requestRead(HttpRequest httpRequest) {
            ClientStateContext clientContext = getContext();
            for (StateTracker tracker : proxyServer.getStateTrackerQueue()) {
                tracker.requestReceivedFromClient(clientContext, httpRequest);
            }
        }
    };

    private BytesWrittenMonitor bytesWrittenMonitor = new BytesWrittenMonitor() {
        @Override
        protected void bytesWritten(int numberOfBytes) {
            ClientStateContext clientContext = getContext();
            for (StateTracker tracker : proxyServer.getStateTrackerQueue()) {
                tracker.bytesSentToClient(clientContext, numberOfBytes);
            }
        }
    };

    private ResponseWrittenMonitor responseWrittenMonitor = new ResponseWrittenMonitor() {
        @Override
        protected void responseWritten(HttpResponse httpResponse) {
            ClientStateContext clientContext = getContext();
            for (StateTracker tracker : proxyServer.getStateTrackerQueue()) {
                tracker.responseSentToClient(clientContext,httpResponse);
            }
        }
    };

    private void recordClientConnected() {
        try {
            InetSocketAddress clientAddress = getClientAddress();
            for (StateTracker tracker : proxyServer.getStateTrackerQueue()) {
                tracker.clientConnected(clientAddress);
            }
        } catch (Exception e) {
            LOGGER.error("Unable to recordClientConnected", e);
        }
    }

    private void recordClientSSLHandshakeSucceeded() {
        try {
            InetSocketAddress clientAddress = getClientAddress();
            for (StateTracker tracker : proxyServer.getStateTrackerQueue()) {
                tracker.clientSSLHandshakeSucceeded(clientAddress, clientSslSession);
            }
        } catch (Exception e) {
            LOGGER.error("Unable to recorClientSSLHandshakeSucceeded", e);
        }
    }

    private void recordClientDisconnected() {
        try {
            InetSocketAddress clientAddress = getClientAddress();
            for (StateTracker tracker : proxyServer.getStateTrackerQueue()) {
                tracker.clientDisconnected(clientAddress, clientSslSession);
            }
        } catch (Exception e) {
            LOGGER.error("Unable to recordClientDisconnected", e);
        }
    }

    public InetSocketAddress getClientAddress() {
        if (channel == null) {
            return null;
        }
        return (InetSocketAddress) channel.remoteAddress();
    }

    private ClientStateContext getContext() {
        if (currentServerConnection != null) {
            return new ServerStateContext(this, currentServerConnection);
        } else {
            return new ClientStateContext(this);
        }
    }

}
