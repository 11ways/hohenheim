#!/usr/bin/env node
'use strict';
// Tests for the DNS cutover gate. Run: node --test "tools/*.test.js"
//
// AIDEV-NOTE: everything below is offline. The delegation walk and the compare
// loop take their query function as an argument precisely so the classification
// -- lame / mismatch / skew / only-old -- can be proven against answers a real
// network would never reliably reproduce, and the wire codec is proven against
// a REAL captured answer (starfleet.life SOA from 104.223.42.142, 2026-08-29)
// so a decoder bug cannot hide behind a self-consistent encoder.

const test = require('node:test');
const assert = require('node:assert');

const tool = require('./hoh-dns-diff');

// A real answer, captured from the live FreeDNS primary. Its MNAME is
// nssl.mooo.com -- the FreeDNS trap the migration doc warns about.
const CAPTURED_SOA = '1234840000010001000000000973746172666c656574046c69666500000600'
    + '01c00c0006000100000e100030046e73736c046d6f6f6f03636f6d000a686f73746d6173746572'
    + 'c00c0000002200001c2000000e10001275000000012c';

/** Builds a decoded-message shape without going near a socket. */
function answer({ rcode = 0, aa = true, records = [], authority = [], additional = [], server = 'test', error = null } = {}) {
    if (error) return { error, server, answer: [], authority: [], additional: [] };
    return {
        rcode,
        rcodeName: { 0: 'NOERROR', 2: 'SERVFAIL', 3: 'NXDOMAIN', 5: 'REFUSED' }[rcode] || String(rcode),
        aa,
        tc: false,
        server,
        answer: records,
        authority,
        additional,
    };
}

function rr(name, type, data, ttl = 300) {
    return { name: tool.normaliseName(name), type, ttl, klass: 1, data };
}

// -- wire codec ---------------------------------------------------------------

test('encodeName round-trips through decodeName, root included', () => {
    for (const name of ['starfleet.life.', 'www.example.co.uk.', '.']) {
        const buffer = tool.encodeName(name);
        assert.equal(tool.decodeName(buffer, 0).name, name);
    }
});

test('encodeQuery builds a question decodeMessage reads back', () => {
    const packet = tool.encodeQuery('www.11ways.be', 'MX', { id: 0xBEEF, rd: false });
    const decoded = tool.decodeMessage(packet);
    assert.equal(decoded.id, 0xBEEF);
    assert.equal(decoded.questions.length, 1);
    assert.equal(decoded.questions[0].name, 'www.11ways.be.');
    assert.equal(decoded.questions[0].type, 'MX');
    assert.equal(decoded.rcode, 0);
});

test('encodeQuery honours the recursion-desired bit in both directions', () => {
    assert.equal(tool.encodeQuery('a.b', 'A', { rd: true }).readUInt16BE(2), 0x0100);
    assert.equal(tool.encodeQuery('a.b', 'A', { rd: false }).readUInt16BE(2), 0x0000);
});

test('decodeMessage reads a real captured SOA answer, compression pointers and all', () => {
    const decoded = tool.decodeMessage(Buffer.from(CAPTURED_SOA, 'hex'));
    assert.equal(decoded.rcodeName, 'NOERROR');
    assert.equal(decoded.aa, true, 'the captured answer is authoritative');
    assert.equal(decoded.questions[0].name, 'starfleet.life.');
    assert.equal(decoded.answer.length, 1);
    const soa = decoded.answer[0];
    assert.equal(soa.type, 'SOA');
    assert.equal(soa.name, 'starfleet.life.');
    assert.equal(soa.ttl, 3600);
    assert.equal(soa.data, 'nssl.mooo.com. hostmaster.starfleet.life. 34 7200 3600 1209600 300');
});

test('unknown record types decode as opaque hex rather than being dropped', () => {
    // TYPE9999 with rdata 0xDEAD, so a shape nobody anticipated still compares byte-for-byte.
    const header = Buffer.from([0, 1, 0x84, 0, 0, 0, 0, 1, 0, 0, 0, 0]);
    const record = Buffer.concat([
        tool.encodeName('x.test.'),
        Buffer.from([0x27, 0x0F, 0, 1, 0, 0, 0, 60, 0, 2, 0xDE, 0xAD]),
    ]);
    const decoded = tool.decodeMessage(Buffer.concat([header, record]));
    assert.equal(decoded.answer[0].type, '9999');
    assert.equal(decoded.answer[0].data, 'dead');
});

