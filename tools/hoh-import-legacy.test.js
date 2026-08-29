#!/usr/bin/env node
'use strict';
// Tests for the legacy site converter: one anonymized fixture covering every old
// site_type, asserted through the CONVERTER'S OWN OUTPUT (the manifest and the
// printed hoh invocations), never its internals. Run: node --test tools/
//
// AIDEV-NOTE: the "dry-run never calls hoh" test is the load-bearing one -- the
// default mode is the one an operator points at a live panel by accident, so it
// is proven by a counterfactual (a stub hoh that records every invocation) and
// not by reading the code.

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const cp = require('node:child_process');

const TOOL = path.join(__dirname, 'hoh-import-legacy');
const FIXTURE = path.join(__dirname, 'legacy-sites.fixture.json');

const STUB_HOH = `
const fs = require('node:fs');
const argv = process.argv.slice(2);
fs.appendFileSync(process.env.STUB_LOG, JSON.stringify(argv) + '\\n');
if (argv[0] === 'sites') {
    console.log(JSON.stringify({ sites: [{ id: 1, name: 'Example Proxy', slug: 'example-proxy' }] }));
} else if (argv[1] === 'create') {
    console.log(JSON.stringify({ id: 42, slug: 'created' }));
} else {
    console.log(JSON.stringify({ id: 7 }));
}
`;

/** Runs the converter in a fresh temp dir and returns its output plus the manifest. */
function convert(args, { map = null, stub = false } = {}) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'hoh-import-'));
    const base = path.join(dir, 'manifest');
    const full = [FIXTURE, '--manifest', base, ...args];
    if (map) {
        const mapPath = path.join(dir, 'map.json');
        fs.writeFileSync(mapPath, JSON.stringify(map));
        full.push('--map', mapPath);
    }
    const env = { ...process.env };
    if (stub) {
        const stubPath = path.join(dir, 'stub-hoh.js');
        fs.writeFileSync(stubPath, STUB_HOH);
        env.STUB_LOG = path.join(dir, 'stub.log');
        fs.writeFileSync(env.STUB_LOG, '');
        full.push('--hoh', stubPath);
    }
    const result = cp.spawnSync(process.execPath, [TOOL, ...full], { encoding: 'utf8', env });
    return {
        status: result.status,
        stdout: result.stdout || '',
        stderr: result.stderr || '',
        manifest: JSON.parse(fs.readFileSync(base + '.json', 'utf8')),
        text: fs.readFileSync(base + '.txt', 'utf8'),
        calls: stub ? fs.readFileSync(env.STUB_LOG, 'utf8').trim() : '',
    };
}

const siteOf = (manifest, name) => manifest.sites.find(site => site.name === name);
const createOf = (site) => site.commands.find(command => command[1] === 'create') || [];
const domainsOf = (site) => site.commands.filter(command => command[1] === 'domain');

test('a proxy becomes an address site with its Host rewrite and its delay', () => {
    const run = convert([]);
    const site = siteOf(run.manifest, 'Example Proxy');
    assert.strictEqual(site.verdict, 'converted');
    const create = createOf(site);
    assert.deepStrictEqual(create.slice(0, 4),
        ['site', 'create', 'Example Proxy', 'hohenheim:address']);
    assert.ok(create.includes('settings.forward_scheme=http'), create.join(' '));
    assert.ok(create.includes('settings.forward_host=localhost'), create.join(' '));
    assert.ok(create.includes('settings.forward_port=8080'), create.join(' '));
    assert.ok(create.includes('settings.delay=100'), create.join(' '));

    // The documented trap: a rewritten Host must not rewrite upstream Location.
    assert.ok(create.includes('settings.rewrite_location=false'), create.join(' '));

    const domains = domainsOf(site);
    assert.strictEqual(domains.length, 2, 'both hostnames');
    assert.ok(domains[0].includes('custom_headers.0.key=Host'), domains[0].join(' '));
    assert.ok(domains[0].includes('custom_headers.0.value=example.oldbox'), domains[0].join(' '));

    // The old listeners are reported as dropped, never invented into the new row.
    assert.ok(site.dropped.some(drop => drop.field === 'domain.listen_on'),
        JSON.stringify(site.dropped));
    assert.ok(!domains[0].some(arg => arg.startsWith('listen_on=')), domains[0].join(' '));
});

test('an upstream url with a path component is unmappable, and says so', () => {
    const run = convert([]);
    const site = siteOf(run.manifest, 'Example Subpath');
    assert.strictEqual(site.verdict, 'unmappable');
    assert.strictEqual(site.commands.length, 0);
    assert.ok(site.reasons.join(' ').includes('/tenant/subpath/'), site.reasons.join(' '));
});

test('a permanent redirect becomes 301 with preserve_path=false, wildcards keep their shape', () => {
    const run = convert([]);
    const site = siteOf(run.manifest, 'Example Redirect');
    assert.strictEqual(site.verdict, 'converted');
    const create = createOf(site);
    assert.strictEqual(create[3], 'hohenheim:redirect');
    assert.ok(create.includes('settings.target_url=https://www.example.test'), create.join(' '));
    assert.ok(create.includes('settings.http_status=301'), create.join(' '));
    assert.ok(create.includes('settings.preserve_path=false'), create.join(' '));
    assert.ok(site.notes.some(note => note.includes('preserve_path=false assumed')),
        site.notes.join(' '));

    const domains = domainsOf(site);
    assert.ok(!domains[0].some(arg => arg.startsWith('match_type=')), 'exact needs no match_type');
    assert.ok(domains[1].includes('match_type=wildcard'), domains[1].join(' '));

    // A leftover of an earlier site_type is dropped BY NAME, never silently.
    assert.ok(site.dropped.some(drop => drop.field === 'settings.script'),
        JSON.stringify(site.dropped));
});

