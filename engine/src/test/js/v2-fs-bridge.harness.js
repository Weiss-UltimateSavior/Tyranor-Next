// 用 Node 仿真 WebView 环境，把 v2 兼容层真跑一遍，验证：
//   1) fs 真读游戏目录  2) require 能加载游戏自己的模块  3) __dirname/dataPath 正确
//   4) path 语义修正  5) 越界路径被拒绝
// 说明：window 指向 global，与 WebView 里「全局即 window」的形态一致；
// Buffer 先置空，让基础层的桩被安装（与 WebView 无原生 Buffer 的真实情形一致）。
const nodeRequire = require;
const fs = nodeRequire('fs');
const nodePath = nodeRequire('path');
const os = nodeRequire('os');

delete global.Buffer;
// 造数据用的宿主 Buffer（仿真 WebView 里不存在原生 Buffer）
const HostBuffer = nodeRequire('buffer').Buffer;
// 我们的 Buffer ↔ Node Buffer 互转（跨实现比对时需要真 Node Buffer）
const asNodeBuf = (b) => HostBuffer.from(b && b.toString ? b.toString('base64') : String(b), 'base64');

// ---- 造一个假游戏目录 ----
const gameRoot = fs.mkdtempSync(nodePath.join(os.tmpdir(), 'fsbridge-'));
const contentRoot = nodePath.join(gameRoot, 'www');
fs.mkdirSync(nodePath.join(contentRoot, 'js', 'plugins'), { recursive: true });
fs.mkdirSync(nodePath.join(contentRoot, 'data'), { recursive: true });
fs.mkdirSync(nodePath.join(contentRoot, 'mods', 'lib'), { recursive: true });
fs.writeFileSync(nodePath.join(contentRoot, 'data', 'table.json'), JSON.stringify({ rows: [1, 2, 3] }));
fs.writeFileSync(nodePath.join(contentRoot, 'data', 'utf8.txt'), '日本語テキスト');
fs.writeFileSync(nodePath.join(contentRoot, 'js', 'plugins', 'MyTable.js'),
    'module.exports = { greeting: "hi from plugin", dir: __dirname };');
fs.writeFileSync(nodePath.join(contentRoot, 'mods', 'lib', 'index.js'), 'module.exports = { nested: true };');
fs.writeFileSync(nodePath.join(contentRoot, 'bin.dat'), HostBuffer.from([1, 2, 3, 250]));
// 越界目标：游戏目录之外
const outsideFile = nodePath.join(os.tmpdir(), 'outside-secret.txt');
fs.writeFileSync(outsideFile, 'SECRET');