test('ipv6Text collapses the longest zero run', () => {
    assert.equal(tool.ipv6Text(Buffer.from('20010db8000000000000000000000001', 'hex')), '2001:db8::1');
    assert.equal(tool.ipv6Text(Buffer.from('2001185000010005080000000000006b', 'hex')), '2001:1850:1:5:800::6b');
    assert.equal(tool.ipv6Text(Buffer.from('20010db8000000010000000200000003', 'hex')), '2001:db8:0:1:0:2:0:3',
        'a single zero group is never collapsed');
});

// -- normalisation ------------------------------------------------------------

test('normaliseName lowercases and dot-terminates every spelling of one owner', () => {
    assert.equal(tool.normaliseName('WWW.Example.COM'), 'www.example.com.');
    assert.equal(tool.normaliseName('www.example.com.'), 'www.example.com.');
    assert.equal(tool.normaliseName(''), '.');
});

test('qualify joins a bare label to the zone and leaves absolute names alone', () => {
    assert.equal(tool.qualify('www', 'starfleet.life'), 'www.starfleet.life.');
    assert.equal(tool.qualify('@', 'starfleet.life'), 'starfleet.life.');
    assert.equal(tool.qualify('www.starfleet.life', 'starfleet.life'), 'www.starfleet.life.');
    assert.equal(tool.qualify('elsewhere.example.', 'starfleet.life'), 'elsewhere.example.');
});

test('rrset sorts and filters, so record ORDER on the wire is never a difference', () => {
    const records = [
        rr('a.test', 'A', '10.0.0.2'),
        rr('a.test', 'A', '10.0.0.1'),
        rr('b.test', 'A', '10.0.0.9'),
        rr('a.test', 'MX', '10 mail.test.'),
    ];
    assert.deepEqual(tool.rrset(records, 'A.TEST', 'A'), ['10.0.0.1', '10.0.0.2']);
});

test('owner CASE on the wire is never a difference', () => {
    const records = [{ name: 'www.test.', type: 'A', ttl: 60, klass: 1, data: '10.0.0.1' }];
    assert.deepEqual(tool.rrset(records, 'WWW.Test', 'A'), ['10.0.0.1']);
});

test('splitSoa separates the serial from the fields a cutover must match', () => {
    const split = tool.splitSoa('ns.a. host.a. 34 7200 3600 1209600 300');
    assert.equal(split.serial, 34);
    assert.equal(split.stable, 'ns.a. host.a. 7200 3600 1209600 300');
});

// -- verdict classes ----------------------------------------------------------

const OLD = 'old.server';
const NEW = 'new.server';

function judgeOf(oldRecords, newRecords, { name = 'www.test.', type = 'A', zone = 'test.', oldTtl, newTtl } = {}) {
    const withTtl = (records, ttl) => (ttl === undefined ? records : records.map((r) => ({ ...r, ttl })));
    return tool.judge(name, type,
        [answer({ records: withTtl(oldRecords, oldTtl), server: OLD })],
        [answer({ records: withTtl(newRecords, newTtl), server: NEW })],
        zone);
}

test('identical answers are IDENTICAL regardless of order', () => {
    const row = judgeOf(
        [rr('www.test', 'A', '10.0.0.2'), rr('www.test', 'A', '10.0.0.1')],
        [rr('www.test', 'A', '10.0.0.1'), rr('www.test', 'A', '10.0.0.2')]);
    assert.equal(row.verdict, 'identical');
    assert.equal(tool.counts(row, false), false);
    assert.equal(tool.counts(row, true), false);
});

test('a changed value is DIFFERS and fails the run', () => {
    const row = judgeOf([rr('www.test', 'A', '10.0.0.1')], [rr('www.test', 'A', '10.0.0.9')]);
    assert.equal(row.verdict, 'differs');
    assert.deepEqual(row.oldValues, ['10.0.0.1']);
    assert.deepEqual(row.newValues, ['10.0.0.9']);
    assert.equal(tool.counts(row, false), true);
});

test('a record the new side lacks is ONLY-OLD and fails the run', () => {
    const row = judgeOf([rr('www.test', 'A', '10.0.0.1')], []);
    assert.equal(row.verdict, 'only-old');
    assert.equal(tool.counts(row, false), true);
});

