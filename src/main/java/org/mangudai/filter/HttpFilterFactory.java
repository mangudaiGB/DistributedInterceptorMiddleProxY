/*
 * Copyright (c) 2017. TechHive Software Labs, Inc - All Rights Reserved.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 * Proprietary and confidential
 */

package org.mangudai.filter;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;

/**
 * Created by neo on 21/03/17.
 */
public class HttpFilterFactory implements HttpFilterFactoryBase {

    public int getMaximumRequestBufferSizeInBytes() {
        return 0;
    }

    public int getMaximumResponseBufferSizeInBytes() {
        return 0;
    }

    public HttpFilter filterRequest(HttpRequest request, ChannelHandlerContext context) {
        return new HttpFilterHandler(request, context);
    }
}
