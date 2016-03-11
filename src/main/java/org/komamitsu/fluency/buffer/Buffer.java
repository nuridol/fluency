package org.komamitsu.fluency.buffer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.komamitsu.fluency.sender.Sender;
import org.msgpack.jackson.dataformat.MessagePackFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class Buffer<T extends Buffer.Config>
{
    private static final Logger LOG = LoggerFactory.getLogger(Buffer.class);
    protected static final Charset CHARSET = Charset.forName("ASCII");
    protected final T bufferConfig;
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
    protected final FileBackup fileBackup;

    public Buffer(T bufferConfig)
    {
        this.bufferConfig = bufferConfig;
        if (bufferConfig.getFileBackupDir() != null) {
            fileBackup = new FileBackup(new File(bufferConfig.getFileBackupDir()), this);
            for (FileBackup.SavedBuffer savedBuffer : fileBackup.getSavedFiles()) {
                savedBuffer.open(new FileBackup.SavedBuffer.Callback() {
                    @Override
                    public void process(List<String> params, ByteBuffer buffer)
                    {
                        loadBuffer(params, buffer);
                    }
                });
            }
        }
        else {
            fileBackup = null;
        }
    }

    public abstract void append(String tag, long timestamp, Map<String, Object> data)
            throws IOException;

    protected abstract void loadBuffer(List<String> params, ByteBuffer buffer);

    public void flush(Sender sender, boolean force)
            throws IOException
    {
        LOG.trace("flush(): force={}, bufferUsage={}", force, getBufferUsage());
        flushInternal(sender, force);
    }

    protected abstract void flushInternal(Sender sender, boolean force)
            throws IOException;

    public abstract String bufferFormatType();

    public void close()
    {
        closeInternal();
    }

    protected abstract void closeInternal();

    public abstract long getAllocatedSize();

    public long getMaxSize()
    {
        return bufferConfig.getMaxBufferSize();
    }

    public float getBufferUsage()
    {
        return (float) getAllocatedSize() / getMaxSize();
    }

    public abstract static class Config<T extends Buffer, C extends Config>
    {
        protected long maxBufferSize = 512 * 1024 * 1024;
        protected boolean ackResponseMode = false;
        protected String fileBackupDir;

        public long getMaxBufferSize()
        {
            return maxBufferSize;
        }

        public C setMaxBufferSize(long maxBufferSize)
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

        public String getFileBackupDir()
        {
            return fileBackupDir;
        }

        public C setFileBackupDir(String fileBackupDir)
        {
            this.fileBackupDir = fileBackupDir;
            return (C) this;
        }

        @Override
        public String toString()
        {
            return "Config{" +
                    "maxBufferSize=" + maxBufferSize +
                    ", ackResponseMode=" + ackResponseMode +
                    ", fileBackupDir='" + fileBackupDir + '\'' +
                    '}';
        }

        public abstract T createInstance();
    }
}
