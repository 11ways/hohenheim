package be.elevenways.hohenheim.server.instance;

import be.elevenways.pepperglass.Pepperglass;
import be.elevenways.pepperglass.agent.ClipboardReply;
import be.elevenways.pepperglass.agent.ClipboardType;
import be.elevenways.pepperglass.agent.FileTransfer;
import be.elevenways.pepperglass.agent.MonitorConfig;
import be.elevenways.pepperglass.agent.Selection;
import be.elevenways.pepperglass.channel.ChannelSettings;
import be.elevenways.pepperglass.session.AudioEncoding;
import be.elevenways.pepperglass.session.AudioFormat;
import be.elevenways.pepperglass.session.AudioPacket;
import be.elevenways.pepperglass.session.CursorShape;
import be.elevenways.pepperglass.session.DisplaySurface;
import be.elevenways.pepperglass.session.MouseButton;
import be.elevenways.pepperglass.session.Session;
import be.elevenways.pepperglass.session.SessionListener;
import be.elevenways.pepperglass.session.StreamFrame;
import be.elevenways.pepperglass.session.VideoCodec;
import be.elevenways.pepperglass.session.VideoStream;
import be.elevenways.pepperglass.wire.Rect;
import be.elevenways.pepperglass.wire.SpiceException;
import be.elevenways.protoblast.common.input.KeyCode;
import be.elevenways.protoblast.server.io.BulkInputStream;
import be.elevenways.zenit.kvm.common.AudioCodec;
import be.elevenways.zenit.kvm.common.RectEncoding;
import be.elevenways.zenit.kvm.common.ScreenCapability;
import be.elevenways.zenit.kvm.common.ScreenCursor;
import be.elevenways.zenit.kvm.common.ScreenProtocol;
import be.elevenways.zenit.kvm.common.UsbStatus;
import be.elevenways.zenit.kvm.server.ScreenFileTarget;
import be.elevenways.zenit.kvm.server.ScreenRefusalReason;
import be.elevenways.zenit.kvm.server.ScreenSink;
import be.elevenways.zenit.kvm.server.ScreenSource;
import be.elevenways.zenit.kvm.server.ScreenUsbDevice;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A SPICE server's screen as a zenit-kvm screen source, through a Pepperglass session: the primary surface's changes
 * go into the sink as damage, each applied batch ending a frame; MJPEG stream frames pass through as JPEG images,
 * playback as audio, with the cursor, the guest's clipboard and its microphone requests; the controller's keys,
 * pointer, wheel, size, pastes, files and USB devices go back.
 *
 * AIDEV-NOTE: display, stream and cursor callbacks arrive on Pepperglass reader threads, one per channel, so the
 * display state below is the primary display channel's own; input arrives on the session's input lane. An MJPEG frame
 * that is scaled, mirrored or partly covered cannot pass through as it is, so it is decoded and drawn as damage.
 * Only MJPEG is announced to the SPICE server: the codecs are fixed when the session links, before any viewer said
 * what it decodes, and one stream feeds every viewer, so a WebCodecs stream would leave a viewer without that decoder
 * looking at a hole, while every viewer draws a JPEG.
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
final class SpiceScreenSource implements ScreenSource, SessionListener {

    private static final Logger LOG = Logger.getLogger(SpiceScreenSource.class.getName());

    /** The browser's usual wheel step in surface pixels, one SPICE wheel notch. */
    private static final int WHEEL_NOTCH = 100;

    /** Uploaded bytes buffered for the guest agent before the controller's next chunk waits. */
    private static final int UPLOAD_CHUNKS = 8;
    private static final long UPLOAD_WAIT_SECONDS = 30;

