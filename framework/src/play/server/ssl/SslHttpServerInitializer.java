package play.server.ssl;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.ssl.SslHandler;
import play.Logger;
import play.Play;
import play.server.HttpServerInitializer;

import javax.net.ssl.SSLEngine;

public class SslHttpServerInitializer extends HttpServerInitializer {

    private String pipelineConfig = Play
            .configuration.getProperty(
                    "play.ssl.netty.pipeline",
                    PLAY_SERVER_FLASH_POLICY_HANDLER + "," +
                            IO_NETTY_HANDLER_CODEC_HTTP_HTTP_REQUEST_DECODER + "," +
                            PLAY_SERVER_STREAM_CHUNK_AGGREGATOR + "," +
                            IO_NETTY_HANDLER_CODEC_HTTP_HTTP_RESPONSE_ENCODER + "," +
                            IO_NETTY_HANDLER_STREAM_CHUNKED_WRITE_HANDLER + "," +
                            PLAY_SERVER_SSL_SSL_PLAY_HANDLER);

    @Override
    protected void initChannel(Channel channel) {

        String mode = Play.configuration.getProperty("play.netty.clientAuth", "none");
        String enabledCiphers = Play.configuration.getProperty("play.ssl.enabledCiphers", "");
        String enabledProtocols = Play.configuration.getProperty("play.ssl.enabledProtocols", "");

        ChannelPipeline pipeline = channel.pipeline();

        // Add SSL handler first to encrypt and decrypt everything.
        SSLEngine engine = SslHttpServerContextFactory.getServerContext().createSSLEngine();
        engine.setUseClientMode(false);

        if (enabledCiphers != null && enabledCiphers.length() > 0) {
            engine.setEnabledCipherSuites(enabledCiphers.replaceAll(" ", "").split(","));
        }

        if ("want".equalsIgnoreCase(mode)) {
            engine.setWantClientAuth(true);
        } else if ("need".equalsIgnoreCase(mode)) {
            engine.setNeedClientAuth(true);
        }

        if (enabledProtocols != null && enabledProtocols.trim().length() > 0) {
            engine.setEnabledProtocols(enabledProtocols.replaceAll(" ", "").split(","));
        }

        engine.setEnableSessionCreation(true);

        pipeline.addLast("ssl", new SslHandler(engine));

        // Get all the pipeline. Give the user the opportunity to add their own
        String[] handlers = pipelineConfig.split(",");
        if (handlers.length <= 0) {
            Logger.error("You must defined at least the SslPlayHandler in \"play.netty.pipeline\"");
            return;
        }

        // Create the play Handler (always the last one)
        String handler = handlers[handlers.length - 1];
        ChannelHandler instance = getInstance(handler);
        SslPlayHandler sslPlayHandler = (SslPlayHandler) instance;
        if (instance == null || !(instance instanceof SslPlayHandler) || sslPlayHandler == null) {
            Logger.error("The last handler must be the SslPlayHandler in \"play.netty.pipeline\"");
            return;
        }

        for (int i = 0; i < handlers.length - 1; i++) {
            handler = handlers[i];
            try {
                String name = getName(handler.trim());
                instance = getInstance(handler);
                if (instance != null) {
                    pipeline.addLast(name, instance);
                    sslPlayHandler.pipelines.put("Ssl" + name, instance);
                }
            } catch (Throwable e) {
                Logger.error(" error adding " + handler, e);
            }

        }

        if (sslPlayHandler != null) {
            pipeline.addLast("handler", sslPlayHandler);
            sslPlayHandler.pipelines.put("SslHandler", sslPlayHandler);
        }
    }
}
