#!/usr/bin/env node
'use strict';
// Smoke tests for the hoh CLI against a stub /api/v1 server: proves the CLI is a
// THIN client (right path, right method, key header, form body) and that the
// human interlocks hold (rollback refuses without confirmation, and the refusal
// means no request was ever sent). Run: node tools/hoh.test.js
//
// AIDEV-NOTE: the CLI child runs asynchronously (spawn + await), never
// spawnSync -- the stub server lives on THIS event loop, and a synchronous
// child wait deadlocks it (the child's request can never be answered).

const http = require('node:http');
const cp = require('node:child_process');
const path = require('node:path');
const os = require('node:os');
const fs = require('node:fs');

const HOH = path.join(__dirname, 'hoh');
const requests = [];

const server = http.createServer((req, res) => {
    let body = '';
    req.on('data', chunk => body += chunk);
    req.on('end', () => {
        requests.push({ method: req.method, url: req.url, body,
            key: req.headers['x-api-key'] });
        const respond = (status, payload) => {
            res.writeHead(status, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify(payload));
        };
        if (req.url === '/api/v1/dns/zones' && req.method === 'POST') {
            return respond(200, { id: 41, origin: 'example.test', role: 'primary', enabled: true,
                serial: 1, nameservers: ['ns1.hoh.test', 'ns2.hoh.test'], delegation_status: '' });
        }
        if (req.url === '/api/v1/dns/zones' && req.method === 'GET') {
            return respond(200, { zones: [{ id: 41, origin: 'example.test', role: 'primary',
                enabled: true, serial: 1, nameservers: ['ns1.hoh.test'], delegation_status: 'matches' }] });
        }
        if (req.url === '/api/v1/dns/zones/41/import' && req.method === 'POST') {
            return respond(200, { id: 41, origin: 'example.test', serial: 2, imported: 3,
                skipped: [], notes: ['SOA ignored: ns1.old.test hostmaster.old.test serial 5 ttl 3600'],
                nameservers: body.includes('keep_ns=on') ? [] : ['ns1.hoh.test', 'ns2.hoh.test'] });
        }
        if (req.url === '/api/v1/sites' && req.method === 'GET') {
            return respond(200, { sites: [{ id: 7, slug: 'alpha', type: 'hohenheim:docker',
                source: 'git', enabled: true, health: 'healthy' }] });
        }
        if (req.url === '/api/v1/sites/7') {
            return respond(200, { id: 7, slug: 'alpha' });
        }
        if (req.url === '/api/v1/sites/7/deploy') {
            return respond(200, { id: 7, status: 'queued' });
        }
        if (req.url === '/api/v1/sites/7/rollback') {
            return respond(200, { id: 7, status: 'rolled_back' });
        }
        if (req.url === '/api/v1/sites/9/deploy') {
            // The REAL /api/v1 refusal envelope: flat, and naming the refused field.
            return respond(422, { status: 422, code: 'deploy_not_available',
                message: 'This site has no on-demand deploy', field: 'site_id' });
        }
        if (req.url === '/api/v1/projects' && req.method === 'GET') {
            return respond(200, { projects: [] });
        }
        if (req.url === '/api/v1/sites/9') {
            return respond(404, {});
        }
        if (req.url === '/api/v1/sites' && req.method === 'POST') {
            return respond(200, { id: 11, slug: 'earl', type: 'hohenheim:address', enabled: true });
        }
        if (req.url === '/api/v1/sites/11') {
            return respond(200, { id: 11, slug: 'earl' });
        }
        if (req.url === '/api/v1/sites/11/domains' && req.method === 'POST') {
            return respond(200, { id: 21, site_id: 11, hostname: 'earl.example',
                match_type: 'exact', live: true });
        }
        if (req.url === '/api/v1/sites/11/domains') {
            return respond(200, { id: 11, domains: [{ id: 21, hostname: 'earl.example',
                match_type: 'exact', path: '', listen_on: '', force_ssl: true, live: true }] });
        }
        if (req.url === '/api/v1/sites/11/domains/21/delete') {
            return respond(200, { id: 21, site_id: 11, status: 'deleted' });
        }
        if (req.url === '/api/v1/sites/11/delete') {
            return respond(200, { id: 11, status: 'deleted' });
        }
        if (req.url === '/api/v1/access-lists' && req.method === 'POST') {
            return respond(200, { id: 31, name: 'Staff', satisfy: 'any', shared: false,
                rules: [] });
        }
        if (req.url === '/api/v1/access-lists' && req.method === 'GET') {
            return respond(200, { access_lists: [{ id: 31, name: 'Staff',
                satisfy: 'any', shared: false }] });
        }
        if (req.url === '/api/v1/access-lists/31/rules') {
            return respond(200, { id: 41, access_list_id: 31, parent_id: null,
                type: 'basic_auth', enabled: true, data: { username: 'earl' },
                has_password: true });
        }
        if (req.url === '/api/v1/access-lists/31/delete') {
            return respond(200, { id: 31, status: 'deleted' });
        }
        if (req.url === '/api/v1/access-lists/31') {
            return respond(200, { id: 31, name: 'Staff', satisfy: 'any', shared: false });
        }
        if (req.url === '/api/v1/instances' && req.method === 'POST') {
            return respond(200, { id: 51, name: 'earl-app', kind: 'hohenheim:application',
                status: 'created' });
        }
        if (req.url === '/api/v1/instances/51/delete') {
            return respond(200, { id: 51, status: 'deleted' });
        }
        if (req.url === '/api/v1/instances/51') {
            return respond(200, { id: 51, name: 'earl-app', status: 'created' });
        }
        if (req.url === '/api/v1/instances/3/variables') {
            return req.method === 'POST'
                ? respond(200, { id: 3, status: 'set', key: 'TOKEN' })
                : respond(200, { variables: [
                    { key: 'PLAIN', kind: 'plain', value: 'visible' },
                    { key: 'TOKEN', kind: 'secret', has_value: true }] });
        }
        respond(404, {});
    });
});

