/*
 * Copyright (c) 2017. TechHive Software Labs, Inc - All Rights Reserved.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 * Proprietary and confidential
 */

package org.mangudai.connection;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Created by neo on 21/03/17.
 */
public class ConnectionProtocol {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectionProtocol.class);

    private Queue<ConnectionStep> steps = new ConcurrentLinkedDeque<ConnectionStep>();

    private final ClientConnection clientConnection;
    private final ServerConnection serverConnection;
    private volatile ConnectionStep currentStep;
    private volatile boolean suppressInitialRequest = false;
    private final Object connectLock;

    public ConnectionProtocol(ClientConnection clientConnection, ServerConnection serverConnection, Object connectLock) {
        this.clientConnection = clientConnection;
        this.serverConnection = serverConnection;
        this.connectLock = connectLock;
    }

    ConnectionProtocol then(ConnectionStep step) {
        steps.add(step);
        return this;
    }

    void read(Object msg) {
        if (this.currentStep != null) {
            this.currentStep.read(this, msg);
        }
    }


    void start() {
        clientConnection.serverConnectionProtocolStarted(serverConnection);
        advance();
    }

    void advance() {
        currentStep = steps.poll();
        if (currentStep == null) {
            succeed();
        } else {
            processCurrentStep();
        }
    }

    private void processCurrentStep() {
        final ProxyConnection connection = currentStep.getConnection();
        LOGGER.debug("Processing connection flow step: {}", currentStep);
        connection.becomeState(currentStep.getConnectionState());
        suppressInitialRequest = suppressInitialRequest || currentStep.shouldSuppressInitialRequest();
        if (currentStep.shouldExecuteOnEventLoop()) {
            connection.ctx.executor().submit((Runnable) () -> {doProcessCurrentStep();});
        } else {
            doProcessCurrentStep();
        }
    }

    private void doProcessCurrentStep() {
        currentStep.execute().addListener((GenericFutureListener) (future) -> {
            synchronized (connectLock) {
                if (future.isSuccess()) {
                    LOGGER.debug("ConnectionFlowStep succeeded");
                    currentStep.onSuccess(ConnectionProtocol.this);
                } else {
                    LOGGER.debug("ConnectionFlowStep failed",
                            future.cause());
                    fail(future.cause());
                }
            }
        });
    }

    void succeed() {
        synchronized (connectLock) {
            LOGGER.debug("Connection flow completed successfully: {}", currentStep);
            serverConnection.connectionSucceeded(!suppressInitialRequest);
            notifyThreadsWaitingForConnection();
        }
    }

    @SuppressWarnings("unchecked")
    void fail(final Throwable cause) {
        final ConnectionState lastStateBeforeFailure = serverConnection.getCurrentState();
        serverConnection.disconnect().addListener((GenericFutureListener) (future) -> {
            synchronized (connectLock) {
                if (!clientConnection.serverConnectionFailed(serverConnection, lastStateBeforeFailure,cause)) {
                    // the connection to the server failed and we are not retrying, so transition to the
                    // DISCONNECTED state
                    serverConnection.becomeState(ConnectionState.DISCONNECTED);

                    // We are not retrying our connection, let anyone waiting for a connection know that we're done
                    notifyThreadsWaitingForConnection();
                }
            }
        });
    }

    void fail() {
        fail(null);
    }

    private void notifyThreadsWaitingForConnection() {
        connectLock.notifyAll();
    }

}
