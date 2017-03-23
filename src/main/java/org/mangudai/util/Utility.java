/*
 * Copyright (c) 2017. TechHive Software Labs, Inc - All Rights Reserved.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 * Proprietary and confidential
 */

package org.mangudai.util;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.udt.nio.NioUdtProvider;
import io.netty.handler.codec.http.*;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Created by neo on 22/03/17.
 */
public class Utility {

    private static final Set<String> SHOULD_NOT_PROXY_HOP_BY_HOP_HEADERS = ImmutableSet.of(
            HttpHeaders.Names.CONNECTION.toLowerCase(Locale.US),
            HttpHeaders.Names.PROXY_AUTHENTICATE.toLowerCase(Locale.US),
            HttpHeaders.Names.PROXY_AUTHORIZATION.toLowerCase(Locale.US),
            HttpHeaders.Names.TE.toLowerCase(Locale.US),
            HttpHeaders.Names.TRAILER.toLowerCase(Locale.US),
            /*  Note: Not removing Transfer-Encoding since LittleProxy does not normally re-chunk content.
                HttpHeaders.Names.TRANSFER_ENCODING.toLowerCase(Locale.US), */
            HttpHeaders.Names.UPGRADE.toLowerCase(Locale.US),
            "Keep-Alive".toLowerCase(Locale.US)
    );

    private static final Logger LOGGER = LoggerFactory.getLogger(Utility.class);
    private static final TimeZone GMT = TimeZone.getTimeZone("GMT");
    private static final Splitter COMMA_SEPARATED_HEADER_VALUE_SPLITTER = Splitter.on(',').trimResults().omitEmptyStrings();
    private static final String PATTERN_RFC1123 = "EEE, dd MMM yyyy HH:mm:ss zzz";
    private static Pattern HTTP_PREFIX = Pattern.compile("^https?://.*", Pattern.CASE_INSENSITIVE);


    public static String stripHost(final String uri) {
        if (!HTTP_PREFIX.matcher(uri).matches()) {
            return uri;
        }
        final String noHttpUri = StringUtils.substringAfter(uri, "://");
        final int slashIndex = noHttpUri.indexOf("/");
        if (slashIndex == -1) {
            return "/";
        }
        final String noHostUri = noHttpUri.substring(slashIndex);
        return noHostUri;
    }

    public static String formatDate(final Date date) {
        return formatDate(date, PATTERN_RFC1123);
    }

    public static String formatDate(final Date date, final String pattern) {
        if (date == null)
            throw new IllegalArgumentException("date is null");
        if (pattern == null)
            throw new IllegalArgumentException("pattern is null");

        final SimpleDateFormat formatter = new SimpleDateFormat(pattern,
                Locale.US);
        formatter.setTimeZone(GMT);
        return formatter.format(date);
    }

    public static boolean isLastChunk(final HttpObject httpObject) {
        return httpObject instanceof LastHttpContent;
    }

    public static boolean isChunked(final HttpObject httpObject) {
        return !isLastChunk(httpObject);
    }

    public static String parseHostAndPort(final HttpRequest httpRequest) {
        final String uriHostAndPort = parseHostAndPort(httpRequest.getUri());
        return uriHostAndPort;
    }

    public static String parseHostAndPort(final String uri) {
        final String tempUri;
        if (!HTTP_PREFIX.matcher(uri).matches()) {
            // Browsers particularly seem to send requests in this form when
            // they use CONNECT.
            tempUri = uri;
        } else {
            // We can't just take a substring from a hard-coded index because it
            // could be either http or https.
            tempUri = StringUtils.substringAfter(uri, "://");
        }
        final String hostAndPort;
        if (tempUri.contains("/")) {
            hostAndPort = tempUri.substring(0, tempUri.indexOf("/"));
        } else {
            hostAndPort = tempUri;
        }
        return hostAndPort;
    }

    public static HttpResponse copyMutableResponseFields(
            final HttpResponse original) {

        HttpResponse copy = null;
        if (original instanceof DefaultFullHttpResponse) {
            ByteBuf content = ((DefaultFullHttpResponse) original).content();
            copy = new DefaultFullHttpResponse(original.getProtocolVersion(),
                    original.getStatus(), content);
        } else {
            copy = new DefaultHttpResponse(original.getProtocolVersion(),
                    original.getStatus());
        }
        final Collection<String> headerNames = original.headers().names();
        for (final String name : headerNames) {
            final List<String> values = original.headers().getAll(name);
            copy.headers().set(name, values);
        }
        return copy;
    }

