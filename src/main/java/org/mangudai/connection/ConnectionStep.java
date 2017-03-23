/*
 * Copyright (c) 2017. TechHive Software Labs, Inc - All Rights Reserved.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 * Proprietary and confidential
 */

package org.mangudai.connection;

import io.netty.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by neo on 21/03/17.
 */
public abstract class ConnectionStep {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectionStep.class);

    private final ProxyConnection connection;
    private final ConnectionState connectionState;

    ConnectionStep(ProxyConnection connection, ConnectionState state) {
        this.connection = connection;
        this.connectionState = state;
    }

    ProxyConnection getConnection() {
        return connection;
    }

    ConnectionState getConnectionState() {
        return connectionState;
    }

    boolean shouldSuppressInitialRequest() {
        return false;
    }

    boolean shouldExecuteOnEventLoop() {
        return true;
    }

    protected abstract Future execute();

    void onSuccess(ConnectionProtocol protocol) {
        protocol.advance();
    }

    void read(ConnectionProtocol protocol, Object message) {
        LOGGER.debug("Received message while in the middle of connecting: {}", message);
    }

    @Override
    public String toString() {
        return connectionState.toString();
    }
}