    /** The SPICE server is asked for MJPEG streams only. */
    private static final ChannelSettings SETTINGS = new ChannelSettings(ChannelSettings.DEFAULTS.imageCacheBytes(),
        ChannelSettings.DEFAULTS.glzDictionaryBytes(), ChannelSettings.DEFAULTS.glzWindowPixels(),
        ChannelSettings.DEFAULTS.preferredCompression(), List.of(VideoCodec.MJPEG),
        ChannelSettings.DEFAULTS.recordEncoding(), ChannelSettings.DEFAULTS.crossChannelWaitMs());

    private final @NonNull Callable<VmSpice.Link> connection;
    private volatile @Nullable ScreenSink sink;
    private volatile @Nullable Session session;
    private VmSpice.@Nullable Link link;
    private boolean closed;

    // The primary display channel's own.
    private volatile @Nullable DisplaySurface primary;
    private int[] scratch = new int[0];
    private boolean drawn;

    // The playback channel's own.
    private boolean playing;
    private int volume = 0xFFFF;
    private boolean muted;

    // The record channel's own.
    private AudioCodec micCodec = AudioCodec.PCM_S16LE;
    private int micRate = 48000;
    private int micChannels = 2;

    // The input lane's own.
    private int buttons;
    private int wheelRemainder;
    private final Map<Integer, SpiceUsbDevice> devices = new ConcurrentHashMap<>();
    private volatile @Nullable Pasted pasted;

    /** The controller's last paste, offered to the guest until it asks for it. */
    private record Pasted(@NonNull ClipboardType type, byte @NonNull [] data) {
    }

    /**
     * @param connection how the session reaches the server; called once, on the session's start thread
     */
    SpiceScreenSource(@NonNull Callable<VmSpice.Link> connection) {
        this.connection = connection;
    }

    @Override
    public int capabilities() {
        return ScreenCapability.maskOf(ScreenCapability.AUDIO_OPUS, ScreenCapability.AUDIO_PCM,
            ScreenCapability.CLIPBOARD, ScreenCapability.FILE, ScreenCapability.MICROPHONE, ScreenCapability.RESIZE,
            ScreenCapability.USB);
    }

    @Override
    public void start(@NonNull ScreenSink sink) throws Exception {
        this.sink = sink;
        VmSpice.Link reached = this.connection.call();
        boolean late;
        synchronized (this) {
            late = this.closed;
            if (!late) {
                this.link = reached;
            }
        }
        if (late) {
            reached.release().run();
            return;
        }
        Session opened = Pepperglass.connect(reached.options().settings(SETTINGS).build(), this);
        synchronized (this) {
            if (!this.closed) {
                this.session = opened;
                return;
            }
        }
        opened.close();
    }

    // --- The display ---

    @Override
    public void surfaceCreated(@NonNull DisplaySurface surface) {
        if (surface.primary() && (this.primary == null || this.primary.channel() == surface.channel())) {
            this.primary = surface;
            this.sink().surface(surface.width(), surface.height());
            this.drawn = true;
        }
    }

    @Override
    public void surfaceDestroyed(@NonNull DisplaySurface surface) {
        if (this.primary == surface) {
            this.primary = null;
        }
    }

    @Override
    public void regionChanged(@NonNull DisplaySurface surface, @NonNull Rect region) {
        if (this.primary != surface || region.isEmpty()) {
            return;
        }
        int width = region.width();
        int[] pixels = this.pixels(width * region.height());
        surface.read(region, pixels, 0, width);
        opaque(pixels, width * region.height());
        this.sink().damage(region.left(), region.top(), width, region.height(), pixels, 0, width);
        this.drawn = true;
    }

    @Override
    public void updatesFlushed(int channel) {
        DisplaySurface shown = this.primary;
        if (this.drawn && shown != null && shown.channel() == channel) {
            this.drawn = false;
            this.sink().frameEnd();
        }
    }

    @Override
    public void displayReset(int channel) {
        DisplaySurface shown = this.primary;
        if (shown != null && shown.channel() == channel) {
            this.primary = null;
        }
    }

    // --- Streams ---

