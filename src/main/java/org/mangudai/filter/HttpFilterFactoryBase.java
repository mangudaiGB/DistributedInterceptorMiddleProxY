/*
 * Copyright (c) 2017. TechHive Software Labs, Inc - All Rights Reserved.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 * Proprietary and confidential
 */

package org.mangudai.filter;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;

/**
 * Created by neo on 22/03/17.
 */
public interface HttpFilterFactoryBase {
    HttpFilter filterRequest(HttpRequest originalRequest, ChannelHandlerContext ctx);
    int getMaximumRequestBufferSizeInBytes();
    int getMaximumResponseBufferSizeInBytes();
}
