#!/usr/bin/env node
// Hohenheim child-process IPC wrapper.
//
// Hohenheim's Java port spawns managed Node children via ProcessBuilder, so
// the child never gets Node's built-in fork()-style IPC channel (the one
// that drives process.send / process.on('message')). Modern alchemy-core
// is aware of HOHENHEIM_IPC_PORT and can bridge our TCP IpcChannel into
// its emitter surface directly, but a large number of deployed sites run
// older alchemy versions that cannot be upgraded.
//
// This wrapper solves that without touching the site's code. We:
//   1. fork() the real entry script, giving IT a proper Node IPC channel.
//   2. Connect to HOHENHEIM_IPC_PORT ourselves and bridge both directions.
//
// The IPC port lives on loopback, which every local user can reach, so the
// channel is authenticated: the FIRST line we write must be
// {"type":"auth","token":<HOHENHEIM_IPC_TOKEN>}. Without it the parent drops
// the connection and keeps accepting, so a hostile local peer cannot squat it.
//
// Server -> child: newline-delimited flat JSON lines from the TCP socket
// get dispatched into the fork via child.send(msg), arriving as a normal
// process.on('message') event. This is exactly what pre-bridge alchemy
// already listens on (see alchemy-core/lib/core/alchemy.js).
//
// Child -> server: child.on('message', msg) is re-serialized as a flat
// JSON line over the TCP socket. Nested objects are dropped because
// Hohenheim's IpcChannel parser is flat-only; send primitive scalars.
//
// Usage: node hohenheim-child-wrapper.js --hh-exec <script> [args...]

'use strict';

const net = require('net');
const path = require('path');
const child_process = require('child_process');

const argv = process.argv.slice(2);
const markerIdx = argv.indexOf('--hh-exec');
if (markerIdx < 0 || markerIdx === argv.length - 1) {
    console.error('[hohenheim-wrapper] missing --hh-exec <script>');
    process.exit(2);
}

const scriptPath = argv[markerIdx + 1];
const scriptArgs = argv.slice(markerIdx + 2);

// Resolve relative to the current working directory so behaviour matches
// a direct `node <script>` invocation.
const resolved = path.resolve(process.cwd(), scriptPath);

const child = child_process.fork(resolved, scriptArgs, {
    stdio: ['inherit', 'inherit', 'inherit', 'ipc'],
    env: process.env,
    cwd: process.cwd(),
});

// ----- Bridge the server TCP socket into the child's fork IPC -----

const ipcPort = parseInt(process.env.HOHENHEIM_IPC_PORT, 10);
const ipcToken = process.env.HOHENHEIM_IPC_TOKEN || '';
let socket = null;

// AIDEV-NOTE: the outbox gates on AUTH WRITTEN, not on socket-exists. The old code
// keyed child->server forwarding on `socket` alone, which failed in two ways: (1)
// `socket` was assigned while the TCP connect was still in flight, so a fast child's
// message entered the Writable queue BEFORE the auth line and the parent (which
// requires auth as the first line) refused the peer; (2) with no usable socket the
// message was dropped outright, so a one-shot `ready` emitted during a reconnect was
// lost forever and the parent killed the child at the ready timeout. Messages queue
// here until the auth line has actually been written, flush FIFO, and survive
// reconnects. The one-shot `ready` is held OUTSIDE the bounded buffer as a sticky
// value replayed on every fresh attach (markProcessReady is idempotent), so overflow
// can never evict the one message the parent's lifecycle depends on.
const OUTBOX_MAX_MESSAGES = 256;
const OUTBOX_MAX_BYTES = 256 * 1024;
const OUTBOX_MAX_LINE_BYTES = 64 * 1024;
const outbox = [];              // serialized lines, oldest first
let outboxBytes = 0;
let droppedCount = 0;           // reported once after the next successful attach
let stickyReadyLine = null;     // latest ready line, replayed per attach
let authWritten = false;

function enqueueLine(line) {
    if (Buffer.byteLength(line) > OUTBOX_MAX_LINE_BYTES) {
        droppedCount++;
        return;
    }
    outbox.push(line);
    outboxBytes += Buffer.byteLength(line);
    while (outbox.length > OUTBOX_MAX_MESSAGES || outboxBytes > OUTBOX_MAX_BYTES) {
        const oldest = outbox.shift();
        outboxBytes -= Buffer.byteLength(oldest);
        droppedCount++;
    }
}

function writeLine(line) {
    if (!authWritten || !socket || socket.destroyed) return false;
    try {
        socket.write(line);
        return true;
    } catch (e) {
        return false;
    }
}

// After auth is on the wire: report drops once, replay the sticky ready, then the
// buffered backlog in FIFO order.
function flushAfterAuth() {
    if (droppedCount > 0) {
        const dropped = droppedCount;
        droppedCount = 0;
        writeLine(JSON.stringify({ type: 'error', code: 'IPC_OUTBOX_OVERFLOW', dropped: dropped }) + '\n');
    }
    if (stickyReadyLine) {
        writeLine(stickyReadyLine);
    }
    while (outbox.length > 0) {
        const line = outbox[0];
        if (!writeLine(line)) return;   // socket died mid-flush; keep the rest queued
        outbox.shift();
        outboxBytes -= Buffer.byteLength(line);
    }
}

