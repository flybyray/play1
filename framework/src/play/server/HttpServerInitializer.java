package play.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import play.Logger;
import play.Play;

import java.util.HashMap;
import java.util.Map;

public class HttpServerInitializer extends ChannelInitializer<Channel> {

    public static final String IO_NETTY_HANDLER_CODEC_HTTP_HTTP_REQUEST_DECODER = "io.netty.handler.codec.http.HttpRequestDecoder";
    public static final String IO_NETTY_HANDLER_CODEC_HTTP_HTTP_RESPONSE_ENCODER = "io.netty.handler.codec.http.HttpResponseEncoder";
    public static final String IO_NETTY_HANDLER_STREAM_CHUNKED_WRITE_HANDLER = "io.netty.handler.stream.ChunkedWriteHandler";
    public static final String PLAY_SERVER_FLASH_POLICY_HANDLER = "play.server.FlashPolicyHandler";
    public static final String PLAY_SERVER_STREAM_CHUNK_AGGREGATOR = "play.server.StreamChunkAggregator";
    public static final String PLAY_SERVER_PLAY_HANDLER = "play.server.PlayHandler";
    public static final String PLAY_SERVER_SSL_SSL_PLAY_HANDLER = "play.server.ssl.SslPlayHandler";

    private String play_netty_pipeline = Play
            .configuration.getProperty(
                    "play.netty.pipeline",
                    PLAY_SERVER_FLASH_POLICY_HANDLER + "," +
                            IO_NETTY_HANDLER_CODEC_HTTP_HTTP_REQUEST_DECODER + "," +
                            PLAY_SERVER_STREAM_CHUNK_AGGREGATOR + "," +
                            IO_NETTY_HANDLER_CODEC_HTTP_HTTP_RESPONSE_ENCODER + "," +
                            IO_NETTY_HANDLER_STREAM_CHUNKED_WRITE_HANDLER + "," +
                            PLAY_SERVER_PLAY_HANDLER);

    private final static Map<String, Class> classes = new HashMap<>();

    @Override
    protected void initChannel(Channel channel) {

        ChannelPipeline pipeline = channel.pipeline();
        
        String[] handlers = play_netty_pipeline.split(",");
        if(handlers.length <= 0){
            Logger.error("You must defined at least the playHandler in \"play.netty.pipeline\"");
            return;
        }       
        
        // Create the play Handler (always the last one)
        String handler = handlers[handlers.length - 1];
        ChannelHandler instance = getInstance(handler);
        PlayHandler playHandler = (PlayHandler) instance;
        if (playHandler == null) {
            Logger.error("The last handler must be the playHandler in \"play.netty.pipeline\"");
            return;
        }
      
        // Get all the pipeline. Give the user the opportunity to add their own
        for (int i = 0; i < handlers.length - 1; i++) {
            handler = handlers[i];
            try {
                String name = getName(handler.trim());
                instance = getInstance(handler);
                if (instance != null) {
                    pipeline.addLast(name, instance);
                    playHandler.pipelines.put(name, instance);
                }
            } catch (Throwable e) {
                Logger.error(" error adding " + handler, e);
            }
        }

        pipeline.addLast("handler", playHandler);
        playHandler.pipelines.put("handler", playHandler);
        }

    protected String getName(String name) {
        if (name.lastIndexOf(".") > 0)
            return name.substring(name.lastIndexOf(".") + 1);
        return name;
    }

    protected ChannelHandler getInstance(String name) {

        Class clazz = classes.get(name);
        if (clazz == null) {
            try {
                clazz = Class.forName(name);
                classes.put(name, clazz);
            } catch (ClassNotFoundException e) {
                Logger.error(e,"name: %s", name);
            }
        }
        if (ChannelHandler.class.isAssignableFrom(clazz)) {
            try {
                return (ChannelHandler)clazz.newInstance();
            } catch (InstantiationException e) {
                Logger.error(e,"name: %s", name);
            } catch (IllegalAccessException e) {
                Logger.error(e,"name: %s", name);
            }
        }
        return null;
    }

//    @Override
//    protected void initChannel(Channel channel) {
//        ChannelPipeline pipeline = channel.pipeline();
//
//        SslContext sslCtx;
//        if (sslCtx != null) {
//            pipeline.addLast(sslCtx.newHandler(channel.alloc()));
//        }
//
//        // Enable stream compression (you can remove these two if unnecessary)
//        pipeline.addLast(ZlibCodecFactory.newZlibEncoder(ZlibWrapper.GZIP));
//        pipeline.addLast(ZlibCodecFactory.newZlibDecoder(ZlibWrapper.GZIP));
//
//        // Add the number codec first,
//        pipeline.addLast(new BigIntegerDecoder());
//        pipeline.addLast(new NumberEncoder());
//
//        // and then business logic.
//        // Please note we create a handler for every new channel
//        // because it has stateful properties.
//        pipeline.addLast(new FactorialServerHandler());
//    }
}

