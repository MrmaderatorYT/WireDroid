package com.ccs.wiredroid.archive;

import com.github.junrar.Archive;
import com.github.junrar.exception.RarException;
import com.github.junrar.exception.UnsupportedRarV5Exception;
import com.github.junrar.rarfile.FileHeader;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * RAR reading, isolated so the rest of the archive package does not depend on
 * junrar directly.
 *
 * <p>Two limits are inherent and reported rather than worked around. junrar
 * implements the RAR4 container only, so an archive written by WinRAR 5 or
 * later (the default since 2013) is rejected with a clear message; and
 * encrypted archives are refused because there is nowhere to ask for the
 * password mid-import. Everything else — including the extraction safety rules
 * — goes through {@link ArchiveExtractor}'s shared helpers.</p>
 */
final class RarSupport {

    private RarSupport() {}

    static ArchiveExtractor.Result extract(File archive, File root,
                                           ArchiveExtractor.Listener listener) throws IOException {
        ArchiveExtractor.Counters counters = new ArchiveExtractor.Counters();
        try (Archive rar = new Archive(archive)) {
            if (rar.isEncrypted()) {
                throw new IOException("This RAR archive is encrypted; extract it on a "
                        + "desktop and import the folder instead.");
            }
            FileHeader header;
            while ((header = rar.nextFileHeader()) != null) {
                counters.checkEntryCount();
                String name = headerName(header);
                File target = ArchiveExtractor.resolve(root, name, counters);
                if (target == null) continue;

                if (header.isDirectory()) {
                    ArchiveExtractor.mkdirs(target);
                    counters.directories++;
                    continue;
                }
                if (header.isEncrypted()) {
                    counters.skip(name, "encrypted entry");
                    continue;
                }

                ArchiveExtractor.mkdirs(target.getParentFile());
                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                    rar.extractFile(header, new CountingOutputStream(out, counters));
                }
                counters.files++;
                ArchiveExtractor.notifyEntry(listener, name, counters);
            }
        } catch (UnsupportedRarV5Exception e) {
            throw new IOException("RAR5 archives are not supported — only the older RAR4 "
                    + "format can be read. Re-create it with \"RAR4\" compatibility, or "
                    + "import a ZIP or TAR instead.");
        } catch (RarException e) {
            throw new IOException("Cannot read RAR archive: " + e.getMessage(), e);
        }
        return counters.toResult(ArchiveFormat.RAR);
    }

    /** Prefers the Unicode name; older archives only carry the raw one. */
    private static String headerName(FileHeader header) {
        String name = header.getFileName();
        if (name == null || name.isEmpty()) name = header.getFileNameW();
        if (name == null || name.isEmpty()) name = header.getFileNameString();
        return name == null ? "" : name;
    }

    /** Feeds the byte total (and its ceiling) while junrar writes. */
    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final ArchiveExtractor.Counters counters;

        CountingOutputStream(OutputStream delegate, ArchiveExtractor.Counters counters) {
            this.delegate = delegate;
            this.counters = counters;
        }

        @Override public void write(int b) throws IOException {
            delegate.write(b);
            counters.addBytes(1);
        }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            counters.addBytes(len);
        }

        @Override public void flush() throws IOException {
            delegate.flush();
        }
        // close() is intentionally not forwarded: the caller's try-with-resources
        // owns the underlying stream.
    }
}
