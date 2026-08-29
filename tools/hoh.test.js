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
            return respond(422, { error: { code: 'deploy_not_available',
                message: 'This site has no on-demand deploy' } });
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

function run(args) {
    return new Promise(resolve => {
        const child = cp.spawn(process.execPath, [HOH, ...args], {
            env: { ...process.env, HOH_HOST: `http://127.0.0.1:${server.address().port}`,
                HOH_TOKEN: 'znit_test_token' },
            stdio: ['pipe', 'pipe', 'pipe'],
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
    } finally {
        server.close();
    }
    console.log(process.exitCode ? 'FAILED' : 'ALL PASSED');
});
