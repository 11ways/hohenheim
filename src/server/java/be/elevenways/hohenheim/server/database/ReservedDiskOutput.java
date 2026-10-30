package be.elevenways.hohenheim.server.database;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.LongSupplier;

/**
 * A dump's file write that stops before its disk drops under the reserve kept free
 * ({@link DatabaseBackups#DUMP_FREE_RESERVE_BYTES}): the free space is read again every {@link #CHECK_EVERY_BYTES}.
 *
 * AIDEV-NOTE: the size check before a dump can only estimate from the previous dump, and a database that grew since
 * would still fill the disk the engines live on; this is the exact half. A refusal leaves the {@code .part} file for
 * the caller to delete, as an over-cap dump does.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class ReservedDiskOutput extends FilterOutputStream {

    /** How many bytes may be written between two reads of the disk's free space. */
    public static final long CHECK_EVERY_BYTES = 64L * 1024 * 1024;

    private final @NonNull LongSupplier freeBytes;
    private final @NonNull String name;
    private final long reserve;
    private long sinceCheck;

    /**
     * @param freeBytes what the disk has free right now, read before the first byte and every
     *                  {@link #CHECK_EVERY_BYTES} after
     * @param name      what is dumped, for the refusal
     * @param reserve   the bytes that must stay free
     * @throws DumpOverCap when the disk has no room even for the first chunk
     */
    public ReservedDiskOutput(@NonNull OutputStream out, @NonNull LongSupplier freeBytes, @NonNull String name,
                              long reserve) throws IOException {
        super(out);
        this.freeBytes = freeBytes;
        this.name = name;
        this.reserve = reserve;
        requireRoom();
    }

    /** A write to {@code file}, watching the disk it lives on. */
    static @NonNull ReservedDiskOutput onDiskOf(@NonNull OutputStream out, @NonNull Path file, @NonNull String name,
                                                long reserve) throws IOException {
        FileStore disk = Files.getFileStore(file.toAbsolutePath().getParent());
        return new ReservedDiskOutput(out, () -> {
            try {
                return disk.getUsableSpace();
            } catch (IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
        }, name, reserve);
    }

    @Override
    public void write(int b) throws IOException {
        counted(1);
        this.out.write(b);
    }

    @Override
    public void write(byte @NonNull [] bytes, int offset, int length) throws IOException {
        counted(length);
        this.out.write(bytes, offset, length);
    }

    private void counted(int length) throws IOException {
        this.sinceCheck += length;
        if (this.sinceCheck >= CHECK_EVERY_BYTES) {
            this.sinceCheck = 0;
            requireRoom();
        }
    }

    /** Room for the reserve and the next chunk, so the disk never drops under the reserve between two reads. */
    private void requireRoom() throws IOException {
        long free = this.freeBytes.getAsLong();
        if (free < this.reserve + CHECK_EVERY_BYTES) {
            throw DumpOverCap.noRoom(this.name, free, this.reserve + CHECK_EVERY_BYTES);
        }
    }
}