    public static void addVia(HttpMessage httpMessage, String alias) {
        String newViaHeader =  new StringBuilder()
                .append(httpMessage.getProtocolVersion().majorVersion())
                .append('.')
                .append(httpMessage.getProtocolVersion().minorVersion())
                .append(' ')
                .append(alias)
                .toString();

        final List<String> vias;
        if (httpMessage.headers().contains(HttpHeaders.Names.VIA)) {
            List<String> existingViaHeaders = httpMessage.headers().getAll(HttpHeaders.Names.VIA);
            vias = new ArrayList<String>(existingViaHeaders);
            vias.add(newViaHeader);
        } else {
            vias = Collections.singletonList(newViaHeader);
        }

        httpMessage.headers().set(HttpHeaders.Names.VIA, vias);
    }

    public static boolean isTrue(final String val) {
        return checkTrueOrFalse(val, "true", "on");
    }

    public static boolean isFalse(final String val) {
        return checkTrueOrFalse(val, "false", "off");
    }

    public static boolean extractBooleanDefaultFalse(final Properties props,
                                                     final String key) {
        final String throttle = props.getProperty(key);
        if (StringUtils.isNotBlank(throttle)) {
            return throttle.trim().equalsIgnoreCase("true");
        }
        return false;
    }

    public static boolean extractBooleanDefaultTrue(final Properties props,
                                                    final String key) {
        final String throttle = props.getProperty(key);
        if (StringUtils.isNotBlank(throttle)) {
            return throttle.trim().equalsIgnoreCase("true");
        }
        return true;
    }

    public static int extractInt(final Properties props, final String key) {
        return extractInt(props, key, -1);
    }

    public static int extractInt(final Properties props, final String key, int defaultValue) {
        final String readThrottleString = props.getProperty(key);
        if (StringUtils.isNotBlank(readThrottleString) &&
                NumberUtils.isNumber(readThrottleString)) {
            return Integer.parseInt(readThrottleString);
        }
        return defaultValue;
    }

    public static boolean isCONNECT(HttpObject httpObject) {
        return httpObject instanceof HttpRequest
                && HttpMethod.CONNECT.equals(((HttpRequest) httpObject)
                .getMethod());
    }

    public static boolean isHEAD(HttpRequest httpRequest) {
        return HttpMethod.HEAD.equals(httpRequest.getMethod());
    }

    private static boolean checkTrueOrFalse(final String val,
                                            final String str1, final String str2) {
        final String str = val.trim();
        return StringUtils.isNotBlank(str)
                && (str.equalsIgnoreCase(str1) || str.equalsIgnoreCase(str2));
    }

    public static boolean isContentAlwaysEmpty(HttpMessage msg) {
        if (msg instanceof HttpResponse) {
            HttpResponse res = (HttpResponse) msg;
            int code = res.getStatus().code();

            // Correctly handle return codes of 1xx.
            //
            // See:
            //     - http://www.w3.org/Protocols/rfc2616/rfc2616-sec4.html Section 4.4
            //     - https://github.com/netty/netty/issues/222
            if (code >= 100 && code < 200) {
                // According to RFC 7231, section 6.1, 1xx responses have no content (https://tools.ietf.org/html/rfc7231#section-6.2):
                //   1xx responses are terminated by the first empty line after
                //   the status-line (the empty line signaling the end of the header
                //        section).

                // Hixie 76 websocket handshake responses contain a 16-byte body, so their content is not empty; but Hixie 76
                // was a draft specification that was superceded by RFC 6455. Since it is rarely used and doesn't conform to
                // RFC 7231, we do not support or make special allowance for Hixie 76 responses.
                return true;
            }

            switch (code) {
                case 204: case 205: case 304:
                    return true;
            }
        }
        return false;
    }

    public static boolean isResponseSelfTerminating(HttpResponse response) {
        if (isContentAlwaysEmpty(response)) {
            return true;
        }

        // if there is a Transfer-Encoding value, determine whether the final encoding is "chunked", which makes the message self-terminating
        List<String> allTransferEncodingHeaders = getAllCommaSeparatedHeaderValues(HttpHeaders.Names.TRANSFER_ENCODING, response);
        if (!allTransferEncodingHeaders.isEmpty()) {
            String finalEncoding = allTransferEncodingHeaders.get(allTransferEncodingHeaders.size() - 1);

            // per #3 above: "If a message is received with both a Transfer-Encoding header field and a Content-Length header field, the latter MUST be ignored."
            // since the Transfer-Encoding field is present, the message is self-terminating if and only if the final Transfer-Encoding value is "chunked"
            return HttpHeaders.Values.CHUNKED.equals(finalEncoding);
        }

        String contentLengthHeader = HttpHeaders.getHeader(response, HttpHeaders.Names.CONTENT_LENGTH);
        if (contentLengthHeader != null && !contentLengthHeader.isEmpty()) {
            return true;
        }

        // not checking for multipart/byteranges, since it is seldom used and its use as a message length indicator was removed in RFC 7230

        // none of the other message length indicators are present, so the only way the server can indicate the end
        // of this message is to close the connection
        return false;
    }

