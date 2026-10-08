package be.elevenways.hohenheim.server.instance;

import be.elevenways.pepperglass.agent.ClipboardReply;
import be.elevenways.pepperglass.agent.ClipboardType;
import be.elevenways.pepperglass.agent.Selection;
import be.elevenways.pepperglass.fixture.ScriptedSpiceServer;
import be.elevenways.pepperglass.session.AudioEncoding;
import be.elevenways.pepperglass.session.AudioFormat;
import be.elevenways.pepperglass.session.AudioPacket;
import be.elevenways.pepperglass.session.CursorShape;
import be.elevenways.pepperglass.session.DisplaySurface;
import be.elevenways.pepperglass.session.SessionOptions;
import be.elevenways.pepperglass.session.StreamFrame;
import be.elevenways.pepperglass.session.VideoStream;
import be.elevenways.pepperglass.surface.ClipSpans;
import be.elevenways.pepperglass.surface.Surface;
import be.elevenways.pepperglass.surface.SurfaceFormat;
import be.elevenways.pepperglass.wire.ChannelType;
import be.elevenways.pepperglass.wire.Clip;
import be.elevenways.pepperglass.wire.MainMessages;
import be.elevenways.pepperglass.wire.Rect;
import be.elevenways.pepperglass.wire.WireWriter;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.routing.EndpointRoute;
import be.elevenways.zenit.common.routing.WebSocketEndpoint;
import be.elevenways.zenit.common.security.PrincipalRef;
import be.elevenways.zenit.common.websocket.WebSocketHandler;
import be.elevenways.zenit.kvm.common.AudioCodec;
import be.elevenways.zenit.kvm.common.RectEncoding;
import be.elevenways.zenit.kvm.common.ScreenCapability;
import be.elevenways.zenit.kvm.common.ScreenMessage;
import be.elevenways.zenit.kvm.common.ScreenProtocol;
import be.elevenways.zenit.kvm.common.ScreenStatus;
import be.elevenways.zenit.kvm.server.ScreenAccess;
import be.elevenways.zenit.kvm.server.ScreenOptions;
import be.elevenways.zenit.kvm.server.ScreenSession;
import be.elevenways.zenit.kvm.test.support.RecordingScreenSocket;
import be.elevenways.zenit.kvm.test.support.ScreenAccessTable;
import be.elevenways.zenit.kvm.test.support.ScreenMessageLog;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static be.elevenways.pepperglass.session.VideoCodec.MJPEG;
import static be.elevenways.pepperglass.session.VideoCodec.VP8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SPICE screen source between a Pepperglass session and a zenit-kvm session, its Pepperglass side driven event by
 * event: it offers resizing and no WebCodecs video; the primary surface, MJPEG frames passed through or drawn, the
 * cursor, playback, the guest's clipboard and microphone reach the viewer while a VP8 frame is dropped; a paste
 * reaches the guest, a USB device without a way in is let go, and the SPICE session's end ends the screen.
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
class SpiceScreenSourceTest {

    private static final PrincipalRef ANN = PrincipalRef.account(61);
    private static final Duration WAIT = Duration.ofSeconds(10);

    private static final ScreenAccessTable ACCESS = new ScreenAccessTable();
    private static volatile ScreenSession current;

    private static final WebSocketEndpoint ENDPOINT = WebSocketEndpoint.builder()
        .identifier(Identifier.of("hohenheim_test", "spice_screen_source"))
        .addRoute(EndpointRoute.builder().addStatic("ws").addDelimiter().addStatic("spice-screen-test").build())
        .publiclyAccessible()
        .revalidateEvery(60_000)
        .handler(socket -> new WebSocketHandler() {
        })
        .build();

    static {
        ACCESS.serve(ENDPOINT, socket -> "vm", subject -> current);
    }

