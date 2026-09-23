package be.elevenways.hohenheim.server.util;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * THE tar codec of the controller: a streaming ustar/PAX {@link Writer} for what the
 * controller pushes into a container, and a STRICT {@link Reader} for what comes back out.
 *
 * AIDEV-NOTE: this replaced the system {@code tar} binary on BOTH lanes, for two defects of
 * the same root. (1) Extraction: a tar a daemon hands back is authored by whatever runs in
 * the container, so an entry can be a SYMLINK to {@code /var/lib/hohenheim/hohenheim.db};
 * unpacking it with {@code tar -xf} and then reading the "extracted file" read the
 * controller's own database or TLS keys. The reader here never creates a link at all:
 * {@link #readSingleFile} accepts exactly one REGULAR entry and {@link #extractTo} accepts
 * regular files and directories only, both refusing links, devices and anything else by
 * name. (2) Creation: file names handed to {@code tar -cf} on its argv were OPTIONS when they
 * started with {@code -} (a file-manager upload named {@code --checkpoint-action=...}). Here a
 * name is only ever data inside a header.
 *
 * AIDEV-NOTE: the writer keeps GNU tar's observable shape so a container sees what it saw
 * before: symlinks are stored as links (never followed), each entry carries the host file's
 * numeric uid/gid and permission bits (the daemon applies them at extraction), a second
 * hard link to one inode is stored as a plain copy, a socket is skipped the way GNU tar
 * skips it, and a device or FIFO is refused rather than recreated inside a workload.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public final class Tar {

    /** What one archive entry is. */
    public enum Kind { FILE, DIRECTORY, SYMLINK, HARDLINK, OTHER }

    /**
     * One entry header as the reader parsed it.
     *
     * @param name     the entry name, PAX/GNU long names already applied
     * @param linkName the link target for SYMLINK/HARDLINK, else empty
     * @param mode     the permission bits (low 12 bits)
     */
    public record Entry(@NonNull String name, @NonNull Kind kind, long size,
                        @NonNull String linkName, int mode) {}

    private static final int BLOCK = 512;
    private static final long MAX_OCTAL_SIZE = 077777777777L;
    private static final long MAX_OCTAL_ID = 07777777L;
    private static final long MAX_OCTAL_TIME = 077777777777L;

    /** Cap on a PAX or GNU long-name body; a real one is a few hundred bytes. */
    private static final int MAX_META_BYTES = 1024 * 1024;

    /**
     * The bytes a single-file archive may carry on top of its file: the entry header, the
     * padding to a block, the two-block end marker and the PAX or GNU records a daemon adds
     * for a long name or extended attributes (a few hundred bytes each in practice).
     */
    private static final long SINGLE_FILE_ENVELOPE_BYTES = 64 * 1024;

    private static final int FILE_TYPE_MASK = 0170000;
    private static final int SOCKET_TYPE = 0140000;

    private Tar() {
    }

    // -- writer ---------------------------------------------------------------

    /**
     * Streams an archive to an {@link OutputStream}; nothing is buffered beyond one block.
     * The caller writes the end-of-archive marker with {@link #finish()} only on success, so
     * a failed walk never produces an archive that LOOKS complete.
     */
    public static final class Writer {

        private final OutputStream out;

        public Writer(@NonNull OutputStream out) {
            this.out = out;
        }

        /**
         * Add one host path under {@code entryName}: a directory brings its whole subtree
         * (children in name order), a symlink is stored as a link and never followed.
         *
         * @throws IOException for a device or FIFO, or a file that changed size mid-read
         */
        public void add(@NonNull Path source, @NonNull String entryName) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(source,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Ownership ownership = Ownership.of(source);
            long mtime = attributes.lastModifiedTime().toMillis() / 1000;
            if (attributes.isSymbolicLink()) {
                writeEntryHeader(entryName, '2', 0, Files.readSymbolicLink(source).toString(),
                    0777, ownership, mtime);
                return;
            }
            if (attributes.isDirectory()) {
                String directoryName = entryName.endsWith("/") ? entryName : entryName + "/";
                writeEntryHeader(directoryName, '5', 0, "", ownership.mode(), ownership, mtime);
                List<Path> children;
                try (Stream<Path> listing = Files.list(source)) {
                    children = listing.sorted().toList();
                }
                for (Path child : children) {
                    add(child, directoryName + child.getFileName());
                }
                return;
            }
            if (attributes.isRegularFile()) {
                long size = attributes.size();
                writeEntryHeader(entryName, '0', size, "", ownership.mode(), ownership, mtime);
                try (InputStream in = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) {
                    copyExactly(in, size, source);
                }
                pad(size);
                return;
            }
            if (ownership.fileType() == SOCKET_TYPE) {
                return;   // GNU tar's own behaviour: a socket is not archivable content
            }
            throw new IOException("Refusing to archive '" + source + "': it is not a regular"
                + " file, a directory or a symlink");
        }

        /**
         * Add ONLY the directory header of {@code source} (its mode and owner), never its
         * contents -- the {@code ./} or {@code prefix/} root entry a whole-tree push carries.
         */
        public void addDirectoryHeader(@NonNull Path source, @NonNull String entryName)
                throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(source,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory()) {
                throw new IOException("'" + source + "' is not a directory");
            }
            Ownership ownership = Ownership.of(source);
            String directoryName = entryName.endsWith("/") ? entryName : entryName + "/";
            writeEntryHeader(directoryName, '5', 0, "", ownership.mode(), ownership,
                attributes.lastModifiedTime().toMillis() / 1000);
        }

        /** Write the end-of-archive marker (two zero blocks) and flush. */
        public void finish() throws IOException {
            this.out.write(new byte[BLOCK * 2]);
            this.out.flush();
        }

        private void copyExactly(InputStream in, long size, Path source) throws IOException {
            byte[] buffer = new byte[64 * 1024];
            long remaining = size;
            while (remaining > 0) {
                int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    throw new IOException("'" + source + "' shrank while it was being archived");
                }
                this.out.write(buffer, 0, read);
                remaining -= read;
            }
        }

        private void pad(long size) throws IOException {
            int padding = (int) ((BLOCK - size % BLOCK) % BLOCK);
            if (padding > 0) {
                this.out.write(new byte[padding]);
            }
        }

        private void writeEntryHeader(String name, char type, long size, String linkName,
                                      int mode, Ownership ownership, long mtime)
                throws IOException {
            Map<String, String> pax = new LinkedHashMap<>();
            if (!fitsUstar(name)) {
                pax.put("path", name);
            }
            if (!fitsUstar(linkName)) {
                pax.put("linkpath", linkName);
            }
            if (size > MAX_OCTAL_SIZE) {
                pax.put("size", String.valueOf(size));
            }
            if (ownership.uid() > MAX_OCTAL_ID) {
                pax.put("uid", String.valueOf(ownership.uid()));
            }
            if (ownership.gid() > MAX_OCTAL_ID) {
                pax.put("gid", String.valueOf(ownership.gid()));
            }
            long clampedTime = Math.max(0, Math.min(mtime, MAX_OCTAL_TIME));
            if (!pax.isEmpty()) {
                byte[] records = paxRecords(pax);
                this.out.write(header("PaxHeaders/" + asciiName(name), 'x', records.length, "",
                    0644, 0, 0, clampedTime));
                this.out.write(records);
                pad(records.length);
            }
            this.out.write(header(asciiName(name), type,
                Math.min(size, MAX_OCTAL_SIZE), asciiName(linkName), mode,
                Math.min(ownership.uid(), MAX_OCTAL_ID), Math.min(ownership.gid(), MAX_OCTAL_ID),
                clampedTime));
        }
    }

    /** Whether a name is representable in a plain ustar field (ASCII, at most 100 bytes). */
    private static boolean fitsUstar(String text) {
        if (text.length() > 100) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    /** The ustar field spelling of a name whose real value rides a PAX record. */
    private static String asciiName(String text) {
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < text.length() && safe.length() < 100; i++) {
            char c = text.charAt(i);
            safe.append(c >= 0x20 && c <= 0x7E ? c : '_');
        }
        return safe.toString();
    }

    /** PAX records: {@code "<len> <key>=<value>\n"} where len counts the whole record. */
    private static byte[] paxRecords(Map<String, String> values) {
        ByteArrayOutputStream records = new ByteArrayOutputStream();
        for (Map.Entry<String, String> value : values.entrySet()) {
            byte[] body = (" " + value.getKey() + "=" + value.getValue() + "\n")
                .getBytes(StandardCharsets.UTF_8);
            int length = body.length + 1;
            while (String.valueOf(length).length() + body.length != length) {
                length = String.valueOf(length).length() + body.length;
            }
            records.writeBytes(String.valueOf(length).getBytes(StandardCharsets.US_ASCII));
            records.writeBytes(body);
        }
        return records.toByteArray();
    }

    private static byte[] header(String name, char type, long size, String linkName, int mode,
                                 long uid, long gid, long mtime) {
        byte[] block = new byte[BLOCK];
        putString(block, 0, 100, name);
        putOctal(block, 100, 8, mode & 07777);
        putOctal(block, 108, 8, uid);
        putOctal(block, 116, 8, gid);
        putOctal(block, 124, 12, size);
        putOctal(block, 136, 12, mtime);
        block[156] = (byte) type;
        putString(block, 157, 100, linkName);
        putString(block, 257, 6, "ustar");
        block[263] = '0';
        block[264] = '0';
        for (int i = 148; i < 156; i++) {
            block[i] = ' ';
        }
        long checksum = 0;
        for (byte b : block) {
            checksum += b & 0xFF;
        }
        String digits = Long.toOctalString(checksum);
        while (digits.length() < 6) {
            digits = "0" + digits;
        }
        putString(block, 148, 6, digits);
        block[154] = 0;
        block[155] = ' ';
        return block;
    }

    private static void putString(byte[] block, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, block, offset, Math.min(bytes.length, length));
    }

    private static void putOctal(byte[] block, int offset, int length, long value) {
        String digits = Long.toOctalString(value);
        StringBuilder padded = new StringBuilder();
        for (int i = digits.length(); i < length - 1; i++) {
            padded.append('0');
        }
        padded.append(digits);
        putString(block, offset, length - 1, padded.toString());
        block[offset + length - 1] = 0;
    }

    /** The numeric owner and mode of a path, lstat-ed; falls back to root-owned 0644 bits. */
    private record Ownership(int mode, int fileType, long uid, long gid) {

        static Ownership of(Path path) throws IOException {
            try {
                Map<String, Object> unix = Files.readAttributes(path, "unix:mode,uid,gid",
                    LinkOption.NOFOLLOW_LINKS);
                int mode = ((Number) unix.get("mode")).intValue();
                return new Ownership(mode & 07777, mode & FILE_TYPE_MASK,
                    ((Number) unix.get("uid")).longValue(), ((Number) unix.get("gid")).longValue());
            } catch (UnsupportedOperationException | IllegalArgumentException noUnixView) {
                int mode = 0;
                try {
                    for (PosixFilePermission permission : Files.getPosixFilePermissions(path,
                            LinkOption.NOFOLLOW_LINKS)) {
                        mode |= 1 << (8 - permission.ordinal());
                    }
                } catch (UnsupportedOperationException noPosix) {
                    mode = 0644;
                }
                return new Ownership(mode, 0, 0, 0);
            }
        }
    }

    // -- reader ---------------------------------------------------------------

    /**
     * Parses an archive entry by entry. PAX ({@code x}) and GNU long-name ({@code L}/{@code K})
     * headers are folded into the entry they describe; a global PAX header is skipped. Every
     * header's checksum is verified, and an archive that ends without its zero-block marker
     * is a truncated archive, never a short one.
     */
    public static final class Reader {

        private final InputStream in;
        private long remaining;
        private long padding;

        public Reader(@NonNull InputStream in) {
            this.in = in;
        }

        /** @return the next entry, or null at the end-of-archive marker */
        public @Nullable Entry next() throws IOException {
            skip(this.remaining + this.padding);
            this.remaining = 0;
            this.padding = 0;
            String longName = null;
            String longLink = null;
            Long paxSize = null;
            while (true) {
                byte[] header = readBlock();
                if (header == null) {
                    throw new EOFException("Tar archive ended without its end-of-archive marker");
                }
                if (allZero(header)) {
                    return null;
                }
                verifyChecksum(header);
                char type = (char) (header[156] & 0xFF);
                long size = parseNumber(header, 124, 12);
                if (size < 0) {
                    throw new IOException("Tar entry declares a negative size");
                }
                switch (type) {
                    case 'x' -> {
                        Map<String, String> pax = parsePax(readMeta(size));
                        if (pax.containsKey("path")) {
                            longName = pax.get("path");
                        }
                        if (pax.containsKey("linkpath")) {
                            longLink = pax.get("linkpath");
                        }
                        if (pax.containsKey("size")) {
                            try {
                                paxSize = Long.parseLong(pax.get("size").trim());
                            } catch (NumberFormatException bad) {
                                throw new IOException("Tar PAX header carries a bad size");
                            }
                        }
                        continue;
                    }
                    case 'g' -> {
                        skip(size + paddingOf(size));
                        continue;
                    }
                    case 'L' -> {
                        longName = cString(readMeta(size));
                        continue;
                    }
                    case 'K' -> {
                        longLink = cString(readMeta(size));
                        continue;
                    }
                    default -> {
                        // an entry proper, handled below
                    }
                }
                if (paxSize != null) {
                    size = paxSize;
                    if (size < 0) {
                        throw new IOException("Tar entry declares a negative size");
                    }
                }
                String name = longName != null ? longName : ustarName(header);
                String link = longLink != null ? longLink : field(header, 157, 100);
                Kind kind = switch (type) {
                    case '0', '\0', '7' -> Kind.FILE;
                    case '1' -> Kind.HARDLINK;
                    case '2' -> Kind.SYMLINK;
                    case '5' -> Kind.DIRECTORY;
                    default -> Kind.OTHER;
                };
                this.remaining = size;
                this.padding = paddingOf(size);
                int mode = (int) (parseNumber(header, 100, 8) & 07777);
                return new Entry(name, kind, size, link, mode);
            }
        }

        /** The current entry's content; reading past its size answers EOF. */
        public @NonNull InputStream body() {
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    byte[] one = new byte[1];
                    int n = read(one, 0, 1);
                    return n < 0 ? -1 : one[0] & 0xFF;
                }

                @Override
                public int read(byte @NonNull [] buffer, int offset, int length)
                        throws IOException {
                    if (Reader.this.remaining <= 0) {
                        return -1;
                    }
                    int want = (int) Math.min(length, Reader.this.remaining);
                    int read = Reader.this.in.read(buffer, offset, want);
                    if (read < 0) {
                        throw new EOFException("Tar entry truncated with "
                            + Reader.this.remaining + " bytes missing");
                    }
                    Reader.this.remaining -= read;
                    return read;
                }
            };
        }

        private byte @Nullable [] readBlock() throws IOException {
            byte[] block = new byte[BLOCK];
            int filled = 0;
            while (filled < BLOCK) {
                int read = this.in.read(block, filled, BLOCK - filled);
                if (read < 0) {
                    if (filled == 0) {
                        return null;
                    }
                    throw new EOFException("Tar archive truncated inside a header block");
                }
                filled += read;
            }
            return block;
        }

        private byte[] readMeta(long size) throws IOException {
            if (size > MAX_META_BYTES) {
                throw new IOException("Tar metadata header of " + size + " bytes refused");
            }
            byte[] data = this.in.readNBytes((int) size);
            if (data.length != size) {
                throw new EOFException("Tar archive truncated inside a metadata header");
            }
            skip(paddingOf(size));
            return data;
        }

        private void skip(long count) throws IOException {
            long left = count;
            byte[] buffer = new byte[8192];
            while (left > 0) {
                int read = this.in.read(buffer, 0, (int) Math.min(buffer.length, left));
                if (read < 0) {
                    throw new EOFException("Tar archive truncated inside an entry");
                }
                left -= read;
            }
        }
    }

    private static long paddingOf(long size) {
        return (BLOCK - size % BLOCK) % BLOCK;
    }

    private static boolean allZero(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static void verifyChecksum(byte[] header) throws IOException {
        long declared = parseNumber(header, 148, 8);
        long unsigned = 0;
        long signed = 0;
        for (int i = 0; i < BLOCK; i++) {
            int value = i >= 148 && i < 156 ? ' ' : header[i];
            unsigned += value & 0xFF;
            signed += (byte) value;
        }
        if (declared != unsigned && declared != signed) {
            throw new IOException("Tar header checksum mismatch: not a tar archive, or a"
                + " corrupted one");
        }
    }

    /** An octal field, or GNU base-256 when the high bit of its first byte is set. */
    private static long parseNumber(byte[] header, int offset, int length) throws IOException {
        if ((header[offset] & 0x80) != 0) {
            if ((header[offset] & 0x40) != 0) {
                throw new IOException("Tar header carries a negative base-256 number");
            }
            long value = header[offset] & 0x3F;
            for (int i = offset + 1; i < offset + length; i++) {
                if ((value >>> 55) != 0) {
                    throw new IOException("Tar header number overflows");
                }
                value = (value << 8) | (header[i] & 0xFF);
            }
            return value;
        }
        String text = field(header, offset, length).trim();
        if (text.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(text, 8);
        } catch (NumberFormatException bad) {
            throw new IOException("Tar header carries a malformed number '" + text + "'");
        }
    }

    private static String field(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static String ustarName(byte[] header) {
        String name = field(header, 0, 100);
        boolean ustar = field(header, 257, 6).startsWith("ustar");
        String prefix = ustar ? field(header, 345, 155) : "";
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }

    private static String cString(byte[] data) {
        int end = 0;
        while (end < data.length && data[end] != 0) {
            end++;
        }
        return new String(data, 0, end, StandardCharsets.UTF_8);
    }

    private static Map<String, String> parsePax(byte[] data) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        int position = 0;
        while (position < data.length) {
            int space = position;
            while (space < data.length && data[space] != ' ') {
                space++;
            }
            int length;
            try {
                length = Integer.parseInt(new String(data, position, space - position,
                    StandardCharsets.US_ASCII));
            } catch (NumberFormatException bad) {
                throw new IOException("Tar PAX header is malformed");
            }
            int end = position + length;
            if (length <= 0 || end > data.length || space >= end || data[end - 1] != '\n') {
                throw new IOException("Tar PAX header is malformed");
            }
            String record = new String(data, space + 1, end - space - 2, StandardCharsets.UTF_8);
            int equals = record.indexOf('=');
            if (equals <= 0) {
                throw new IOException("Tar PAX header is malformed");
            }
            values.put(record.substring(0, equals), record.substring(equals + 1));
            position = end;
        }
        return values;
    }

    // -- the strict consumers -------------------------------------------------

    /**
     * The TRANSFER cap for a single-file archive whose FILE may be {@code fileCap} bytes.
     *
     * AIDEV-NOTE: a caller's cap names the file, but the wire carries the tar envelope too,
     * so capping the transfer at the file cap refused every file within about 2 KiB of it (a
     * 6-byte file under a 1 KiB cap arrives as a 2048-byte archive). The wire gets the
     * envelope on top and {@link #readSingleFile} still enforces the file cap exactly.
     */
    public static long transferCapForSingleFile(long fileCap) {
        return fileCap > Long.MAX_VALUE - SINGLE_FILE_ENVELOPE_BYTES
            ? Long.MAX_VALUE : fileCap + SINGLE_FILE_ENVELOPE_BYTES;
    }

    /**
     * Read an archive that must hold EXACTLY ONE regular file -- the Docker envelope of a
     * single-file {@code GET /archive} -- and return its bytes.
     *
     * @throws IOException naming the entry when it is a link, directory, device or anything
     *         else, when there is no entry or more than one, or when the archive is truncated
     * @throws Http11.BodyCapExceededException when the file exceeds {@code maxBytes}
     */
    public static byte @NonNull [] readSingleFile(@NonNull InputStream archive, long maxBytes)
            throws IOException {
        Reader reader = new Reader(archive);
        Entry entry = requireSingleRegular(reader, maxBytes);
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(entry.size(), 1 << 20));
        reader.body().transferTo(out);
        requireEnd(reader);
        return out.toByteArray();
    }

    /**
     * {@link #readSingleFile} streamed into {@code outFile}, which is only opened after the
     * entry proved to be a regular file and is removed again on any later failure.
     *
     * @return the file's size in bytes
     */
    public static long readSingleFile(@NonNull InputStream archive, @NonNull Path outFile,
                                      long maxBytes) throws IOException {
        Reader reader = new Reader(archive);
        Entry entry = requireSingleRegular(reader, maxBytes);
        try {
            try (OutputStream out = Files.newOutputStream(outFile, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                reader.body().transferTo(out);
            }
            requireEnd(reader);
            return entry.size();
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(outFile);
            throw failure;
        }
    }

    private static Entry requireSingleRegular(Reader reader, long maxBytes) throws IOException {
        Entry entry = reader.next();
        if (entry == null) {
            throw new IOException("The archive contained no file");
        }
        if (entry.kind() != Kind.FILE) {
            throw new IOException("Refusing archive entry '" + entry.name() + "': it is a "
                + describe(entry.kind()) + ", not a regular file");
        }
        if (entry.size() > maxBytes) {
            throw new Http11.BodyCapExceededException("Archived file '" + entry.name() + "' ("
                + entry.size() + " bytes) exceeds the " + maxBytes + "-byte cap");
        }
        return entry;
    }

    /** The operator-facing word for an entry kind. */
    private static @NonNull String describe(@NonNull Kind kind) {
        return switch (kind) {
            case FILE -> "regular file";
            case DIRECTORY -> "directory";
            case SYMLINK -> "symlink";
            case HARDLINK -> "hardlink";
            case OTHER -> "device, FIFO or other special entry";
        };
    }

    private static void requireEnd(Reader reader) throws IOException {
        Entry extra = reader.next();
        if (extra != null) {
            throw new IOException("Refusing an archive that holds more than one entry ('"
                + extra.name() + "')");
        }
    }

    /**
     * Extract an archive of REGULAR FILES AND DIRECTORIES under {@code root}, and nothing
     * else.
     *
     * AIDEV-NOTE: every component on the way to an entry is LSTAT-ed and must be a real
     * directory; a pre-existing symlink anywhere under {@code root} (a tenant checkout can
     * carry one) is a refusal, never a hop. Files are created with CREATE_NEW and
     * NOFOLLOW_LINKS, so nothing is overwritten and no link is ever written through.
     *
     * @throws IOException naming the first link, device, absolute or {@code ..} entry
     */
    public static void extractTo(@NonNull InputStream archive, @NonNull Path root)
            throws IOException {
        Reader reader = new Reader(archive);
        Entry entry;
        while ((entry = reader.next()) != null) {
            List<String> segments = segmentsOf(entry.name());
            if (segments.isEmpty()) {
                continue;   // the "./" root entry itself
            }
            if (entry.kind() != Kind.FILE && entry.kind() != Kind.DIRECTORY) {
                throw new IOException("Refusing archive entry '" + entry.name() + "': it is a "
                    + describe(entry.kind()) + ", and only regular files and directories are"
                    + " extracted");
            }
            Path parent = requireDirectories(root, segments.subList(0, segments.size() - 1));
            Path target = parent.resolve(segments.get(segments.size() - 1));
            if (entry.kind() == Kind.DIRECTORY) {
                requireDirectories(root, segments);
                continue;
            }
            try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                reader.body().transferTo(out);
            }
            applyMode(target, entry.mode());
        }
    }

    /** Validated relative segments; "." segments dropped, anything escaping refused. */
    private static List<String> segmentsOf(String name) throws IOException {
        if (name.startsWith("/")) {
            throw new IOException("Refusing absolute archive entry '" + name + "'");
        }
        List<String> segments = new ArrayList<>();
        for (String segment : name.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment) || segment.indexOf('\0') >= 0) {
                throw new IOException("Refusing archive entry '" + name + "': it escapes the"
                    + " extraction root");
            }
            segments.add(segment);
        }
        return segments;
    }

    /** Walk (creating as needed) a chain of REAL directories under root; never a link. */
    private static Path requireDirectories(Path root, List<String> segments) throws IOException {
        Path current = root;
        for (String segment : segments) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Refusing to extract through '" + current
                        + "': it exists and is not a real directory");
                }
            } else {
                Files.createDirectory(current);
            }
        }
        return current;
    }

    private static void applyMode(Path file, int mode) throws IOException {
        Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
        PosixFilePermission[] order = PosixFilePermission.values();
        for (int bit = 0; bit < 9; bit++) {
            if ((mode & (1 << (8 - bit))) != 0) {
                permissions.add(order[bit]);
            }
        }
        try {
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException notPosix) {
            // the file keeps the default mode on a non-POSIX filesystem
        }
    }
}
