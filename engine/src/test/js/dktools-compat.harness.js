const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const path = require('node:path');
const hook = fs.readFileSync(path.join(__dirname, '../../main/assets/rpgmaker/__dktools_compat.js'), 'utf8');
function run({ index = { locales: {} }, nwjs = false, mode = 0, status = 200 } = {}) {
    let originalCalls = 0;
    let requests = 0;
    const io = { MODE_NWJS_STAMP: 0, mode, _stamp: {}, _loadStamp() { originalCalls++; } };
    const context = vm.createContext({ DKTools: { IO: io }, Utils: { isNwjs: () => nwjs }, console,
        XMLHttpRequest: class {
            open(method, url, async) { assert.equal(url, '__tyranor_internal/dktools-index.json'); assert.equal(async, false); }
            overrideMimeType() {}
            send() { requests++; this.status = status; this.responseText = JSON.stringify(index); }
        }
    });
    vm.runInContext(hook, context);
    vm.runInContext(hook, context); // Injection is idempotent.
    io._loadStamp();
    return { io, originalCalls, requests };
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
console.log('DKTools compatibility harness: 7 scenarios passed');