test('a record only the new side has is ONLY-NEW and fails the run', () => {
    const row = judgeOf([], [rr('www.test', 'A', '10.0.0.1')]);
    assert.equal(row.verdict, 'only-new');
    assert.equal(tool.counts(row, false), true);
});

test('both sides empty produces no row at all', () => {
    assert.equal(judgeOf([], []), null);
});

test('a refusing server is ERROR, never an empty RRset', () => {
    const row = tool.judge('www.test.', 'A',
        [answer({ records: [rr('www.test', 'A', '10.0.0.1')], server: OLD })],
        [answer({ rcode: 5, aa: false, server: NEW })],
        'test.');
    assert.equal(row.verdict, 'error');
    assert.equal(tool.counts(row, false), true);
    assert.deepEqual(row.oldValues, ['10.0.0.1'], 'the reachable side is still reported');
    assert.match(row.newStatus, /REFUSED/);
});

test('a timeout is ERROR and names the server', () => {
    const row = tool.judge('www.test.', 'A',
        [answer({ error: 'timeout', server: OLD })],
        [answer({ records: [rr('www.test', 'A', '10.0.0.1')], server: NEW })],
        'test.');
    assert.equal(row.verdict, 'error');
    assert.ok(row.notes.some((note) => note.includes('old old.server: timeout')));
});

test('NXDOMAIN is a real answer: an absent name on both sides is not an error', () => {
    const row = tool.judge('gone.test.', 'A',
        [answer({ rcode: 3, records: [], server: OLD })],
        [answer({ rcode: 3, records: [], server: NEW })],
        'test.');
    assert.equal(row, null);
});

test('servers on one side disagreeing with each other is reported', () => {
    const row = tool.judge('www.test.', 'A',
        [answer({ records: [rr('www.test', 'A', '10.0.0.1')], server: 'old1' }),
            answer({ records: [rr('www.test', 'A', '10.0.0.7')], server: 'old2' })],
        [answer({ records: [rr('www.test', 'A', '10.0.0.1')], server: NEW })],
        'test.');
    assert.ok(row.notes.some((note) => note.includes('old servers disagree')));
});

// -- TTL, SOA and apex-NS special cases ---------------------------------------

test('a TTL-only difference is IDENTICAL with a warning, and --strict flips it', () => {
    const row = judgeOf(
        [rr('www.test', 'A', '10.0.0.1')], [rr('www.test', 'A', '10.0.0.1')],
        { oldTtl: 3600, newTtl: 300 });
    assert.equal(row.verdict, 'identical');
    assert.equal(row.ttlDiffers, true);
    assert.ok(row.notes.some((note) => note.includes('TTL differs: old 3600 vs new 300')));
    assert.equal(tool.counts(row, false), false, 'a TTL never fails a normal run');
    assert.equal(tool.counts(row, true), true, '--strict makes it fail');
});

test('an SOA differing ONLY in serial is IDENTICAL, with the serials reported', () => {
    const row = judgeOf(
        [rr('test', 'SOA', 'ns.a. host.a. 34 7200 3600 1209600 300')],
        [rr('test', 'SOA', 'ns.a. host.a. 99 7200 3600 1209600 300')],
        { name: 'test.', type: 'SOA' });
    assert.equal(row.verdict, 'identical');
    assert.ok(row.notes.some((note) => note.includes('serial differs: old 34 vs new 99')));
});

test('an SOA differing in a stable field IS a difference', () => {
    const row = judgeOf(
        [rr('test', 'SOA', 'ns.a. host.a. 34 7200 3600 1209600 300')],
        [rr('test', 'SOA', 'ns.b. host.a. 34 7200 3600 1209600 300')],
        { name: 'test.', type: 'SOA' });
    assert.equal(row.verdict, 'differs');
    assert.equal(tool.counts(row, false), true);
});

test('the apex NS set differing is APEX-NS: expected before a cutover, so no failure', () => {
    const row = judgeOf(
        [rr('test', 'NS', 'a.old.')], [rr('test', 'NS', 'a.new.')],
        { name: 'test.', type: 'NS' });
    assert.equal(row.verdict, 'apex-ns');
    assert.equal(tool.counts(row, false), false);
    assert.equal(tool.counts(row, true), true, '--strict demands the apex NS already match');
});

