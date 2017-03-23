/*
 * Copyright (c) 2017. TechHive Software Labs, Inc - All Rights Reserved.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 * Proprietary and confidential
 */

package org.mangudai.network;

import org.littleshoot.dnssec4j.VerifiedAddressFactory;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;

/**
 * Created by neo on 22/03/17.
 */
public class DnsSecServerResolver implements HostResolver {
    @Override
    public InetSocketAddress resolve(String host, int port) throws UnknownHostException {
        return VerifiedAddressFactory.newInetSocketAddress(host, port, true);
    }
}
