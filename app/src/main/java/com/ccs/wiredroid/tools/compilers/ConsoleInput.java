package com.ccs.wiredroid.tools.compilers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * The stream a running program reads when it asks for console input.
 *
 * <p>Installed as {@code System.in} for the duration of a run and fed a line at
 * a time from the console's input field. A read with nothing queued blocks the
 * program's thread, which is exactly what a program waiting for input should
 * do — and why the run has to be stoppable: {@link #close()} unblocks it and
 * reports end of input rather than leaving the thread parked forever.</p>
 *
 * <p>Built on a queue rather than {@link java.io.PipedInputStream}, which ties
 * itself to the thread that wrote last and throws "write end dead" when that
 * thread — here, whichever main-thread dispatch delivered the line — goes
 * away.</p>
 */
public final class ConsoleInput extends InputStream {

    /** Handed to the queue to mean "no more input", since it cannot take null. */
    private static final byte[] EOF = new byte[0];

    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private byte[] current;
    private int offset;
    private volatile boolean closed;
    private volatile Runnable beforeBlock;

    /**
     * Runs just before a read has to wait for input.
     *
     * <p>Used to flush pending output. {@code System.out.print("Name: ")} does
     * not end a line, and an autoflushing {@link java.io.PrintStream} only
     * flushes on one — so the prompt would sit in the buffer while the program
     * blocked waiting for an answer to a question the screen never asked. A
     * terminal flushes stdout before reading stdin; so does this.</p>
     */
    public void setBeforeBlock(Runnable action) {
        this.beforeBlock = action;
    }

    /**
     * Queues one line, adding the newline the reader is waiting for.
     *
     * <p>{@code Scanner.nextLine()} and {@code BufferedReader.readLine()} both
     * block until they see a line terminator, so a line submitted without one
     * would leave the program hanging on input it has already been given.</p>
     */
    public void submitLine(String line) {
        if (closed) return;
        String text = (line == null ? "" : line) + "\n";
        queue.add(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Reports end of input and releases any thread blocked in {@link #read()}. */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        queue.add(EOF);
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public int read() throws IOException {
        if (!ensureCurrent()) return -1;
        return current[offset++] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (b == null) throw new NullPointerException();
        if (off < 0 || len < 0 || len > b.length - off) throw new IndexOutOfBoundsException();
        if (len == 0) return 0;
        if (!ensureCurrent()) return -1;
        int n = Math.min(len, current.length - offset);
        System.arraycopy(current, offset, b, off, n);
        offset += n;
        return n;
    }

    /**
     * Bytes readable without blocking.
     *
     * <p>Zero rather than a guess when the queue is empty: a program that polls
     * {@code available()} before reading must not be told there is input when
     * the user has not typed any.</p>
     */
    @Override
    public int available() {
        if (current != null && offset < current.length) return current.length - offset;
        return 0;
    }

    /** Blocks until there is a byte to hand out, or input has ended. */
    private boolean ensureCurrent() throws IOException {
        while (current == null || offset >= current.length) {
            if (closed && queue.isEmpty()) return false;
            if (queue.isEmpty()) {
                Runnable flush = beforeBlock;
                if (flush != null) {
                    try {
                        flush.run();
                    } catch (Throwable ignored) {
                        // Flushing is a courtesy to the reader; a program must
                        // not fail to read because the console could not draw.
                    }
                }
            }
            byte[] next;
            try {
                next = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for console input", e);
            }
            if (next == EOF) {
                // Put it back so every other reader sees the end too.
                queue.add(EOF);
                return false;
            }
            current = next;
            offset = 0;
        }
        return true;
    }
}
