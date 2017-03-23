/*
 * Copyright (c) 2017. TechHive Software Labs, Inc - All Rights Reserved.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 * Proprietary and confidential
 */

package org.mangudai.connection;

/**
 * Created by neo on 21/03/17.
 */
public enum ConnectionState {
    CONNECTING(true),
    HANDSHAKING(true),
    NEGOTIATING_CONNECT(true),
    // Tobe used for chaines proxy
     AWAITING_CONNECT_OK(true),
    AWAITING_PROXY_AUTHENTICATION,
    AWAITING_INITIAL,
    AWAITING_CHUNK,
    DISCONNECT_REQUESTED(),
    DISCONNECTED();

    private final boolean partOfConnectionProtocol;

    ConnectionState(boolean partOfConnectionProtocol) {
        this.partOfConnectionProtocol = partOfConnectionProtocol;
    }

    ConnectionState() {
        this(false);
    }
    public boolean isPartOfConnectionProtocol() {
        return partOfConnectionProtocol;
    }
    // Not waiting for messages
    public boolean isDisconnectingOrDisconnected() {
        return this == DISCONNECT_REQUESTED || this == DISCONNECTED;
    }
}
