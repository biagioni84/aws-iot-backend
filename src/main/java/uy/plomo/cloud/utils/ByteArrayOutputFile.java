package uy.plomo.cloud.utils;

import org.apache.parquet.io.OutputFile;
import org.apache.parquet.io.PositionOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * In-memory Parquet OutputFile. Write a complete Parquet file to a byte array,
 * then upload to S3 — no temp files, no Hadoop FS required.
 */
public class ByteArrayOutputFile implements OutputFile {

    private final ByteArrayOutputStream baos = new ByteArrayOutputStream();

    @Override
    public PositionOutputStream create(long blockSizeHint) {
        return stream();
    }

    @Override
    public PositionOutputStream createOrOverwrite(long blockSizeHint) {
        return stream();
    }

    @Override public boolean supportsBlockSize() { return false; }
    @Override public long defaultBlockSize()      { return 0; }

    public byte[] toByteArray() { return baos.toByteArray(); }

    private PositionOutputStream stream() {
        return new PositionOutputStream() {
            private long pos = 0;

            @Override public long getPos() { return pos; }

            @Override
            public void write(int b) throws IOException {
                baos.write(b);
                pos++;
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                baos.write(b, off, len);
                pos += len;
            }

            @Override public void flush() throws IOException { baos.flush(); }
            @Override public void close() {}
        };
    }
}
