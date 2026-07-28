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

if (ipcPort > 0) {
    try {
        socket = net.connect(ipcPort, '127.0.0.1', function onIpcConnect() {
            // Authenticate before anything else; the parent reads exactly one
            // line and drops the connection when it does not carry the token.
            socket.write(JSON.stringify({ type: 'auth', token: ipcToken }) + '\n');
        });
        socket.setEncoding('utf8');
        let buffer = '';
        socket.on('data', function onData(chunk) {
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
        socket.on('error', function onSocketErr() { /* tolerate */ });

        // Forward child-initiated messages back to Hohenheim. Because our
        // IpcChannel parser is flat, stringify only top-level scalars and
        // drop nested objects (they would cause malformed lines).
        child.on('message', function onChildMessage(msg) {
            if (!socket || socket.destroyed) return;
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
            try { socket.write(JSON.stringify(flat) + '\n'); } catch (e) { /* ignore */ }
        });
    } catch (e) {
        // Bridge is best-effort: child must still run.
    }
}

// ----- Lifecycle plumbing: exit codes and signals -----

child.on('exit', function onChildExit(code, signal) {
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
