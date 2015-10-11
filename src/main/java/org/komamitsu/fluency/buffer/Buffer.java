package org.komamitsu.fluency.buffer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.komamitsu.fluency.sender.Sender;
import org.msgpack.jackson.dataformat.MessagePackFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class Buffer<T extends Buffer.Config>
{
    private static final Logger LOG = LoggerFactory.getLogger(Buffer.class);
    protected static final Charset CHARSET = Charset.forName("ASCII");
    protected final T bufferConfig;
    protected final AtomicInteger allocatedSize = new AtomicInteger();
    protected final ThreadLocal<ObjectMapper> objectMapperHolder = new ThreadLocal<ObjectMapper>() {
        @Override
        protected ObjectMapper initialValue()
        {
            return new ObjectMapper(new MessagePackFactory());
        }
    };
    protected final ThreadLocal<ByteArrayOutputStream> outputStreamHolder = new ThreadLocal<ByteArrayOutputStream>() {
        @Override
        protected ByteArrayOutputStream initialValue()
        {
            return new ByteArrayOutputStream();
        }
    };

    public static class BufferFullException extends IOException {
        public BufferFullException(String s)
        {
            super(s);
        }
    }

    public Buffer(T bufferConfig)
    {
        this.bufferConfig = bufferConfig;
    }

    public abstract void append(String tag, long timestamp, Map<String, Object> data)
            throws IOException;

    public void flush(Sender sender, boolean force)
            throws IOException
    {
        LOG.trace("flush(): force={}, bufferUsage={}", force, getBufferUsage());
        flushInternal(sender, force);
    }

    public abstract void flushInternal(Sender sender, boolean force)
            throws IOException;

    public void close(Sender sender)
            throws IOException
    {
        closeInternal(sender);
    }

    protected abstract void closeInternal(Sender sender)
            throws IOException;

    public int getAllocatedSize()
    {
        return allocatedSize.get();
    }

    public int getMaxSize()
    {
        return bufferConfig.getMaxBufferSize();
    }

    public float getBufferUsage()
    {
        return (float) getAllocatedSize() / getMaxSize();
    }

    public abstract static class Config<T extends Buffer, C extends Config>
    {
        protected int maxBufferSize = 16 * 1024 * 1024;
        protected boolean ackResponseMode = false;

        public int getMaxBufferSize()
        {
            return maxBufferSize;
        }

        public C setMaxBufferSize(int maxBufferSize)
        {
            this.maxBufferSize = maxBufferSize;
            return (C)this;
        }

        public boolean isAckResponseMode()
        {
            return ackResponseMode;
        }

        public C setAckResponseMode(boolean ackResponseMode)
        {
            this.ackResponseMode = ackResponseMode;
            return (C)this;
        }

        @Override
        public String toString()
        {
            return "Config{" +
                    "maxBufferSize=" + maxBufferSize +
                    ", ackResponseMode=" + ackResponseMode +
                    '}';
        }

        public abstract T createInstance();
    }
}
