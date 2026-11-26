// RPG Maker MV（v1 / v2 会话）「这是不是本作存档」判定的回归。
//
// 被测行为：MV 内置 DataManager.isThisGameFile 在非本地模式下要求存档的 info.title
// 等于 $dataSystem.gameTitle，不符就判定「不是本作存档」——标题页的「继续游戏」随之
// 不可用，DataManager.loadGame() 直接返回 false（不抛异常）。BB_CustomSaveWindow 一类
// 插件会把 info.title 改写成章节名（如「第2章『欢乐街与夜之王(前篇)』」），于是本作
// 存档被判成他人的。Tyranor 的存档目录由游戏目录名唯一标识，title 只是展示字段。
//
// 本 harness 用 Node 的 vm 加载仓库**真实**资产：
//   rpgmv-v1/js/libs/lz-string.js、rpg_core.js、rpg_managers.js 与注入用的 __rpg_v12.js，
// 配一个假的 saveDataManager 桥（对应 Kotlin RpgMakerSaveBridge 的键空间）把存/读跑通。
//
// 「两套核心都兼容」怎么证明：仓库里没有 vendored 的上游 corescript，无法逐字对照，
// 所以这里不声称复制官方原文，而是对**两种形态**分别断言同一份 hook 都成立：
//   - `v1`：仓库打包核心（globalInfo 带内存缓存、会清洗 title/timestamp 非法的槽位）。
//   - `upstream`：不缓存、只按文件存在性清洗的形态 + 上游写法的 isThisGameFile
//     （无类型守卫）。它代表「未经本仓库加固的核心」，用来确认 hook 不依赖 v1 那份
//     实现的具体行为。上游是否逐字如此**未验证**，故只作为形态而非结论。
// 若将来把上游 corescript 整份入库，应把 `upstream` 换成真实资产。
//
// 数据一律经 saveDataManager 桥写入后清空 localStorage，读取因此只能来自桥——避免
// 测试退化成「localStorage 自嗨」，也保证桥本身承重。
//
// 运行：node rpg-save-identity.harness.js（或 ./gradlew :engine:jsSaveIdentityHarness）
'use strict';

const nodeRequire = require;
const fs = nodeRequire('fs');
const nodePath = nodeRequire('path');
const vm = nodeRequire('vm');

const ASSETS = nodePath.resolve(__dirname, '..', '..', 'main', 'assets');
const CORE = nodePath.join(ASSETS, 'rpgmaker', 'rpgmv-v1', 'js');
const HOOK = nodePath.join(ASSETS, 'rpgmaker', '__rpg_v12.js');
const V0_HOOK = nodePath.join(ASSETS, '__rpg__.js');

// 上游（官方 MV 1.6.1 写法）的 isThisGameFile：无类型守卫、先判 isLocalMode。
const UPSTREAM_IS_THIS_GAME_FILE = `
DataManager.isThisGameFile = function(savefileId) {
    var globalInfo = this.loadGlobalInfo();
    if (globalInfo && globalInfo[savefileId]) {
        if (StorageManager.isLocalMode()) return true;
        var savefile = globalInfo[savefileId];
        return (savefile.globalId === this._globalId && savefile.title === $dataSystem.gameTitle);
    }
    return false;
};
`;

// 不缓存的 loadGlobalInfo：每次重新解析，只按「文件是否存在」清洗槽位
// （对应没有本仓库 df394079 那层加固的实现）。
const UPSTREAM_LOAD_GLOBAL_INFO = `
DataManager.loadGlobalInfo = function() {
    var json = StorageManager.load(0);
    if (!json) return [];
    var globalInfo;
    try { globalInfo = JSON.parse(json); } catch (e) { return []; }
    for (var i = 1; i <= this.maxSavefiles(); i++) {
        if (!StorageManager.exists(i)) delete globalInfo[i];
    }
    return globalInfo;
};
`;