    @Override
    public void streamFrameReceived(@NonNull StreamFrame frame) {
        VideoStream stream = frame.stream();
        DisplaySurface shown = this.primary;
        if (shown == null || shown.channel() != stream.channel() || shown.id() != stream.surfaceId()) {
            return;
        }
        Rect dest = stream.destination();
        if (dest.isEmpty() || !dest.equals(dest.intersect(shown.bounds()))) {
            return;
        }
        switch (stream.codec()) {
            case MJPEG -> this.jpeg(shown, stream, frame.data());
            // Never announced, so never sent.
            case VP8, VP9, H264, H265 -> {
            }
        }
    }

    /** Draws an MJPEG frame: passed through when it fills its whole destination as it is, else decoded. */
    private void jpeg(@NonNull DisplaySurface shown, @NonNull VideoStream stream, byte @NonNull [] data) {
        Rect dest = stream.destination();
        boolean whole = stream.clip().all() && stream.topDown() && stream.frameWidth() == dest.width()
            && stream.frameHeight() == dest.height();
        if (whole) {
            this.sink().image(dest.left(), dest.top(), dest.width(), dest.height(), RectEncoding.JPEG, data);
            this.drawn = true;
            return;
        }
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(data));
        } catch (IOException unreadable) {
            LOG.log(Level.FINE, "An MJPEG stream frame could not be read", unreadable);
            return;
        }
        if (image == null) {
            return;
        }
        int width = dest.width();
        int height = dest.height();
        int[] pixels = this.pixels(width * height);
        shown.read(dest, pixels, 0, width);
        for (int row = 0; row < height; row++) {
            int y = dest.top() + row;
            int fromY = (int) ((long) row * image.getHeight() / height);
            if (!stream.topDown()) {
                fromY = image.getHeight() - 1 - fromY;
            }
            for (int column = 0; column < width; column++) {
                int x = dest.left() + column;
                if (stream.clip().all() || stream.clip().admits(x, y)) {
                    int fromX = (int) ((long) column * image.getWidth() / width);
                    pixels[row * width + column] = image.getRGB(fromX, fromY);
                }
            }
        }
        opaque(pixels, width * height);
        this.sink().damage(dest.left(), dest.top(), width, height, pixels, 0, width);
        this.drawn = true;
    }

    // --- The cursor ---

    @Override
    public void cursorShapeChanged(@NonNull CursorShape shape) {
        if (shape.width() < 1 || shape.height() < 1 || shape.width() > ScreenProtocol.MAX_CURSOR_DIMENSION
                || shape.height() > ScreenProtocol.MAX_CURSOR_DIMENSION) {
            this.sink().cursorName(ScreenCursor.DEFAULT);
            return;
        }
        int[] argb = shape.pixels();
        byte[] rgba = new byte[shape.width() * shape.height() * 4];
        for (int at = 0; at < shape.width() * shape.height(); at++) {
            int pixel = argb[at];
            rgba[at * 4] = (byte) (pixel >> 16);
            rgba[at * 4 + 1] = (byte) (pixel >> 8);
            rgba[at * 4 + 2] = (byte) pixel;
            rgba[at * 4 + 3] = (byte) (pixel >>> 24);
        }
        this.sink().cursorShape(shape.width(), shape.height(), Math.clamp(shape.hotX(), 0, shape.width() - 1),
            Math.clamp(shape.hotY(), 0, shape.height() - 1), rgba);
    }

    @Override
    public void cursorMoved(int x, int y) {
        this.sink().cursorPosition(Math.clamp(x, 0, ScreenProtocol.MAX_DIMENSION - 1),
            Math.clamp(y, 0, ScreenProtocol.MAX_DIMENSION - 1));
    }

    @Override
    public void cursorVisibilityChanged(boolean visible) {
        this.sink().cursorVisible(visible);
    }

    // --- Audio ---

    @Override
    public void playbackStarted(@NonNull AudioFormat format, @NonNull AudioEncoding encoding) {
        if (this.playing) {
            this.sink().audioStop(0);
        }
        this.sink().audioStart(0, codec(encoding), format.frequency(), format.channels());
        this.sink().audioVolume(0, this.volume, this.muted);
        this.playing = true;
    }

    @Override
    public void playbackPacketReceived(@NonNull AudioPacket packet) {
        if (this.playing) {
            this.sink().audioChunk(0, packet.mmTime() * 1000, packet.data());
        }
    }

    @Override
    public void playbackStopped() {
        if (this.playing) {
            this.playing = false;
            this.sink().audioStop(0);
        }
    }

    @Override
    public void playbackVolumeChanged(int @NonNull [] volumes) {
        int loudest = 0;
        for (int channel : volumes) {
            loudest = Math.max(loudest, channel & 0xFFFF);
        }
        this.volume = loudest;
        if (this.playing) {
            this.sink().audioVolume(0, this.volume, this.muted);
        }
    }

    @Override
    public void playbackMuteChanged(boolean muted) {
        this.muted = muted;
        if (this.playing) {
            this.sink().audioVolume(0, this.volume, this.muted);
        }
    }

    @Override
    public void recordingStarted(@NonNull AudioFormat format, @NonNull AudioEncoding encoding) {
        this.micCodec = codec(encoding);
        this.micRate = format.frequency();
        this.micChannels = format.channels();
        this.sink().microphone(true, this.micCodec, this.micRate, this.micChannels);
    }

    @Override
    public void recordingStopped() {
        this.sink().microphone(false, this.micCodec, this.micRate, this.micChannels);
    }

    @Override
    public void microphoneChunk(long timestampMicros, byte @NonNull [] data) {
        Session current = this.session;
        if (current != null) {
            current.sendRecordedAudio(data);
        }
    }

    // --- The clipboard ---

    @Override
    public void clipboardGrabbed(@NonNull Selection selection, @NonNull List<ClipboardType> types) {
        Session current = this.session;
        if (current == null || selection != Selection.CLIPBOARD) {
            return;
        }
        for (ClipboardType wanted : List.of(ClipboardType.UTF8_TEXT, ClipboardType.IMAGE_PNG)) {
            if (types.contains(wanted)) {
                current.requestClipboard(selection, wanted);
                return;
            }
        }
    }

    @Override
    public void clipboardData(@NonNull Selection selection, @NonNull ClipboardType type, byte @NonNull [] data) {
        if (selection == Selection.CLIPBOARD && type != ClipboardType.NONE
                && data.length <= ScreenProtocol.MAX_CLIPBOARD_BYTES) {
            this.sink().clipboard(type.mimeType(), data);
        }
    }

    @Override
    public @NonNull ClipboardReply clipboardRequested(@NonNull Selection selection, @NonNull ClipboardType type) {
        Pasted offered = this.pasted;
        return offered != null && selection == Selection.CLIPBOARD && offered.type() == type
            ? ClipboardReply.of(offered.data()) : ClipboardReply.NONE;
    }

    /** A paste is offered to the guest, which asks for its bytes when its own paste happens. */
    @Override
    public void clipboard(@NonNull String mime, byte @NonNull [] data) {
        Session current = this.session;
        ClipboardType type = clipboardType(mime);
        if (current == null || type == null) {
            return;
        }
        this.pasted = new Pasted(type, data);
        current.grabClipboard(Selection.CLIPBOARD, List.of(type));
    }

    // --- Input ---

    @Override
    public void key(@NonNull KeyCode code, @NonNull String key, boolean down) {
        Session current = this.session;
        if (current != null) {
            current.key(code, down);
        }
    }

    @Override
    public void pointer(int x, int y, int buttons) {
        Session current = this.session;
        if (current == null) {
            return;
        }
        current.pointerTo(x, y, 0);
        int changed = buttons ^ this.buttons;
        this.buttons = buttons;
        for (DomButton button : DomButton.values()) {
            if ((changed & button.bit) == 0) {
                continue;
            }
            if ((buttons & button.bit) != 0) {
                current.press(button.spice);
            } else {
                current.release(button.spice);
            }
        }
    }

    @Override
    public void wheel(int x, int y, int deltaX, int deltaY) {
        Session current = this.session;
        if (current == null) {
            return;
        }
        this.wheelRemainder += deltaY;
        int notches = Math.clamp(this.wheelRemainder / WHEEL_NOTCH, -16, 16);
        if (notches != 0) {
            this.wheelRemainder -= notches * WHEEL_NOTCH;
            current.pointerTo(x, y, 0);
            current.wheel(notches);
        }
    }

    /** The guest agent lays its one monitor out at the size; without an agent the size waits for one. */
    @Override
    public void resize(int width, int height) {
        Session current = this.session;
        if (current != null) {
            current.setMonitors(List.of(MonitorConfig.of(width, height)), false);
        }
    }

    /** A dropped file goes to the guest agent, which must be running to take it. */
    @Override
    public @NonNull ScreenFileTarget file(@NonNull String name, @NonNull String mime, long size) {
        Session current = this.session;
        if (current == null || !current.agentConnected()) {
            throw ScreenRefusalReason.UNSUPPORTED.refusal("No guest agent runs to take files");
        }
        Upload upload = new Upload();
        FileTransfer transfer;
        try {
            transfer = current.sendFile(name, size, upload);
        } catch (SpiceException refused) {
            throw ScreenRefusalReason.UNSUPPORTED.refusal("The guest agent takes no file now: " + refused.getMessage());
        }
        return new ScreenFileTarget() {
            @Override
            public void write(byte @NonNull [] data) throws Exception {
                upload.add(data);
            }

            @Override
            public void complete() throws Exception {
                upload.add(Upload.END);
            }

            @Override
            public void cancel() {
                transfer.cancel();
                upload.abort();
            }
        };
    }

    // --- USB ---

    @Override
    public void usbAttach(@NonNull ScreenUsbDevice device) {
        Session current = this.session;
        ScreenSink target = this.sink;
        if (current == null || target == null) {
            return;
        }
        SpiceUsbDevice bridge = new SpiceUsbDevice(device, target);
        this.devices.put(device.device(), bridge);
        try {
            current.attachUsb(bridge);
        } catch (SpiceException refused) {
            LOG.log(Level.FINE, "A shared USB device found no way into the guest", refused);
            this.devices.remove(device.device());
            bridge.close();
        }
    }

    @Override
    public void usbDetach(int device) {
        SpiceUsbDevice bridge = this.devices.remove(device);
        Session current = this.session;
        if (bridge != null) {
            bridge.gone();
            if (current != null) {
                current.detachUsb(bridge);
            }
        }
    }

    @Override
    public void usbResult(int device, int transfer, @NonNull UsbStatus status, int actualLength,
                          byte @NonNull [] data) {
        SpiceUsbDevice bridge = this.devices.get(device);
        if (bridge == null) {
            return;
        }
        switch (status) {
            case OK -> bridge.completed(transfer, actualLength, data);
            case STALL -> bridge.stalled(transfer);
            case BABBLE -> bridge.babbled(transfer);
            case DISCONNECTED, ERROR -> bridge.failed(transfer);
        }
    }

    // --- The session ---

    @Override
    public void disconnected(@Nullable Throwable cause) {
        LOG.log(cause == null ? Level.FINE : Level.INFO, "A VM's SPICE session ended", cause);
        ScreenSink target = this.sink;
        if (target != null) {
            target.end();
        }
    }

    /** Closes the SPICE session, then releases the console the host opened for it. */
    @Override
    public void close() {
        Session current;
        VmSpice.Link reached;
        synchronized (this) {
            if (this.closed) {
                return;
            }
            this.closed = true;
            current = this.session;
            reached = this.link;
        }
        try {
            if (current != null) {
                current.close();
            }
        } finally {
            if (reached != null) {
                reached.release().run();
            }
        }
    }

    // --- Helpers ---

    private @NonNull ScreenSink sink() {
        ScreenSink current = this.sink;
        if (current == null) {
            throw new IllegalStateException("The SPICE session spoke before its screen session started it");
        }
        return current;
    }

    private int @NonNull [] pixels(int count) {
        if (this.scratch.length < count) {
            this.scratch = new int[count];
        }
        return this.scratch;
    }

    /** Drops the alpha byte: the sink takes 0xRRGGBB. */
    private static void opaque(int @NonNull [] pixels, int count) {
        for (int at = 0; at < count; at++) {
            pixels[at] &= 0xFFFFFF;
        }
    }

    private static @NonNull AudioCodec codec(@NonNull AudioEncoding encoding) {
        return switch (encoding) {
            case RAW -> AudioCodec.PCM_S16LE;
            case OPUS -> AudioCodec.OPUS;
        };
    }

    private static @Nullable ClipboardType clipboardType(@NonNull String mime) {
        String base = baseMime(mime);
        for (ClipboardType type : ClipboardType.values()) {
            if (type != ClipboardType.NONE && baseMime(type.mimeType()).equals(base)) {
                return type;
            }
        }
        return null;
    }

    private static @NonNull String baseMime(@NonNull String mime) {
        int parameters = mime.indexOf(';');
        return (parameters < 0 ? mime : mime.substring(0, parameters)).trim().toLowerCase(Locale.ROOT);
    }

    /** The DOM pointer buttons mask bits and the SPICE buttons they press. */
    private enum DomButton {
        LEFT(1, MouseButton.LEFT),
        RIGHT(2, MouseButton.RIGHT),
        MIDDLE(4, MouseButton.MIDDLE),
        BACK(8, MouseButton.SIDE),
        FORWARD(16, MouseButton.EXTRA);

        private final int bit;
        private final @NonNull MouseButton spice;

        DomButton(int bit, @NonNull MouseButton spice) {
            this.bit = bit;
            this.spice = spice;
        }
    }

    /**
     * An upload's bytes as the stream the guest agent's transfer reads, a few chunks ahead of it.
     *
     * AIDEV-NOTE: a full buffer makes the controller's next chunk wait, which holds back its acknowledgement and so
     * the browser; a guest that stops reading fails the upload instead of stalling input for good.
     */
    private static final class Upload extends BulkInputStream {

        static final byte[] END = new byte[0];

        private final LinkedBlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>(UPLOAD_CHUNKS);
        private byte @Nullable [] current;
        private int offset;
        private volatile boolean aborted;

        void add(byte @NonNull [] chunk) throws IOException, InterruptedException {
            if (this.aborted || !this.chunks.offer(chunk, UPLOAD_WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("The guest agent stopped taking the upload");
            }
        }

        void abort() {
            this.aborted = true;
            this.chunks.clear();
            this.chunks.offer(END);
        }

        @Override
        public int read(byte @NonNull [] into, int at, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            while (this.current == null || this.offset >= this.current.length) {
                byte[] next;
                try {
                    next = this.chunks.take();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted reading an upload", interrupted);
                }
                if (next == END || this.aborted) {
                    this.chunks.offer(END);
                    if (this.aborted) {
                        throw new IOException("The upload was cancelled");
                    }
                    return -1;
                }
                this.current = next;
                this.offset = 0;
            }
            int taken = Math.min(length, this.current.length - this.offset);
            System.arraycopy(this.current, this.offset, into, at, taken);
            this.offset += taken;
            return taken;
        }

        @Override
        public void close() {
            this.aborted = true;
            this.chunks.clear();
        }
    }
}
