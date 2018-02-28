package play.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.stream.ChunkedInput;
import io.netty.handler.stream.ChunkedStream;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.CharsetUtil;
import org.apache.commons.lang.StringUtils;
import play.Invoker;
import play.Invoker.InvocationContext;
import play.Logger;
import play.Play;
import play.data.binding.CachedBoundActionMethodArgs;
import play.data.validation.Validation;
import play.exceptions.PlayException;
import play.exceptions.UnexpectedException;
import play.i18n.Messages;
import play.libs.F.Action;
import play.libs.F.Promise;
import play.libs.MimeTypes;
import play.mvc.*;
import play.mvc.Http.Request;
import play.mvc.Http.Response;
import play.mvc.results.NotFound;
import play.mvc.results.RenderStatic;
import play.templates.JavaExtensions;
import play.templates.TemplateLoader;
import play.utils.HTTP;
import play.utils.Utils;
import play.vfs.VirtualFile;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import static io.netty.buffer.Unpooled.wrappedBuffer;
import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpHeaderValues.WEBSOCKET;

//        migration guide: 'extends ChannelInboundHandlerAdapter'
public class PlayHandler extends SimpleChannelInboundHandler<Object> {


    /**
     * If true (the default), Play will send the HTTP header
     * "Server: Play! Framework; ....". This could be a security problem (old
     * versions having publicly known security bugs), so you can disable the
     * header in application.conf: <code>http.exposePlayServer = false</code>
     */
    private static final String signature = "Play! Framework;" + Play.version + ";" + Play.mode.name().toLowerCase();
    private static final boolean exposePlayServer;

    private static final String ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final Charset ASCII = Charset.forName("ASCII");
    private static final MessageDigest SHA_1;

    /**
     * The Pipeline is given for a PlayHandler
     */
    public Map<String, ChannelHandler> pipelines = new HashMap<>();

    private WebSocketServerHandshaker handshaker;

    static {
        try {
            SHA_1 = MessageDigest.getInstance("SHA1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not supported on this platform", e);
        }
    }

    static {
        exposePlayServer = !"false".equals(Play.configuration.getProperty("http.exposePlayServer"));
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        super.channelRead(ctx, msg);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
        if (Logger.isTraceEnabled()) {
            Logger.trace("messageReceived: begin: %s", msg.getClass().getName());
        }
        if (msg instanceof HttpRequest) {
            if (Logger.isTraceEnabled()) {
                Logger.trace("msg instanceof HttpRequest");
            }

            final HttpRequest nettyRequest = (HttpRequest) msg;

            // Websocket upgrade
            if (WEBSOCKET.equals(nettyRequest.headers().get(UPGRADE))) {
                websocketHandshake(ctx, nettyRequest, nettyRequest);
                return;
            }

            // Plain old HttpRequest
            try {
                // Reset request object and response object for the current
                // thread.
                Http.Request.current.set(new Http.Request());

                final Response response = new Response();
                Http.Response.current.set(response);

                final Request request = parseRequest(ctx, nettyRequest, msg);

                // Buffered in memory output
                response.out = new ByteArrayOutputStream();

                // Direct output (will be set later)
                response.direct = null;

                // Streamed output (using response.writeChunk)
                response.onWriteChunk(result -> writeChunk(request, response, ctx, nettyRequest, result));

                // Raw invocation
                boolean raw = Play.pluginCollection.rawInvocation(request, response);
                if (raw) {
                    copyResponse(ctx, request, response, nettyRequest);
                } else {

                    // Delegate to Play framework
                    Invoker.invoke(new NettyInvocation(request, response, ctx, nettyRequest, msg));

                }

            } catch (Exception ex) {
                Logger.warn(ex, "Exception on request. serving 500 back");
                serve500(ex, ctx, nettyRequest);
            }
        }
        if (msg instanceof HttpContent) {
            if (Logger.isTraceEnabled()) {
                Logger.trace("msg instanceof HttpContent");
            }
            HttpContent httpContent = (HttpContent) msg;
            if (msg instanceof LastHttpContent) {
                if (Logger.isTraceEnabled()) {
                    Logger.trace("msg instanceof LastHttpContent");
                }
                LastHttpContent lastHttpContent = (LastHttpContent) msg;
            }
        }
        if (msg instanceof WebSocketFrame) {
            websocketFrameReceived(ctx, (WebSocketFrame) msg);
        }
        if (Logger.isTraceEnabled()) {
            Logger.trace("messageReceived: end: %s", msg.getClass().getName());
        }
    }


    private static final Map<String, RenderStatic> staticPathsCache = new HashMap<>();

    public class NettyInvocation extends Invoker.Invocation {

        private final ChannelHandlerContext ctx;
        private final Request request;
        private final Response response;
        private final HttpRequest nettyRequest;
        private final Object msg;

        public NettyInvocation(Request request, Response response, ChannelHandlerContext ctx, HttpRequest nettyRequest, Object msg) {
            this.ctx = ctx;
            this.request = request;
            this.response = response;
            this.nettyRequest = nettyRequest;
            this.msg = msg;
        }