let failed = 0;
let total = 0;
const isThenable = (v) => !!(v && typeof v.then === 'function');
// extra 传函数（thunk）：只在失败时求值，避免带副作用的探针（loadGame 之类）被跑两遍。
function check(name, cond, extraThunk) {
    total++;
    if (isThenable(cond)) {
        failed++;
        console.log('  FAIL  ' + name + '  -> 断言值是 Promise（恒真）；请 await 后传布尔值');
        return;
    }
    if (typeof cond === 'function') {
        failed++;
        console.log('  FAIL  ' + name + '  -> 断言值未调用（函数对象恒真）');
        return;
    }
    if (cond) {
        console.log('  ok    ' + name);
        return;
    }
    failed++;
    let extra = '';
    if (typeof extraThunk === 'function') {
        try { extra = '  -> ' + JSON.stringify(extraThunk()); } catch (e) { extra = '  -> <求值抛出 ' + e.message + '>'; }
    }
    console.log('  FAIL  ' + name + extra);
}

// ---- PIXI 宽容桩：只为让 rpg_core.js 的 prototype 继承与 PIXI.xxx.FLAG = … 跑通 ----
function makeStubNode() {
    const target = function () {};
    target.prototype = {};
    return new Proxy(target, {
        get(t, p) {
            if (p === 'prototype') return t.prototype;
            if (p in t) return t[p];
            const child = makeStubNode();
            t[p] = child;
            return child;
        },
        set(t, p, v) { t[p] = v; return true; },
    });
}

function makeBridge() {
    const store = new Map();
    return {
        store,
        Save: (k, v) => { store.set(String(k), String(v)); return true; },
        Load: (k) => (store.has(String(k)) ? store.get(String(k)) : null),
        Exists: (k) => store.has(String(k)),
        Remove: (k) => { store.delete(String(k)); return true; },
    };
}

