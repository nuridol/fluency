package org.komamitsu.fluency;

import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.ExtensionValue;
import org.msgpack.value.ImmutableArrayValue;
import org.msgpack.value.ImmutableValue;
import org.msgpack.value.MapValue;
import org.msgpack.value.RawValue;
import org.msgpack.value.StringValue;
import org.msgpack.value.Value;
import org.msgpack.value.ValueFactory;
import org.msgpack.value.ValueType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public abstract class AbstractFluentdServer
        extends MockTCPServer
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractFluentdServer.class);
    private static final Charset CHARSET = Charset.forName("UTF-8");
    private FluentdEventHandler fluentdEventHandler;

    public interface EventHandler
    {
        void onConnect(SocketChannel acceptSocketChannel);

        void onReceive(String tag, long timestampMillis, MapValue data);

        void onClose(SocketChannel accpetSocketChannel);
    }

    private static class FluentdEventHandler
            implements MockTCPServer.EventHandler
    {
        private static final StringValue KEY_OPTION_SIZE = ValueFactory.newString("size");
        private static final StringValue KEY_OPTION_CHUNK = ValueFactory.newString("chunk");
        private final EventHandler eventHandler;
        private final ExecutorService executorService = Executors.newCachedThreadPool();
        private final Map<SocketChannel, FluentdAcceptTask> fluentdTasks = new ConcurrentHashMap<SocketChannel, FluentdAcceptTask>();

        private FluentdEventHandler(EventHandler eventHandler)
        {
            this.eventHandler = eventHandler;
        }

        private void ack(SocketChannel acceptSocketChannel, byte[] ackResponseToken)
                throws IOException
        {
            ByteBuffer byteBuffer = ByteBuffer.allocate(
                    1 /* map header */ +
                    1 /* key header */ +
                    3 /* key body */ +
                    2 /* value header(including len) */ +
                    ackResponseToken.length);

            byteBuffer.put((byte) 0x81); /* map header */
            byteBuffer.put((byte) 0xA3); /* key header */
            byteBuffer.put("ack".getBytes(CHARSET));    /* key body */
            byteBuffer.put((byte) 0xC4);
            byteBuffer.put((byte) ackResponseToken.length);
            byteBuffer.put(ackResponseToken);
            byteBuffer.flip();
            acceptSocketChannel.write(byteBuffer);
        }

        private class FluentdAcceptTask implements Runnable
        {
            private final SocketChannel acceptSocketChannel;
            private final PipedInputStream pipedInputStream;
            private final PipedOutputStream pipedOutputStream;

            private FluentdAcceptTask(SocketChannel acceptSocketChannel)
                    throws IOException
            {
                this.acceptSocketChannel = acceptSocketChannel;
                this.pipedOutputStream = new PipedOutputStream();
                this.pipedInputStream = new PipedInputStream(pipedOutputStream);
            }

            PipedInputStream getPipedInputStream()
            {
                return pipedInputStream;
            }

            PipedOutputStream getPipedOutputStream()
            {
                return pipedOutputStream;
            }

            @Override
            public void run()
            {
                MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(pipedInputStream);

                try {
                    while (!executorService.isTerminated()) {
                        ImmutableValue value;
                        try {
                            if (!unpacker.hasNext()) {
                                break;
                            }
                            value = unpacker.unpackValue();
                            LOG.trace("value={}, local.port={}, remote.port={}", value, acceptSocketChannel.socket().getLocalPort(), acceptSocketChannel.socket().getPort());
                        }
                        catch (Exception e) {
                            LOG.debug("Fluentd accept task received IOException");
                            break;
                        }
                        assertEquals(ValueType.ARRAY, value.getValueType());
                        ImmutableArrayValue rootValue = value.asArrayValue();
                        assertEquals(rootValue.size(), 3);

                        String tag = rootValue.get(0).toString();
                        Value secondValue = rootValue.get(1);

                        // PackedForward
                        byte[] packedBytes = secondValue.asRawValue().asByteArray();
                        MessageUnpacker eventsUnpacker = MessagePack.newDefaultUnpacker(packedBytes);
                        while (eventsUnpacker.hasNext()) {
                            ImmutableArrayValue arrayValue = eventsUnpacker.unpackValue().asArrayValue();
                            assertEquals(2, arrayValue.size());
                            Value timestampValue = arrayValue.get(0);
                            MapValue mapValue = arrayValue.get(1).asMapValue();
                            long timestampMillis;
                            if (timestampValue.isIntegerValue()) {
                                timestampMillis = timestampValue.asIntegerValue().asLong() * 1000;
                            }
                            else if (timestampValue.isExtensionValue()) {
                                ExtensionValue extensionValue = timestampValue.asExtensionValue();
                                if (extensionValue.getType() != 0) {
                                    throw new IllegalArgumentException("Unexpected extension type: " + extensionValue.getType());
                                }
                                byte[] data = extensionValue.getData();
                                long seconds = ByteBuffer.wrap(data, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                                long nanos = ByteBuffer.wrap(data, 4, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                                timestampMillis = seconds * 1000 + nanos / 1000000;
                            }
                            else {
                                throw new IllegalArgumentException("Unexpected value type: " + timestampValue);
                            }
                            eventHandler.onReceive(tag, timestampMillis, mapValue);
                        }

                        // Option
                        Map<Value, Value> map = rootValue.get(2).asMapValue().map();
                        //    "size"
                        assertEquals(map.get(KEY_OPTION_SIZE).asIntegerValue().asLong(), packedBytes.length);
                        //    "chunk"
                        Value chunk = map.get(KEY_OPTION_CHUNK);
                        if (chunk != null) {
                            RawValue ackResponseToken = chunk.asRawValue();
                            ack(acceptSocketChannel, ackResponseToken.asBinaryValue().asByteArray());
                        }
                    }

                    try {
                        LOG.debug("Closing unpacker: this={}, local.port={}, remote.port={}", this, acceptSocketChannel.socket().getLocalPort(), acceptSocketChannel.socket().getPort());
                        unpacker.close();
                    }
                    catch (IOException e) {
                        LOG.warn("Failed to close unpacker quietly: this={}, unpacker={}", this, unpacker);
                    }
                }
                catch (Throwable e) {
                    LOG.error("Fluentd server failed: this=" + this + ", local.port=" + acceptSocketChannel.socket().getLocalPort() + ", remote.port=" + acceptSocketChannel.socket().getPort(), e);
                    try {
                        acceptSocketChannel.close();
                    }
                    catch (IOException e1) {
                        LOG.warn("Failed to close accept socket quietly", e1);
                    }
                }
            }
        }

        @Override
        public void onConnect(final SocketChannel acceptSocketChannel)
        {
            eventHandler.onConnect(acceptSocketChannel);
            try {
                FluentdAcceptTask fluentdAcceptTask = new FluentdAcceptTask(acceptSocketChannel);
                fluentdTasks.put(acceptSocketChannel, fluentdAcceptTask);
                executorService.execute(fluentdAcceptTask);
            }
            catch (IOException e) {
                fluentdTasks.remove(acceptSocketChannel);
                throw new IllegalStateException("Failed to create FluentdAcceptTask", e);
            }
        }

        @Override
        public void onReceive(SocketChannel acceptSocketChannel, ByteBuffer data)
        {
            FluentdAcceptTask fluentdAcceptTask = fluentdTasks.get(acceptSocketChannel);
            if (fluentdAcceptTask == null) {
                throw new IllegalStateException("fluentAccept is null: this=" + this);
            }
            data.flip();
            byte[] bytes = new byte[data.limit()];
            data.get(bytes);

            LOG.trace("onReceived: local.port={}, remote.port={}, dataLen={}", acceptSocketChannel.socket().getLocalPort(), acceptSocketChannel.socket().getPort(), bytes.length);
            try {
                fluentdAcceptTask.getPipedOutputStream().write(bytes);
                fluentdAcceptTask.getPipedOutputStream().flush();
            }
            catch (IOException e) {
                throw new RuntimeException("Failed to call PipedOutputStream.write(): this=" + this);
            }
        }

        @Override
        public void onClose(SocketChannel acceptSocketChannel)
        {
            eventHandler.onClose(acceptSocketChannel);
            FluentdAcceptTask fluentdAcceptTask = fluentdTasks.remove(acceptSocketChannel);
            try {
                fluentdAcceptTask.getPipedInputStream().close();
            }
            catch (IOException e) {
                LOG.warn("Failed to close PipedInputStream");
            }
            try {
                fluentdAcceptTask.getPipedOutputStream().close();
            }
            catch (IOException e) {
                LOG.warn("Failed to close PipedOutputStream");
            }
        }
    }

    public AbstractFluentdServer()
            throws IOException
    {
        super();
    }

    @Override
    protected synchronized MockTCPServer.EventHandler getEventHandler()
    {
        if (this.fluentdEventHandler == null) {
            this.fluentdEventHandler = new FluentdEventHandler(getFluentdEventHandler());
        }
        return this.fluentdEventHandler;
    }

    protected abstract EventHandler getFluentdEventHandler();
}