test('a NON-apex NS set differing is an ordinary difference', () => {
    const row = judgeOf(
        [rr('sub.test', 'NS', 'a.old.')], [rr('sub.test', 'NS', 'a.new.')],
        { name: 'sub.test.', type: 'NS' });
    assert.equal(row.verdict, 'differs');
    assert.equal(tool.counts(row, false), true);
});

// -- zone file ----------------------------------------------------------------

test('zoneFileOwners honours $ORIGIN, @, blank owners and parenthesised SOAs', () => {
    const zoneFile = [
        '$ORIGIN example.com.',
        '$TTL 3600',
        '@   IN SOA ns1.example.com. host.example.com. (',
        '        2026082901 ; serial',
        '        7200 3600 1209600 300 )',
        '    IN NS  ns1.example.com.',
        'www IN A   10.0.0.1',
        'www IN AAAA 2001:db8::1',
        'mail.example.com. IN A 10.0.0.2 ; absolute',
        '; a full comment line',
        'ftp IN CNAME www',
    ].join('\n');
    const owners = tool.zoneFileOwners(zoneFile, 'example.com');
    assert.deepEqual(owners.sort(), [
        'example.com.', 'ftp.example.com.', 'mail.example.com.', 'www.example.com.',
    ]);
});

test('a comment containing a semicolon inside a quoted TXT value does not truncate the owner', () => {
    const owners = tool.zoneFileOwners('_dmarc IN TXT "v=DMARC1; p=none"', 'example.com');
    assert.ok(owners.includes('_dmarc.example.com.'));
});

// -- delegation over an injected resolver --------------------------------------
//
// One fake root -> TLD -> delegation walk, whose leaves each test bends.

const ROOT = tool.ROOT_SERVERS[0];
const TLD = '10.9.0.1';
const NS_A = '10.9.1.1';
const NS_B = '10.9.1.2';

function fakeNetwork({ apexNs = ['ns1.test.example.', 'ns2.test.example.'], lame = [], serials = {}, glue = true, ds = [] } = {}) {
    const parentNs = ['ns1.test.example.', 'ns2.test.example.'];
    const addresses = { 'ns1.test.example.': NS_A, 'ns2.test.example.': NS_B };
    return async function fakeQuery(server, name, type) {
        const zone = 'test.example.';
        if (server === ROOT) {
            return answer({
                aa: false,
                authority: [rr('example.', 'NS', 'a.tld.')],
                additional: [rr('a.tld.', 'A', TLD)],
            });
        }
        if (server === TLD) {
            if (type === 'DS') return answer({ aa: false, records: ds.map((d) => rr(zone, 'DS', d)) });
            return answer({
                aa: false,
                authority: parentNs.map((ns) => rr(zone, 'NS', ns)),
                additional: glue ? parentNs.map((ns) => rr(ns, 'A', addresses[ns])) : [],
            });
        }
        if (server === '1.1.1.1') {
            const address = addresses[tool.normaliseName(name)];
            return answer({ records: address && type === 'A' ? [rr(name, 'A', address)] : [] });
        }
        const nsName = Object.keys(addresses).find((key) => addresses[key] === server);
        if (lame.includes(nsName)) return answer({ aa: false, rcode: 5, records: [] });
        if (type === 'SOA') {
            const serial = serials[nsName] === undefined ? 34 : serials[nsName];
            return answer({ records: [rr(zone, 'SOA', `ns1.test.example. host.test.example. ${serial} 7200 3600 1209600 300`)] });
        }
        if (type === 'NS') return answer({ records: apexNs.map((ns) => rr(zone, 'NS', ns)) });
        return answer({ records: [] });
    };
}

/** Runs the delegation command against a fake network, capturing what it printed. */
async function delegate(network, options = {}) {
    const written = [];
    const original = process.stdout.write;
    process.stdout.write = (text) => { written.push(text); return true; };
    let code;
    try {
        code = await tool.runDelegation('test.example', { timeout: 100, expectNs: null, ...options }, network);
    } finally {
        process.stdout.write = original;
    }
    return { code, output: written.join('') };
}

test('a healthy delegation walks root -> TLD -> zone and reports OK', async () => {
    const { code, output } = await delegate(fakeNetwork());
    assert.match(output, /parent NS ns1\.test\.example\., ns2\.test\.example\./);
    assert.match(output, /sets      parent == apex/);
    assert.match(output, /serials   34 on every authoritative server/);
    assert.match(output, /VERDICT: DELEGATION OK/);
    assert.equal(code, 0);
});

