package pro.tools.http.netty.clientpool;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.pool.ChannelPool;
import io.netty.channel.pool.FixedChannelPool;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pro.tools.http.netty.handler.HttpClientChannelPoolHandler;
import pro.tools.http.netty.handler.HttpClientHandler;
import pro.tools.http.pojo.*;
import pro.tools.http.pojo.HttpMethod;

import javax.net.ssl.SSLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * @author SeanDragon
 * <p>
 * Create By 2017-07-21 10:02
 */
public class DefaultClientPool {
    private static final Logger log = LoggerFactory.getLogger(DefaultClientPool.class);
    private static EventLoopGroup GROUP;
    private static volatile boolean haveInit;

    private ChannelPool channelPool;
    private String host;
    private String scheme;
    private Integer port;
    private SslContext sslContext;

    public DefaultClientPool(final String url) throws HttpException {
        if (!haveInit) {
            GROUP = new NioEventLoopGroup();
            Runtime.getRuntime().addShutdownHook(new Thread(DefaultClientPool::stopAll));
            haveInit = true;
        }

        try {
            init(url);
        } catch (URISyntaxException | SSLException e) {
            throw new HttpException(e);
        }
    }

    public static void stopAll() {
        GROUP.shutdownGracefully();
    }

    private void init(String url) throws URISyntaxException, SSLException {
        URI uri = new URI(url);
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("uri不合法");
        }
        scheme = uri.getScheme();
        host = uri.getHost();
        port = uri.getPort();
        if (port == -1) {
            if ("http".equalsIgnoreCase(scheme)) {
                port = 80;
            } else if ("https".equalsIgnoreCase(scheme)) {
                port = 443;
            }
        }

        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            if (log.isErrorEnabled()) {
                log.error("仅有HTTP（S）是支持的。");
            }
            return;
        }

        final boolean ssl = "https".equalsIgnoreCase(scheme);

        this.setSSlContext(ssl);

        final Bootstrap b = new Bootstrap();
        b.group(GROUP)
                .channel(NioSocketChannel.class)
                //FIXME 删除好像没有事情，走的是HttpClientChannelPoolHandler
                //.handler(new HttpClientInitializer(sslContext))
                .remoteAddress(host, port)
        ;

        channelPool = new FixedChannelPool(b, new HttpClientChannelPoolHandler(sslContext), Integer.MAX_VALUE);
    }

    public HttpReceive request(HttpSend httpSend) {
        return request(httpSend, Integer.MAX_VALUE, TimeUnit.DAYS);
    }

    public HttpReceive request(HttpSend httpSend, long timeout, TimeUnit timeUnit) {
        final HttpReceive httpReceive = new HttpReceive();
        final Channel channel = channelPool.acquire().syncUninterruptibly().getNow();
        try {
            channel.pipeline().addLast(new HttpClientHandler(httpSend, httpReceive));

            final FullHttpRequest fullHttpRequest = convertRequest(httpSend);

            channel.pipeline().writeAndFlush(fullHttpRequest);

            channel.closeFuture().await(timeout, timeUnit);

            if (!httpReceive.getIsDone()) {
                httpReceive.setHaveError(true);
                httpReceive.setErrMsg("请求已经超时");
            }
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn(e.getMessage(), e);
            }
            httpReceive.setHaveError(true)
                    .setErrMsg(e.getMessage())
                    .setThrowable(e)
                    .setIsDone(true);
        } finally {
            channelPool.release(channel).awaitUninterruptibly(0, TimeUnit.MILLISECONDS);
        }

        return httpReceive;
    }

    private static final HttpHeaders DEFAULT_HTTP_HEADERS;

    static {
        DEFAULT_HTTP_HEADERS = new DefaultHttpHeaders();

        HttpDefaultHeaders.getDefaultHeaders().forEach(DEFAULT_HTTP_HEADERS::set);
    }

    public void stop() {
        this.channelPool.close();
    }

    private FullHttpRequest convertRequest(HttpSend httpSend) {
        io.netty.handler.codec.http.HttpMethod httpMethod;
        HttpMethod method = httpSend.getMethod();
        Map<String, Object> params = httpSend.getParams();
        Map<String, Object> headers = httpSend.getHeaders();

        switch (method) {
            case GET:
                httpMethod = io.netty.handler.codec.http.HttpMethod.GET;
                break;
            case POST:
                httpMethod = io.netty.handler.codec.http.HttpMethod.POST;
                break;
            case PUT:
                httpMethod = io.netty.handler.codec.http.HttpMethod.PUT;
                break;
            case DELETE:
                httpMethod = io.netty.handler.codec.http.HttpMethod.DELETE;
                break;
            case TRACE:
                httpMethod = io.netty.handler.codec.http.HttpMethod.TRACE;
                break;
            default:
                httpMethod = io.netty.handler.codec.http.HttpMethod.POST;
                break;
        }

        if (httpSend.getUrl().trim().charAt(0) != '/') {
            httpSend.setUrl("/" + httpSend.getUrl());
        }


        QueryStringEncoder queryStringEncoder = new QueryStringEncoder(scheme + "://" + host + ":" + port + httpSend.getUrl(), httpSend.getCharset());

        StringBuffer content = new StringBuffer();
        if (params != null) {
            params.forEach((key, value) -> {
                content.append(key).append("=").append(value.toString()).append("&");
                //if (value instanceof AbstractCollection
                //        || value instanceof Map
                //        || value instanceof Number
                //        || value instanceof String) {
                //    queryStringEncoder.addParam(key, value.toString());
                //} else {
                //    queryStringEncoder.addParam(key, ToolJson.anyToJson(value));
                //}
            });
        }

        URI sendURI;
        try {
            sendURI = new URI(queryStringEncoder.toString());
        } catch (URISyntaxException e) {
            log.warn(e.getMessage(), e);
            throw new RuntimeException(e);
        }

        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1
                , httpMethod
                , sendURI.toString()
                , Unpooled.copiedBuffer(content.toString().getBytes())
        );

        // FIXME: 2017/7/27 暂未加Cookie
        // if (cookies != null) {
        //    List<Cookie> cookieList = Lists.newArrayListWithCapacity(cookies.size());
        //    cookies.forEach((key, value) -> {
        //        cookieList.add(new DefaultCookie(key, value));
        //    });
        //
        //    request.headers().set(
        //            HttpHeaderNames.COOKIE,
        //            ClientCookieEncoder.STRICT.encode(cookieList)
        //    );
        // }

        request.headers().add(DEFAULT_HTTP_HEADERS);
        if (headers != null) {
            headers.forEach((key, value) -> {
                request.headers().set(key, value);
            });
        }

        request.headers().set(HttpHeaderNames.HOST, host + ":" + port);
        request.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED + ";charset" + httpSend.getCharset().toString());
        request.headers().set(HttpHeaderNames.CONTENT_LENGTH, request.content().readableBytes());

        return request;
    }

    private void setSSlContext(boolean ssl) throws SSLException {
        if (ssl) {
            sslContext = SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE).build();
        } else {
            sslContext = null;
        }
    }
}