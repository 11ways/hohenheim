package be.elevenways.hohenheim.server.incus;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps browser {@code KeyboardEvent.code} strings to PC "AT set 1" scancodes for the
 * SPICE INPUTS channel. The browser stays SPICE-agnostic: it reports layout-independent
 * {@code code} names and the server owns the scancode vocabulary. An extended key is
 * stored as {@code 0xE000 | base}; {@link #makeCode}/{@link #breakCode} pack the two-byte
 * form the way qemu's inputs handler expects (low byte 0xE0, then the make/break byte).
 */
final class SpiceScancodes {

    private static final int EXTENDED = 0xE000;
    private static final Map<String, Integer> CODES = new HashMap<>();

    private SpiceScancodes() {}

    static {
        put("Escape", 0x01);
        put("Digit1", 0x02); put("Digit2", 0x03); put("Digit3", 0x04); put("Digit4", 0x05);
        put("Digit5", 0x06); put("Digit6", 0x07); put("Digit7", 0x08); put("Digit8", 0x09);
        put("Digit9", 0x0A); put("Digit0", 0x0B);
        put("Minus", 0x0C); put("Equal", 0x0D); put("Backspace", 0x0E); put("Tab", 0x0F);
        put("KeyQ", 0x10); put("KeyW", 0x11); put("KeyE", 0x12); put("KeyR", 0x13);
        put("KeyT", 0x14); put("KeyY", 0x15); put("KeyU", 0x16); put("KeyI", 0x17);
        put("KeyO", 0x18); put("KeyP", 0x19);
        put("BracketLeft", 0x1A); put("BracketRight", 0x1B); put("Enter", 0x1C);
        put("ControlLeft", 0x1D);
        put("KeyA", 0x1E); put("KeyS", 0x1F); put("KeyD", 0x20); put("KeyF", 0x21);
        put("KeyG", 0x22); put("KeyH", 0x23); put("KeyJ", 0x24); put("KeyK", 0x25);
        put("KeyL", 0x26); put("Semicolon", 0x27); put("Quote", 0x28); put("Backquote", 0x29);
        put("ShiftLeft", 0x2A); put("Backslash", 0x2B);
        put("KeyZ", 0x2C); put("KeyX", 0x2D); put("KeyC", 0x2E); put("KeyV", 0x2F);
        put("KeyB", 0x30); put("KeyN", 0x31); put("KeyM", 0x32);
        put("Comma", 0x33); put("Period", 0x34); put("Slash", 0x35); put("ShiftRight", 0x36);
        put("NumpadMultiply", 0x37); put("AltLeft", 0x38); put("Space", 0x39);
        put("CapsLock", 0x3A);
        put("F1", 0x3B); put("F2", 0x3C); put("F3", 0x3D); put("F4", 0x3E); put("F5", 0x3F);
        put("F6", 0x40); put("F7", 0x41); put("F8", 0x42); put("F9", 0x43); put("F10", 0x44);
        put("NumLock", 0x45); put("ScrollLock", 0x46);
        put("Numpad7", 0x47); put("Numpad8", 0x48); put("Numpad9", 0x49); put("NumpadSubtract", 0x4A);
        put("Numpad4", 0x4B); put("Numpad5", 0x4C); put("Numpad6", 0x4D); put("NumpadAdd", 0x4E);
        put("Numpad1", 0x4F); put("Numpad2", 0x50); put("Numpad3", 0x51); put("Numpad0", 0x52);
        put("NumpadDecimal", 0x53); put("F11", 0x57); put("F12", 0x58);

        // Extended (0xE0-prefixed) keys.
        put("ControlRight", EXTENDED | 0x1D);
        put("AltRight", EXTENDED | 0x38);
        put("NumpadEnter", EXTENDED | 0x1C);
        put("NumpadDivide", EXTENDED | 0x35);
        put("Home", EXTENDED | 0x47);
        put("ArrowUp", EXTENDED | 0x48);
        put("PageUp", EXTENDED | 0x49);
        put("ArrowLeft", EXTENDED | 0x4B);
        put("ArrowRight", EXTENDED | 0x4D);
        put("End", EXTENDED | 0x4F);
        put("ArrowDown", EXTENDED | 0x50);
        put("PageDown", EXTENDED | 0x51);
        put("Insert", EXTENDED | 0x52);
        put("Delete", EXTENDED | 0x53);
        put("MetaLeft", EXTENDED | 0x5B);
        put("MetaRight", EXTENDED | 0x5C);
        put("ContextMenu", EXTENDED | 0x5D);
    }

    private static void put(@NonNull String code, int scancode) {
        CODES.put(code, scancode);
    }

    /** The stored scancode for a {@code KeyboardEvent.code}, or 0 when unmapped. */
    static int forCode(@NonNull String code) {
        Integer value = CODES.get(code);
        return value == null ? 0 : value;
    }

    /** The make-code word to send on key down. */
    static int makeCode(int scancode) {
        if ((scancode & EXTENDED) != 0) {
            return 0xE0 | ((scancode & 0xFF) << 8);
        }
        return scancode & 0xFF;
    }

    /** The break-code word to send on key up (bit 7 set on the make byte). */
    static int breakCode(int scancode) {
        if ((scancode & EXTENDED) != 0) {
            return 0xE0 | (((scancode & 0xFF) | 0x80) << 8);
        }
        return (scancode & 0xFF) | 0x80;
    }
}