test('basic auth without a mapped access list refuses; with one it converts', () => {
    let site = siteOf(convert([]).manifest, 'Example Static');
    assert.strictEqual(site.verdict, 'needs-access-list');
    assert.strictEqual(site.commands.length, 0);
    assert.ok(site.reasons.join(' ').includes('reviewer'), 'the user name is named');

    const run = convert([], { map: { accessLists: { 'Example Static': 3 } } });
    site = siteOf(run.manifest, 'Example Static');
    assert.strictEqual(site.verdict, 'converted');
    const create = createOf(site);
    assert.strictEqual(create[3], 'hohenheim:static');
    assert.ok(create.includes('access_list_id=3'), create.join(' '));
    assert.ok(create.includes('settings.root_path=/srv/example-static/build/'), create.join(' '));
    assert.ok(create.includes('settings.fallback_file=index.html'),
        'the fallback is made relative to the root: ' + create.join(' '));
});

test('a workload needs an instance, and the manifest carries its blueprint', () => {
    const run = convert([]);
    for (const name of ['Example Workload', 'Example Node']) {
        const site = siteOf(run.manifest, name);
        assert.strictEqual(site.verdict, 'needs-instance', name);
        assert.strictEqual(site.commands.length, 0, name);
    }
    const blueprint = siteOf(run.manifest, 'Example Workload').instance_blueprint;
    assert.strictEqual(blueprint.node_version, '16.13.2');
    assert.strictEqual(blueprint.script, '/home/example/app/server.js');
    assert.strictEqual(blueprint.run_as_uid, '4001');
    assert.strictEqual(blueprint.maximum_processes, 2);
    assert.strictEqual(blueprint.api_key_count, 1);
    assert.deepStrictEqual(blueprint.environment_variables,
        [{ name: 'LD_LIBRARY_PATH', value: '(redacted)' }]);
    assert.ok(!run.text.includes('/opt/example/lib'), 'values stay out of the text manifest');
    assert.ok(!run.text.includes('key-one'), 'api key values stay out of the manifest');
});

test('a mapped instance turns the workload into an instance site', () => {
    const run = convert([], { map: { instances: { 'Example Workload': 12 },
        listenOn: '10.0.0.5' } });
    const site = siteOf(run.manifest, 'Example Workload');
    assert.strictEqual(site.verdict, 'converted');
    const create = createOf(site);
    assert.strictEqual(create[3], 'hohenheim:instance');
    assert.ok(create.includes('instance_id=12'), create.join(' '));
    assert.ok(domainsOf(site)[0].includes('listen_on=10.0.0.5'), 'the mapped listener travels');
});

test('an unknown settings key makes the site unmappable, named', () => {
    const run = convert([]);
    const site = siteOf(run.manifest, 'Example Stranger');
    assert.strictEqual(site.verdict, 'unmappable');
    assert.strictEqual(site.commands.length, 0);
    assert.ok(site.reasons.join(' ').includes('unheard_of_setting'), site.reasons.join(' '));
});

test('basic-auth passwords are never written to the manifest', () => {
    const run = convert(['--include-secrets'],
        { map: { accessLists: { 'Example Static': 3 } } });
    assert.ok(!run.text.includes('hunter2'), 'text manifest');
    assert.ok(!JSON.stringify(run.manifest).includes('hunter2'), 'json manifest');
    assert.ok(run.text.includes('reviewer'), 'the user NAME is reported');
});

test('--only / --skip select by name and unresolved selectors are reported', () => {
    const run = convert(['--only', 'Example Proxy,Example Redirect', '--skip', 'Nothing Here']);
    assert.deepStrictEqual(run.manifest.sites.map(site => site.name),
        ['Example Proxy', 'Example Redirect']);
    assert.deepStrictEqual(run.manifest.unresolved_selectors, ['Nothing Here']);
    assert.ok(run.stderr.includes('Nothing Here'), run.stderr);
});

test('the dry run prints the invocations and calls hoh not once', () => {
    const run = convert([], { stub: true });
    assert.strictEqual(run.status, 0, run.stderr);
    assert.ok(run.stdout.includes('hoh site create Example\\ Proxy hohenheim:address')
        || run.stdout.includes("hoh site create 'Example Proxy' hohenheim:address"),
        run.stdout);
    assert.strictEqual(run.calls, '', 'the stub hoh recorded an invocation: ' + run.calls);
});

test('--apply drives hoh and skips a site whose name already exists', () => {
    const run = convert(['--apply'], { stub: true,
        map: { accessLists: { 'Example Static': 3 } } });
    assert.strictEqual(run.status, 0, run.stderr);
    const calls = run.calls.split('\n').map(line => JSON.parse(line));

    assert.deepStrictEqual(calls[0], ['sites', '--json'], 'it reads what exists first');
    assert.ok(!calls.some(call => call[1] === 'create' && call[2] === 'Example Proxy'),
        'the pre-existing site is not created twice');
    assert.ok(run.stdout.includes('skip  Example Proxy'), run.stdout);

    const created = calls.filter(call => call[1] === 'create').map(call => call[2]);
    assert.deepStrictEqual(created, ['Example Redirect', 'Example Static']);
    const domainAdd = calls.find(call => call[1] === 'domain');
    assert.strictEqual(domainAdd[3], '42', 'the created site id is substituted');
    assert.strictEqual(siteOf(run.manifest, 'Example Redirect').applied.site_id, 42);
});