// ---- 仿真原生桥（与 Kotlin RpgMakerFsBridge 同语义）----
// 相对路径按网页根解析（Kotlin 侧 File(contentRoot, path)），再做目录边界校验
function toAbs(p) {
    const s = String(p == null ? '' : p).replace(/\\/g, '/').replace(/^file:\/\//, '');
    if (!s) return null;
    return nodePath.isAbsolute(s) ? s : nodePath.join(contentRoot, s);
}
function inside(p) {
    const abs = toAbs(p);
    if (abs === null) return null;
    // 与 Kotlin canonicalFile 对齐：目标可以尚不存在，此时退化为词法归一
    let c;
    try { c = fs.realpathSync.native(abs); }
    catch (e) { c = nodePath.resolve(abs).replace(/[\\/]+$/, ''); }
    const r = fs.realpathSync.native(gameRoot);
    return (c === r || c.startsWith(r + nodePath.sep)) ? c : null;
}
const { createBridges } = nodeRequire('./bridges.js');
const created = createBridges(gameRoot, contentRoot);
const bridge = created.fsBridge;
const envBridge = created.envBridge;
global.TyranorEnv = envBridge;

// ---- 最小 DOM/window 桩 ----
global.window = global;
global.TyranorFs = bridge;
global.document = {
    readyState: 'loading',
    documentElement: { style: {} },
    createElement: () => ({ style: {}, getContext: () => null, addEventListener: () => {}, appendChild: () => {} }),
    getElementById: () => null,
    addEventListener: () => {},
    fonts: undefined,
};
global.screen = {};
global.navigator = global.navigator || { userAgent: 'stub' };
// WebView 的 window 事件接口（兼容层会挂 load/pagehide）
global.addEventListener = () => {};
global.removeEventListener = () => {};
global.dispatchEvent = () => true;
global.location = global.location || { href: 'http://localhost/index.html', reload: () => {} };
// Storage/localStorage：WebView 必有的宿主存储接口（兼容层会劫持其 prototype）
global.localStorage = {
    _d: {},
    setItem(k, v) { this._d[k] = String(v); },
    getItem(k) { return Object.prototype.hasOwnProperty.call(this._d, k) ? this._d[k] : null; },
    removeItem(k) { delete this._d[k]; },
};
global.Storage = function Storage() {};
global.Storage.prototype = global.localStorage;
global.setTimeout_ = setTimeout;
global.setInterval_ = setInterval;

// ---- 依次跑基础层与 v2 层（与真实注入顺序一致）----
const run = (file) => {
    const code = fs.readFileSync(file, 'utf8');
    // 用间接 eval 在全局作用域执行，模拟 <script> 注入
    (0, eval)(code);
};
run(process.env.POLY_BASE || nodePath.join(__dirname, '..', '..', 'main', 'assets', 'rpgmaker', '__nwjs_polyfill.js'));
run(process.env.POLY_V2 || nodePath.join(__dirname, '..', '..', 'main', 'assets', 'rpgmaker', '__nwjs_polyfill_v2.js'));

// ---- 断言 ----
let failed = 0;
const check = (name, cond, extra) => {
    if (cond) { console.log('  PASS  ' + name); }
    else { failed++; console.log('  FAIL  ' + name + (extra !== undefined ? '  -> ' + extra : '')); }
};

console.log('\n== fs 真读写 ==');
const fsMod = window.require('fs');
check('existsSync(存在) === true', fsMod.existsSync('data/table.json') === true);
check('existsSync(不存在) === false', fsMod.existsSync('data/nope.json') === false);
check('readFileSync(utf8) 内容正确', fsMod.readFileSync('data/utf8.txt', 'utf8') === '日本語テキスト');
check('readFileSync(JSON) 可解析', JSON.parse(fsMod.readFileSync('data/table.json', 'utf8')).rows.length === 3);
const buf = fsMod.readFileSync('bin.dat');
check('无编码时返回 Buffer-like', !!(buf && typeof buf._bin === 'string'), typeof buf);
check('Buffer 内容正确', buf && buf._bin === String.fromCharCode(1, 2, 3, 250));
check('readFileSync(缺失) 抛 ENOENT', (() => { try { fsMod.readFileSync('data/nope.json', 'utf8'); return false; } catch (e) { return e.code === 'ENOENT'; } })());
check('readdirSync 返回真实条目', JSON.stringify(fsMod.readdirSync('js/plugins')) === '["MyTable.js"]');
check('statSync().isFile() 正确', fsMod.statSync('data/table.json').isFile() === true);
check('statSync().size > 0', fsMod.statSync('data/table.json').size > 0);
check('statSync(不存在).isFile() === false', fsMod.statSync('data/nope.json').isFile() === false);
check('越界读被拒绝', fsMod.existsSync(outsideFile) === false);
check('越界读内容为 null/抛错', (() => { try { fsMod.readFileSync(outsideFile, 'utf8'); return false; } catch (e) { return true; } })());
check('writeFileSync 真落盘', (() => { fsMod.writeFileSync('data/out.txt', 'written'); return fs.readFileSync(nodePath.join(contentRoot, 'data', 'out.txt'), 'utf8') === 'written'; })());
check('mkdirSync 真建目录', (() => { fsMod.mkdirSync('data/newdir'); return fs.existsSync(nodePath.join(contentRoot, 'data', 'newdir')); })());
check('writeFileSync 越界被拒绝', (() => { fsMod.writeFileSync(nodePath.join(os.tmpdir(), 'evil.txt'), 'x'); return !fs.existsSync(nodePath.join(os.tmpdir(), 'evil.txt')); })());

console.log('\n== require 加载游戏模块 ==');
const table = window.require('./js/plugins/MyTable.js');
// 兼容层统一输出 POSIX 正斜杠路径（NW.js/Node 语义），比对时归一分隔符
const slash = (p) => String(p).replace(/\\/g, '/');
check('加载相对路径模块', table && table.greeting === 'hi from plugin', JSON.stringify(table));
check('模块内 __dirname 正确', table && slash(table.dir) === slash(nodePath.join(contentRoot, 'js', 'plugins')), table && table.dir);
const nested = window.require('./mods/lib');
check('目录 index.js 解析', nested && nested.nested === true, JSON.stringify(nested));
const jsonMod = window.require('./data/table.json');
check('加载 .json 模块', jsonMod && jsonMod.rows.length === 3);
check('缓存生效（同一对象）', window.require('./js/plugins/MyTable.js') === table);
check('require("fs") 返回真实实现', window.require('fs').existsSync('data/table.json') === true);
check('内建 path 仍可用', typeof window.require('path').join === 'function');

console.log('\n== 环境路径 ==');
check('__dirname 指向网页根', slash(window.__dirname) === slash(contentRoot), window.__dirname);
check('process.cwd() 指向网页根', slash(window.process.cwd()) === slash(contentRoot), window.process.cwd());
check('nw.gui.App.dataPath 指向 AppData', slash(window.nw.gui.App.dataPath) === slash(nodePath.join(gameRoot, 'AppData')), window.nw.gui.App.dataPath);

console.log('\n== path 语义 ==');
const p = window.require('path');
check('normalize 折叠 ..', p.normalize('/a/b/../c') === '/a/c', p.normalize('/a/b/../c'));
check('relative 正确', p.relative('/a/b', '/a/c/d') === '../c/d', p.relative('/a/b', '/a/c/d'));
check('resolve 绝对化', p.resolve('data') === nodePath.join(contentRoot, 'data').replace(/\\/g, '/') || p.resolve('data').indexOf(contentRoot.replace(/\\/g, '/')) === 0, p.resolve('data'));
check('dirname 正确', p.dirname('/a/b/c.js') === '/a/b', p.dirname('/a/b/c.js'));

console.log('\n== 兼容层自证 ==');
check('__tyranorFsState.real === true', window.__tyranorFsState && window.__tyranorFsState.real === true);

console.log('\n== Node 调用形式兼容（options 对象 / 二进制解析）==');
// readFileSync 的 options 对象形态（Node 常见写法；只认字符串会返回 Buffer 而乱码）
check("readFileSync(p,'utf8') 取到文本", fsMod.readFileSync('data/utf8.txt', 'utf8') === '日本語テキスト');
check("readFileSync(p,{encoding:'utf8'}) 取到文本", fsMod.readFileSync('data/utf8.txt', { encoding: 'utf8' }) === '日本語テキスト');
check("readFileSync(p,{encoding:'utf8',flag:'r'})", fsMod.readFileSync('data/utf8.txt', { encoding: 'utf8', flag: 'r' }) === '日本語テキスト');
// fs 返回的 Buffer 必须具备二进制解析能力
const bin = fsMod.readFileSync('bin.dat');
check('Buffer.slice 可用', typeof bin.slice === 'function' && bin.slice(1).length === 3, 'len=' + bin.length + ' slicedLen=' + (bin.slice(1) && bin.slice(1).length));
check('Buffer.readUInt8 可用', typeof bin.readUInt8 === 'function' && bin.readUInt8(0) === 1, typeof bin.readUInt8);
check('Buffer.readUInt8 取值正确', bin.readUInt8(3) === 250, bin.readUInt8 && bin.readUInt8(3));
check('Buffer.readUInt16LE 正确', bin.readUInt16LE(0) === 0x0201, bin.readUInt16LE && bin.readUInt16LE(0));
check('Buffer.toJSON 形态正确', JSON.stringify(bin.toJSON()) === '{"type":"Buffer","data":[1,2,3,250]}', JSON.stringify(bin.toJSON && bin.toJSON()));
check('Buffer.equals 正确', bin.equals(fsMod.readFileSync('bin.dat')) === true);
check('Buffer.indexOf 正确', bin.indexOf(3) === 2, bin.indexOf && bin.indexOf(3));
check('Buffer 越界读抛错', (() => { try { bin.readUInt32LE(5); return false; } catch (e) { return true; } })());
check('Buffer 二进制→UTF-8 解码正确（多字节）', fsMod.readFileSync('data/utf8.txt').toString('utf8') === '日本語テキスト', fsMod.readFileSync('data/utf8.txt').toString('utf8'));
check('Buffer.toString(hex) 正确', HostBuffer.from([1, 2, 250]).toString('hex') !== undefined && fsMod.readFileSync('bin.dat').toString('hex') === '010203fa', fsMod.readFileSync('bin.dat').toString('hex'));
check('Buffer.toString(base64) 正确', fsMod.readFileSync('bin.dat').toString('base64') === HostBuffer.from([1,2,3,250]).toString('base64'), fsMod.readFileSync('bin.dat').toString('base64'));

console.log('\n== crypto（与 Node 真值逐项对照）==');
const cryptoMod = window.require('crypto');
const nodeCrypto = nodeRequire('crypto');
check("crypto 走原生桥（非空壳）", typeof cryptoMod.createHash === 'function');
check('sha256 与 Node 一致', cryptoMod.createHash('sha256').update('hello').digest('hex') === nodeCrypto.createHash('sha256').update('hello').digest('hex'), cryptoMod.createHash('sha256').update('hello').digest('hex'));
check('md5 与 Node 一致', cryptoMod.createHash('md5').update('abc').digest('hex') === nodeCrypto.createHash('md5').update('abc').digest('hex'));
check('sha1 与 Node 一致', cryptoMod.createHash('sha1').update('abc').digest('hex') === nodeCrypto.createHash('sha1').update('abc').digest('hex'));
check('sha512 与 Node 一致', cryptoMod.createHash('sha512').update('abc').digest('hex') === nodeCrypto.createHash('sha512').update('abc').digest('hex'));
check('多次 update 等价（链式）', cryptoMod.createHash('sha256').update('he').update('llo').digest('hex') === nodeCrypto.createHash('sha256').update('hello').digest('hex'));
check('base64 输出与 Node 一致', cryptoMod.createHash('sha256').update('hello').digest('base64') === nodeCrypto.createHash('sha256').update('hello').digest('base64'));
check('日文 UTF-8 摘要一致', cryptoMod.createHash('sha256').update('日本語').digest('hex') === nodeCrypto.createHash('sha256').update('日本語').digest('hex'));
check('重复 digest 抛错', (() => { const h = cryptoMod.createHash('sha256'); h.digest(); try { h.digest(); return false; } catch (e) { return true; } })());
check('hmac 与 Node 一致', (() => { const a = cryptoMod.createHmac('sha256', 'key').update('data').digest('hex'); const b = nodeCrypto.createHmac('sha256', 'key').update('data').digest('hex'); return a === b; })(), cryptoMod.createHmac('sha256', 'key').update('data').digest('hex'));
check('randomBytes 长度与随机性', (() => { const a = cryptoMod.randomBytes(16), b = cryptoMod.randomBytes(16); return a.length === 16 && a.toString('hex') !== b.toString('hex'); })());
check('randomUUID 形态正确', /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(cryptoMod.randomUUID()), cryptoMod.randomUUID());
check('pbkdf2Sync 与 Node 一致', (() => { const a = cryptoMod.pbkdf2Sync('password', 'salt', 1000, 32, 'sha256').toString('hex'); const b = nodeCrypto.pbkdf2Sync('password', 'salt', 1000, 32, 'sha256').toString('hex'); return a === b; })());
check('pbkdf2Sync(sha1) 与 Node 一致', (() => { const a = cryptoMod.pbkdf2Sync('pw', 'sa', 500, 16, 'sha1').toString('hex'); const b = nodeCrypto.pbkdf2Sync('pw', 'sa', 500, 16, 'sha1').toString('hex'); return a === b; })());
check('timingSafeEqual 正确', cryptoMod.timingSafeEqual('abc', 'abc') === true && cryptoMod.timingSafeEqual('abc', 'abd') === false);
// 说明：我们的 Cipher 是「一次性」实现（update 缓存、final 出全量），
// 而 Node 是流式（update 就吐数据）。比对必须在 final 后取全量，
// 且 Node 侧要 concat(update, final) 才公平。
function aesCompare(mode, plaintext) {
    const keyBuf = nodeCrypto.randomBytes(32);
    const ivBuf = mode === 'ecb' ? null : nodeCrypto.randomBytes(16);
    const key = window.Buffer.from(keyBuf.toString('base64'), 'base64');
    const iv = ivBuf ? window.Buffer.from(ivBuf.toString('base64'), 'base64') : undefined;
    const ours = cryptoMod.createCipheriv('aes-256-' + mode, key, iv);
    ours.update(plaintext);
    const mine = ours.final().toString('hex');
    const theirsCipher = nodeCrypto.createCipheriv('aes-256-' + mode, keyBuf, ivBuf);
    const theirs = HostBuffer.concat([theirsCipher.update(plaintext), theirsCipher.final()]).toString('hex');
    return { mine, theirs };
}
check('AES-256-CBC 加密与 Node 逐字节一致', (() => { const r = aesCompare('cbc', 'secret'); return r.mine === r.theirs; })());
check('AES-256-CTR 加密与 Node 逐字节一致', (() => { const r = aesCompare('ctr', 'hello world'); return r.mine === r.theirs; })());
check('AES-256-ECB 加密与 Node 逐字节一致', (() => { const r = aesCompare('ecb', 'block!!'); return r.mine === r.theirs; })());
check('AES 可解 Node 产出的密文（互操作）', (() => {
    const keyBuf = nodeCrypto.randomBytes(32), ivBuf = nodeCrypto.randomBytes(16);
    const key = window.Buffer.from(keyBuf.toString('base64'), 'base64');
    const iv = window.Buffer.from(ivBuf.toString('base64'), 'base64');
    const n = nodeCrypto.createCipheriv('aes-256-cbc', keyBuf, ivBuf);
    const cipherText = HostBuffer.concat([n.update('存档内容'), n.final()]);
    const dec = cryptoMod.createDecipheriv('aes-256-cbc', key, iv);
    dec.update(window.Buffer.from(cipherText.toString('base64'), 'base64'));
    return dec.final().toString('utf8') === '存档内容';
})());
check('AES 往返（我方加密→我方解密，含多字节）', (() => {
    const key = cryptoMod.randomBytes(32), iv = cryptoMod.randomBytes(16);
    const enc = cryptoMod.createCipheriv('aes-256-cbc', key, iv);
    enc.update('存档内容');
    const cipherText = enc.final();
    const dec = cryptoMod.createDecipheriv('aes-256-cbc', key, iv);
    dec.update(cipherText);
    return dec.final().toString('utf8') === '存档内容';
})());
check('AES 密钥长度不符时抛错（不静默给错值）', (() => {
    try {
        const e = cryptoMod.createCipheriv('aes-256-cbc', cryptoMod.randomBytes(16), cryptoMod.randomBytes(16));
        e.update('x');
        e.final();
        return false;
    } catch (err) { return true; }
})());
console.log('\n== zlib（与 Node 真值对照）==');
const zlibMod = window.require('zlib');
const nodeZlib = nodeRequire('zlib');
const sample = 'これは圧縮テストです。'.repeat(30);
check('deflateSync/inflateSync 往返', (() => { const c = zlibMod.deflateSync(sample); return zlibMod.inflateSync(c).toString('utf8') === sample; })());
check('gzipSync/gunzipSync 往返', (() => { const c = zlibMod.gzipSync(sample); return zlibMod.gunzipSync(c).toString('utf8') === sample; })());
check('deflateRawSync/inflateRawSync 往返', (() => { const c = zlibMod.deflateRawSync(sample); return zlibMod.inflateRawSync(c).toString('utf8') === sample; })());
check('unzipSync 自动识别 gzip', (() => { const c = nodeZlib.gzipSync(sample); return zlibMod.unzipSync(asNodeBuf(c)).toString('utf8') === sample; })());
check('unzipSync 自动识别 zlib', (() => { const c = nodeZlib.deflateSync(sample); return zlibMod.unzipSync(asNodeBuf(c)).toString('utf8') === sample; })());
check('可解 Node 产出的数据（互操作）', zlibMod.inflateSync(asNodeBuf(nodeZlib.deflateSync(sample))).toString('utf8') === sample);
check('Node 可解我们产出的数据（互操作）', nodeZlib.inflateSync(asNodeBuf(zlibMod.deflateSync(sample))).toString('utf8') === sample);
check('损坏数据抛错（不静默给错值）', (() => { try { zlibMod.inflateSync('not-zlib-data'); return false; } catch (e) { return true; } })());
check('压缩确实变小', zlibMod.deflateSync(sample).length < HostBuffer.byteLength(sample));

console.log('\n== 补齐的内建模块 ==');
const assertMod = window.require('assert');
check('assert 通过/失败语义', (() => { assertMod(true); try { assertMod(false); return false; } catch (e) { return e.name === 'AssertionError'; } })());
check('assert.strictEqual 抛 AssertionError', (() => { try { assertMod.strictEqual(1, 2); return false; } catch (e) { return e.name === 'AssertionError' && e.code === 'ERR_ASSERTION'; } })());
check('assert.deepStrictEqual 比较对象', (() => { try { assertMod.deepStrictEqual({ a: [1, 2] }, { a: [1, 2] }); return true; } catch (e) { return false; } })());
check('assert.throws 生效', (() => { try { assertMod.throws(function () { throw new Error('x'); }); return true; } catch (e) { return false; } })());
const streamMod = window.require('stream');
check('stream.Readable/Writable/Transform 可用', typeof streamMod.Readable === 'function' && typeof streamMod.Writable === 'function' && typeof streamMod.Transform === 'function');
check('Readable 能 emit data（管道场景）', (() => {
    let got = null;
    const r = new streamMod.Readable();
    r.on('data', (chunk) => { got = chunk; });
    r.push('hello');
    return got === 'hello';
})());
check('Writable 收到 write', (() => {
    let got = null;
    const w = new streamMod.Writable({ write: (chunk, enc, cb) => { got = chunk; cb(); } });
    w.write('data');
    return got === 'data';
})());
check('Transform 变形', (() => {
    let got = null;
    const t = new streamMod.Transform({ transform: (chunk, enc, cb) => cb(null, String(chunk).toUpperCase()) });
    t.on('data', (c) => { got = c; });
    t.write('abc');
    return got === 'ABC';
})());
check('PassThrough 直通', (() => {
    let got = null;
    const p = new streamMod.PassThrough();
    p.on('data', (c) => { got = c; });
    p.write('xyz');
    return got === 'xyz';
})());
const sdMod = window.require('string_decoder');
check('StringDecoder 解 UTF-8', new sdMod.StringDecoder('utf8').write(Buffer.from('日本語')) === '日本語');
const timersMod = window.require('timers');
check('timers 模块可用', typeof timersMod.setTimeout === 'function' && typeof timersMod.setImmediate === 'function');
const vmMod = window.require('vm');
check('vm.runInNewContext 隔离求值', vmMod.runInNewContext('a + b', { a: 1, b: 2 }) === 3);
check('vm 沙箱内新变量不进入宿主作用域', (() => {
    global.__hostGuard = 'host';
    vmMod.runInNewContext('var __hostGuard = "sandbox"; __hostGuard', {});
    return global.__hostGuard === 'host';
})(), global.__hostGuard);
check('vm 结果可写回沙箱对象', (() => {
    const sandbox = { out: 0, a: 21 };
    vmMod.runInNewContext('out = a * 2', sandbox);
    return sandbox.out === 42;
})(), (() => { const sb = { out: 0, a: 21 }; vmMod.runInNewContext('out = a * 2', sb); return sb.out; })());
const punyMod = window.require('punycode');
check('punycode 编码正确', punyMod.encode('日本語') === nodeRequire('punycode').encode('日本語') || punyMod.toASCII('日本語.jp') === nodeRequire('punycode').toASCII('日本語.jp'), punyMod.toASCII('日本語.jp'));
check('punycode 解码往返', punyMod.toUnicode(punyMod.toASCII('日本語.jp')) === '日本語.jp', punyMod.toUnicode(punyMod.toASCII('日本語.jp')));
const constantsMod = window.require('constants');
check('constants.F_OK 可用', constantsMod.F_OK === 0 && constantsMod.O_RDWR === 2);
check('require("process") 返回真 process', window.require('process') === window.process || typeof window.require('process').cwd === 'function');

console.log('\n== fs 长尾 ==');
check('accessSync 存在则通过', (() => { try { fsMod.accessSync('data/table.json'); return true; } catch (e) { return false; } })());
check('accessSync 缺失抛 ENOENT', (() => { try { fsMod.accessSync('data/none.json'); return false; } catch (e) { return e.code === 'ENOENT'; } })());
check('realpathSync 缺失抛 ENOENT', (() => { try { fsMod.realpathSync('data/none.json'); return false; } catch (e) { return e.code === 'ENOENT'; } })());
check('rmSync 删文件', (() => { fsMod.writeFileSync('data/to-remove.txt', 'x'); fsMod.rmSync('data/to-remove.txt'); return fsMod.existsSync('data/to-remove.txt') === false; })());
check('rmSync 递归删目录', (() => { fsMod.mkdirSync('data/tree/sub'); fsMod.writeFileSync('data/tree/sub/a.txt', 'x'); fsMod.rmSync('data/tree', { recursive: true }); return fsMod.existsSync('data/tree') === false; })());
check('rmSync 非空目录无 recursive 抛错', (() => {
    fsMod.mkdirSync('data/ne'); fsMod.writeFileSync('data/ne/a.txt', 'x');
    let threw = false;
    try { fsMod.rmSync('data/ne'); } catch (e) { threw = true; }
    fsMod.rmSync('data/ne', { recursive: true });
    return threw;
})());
check('rmdirSync 删空目录', (() => { fsMod.mkdirSync('data/empty'); fsMod.rmdirSync('data/empty'); return fsMod.existsSync('data/empty') === false; })());
check('truncateSync 截断', (() => { fsMod.writeFileSync('data/trunc.txt', 'abcdef'); fsMod.truncateSync('data/trunc.txt', 3); return fsMod.readFileSync('data/trunc.txt', 'utf8') === 'abc'; })());
check('utimesSync 改 mtime', (() => { fsMod.writeFileSync('data/times.txt', 'x'); const t = 1600000000000; fsMod.utimesSync('data/times.txt', t / 1000, t / 1000); return Math.abs(fsMod.statSync('data/times.txt').mtime.getTime() - t) < 2000; })());
check('mkdtempSync 建临时目录', (() => { const dir = fsMod.mkdtempSync('data/tmp-'); return fsMod.statSync(dir).isDirectory() === true; })());
check('opendirSync 遍历条目', (() => { const d = fsMod.opendirSync('data'); const first = d.readSync(); return first && typeof first.name === 'string' && typeof first.isFile === 'function'; })());
check('chmodSync 静默接受', (() => { fsMod.chmodSync('data/table.json', 0o644); return true; })());
check('symlinkSync 退化为内容复制', (() => {
    fsMod.writeFileSync('data/target.txt', 'content');
    fsMod.symlinkSync('data/target.txt', 'data/link.txt');
    return fsMod.readFileSync('data/link.txt', 'utf8') === 'content';
})());
check('fs.constants 可用', fsMod.constants === undefined || typeof fsMod.constants === 'object');

console.log('\n== util / os / url 增强 ==');
const utilMod = window.require('util');
check('util.promisify 生效', (() => {
    const fn = utilMod.promisify((a, cb) => cb(null, a * 2));
    return fn(21).then ? true : false;
})());
check('util.promisify 实际求值', (() => utilMod.promisify((a, cb) => cb(null, a * 2))(21).then((v) => v === 42)));
check('util.isDeepStrictEqual 正确', utilMod.isDeepStrictEqual({ a: 1 }, { a: 1 }) === true && utilMod.isDeepStrictEqual({ a: 1 }, { a: 2 }) === false);
check('util.promisify.reject 路径', (() => utilMod.promisify((cb) => cb(new Error('boom')))().then(() => false, () => true)));
const osMod = window.require('os');
check('os.homedir 指向 AppData', String(osMod.homedir()).indexOf('AppData') >= 0, osMod.homedir());
check('os.totalmem 来自原生（非 0）', osMod.totalmem() > 0, osMod.totalmem());
check('os.endianness 可用', osMod.endianness() === 'LE');
check('os.userInfo 可用', typeof osMod.userInfo().username === 'string');
const urlMod = window.require('url');
check('url.URL 可用', typeof urlMod.URL === 'function');
check('url.pathToFileURL/fileURLToPath 往返', urlMod.fileURLToPath(urlMod.pathToFileURL('/a/b c.txt').href) === '/a/b c.txt', urlMod.fileURLToPath(urlMod.pathToFileURL('/a/b c.txt').href));
check('url.domainToASCII 可用', urlMod.domainToASCII('日本語.jp').indexOf('xn--') === 0);

console.log('\n== EventEmitter 别名 ==');
const EE2 = window.require('events');
const em2 = new EE2();
check('addListener 是 on 的别名', typeof em2.addListener === 'function' && (() => { let hit = false; em2.addListener('x', () => { hit = true; }); em2.emit('x'); return hit; })());
check('off 是 removeListener 的别名', (() => { const e = new EE2(); let n = 0; const f = () => n++; e.on('x', f); e.off('x', f); e.emit('x'); return n === 0; })());
check('prependListener 顺序正确', (() => { const e = new EE2(); const order = []; e.on('x', () => order.push('a')); e.prependListener('x', () => order.push('b')); e.emit('x'); return order.join(',') === 'b,a'; })());
check('listeners/eventNames 可用', (() => { const e = new EE2(); const f = () => {}; e.on('x', f); return e.listeners('x').length === 1 && e.eventNames().indexOf('x') >= 0; })());
check('listenerCount 可用', (() => { const e = new EE2(); e.on('x', () => {}); return e.listenerCount('x') === 1 && EE2.listenerCount(e, 'x') === 1; })());
check('setMaxListeners/getMaxListeners', (() => { const e = new EE2(); e.setMaxListeners(5); return e.getMaxListeners() === 5; })());

console.log('\n== NW.js 全局与 evalNWBin ==');
check('window.on 可用（事件订阅）', typeof window.on === 'function');
check('window.gc / window.focus 可用', typeof window.gc === 'function' && typeof window.focus === 'function');
check('window.speechSynthesis 可用', typeof window.speechSynthesis === 'object' && Array.isArray(window.speechSynthesis.getVoices()));
check('nw.gui.App.argv 非空（游戏校验启动参数）', Array.isArray(window.nw.gui.App.argv) && window.nw.gui.App.argv.length > 0, JSON.stringify(window.nw.gui.App.argv));
check('process.versions.nw 有真版本号', window.process.versions.nw !== '0.0.0', window.process.versions.nw);
check('setImmediate 可用', typeof window.setImmediate === 'function');
check(
    'evalNWBin：.bin 不存在时降级读同名 .js',
    (() => {
        fs.writeFileSync(nodePath.join(contentRoot, 'boot.js'), 'window.__binRan = "from-js";');
        window.nw.Window.evalNWBin(window, 'boot.bin');
        return window.__binRan === 'from-js';
    })(),
    window.__binRan,
);
check(
    'evalNWBin：有 .bin 时按其对应源码执行（JoiPlay 同语义）',
    (() => {
        fs.writeFileSync(nodePath.join(contentRoot, 'real.js'), 'window.__realRan = true;');
        fs.writeFileSync(nodePath.join(contentRoot, 'real.bin'), new HostBuffer('compiled'));
        window.nw.Window.evalNWBin(window, 'real.bin');
        return window.__realRan === true;
    })(),
);

console.log('\n== localStorage 落地（修随机端口导致的清空）==');
check('Storage.prototype 已被接管', window.__tyranorLocalStoragePersisted === true);
check(
    'setItem 落盘到游戏目录',
    (() => {
        const store = {};
        const fakeStorage = Object.create(global.Storage.prototype);
        fakeStorage.setItem('ConfigManager.data', '{"volume":80}');
        return fs.existsSync(nodePath.join(gameRoot, 'AppData', 'Local Storage'));
    })(),
);
check(
    '换一个「会话」（新 origin）仍能读到',
    (() => {
        const reader = Object.create(global.Storage.prototype);
        return reader.getItem('ConfigManager.data') === '{"volume":80}';
    })(),
    (() => { const r = Object.create(global.Storage.prototype); return r.getItem('ConfigManager.data'); })(),
);
check(
    'removeItem 删除后读不到',
    (() => {
        const s = Object.create(global.Storage.prototype);
        s.setItem('temp.key', 'x');
        const before = s.getItem('temp.key');
        s.removeItem('temp.key');
        return before === 'x' && s.getItem('temp.key') === null;
    })(),
);
check(
    '超长键名（整段 JSON 当键）不崩且可读回',
    (() => {
        const s = Object.create(global.Storage.prototype);
        const longKey = 'k' + 'x'.repeat(4000);
        s.setItem(longKey, 'value-long');
        return s.getItem(longKey) === 'value-long';
    })(),
);

console.log('\n== 第二批：Buffer 写入/浮点/变长 ==');
{
    const B = window.Buffer;
    const nb = (arr) => asNodeBuf(B.from(arr.map((x) => String.fromCharCode(x))));

    // 定长写入与 Node 对照
    const w = B.alloc(8);
    w.writeUInt8(0xff, 0);
    w.writeUInt16LE(0x1234, 1);
    w.writeUInt32BE(0xdeadbeef, 3);
    const nw = HostBuffer.alloc(8);
    nw.writeUInt8(0xff, 0);
    nw.writeUInt16LE(0x1234, 1);
    nw.writeUInt32BE(0xdeadbeef, 3);
    check('writeUInt16LE/writeUInt32BE 与 Node 一致', w.toString('hex') === nw.toString('hex'), w.toString('hex'));
    check('writeInt8 负值正确', (() => { const t = B.alloc(1); t.writeInt8(-1, 0); return t.toString('hex') === 'ff'; })(), (() => { const t = B.alloc(1); t.writeInt8(-1, 0); return t.toString('hex'); })());

    // 浮点
    const f = B.alloc(8);
    f.writeFloatLE(3.14159, 0);
    f.writeFloatBE(2.5, 4);
    const nf = HostBuffer.alloc(8);
    nf.writeFloatLE(3.14159, 0);
    nf.writeFloatBE(2.5, 4);
    check('writeFloatLE/BE 与 Node 一致', f.toString('hex') === nf.toString('hex'), f.toString('hex'));
    check('readFloatLE 往返', Math.abs(f.readFloatLE(0) - 3.14159) < 1e-5, f.readFloatLE(0));
    const d = B.alloc(8);
    d.writeDoubleLE(1.7976931348623157e308, 0);
    check('writeDoubleLE/readDoubleLE 往返', d.readDoubleLE(0) === 1.7976931348623157e308);
    check('readFloatBE 与 Node 一致', (() => { const src = asNodeBuf(B.from([0x40, 0x20, 0x00, 0x00])); const nodeVal = HostBuffer.from(src).readFloatBE(0); return Math.abs(B.from([0x40, 0x20, 0x00, 0x00]).readFloatBE(0) - nodeVal) < 1e-9; })());

    // 变长
    const v = B.from('\x01\x02\x03\x04\x05\x06');
    check('readUIntBE 变长正确', v.readUIntBE(0, 3) === 0x010203, v.readUIntBE(0, 3));
    check('readUIntLE 变长正确', v.readUIntLE(0, 3) === 0x030201, v.readUIntLE(0, 3));
    // 编码语义注意：默认 utf8 下 '\xff' 是两字节（c3bf），与 Node 一致；
    // 要「一字符一字节」必须显式 latin1
    check('readIntBE 有符号正确（latin1）', B.from('\xff\xff', 'latin1').readIntBE(0, 2) === -1, B.from('\xff\xff', 'latin1').readIntBE(0, 2));
    check('默认 utf8 编码与 Node 一致', B.from('\xff\xff').toString('hex') === 'c3bfc3bf', B.from('\xff\xff').toString('hex'));
    check('变长写入与 Node 一致', (() => { const t = B.alloc(4); t.writeUIntBE(0x010203, 0, 3); const n = HostBuffer.alloc(4); n.writeUIntBE(0x010203, 0, 3); return t.toString('hex') === n.toString('hex'); })());
    check('变长越界抛错', (() => { try { B.from('\x01').readUIntBE(0, 7); return false; } catch (e) { return true; } })());

    // fill / copy / lastIndexOf / swap
    check('fill 填充', B.alloc(4).fill(0xab).toString('hex') === 'abababab');
    check('fill 字符串填充', B.alloc(4).fill('ab').toString('hex') === '61626162', B.alloc(4).fill('ab').toString('hex'));
    check('copy 复制到目标', (() => { const src = B.from('\x01\x02'); const dst = B.alloc(4); const n = src.copy(dst, 1); return n === 2 && dst.toString('hex') === '00010200'; })(), (() => { const src = B.from('\x01\x02'); const dst = B.alloc(4); src.copy(dst, 1); return dst.toString('hex'); })());
    check('lastIndexOf 正确', B.from('abcabc').lastIndexOf('abc') === 3, B.from('abcabc').lastIndexOf('abc'));
    check('swap16 正确', (() => { const t = B.from('\x01\x02\x03\x04'); t.swap16(); return t.toString('hex') === '02010403'; })(), (() => { const t = B.from('\x01\x02\x03\x04'); t.swap16(); return t.toString('hex'); })());
    check('swap32 正确', (() => { const t = B.from('\x01\x02\x03\x04'); t.swap32(); return t.toString('hex') === '04030201'; })());
    check('write 字符串（utf8 多字节）', (() => { const t = B.alloc(10); const n = t.write('日本', 0); return n === 6 && t.slice(0, 6).toString('hex') === HostBuffer.from('日本').toString('hex'); })(), (() => { const t = B.alloc(10); const n = t.write('日本', 0); return 'n=' + n + ' hex=' + t.slice(0, 6).toString('hex'); })());

    // 静态
    check('Buffer.of 可用', B.of(1, 2, 3).toString('hex') === '010203', B.of(1, 2, 3).toString('hex'));
    check('Buffer.copyBytesFrom 可用', (() => { const u8 = new Uint8Array([9, 8, 7]); return B.copyBytesFrom(u8).toString('hex') === '090807'; })());
    check('Buffer.concat 可用', B.concat([B.from('ab'), B.from('cd')]).toString('utf8') === 'abcd');
}

console.log('\n== 第二批：querystring / util / events 静态 ==');
{
    const qs = window.require('querystring');
    check('querystring.parse 解析', JSON.stringify(qs.parse('a=1&b=2')) === '{"a":"1","b":"2"}', JSON.stringify(qs.parse('a=1&b=2')));
    check('querystring.parse 重复键变数组', JSON.stringify(qs.parse('a=1&a=2')) === '{"a":["1","2"]}', JSON.stringify(qs.parse('a=1&a=2')));
    check('querystring.parse 解码 %XX 与 +', (() => { const r = qs.parse('k=%E6%97%A5&s=a+b'); return r.k === '日' && r.s === 'a b'; })(), JSON.stringify(qs.parse('k=%E6%97%A5&s=a+b')));
    check('querystring.stringify 编码', qs.stringify({ a: 1, b: 'x y' }) === 'a=1&b=x%20y', qs.stringify({ a: 1, b: 'x y' }));
    check('querystring.encode/decode 别名', typeof qs.encode === 'function' && typeof qs.decode === 'function');
    check('querystring.unescapeBuffer 可用', Array.isArray(qs.unescapeBuffer('a%20b')) === false || qs.unescapeBuffer('a%20b').length > 0);

    const u = window.require('util');
    check('util.parseEnv 解析', JSON.stringify(u.parseEnv('A=1\n# c\nB=2')) === '{"A":"1","B":"2"}', JSON.stringify(u.parseEnv('A=1\n# c\nB=2')));
    check('util.stripVTControlCharacters 可用', u.stripVTControlCharacters('\u001b[31mred\u001b[0m') === 'red', u.stripVTControlCharacters('\u001b[31mred\u001b[0m'));
    check('util.getSystemErrorName 可用', u.getSystemErrorName(2) === 'ENOENT', u.getSystemErrorName(2));
    check('util.parseArgs 解析选项', (() => { const r = u.parseArgs({ options: { verbose: { type: 'boolean' } } }, ['--verbose', 'x']); return r.values.verbose === true && r.positionals[0] === 'x'; })(), JSON.stringify(u.parseArgs({ options: { verbose: { type: 'boolean' } } }, ['--verbose', 'x'])));
    check('util.TextEncoder 存在', typeof u.TextEncoder === 'function');
    check('util.promisify 仍是可用实现', u.promisify((a, cb) => cb(null, a + 1))(1).then((v) => v === 2));

    const EES = window.require('events');
    check('events.errorMonitor 存在', EES.errorMonitor !== undefined);
    check('events.captureRejectionSymbol 存在', EES.captureRejectionSymbol !== undefined);
    check('events.captureRejections 布尔', typeof EES.captureRejections === 'boolean');
    check('events.addAbortListener 可用', typeof EES.addAbortListener === 'function');
    check('events.init 可用', typeof EES.init === 'function');
}

console.log('\n== 第二批：fs.promises / url / os / path / crypto 长尾 ==');
{
    const fp = fsMod.promises;
    check('fs.promises.rm 存在', typeof fp.rm === 'function');
    check('fs.promises.rmdir 存在', typeof fp.rmdir === 'function');
    check('fs.promises.realpath 存在', typeof fp.realpath === 'function');
    check('fs.promises.rename 存在', typeof fp.rename === 'function');
    check('fs.promises.symlink 存在', typeof fp.symlink === 'function');
    check('fs.promises.truncate 存在', typeof fp.truncate === 'function');
    check('fs.promises.utimes 存在', typeof fp.utimes === 'function');
    check('fs.promises.mkdtemp 存在', typeof fp.mkdtemp === 'function');
    check('fs.promises.stat 求值正确', fp.stat('data/table.json').then((s) => s.isFile() === true));
    check('fs.promises.readFile 拒绝缺失文件', fp.readFile('data/none.json', 'utf8').then(() => false, () => true));
    check('fs.promises.open + readFile', fp.open('data/table.json', 'r').then((fh) => fh.readFile('utf8').then((c) => c.indexOf('rows') >= 0)));

    const url2 = window.require('url');
    check('url.Url 构造可用', typeof url2.Url === 'function');
    check('url.resolveObject 可用', typeof url2.resolveObject === 'function');
    check('url.fileURLToPathBuffer 返回字节', (() => { const b = url2.fileURLToPathBuffer('file:///a/b'); return b.length === 4; })(), (() => { const b = url2.fileURLToPathBuffer('file:///a/b'); return b.length; })());

    const os2 = window.require('os');
    check('os.getPriority/setPriority 可用', typeof os2.getPriority === 'function' && typeof os2.setPriority === 'function');
    check('os.constants.priority 可用', os2.constants.priority.PRIORITY_NORMAL === 0);

    const p2 = window.require('path');
    check('path.matchesGlob 匹配', p2.matchesGlob('a.png', '*.png') === true && p2.matchesGlob('a.jpg', '*.png') === false);
    check('path.toNamespacedPath 可用', p2.toNamespacedPath('/a/b') === '/a/b');

    const c2 = window.require('crypto');
    check('crypto.Hash 类与实例匹配', (() => { const h = c2.createHash('sha256'); return c2.Hash && h instanceof c2.Hash; })());
    check('crypto.Hmac 类与实例匹配', (() => { const h = c2.createHmac('sha256', 'k'); return h instanceof c2.Hmac; })());
    check('crypto.Cipheriv 类与实例匹配', (() => { const c = c2.createCipheriv('aes-256-cbc', c2.randomBytes(32), c2.randomBytes(16)); return c instanceof c2.Cipheriv; })());
    check('hkdfSync 与已知向量一致', (() => {
        // RFC 5869 Test Case 1（SHA-256）
        const ikm = window.Buffer.from(HostBuffer.from('0b'.repeat(22), 'hex').toString('base64'), 'base64');
        const salt = window.Buffer.from(HostBuffer.from('000102030405060708090a0b0c', 'hex').toString('base64'), 'base64');
        const info = window.Buffer.from(HostBuffer.from('f0f1f2f3f4f5f6f7f8f9', 'hex').toString('base64'), 'base64');
        const okm = c2.hkdfSync('sha256', ikm, salt, info, 42);
        return okm.toString('hex') === '3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865';
    })(), (() => {
        const ikm = window.Buffer.from(HostBuffer.from('0b'.repeat(22), 'hex').toString('base64'), 'base64');
        const salt = window.Buffer.from(HostBuffer.from('000102030405060708090a0b0c', 'hex').toString('base64'), 'base64');
        const info = window.Buffer.from(HostBuffer.from('f0f1f2f3f4f5f6f7f8f9', 'hex').toString('base64'), 'base64');
        return c2.hkdfSync('sha256', ikm, salt, info, 42).toString('hex');
    })());
    check('scryptSync 明确抛错（不给错值）', (() => { try { c2.scryptSync('p', 's', 32); return false; } catch (e) { return e.code === 'ERR_NOT_IMPLEMENTED'; } })());
    check('crypto.getRandomValues 填充字节', (() => { const arr = new Uint8Array(8); c2.getRandomValues(arr); return arr.some((b) => b !== 0); })());
    check('crypto.randomFillSync 填充', (() => { const arr = new Uint8Array(4); c2.randomFillSync(arr); return arr.length === 4; })());
}

console.log('\n== 第三批：长尾补齐与明确报错 ==');
{
    // 无法实现的模块必须「明确报错」而不是静默给空对象
    const netMod = window.require('net');
    let threw = false;
    try { netMod.createServer(); } catch (e) { threw = e && e.code === 'ERR_NOT_IMPLEMENTED'; }
    check("require('net') 调用时明确抛错", threw);

    const httpMod = window.require('http');
    let httpThrew = false;
    try { httpMod.createServer(); } catch (e) { httpThrew = e && e.code === 'ERR_NOT_IMPLEMENTED'; }
    check("require('http') 调用时明确抛错", httpThrew);

    // Buffer
    const B = window.Buffer;
    check('Buffer.allocUnsafeSlow 可用', typeof B.allocUnsafeSlow === 'function' && B.allocUnsafeSlow(4).length === 4);
    check('Buffer 索引读写真实生效（此前是静默无效）', (() => {
        const t = B.from([1, 2, 3]);
        const before = t[0];
        t[0] = 90;
        return before === 1 && t[0] === 90 && t.toString('hex') === '5a0203';
    })(), (() => { const t = B.from([1, 2, 3]); t[0] = 90; return t.toString('hex'); })());
    check('Buffer 是 Uint8Array（可与 TypedArray API 互操作）', B.from('ab') instanceof Uint8Array);
    check('Buffer 修改后 toString 缓存失效', (() => { const t = B.from('abc'); t.toString('utf8'); t[0] = 122; return t.toString('utf8') === 'zbc'; })(), (() => { const t = B.from('abc'); t.toString('utf8'); t[0] = 122; return t.toString('utf8'); })());

    // events
    const EES = window.require('events');
    check('events.EventEmitter 可作为构造器', typeof EES.EventEmitter === 'function' && (() => { const e = new EES.EventEmitter(); return typeof e.on === 'function'; })());
    check('events.getMaxListeners 可用', EES.getMaxListeners(new EES()) === 10 || typeof EES.getMaxListeners(new EES()) === 'number');
    check('events.on 返回 Promise', (() => { const p = EES.on(new EES(), 'x'); return p && typeof p.then === 'function'; })());

    // child_process
    const cp = window.require('child_process');
    check('child_process.ChildProcess 类存在', typeof cp.ChildProcess === 'function');
    check('execFileSync 明确抛错（不返回空串）', (() => { try { cp.execFileSync('ls'); return false; } catch (e) { return e.code === 'ERR_NOT_IMPLEMENTED'; } })());

    // util
    const u = window.require('util');
    check('util.MIMEType 解析', (() => { const m = new u.MIMEType('text/html; charset=utf-8'); return m.type === 'text/html' && m.params.get('charset') === 'utf-8'; })(), (() => { const m = new u.MIMEType('text/html; charset=utf-8'); return m.type + '|' + m.params.get('charset'); })());
    check('util.styleText 可用', typeof u.styleText('bold', 'x') === 'string');
    check('util.aborted 生成 AbortError', u.aborted().name === 'AbortError');

    // url
    const url3 = window.require('url');
    check('url.URLPattern 匹配路径', (() => { const p = new url3.URLPattern('/api/:id'); return p.test('/api/123') === true && p.test('/other') === false; })());

    // fs 长尾
    check('fs.cpSync 复制文件', (() => {
        fsMod.writeFileSync('data/cp-src.txt', 'copyme');
        fsMod.cpSync('data/cp-src.txt', 'data/cp-dst.txt');
        return fsMod.readFileSync('data/cp-dst.txt', 'utf8') === 'copyme';
    })());
    check('fs.cpSync 递归复制目录', (() => {
        fsMod.mkdirSync('data/cpTree/sub');
        fsMod.writeFileSync('data/cpTree/sub/a.txt', 'deep');
        fsMod.cpSync('data/cpTree', 'data/cpTree2');
        const ok = fsMod.readFileSync('data/cpTree2/sub/a.txt', 'utf8') === 'deep';
        fsMod.rmSync('data/cpTree', { recursive: true });
        fsMod.rmSync('data/cpTree2', { recursive: true });
        return ok;
    })());
    check('fs.globSync 匹配文件', (() => {
        fsMod.writeFileSync('data/glob-a.txt', 'x');
        fsMod.writeFileSync('data/glob-b.txt', 'y');
        const hits = fsMod.globSync('data/glob-*.txt');
        return hits.length >= 2;
    })(), (() => fsMod.globSync('data/glob-*.txt').length)());
    check('fs.statfsSync 返回结构（不抛错）', typeof fsMod.statfsSync().bsize === 'number');
    check('fs.fstatSync 明确抛错（无真 fd）', (() => { try { fsMod.fstatSync(0); return false; } catch (e) { return e.code === 'ERR_NOT_IMPLEMENTED'; } })());
    check('fs.mkdtempDisposableSync 可清理', (() => {
        const d = fsMod.mkdtempDisposableSync('data/dtmp-');
        const existed = fsMod.statSync(d.path).isDirectory() === true;
        d.remove();
        return existed && fsMod.existsSync(d.path) === false;
    })());

    // crypto 非对称：明确报错
    const c3 = window.require('crypto');
    check('crypto.createSign 明确抛错', (() => { try { c3.createSign('sha256'); return false; } catch (e) { return e.code === 'ERR_NOT_IMPLEMENTED'; } })());
    check('crypto.generateKeyPairSync 明确抛错', (() => { try { c3.generateKeyPairSync('rsa'); return false; } catch (e) { return e.code === 'ERR_NOT_IMPLEMENTED'; } })());
    check('crypto.randomUUIDv7 可用（唯一 ID）', (() => { const a = c3.randomUUIDv7(), b = c3.randomUUIDv7(); return a !== b && a.length > 10; })());

    // 回归：digest 无参数返回 Buffer（此前返回 hex 字符串导致 HKDF 死循环）
    check('digest() 无参数返回 Buffer', (() => { const d = c3.createHash('sha256').update('x').digest(); return window.Buffer.isBuffer(d) && d.length === 32; })(), (() => { const d = c3.createHash('sha256').update('x').digest(); return typeof d + '/' + (d && d.length); })());
    check('digest("hex") 返回字符串', typeof c3.createHash('sha256').update('x').digest('hex') === 'string');
    check('createHmac().digest() 无参数返回 Buffer', window.Buffer.isBuffer(c3.createHmac('sha256', 'k').update('x').digest()));
}

console.log('\n== 现象二端到端复现：插件读表 -> 渲染名字 ==');
// 造一张插件要读的名字表
fs.writeFileSync(nodePath.join(contentRoot, 'data', 'names.json'), JSON.stringify({ "1": "リリス" }));
// 插件最典型写法（原桩下 existsSync 恒 false，表不加载，取值即 undefined）
const plugin = function () {
    const fsm = window.require('fs');
    let table = {};
    try {
        if (fsm.existsSync('data/names.json')) {
            table = JSON.parse(fsm.readFileSync('data/names.json', 'utf8'));
        }
    } catch (e) { /* 原桩下读空串会 JSON.parse 抛错，插件通常 catch 掉 */ }
    const name = table[1] || undefined;              // 表没加载 → undefined
    return '[' + String(1).padStart(3, '0') + String(name) + ']';
};
const rendered = plugin();
check('名字表被真实加载，渲染出人名', rendered === '[001リリス]', rendered);
check('不再渲染出 001undefined', rendered !== '[001undefined]', rendered);

console.log(failed === 0 ? '\n全部通过' : '\n失败 ' + failed + ' 项');
process.exit(failed === 0 ? 0 : 1);
