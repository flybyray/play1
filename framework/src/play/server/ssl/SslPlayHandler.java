package play.server.ssl;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.ssl.SslHandler;
import play.Logger;
import play.mvc.Http.Request;
import play.server.PlayHandler;
import play.server.Server;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;

import static io.netty.channel.ChannelFutureListener.CLOSE;
import static io.netty.handler.codec.http.HttpHeaders.Names.LOCATION;
import static io.netty.handler.codec.http.HttpResponseStatus.TEMPORARY_REDIRECT;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

public class SslPlayHandler extends PlayHandler {

    @Override
    public Request parseRequest(ChannelHandlerContext ctx, HttpRequest nettyRequest, Object msg) throws Exception {
        Request request = super.parseRequest(ctx, nettyRequest, msg);
        request.secure = true;
        return request;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
        super.channelRead0(ctx, msg);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        //TODO do not know how to archive that
        //.attachment(e.getValue());
        // Get the SslHandler in the current pipeline.
        SslHandler sslHandler = ctx.pipeline().get(SslHandler.class);

        //TODO remove reneg false i think it was setup sometime when this was in netty doc
        /* Please note that TLS renegotiation had a security issue before.  If your
         * runtime environment did not fix it, please make sure to disable TLS
         * renegotiation by calling {@link #setEnableRenegotiation(boolean)} with
         * {@code false}.  For more information, please refer to the following documents:
         * <ul>
         *   <li><a href="http://cve.mitre.org/cgi-bin/cvename.cgi?name=CVE-2009-3555">CVE-2009-3555</a></li>
         *   <li><a href="http://www.ietf.org/rfc/rfc5746.txt">RFC5746</a></li>
         *   <li><a href="http://www.oracle.com/technetwork/java/javase/documentation/tlsreadme2-176330.html">Phased
         *       Approach to Fixing the TLS Renegotiation Issue</a></li>
         * </ul>
         */
//            sslHandler.setEnableRenegotiation(false);
        sslHandler.handshakeFuture().addListener(future -> {
            if (!future.isSuccess()) {
                Logger.debug(future.cause(), "Invalid certificate");
            }
        });

    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) throws Exception {
        // We have to redirect to https://, as it was targeting http://
        // Redirect to the root as we don't know the url at that point
        if (e.getCause() instanceof SSLException) {
            Logger.debug(e.getCause(), "");
            InetSocketAddress inet = ((InetSocketAddress) ctx.channel().remoteAddress());
            ctx.pipeline().remove("ssl");
            HttpResponse nettyResponse = new DefaultHttpResponse(HTTP_1_1, TEMPORARY_REDIRECT);
            nettyResponse.headers().set(LOCATION, "https://" + inet.getHostName() + ":" + Server.httpsPort + "/");
            ChannelFuture writeFuture = ctx.channel().writeAndFlush(nettyResponse);
            writeFuture.addListener(CLOSE);
        } else {
            Logger.error(e.getCause(), "");
            ctx.channel().close();
        }
    }

}
