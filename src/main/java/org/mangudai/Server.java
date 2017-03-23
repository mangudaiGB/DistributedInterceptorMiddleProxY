/*
 * Copyright (c) 2017. TechHive Software Labs, Inc - All Rights Reserved.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 * Proprietary and confidential
 */

package org.mangudai;

import org.mangudai.bootstrap.DefaultProxyServerBootstrap;
import org.mangudai.bootstrap.ProxyServerBootstrap;
import org.mangudai.filter.HttpFilterFactory;
import org.mangudai.util.ServerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by neo on 21/03/17.
 */
public class Server {

    private static final Logger LOGGER = LoggerFactory.getLogger(Server.class);

    private static final String OPTION_DNSSEC = "dnssec";

    private static final String OPTION_PORT = "port";

    private static final String OPTION_HELP = "help";

    private static final String OPTION_MITM = "mitm";

    private static final String OPTION_NIC = "nic";

    public static void main(String args[]) {
        System.out.println("Hello World!!");
        int port = Integer.parseInt(ServerProperties.INSTANCE.getProperty("server.port"));
        ProxyServerBootstrap bootstrap = new DefaultProxyServerBootstrap()
                .withPort(port)
                .withAllowLocalOnly(true)
                .withFilterFactory(new HttpFilterFactory());
        System.out.println("Server has started.....");
        bootstrap.start();
    }
}