function run(args, overrides = {}) {
    return new Promise(resolve => {
        const env = { ...process.env,
            HOH_HOST: `http://127.0.0.1:${server.address().port}`,
            HOH_TOKEN: 'znit_test_token', HOH_CONTEXT: undefined, ...overrides };
        // An undefined override means "unset", which spawn cannot express.
        for (const key of Object.keys(env)) if (env[key] === undefined) delete env[key];
        const child = cp.spawn(process.execPath, [HOH, ...args], {
            env, stdio: ['pipe', 'pipe', 'pipe'],
        });
        let stdout = '', stderr = '';
        child.stdout.on('data', d => stdout += d);
        child.stderr.on('data', d => stderr += d);
        child.stdin.end();
        child.on('close', status => resolve({ status, stdout, stderr }));
    });
}

function check(name, condition, detail) {
    if (!condition) {
        console.error(`FAIL ${name}${detail ? ' -- ' + detail : ''}`);
        process.exitCode = 1;
    } else {
        console.log(`ok ${name}`);
    }
}

server.listen(0, '127.0.0.1', async () => {
    try {
        // 1. sites: right path, key header travels, table renders the slug.
        let r = await run(['sites']);
        check('sites renders', r.status === 0 && r.stdout.includes('alpha'), r.stderr);
        check('sites sent the key header',
            requests.at(-1).key === 'znit_test_token', JSON.stringify(requests.at(-1)));

        // 2. deploy: one POST to the documented path.
        r = await run(['deploy', '7']);
        check('deploy posts', r.status === 0 && r.stdout.includes('queued'), r.stderr);
        check('deploy hit the right lane',
            requests.at(-1).method === 'POST' && requests.at(-1).url === '/api/v1/sites/7/deploy');

        // 3. rollback without confirmation (non-tty): REFUSED, and the counterfactual
        //    half -- the wire never saw a rollback request.
        const before = requests.length;
        r = await run(['rollback', '7']);
        check('rollback refuses without confirmation', r.status === 1, r.stdout + r.stderr);
        check('and never sent the rollback request',
            !requests.slice(before).some(q => q.url.endsWith('/rollback')),
            JSON.stringify(requests.slice(before)));

        // 4. rollback --yes: acts.
        r = await run(['rollback', '7', '--yes']);
        check('rollback --yes acts', r.status === 0 && r.stdout.includes('rolled_back'),
            r.stdout + r.stderr);
        check('through the documented lane',
            requests.at(-1).url === '/api/v1/sites/7/rollback');

        // 5. vars set --secret: kind=secret travels form-encoded.
        r = await run(['vars', 'instance', '3', 'set', 'TOKEN', 'hunter2', '--secret']);
        check('secret set accepted', r.status === 0, r.stderr);
        check('kind=secret in the form body',
            requests.at(-1).body.includes('kind=secret')
                && requests.at(-1).body.includes('value=hunter2'));

        // 6. vars list: the secret has no value column content beyond "(set)".
        r = await run(['vars', 'instance', '3']);
        check('vars list masks the secret',
            r.stdout.includes('(set)') && !r.stdout.includes('hunter2'), r.stdout);

        // 7. a server refusal surfaces its machine code and exits nonzero.
        r = await run(['deploy', '9']);
        check('typed refusal surfaces its code',
            r.status === 1 && r.stderr.includes('deploy_not_available'), r.stderr);
        check('and the field the envelope names',
            r.stderr.includes('[site_id]'), r.stderr);

        // 8. site create: name, kind and VERBATIM dotted fields travel form-encoded to
        //    the create lane -- the CLI learns no field vocabulary of its own.
        r = await run(['site', 'create', 'Earl', 'hohenheim:address',
            'settings.forward_host=127.0.0.1', 'settings.forward_port=8080', 'enabled=true']);
        check('site create posts', r.status === 0 && r.stdout.includes('"earl"'), r.stdout + r.stderr);
        check('site create hit the create lane',
            requests.at(-1).method === 'POST' && requests.at(-1).url === '/api/v1/sites');
        check('site create passed the dotted fields verbatim',
            requests.at(-1).body.includes('settings.forward_host=127.0.0.1')
                && requests.at(-1).body.includes('upstream_kind=hohenheim%3Aaddress')
                && requests.at(-1).body.includes('name=Earl'), requests.at(-1).body);

        // 9. domain add / list / remove ride the documented site-domain lanes; remove
        //    has the same slug interlock as rollback.
        r = await run(['site', 'domain', 'add', '11', 'earl.example',
            'custom_headers.0.key=Host', 'custom_headers.0.value=earl.phoenix']);
        check('domain add posts', r.status === 0 && r.stdout.includes('earl.example'), r.stderr);
        check('domain add hit the domain lane with the header rows',
            requests.at(-1).url === '/api/v1/sites/11/domains'
                && requests.at(-1).body.includes('custom_headers.0.value=earl.phoenix'));
        r = await run(['site', 'domains', '11']);
        check('domains lists', r.status === 0 && r.stdout.includes('earl.example'), r.stderr);
        const beforeRemove = requests.length;
        r = await run(['site', 'domain', 'remove', '11', '21']);
        check('domain remove refuses without confirmation', r.status === 1, r.stdout + r.stderr);
        check('and never sent the delete',
            !requests.slice(beforeRemove).some(q => q.url.endsWith('/delete')));
        r = await run(['site', 'domain', 'remove', '11', '21', '--yes']);
        check('domain remove --yes acts', r.status === 0
            && requests.at(-1).url === '/api/v1/sites/11/domains/21/delete', r.stderr);

        // 10. site delete: same interlock, documented delete lane.
        r = await run(['site', 'delete', '11', '--yes']);
        check('site delete --yes acts', r.status === 0
            && requests.at(-1).url === '/api/v1/sites/11/delete', r.stderr);
        r = await run(['site', '11']);
        check('site <id> still reads the detail', r.status === 0 && r.stdout.includes('earl'), r.stderr);

        // 11. access lists: create, list, a rule with its dotted data fields, delete --
        //     same verbatim pass-through and the same name interlock as the site verbs.
        r = await run(['access-list', 'create', 'Staff', 'satisfy=all']);
        check('access-list create posts', r.status === 0 && r.stdout.includes('"Staff"'),
            r.stdout + r.stderr);
        check('access-list create hit the create lane with its fields',
            requests.at(-1).method === 'POST' && requests.at(-1).url === '/api/v1/access-lists'
                && requests.at(-1).body.includes('name=Staff')
                && requests.at(-1).body.includes('satisfy=all'), requests.at(-1).body);
        r = await run(['access-list', 'list']);
        check('access-list list renders', r.status === 0 && r.stdout.includes('Staff'), r.stderr);
        r = await run(['access-list', 'rule', 'add', '31', 'basic_auth',
            'data.username=earl', 'data.password=hunter2', 'enabled=true']);
        check('rule add posts', r.status === 0 && r.stdout.includes('basic_auth'),
            r.stdout + r.stderr);
        check('rule add passed the dotted data fields verbatim',
            requests.at(-1).url === '/api/v1/access-lists/31/rules'
                && requests.at(-1).body.includes('data.username=earl')
                && requests.at(-1).body.includes('type=basic_auth'), requests.at(-1).body);
        const beforeListDelete = requests.length;
        r = await run(['access-list', 'delete', '31']);
        check('access-list delete refuses without confirmation', r.status === 1,
            r.stdout + r.stderr);
        check('and never sent the delete',
            !requests.slice(beforeListDelete).some(q => q.url.endsWith('/delete')));
        r = await run(['access-list', 'delete', '31', '--yes']);
        check('access-list delete --yes acts', r.status === 0
            && requests.at(-1).url === '/api/v1/access-lists/31/delete', r.stderr);

        // 12. instances: create through the resource lane, show, and the same interlock
        //     on the destroy verb.
        r = await run(['instance', 'create', 'earl-app', 'hohenheim:application',
            'settings.repository_url=https://example.test/earl.git', 'settings.branch=main']);
        check('instance create posts', r.status === 0 && r.stdout.includes('earl-app'),
            r.stdout + r.stderr);
        check('instance create hit the create lane with its dotted settings',
            requests.at(-1).method === 'POST' && requests.at(-1).url === '/api/v1/instances'
                && requests.at(-1).body.includes('settings.branch=main')
                && requests.at(-1).body.includes('kind=hohenheim%3Aapplication'),
            requests.at(-1).body);
        check('instance create carries NO template_id (that is the other lane)',
            !requests.at(-1).body.includes('template_id'), requests.at(-1).body);
        r = await run(['instance', 'show', '51']);
        check('instance show reads the detail', r.status === 0 && r.stdout.includes('earl-app'),
            r.stderr);
        const beforeInstanceDelete = requests.length;
        r = await run(['instance', 'delete', '51']);
        check('instance delete refuses without confirmation', r.status === 1,
            r.stdout + r.stderr);
        check('and never sent the destroy',
            !requests.slice(beforeInstanceDelete).some(q => q.url.endsWith('/delete')));
        r = await run(['instance', 'delete', '51', '--yes']);
        check('instance delete --yes acts', r.status === 0
            && requests.at(-1).url === '/api/v1/instances/51/delete', r.stderr);
        r = await run(['instance', '51']);
        check('instance <id> still reads the detail', r.status === 0
            && r.stdout.includes('earl-app'), r.stderr);
        // dns: zone create posts the origin plus verbatim fields; import reads the file
        //      and carries keep_ns only when --keep-ns was given; the answer's notes print.
        r = await run(['dns', 'zone', 'create', 'example.test', 'soa_contact=hostmaster@example.test']);
        check('dns zone create posts', r.status === 0 && r.stdout.includes('ns1.hoh.test'),
            r.stdout + r.stderr);
        check('dns zone create hit the zone lane with its fields',
            requests.at(-1).method === 'POST' && requests.at(-1).url === '/api/v1/dns/zones'
                && requests.at(-1).body.includes('origin=example.test')
                && requests.at(-1).body.includes('soa_contact=hostmaster%40example.test'),
            requests.at(-1).body);
        const zoneFile = path.join(os.tmpdir(), `hoh-test-${process.pid}.zone`);
        fs.writeFileSync(zoneFile, '$ORIGIN example.test.\n@ IN NS ns1.old.test.\nwww IN A 192.0.2.1\n');
        try {
            r = await run(['dns', 'zone', 'import', '41', zoneFile]);
            check('dns zone import posts the file text',
                r.status === 0 && requests.at(-1).url === '/api/v1/dns/zones/41/import'
                    && requests.at(-1).body.includes('zone_text=%24ORIGIN+example.test.')
                    && !requests.at(-1).body.includes('keep_ns'),
                requests.at(-1).body + r.stderr);
            check('dns zone import prints the notes', r.stdout.includes('note: SOA ignored'), r.stdout);
            r = await run(['dns', 'zone', 'import', '41', zoneFile, '--keep-ns']);
            check('dns zone import --keep-ns carries keep_ns',
                r.status === 0 && requests.at(-1).body.includes('keep_ns=on'), requests.at(-1).body);
            r = await run(['dns', 'zone', 'import', '41', zoneFile + '.missing']);
            check('dns zone import refuses an unreadable file before any request',
                r.status === 1 && r.stderr.includes('Cannot read'), r.stderr);
        } finally {
            fs.unlinkSync(zoneFile);
        }
        r = await run(['dns', 'zones']);
        check('dns zones lists', r.status === 0 && r.stdout.includes('example.test')
            && r.stdout.includes('matches'), r.stdout + r.stderr);

        // 13. help: prints AND exits clean. It used to print the whole page and then
        //     die on `.catch` of undefined, because the handler is synchronous -- so
        //     the exit status and an empty stderr are the assertions that matter.
        for (const args of [['help'], ['--help'], ['-h'], []]) {
            r = await run(args);
            check(`help exits clean (hoh ${args.join(' ') || '<no args>'})`,
                r.status === 0 && r.stderr === '', `status=${r.status} stderr=${r.stderr}`);
            check(`help prints the usage (hoh ${args.join(' ') || '<no args>'})`,
                r.stdout.includes('Hohenheim PaaS CLI') && r.stdout.includes('--context'),
                r.stdout);
        }

        // 14. contexts: --context / HOH_CONTEXT select a lane for ONE command without
        //     moving the stored default, which is what let two boxes clobber each other.
        const home = fs.mkdtempSync(path.join(os.tmpdir(), 'hoh-home-'));
        const configPath = path.join(home, '.config', 'hoh', 'config.json');
        const stubHost = `http://127.0.0.1:${server.address().port}`;
        const readCurrent = () => JSON.parse(fs.readFileSync(configPath, 'utf8')).current;
        try {
            fs.mkdirSync(path.dirname(configPath), { recursive: true });
            fs.writeFileSync(configPath, JSON.stringify({
                current: 'bravo',
                contexts: {
                    alpha: { host: stubHost, token: 'znit_alpha' },
                    bravo: { host: 'http://127.0.0.1:1', token: 'znit_bravo' },
                },
            }, null, 2) + '\n');
            // The file lane only: no ambient HOH_HOST/HOH_TOKEN to lean on.
            const onFile = { HOME: home, HOH_HOST: undefined, HOH_TOKEN: undefined };

            r = await run(['--context', 'alpha', 'sites'], onFile);
            check('--context selects the named context', r.status === 0
                && r.stdout.includes('alpha'), r.stdout + r.stderr);
            check('and presents THAT context\'s key',
                requests.at(-1).key === 'znit_alpha', JSON.stringify(requests.at(-1)));
            check('and left the stored default alone', readCurrent() === 'bravo', readCurrent());

            r = await run(['sites'], onFile);
            check('the stored default is still the unreachable bravo',
                r.status === 1 && r.stderr.includes('Cannot reach'), r.stderr);

            r = await run(['sites', '--context=alpha'], onFile);
            check('--context=name spelling works, after the command too',
                r.status === 0 && requests.at(-1).key === 'znit_alpha', r.stderr);

            r = await run(['sites'], { ...onFile, HOH_CONTEXT: 'alpha' });
            check('HOH_CONTEXT selects the same way',
                r.status === 0 && requests.at(-1).key === 'znit_alpha', r.stderr);
            check('and it too left the default alone', readCurrent() === 'bravo', readCurrent());

            // Explicit beats ambient: a shell pointed at a dead box does not win, and
            // the named context brings its OWN key rather than the ambient one.
            r = await run(['--context', 'alpha', 'sites'],
                { HOME: home, HOH_HOST: 'http://127.0.0.1:1', HOH_TOKEN: 'znit_env' });
            check('--context outranks HOH_HOST/HOH_TOKEN', r.status === 0
                && requests.at(-1).key === 'znit_alpha', r.stdout + r.stderr);

            r = await run(['--context', 'nope', 'sites'], onFile);
            check('an unknown context is a named refusal, not a crash',
                r.status === 1 && r.stderr.includes('nope')
                    && r.stderr.includes('context list'), r.stderr);

            const beforeList = requests.length;
            r = await run(['context', 'list'], onFile);
            check('context list shows every context with its host', r.status === 0
                && r.stdout.includes('alpha') && r.stdout.includes('bravo')
                && r.stdout.includes(stubHost), r.stdout + r.stderr);
            check('context list is offline (no API call, so it works with a dead default)',
                requests.length === beforeList, JSON.stringify(requests.slice(beforeList)));
            r = await run(['context', 'list', '--json'], onFile);
            check('context list --json names current and selected',
                r.status === 0 && JSON.parse(r.stdout).current === 'bravo'
                    && JSON.parse(r.stdout).selected === 'bravo', r.stdout + r.stderr);
            r = await run(['context', 'list', '--context', 'alpha', '--json'], onFile);
            check('and reports the selected one under --context',
                JSON.parse(r.stdout).selected === 'alpha'
                    && JSON.parse(r.stdout).current === 'bravo', r.stdout);

            r = await run(['context', 'use', 'alpha'], onFile);
            check('context use moves the stored default', r.status === 0
                && readCurrent() === 'alpha', r.stdout + r.stderr);
            r = await run(['sites'], onFile);
            check('and the bare command now rides it',
                r.status === 0 && requests.at(-1).key === 'znit_alpha', r.stderr);
            r = await run(['context', 'use', 'nope'], onFile);
            check('context use refuses an unknown name and keeps the default',
                r.status === 1 && readCurrent() === 'alpha', r.stderr);

            // 15. login stores into the ACTIVE context, resolved exactly like every other
            //     verb resolves it. It used to write "default" unconditionally, so a
            //     workstation driving two boxes authenticated the wrong one -- silently,
            //     because the write itself succeeded.
            const contextsOf = () => JSON.parse(fs.readFileSync(configPath, 'utf8')).contexts;
            r = await run(['context', 'use', 'bravo'], onFile);
            check('setup: bravo is the stored default again',
                r.status === 0 && readCurrent() === 'bravo', r.stderr);

            r = await run(['login', stubHost], { ...onFile, HOH_TOKEN: 'znit_relogin' });
            check('login lands in the stored default context', r.status === 0
                && contextsOf().bravo.token === 'znit_relogin'
                && contextsOf().bravo.host === stubHost, r.stdout + r.stderr);
            check('and leaves the other context untouched',
                contextsOf().alpha.token === 'znit_alpha', JSON.stringify(contextsOf().alpha));
            check('and writes no "default" context nobody asked for',
                contextsOf().default === undefined, JSON.stringify(contextsOf()));
            check('and does not move the stored default', readCurrent() === 'bravo', readCurrent());

            r = await run(['login', stubHost, '--context', 'alpha'],
                { ...onFile, HOH_TOKEN: 'znit_flagged' });
            check('--context aims login at that context', r.status === 0
                && contextsOf().alpha.token === 'znit_flagged'
                && contextsOf().bravo.token === 'znit_relogin', r.stdout + r.stderr);
            check('and says the default is still elsewhere',
                r.stdout.includes('still "bravo"'), r.stdout);

            r = await run(['login', stubHost],
                { ...onFile, HOH_CONTEXT: 'alpha', HOH_TOKEN: 'znit_ambient' });
            check('HOH_CONTEXT aims it the same way', r.status === 0
                && contextsOf().alpha.token === 'znit_ambient'
                && contextsOf().bravo.token === 'znit_relogin', r.stdout + r.stderr);

            // A first login on a virgin machine still names the context "default".
            const fresh = fs.mkdtempSync(path.join(os.tmpdir(), 'hoh-fresh-'));
            try {
                r = await run(['login', stubHost],
                    { HOME: fresh, HOH_HOST: undefined, HOH_TOKEN: 'znit_first' });
                const config = JSON.parse(fs.readFileSync(
                    path.join(fresh, '.config', 'hoh', 'config.json'), 'utf8'));
                check('a first login creates and selects "default"', r.status === 0
                    && config.current === 'default'
                    && config.contexts.default.token === 'znit_first', r.stdout + r.stderr);
            } finally {
                fs.rmSync(fresh, { recursive: true, force: true });
            }
        } finally {
            fs.rmSync(home, { recursive: true, force: true });
        }
    } finally {
        server.close();
    }
    console.log(process.exitCode ? 'FAILED' : 'ALL PASSED');
});
