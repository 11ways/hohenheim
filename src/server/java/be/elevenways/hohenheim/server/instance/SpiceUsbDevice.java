package be.elevenways.hohenheim.server.instance;

import be.elevenways.pepperglass.usb.ControlSetup;
import be.elevenways.pepperglass.usb.PacketSink;
import be.elevenways.pepperglass.usb.TransferResult;
import be.elevenways.pepperglass.usb.UsbDescriptors;
import be.elevenways.pepperglass.usb.UsbDevice;
import be.elevenways.pepperglass.usb.UsbRedirMessage;
import be.elevenways.pepperglass.usb.UsbSpeed;
import be.elevenways.pepperglass.usb.UsbStatus;
import be.elevenways.pepperglass.wire.SpiceException;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.kvm.common.ScreenProtocol;
import be.elevenways.zenit.kvm.common.UsbTransferKind;
import be.elevenways.zenit.kvm.server.ScreenSink;
import be.elevenways.zenit.kvm.server.ScreenUsbDevice;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * A USB device the controller shared from its browser, redirected into the guest: every transfer the guest asks for
 * runs in the browser through the screen session, and an IN stream the guest starts is one transfer after another.
 *
 * AIDEV-NOTE: the browser opened the device with its first configuration and every interface claimed, so another
 * configuration stalls; isochronous endpoints and bulk streams are not offered.
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
final class SpiceUsbDevice implements UsbDevice {

    private static final byte[] NONE = new byte[0];

    private final @NonNull ScreenUsbDevice device;
    private final @NonNull ScreenSink sink;
    private final AtomicInteger transfers = new AtomicInteger();
    private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> alternates = new ConcurrentHashMap<>();
    private final Set<Integer> receiving = ConcurrentHashMap.newKeySet();
    private volatile boolean gone;

    /** A transfer running in the browser, and whether it reads. */
    private record Pending(boolean in, @NonNull Consumer<TransferResult> done) {
    }

    SpiceUsbDevice(@NonNull ScreenUsbDevice device, @NonNull ScreenSink sink) {
        this.device = device;
        this.sink = sink;
    }

    @Override
    public @NonNull UsbSpeed speed() {
        byte[] descriptor = this.device.deviceDescriptor();
        int usb = (descriptor[2] & 0xFF) | (descriptor[3] & 0xFF) << 8;
        return usb >= 0x0300 ? UsbSpeed.SUPER : usb >= 0x0200 ? UsbSpeed.HIGH : UsbSpeed.FULL;
    }

    @Override
    public byte @NonNull [] deviceDescriptor() {
        return this.device.deviceDescriptor();
    }

    @Override
    public byte @Nullable [] configurationDescriptor(int configurationValue) {
        return configurationValue == this.configuration() ? this.device.configurationDescriptor() : null;
    }

    @Override
    public int configuration() {
        byte[] configuration = this.device.configurationDescriptor();
        return configuration.length > 5 ? configuration[5] & 0xFF : 0;
    }

    @Override
    public int altSetting(int iface) {
        try {
            return UsbDescriptors.layout(this.device.deviceDescriptor(), this.configuration(),
                this.device.configurationDescriptor(), this::alternate).altSetting(iface);
        } catch (SpiceException malformed) {
            return -1;
        }
    }

    @Override
    public void setConfiguration(int configuration, @NonNull Consumer<UsbStatus> done) {
        done.accept(configuration == this.configuration() ? UsbStatus.SUCCESS : UsbStatus.STALL);
    }

    @Override
    public void setAltSetting(int iface, int altSetting, @NonNull Consumer<UsbStatus> done) {
        // SET_INTERFACE, a standard request to the interface.
        this.controlTransfer(0, new ControlSetup(0x01, 0x0B, altSetting, iface, 0), NONE, result -> {
            if (result.status() == UsbStatus.SUCCESS) {
                this.alternates.put(iface, altSetting);
            }
            done.accept(result.status());
        });
    }

    @Override
    public void reset(@NonNull Consumer<UsbStatus> done) {
        done.accept(this.send(() -> this.sink.usbReset(this.device.device())) ? UsbStatus.SUCCESS : UsbStatus.IOERROR);
    }

    @Override
    public void controlTransfer(long id, @NonNull ControlSetup setup, byte @NonNull [] data,
                                @NonNull Consumer<TransferResult> done) {
        this.transfer(setup.deviceToHost(), done, transfer -> this.sink.usbControl(this.device.device(), transfer,
            setup.requestType(), setup.request(), setup.value(), setup.index(), setup.length(), data));
    }

    @Override
    public void bulkTransfer(long id, int endpoint, long streamId, int length, byte @NonNull [] data,
                             @NonNull Consumer<TransferResult> done) {
        if (streamId != 0) {
            done.accept(TransferResult.failed(UsbStatus.INVAL));
            return;
        }
        this.transfer((endpoint & 0x80) != 0, done, transfer -> this.sink.usbData(this.device.device(), transfer,
            endpoint, UsbTransferKind.BULK, length, data));
    }