test('a nameserver that refuses the zone is reported LAME and fails', async () => {
    const { code, output } = await delegate(fakeNetwork({ lame: ['ns2.test.example.'] }));
    assert.match(output, /NOT AUTHORITATIVE/);
    assert.match(output, /LAME: no address of ns2\.test\.example\./);
    assert.equal(code, 1);
});

test('an apex NS set the parent does not carry is a MISMATCH and fails', async () => {
    const { code, output } = await delegate(fakeNetwork({
        apexNs: ['ns1.test.example.', 'ns2.test.example.', 'ns3.test.example.'],
    }));
    assert.match(output, /sets      MISMATCH/);
    assert.match(output, /in apex but not at parent: ns3\.test\.example\./);
    assert.equal(code, 1);
});

test('serial skew between authoritative servers is reported and fails', async () => {
    const { code, output } = await delegate(fakeNetwork({ serials: { 'ns2.test.example.': 33 } }));
    assert.match(output, /serials   SKEW/);
    assert.equal(code, 1);
});

test('missing glue for an in-bailiwick nameserver is reported and fails', async () => {
    // The nameservers live inside test.example., so the parent MUST carry glue.
    const { code, output } = await delegate(fakeNetwork({ glue: false }));
    assert.match(output, /missing glue for in-bailiwick nameserver/);
    assert.equal(code, 1);
});

test('a published DS is reported, and its absence is stated rather than assumed', async () => {
    const signed = await delegate(fakeNetwork({ ds: ['12345 13 2 ABCD'] }));
    assert.match(signed.output, /DS        12345 13 2 ABCD/);
    const unsigned = await delegate(fakeNetwork());
    assert.match(unsigned.output, /DS        none published at the parent/);
});

test('--expect-ns fails when the parent publishes something else', async () => {
    const wrong = await delegate(fakeNetwork(), { expectNs: ['ns1.hohenheim.example.'] });
    assert.match(wrong.output, /expected  MISMATCH/);
    assert.equal(wrong.code, 1);
    const right = await delegate(fakeNetwork(), { expectNs: ['ns2.test.example.', 'ns1.test.example.'] });
    assert.match(right.output, /expected  parent NS matches/);
    assert.equal(right.code, 0);
});

// -- propagate ----------------------------------------------------------------

test('matchesExpected ignores TXT quoting and case', () => {
    assert.equal(tool.matchesExpected('"v=spf1 -all"', 'v=spf1 -all'), true);
    assert.equal(tool.matchesExpected('NS1.Example.COM.', 'ns1.example.com.'), true);
    assert.equal(tool.matchesExpected('10.0.0.1', '10.0.0.2'), false);
});

test('propagate returns 0 only once every resolver matches', async () => {
    const network = async (server, name, type) => answer({
        records: [rr(name, type, server === '9.9.9.9' ? '10.0.0.9' : '10.0.0.1')],
    });
    const written = [];
    const original = process.stdout.write;
    process.stdout.write = (text) => { written.push(text); return true; };
    let good, bad;
    try {
        good = await tool.runPropagate('www.test.', 'A',
            { expect: '10.0.0.1', resolvers: ['1.1.1.1', '8.8.8.8'], deadline: 1, interval: 1, timeout: 100 }, network);
        bad = await tool.runPropagate('www.test.', 'A',
            { expect: '10.0.0.1', resolvers: ['1.1.1.1', '9.9.9.9'], deadline: 0, interval: 1, timeout: 100 }, network);
    } finally {
        process.stdout.write = original;
    }
    const output = written.join('');
    assert.equal(good, 0);
    assert.equal(bad, 1);
    assert.match(output, /VERDICT: PROPAGATED/);
    assert.match(output, /VERDICT: NOT PROPAGATED/);
    assert.match(output, /stale/);
});

test('an unserved server produces no row where the answering side has nothing either', () => {
    const row = tool.judge('nothing.test.', 'CAA',
        [answer({ records: [], server: OLD })],
        [answer({ rcode: 5, aa: false, server: NEW })],
        'test.');
    assert.equal(row, null, 'one refusing server must not inflate into a row per question');
});

test('but a total outage on both sides is still an ERROR row', () => {
    const row = tool.judge('www.test.', 'A',
        [answer({ error: 'timeout', server: OLD })],
        [answer({ rcode: 5, aa: false, server: NEW })],
        'test.');
    assert.equal(row.verdict, 'error');
});