        @Override
        public boolean init() {
            Thread.currentThread().setContextClassLoader(Play.classloader);
            if (Logger.isTraceEnabled()) {
                Logger.trace("init: begin");
            }

            Request.current.set(request);
            Response.current.set(response);

            Scope.Params.current.set(request.params);
            Scope.RenderArgs.current.set(null);
            Scope.RouteArgs.current.set(null);
            Scope.Session.current.set(null);
            Scope.Flash.current.set(null);
            CachedBoundActionMethodArgs.init();

            try {
                if (Play.mode == Play.Mode.DEV) {
                    Router.detectChanges(Play.ctxPath);
                }
                if (Play.mode == Play.Mode.PROD
                        && staticPathsCache.containsKey(request.domain + " " + request.method + " " + request.path)) {
                    RenderStatic rs = null;
                    synchronized (staticPathsCache) {
                        rs = staticPathsCache.get(request.domain + " " + request.method + " " + request.path);
                    }
                    serveStatic(rs, ctx, request, response, nettyRequest, msg);
                    if (Logger.isTraceEnabled()) {
                        Logger.trace("init: end false");
                    }
                    return false;
                }
                Router.routeOnlyStatic(request);
                super.init();
            } catch (NotFound nf) {
                serve404(nf, ctx, request, nettyRequest);
                if (Logger.isTraceEnabled()) {
                    Logger.trace("init: end false");
                }
                return false;
            } catch (RenderStatic rs) {
                if (Play.mode == Play.Mode.PROD) {
                    synchronized (staticPathsCache) {
                        staticPathsCache.put(request.domain + " " + request.method + " " + request.path, rs);
                    }
                }
                serveStatic(rs, ctx, request, response, nettyRequest, msg);
                if (Logger.isTraceEnabled()) {
                    Logger.trace("init: end false");
                }
                return false;
            }

            if (Logger.isTraceEnabled()) {
                Logger.trace("init: end true");
            }
            return true;
        }

        @Override
        public InvocationContext getInvocationContext() {
            ActionInvoker.resolve(request);
            return new InvocationContext(Http.invocationType, request.invokedMethod.getAnnotations(),
                    request.invokedMethod.getDeclaringClass().getAnnotations());
        }

        @Override
        public void run() {
            try {
                if (Logger.isTraceEnabled()) {
                    Logger.trace("run: begin");
                }
                super.run();
            } catch (Exception e) {
                serve500(e, ctx, nettyRequest);
            }
            if (Logger.isTraceEnabled()) {
                Logger.trace("run: end");
            }
        }

        @Override
        public void execute() throws Exception {
            if (!ctx.channel().isActive()) {
                try {
                    ctx.channel().close();
                } catch (Throwable e) {
                    // Ignore
                }
                return;
            }

            // Check the exceeded size before re rendering so we can render the
            // error if the size is exceeded
            saveExceededSizeError(nettyRequest, request, response);
            ActionInvoker.invoke(request, response);
        }

        @Override
        public void onSuccess() throws Exception {
            super.onSuccess();
            if (response.chunked) {
                closeChunked(request, response, ctx, nettyRequest);
            } else {
                copyResponse(ctx, request, response, nettyRequest);
            }
            if (Logger.isTraceEnabled()) {
                Logger.trace("execute: end");
            }
        }
    }

    void saveExceededSizeError(HttpRequest nettyRequest, Request request, Response response) {

        String warning = nettyRequest.headers().get(HttpHeaders.Names.WARNING);
        String length = nettyRequest.headers().get(HttpHeaders.Names.CONTENT_LENGTH);
        if (warning != null) {
            if (Logger.isTraceEnabled()) {
                Logger.trace("saveExceededSizeError: begin");
            }

            try {
                StringBuilder error = new StringBuilder();
                error.append("\u0000");
                // Cannot put warning which is
                // play.netty.content.length.exceeded
                // as Key as it will result error when printing error
                error.append("play.netty.maxContentLength");
                error.append(":");
                String size = null;
                try {
                    size = JavaExtensions.formatSize(Long.parseLong(length));
                } catch (Exception e) {
                    size = length + " bytes";
                }
                error.append(Messages.get(warning, size));
                error.append("\u0001");
                error.append(size);
                error.append("\u0000");
                if (request.cookies.get(Scope.COOKIE_PREFIX + "_ERRORS") != null
                        && request.cookies.get(Scope.COOKIE_PREFIX + "_ERRORS").value != null) {
                    error.append(request.cookies.get(Scope.COOKIE_PREFIX + "_ERRORS").value);
                }
                String errorData = URLEncoder.encode(error.toString(), "utf-8");
                Http.Cookie c = new Http.Cookie();
                c.value = errorData;
                c.name = Scope.COOKIE_PREFIX + "_ERRORS";
                request.cookies.put(Scope.COOKIE_PREFIX + "_ERRORS", c);
                if (Logger.isTraceEnabled()) {
                    Logger.trace("saveExceededSizeError: end");
                }
            } catch (Exception e) {
                throw new UnexpectedException("Error serialization problem", e);
            }
        }
    }

