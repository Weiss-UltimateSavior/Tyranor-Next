const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const path = require('node:path');
const contract = fs.readFileSync(path.join(__dirname, '../../main/java/com/core/web/DkToolsCompat.kt'), 'utf8');
function constant(name) {
    const match = contract.match(new RegExp('const val ' + name + ' = "([^"]+)"'));
    assert.ok(match, `Missing DKTools host contract ${name}`);
    return match[1];
}
const resourcePath = constant('RESOURCE_PATH');
const hook = fs.readFileSync(path.join(__dirname, '../../main/assets', constant('ASSET_PATH')), 'utf8');
let scenarios = 0;
function run({ index = { locales: {} }, nwjs = false, mode = 0, status = 200, stamp = {} } = {}) {
    scenarios++;
    let originalCalls = 0;
    let requests = 0;
    const io = { MODE_NWJS_STAMP: 0, mode, _stamp: stamp, _loadStamp() { originalCalls++; } };
    const context = vm.createContext({ DKTools: { IO: io }, Utils: { isNwjs: () => nwjs }, console,
        XMLHttpRequest: class {
            open(method, url, async) { assert.equal(method, 'GET'); assert.equal(url, resourcePath); assert.equal(async, false); }
            overrideMimeType() {}
            send() { requests++; this.status = status; this.responseText = JSON.stringify(index); }
        }
    });
    vm.runInContext(hook, context);
    vm.runInContext(hook, context); // Injection is idempotent.
    io._loadStamp();
    return { io, get originalCalls() { return originalCalls; }, get requests() { return requests; } };
}
const generated = run();
assert.equal(generated.originalCalls, 0, 'Missing Stamp must not schedule the original 404 error');
assert.deepEqual(JSON.parse(JSON.stringify(generated.io._stamp)), { locales: {} });
assert.equal(generated.requests, 1);
assert.equal(run({ index: null }).originalCalls, 1, 'Existing game Stamp remains authoritative');
assert.equal(run({ nwjs: true }).requests, 0, 'Native Node IO remains unchanged');
assert.equal(run({ mode: 1 }).requests, 0, 'Standard desktop IO mode remains unchanged');
assert.equal(run({ status: 500 }).originalCalls, 1, 'Failed generation preserves the original error path');
assert.equal(run({ index: [] }).originalCalls, 1, 'Malformed indexes are rejected');
const stamp = { locales: { existing: true } };
const preserved = run({ stamp });
assert.equal(preserved.requests, 0, 'An existing non-empty stamp must not fetch a replacement');
assert.strictEqual(preserved.io._stamp, stamp);
assert.equal(preserved.originalCalls, 1);
assert.equal(run({ mode: 42 }).requests, 0, 'Unknown modes remain owned by the original plugin');
const repeated = run();
const applied = repeated.io._stamp;
repeated.io._loadStamp();
assert.strictEqual(repeated.io._stamp, applied, 'Repeated load preserves its generated stamp');
assert.equal(repeated.originalCalls, 0, 'Repeated load must not schedule the missing original Stamp');
assert.equal(repeated.requests, 1, 'Repeated load reuses its generated index');
const reset = run();
reset.io._stamp = {};
reset.io._loadStamp();
assert.equal(reset.requests, 2, 'Explicitly resetting the stamp must allow a fresh index');
const changedMode = run();
changedMode.io.mode = 1;
changedMode.io._loadStamp();
assert.equal(changedMode.originalCalls, 1, 'Changing mode returns control to the original plugin');
const changedStamp = run();
changedStamp.io._stamp = { game: true };
changedStamp.io._loadStamp();
assert.equal(changedStamp.originalCalls, 1, 'Replacing the stamp returns ownership to the game');
const directory = { __stats__: { type: 'directory' }, 'en.json': { __stats__: { type: 'file', size: 2 } } };
const tolerant = run({ index: { locales: directory } });
assert.equal(tolerant.io._stamp.Locales['EN.json'].__stats__.type, 'file', 'Nested case mismatches resolve before DKTools exists checks');
assert.deepEqual(Object.keys(tolerant.io._stamp.Locales), ['__stats__', 'en.json'], 'Directory enumeration keeps canonical names');
const spacing = run({ index: { img: { '街並み  .png': { __stats__: { type: 'file' } } } } });
assert.equal(spacing.io._stamp.img['街並み\u3000\u3000.png'].__stats__.type, 'file', 'Whitespace classes preserve whitespace count');
assert.equal(spacing.io._stamp.img['街並み.png'].__stats__.type, 'file', 'Trailing whitespace before extension tolerates distribution changes');
const ambiguous = run({ index: { locales: { 'en.json': { exact: 'lower' }, 'EN.json': { exact: 'upper' } } } });
assert.equal(ambiguous.io._stamp.locales['En.json'], undefined, 'Ambiguous case variants must not choose a different language file');
assert.equal(ambiguous.io._stamp.locales['EN.json'].exact, 'upper', 'Exact spelling still wins when loose matches collide');
const originalCase = run({ stamp: { locales: directory } });
const unicodeCase = run({ index: { locales: { 'ΟΣ.json': { language: 'greek' }, 'I.json': { language: 'turkish' }, 'ß.json': { language: 'sharp-s' } } } });
assert.equal(unicodeCase.io._stamp.locales['ος.json'].language, 'greek', 'Simple case matching treats final sigma like the HTTP matcher');
assert.equal(unicodeCase.io._stamp.locales['ı.json'].language, 'turkish', 'Simple case matching treats dotless I like the HTTP matcher');
assert.equal(unicodeCase.io._stamp.locales['SS.json'], undefined, 'Case expansion must not turn one resource into a different filename');
assert.equal(originalCase.io._stamp.Locales, undefined, 'The game-provided stamp keeps its original matching semantics');
const unsafe = run({ index: JSON.parse('{"__proto__":{"injected":true},"constructor":{"injected":true},"prototype":{"injected":true},"locales":{}}') });
assert.equal(unsafe.io._stamp.__proto__, undefined, 'Prototype keys are never resolved as resource aliases');
assert.equal(unsafe.io._stamp.constructor, undefined);
assert.equal(unsafe.io._stamp.prototype, undefined);
assert.equal({}.injected, undefined);
console.log(`DKTools compatibility harness: ${scenarios} scenarios passed`);