    public static List<String> getAllCommaSeparatedHeaderValues(String headerName, HttpMessage httpMessage) {
        List<String> allHeaders = httpMessage.headers().getAll(headerName);
        if (allHeaders.isEmpty()) {
            return Collections.emptyList();
        }

        ImmutableList.Builder<String> headerValues = ImmutableList.builder();
        for (String header : allHeaders) {
            List<String> commaSeparatedValues = splitCommaSeparatedHeaderValues(header);
            headerValues.addAll(commaSeparatedValues);
        }

        return headerValues.build();
    }

    public static HttpResponse duplicateHttpResponse(HttpResponse originalResponse) {
        DefaultHttpResponse newResponse = new DefaultHttpResponse(originalResponse.getProtocolVersion(), originalResponse.getStatus());
        newResponse.headers().add(originalResponse.headers());

        return newResponse;
    }

    public static String getHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            LOGGER.debug("Ignored exception", e);
        } catch (RuntimeException e) {
            // An exception here must not stop the proxy. Android could throw a
            // runtime exception, since it not allows network access in the main
            // process.
            LOGGER.debug("Ignored exception", e);
        }
        LOGGER.info("Could not lookup localhost");
        return null;
    }

    public static boolean shouldRemoveHopByHopHeader(String headerName) {
        return SHOULD_NOT_PROXY_HOP_BY_HOP_HEADERS.contains(headerName.toLowerCase(Locale.US));
    }

    public static List<String> splitCommaSeparatedHeaderValues(String headerValue) {
        return ImmutableList.copyOf(COMMA_SEPARATED_HEADER_VALUE_SPLITTER.split(headerValue));
    }

    public static boolean isUdtAvailable() {
        try {
            return NioUdtProvider.BYTE_PROVIDER != null;
        } catch (NoClassDefFoundError e) {
            return false;
        }
    }

    public static FullHttpResponse createFullHttpResponse(HttpVersion httpVersion,
                                                          HttpResponseStatus status,
                                                          String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ByteBuf content = Unpooled.copiedBuffer(bytes);

        return createFullHttpResponse(httpVersion, status, "text/html; charset=utf-8", content, bytes.length);
    }

    public static FullHttpResponse createFullHttpResponseJSFile(HttpVersion httpVersion,
                                                                HttpResponseStatus status,
                                                                String fileName) {
        File file = new File(fileName);
        byte[] byteArray;
        if (file.exists()) {
            try {
                FileInputStream fis = new FileInputStream(file);
                int fileLength = (int) file.length();
                byteArray = new byte[fileLength];
                fis.read(byteArray);
                fis.close();
            } catch (Exception ex) {
                LOGGER.error("Error reading javascript file", ex);
                byteArray = ("function replacer(){console.log(\"File" + fileName + " does not  exist.\")}").getBytes();
            }
        } else {
            byteArray = ("function replacer(){console.log(\"File" + fileName + " does not  exist.\")}").getBytes();
        }
        Path path = Paths.get(fileName);
//        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ByteBuf content = Unpooled.copiedBuffer(byteArray);
        return createFullHttpResponse(httpVersion, status, "application/javascript; charset=utf-8", content, byteArray.length);
    }

    public static FullHttpResponse createFullHttpResponse(HttpVersion httpVersion,
                                                          HttpResponseStatus status) {
        return createFullHttpResponse(httpVersion, status, null, null, 0);
    }

    public static FullHttpResponse createFullHttpResponse(HttpVersion httpVersion,
                                                          HttpResponseStatus status,
                                                          String contentType,
                                                          ByteBuf body,
                                                          int contentLength) {
        DefaultFullHttpResponse response;

        if (body != null) {
            response = new DefaultFullHttpResponse(httpVersion, status, body);
            response.headers().set(HttpHeaders.Names.CONTENT_LENGTH, contentLength);
            response.headers().set(HttpHeaders.Names.CONTENT_TYPE, contentType);
        } else {
            response = new DefaultFullHttpResponse(httpVersion, status);
        }

        return response;
    }

    public static void removeSdchEncoding(HttpHeaders headers) {
        List<String> encodings = headers.getAll(HttpHeaders.Names.ACCEPT_ENCODING);
        headers.remove(HttpHeaders.Names.ACCEPT_ENCODING);

        for (String encoding : encodings) {
            if (encoding != null) {
                // The former regex should remove occurrences of 'sdch' while the
                // latter regex should take care of the dangling comma case when
                // 'sdch' was the first element in the list and there are other
                // encodings.
                encoding = encoding.replaceAll(",? *(sdch|SDCH)", "").replaceFirst("^ *, *", "");

                if (StringUtils.isNotBlank(encoding)) {
                    headers.add(HttpHeaders.Names.ACCEPT_ENCODING, encoding);
                }
            }
        }
    }
}
