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
const bridge = {
    baseDir: () => contentRoot,
    dataDir: () => nodePath.join(gameRoot, 'AppData'),
    exists: (p) => inside(p) !== null && fs.existsSync(inside(p)),
    isFile: (p) => { const f = inside(p); return f !== null && fs.existsSync(f) && fs.statSync(f).isFile(); },
    isDir: (p) => { const f = inside(p); return f !== null && fs.existsSync(f) && fs.statSync(f).isDirectory(); },
    readText: (p) => { const f = inside(p); return (f && fs.existsSync(f) && fs.statSync(f).isFile()) ? fs.readFileSync(f, 'utf8') : null; },
    readBase64: (p) => { const f = inside(p); return (f && fs.existsSync(f) && fs.statSync(f).isFile()) ? fs.readFileSync(f).toString('base64') : null; },
    readdir: (p) => { const f = inside(p); return (f && fs.existsSync(f) && fs.statSync(f).isDirectory()) ? JSON.stringify(fs.readdirSync(f)) : '[]'; },
    stat: (p) => { const f = inside(p); if (!f || !fs.existsSync(f)) return ''; const s = fs.statSync(f); return JSON.stringify({ file: s.isFile(), dir: s.isDirectory(), size: s.size, mtime: s.mtimeMs }); },
    writeText: (p, d) => { const f = inside(p); if (!f) return false; fs.mkdirSync(nodePath.dirname(f), { recursive: true }); fs.writeFileSync(f, d == null ? '' : String(d)); return true; },
    writeBase64: (p, d) => { const f = inside(p); if (!f) return false; fs.mkdirSync(nodePath.dirname(f), { recursive: true }); fs.writeFileSync(f, HostBuffer.from(d || '', 'base64')); return true; },
    makeDirs: (p) => { const f = inside(p); if (!f) return false; fs.mkdirSync(f, { recursive: true }); return true; },
    remove: (p) => { const f = inside(p); if (!f) return true; if (fs.existsSync(f)) fs.unlinkSync(f); return true; },
};

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
check('Buffer.slice 可用', typeof bin.slice === 'function' && bin.slice(1).length === 3, typeof bin.slice);
check('Buffer.readUInt8 可用', typeof bin.readUInt8 === 'function' && bin.readUInt8(0) === 1, typeof bin.readUInt8);
check('Buffer.readUInt8 取值正确', bin.readUInt8(3) === 250, bin.readUInt8 && bin.readUInt8(3));
check('Buffer.readUInt16LE 正确', bin.readUInt16LE(0) === 0x0201, bin.readUInt16LE && bin.readUInt16LE(0));
check('Buffer.toJSON 形态正确', JSON.stringify(bin.toJSON()) === '{"type":"Buffer","data":[1,2,3,250]}', JSON.stringify(bin.toJSON && bin.toJSON()));
check('Buffer.equals 正确', bin.equals(fsMod.readFileSync('bin.dat')) === true);
check('Buffer.indexOf 正确', bin.indexOf(3) === 2, bin.indexOf && bin.indexOf(3));
check('Buffer 越界读抛错', (() => { try { bin.readUInt32LE(5); return false; } catch (e) { return true; } })());

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
