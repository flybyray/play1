package play.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpMessage;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import org.apache.commons.io.IOUtils;
import play.Play;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.List;
import java.util.UUID;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpHeaderValues.CHUNKED;

public class StreamChunkAggregator extends SimpleChannelInboundHandler<FullHttpMessage> {

    private volatile FullHttpMessage currentMessage;
    private volatile OutputStream out;
    private static final int maxContentLength = Integer.valueOf(Play.configuration.getProperty("play.netty.maxContentLength", "-1"));
    private volatile File file;

    /**
     * Creates a new instance.
     */
    public StreamChunkAggregator() {
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpMessage httpMessage) throws Exception {
        if (!(httpMessage instanceof FullHttpMessage) && !(httpMessage instanceof HttpContent)) {
            ctx.fireChannelRead(httpMessage);
            return;
        }

        FullHttpMessage currentMessage = this.currentMessage;
        File localFile = this.file;
        if (currentMessage == null) {
            if (HttpUtil.isTransferEncodingChunked(httpMessage)) {
                String localName = UUID.randomUUID().toString();
                // A chunked message - remove 'Transfer-Encoding' header,
                // initialize the cumulative buffer, and wait for incoming chunks.
                List<String> encodings = httpMessage.headers().getAll(TRANSFER_ENCODING);
                encodings.remove(CHUNKED);
                if (encodings.isEmpty()) {
                    httpMessage.headers().remove(TRANSFER_ENCODING);
                }
                this.currentMessage = httpMessage;
                this.file = new File(Play.tmpDir, localName);
                this.out = new FileOutputStream(file, true);
            } else {
                // Not a chunked message - pass through.
                ctx.fireChannelRead(httpMessage);
            }
        } else {
            // TODO: If less that threshold then in memory
            // Merge the received chunk into the content of the current message.
            HttpContent chunk = (HttpContent) httpMessage;
            if (maxContentLength != -1 && (localFile.length() > (maxContentLength - chunk.content().readableBytes()))) {
                currentMessage.headers().set(WARNING, "play.netty.content.length.exceeded");
            } else {
                IOUtils.copyLarge(new ByteBufInputStream(chunk.content()), this.out);

                if (chunk instanceof LastHttpContent) {
                    this.out.flush();
                    this.out.close();

                    currentMessage.headers().set(
                            CONTENT_LENGTH,
                            String.valueOf(localFile.length()));

                    RandomAccessFile randomAccessFile = new RandomAccessFile(localFile, "r");
                    int len = Math.toIntExact(randomAccessFile.length());
                    ByteBuf buffer = Unpooled.buffer(len);
                    buffer.readBytes(randomAccessFile.getChannel(), 0, len);
                    currentMessage.content().writeBytes(buffer);
                    this.out = null;
                    this.currentMessage = null;
                    this.file.delete();
                    this.file = null;
                    ctx.pipeline().fireChannelRead(currentMessage);
                }
            }
        }

    }
}

