# Interactive console (`console_kind = tty`)

The "Janeway console" the phase-0 design reserved as phase 3, shipped 2026-08-29.

## What it is

An instance whose kind settings declare `console_kind: tty` runs its primary process
behind a real pseudo-terminal. The console tab then behaves like a terminal: what the
viewer types goes to the process as keystrokes, the process echoes, and the viewer's
terminal size is handed to the process (SIGWINCH), so a full-screen TUI such as Alchemy's
Janeway renders and redraws exactly as it would in an SSH session. The default, `plain`,
is unchanged: output only on the socket, one command line per POST through the form.

## The one declaring home

`be.elevenways.hohenheim.instance.ConsoleKind` is the vocabulary (`PLAIN`, `TTY`), and
its `interactive()` fact is what every consumer reads. The declaration lives in the KIND
SETTINGS under `console_kind` (`DockerContainerKind`, `WorkspaceKind`, `ApplicationKind`,
copied per release onto `ReleaseKind` by `ApplicationReleases`), so a template carries it
in its settings baseline and a template-less workspace or application declares it the same
way. The never-read `instance_templates.console_kind` column was dropped by
`M003_TemplateConsoleKindDropped`. An unknown token refuses the deploy by name
(`console_kind_unknown`); it never degrades to plain.

## How it flows

- `InstanceSpec.tty` carries the declaration to the driver.
- `DockerInstanceRuntime.buildSpec` sets `Tty: true` (permitted in
  `ContainerHardening.PERMITTED_BODY_KEYS`, with the reason) and `TERM=xterm-256color`
  unless the declared environment names one. `start` sizes the fresh pseudo-terminal to
  `ConsoleStreamSupport.INITIAL_COLS x INITIAL_ROWS` right after the container starts,
  because a PTY nobody sized reports 0x0 and `HostConfig.ConsoleSize` is honoured on
  Linux only from Docker API 1.42.
- `DockerInstanceRuntime.openConsole` reads `Config.Tty` off the inspect payload, attaches
  with the framing DECLARED (a TTY attach is raw, never frame-multiplexed) and returns
  `Console(stream, stdinDelivered, interactive)`. `resizeConsole` is
  `POST /containers/{id}/resize`. `consoleTail` reads the log raw for a TTY container.
- The Incus driver refuses a `tty` spec by name: its start command runs under the guest's
  init and there is no pseudo-terminal lane there yet.
- `InstanceConsoles.attach(id)` hands a viewer a `Viewer`: `interactive()`, `follow`,
  `write` (raw keystrokes, no newline appended, no stop-command matching) and `resize`.
  `InstanceConsoleHandler` uses it: for an interactive session output is relayed
  verbatim (the PTY already emits `\r\n`) and inbound frames are keystrokes or the
  `{"type":"resize"}` control frame (`TerminalControlFrames`, shared with the shell
  handler); for a plain session inbound text is ignored and `\n` becomes `\r\n`.
- `cms/instance-console.hwk` renders `pl-terminal` writable and drops the command form
  when `interactive` is true.

## Why no IPC geometry dance

The old Node controller (and the deleted Java `ProcessTerminalHandler`) sent
`janeway_propose_geometry` + `janeway_redraw` over an IPC channel because the child ran on
PIPES and had no terminal to read its size from. On a pseudo-terminal the size IS the
terminal's: `docker resize` delivers SIGWINCH and Node's `process.stdout` reports the new
columns, so Janeway redraws on its own. Nothing Alchemy-specific exists on the Hohenheim
side, and any TUI works.

## Running an Alchemy app

A workspace on the `node-22` runtime image (which now carries `python3 make g++`, because
`@picturae/mmmagic` compiles from source), `build_command: npm install`, `start_command:
node server.js`, `container_port: 3000`, `console_kind: tty`, environment
`ALCHEMY_SKIP_LOCAL_CONFIG=1`, `ALCHEMY_ENV=live`, and a managed Mongo attached so
`DATABASE_URL` is injected (the skeleton's `hohenheim` branch reads it). The stored
console history of a TTY workload is raw terminal output, escape sequences included; that
is the workload's stdout verbatim, which is what the history has always stored.

## Proof

- `InteractiveConsoleTest` (hermetic, `FakeNativeDaemons`): the declaration reaches the
  driver as a TTY, keystrokes and geometry reach the workload, raw relay, the plain
  console's guarantees, the unknown-token refusal.
- `InstanceConsoleLiveTest.anInteractiveConsoleIsARealPseudoTerminalWithKeystrokesAndGeometry`
  (real daemon): `Config.Tty`, echo, `stty size` reporting the viewer's geometry, a live
  resize.
- `ContainerEscapeKeyTest` pins `Tty` as a permitted create-body key.