// A BOUNDED reconnect loop. A single net.connect that only tolerates errors turns
// any transient refusal (parent restarting, or a hostile local peer briefly
// saturating the parent's pre-auth cap) into a PERMANENT loss of the IPC bridge:
// no ready signal, no remcache, for the child's whole life. So we retry with
// exponential backoff, but only a bounded number of times and never after the
// child exits, so a genuinely-gone parent does not make us spin forever.
const IPC_MAX_ATTEMPTS = 12;           // hard cap: give up rather than spin
const IPC_BACKOFF_BASE_MS = 100;
const IPC_BACKOFF_MAX_MS = 5000;
const IPC_HEALTHY_MS = 30000;          // a connection older than this earns a fresh budget
let ipcAttempts = 0;
let childExited = false;
let reconnectTimer = null;

function scheduleReconnect() {
    // Never race the child's own lifecycle: once it is gone there is nothing to bridge.
    if (childExited || ipcPort <= 0) return;
    if (reconnectTimer) return;
    if (ipcAttempts >= IPC_MAX_ATTEMPTS) return;
    const delay = Math.min(IPC_BACKOFF_BASE_MS * Math.pow(2, ipcAttempts), IPC_BACKOFF_MAX_MS);
    ipcAttempts++;
    reconnectTimer = setTimeout(function () {
        reconnectTimer = null;
        connectIpc();
    }, delay);
    if (reconnectTimer.unref) reconnectTimer.unref();
}

function connectIpc() {
    if (childExited || ipcPort <= 0) return;
    authWritten = false;
    let healthyTimer = null;
    let localSocket;
    try {
        localSocket = net.connect(ipcPort, '127.0.0.1', function onIpcConnect() {
            // Authenticate before anything else; the parent reads exactly one
            // line and drops the connection when it does not carry the auth
            // message. Child messages never overtake it: forwarding gates on
            // authWritten, which only flips AFTER this write is issued.
            try { localSocket.write(JSON.stringify({ type: 'auth', token: ipcToken }) + '\n'); }
            catch (e) { /* the close handler drives the retry */ }
            authWritten = true;
            flushAfterAuth();
            // A connection that survives IPC_HEALTHY_MS is a real attach, not a
            // reject loop, so it earns a fresh reconnect budget for a later drop.
            healthyTimer = setTimeout(function () { ipcAttempts = 0; }, IPC_HEALTHY_MS);
            if (healthyTimer.unref) healthyTimer.unref();
        });
    } catch (e) {
        scheduleReconnect();
        return;
    }
    socket = localSocket;
    localSocket.setEncoding('utf8');
    let buffer = '';
    localSocket.on('data', function onData(chunk) {
        buffer += chunk;
        let idx;
        while ((idx = buffer.indexOf('\n')) >= 0) {
            const line = buffer.slice(0, idx);
            buffer = buffer.slice(idx + 1);
            if (!line) continue;
            let msg;
            try {
                msg = JSON.parse(line);
            } catch (e) {
                continue;
            }
            // Child may not be alive yet if a message arrives very early.
            if (child.connected) {
                try { child.send(msg); } catch (e) { /* ignore */ }
            }
        }
    });
    localSocket.on('error', function onSocketErr() { /* the close handler drives the retry */ });
    localSocket.on('close', function onSocketClose() {
        if (healthyTimer) { clearTimeout(healthyTimer); healthyTimer = null; }
        if (socket === localSocket) {
            socket = null;
            // Gate closed again; the outbox is deliberately NOT cleared, so
            // whatever queued during this attach survives into the next one.
            authWritten = false;
        }
        scheduleReconnect();
    });
}

// Forward child-initiated messages back to Hohenheim. Because our IpcChannel
// parser is flat, stringify only top-level scalars and drop nested objects
// (they would cause malformed lines). Registered once; delivery rides the
// auth-gated outbox so nothing is written before auth or lost between attaches.
child.on('message', function onChildMessage(msg) {
    const flat = {};
    if (typeof msg === 'string') {
        flat.type = msg;
    } else if (msg && typeof msg === 'object') {
        for (const key in msg) {
            const v = msg[key];
            if (v == null || typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean') {
                flat[key] = v;
            }
        }
    } else {
        return;
    }
    const line = JSON.stringify(flat) + '\n';
    if (flat.type === 'ready') {
        // Sticky, never buffered: replayed on every fresh attach so the parent's
        // ready-gated lifecycle can never lose it to buffer bounds.
        stickyReadyLine = line;
        writeLine(line);
        return;
    }
    if (!writeLine(line)) {
        enqueueLine(line);
    }
});

connectIpc();

// ----- Lifecycle plumbing: exit codes and signals -----

child.on('exit', function onChildExit(code, signal) {
    // The child is gone: stop the bridge and cancel any pending reconnect so the
    // wrapper never outlives its child chasing a connection nothing will read.
    childExited = true;
    if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }
    if (socket && !socket.destroyed) socket.end();
    if (signal) {
        // Re-raise the original signal with our listener detached so the
        // runtime's default termination behaviour applies; otherwise our
        // forwarder would re-dispatch to the already-dead child and the
        // wrapper would linger.
        process.removeAllListeners(signal);
        try { process.kill(process.pid, signal); } catch (e) { /* ignore */ }
        process.exit(128);
    } else {
        process.exit(code != null ? code : 0);
    }
});

function forwardSignal(sig) {
    process.on(sig, function () {
        try { child.kill(sig); } catch (e) { /* child already gone */ }
    });
}
forwardSignal('SIGTERM');
forwardSignal('SIGINT');
forwardSignal('SIGHUP');