    @Test
    void theVmsScreenMediaAndClipboardReachTheViewerAndItsPastesAndDevicesGoBack() throws Exception {
        try (ScriptedSpiceServer spice = ScriptedSpiceServer.builder()
                .channel(ChannelType.MAIN, channel -> {
                    channel.send(MainMessages.INIT, ScriptedSpiceServer.mainInit(3));
                    channel.expect(MainMessages.CLIENT_ATTACH_CHANNELS, WAIT, new ArrayList<>());
                    channel.send(MainMessages.CHANNELS_LIST, new WireWriter().u32(0).toByteArray());
                    channel.drainUntilClosed();
                })
                .start()) {
            SpiceScreenSource source = new SpiceScreenSource(() -> VmSpice.Link.direct(SessionOptions.builder(
                "127.0.0.1", spice.port())));
            current = ScreenSession.open(source, ScreenOptions.DEFAULTS);
            ACCESS.grant(ANN, ScreenAccess.CONTROL);
            RecordingScreenSocket viewer = RecordingScreenSocket.as(ScreenAccessTable.principal(ANN)).autoAck(true)
                .open(ENDPOINT);
            viewer.send(new ScreenMessage.ViewerHello(ScreenProtocol.VERSION,
                ScreenCapability.maskOf(ScreenCapability.values())));
            int offered = viewer.await(ScreenMessage.Hello.class, hello -> true).capabilities();
            assertThat(ScreenCapability.RESIZE.in(offered)).as("the guest takes the controller's size").isTrue();
            assertThat(ScreenCapability.VIDEO_H264.in(offered) || ScreenCapability.VIDEO_VP8.in(offered)
                || ScreenCapability.VIDEO_VP9.in(offered)).as("and no WebCodecs video is offered").isFalse();

            // 1. The primary surface becomes the screen's surface.
            DisplaySurface primary = new DisplaySurface(0, true,
                new Surface(0, 32, 16, SurfaceFormat.XRGB_8888, new ClipSpans()));
            source.surfaceCreated(primary);
            source.updatesFlushed(0);
            viewer.await(ScreenMessage.Surface.class, surface -> surface.width() == 32 && surface.height() == 16);

            // 2. An MJPEG frame filling its whole destination passes through as the JPEG it is.
            byte[] red = jpeg(8, 8, 0xFF0000);
            VideoStream whole = new VideoStream(0, 0, 1, MJPEG, 8, 8,
                new Rect(0, 0, 8, 8), Clip.NONE, true);
            source.streamFrameReceived(new StreamFrame(whole, 5, red));
            source.updatesFlushed(0);
            viewer.await(ScreenMessage.Rect.class, rect -> rect.encoding() == RectEncoding.JPEG && rect.x() == 0
                && rect.width() == 8);

            // 3. A scaled frame, partly covered, is drawn only where the stream shows.
            VideoStream covered = new VideoStream(0, 0, 2, MJPEG, 4, 4,
                new Rect(16, 0, 24, 8), Clip.of(List.of(new Rect(16, 0, 20, 8))), true);
            source.streamFrameReceived(new StreamFrame(covered, 6, jpeg(4, 4, 0x0000FF)));
            source.updatesFlushed(0);
            assertThat(ScreenMessageLog.waitUntil(() -> blue(viewer.picture().pixel(17, 2)), WAIT))
                .as("step 3: the visible part shows the frame, scaled up").isTrue();
            assertThat(viewer.picture().pixel(22, 2)).as("step 3: the covered part keeps the surface").isZero();

            // 4. Only MJPEG is announced to the SPICE server, so a VP8 frame cannot come; one that does is dropped
            //    instead of reaching a viewer that may have no decoder for it.
            VideoStream vp8 = new VideoStream(0, 0, 3, VP8, 32, 32,
                new Rect(0, 8, 16, 16), Clip.NONE, true);
            source.streamFrameReceived(new StreamFrame(vp8, 8, new byte[] {0x10, 0x02, 0x00}));
            source.streamDestroyed(vp8);

            // 5. The cursor's shape goes as straight RGBA, its position as it moves.
            source.cursorShapeChanged(new CursorShape(1, 1, 0, 0, new int[] {0x8011FF22}, null));
            ScreenMessage.CursorShape shape = viewer.await(ScreenMessage.CursorShape.class, cursor -> true);
            assertThat(viewer.received(ScreenMessage.VideoStart.class)).as("step 4: no video reached the viewer")
                .isEmpty();
            assertThat(shape.rgba()).as("step 5: ARGB became RGBA")
                .isEqualTo(new byte[] {0x11, (byte) 0xFF, 0x22, (byte) 0x80});
            source.cursorMoved(5, 6);
            viewer.await(ScreenMessage.CursorPosition.class, position -> position.x() == 5 && position.y() == 6);

            // 6. Playback starts, plays and stops as one audio stream.
            source.playbackStarted(new AudioFormat(2, 48000), AudioEncoding.OPUS);
            source.playbackPacketReceived(new AudioPacket(20, AudioEncoding.OPUS, new byte[] {(byte) 0xF8}));
            source.playbackStopped();
            ScreenMessage.AudioStart audio = viewer.await(ScreenMessage.AudioStart.class, stream -> true);
            assertThat(audio.codec()).as("step 6: Opus").isEqualTo(AudioCodec.OPUS);
            assertThat(audio.sampleRate()).as("step 6: at 48 kHz").isEqualTo(48000);
            viewer.await(ScreenMessage.AudioChunk.class, chunk -> chunk.timestampMicros() == 20_000);
            viewer.await(ScreenMessage.AudioStop.class, stop -> true);

            // 7. The guest's clipboard and its microphone request reach the controller.
            source.clipboardData(Selection.CLIPBOARD, ClipboardType.UTF8_TEXT,
                "from the guest".getBytes(StandardCharsets.UTF_8));
            ScreenMessage.Clipboard copied = viewer.await(ScreenMessage.Clipboard.class, clipboard -> true);
            assertThat(new String(copied.data(), StandardCharsets.UTF_8)).as("step 7: the guest's text")
                .isEqualTo("from the guest");
            source.recordingStarted(new AudioFormat(1, 44100), AudioEncoding.RAW);
            ScreenMessage.MicRequest mic = viewer.await(ScreenMessage.MicRequest.class, ScreenMessage.MicRequest::on);
            assertThat(mic.codec()).as("step 7: raw PCM").isEqualTo(AudioCodec.PCM_S16LE);
            assertThat(mic.sampleRate()).as("step 7: at the guest's rate").isEqualTo(44100);

            // 8. Once the SPICE session is up, the controller's paste is what the guest receives when it asks.
            assertThat(ScreenMessageLog.waitUntil(() -> {
                viewer.send(new ScreenMessage.Clipboard("text/plain;charset=utf-8",
                    "to the guest".getBytes(StandardCharsets.UTF_8)));
                return source.clipboardRequested(Selection.CLIPBOARD, ClipboardType.UTF8_TEXT)
                    instanceof ClipboardReply.Data;
            }, WAIT)).as("step 8: the paste is offered to the guest").isTrue();
            ClipboardReply.Data pasted = (ClipboardReply.Data) source.clipboardRequested(Selection.CLIPBOARD,
                ClipboardType.UTF8_TEXT);
            assertThat(new String(pasted.data(), StandardCharsets.UTF_8)).as("step 8: its text")
                .isEqualTo("to the guest");
            assertThat(source.clipboardRequested(Selection.CLIPBOARD, ClipboardType.IMAGE_PNG))
                .as("step 8: nothing in another type").isEqualTo(ClipboardReply.NONE);

            // 9. A USB device the VM has no redirection channel for is let go again.
            byte[] device = {18, 1, 0, 2, 0, 0, 0, 64, 0x34, 0x12, 0x78, 0x56, 0, 1, 0, 0, 0, 1};
            byte[] configuration = {9, 2, 9, 0, 0, 1, 0, (byte) 0x80, 50};
            viewer.send(new ScreenMessage.UsbAttach(7, 0x1234, 0x5678, device, configuration, "", "", ""));
            viewer.await(ScreenMessage.UsbRelease.class, release -> release.device() == 7);

            // 10. The SPICE session ending ends the screen.
            source.disconnected(null);
            viewer.await(ScreenMessage.Status.class, status -> status.status() == ScreenStatus.ENDED);
            assertThat(current.isEnded()).as("step 10: the screen ended").isTrue();
        } finally {
            if (current != null) {
                current.close();
            }
            ACCESS.clear();
        }
    }

    private static boolean blue(int rgb) {
        return (rgb >> 16 & 0xFF) < 40 && (rgb >> 8 & 0xFF) < 40 && (rgb & 0xFF) > 200;
    }

    private static byte[] jpeg(int width, int height, int rgb) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, rgb);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", out);
        return out.toByteArray();
    }
}