    protected static void addToResponse(Response response, HttpResponse nettyResponse) {
        Map<String, Http.Header> headers = response.headers;
        for (Map.Entry<String, Http.Header> entry : headers.entrySet()) {
            Http.Header hd = entry.getValue();
            for (String value : hd.values) {
                nettyResponse.headers().add(entry.getKey(), value);
            }
        }

        nettyResponse.headers().set(DATE, Utils.getHttpDateFormatter().format(new Date()));

        Map<String, Http.Cookie> cookies = response.cookies;

        for (Http.Cookie cookie : cookies.values()) {
            Cookie c = new DefaultCookie(cookie.name, cookie.value);
            c.setSecure(cookie.secure);
            c.setPath(cookie.path);
            if (cookie.domain != null) {
                c.setDomain(cookie.domain);
            }
            if (cookie.maxAge != null) {
                c.setMaxAge(cookie.maxAge);
            }
            c.setHttpOnly(cookie.httpOnly);
            nettyResponse.headers().add(SET_COOKIE, ServerCookieEncoder.STRICT.encode(c));
        }

        if (!response.headers.containsKey(CACHE_CONTROL) && !response.headers.containsKey(EXPIRES) && !(response.direct instanceof File)) {
            nettyResponse.headers().set(CACHE_CONTROL, "no-cache");
        }

    }

    protected static void writeResponse(ChannelHandlerContext ctx, Response response, FullHttpResponse nettyResponse,
            HttpRequest nettyRequest) {
        if (Logger.isTraceEnabled()) {
            Logger.trace("writeResponse: begin");
        }

        byte[] content = null;

        boolean keepAlive = isKeepAlive(nettyRequest);
        if (nettyRequest.getMethod().equals(HttpMethod.HEAD)) {
            content = new byte[0];
        } else {
            content = response.out.toByteArray();
        }

        nettyResponse.content().writeBytes(content);

        if (!nettyResponse.getStatus().equals(HttpResponseStatus.NOT_MODIFIED)) {
            if (Logger.isTraceEnabled()) {
                Logger.trace("writeResponse: content length [" + response.out.size() + "]");
            }
            setContentLength(nettyResponse, response.out.size());
        }

        ChannelFuture f = null;
        if (ctx.channel().isOpen()) {
            f = ctx.channel().writeAndFlush(nettyResponse);
        } else {
            Logger.debug("Try to write on a closed channel[keepAlive:%s]: Remote host may have closed the connection",
                    String.valueOf(keepAlive));
        }

        // Decide whether to close the connection or not.
        if (f != null && !keepAlive) {
            // Close the connection when the whole content is written out.
            f.addListener(ChannelFutureListener.CLOSE);
        }
        if (Logger.isTraceEnabled()) {
            Logger.trace("writeResponse: end");
        }
    }

    public void copyResponse(ChannelHandlerContext ctx, Request request, Response response, HttpRequest nettyRequest) throws Exception {
        if (Logger.isTraceEnabled()) {
            Logger.trace("copyResponse: begin");
        }

        // Decide whether to close the connection or not.

        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(response.status));
        if (exposePlayServer) {
            nettyResponse.headers().set(SERVER, signature);
        }

        if (response.contentType != null) {
            nettyResponse.headers().set(CONTENT_TYPE,
                    response.contentType + (response.contentType.startsWith("text/") && !response.contentType.contains("charset")
                            ? "; charset=" + response.encoding : ""));
        } else {
            nettyResponse.headers().set(CONTENT_TYPE, "text/plain; charset=" + response.encoding);
        }

        addToResponse(response, nettyResponse);

        Object obj = response.direct;
        File file = null;
        ChunkedInput stream = null;
        InputStream is = null;
        if (obj instanceof File) {
            file = (File) obj;
        } else if (obj instanceof InputStream) {
            is = (InputStream) obj;
        } else if (obj instanceof ChunkedInput) {
            // Streaming we don't know the content length
            stream = (ChunkedInput) obj;
        }