    @Override
    public void interruptTransfer(long id, int endpoint, byte @NonNull [] data,
                                  @NonNull Consumer<TransferResult> done) {
        this.transfer(false, done, transfer -> this.sink.usbData(this.device.device(), transfer, endpoint,
            UsbTransferKind.INTERRUPT, data.length, data));
    }

    @Override
    public void startInterruptReceiving(int endpoint, @NonNull PacketSink sink, @NonNull Consumer<UsbStatus> done) {
        int length = this.maxPacketSize(endpoint);
        if (length < 1 || !this.receiving.add(endpoint)) {
            done.accept(UsbStatus.INVAL);
            return;
        }
        done.accept(UsbStatus.SUCCESS);
        this.receive(endpoint, UsbTransferKind.INTERRUPT, length, sink);
    }

    @Override
    public void stopInterruptReceiving(int endpoint) {
        this.receiving.remove(endpoint);
    }

    @Override
    public void startBulkReceiving(long streamId, int endpoint, long bytesPerTransfer, int transferCount,
                                   @NonNull PacketSink sink, @NonNull Consumer<UsbStatus> done) {
        if (streamId != 0 || bytesPerTransfer < 1 || !this.receiving.add(endpoint)) {
            done.accept(UsbStatus.INVAL);
            return;
        }
        done.accept(UsbStatus.SUCCESS);
        this.receive(endpoint, UsbTransferKind.BULK,
            (int) Math.min(bytesPerTransfer, ScreenProtocol.MAX_USB_TRANSFER_BYTES), sink);
    }

    @Override
    public void stopBulkReceiving(long streamId, int endpoint) {
        this.receiving.remove(endpoint);
    }

    /** The guest let go of the device: the browser closes it. */
    @Override
    public void close() {
        this.gone();
        this.send(() -> this.sink.usbRelease(this.device.device()));
    }

    /** The device is gone from the browser: transfers still running fail, streams stop. */
    void gone() {
        this.gone = true;
        this.receiving.clear();
        for (Integer transfer : Set.copyOf(this.pending.keySet())) {
            Pending stopped = this.pending.remove(transfer);
            if (stopped != null) {
                stopped.done().accept(TransferResult.failed(UsbStatus.IOERROR));
            }
        }
    }

    /** A transfer the browser ran completed. */
    void completed(int transfer, int actualLength, byte @NonNull [] data) {
        Pending finished = this.pending.remove(transfer);
        if (finished != null) {
            finished.done().accept(finished.in() ? TransferResult.in(data) : TransferResult.out(actualLength));
        }
    }

    /** A transfer the browser ran stalled. */
    void stalled(int transfer) {
        this.failed(transfer, UsbStatus.STALL);
    }

    /** A transfer the browser ran read more than it asked for. */
    void babbled(int transfer) {
        this.failed(transfer, UsbStatus.BABBLE);
    }

    /** A transfer the browser refused or ran on a device that went away. */
    void failed(int transfer) {
        this.failed(transfer, UsbStatus.IOERROR);
    }

    private void failed(int transfer, @NonNull UsbStatus status) {
        Pending finished = this.pending.remove(transfer);
        if (finished != null) {
            finished.done().accept(TransferResult.failed(status));
        }
    }

    /** Reads an IN endpoint again each time the previous read ended well, until the guest stops it. */
    private void receive(int endpoint, @NonNull UsbTransferKind kind, int length, @NonNull PacketSink sink) {
        if (!this.receiving.contains(endpoint)) {
            return;
        }
        this.transfer(true, result -> {
            if (!this.receiving.contains(endpoint)) {
                return;
            }
            sink.deliver(result.status(), result.data());
            if (result.status() == UsbStatus.SUCCESS) {
                this.receive(endpoint, kind, length, sink);
            } else {
                this.receiving.remove(endpoint);
            }
        }, transfer -> this.sink.usbData(this.device.device(), transfer, endpoint, kind, length, NONE));
    }

    private void transfer(boolean in, @NonNull Consumer<TransferResult> done, @NonNull IntConsumer start) {
        if (this.gone) {
            done.accept(TransferResult.failed(UsbStatus.IOERROR));
            return;
        }
        int transfer = this.transfers.incrementAndGet();
        this.pending.put(transfer, new Pending(in, done));
        if (!this.send(() -> start.accept(transfer)) && this.pending.remove(transfer) != null) {
            done.accept(TransferResult.failed(UsbStatus.IOERROR));
        }
    }

    /** @return whether the session took the message; it refuses once the device or its viewer is gone */
    private boolean send(@NonNull Runnable message) {
        try {
            message.run();
            return true;
        } catch (DomainRefusal refused) {
            return false;
        }
    }

    private int alternate(int iface) {
        return this.alternates.getOrDefault(iface, 0);
    }

    private int maxPacketSize(int endpoint) {
        try {
            return UsbDescriptors.layout(this.device.deviceDescriptor(), this.configuration(),
                this.device.configurationDescriptor(), this::alternate).endpoints()
                .maxPacketSizes()[UsbRedirMessage.EpInfo.index(endpoint)];
        } catch (SpiceException malformed) {
            return 0;
        }
    }
}
