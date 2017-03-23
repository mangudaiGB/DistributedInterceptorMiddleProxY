/*
 * Copyright (c) 2017. TechHive Software Labs, Inc - All Rights Reserved.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 * Proprietary and confidential
 */

package org.mangudai.network;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;

/**
 * Created by neo on 21/03/17.
 */
public interface HostResolver {
    InetSocketAddress resolve(String host, int port) throws UnknownHostException;
}
