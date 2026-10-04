package com.ccs.wiredroid.tools.compilers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The stream standing in for {@code System.in} while a program runs.
 *
 * <p>Read through {@link Scanner} and {@link BufferedReader} here rather than
 * byte by byte, because those are what user programs actually use and they are
 * what the newline handling has to satisfy.</p>
 */
public class ConsoleInputTest {

    @Test
    public void scannerReadsASubmittedLine() {
        ConsoleInput in = new ConsoleInput();
        in.submitLine("Dmytro");
        Scanner sc = new Scanner(in);
        assertTrue(sc.hasNextLine());
        assertEquals("Dmytro", sc.nextLine());
    }

    @Test
    public void bufferedReaderReadsLinesInOrder() throws Exception {
        ConsoleInput in = new ConsoleInput();
        in.submitLine("first");
        in.submitLine("second");
        in.close();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        assertEquals("first", r.readLine());
        assertEquals("second", r.readLine());
        assertNull("closing must report end of input", r.readLine());
    }

    @Test
    public void readBlocksUntilALineArrives() throws Exception {
        ConsoleInput in = new ConsoleInput();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        final String[] got = new String[1];

        Thread program = new Thread(() -> {
            started.countDown();
            got[0] = new Scanner(in).nextLine();
            done.countDown();
        });
        program.start();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertFalse("must still be waiting with nothing submitted",
                done.await(200, TimeUnit.MILLISECONDS));

        in.submitLine("late");
        assertTrue("submitting must release the reader", done.await(2, TimeUnit.SECONDS));
        assertEquals("late", got[0]);
    }

    @Test
    public void closingReleasesABlockedReader() throws Exception {
        ConsoleInput in = new ConsoleInput();
        CountDownLatch done = new CountDownLatch(1);
        final int[] result = { -2 };
        Thread program = new Thread(() -> {
            try {
                result[0] = in.read();
            } catch (Exception ignored) {
            } finally {
                done.countDown();
            }
        });
        program.start();
        Thread.sleep(100);
        in.close();
        assertTrue("stopping a run must not leave the thread parked",
                done.await(2, TimeUnit.SECONDS));
        assertEquals(-1, result[0]);
    }

    @Test
    public void endOfInputStaysEndOfInput() throws Exception {
        ConsoleInput in = new ConsoleInput();
        in.close();
        assertEquals(-1, in.read());
        assertEquals(-1, in.read());
        assertTrue(in.isClosed());
    }

    @Test
    public void availableDoesNotClaimInputThatWasNeverTyped() throws Exception {
        ConsoleInput in = new ConsoleInput();
        assertEquals(0, in.available());
        in.submitLine("x");
        in.read();                       // pulls the line into the buffer
        assertEquals("newline still pending", 1, in.available());
    }

    @Test
    public void submittingAfterCloseIsIgnored() throws Exception {
        ConsoleInput in = new ConsoleInput();
        in.close();
        in.submitLine("too late");
        assertEquals(-1, in.read());
    }

    @Test
    public void anEmptyLineIsStillALine() throws Exception {
        ConsoleInput in = new ConsoleInput();
        in.submitLine("");
        BufferedReader r = new BufferedReader(new InputStreamReader(in));
        assertEquals("", r.readLine());
    }

    @Test
    public void pendingOutputIsFlushedBeforeTheReadBlocks() throws Exception {
        ConsoleInput in = new ConsoleInput();
        final java.util.concurrent.atomic.AtomicInteger flushes =
                new java.util.concurrent.atomic.AtomicInteger();
        in.setBeforeBlock(flushes::incrementAndGet);

        // Nothing queued yet, so this read must wait — and flush on the way.
        Thread reader = new Thread(() -> {
            try { in.read(); } catch (Exception ignored) { }
        });
        reader.start();
        Thread.sleep(150);
        assertTrue("the prompt must be flushed before the program waits",
                flushes.get() >= 1);
        in.submitLine("x");
        reader.join(2000);
    }

    @Test
    public void readingQueuedInputDoesNotNeedAFlush() throws Exception {
        ConsoleInput in = new ConsoleInput();
        final java.util.concurrent.atomic.AtomicInteger flushes =
                new java.util.concurrent.atomic.AtomicInteger();
        in.setBeforeBlock(flushes::incrementAndGet);
        in.submitLine("ready");
        new Scanner(in).nextLine();
        assertEquals("no waiting happened, so nothing to flush", 0, flushes.get());
    }
}
