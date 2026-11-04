package com.asu.ysmrelay.upload;

import com.asu.ysmrelay.net.msg.UploadChunk;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * 一次服务端上传的接收状态。
 *
 * <p>不放在内存里拼：每个分片直接追加写到 staging 文件，靠 {@link #received} 和
 * {@link #expectedSeq} 防重放、防乱序、防多收。封口条件是「收到 last 且字节数正好等于声明值」，
 * 两个条件缺一不可 —— 只看 last 会让少传的包被当成完整文件。
 *
 * <p>线程模型：所有方法由 {@code UploadService} 在服务端主线程调用，所以用 synchronized 串行化。
 */
final class ServerUpload implements AutoCloseable {
    final UUID playerId;
    final long transferId;
    final byte[] declaredModelId;
    final String originalName;
    final Path stagingFile;
    final long expectedSize;

    private OutputStream out;
    private long received;
    private int expectedSeq;
    private boolean sealed;

    ServerUpload(UUID playerId, long transferId, byte[] declaredModelId, String originalName, Path stagingFile,
        long expectedSize) {
        this.playerId = playerId;
        this.transferId = transferId;
        this.declaredModelId = declaredModelId;
        this.originalName = originalName;
        this.stagingFile = stagingFile;
        this.expectedSize = expectedSize;
    }

    void open() throws IOException {
        Files.createDirectories(stagingFile.getParent());
        out = Files.newOutputStream(stagingFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    synchronized void append(UploadChunk chunk) throws IOException {
        if (sealed) {
            throw new IOException("upload is already sealed");
        }
        if (chunk.seq != expectedSeq) {
            throw new IOException("chunk out of order: expected " + expectedSeq + ", got " + chunk.seq);
        }
        if (chunk.data.length == 0) {
            throw new IOException("empty chunk");
        }
        if (received + chunk.data.length > expectedSize) {
            throw new IOException("received more bytes than declared (" + expectedSize + ")");
        }
        var stream = out;
        if (stream == null) {
            throw new IOException("upload stream is closed");
        }
        stream.write(chunk.data);
        stream.flush();
        received += chunk.data.length;
        expectedSeq++;
        if (chunk.last) {
            stream.close();
            out = null;
            sealed = true;
        }
    }

    /** 封口且字节数正好对上。 */
    synchronized boolean isComplete() {
        return sealed && received == expectedSize;
    }

    synchronized long received() {
        return received;
    }

    synchronized boolean isSealed() {
        return sealed;
    }

    @Override
    public synchronized void close() {
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // 关闭失败不能掩盖真正的上传错误
            }
            out = null;
        }
    }

    /** 放弃这次上传：关流并删掉暂存文件。 */
    void discard() {
        close();
        try {
            Files.deleteIfExists(stagingFile);
        } catch (IOException ignored) {
            // 残留由 TTL 清理兜底
        }
    }
}