        boolean keepAlive = isKeepAlive(nettyRequest);
        if (file != null && file.isFile()) {
            try {
                nettyResponse = addEtag(nettyRequest, nettyResponse, file);
                if (nettyResponse.getStatus().equals(HttpResponseStatus.NOT_MODIFIED)) {

                    Channel ch = ctx.channel();

                    // Write the initial line and the header.
                    ChannelFuture writeFuture = ch.write(nettyResponse);

                    if (!keepAlive) {
                        // Close the connection when the whole content is
                        // written out.
                        writeFuture.addListener(ChannelFutureListener.CLOSE);
                    }
                } else {
                    FileService.serve(file, nettyRequest, nettyResponse, ctx, request, response, ctx.channel());
                }
            } catch (Exception e) {
                throw e;
            }
        } else if (is != null) {
            ChannelFuture writeFuture = ctx.channel().writeAndFlush(nettyResponse);
            if (!nettyRequest.method().equals(HttpMethod.HEAD) && !nettyResponse.getStatus().equals(HttpResponseStatus.NOT_MODIFIED)) {
                writeFuture = ctx.channel().writeAndFlush(new ChunkedStream(is));
            } else {
                is.close();
            }
            if (!keepAlive) {
                writeFuture.addListener(ChannelFutureListener.CLOSE);
            }
        } else if (stream != null) {
            ChannelFuture writeFuture = ctx.channel().writeAndFlush(nettyResponse);
            if (!nettyRequest.method().equals(HttpMethod.HEAD) && !nettyResponse.getStatus().equals(HttpResponseStatus.NOT_MODIFIED)) {
                writeFuture = ctx.channel().writeAndFlush(stream);
            } else {
                stream.close();
            }
            if (!keepAlive) {
                writeFuture.addListener(ChannelFutureListener.CLOSE);
            }
        } else {
            writeResponse(ctx, response, nettyResponse, nettyRequest);
        }
        if (Logger.isTraceEnabled()) {
            Logger.trace("copyResponse: end");
        }
    }

    static String getRemoteIPAddress(SocketAddress e) {
        String fullAddress = ((InetSocketAddress) e).getAddress().getHostAddress();
        if (fullAddress.matches("/[0-9]+[.][0-9]+[.][0-9]+[.][0-9]+[:][0-9]+")) {
            fullAddress = fullAddress.substring(1);
            fullAddress = fullAddress.substring(0, fullAddress.indexOf(":"));
        } else if (fullAddress.matches(".*[%].*")) {
            fullAddress = fullAddress.substring(0, fullAddress.indexOf("%"));
        }
        return fullAddress;
    }

    public Request parseRequest(ChannelHandlerContext ctx, HttpRequest nettyRequest, Object msg) throws Exception {
        if (Logger.isTraceEnabled()) {
            Logger.trace("parseRequest: begin");
            Logger.trace("parseRequest: URI = " + nettyRequest.uri());
        }

        String uri = nettyRequest.uri();
        // Remove domain and port from URI if it's present.
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            // Begins searching / after 9th character (last / of https://)
            int index = uri.indexOf("/", 9);
            // prevent the IndexOutOfBoundsException that was occurring
            if (index >= 0) {
                uri = uri.substring(index);
            } else {
                uri = "/";
            }
        }

        String contentType = nettyRequest.headers().get(CONTENT_TYPE);

        // need to get the encoding now - before the Http.Request is created
        String encoding = Play.defaultWebEncoding;
        if (contentType != null) {
            HTTP.ContentTypeWithEncoding contentTypeEncoding = HTTP.parseContentType(contentType);
            if (contentTypeEncoding.encoding != null) {
                encoding = contentTypeEncoding.encoding;
            }
        }

        int i = uri.indexOf("?");
        String querystring = "";
        String path = uri;
        if (i != -1) {
            path = uri.substring(0, i);
            querystring = uri.substring(i + 1);
        }

        String remoteAddress = getRemoteIPAddress(ctx.channel().remoteAddress());
        String method = nettyRequest.method().name();

        if (nettyRequest.headers().get("X-HTTP-Method-Override") != null) {
            method = nettyRequest.headers().get("X-HTTP-Method-Override").intern();
        }

        //nettyRequest.content()
        InputStream body = new ByteBufInputStream(ctx.channel().alloc().buffer());
        Integer max = Integer.valueOf(Play.configuration.getProperty("play.netty.maxContentLength", "-1"));
        if (!(max == -1 || body.available() < max)) {
            body = new ByteArrayInputStream(new byte[0]);
        }

        String host = nettyRequest.headers().get(HOST);
        boolean isLoopback = false;
        try {
            isLoopback = ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress().isLoopbackAddress()
                    && host.matches("^127\\.0\\.0\\.1:?[0-9]*$");
        } catch (Exception e) {
            // ignore it
        }

        int port = 0;
        String domain = null;
        if (host == null) {
            host = "";
            port = 80;
            domain = "";
        }
        // Check for IPv6 address
        else if (host.startsWith("[")) {
            // There is no port
            if (host.endsWith("]")) {
                domain = host;
                port = 80;
            } else {
                // There is a port so take from the last colon
                int portStart = host.lastIndexOf(':');
                if (portStart > 0 && (portStart + 1) < host.length()) {
                    domain = host.substring(0, portStart);
                    port = Integer.parseInt(host.substring(portStart + 1));
                }
            }
        }
        // Non IPv6 but has port
        else if (host.contains(":")) {
            String[] hosts = host.split(":");
            port = Integer.parseInt(hosts[1]);
            domain = hosts[0];
        } else {
            port = 80;
            domain = host;
        }

        boolean secure = false;

        Request request = Request.createRequest(remoteAddress, method, path, querystring, contentType, body, uri, host, isLoopback,
                port, domain, secure, getHeaders(nettyRequest), getCookies(nettyRequest));

        if (Logger.isTraceEnabled()) {
            Logger.trace("parseRequest: end");
        }
        return request;
    }

    protected static Map<String, Http.Header> getHeaders(HttpRequest nettyRequest) {
        Map<String, Http.Header> headers = new HashMap<>(16);

        for (String key : nettyRequest.headers().names()) {
            Http.Header hd = new Http.Header();
            hd.name = key.toLowerCase();
            hd.values = new ArrayList<>();
            for (String next : nettyRequest.headers().getAll(key)) {
                hd.values.add(next);
            }
            headers.put(hd.name, hd);
        }

        return headers;
    }

    protected static Map<String, Http.Cookie> getCookies(HttpRequest nettyRequest) {
        Map<String, Http.Cookie> cookies = new HashMap<>(16);
        String value = nettyRequest.headers().get(COOKIE);
        if (value != null) {
            Set<Cookie> cookieSet = ServerCookieDecoder.STRICT.decode(value);
            if (cookieSet != null) {
                for (Cookie cookie : cookieSet) {
                    Http.Cookie playCookie = new Http.Cookie();
                    playCookie.name = cookie.name();
                    playCookie.path = cookie.path();
                    playCookie.domain = cookie.domain();
                    playCookie.secure = cookie.isSecure();
                    playCookie.value = cookie.value();
                    playCookie.httpOnly = cookie.isHttpOnly();
                    cookies.put(playCookie.name, playCookie);
                }
            }
        }
        return cookies;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) throws Exception {
        try {
            // If we get a TooLongFrameException, we got a request exceeding 8k.
            // Log this, we can't call serve500()
            Throwable t = e.getCause();
            if (t instanceof TooLongFrameException) {
                Logger.error("Request exceeds 8192 bytes");
            }
            ctx.channel().close();
        } catch (Exception ex) {
        }
    }

    public static void serve404(NotFound e, ChannelHandlerContext ctx, Request request, HttpRequest nettyRequest) {
        if (Logger.isTraceEnabled()) {
            Logger.trace("serve404: begin");
        }
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
        if (exposePlayServer) {
            nettyResponse.headers().set(SERVER, signature);
        }

        nettyResponse.headers().set(CONTENT_TYPE, "text/html");
        Map<String, Object> binding = getBindingForErrors(e, false);

        String format = Request.current().format;
        if (format == null) {
            format = "txt";
        }
        nettyResponse.headers().set(CONTENT_TYPE, (MimeTypes.getContentType("404." + format, "text/plain")));

        String errorHtml = TemplateLoader.load("errors/404." + format).render(binding);
        try {
            byte[] bytes = errorHtml.getBytes(Response.current().encoding);
            ByteBuf buf = Unpooled.copiedBuffer(bytes);
            setContentLength(nettyResponse, bytes.length);
            nettyResponse.content().writeBytes(buf);
            ChannelFuture writeFuture = ctx.channel().writeAndFlush(nettyResponse);
            writeFuture.addListener(ChannelFutureListener.CLOSE);
        } catch (UnsupportedEncodingException fex) {
            Logger.error(fex, "(encoding ?)");
        }
        if (Logger.isTraceEnabled()) {
            Logger.trace("serve404: end");
        }
    }

    protected static Map<String, Object> getBindingForErrors(Exception e, boolean isError) {

        Map<String, Object> binding = new HashMap<>();
        if (!isError) {
            binding.put("result", e);
        } else {
            binding.put("exception", e);
        }
        binding.put("session", Scope.Session.current());
        binding.put("request", Http.Request.current());
        binding.put("flash", Scope.Flash.current());
        binding.put("params", Scope.Params.current());
        binding.put("play", new Play());
        try {
            binding.put("errors", Validation.errors());
        } catch (Exception ex) {
            // Logger.error(ex, "Error when getting Validation errors");
        }

        return binding;
    }

    // TODO: add request and response as parameter
    public static void serve500(Exception e, ChannelHandlerContext ctx, HttpRequest nettyRequest) {
        if (Logger.isTraceEnabled()) {
            Logger.trace("serve500: begin");
        }

        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR);
        if (exposePlayServer) {
            nettyResponse.headers().set(SERVER, signature);
        }

        Request request = Request.current();
        Response response = Response.current();

        String encoding = response.encoding;

        try {
            if (!(e instanceof PlayException)) {
                e = new play.exceptions.UnexpectedException(e);
            }

            // Flush some cookies
            try {

                Map<String, Http.Cookie> cookies = response.cookies;
                for (Http.Cookie cookie : cookies.values()) {
                    Cookie c = new DefaultCookie(cookie.name, cookie.value);
                    c.setSecure(cookie.secure);
                    c.setPath(cookie.path);
                    if (cookie.domain != null) {
                        c.setDomain(cookie.domain);
                    }
                    if (cookie.maxAge != null) {
                        c.setMaxAge(cookie.maxAge);
                    }
                    c.setHttpOnly(cookie.httpOnly);

                    nettyResponse.headers().add(SET_COOKIE, ServerCookieEncoder.STRICT.encode(c));
                }

            } catch (Exception exx) {
                Logger.error(e, "Trying to flush cookies");
                // humm ?
            }
            Map<String, Object> binding = getBindingForErrors(e, true);

            String format = request.format;
            if (format == null) {
                format = "txt";
            }

            nettyResponse.headers().set("Content-Type", (MimeTypes.getContentType("500." + format, "text/plain")));
            try {
                String errorHtml = TemplateLoader.load("errors/500." + format).render(binding);

                byte[] bytes = errorHtml.getBytes(encoding);
                ByteBuf buf = Unpooled.copiedBuffer(bytes);
                setContentLength(nettyResponse, bytes.length);
                nettyResponse.content().writeBytes(buf);
                ChannelFuture writeFuture = ctx.channel().writeAndFlush(nettyResponse);
                writeFuture.addListener(ChannelFutureListener.CLOSE);
                Logger.error(e, "Internal Server Error (500) for request %s", request.method + " " + request.url);
            } catch (Throwable ex) {
                Logger.error(e, "Internal Server Error (500) for request %s", request.method + " " + request.url);
                Logger.error(ex, "Error during the 500 response generation");
                try {
                    String errorHtml = "Internal Error (check logs)";
                    byte[] bytes = errorHtml.getBytes(encoding);
                    ByteBuf buf = Unpooled.copiedBuffer(bytes);
                    setContentLength(nettyResponse, bytes.length);
                    nettyResponse.content().writeBytes(buf);
                    ChannelFuture writeFuture = ctx.channel().writeAndFlush(nettyResponse);
                    writeFuture.addListener(ChannelFutureListener.CLOSE);
                } catch (UnsupportedEncodingException fex) {
                    Logger.error(fex, "(encoding ?)");
                }
            }
        } catch (Throwable exxx) {
            try {
                String errorHtml = "Internal Error (check logs)";
                byte[] bytes = errorHtml.getBytes(encoding);
                ByteBuf buf = Unpooled.copiedBuffer(bytes);
                setContentLength(nettyResponse, bytes.length);
                nettyResponse.content().writeBytes(buf);
                ChannelFuture writeFuture = ctx.channel().writeAndFlush(nettyResponse);
                writeFuture.addListener(ChannelFutureListener.CLOSE);
            } catch (Exception fex) {
                Logger.error(fex, "(encoding ?)");
            }
            if (exxx instanceof RuntimeException) {
                throw (RuntimeException) exxx;
            }
            throw new RuntimeException(exxx);
        }
        if (Logger.isTraceEnabled()) {
            Logger.trace("serve500: end");
        }
    }

    public void serveStatic(RenderStatic renderStatic, ChannelHandlerContext ctx, Request request, Response response,
            HttpRequest nettyRequest, Object e) {
        if (Logger.isTraceEnabled()) {
            Logger.trace("serveStatic: begin");
        }

        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(response.status));
        if (exposePlayServer) {
            nettyResponse.headers().set(SERVER, signature);
        }
        try {
            VirtualFile file = Play.getVirtualFile(renderStatic.file);
            if (file != null && file.exists() && file.isDirectory()) {
                file = file.child("index.html");
                if (file != null) {
                    renderStatic.file = file.relativePath();
                }
            }
            if ((file == null || !file.exists())) {
                serve404(new NotFound("The file " + renderStatic.file + " does not exist"), ctx, request, nettyRequest);
            } else {
                boolean raw = Play.pluginCollection.serveStatic(file, Request.current(), Response.current());
                if (raw) {
                    copyResponse(ctx, request, response, nettyRequest);
                } else {
                    File localFile = file.getRealFile();
                    boolean keepAlive = isKeepAlive(nettyRequest);
                    nettyResponse = addEtag(nettyRequest, nettyResponse, localFile);

                    if (nettyResponse.status().equals(HttpResponseStatus.NOT_MODIFIED)) {
                        Channel ch = ctx.channel();

                        // Write the initial line and the header.
                        ChannelFuture writeFuture = ch.writeAndFlush(nettyResponse);
                        if (!keepAlive) {
                            // Write the content.
                            writeFuture.addListener(ChannelFutureListener.CLOSE);
                        }
                    } else {
                        FileService.serve(localFile, nettyRequest, nettyResponse, ctx, request, response, ctx.channel());
                    }
                }

            }
        } catch (Throwable ez) {
            Logger.error(ez, "serveStatic for request %s", request.method + " " + request.url);
            try {
                FullHttpResponse errorResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR);
                String errorHtml = "Internal Error (check logs)";
                byte[] bytes = errorHtml.getBytes(response.encoding);
                ByteBuf buf = Unpooled.copiedBuffer(bytes);
                setContentLength(nettyResponse, bytes.length);
                errorResponse.content().writeBytes(buf);
                ChannelFuture future = ctx.channel().writeAndFlush(errorResponse);
                future.addListener(ChannelFutureListener.CLOSE);
            } catch (Exception ex) {
                Logger.error(ex, "serveStatic for request %s", request.method + " " + request.url);
            }
        }
        if (Logger.isTraceEnabled()) {
            Logger.trace("serveStatic: end");
        }
    }

    public static boolean isModified(String etag, long last, HttpRequest nettyRequest) {

        if (nettyRequest.headers().contains(IF_NONE_MATCH)) {
            String browserEtag = nettyRequest.headers().get(IF_NONE_MATCH);
            if (browserEtag.equals(etag)) {
                return false;
            }
            return true;
        }

        if (nettyRequest.headers().contains(IF_MODIFIED_SINCE)) {
            String ifModifiedSince = nettyRequest.headers().get(IF_MODIFIED_SINCE);

            if (!StringUtils.isEmpty(ifModifiedSince)) {
                try {
                    Date browserDate = Utils.getHttpDateFormatter().parse(ifModifiedSince);
                    if (browserDate.getTime() >= last) {
                        return false;
                    }
                } catch (ParseException ex) {
                    Logger.warn("Can't parse HTTP date", ex);
                }
                return true;
            }
        }
        return true;
    }

    private static FullHttpResponse addEtag(HttpRequest nettyRequest, FullHttpResponse httpResponse, File file) {
        if (Play.mode == Play.Mode.DEV) {
            httpResponse.headers().set(CACHE_CONTROL, "no-cache");
        } else {
            // Check if Cache-Control header is not set
            if (httpResponse.headers().get(CACHE_CONTROL) == null) {
                String maxAge = Play.configuration.getProperty("http.cacheControl", "3600");
                if (maxAge.equals("0")) {
                    httpResponse.headers().set(CACHE_CONTROL, "no-cache");
                } else {
                    httpResponse.headers().set(CACHE_CONTROL, "max-age=" + maxAge);
                }
            }
        }
        boolean useEtag = Play.configuration.getProperty("http.useETag", "true").equals("true");
        long last = file.lastModified();
        String etag = "\"" + last + "-" + file.hashCode() + "\"";
        if (!isModified(etag, last, nettyRequest)) {
            if (nettyRequest.getMethod().equals(HttpMethod.GET)) {
                httpResponse.setStatus(HttpResponseStatus.NOT_MODIFIED);
            }
            if (useEtag) {
                httpResponse.headers().set(ETAG, etag);
            }

        } else {
            httpResponse.headers().set(LAST_MODIFIED, Utils.getHttpDateFormatter().format(new Date(last)));
            if (useEtag) {
                httpResponse.headers().set(ETAG, etag);
            }
        }
        return httpResponse;
    }

    public static boolean isKeepAlive(HttpMessage message) {
        boolean keepAlive = HttpUtil.isKeepAlive(message);
        boolean http_1_1_message = message.protocolVersion().equals(HttpVersion.HTTP_1_1);
        if (Logger.isTraceEnabled()) {
            Logger.trace("keepAlive: %b, http_1_1_message: %b", keepAlive, http_1_1_message);
        }
        return keepAlive;
    }

    public static void setContentLength(HttpMessage message, long contentLength) {
        message.headers().set(CONTENT_LENGTH, String.valueOf(contentLength));
    }

    static class LazyChunkedInput implements ChunkedInput {

        private boolean closed = false;
        private ConcurrentLinkedQueue<byte[]> nextChunks = new ConcurrentLinkedQueue<>();

        @Override
        public boolean isEndOfInput() throws Exception {
            return closed && nextChunks.isEmpty();
        }

        @Override
        public void close() throws Exception {
            if (!closed) {
                nextChunks.offer("0\r\n\r\n".getBytes());
            }
            closed = true;
        }

        @Override
        public Object readChunk(ChannelHandlerContext channelHandlerContext) throws Exception {
            return null;
        }

        @Override
        public Object readChunk(ByteBufAllocator byteBufAllocator) throws Exception {
            if (nextChunks.isEmpty()) {
                return null;
            }
            byte[] bytes = nextChunks.poll();
            return byteBufAllocator.buffer(bytes.length).writeBytes(bytes);
        }

        @Override
        public long length() {
            return -1;
        }

        @Override
        public long progress() {
            return 0;
        }

        public void writeChunk(Object chunk) throws Exception {
            if (closed) {
                throw new Exception("HTTP output stream closed");
            }

            byte[] bytes;
            if (chunk instanceof byte[]) {
                bytes = (byte[]) chunk;
            } else {
                String message = chunk == null ? "" : chunk.toString();
                bytes = message.getBytes(Response.current().encoding);
            }

            ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
            byteStream.write(Integer.toHexString(bytes.length).getBytes());
            byte[] crlf = new byte[] { (byte) '\r', (byte) '\n' };
            byteStream.write(crlf);
            byteStream.write(bytes);
            byteStream.write(crlf);
            nextChunks.offer(byteStream.toByteArray());
        }
    }

    public void writeChunk(Request playRequest, Response playResponse, ChannelHandlerContext ctx, HttpRequest nettyRequest, Object chunk) {
        try {
            if (playResponse.direct == null) {
                playResponse.setHeader("Transfer-Encoding", "chunked");
                playResponse.direct = new LazyChunkedInput();
                copyResponse(ctx, playRequest, playResponse, nettyRequest);
            }
            ((LazyChunkedInput) playResponse.direct).writeChunk(chunk);

            if (this.pipelines.get("ChunkedWriteHandler") != null) {
                ((ChunkedWriteHandler) this.pipelines.get("ChunkedWriteHandler")).resumeTransfer();
            }
            if (this.pipelines.get("SslChunkedWriteHandler") != null) {
                ((ChunkedWriteHandler) this.pipelines.get("SslChunkedWriteHandler")).resumeTransfer();
            }
        } catch (Exception e) {
            throw new UnexpectedException(e);
        }
    }

    public void closeChunked(Request playRequest, Response playResponse, ChannelHandlerContext ctx, HttpRequest nettyRequest) {
        try {
            ((LazyChunkedInput) playResponse.direct).close();
            if (this.pipelines.get("ChunkedWriteHandler") != null) {
                ((ChunkedWriteHandler) this.pipelines.get("ChunkedWriteHandler")).resumeTransfer();
            }
            if (this.pipelines.get("SslChunkedWriteHandler") != null) {
                ((ChunkedWriteHandler) this.pipelines.get("SslChunkedWriteHandler")).resumeTransfer();
            }
        } catch (Exception e) {
            throw new UnexpectedException(e);
        }
    }

    // ~~~~~~~~~~~ Websocket
    static final Map<ChannelHandlerContext, Http.Inbound> channels = new ConcurrentHashMap<>();

    private void websocketFrameReceived(ChannelHandlerContext ctx, WebSocketFrame webSocketFrame) {
        Http.Inbound inbound = channels.get(ctx);
        // Check for closing frame
        if (webSocketFrame instanceof CloseWebSocketFrame) {
            this.handshaker.close(ctx.channel(), (CloseWebSocketFrame) webSocketFrame);
        } else if (webSocketFrame instanceof PingWebSocketFrame) {
            ctx.channel().writeAndFlush(new PongWebSocketFrame(webSocketFrame.content()));
        } else if (webSocketFrame instanceof BinaryWebSocketFrame) {
            inbound._received(new Http.WebSocketFrame(webSocketFrame.content().array()));
        } else if (webSocketFrame instanceof TextWebSocketFrame) {
            inbound._received(new Http.WebSocketFrame(webSocketFrame.content().toString(CharsetUtil.UTF_8)));
        }
    }

    private String getWebSocketLocation(HttpRequest req) {
        return "ws://" + req.headers().get(HOST) + req.uri();
    }

    private void websocketHandshake(final ChannelHandlerContext ctx, HttpRequest req, HttpRequest msg) {

        Integer max = Integer.valueOf(Play.configuration.getProperty("play.netty.maxContentLength", "65345"));

        // Upgrade the pipeline as the handshaker needs the HttpStream
        // Aggregator
        ctx.pipeline().addLast("fake-aggregator", new HttpObjectAggregator(max));
        try {
            WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(this.getWebSocketLocation(req), null, false);
            this.handshaker = wsFactory.newHandshaker(req);
            if (this.handshaker == null) {
                WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
            } else {
                try {
                    this.handshaker.handshake(ctx.channel(), req);
                } catch (Exception e) {
                    e.printStackTrace();

                }
            }
        } finally {
            // Remove fake aggregator in case handshake was not a success, it is
            // still lying around
            try {
                ctx.pipeline().remove("fake-aggregator");
            } catch (Exception e) {
            }
        }
        Request request = null;
        try {
            request = parseRequest(ctx, req, msg);
        } catch (Exception e) {
            //TODO
            e.printStackTrace();
        }

        // Route the websocket request
        request.method = "WS";

        Map<String, String> route = Router.route(request.method, request.path);
        if (!route.containsKey("action")) {
            // No route found to handle this websocket connection
            ctx.channel().writeAndFlush(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND));
            return;
        }

        // Inbound
        Http.Inbound inbound = new Http.Inbound(ctx) {

            @Override
            public boolean isOpen() {
                return ctx.channel().isOpen();
            }
        };
        channels.put(ctx, inbound);

        // Outbound
        Http.Outbound outbound = new Http.Outbound() {

            final List<ChannelFuture> writeFutures = Collections.synchronizedList(new ArrayList<ChannelFuture>());
            Promise<Void> closeTask;

            synchronized void writeAndClose(ChannelFuture writeFuture) {
                if (!writeFuture.isDone()) {
                    writeFutures.add(writeFuture);
                    writeFuture.addListener(new ChannelFutureListener() {

                        @Override
                        public void operationComplete(ChannelFuture cf) throws Exception {
                            writeFutures.remove(cf);
                            futureClose();
                        }
                    });
                }
            }

            void futureClose() {
                if (closeTask != null && writeFutures.isEmpty()) {
                    closeTask.invoke(null);
                }
            }

            @Override
            public void send(String data) {
                if (!isOpen()) {
                    throw new IllegalStateException("The outbound channel is closed");
                }
                writeAndClose(ctx.channel().writeAndFlush(new TextWebSocketFrame(data)));
            }

            @Override
            public void send(byte opcode, byte[] data, int offset, int length) {
                if (!isOpen()) {
                    throw new IllegalStateException("The outbound channel is closed");
                }

                writeAndClose(ctx.channel().writeAndFlush(new BinaryWebSocketFrame(wrappedBuffer(data, offset, length))));
            }

            @Override
            public synchronized boolean isOpen() {
                return ctx.channel().isOpen() && closeTask == null;
            }

            @Override
            public synchronized void close() {
                closeTask = new Promise<>();
                closeTask.onRedeem(new Action<Promise<Void>>() {

                    @Override
                    public void invoke(Promise<Void> completed) {
                        writeFutures.clear();
                        ctx.channel().disconnect();
                        closeTask = null;
                    }
                });
                futureClose();
            }
        };
        Logger.trace("invoking");

        Invoker.invoke(new WebSocketInvocation(route, request, inbound, outbound, ctx, msg));
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        Http.Inbound inbound = channels.get(ctx);
        if (inbound != null) {
            inbound.close();
        }
        channels.remove(ctx);
    }

    public static class WebSocketInvocation extends Invoker.Invocation {

        Map<String, String> route;
        Http.Request request;
        Http.Inbound inbound;
        Http.Outbound outbound;
        ChannelHandlerContext ctx;
        HttpRequest msg;

        public WebSocketInvocation(Map<String, String> route, Http.Request request, Http.Inbound inbound, Http.Outbound outbound,
                ChannelHandlerContext ctx, HttpRequest msg) {
            this.route = route;
            this.request = request;
            this.inbound = inbound;
            this.outbound = outbound;
            this.ctx = ctx;
            this.msg = msg;
        }

        @Override
        public boolean init() {
            Http.Request.current.set(request);
            Http.Inbound.current.set(inbound);
            Http.Outbound.current.set(outbound);
            return super.init();
        }

        @Override
        public InvocationContext getInvocationContext() {
            WebSocketInvoker.resolve(request);
            return new InvocationContext(Http.invocationType, request.invokedMethod.getAnnotations(),
                    request.invokedMethod.getDeclaringClass().getAnnotations());
        }

        @Override
        public void execute() throws Exception {
            WebSocketInvoker.invoke(request, inbound, outbound);
        }

        @Override
        public void onException(Throwable e) {
            Logger.error(e, "Internal Server Error in WebSocket (closing the socket) for request %s", request.method + " " + request.url);
            ctx.channel().close();
            super.onException(e);
        }

        @Override
        public void onSuccess() throws Exception {
            outbound.close();
            super.onSuccess();
        }
    }
}