function makeSession(model) {
    const sandbox = {};
    const ctx = vm.createContext(sandbox);
    vm.runInContext('window = globalThis; self = globalThis; global = globalThis;', ctx);

    const el = () => {
        const node = {
            style: {}, parentNode: { style: {} },
            appendChild() {}, removeChild() {}, setAttribute() {},
            getContext: () => ({ fillRect() {}, drawImage() {}, getImageData: () => ({ data: [] }) }),
            addEventListener() {}, removeEventListener() {}, focus() {},
        };
        return node;
    };
    const lsMap = new Map();
    sandbox.document = {
        documentElement: el(), body: el(), head: el(),
        createElement: el, getElementById: () => null,
        addEventListener() {}, removeEventListener() {},
        fullscreenElement: null, webkitFullscreenElement: null,
    };
    sandbox.navigator = { userAgent: 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36', language: 'zh-CN' };
    sandbox.location = { href: 'http://127.0.0.1:8080/index.html', reload() {} };
    sandbox.localStorage = {
        getItem: (k) => (lsMap.has(String(k)) ? lsMap.get(String(k)) : null),
        setItem: (k, v) => { lsMap.set(String(k), String(v)); },
        removeItem: (k) => { lsMap.delete(String(k)); },
    };
    // 记下被测脚本的告警：hook 里「跳过兼容」的分支是静默降级，只能靠告警断言守住。
    const warns = [];
    sandbox.console = {
        log: () => {}, error: () => {}, info: () => {}, debug: () => {},
        warn: (m) => warns.push(String(m)),
    };
    sandbox.setTimeout = setTimeout; sandbox.clearTimeout = clearTimeout;
    sandbox.setInterval = setInterval; sandbox.clearInterval = clearInterval;
    sandbox.requestAnimationFrame = () => 0;
    sandbox.performance = { now: () => Date.now() };
    sandbox.Image = function () { this.addEventListener = () => {}; };
    sandbox.Audio = function () { this.addEventListener = () => {}; };
    sandbox.URL = URL;
    sandbox.PIXI = makeStubNode();
    const bridge = makeBridge();
    sandbox.window.saveDataManager = bridge;
    sandbox.__tyranorRpg = { engine: 'rpgmaker', version: model.key };

    const run = (file) => vm.runInContext(fs.readFileSync(file, 'utf8'), ctx, { filename: file });
    run(nodePath.join(CORE, 'libs', 'lz-string.js'));
    run(nodePath.join(CORE, 'rpg_core.js'));
    run(nodePath.join(CORE, 'rpg_managers.js'));
    if (model.upstream) {
        vm.runInContext(UPSTREAM_LOAD_GLOBAL_INFO, ctx, { filename: 'upstream-loadGlobalInfo' });
        vm.runInContext(UPSTREAM_IS_THIS_GAME_FILE, ctx, { filename: 'upstream-isThisGameFile' });
    }

    const box = {
        ctx, bridge, run, warns,
        loadHook: () => run(HOOK),
        loadV0Hook: () => run(V0_HOOK),
        clearLocalStorage: () => lsMap.clear(),
        localStorageSize: () => lsMap.size,
        probe: (expr) => vm.runInContext(expr, ctx),
    };

    vm.runInContext(`
        $dataSystem = { gameTitle: "夜の街RPG", startMapId: 1, startX: 0, startY: 0 };
        DataManager._globalId = "RPGMV";
        // BB_CustomSaveWindow 一类插件：复用标准 title 字段写章节名
        var __origMakeSavefileInfo = DataManager.makeSavefileInfo;
        DataManager.makeSavefileInfo = function () {
            var info = __origMakeSavefileInfo.call(this);
            info.title = "第2章『欢乐街与夜之王(前篇)』";
            return info;
        };
        // 只考察存档身份门禁：落盘后的反序列化不拖动 rpg_objects.js，
        // 但保留入参捕获，用来确认读回的确实是那份内容。
        window.__extracted = [];
        DataManager.createGameObjects = function () {};
        DataManager.makeSaveContents = function () { return { marker: "save-contents" }; };
        DataManager.extractSaveContents = function (contents) { window.__extracted.push(contents); };
    `, ctx);
    return box;
}

// 修复前形态：用核心自己的 web 存储分支（localStorage）写两个槽位。
function sessionWithCoreSaves(model) {
    const box = makeSession(model);
    box.probe('DataManager.saveGameWithoutRescue(1); DataManager.saveGameWithoutRescue(2);');
    return box;
}

// 修复后形态：hook 就位后经**桥**写两个槽位，再清空 localStorage，
// 后续读取只能来自桥（桥若失效，下面所有读档断言都会红）。
function seededSession(model) {
    const box = makeSession(model);
    box.loadHook();
    box.probe(`
        DataManager._globalInfo = null;
        DataManager.saveGameWithoutRescue(1);
        DataManager.saveGameWithoutRescue(2);
        DataManager._globalInfo = null;
    `);
    box.clearLocalStorage();
    return box;
}

// 覆写 global 槽位内容（经桥写入，两种形态都能读到）。
function writeGlobal(box, obj) {
    box.probe(`window.__probeGlobal = ${JSON.stringify(JSON.stringify(obj))};` +
        ' StorageManager.save(0, window.__probeGlobal); DataManager._globalInfo = null;');
}

// 安全探针：把抛错折成 ok=false，避免一条用例把整套中断掉（错误信息进失败行的 extra）。
// 只接受**表达式**：拼装方式是把 expr 放进括号里，传语句会在外层解析阶段抛 SyntaxError。
function probeSafe(box, expr) {
    try {
        return box.probe(`(function () {
            try { return { ok: true, value: (${expr}) }; }
            catch (e) { return { ok: false, error: String((e && e.message) || e) }; }
        })()`);
    } catch (e) {
        return { ok: false, error: 'OUTER_THROW ' + String((e && e.message) || e) };
    }
}

// 造一个「有存档文件、但 globalInfo 里字段不全」的槽位：不造文件的话，
// loadGlobalInfo 按存在性做的清洗会先把它删掉，类型前置守卫就测不到了。
function writeSlotFile(box, id) {
    box.probe(`StorageManager.save(${id}, "{}");`);
}

// 本地模式下的判定结果：两种形态都会走 loadFromLocalFile / localFileExists，
// 这里给它们数据源，只考察「是否回落原实现」这条控制流，不考察真实文件路径
// （Node 沙箱里没有 require('fs')，不桩的话核心的 globalInfo 清洗会抛；
// 也因此 localFilePath/localFileDirectoryPath 的路径拼接不在本探针覆盖范围内）。
//
// 注意：调用方必须先把槽位写成「判为他作」的 globalId，否则回不回落都返回 true，
// 这条探针就区分不出任何东西。
function localModeResult(box) {
    box.probe('window.__probeGlobal = window.__probeGlobal || StorageManager.load(0);');
    return box.probe(`(function () {
        var keepIsLocal = StorageManager.isLocalMode;
        var keepLoad = StorageManager.loadFromLocalFile;
        var keepExists = StorageManager.localFileExists;
        StorageManager.isLocalMode = function () { return true; };
        StorageManager.loadFromLocalFile = function () { return window.__probeGlobal; };
        StorageManager.localFileExists = function () { return true; };
        var result;
        try { result = DataManager.isThisGameFile(1); } catch (e) { result = "THREW:" + e.message; }
        StorageManager.isLocalMode = keepIsLocal;
        StorageManager.loadFromLocalFile = keepLoad;
        StorageManager.localFileExists = keepExists;
        return result;
    })()`);
}

// 直接接管 loadGlobalInfo 返回半截槽位：类型前置守卫写在包装函数里，
// 不该靠「某种核心是否会先清洗掉它」间接体现。
function halfSlotResult(box) {
    return box.probe(`(function () {
        var keep = DataManager.loadGlobalInfo;
        DataManager.loadGlobalInfo = function () { return { 8: { globalId: "RPGMV" } }; };
        var result;
        try { result = DataManager.isThisGameFile(8); } catch (e) { result = "THREW:" + e.message; }
        DataManager.loadGlobalInfo = keep;
        return result;
    })()`);
}

const MODELS = [
    { key: 'v1', label: 'v1（仓库打包核心：globalInfo 带缓存与清洗）', upstream: false },
    { key: 'upstream', label: 'upstream 形态（不缓存、无类型守卫的上游写法）', upstream: true },
];

const preHookSources = {};
const cachedCheck = {};

for (const model of MODELS) {
    console.log('\n==== ' + model.label + ' ====');

    // ---- 前置：先证明模型忠实（修复前确实复现 bug）----
    const before = sessionWithCoreSaves(model);
    check('前置：插件写进槽位 1 的 title 是章节名',
        before.probe('DataManager.loadGlobalInfo()[1].title') === '第2章『欢乐街与夜之王(前篇)』',
        () => before.probe('DataManager.loadGlobalInfo()[1].title'));
    check('前置：槽位 1 的 globalId 与会话一致',
        before.probe('DataManager.loadGlobalInfo()[1].globalId') === 'RPGMV');
    check('前置：Web 存档模式（非本地）', before.probe('StorageManager.isLocalMode()') === false);
    check('前置：修复前 isThisGameFile(1) 为 false（复现「读不进去」）',
        before.probe('DataManager.isThisGameFile(1)') === false,
        () => before.probe('DataManager.isThisGameFile(1)'));
    preHookSources[model.key] = before.probe('DataManager.isThisGameFile.toString()');
    cachedCheck[model.key] = before.probe('DataManager.loadGlobalInfo() === DataManager.loadGlobalInfo()');

    // ---- 主回归：hook 就位，数据经桥 ----
    const box = seededSession(model);
    check('桥里确实落了存档（RPG Global / RPG File1 / RPG File2）',
        ['RPG Global', 'RPG File1', 'RPG File2'].every((k) => box.bridge.store.has(k)),
        () => [...box.bridge.store.keys()]);
    check('localStorage 已清空（读取只能来自桥）', box.localStorageSize() === 0,
        () => box.localStorageSize());

    check('插件改写 title 后仍判定为本作存档',
        box.probe('DataManager.isThisGameFile(1)') === true,
        () => box.probe('DataManager.isThisGameFile(1)'));
    check('loadGame 通过门禁并读回那份内容',
        box.probe('DataManager.loadGame(1)') === true,
        () => box.probe('DataManager.loadGame(1)'));
    check('loadGame 喂给 extractSaveContents 的正是存档内容',
        JSON.stringify(box.probe('window.__extracted[0]')) === JSON.stringify({ marker: 'save-contents' }),
        () => box.probe('window.__extracted[0]'));
    check('loadGame 记录了 _lastAccessedId', box.probe('DataManager._lastAccessedId') === 1,
        () => box.probe('DataManager._lastAccessedId'));
    check('两个槽位都可读', box.probe('DataManager.isThisGameFile(2)') === true);

    // ---- 判别能力仍在 ----
    check('globalId 不匹配仍判为他作存档',
        (writeGlobal(box, { 1: { globalId: 'OTHER', title: '第2章', timestamp: 1 } }),
            box.probe('DataManager.isThisGameFile(1)')) === false);
    check('未写入过的槽位返回 false', box.probe('DataManager.isThisGameFile(5)') === false);
    check('槽位是字符串等非对象时返回 false',
        (writeGlobal(box, { 9: 'junk' }), box.probe('DataManager.isThisGameFile(9)')) === false);
    // 用 probeSafe 包住：守卫被拿掉时这里会抛 TypeError，应报成一条失败而不是中断整套
    check('槽位是 null 时返回 false（不抛 TypeError）',
        (writeGlobal(box, { 42: null }), (() => {
            const r = probeSafe(box, 'DataManager.isThisGameFile(42)');
            return r.ok === true && r.value === false;
        })()),
        () => probeSafe(box, 'DataManager.isThisGameFile(42)'));
    check('只有 globalId、没有 title 的半截槽位不放行（与原实现的类型前置一致）',
        (writeSlotFile(box, 7), writeGlobal(box, { 7: { globalId: 'RPGMV' } }),
            probeSafe(box, 'DataManager.isThisGameFile(7)').value) === false,
        () => probeSafe(box, 'DataManager.isThisGameFile(7)'));
    // 上面那条在 v1 形态下靠核心的清洗就会通过，单看不出发包装函数的守卫在不在；
    // 这条把 loadGlobalInfo 直接钉死成半截槽位，两形态都只由守卫决定结果。
    check('包装函数自身的类型前置生效（与核心是否清洗无关）', halfSlotResult(box) === false,
        () => halfSlotResult(box));
    check('globalInfo 为空时不抛错且返回 false',
        (writeGlobal(box, []), box.probe('DataManager.isThisGameFile(1)')) === false);

    // ---- 老存档 / 改标题都不能重新失效 ----
    const legacy = seededSession(model);
    check('老存档（只有 globalId/title/timestamp 三个标准字段）照常可读',
        legacy.probe('DataManager.isThisGameFile(1)') === true);
    check('游戏更新改了 gameTitle 后旧存档仍可读',
        (legacy.probe('$dataSystem.gameTitle = "夜の街RPG 完全版"'),
            legacy.probe('DataManager.isThisGameFile(1)')) === true);
    check('Web 存档模式下 $dataSystem 未就绪也不抛错',
        (legacy.probe('window.__keepTitle = $dataSystem; $dataSystem = null;'),
            (() => {
                const r = legacy.probe('(function(){ try { return DataManager.isThisGameFile(1); } catch (e) { return "THREW:" + e.message; } })()');
                legacy.probe('$dataSystem = window.__keepTitle;');
                return r;
            })()) === true);

    // ---- 本地模式回落原实现 ----
    // 先把槽位写成 globalId 不匹配：回落原实现会返回 true（本地模式下有槽位即算本作），
    // 不回落则按 Web 规则返回 false——这样探针才能区分两条分支。
    const local = seededSession(model);
    writeGlobal(local, { 1: { globalId: 'OTHER', title: '第2章', timestamp: 1 } });
    check('本地模式下即便 globalId 对不上也回落原实现（true）', localModeResult(local) === true,
        () => localModeResult(local));

    // ---- 注入幂等：不叠加包装 ----
    const idem = seededSession(model);
    const firstRef = idem.probe('DataManager.isThisGameFile');
    idem.loadHook();
    check('hook 重复注入不新增包装层（函数引用不变）',
        firstRef === idem.probe('DataManager.isThisGameFile'));
    check('hook 重复注入后行为不变', idem.probe('DataManager.isThisGameFile(1)') === true);
    // 同样先用他作槽位，才能确认二次注入后回落分支仍然只委派一层（不自递归、不叠加）
    writeGlobal(idem, { 1: { globalId: 'OTHER', title: '第2章', timestamp: 1 } });
    check('hook 重复注入后本地模式回落不自递归', localModeResult(idem) === true);
}

// ---- 模型自检 ----
console.log('\n==== 模型自检 ====');
// 行为断言优于源码字符串比较：upstream 形态的意义就是「不缓存」，
// 而 v1 打包核心的 loadGlobalInfo 会返回同一份内存对象。
check('v1 形态的 globalInfo 有内存缓存（两次调用是同一对象）',
    cachedCheck.v1 === true);
check('upstream 形态的 globalInfo 不缓存（对应核心不清洗、每次重新解析）',
    cachedCheck.upstream === false);
// 保留两条源码哨兵：它们只能说明「模型没被写坏」，不能当作兼容性证据。
check('两模型的修复前实现不同（模型没被写坏）',
    preHookSources.v1 !== preHookSources.upstream);
check('修复前是两套各自「带 title 比较」的实现',
    preHookSources.v1.indexOf('gameTitle') >= 0 && preHookSources.upstream.indexOf('gameTitle') >= 0);

// ---- 核心异常形态：不制造新的崩溃路径 ----
console.log('\n==== 核心缺少该函数时 ====');
{
    const box = makeSession(MODELS[0]);
    box.probe('delete DataManager.isThisGameFile;');
    box.loadHook();
    check('核心没有 isThisGameFile 时不安装半套补丁（不引入 original.call 抛错）',
        box.probe('typeof DataManager.isThisGameFile') === 'undefined',
        () => box.probe('typeof DataManager.isThisGameFile'));
    // 静默降级等于没修：这条守住 hook 里那条「跳过兼容」告警真的会发出来。
    check('该分支留下可诊断的告警', box.warns.some((w) => w.indexOf('isThisGameFile') >= 0),
        () => box.warns);
}

// ---- v1 打包核心特有的槽位汇总函数 ----
console.log('\n==== v1 打包核心：槽位汇总 ====');
{
    const box = seededSession(MODELS[0]);
    check('isAnySavefileExists 认为有可读存档（标题页「继续」可用）',
        box.probe('DataManager.isAnySavefileExists()') === true);
    check('latestSavefileId 指向的槽位确实可读',
        box.probe('DataManager.isThisGameFile(DataManager.latestSavefileId())') === true,
        () => box.probe('DataManager.latestSavefileId()'));
}

// ---- v0（Tyranor-2.3.4）确实没被改动 ----
// 边界：这里在「v1 打包核心」沙箱里执行 __rpg__.js，只能证明「v0 那份资源本身没有
// 覆盖判定、也没有设本补丁的标记」，不是 v0 端到端回归（v0 生产会话是 TyranoActivity +
// 游戏自带核心）。v0 未被改动的硬证据仍是本提交的改动清单里没有 __rpg__.js /
// TyranoActivity.kt；真机与真实会话未验证。
console.log('\n==== v0（Tyranor-2.3.4 / __rpg__.js）不受影响 ====');
{
    const box = sessionWithCoreSaves(MODELS[0]);
    const v0Source = box.probe('DataManager.isThisGameFile.toString()');
    box.loadV0Hook();
    check('v0 hook 装载后 isThisGameFile 仍是原实现（未被本补丁覆盖）',
        box.probe('DataManager.isThisGameFile.toString()') === v0Source);
    check('v0 下 title 不匹配的槽位仍判否（本提交未修 v0，行为与改前一致）',
        box.probe('DataManager.isThisGameFile(1)') === false);
    check('v0 下不会出现本补丁的标记', box.probe('!DataManager.__tyranorSaveIdentityPatched'));
}

console.log('\n' + (failed === 0 ? '全部通过（' + total + ' 项）' : '失败 ' + failed + ' / ' + total + ' 项'));
process.exit(failed === 0 ? 0 : 1);
