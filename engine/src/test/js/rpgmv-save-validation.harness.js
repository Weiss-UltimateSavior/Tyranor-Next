// Node.js regression harness for RPG Maker MV save validation across Tyranor-2.3.4 (v0),
// TynMV-0.1 (v1), and TynMV-0.2 (v2), plus a smoke check for RPG Maker MZ hooks.
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const ASSETS_DIR = path.join(__dirname, '..', '..', 'main', 'assets');
const LZ_STRING_CODE = fs.readFileSync(path.join(ASSETS_DIR, 'rpgmaker', 'rpgmv-v1', 'js', 'libs', 'lz-string.js'), 'utf8');
const RPG_MANAGERS_CODE = fs.readFileSync(path.join(ASSETS_DIR, 'rpgmaker', 'rpgmv-v1', 'js', 'rpg_managers.js'), 'utf8');
const NWJS_POLYFILL_CODE = fs.readFileSync(path.join(ASSETS_DIR, 'rpgmaker', '__nwjs_polyfill.js'), 'utf8');
const RPG_V0_HOOK_CODE = fs.readFileSync(path.join(ASSETS_DIR, '__rpg__.js'), 'utf8');
const RPG_V12_HOOK_CODE = fs.readFileSync(path.join(ASSETS_DIR, 'rpgmaker', '__rpg_v12.js'), 'utf8');
const MOD_CORE_CODE = fs.readFileSync(path.join(ASSETS_DIR, '__rpgmaker_mod_core.js'), 'utf8');
const RMMZ_HOOK_CODE = fs.readFileSync(path.join(ASSETS_DIR, '__rmmz__.js'), 'utf8');
const RMMZ_MANAGERS_HOOK_CODE = fs.readFileSync(path.join(ASSETS_DIR, '__hook_rmmz_managers.js'), 'utf8');
// MZ v2（TynMZ-0.2）会话专有资产
const MZ_V2_DIR = path.join(ASSETS_DIR, 'rpgmaker', 'mz');
const MZ_V2_HOOK_CODE = fs.readFileSync(path.join(MZ_V2_DIR, '__mz_hook.js'), 'utf8');
const MZ_V2_CORE_HOOK_CODE = fs.readFileSync(path.join(MZ_V2_DIR, '__mz_core_hook.js'), 'utf8');
const MZ_V2_MANAGERS_HOOK_CODE = fs.readFileSync(path.join(MZ_V2_DIR, '__mz_managers_hook.js'), 'utf8');

let failed = 0;
function check(name, cond, detail) {
    if (cond && typeof cond.then === 'function') {
        failed++;
        console.log('  FAIL  ' + name + ' -> assertion passed a Promise; await it first');
        return;
    }
    if (cond) {
        console.log('  PASS  ' + name);
    } else {
        failed++;
        console.log('  FAIL  ' + name + (detail !== undefined ? ' -> ' + detail : ''));
    }
}

function createSaveBridgeMock(initialStore) {
    const store = Object.assign({}, initialStore || {});
    return {
        _store: store,
        _calls: [],
        Save(key, data) {
            this._calls.push(['Save', key]);
            store[String(key)] = String(data);
            return true;
        },
        Load(key) {
            this._calls.push(['Load', key]);
            return Object.prototype.hasOwnProperty.call(store, String(key)) ? store[String(key)] : '';
        },
        Exists(key) {
            this._calls.push(['Exists', key]);
            return Object.prototype.hasOwnProperty.call(store, String(key));
        },
        Remove(key) {
            this._calls.push(['Remove', key]);
            delete store[String(key)];
            return true;
        },
    };
}

function createLocalStorageMock() {
    const data = {};
    return {
        setItem(k, v) { data[String(k)] = String(v); },
        getItem(k) { return Object.prototype.hasOwnProperty.call(data, String(k)) ? data[String(k)] : null; },
        removeItem(k) { delete data[String(k)]; },
        clear() { Object.keys(data).forEach((k) => delete data[k]); },
        key(i) { return Object.keys(data)[i] || null; },
        get length() { return Object.keys(data).length; },
    };
}

function createMvSandbox(options) {
    const opts = options || {};
    const windowListeners = {};
    const documentListeners = {};
    const timers = new Set();
    let nextTimerId = 1;
    let navigatedScene = null;
    const fsRequireCalls = [];

    const saveDataManager = opts.saveDataManager || createSaveBridgeMock();
    const localStorage = createLocalStorageMock();
    const pixi = {
        dontSayHello: false,
        CanvasRenderer: function CanvasRenderer() {},
        WebGLRenderer: function WebGLRenderer() {},
        autoDetectRenderer: function () { return { textureGC: {} }; },
    };

    const sandbox = {
        console,
        Int32Array,
        Uint8Array,
        PIXI: pixi,
        performance,
        saveDataManager,
        localStorage,
        location: { href: opts.href || 'http://localhost:12345/index.html', reload() {} },
        navigator: { userAgent: 'Android WebView' },
        screen: {},
        document: {
            readyState: 'loading',
            documentElement: { style: {} },
            body: { parentNode: { style: {} }, appendChild() {} },
            createElement() { return { style: {}, getContext: () => null, addEventListener() {}, appendChild() {} }; },
            getElementById() { return null; },
            addEventListener(type, fn) {
                (documentListeners[type] = documentListeners[type] || []).push(fn);
            },
        },
        addEventListener(type, fn) {
            (windowListeners[type] = windowListeners[type] || []).push(fn);
        },
        removeEventListener(type, fn) {
            if (!windowListeners[type]) return;
            windowListeners[type] = windowListeners[type].filter((item) => item !== fn);
        },
        dispatchEvent() { return true; },
        setTimeout(fn) {
            const id = nextTimerId++;
            timers.add(id);
            return id;
        },
        clearTimeout(id) { timers.delete(id); },
        setInterval(fn) {
            const id = nextTimerId++;
            timers.add(id);
            return id;
        },
        clearInterval(id) { timers.delete(id); },
        require(mod) {
            fsRequireCalls.push(mod);
            throw new Error('Unexpected Node require(' + mod + ') in Web save mode');
        },
        JsonEx: {
            stringify: JSON.stringify,
            parse: JSON.parse,
        },
        Utils: {
            isNwjs: () => false,
            isMobileDevice: () => false,
            isMobileSafari: () => false,
            isOptionValid: () => false,
            generateRuntimeId: () => 1,
            canReadGameFiles: () => true,
        },
        Graphics: {
            _width: 816,
            _height: 624,
            _rendererType: 'auto',
            _canvas: {},
        },
        CacheMap: function CacheMap() {},
        ImageCache: function ImageCache() {},
        RequestQueue: function RequestQueue() {},
        WebAudio: { canPlayOgg: () => true, initialize: () => true },
        Bitmap: function Bitmap() {},
        Game_Temp: function Game_Temp() {},
        Game_System: function Game_System() {
            this.isSaveEnabled = () => true;
            this.playtimeText = () => '00:12:34';
        },
        Game_Screen: function Game_Screen() {},
        Game_Timer: function Game_Timer() {},
        Game_Message: function Game_Message() {},
        Game_Switches: function Game_Switches() {},
        Game_Variables: function Game_Variables() { this.marker = 'empty'; },
        Game_SelfSwitches: function Game_SelfSwitches() {},
        Game_Actors: function Game_Actors() {},
        Game_Party: function Game_Party() {
            this.charactersForSavefile = () => [];
            this.facesForSavefile = () => [];
            this.members = () => [];
            this.battleMembers = () => [];
            this.gold = () => 0;
        },
        Game_Troop: function Game_Troop() { this.members = () => []; },
        Game_Map: function Game_Map() { this.mapId = () => 1; },
        Game_Player: function Game_Player() {},
        Game_Battler: function Game_Battler() {},
        Game_Action: function Game_Action() {},
        Window_Message: function Window_Message() {},
        Scene_Boot: function Scene_Boot() {},
        Scene_Map: function Scene_Map() {},
    };
    sandbox.window = sandbox;
    sandbox.global = sandbox;
    sandbox.globalThis = sandbox;

    const context = vm.createContext(sandbox);
    // MV String.prototype.format from rpg_core.js
    vm.runInContext(
        "String.prototype.format = function() { var args = arguments; return this.replace(/%([0-9]+)/g, function(s, n) { return args[Number(n) - 1]; }); };",
        context,
    );
    vm.runInContext(LZ_STRING_CODE, context, { filename: 'lz-string.js' });

    if (opts.version !== 'v0') {
        sandbox.__tyranorRpg = { engine: 'MV', version: opts.version || 'v1' };
        vm.runInContext(NWJS_POLYFILL_CODE, context, { filename: '__nwjs_polyfill.js' });
    }

    vm.runInContext(RPG_MANAGERS_CODE, context, { filename: 'rpg_managers.js' });
    // rpg_managers.js declares `var $dataSystem = null;` at top level; populate after database load simulation.
    sandbox.$dataSystem = { gameTitle: opts.gameTitle || 'Game Title', switches: [], variables: [] };
    // Stub SceneManager.goto cleanly after rpg_managers.js defines SceneManager.
    sandbox.SceneManager.goto = function (sceneClass) { navigatedScene = sceneClass; };

    if (opts.simulateUpstreamIsThisGameFile) {
        // Simulate unmodified upstream game rpg_managers.js (used by v0 / v2) with strict title matching.
        vm.runInContext(
            `DataManager.isThisGameFile = function(savefileId) {
                var globalInfo = this.loadGlobalInfo();
                if (globalInfo && globalInfo[savefileId]) {
                    if (StorageManager.isLocalMode()) {
                        return true;
                    } else {
                        var savefile = globalInfo[savefileId];
                        return (savefile.globalId === this._globalId &&
                                savefile.title === $dataSystem.gameTitle);
                    }
                }
                return false;
            };`,
            context,
        );
    }

    if (opts.loadHook !== false) {
        const hookCode = opts.version === 'v0' ? RPG_V0_HOOK_CODE : RPG_V12_HOOK_CODE;
        const hookName = opts.version === 'v0' ? '__rpg__.js' : '__rpg_v12.js';
        vm.runInContext(hookCode, context, { filename: hookName });
    }

    if (opts.loadMod) {
        vm.runInContext(MOD_CORE_CODE, context, { filename: '__rpgmaker_mod_core.js' });
    }

    return {
        sandbox,
        context,
        saveDataManager,
        fsRequireCalls,
        getNavigatedScene: () => navigatedScene,
        fireWindowLoad: () => {
            (windowListeners.load || []).slice().forEach((fn) => fn({}));
        },
        runCode: (code, filename) => vm.runInContext(code, context, { filename }),
    };
}

function encodeSave(lz, payload) {
    return lz.compressToBase64(JSON.stringify(payload));
}

function seedMvSave(env, slotId, options) {
    const opts = options || {};
    const globalKey = opts.globalKey || 'RPG Global';
    const fileKey = opts.fileKey || ('RPG File' + slotId);
    const globalArr = opts.globalArray || [];
    if (!opts.skipGlobalEntry) {
        globalArr[slotId] = {
            globalId: Object.prototype.hasOwnProperty.call(opts, 'globalId') ? opts.globalId : 'RPGMV',
            title: Object.prototype.hasOwnProperty.call(opts, 'title') ? opts.title : 'Original Game Title',
            timestamp: Object.prototype.hasOwnProperty.call(opts, 'timestamp') ? opts.timestamp : 1700000000000,
            playtime: '01:23:45',
            characters: [],
            faces: [],
        };
    }
    const saveContents = {
        system: { isSaveEnabled: () => true },
        screen: {},
        timer: {},
        switches: {},
        variables: { loadedSlot: slotId, marker: opts.marker || 'ok' },
        selfSwitches: {},
        actors: {},
        party: {},
        map: {},
        player: {},
    };
    if (!opts.omitGlobalKey) {
        env.saveDataManager._store[globalKey] = encodeSave(env.sandbox.LZString, globalArr);
    }
    if (!opts.omitSaveFile) {
        env.saveDataManager._store[fileKey] = encodeSave(env.sandbox.LZString, saveContents);
    }
    env.sandbox.DataManager._globalInfo = null;
}

async function runTests() {
    console.log('== Skenario 1: global.title berbeda pada MV Tyranor-2.3.4 (v0), TynMV-0.1 (v1), dan TynMV-0.2 (v2) ==');
    for (const cfg of [
        { label: 'Tyranor-2.3.4 (v0)', version: 'v0', simulateUpstreamIsThisGameFile: true },
        { label: 'TynMV-0.1 (v1)', version: 'v1', simulateUpstreamIsThisGameFile: false },
        { label: 'TynMV-0.2 (v2)', version: 'v2', simulateUpstreamIsThisGameFile: true },
    ]) {
        if (cfg.simulateUpstreamIsThisGameFile) {
            const reproEnv = createMvSandbox({
                version: cfg.version,
                gameTitle: 'Localized Game [ID]',
                simulateUpstreamIsThisGameFile: true,
                loadHook: false,
            });
            seedMvSave(reproEnv, 1, { title: '元のタイトル', globalId: 'RPGMV', marker: cfg.version });
            // Simulate StorageManager reading from the same savedata without the #148 isThisGameFile fix.
            reproEnv.sandbox.localStorage.setItem('RPG Global', reproEnv.saveDataManager._store['RPG Global']);
            reproEnv.sandbox.localStorage.setItem('RPG File1', reproEnv.saveDataManager._store['RPG File1']);
            reproEnv.sandbox.DataManager._globalInfo = null;
            check(
                cfg.label + ' sebelum patch gagal pada judul berbeda (repro #148)',
                reproEnv.sandbox.DataManager.isThisGameFile(1) === false && reproEnv.sandbox.DataManager.loadGame(1) === false,
            );
        }

        const env = createMvSandbox({
            version: cfg.version,
            gameTitle: 'Localized Game [ID]',
            simulateUpstreamIsThisGameFile: cfg.simulateUpstreamIsThisGameFile,
        });
        seedMvSave(env, 1, { title: '元のタイトル', globalId: 'RPGMV', marker: cfg.version });
        const dm = env.sandbox.DataManager;
        const valid = dm.isThisGameFile(1);
        const anyExists = dm.isAnySavefileExists();
        const loaded = dm.loadGame(1);
        check(
            cfg.label + ' mengizinkan save walau global.title berbeda',
            valid === true && anyExists === true && loaded === true && env.sandbox.$gameVariables.marker === cfg.version,
            `valid=${valid}, anyExists=${anyExists}, loaded=${loaded}`,
        );
    }

    console.log('\n== Skenario 2: Validasi globalId berbeda atau tidak valid ==');
    {
        const env = createMvSandbox({ version: 'v2', gameTitle: 'Localized Game', simulateUpstreamIsThisGameFile: true });
        seedMvSave(env, 1, { globalId: 'OTHER_GAME', title: 'Localized Game' });
        const dm = env.sandbox.DataManager;
        check('globalId berbeda ditolak oleh isThisGameFile', dm.isThisGameFile(1) === false);
        check('globalId berbeda ditolak oleh loadGame', dm.loadGame(1) === false);

        dm._globalInfo = [
            null,
            { globalId: 12345, title: 'Localized Game', timestamp: 1700000000000 },
            { title: 'Localized Game', timestamp: 1700000000000 },
        ];
        check('globalId non-string ditolak', dm.isThisGameFile(1) === false);
        check('globalId hilang ditolak', dm.isThisGameFile(2) === false);

        dm._globalId = undefined;
        dm._globalInfo = [null, { globalId: 'RPGMV', title: 'Localized Game', timestamp: 1700000000000 }];
        check('DataManager._globalId undefined menolak slot', dm.isThisGameFile(1) === false);
    }

    console.log('\n== Skenario 3: Edge case globalInfo kosong, korup, null, dan file save hilang ==');
    for (const version of ['v0', 'v1', 'v2']) {
        const env = createMvSandbox({ version, simulateUpstreamIsThisGameFile: version !== 'v1' });
        const dm = env.sandbox.DataManager;
        check(version + ' globalInfo kosong -> false tanpa error', dm.isThisGameFile(1) === false && dm.loadGame(1) === false);

        env.saveDataManager._store['RPG Global'] = env.sandbox.LZString.compressToBase64('{broken-json');
        dm._globalInfo = null;
        check(version + ' globalInfo JSON korup -> false tanpa error', dm.isThisGameFile(1) === false && dm.loadGame(1) === false);

        // Cache globalInfo null atau primitif (simulasi globalInfo korup di runtime)
        const origLoadGlobal = dm.loadGlobalInfo;
        dm.loadGlobalInfo = () => null;
        check(version + ' loadGlobalInfo null -> false', dm.isThisGameFile(1) === false);
        dm.loadGlobalInfo = () => 'corrupt';
        check(version + ' loadGlobalInfo primitif -> false', dm.isThisGameFile(0) === false && dm.isThisGameFile(1) === false);
        dm.loadGlobalInfo = origLoadGlobal;

        // Metadata di global ada, tapi backing file1 tidak ada di StorageManager
        seedMvSave(env, 1, { omitSaveFile: true, title: 'Old Title' });
        check(version + ' global ada tetapi file1 hilang -> drop slot & loadGame false', dm.isThisGameFile(1) === false && dm.loadGame(1) === false);
    }

    console.log('\n== Skenario 4: Integrasi TyranorMod.listSaveSlots() dan TyranorMod.loadGame() ==');
    for (const version of ['v0', 'v1', 'v2']) {
        const env = createMvSandbox({
            version,
            gameTitle: 'New Translated Title',
            simulateUpstreamIsThisGameFile: version !== 'v1',
            loadMod: true,
        });
        seedMvSave(env, 1, { title: '旧版本标题', globalId: 'RPGMV', marker: 'from-mod-' + version });
        seedMvSave(env, 2, {
            title: 'Other Game',
            globalId: 'ANOTHER_ID',
            omitGlobalKey: false,
            globalArray: JSON.parse(env.sandbox.LZString.decompressFromBase64(env.saveDataManager._store['RPG Global'])),
        });
        env.sandbox.DataManager._globalInfo = null;

        const mod = env.sandbox.TyranorMod;
        const slots = await mod.listSaveSlots();
        const slot1 = slots.find((s) => s.id === 1);
        check(version + ' TyranorMod.listSaveSlots melihat slot 1', !!(slot1 && slot1.exists && slot1.title === '旧版本标题'));

        const loadOk = await mod.loadGame(1);
        check(
            version + ' TyranorMod.loadGame(1) berhasil saat title berbeda',
            loadOk === true &&
                env.sandbox.$gameVariables.marker === 'from-mod-' + version &&
                env.getNavigatedScene() === env.sandbox.Scene_Map,
        );

        let rejectedMismatchedGlobal = false;
        try {
            await mod.loadGame(2);
        } catch (err) {
            rejectedMismatchedGlobal = String(err.message || err).indexOf('读取存档失败') >= 0;
        }
        check(version + ' TyranorMod.loadGame(2) tetap menolak globalId yang salah', rejectedMismatchedGlobal);
    }

    console.log('\n== Skenario 5: Invariansi StorageManager.isLocalMode() === false, jalur saveDataManager, & delegasi local mode ==');
    {
        for (const version of ['v0', 'v1', 'v2']) {
            const env = createMvSandbox({ version, simulateUpstreamIsThisGameFile: version !== 'v1' });
            seedMvSave(env, 1, { title: 'Title A' });
            const isLocal = env.sandbox.StorageManager.isLocalMode();
            const ok = env.sandbox.DataManager.loadGame(1);
            check(
                version + ' menjaga isLocalMode() === false dan memakai saveDataManager',
                isLocal === false && ok === true && env.fsRequireCalls.length === 0 && env.saveDataManager._calls.length > 0,
            );
        }

        // Jika runtime secara eksplisit berjalan di local mode true, wrapper mendelegasikan ke original
        const localEnv = createMvSandbox({ version: 'v0', loadHook: false });
        let originalCalledWith = null;
        localEnv.sandbox.StorageManager.isLocalMode = () => true;
        localEnv.sandbox.DataManager.isThisGameFile = function (id) {
            originalCalledWith = id;
            return 'from-original-' + id;
        };
        localEnv.runCode(RPG_V0_HOOK_CODE, '__rpg__.js');
        const delegated = localEnv.sandbox.DataManager.isThisGameFile(7);
        check('Mode local isLocalMode() === true mendelegasikan ke fungsi asli', originalCalledWith === 7 && delegated === 'from-original-7');
    }

    console.log('\n== Skenario 6: Fallback save PC global.bin / file1.bin (dan YEP_SaveCore) + judul berbeda ==');
    {
        const env = createMvSandbox({
            version: 'v2',
            gameTitle: 'Android Release Title',
            simulateUpstreamIsThisGameFile: true,
        });
        const pcGlobal = [];
        pcGlobal[1] = { globalId: 'RPGMV', title: 'PC Original JP', timestamp: 1700000000000, playtime: '02:00:00' };
        env.saveDataManager._store.global = encodeSave(env.sandbox.LZString, pcGlobal);
        env.saveDataManager._store.file1 = encodeSave(env.sandbox.LZString, {
            system: { isSaveEnabled: () => true },
            variables: { marker: 'pc-save' },
            party: {},
            map: {},
        });

        check(
            '__rpg_v12.js membaca global.bin/file1.bin fallback meski title berbeda',
            env.sandbox.DataManager.isThisGameFile(1) === true &&
                env.sandbox.DataManager.isAnySavefileExists() === true &&
                env.sandbox.DataManager.loadGame(1) === true &&
                env.sandbox.$gameVariables.marker === 'pc-save',
        );

        // YEP_SaveCore mengubah webStorageKey menjadi 'RPG <Title> Global' / 'RPG <Title> File1'
        env.sandbox.StorageManager.webStorageKey = (id) => (id === 0 ? 'RPG CustomTitle Global' : 'RPG CustomTitle File' + id);
        env.sandbox.DataManager._globalInfo = null;
        check(
            'Fallback PC + title berbeda tetap bekerja dengan key YEP_SaveCore',
            env.sandbox.DataManager.isThisGameFile(1) === true && env.sandbox.DataManager.loadGame(1) === true,
        );
    }

    console.log('\n== Skenario 7: Plugin menimpa DataManager.isThisGameFile sebelum event load & idempotensi wrapper ==');
    for (const version of ['v0', 'v1', 'v2']) {
        const env = createMvSandbox({
            version,
            gameTitle: 'New Title',
            simulateUpstreamIsThisGameFile: version !== 'v1',
        });
        seedMvSave(env, 1, { title: 'Old Plugin Title', globalId: 'RPGMV', marker: 'plugin-ok' });

        // Simulasikan plugin yang dimuat PluginManager.loadScript setelah lateHook dan menimpa total isThisGameFile
        env.runCode(
            `DataManager.isThisGameFile = function(savefileId) {
                var globalInfo = this.loadGlobalInfo();
                var slot = globalInfo && globalInfo[savefileId];
                return !!(slot && slot.globalId === this._globalId && slot.title === $dataSystem.gameTitle);
            };`,
            'ThirdPartySavePlugin.js',
        );
        check(version + ' sebelum event load, plugin overwrite menolak title berbeda', env.sandbox.DataManager.isThisGameFile(1) === false);

        env.fireWindowLoad();
        const patchedFn = env.sandbox.DataManager.isThisGameFile;
        check(
            version + ' sesudah event load, wrapper dipasang ulang & meloloskan save',
            patchedFn.__tyranorTitlePatched === true &&
                env.sandbox.DataManager.isThisGameFile(1) === true &&
                env.sandbox.DataManager.loadGame(1) === true,
        );

        // Idempotensi: re-evaluasi hook & fire load lagi tidak menumpuk wrapper
        env.runCode(version === 'v0' ? RPG_V0_HOOK_CODE : RPG_V12_HOOK_CODE, 're-eval.js');
        env.fireWindowLoad();
        check(version + ' re-evaluasi dan load ulang bersifat idempoten', env.sandbox.DataManager.isThisGameFile === patchedFn);
    }

    console.log('\n== Skenario 8: Smoke test hook RPG Maker MZ (__rmmz__.js + __hook_rmmz_managers.js) ==');
    {
        const saveDataManager = createSaveBridgeMock();
        const sandbox = {
            console,
            document: { body: { parentNode: { style: {} } } },
            saveDataManager,
            StorageManager: {
                objectToJson: (obj) => Promise.resolve(JSON.stringify(obj)),
                jsonToObject: (json) => Promise.resolve(JSON.parse(json)),
            },
            SceneManager: {},
        };
        sandbox.window = sandbox;
        sandbox.globalThis = sandbox;
        const context = vm.createContext(sandbox);
        vm.runInContext(RMMZ_HOOK_CODE, context, { filename: '__rmmz__.js' });
        vm.runInContext(RMMZ_MANAGERS_HOOK_CODE, context, { filename: '__hook_rmmz_managers.js' });

        await sandbox.StorageManager.saveObject('global', { version: 1, title: 'MZ Save' });
        const exists = sandbox.StorageManager.exists('global');
        const loaded = await sandbox.StorageManager.loadObject('global');
        let missingRejected = false;
        try {
            await sandbox.StorageManager.loadObject('missing');
        } catch (err) {
            missingRejected = String(err.message || err).indexOf('Savefile not found') >= 0;
        }
        check(
            'Hook MZ berjalan normal dan independen dari DataManager MV',
            sandbox.document.body.parentNode.style.overflow === 'hidden' &&
                sandbox.SceneManager.isGameActive() === true &&
                exists === true &&
                loaded &&
                loaded.title === 'MZ Save' &&
                missingRejected,
        );
    }

    console.log('\n== Skenario 9: Hook RPG Maker MZ v2 (TynMZ-0.2, assets/rpgmaker/mz/) ==');
    {
        // 按真实注入顺序仿真 MZ 1.1.0 的加载链：
        //   </head> 前（earlyHtml）  → 会话配置 + Effekseer 桩
        //   </body> 前（lateHook）   → __mz_hook.js
        //   main.js 动态插入          → effekseer.min.js → rmmz_core.js(+core hook)
        //                                → rmmz_managers.js(+managers hook)
        // lateHook 求值时 Graphics/SceneManager 都还不存在，这是关键时序。
        const makeMzV2Env = (sessionCfg) => {
            const saveDataManager = createSaveBridgeMock();
            const timers = [];
            const intervals = [];
            const sandbox = {
                console,
                setTimeout: (fn) => { timers.push(fn); return timers.length; },
                clearTimeout: () => {},
                // 基础层 polyfill 里 pinIsNwjs 用了 setInterval；缺它会在求值期直接 ReferenceError
                setInterval: (fn) => { intervals.push(fn); return intervals.length; },
                clearInterval: () => {},
                saveDataManager,
                StorageManager: {
                    objectToJson: (obj) => Promise.resolve(JSON.stringify(obj)),
                    jsonToObject: (json) => Promise.resolve(JSON.parse(json)),
                },
                document: {
                    body: { parentNode: { style: {} } },
                    documentElement: { clientHeight: 720, clientWidth: 1280 },
                    // 降采样路径要建 canvas：给出最小可用实现（只记录尺寸与 drawImage）
                    createElement: function (tag) {
                        if (tag !== 'canvas') return { style: {} };
                        var el = {
                            width: 0,
                            height: 0,
                            __drawn: null,
                            getContext: function (ctxType) {
                                if (ctxType === '2d') {
                                    return {
                                        imageSmoothingEnabled: false,
                                        imageSmoothingQuality: 'low',
                                        drawImage: function (src, dx, dy, dw, dh) {
                                            el.__drawn = { src: src, dw: dw, dh: dh };
                                        },
                                    };
                                }
                                // webgl：用于探 MAX_TEXTURE_SIZE
                                return {
                                    getParameter: function (p) { return p === 0x0D33 ? 4096 : 0; },
                                    getExtension: function () { return null; },
                                };
                            },
                        };
                        return el;
                    },
                },
                navigator: { userAgent: 'Mozilla/5.0 (Linux; Android 13) Chrome/124.0.0.0 Mobile' },
            };
            // 基础层 polyfill 会注册 window/document 事件：订阅表要能接收
            sandbox.addEventListener = function (type, fn) {
                (sandbox.__listeners = sandbox.__listeners || {});
                (sandbox.__listeners[type] = sandbox.__listeners[type] || []).push(fn);
            };
            sandbox.removeEventListener = function () {};
            sandbox.dispatchEvent = function () { return true; };
            sandbox.document.addEventListener = sandbox.addEventListener;
            sandbox.document.removeEventListener = sandbox.removeEventListener;
            sandbox.window = sandbox;
            sandbox.globalThis = sandbox;
            if (sessionCfg) sandbox.__tyranorRpg = sessionCfg;
            const context = vm.createContext(sandbox);
            return {
                sandbox,
                runCode: (code, filename) => vm.runInContext(code, context, { filename }),
                drainTimers: () => { while (timers.length) timers.shift()(); },
            };
        };

        // --- 9a: effekseer:false —— 桩必须拦住库的自我赋值 ---
        // 注意：桩**不是**让全局变 undefined。MZ 的 main.js 会无判空地调
        // effekseer.initRuntime(...)，undefined 会在那行抛错、启动断在 SceneManager.run
        // 之前（详见 9k）。正确形态是「带 initRuntime 的静默桩」。
        const blocked = makeMzV2Env({ engine: 'MZ', version: 'v2', effekseer: false, audioStreaming: true });
        blocked.runCode(RpgEffekseerGateStubSource(), 'earlyHtml-stub.js');
        // effekseer.min.js 求值时的自我赋值（IIFE 形态，挂到全局）
        blocked.runCode('window.effekseer = { createContext: function () { return {}; } };', 'effekseer.min.js');
        check(
            '9a effekseer:false 时全局保留静默桩（不被库的自我赋值覆盖）',
            blocked.sandbox.effekseer &&
                typeof blocked.sandbox.effekseer.initRuntime === 'function' &&
                blocked.sandbox.effekseer.createContext() === null,
        );

        // --- 9b: lateHook 在 Graphics 尚未定义时不得抛错，且稍后能装上兜底 ---
        let lateHookThrew = null;
        try {
            blocked.runCode(MZ_V2_HOOK_CODE, '__mz_hook.js');
        } catch (err) {
            lateHookThrew = err;
        }
        check(
            '9b lateHook 在 Graphics 未定义时求值不抛错（MZ 的 rmmz_core.js 由 main.js 动态插入）',
            lateHookThrew === null,
            lateHookThrew ? String(lateHookThrew.message || lateHookThrew) : undefined,
        );
        check(
            '9b lateHook 已把 overflow 处理到位（v0 hook 的既有契约）',
            blocked.sandbox.document.body.parentNode.style.overflow === 'hidden',
        );

        // rmmz_core.js 稍后求值：Graphics / SceneManager 出现
        blocked.runCode(
            'window.Graphics = { _createEffekseerContext: function () { this._effekseer = "real"; } };\n' +
            'window.SceneManager = { isGameActive: function () { return !!(window.top && window.top.document.hasFocus()); } };',
            'rmmz_core.js',
        );
        blocked.drainTimers();
        blocked.sandbox.Graphics._createEffekseerContext();
        check(
            '9b Graphics 出现后 lateHook 兜底生效（_effekseer 保持 null）',
            blocked.sandbox.Graphics._effekseer === null,
        );

        // --- 9c: effekseer 未关闭时不得装桩（默认放行） ---
        const allowed = makeMzV2Env({ engine: 'MZ', version: 'v2', effekseer: true });
        allowed.runCode(RpgEffekseerGateStubSource(), 'earlyHtml-stub.js');
        allowed.runCode('window.effekseer = { createContext: function () { return "ctx"; } };', 'effekseer.min.js');
        check('9c effekseer:true 时放行库的正常挂载', allowed.sandbox.effekseer === undefined ? false : true);

        // --- 9d: 缺 VorbisDecoder 时不改写 _shouldUseDecoder（否则 BGM 全哑） ---
        const noDecoder = makeMzV2Env({ engine: 'MZ', version: 'v2', effekseer: true, audioStreaming: true });
        noDecoder.runCode(
            'window.Utils = { canPlayOgg: function () { return false; } };\n' +
            'window.WebAudio = function () {};\n' +
            'WebAudio.prototype._shouldUseDecoder = function () { return false; };',
            'rmmz_core.js',
        );
        noDecoder.runCode(MZ_V2_CORE_HOOK_CODE, '__mz_core_hook.js');
        check(
            '9d 缺 VorbisDecoder 时保持原实现（不能把 BGM 钉哑）',
            noDecoder.sandbox.WebAudio.prototype._shouldUseDecoder() === false,
        );

        // --- 9e: 有 VorbisDecoder 且开关开 → 钉成流式解码 ---
        const streaming = makeMzV2Env({ engine: 'MZ', version: 'v2', effekseer: true, audioStreaming: true });
        streaming.runCode(
            'window.VorbisDecoder = function () {};\n' +
            'window.Utils = { canPlayOgg: function () { return true; } };\n' +
            'window.WebAudio = function () {};\n' +
            'WebAudio.prototype._shouldUseDecoder = function () { return !Utils.canPlayOgg() && typeof VorbisDecoder === "function"; };',
            'rmmz_core.js',
        );
        streaming.runCode(MZ_V2_CORE_HOOK_CODE, '__mz_core_hook.js');
        check(
            '9e 有 VorbisDecoder 时 _shouldUseDecoder 恒为 true（流式解码）',
            streaming.sandbox.WebAudio.prototype._shouldUseDecoder() === true,
        );

        // --- 9f: audioStreaming:false 时不改写 ---
        const noStreaming = makeMzV2Env({ engine: 'MZ', version: 'v2', effekseer: true, audioStreaming: false });
        noStreaming.runCode(
            'window.VorbisDecoder = function () {};\n' +
            'window.Utils = { canPlayOgg: function () { return true; } };\n' +
            'window.WebAudio = function () {};\n' +
            'WebAudio.prototype._shouldUseDecoder = function () { return false; };',
            'rmmz_core.js',
        );
        noStreaming.runCode(MZ_V2_CORE_HOOK_CODE, '__mz_core_hook.js');
        check(
            '9f audioStreaming:false 时不动 _shouldUseDecoder',
            noStreaming.sandbox.WebAudio.prototype._shouldUseDecoder() === false,
        );

        // --- 9h: 桩生效时 MZ 仍必须能启动到 SceneManager.run ---
        // 真实 main.js（MZ 1.9.0）在 SceneManager.run 之前会调
        // effekseer.initRuntime(wasm, onLoad, onError)，**且没有判空**；
        // onLoad 是进入 SceneManager.run 的唯一入口。桩必须保留 initRuntime 并
        // 按成功路径回调，否则启动链断在这行、页面黑屏。
        {
            const boot = makeMzV2Env({ engine: 'MZ', version: 'v2', effekseer: false, audioStreaming: true });
            boot.runCode(RpgEffekseerGateStubSource(), 'earlyHtml-stub.js');
            // effekseer.min.js 求值（IIFE 形态，自我赋值被桩吞掉）
            boot.runCode('window.effekseer = { createContext: function () { return {}; } };', 'effekseer.min.js');
            boot.runCode(
                'window.__bootLog = [];\n' +
                'window.__sceneRan = false;\n' +
                'window.SceneManager = { run: function () { window.__sceneRan = true; } };\n' +
                'try {\n' +
                '  effekseer.initRuntime("js/libs/effekseer.wasm",\n' +
                '    function () { window.__bootLog.push("onLoad"); window.SceneManager.run(); },\n' +
                '    function () { window.__bootLog.push("onError"); });\n' +
                '} catch (e) { window.__bootLog.push("threw:" + e.message); }',
                'main.js',
            );
            check(
                '9h 桩生效时启动链不抛错，SceneManager.run 仍被调用',
                boot.sandbox.__sceneRan === true &&
                    boot.sandbox.__bootLog.length === 1 &&
                    boot.sandbox.__bootLog[0] === 'onLoad',
                JSON.stringify(boot.sandbox.__bootLog) + ' sceneRan=' + String(boot.sandbox.__sceneRan),
            );
        }

        // --- 9i: MZ 1.2.0 的 main.js 会调 nw.Window.get().on('close', ...) ---
        // 真机症状：Main.run() 第一句就抛
        //   TypeError: nw.Window.get(...).on is not a function
        // 于是 loadMainScripts() 从未执行，rmmz_core.js 一个请求都没发出，页面永远黑屏
        // （只有阶段 H 的 15s 超时把黑屏揭开）。基础层 nw.gui 的 winStub 缺 .on。
        {
            const win = makeMzV2Env({ engine: 'MZ', version: 'v2', effekseer: true, audioStreaming: true });
            // 只加载基础层 —— 真机上 v2 层虽然也在，但 nw.gui 的 winStub 由基础层创建
            win.runCode(NWJS_POLYFILL_CODE, '__nwjs_polyfill.js');
            let stubOk = false;
            let detail = '';
            try {
                const w = win.sandbox.nw.Window.get();
                stubOk = typeof w.on === 'function' && typeof w.removeListener === 'function';
                if (!stubOk) {
                    detail = 'on=' + typeof w.on + ' removeListener=' + typeof w.removeListener;
                } else {
                    // NW.js 的语义：on/once/removeListener 都要能链式调用且不抛
                    let fired = 0;
                    w.on('close', function () { fired++; });
                    w.once('close', function () { fired++; });
                    w.emit('close');
                    stubOk = fired === 2;
                    detail = 'close handlers fired=' + fired;
                }
            } catch (err) {
                stubOk = false;
                detail = String(err && err.message || err);
            }
            check(
                '9i nw.Window.get() 必须提供 on/once/removeListener/emit（MZ 1.2 main.js 的 hookNwjsClose）',
                stubOk,
                detail,
            );
        }

        // --- 9j: 按 MZ 1.2.0 的 Main.run() 顺序跑一遍，确认能走到脚本加载 ---
        // 依真机日志回推的调用序：Main.run() → hookNwjsClose()（nw.Window.get().on）
        // → showLoadingSpinner() → testXhr() → loadMainScripts()。
        // 任何一步抛错，loadMainScripts 就不会执行，页面永远不会有 rmmz_core.js 请求。
        {
            const boot = makeMzV2Env({ engine: 'MZ', version: 'v2', effekseer: true, audioStreaming: true });
            boot.runCode(NWJS_POLYFILL_CODE, '__nwjs_polyfill.js');
            const result = boot.runCode(`
                window.__bootTrace = [];
                var Main = function () { this.xhrSucceeded = false; this.loadCount = 0; };
                Main.prototype.run = function () {
                    this.hookNwjsClose();
                    this.showLoadingSpinner();
                    this.testXhr();
                    this.loadMainScripts();
                };
                Main.prototype.hookNwjsClose = function () {
                    if (typeof nw === "undefined" || !nw.Window) return;
                    var win = nw.Window.get();
                    win.on("close", function () { window.__bootTrace.push("close-hook"); });
                };
                Main.prototype.showLoadingSpinner = function () { window.__bootTrace.push("spinner"); };
                Main.prototype.testXhr = function () { window.__bootTrace.push("xhr"); };
                Main.prototype.loadMainScripts = function () { window.__bootTrace.push("loadScripts"); };
                try {
                    new Main().run();
                    String(window.__bootTrace.join(","));
                } catch (e) {
                    "threw:" + e.message;
                }
            `, 'main.js');
            check(
                '9j MZ 1.2 的 Main.run() 全程不抛错并走到 loadMainScripts',
                result === 'spinner,xhr,loadScripts',
                String(result),
            );
            // 关闭事件也要能真的派发（用户按返回键时游戏靠它存盘/退出）
            const emitted = boot.runCode(`
                (function () {
                    try { nw.Window.get().emit("close"); } catch (e) { return "emit-threw:" + e.message; }
                    return String(window.__bootTrace.join(","));
                })()
            `, 'close.js');
            check(
                '9j nw.Window.get().emit("close") 能派发到已注册的处理器',
                emitted === 'spinner,xhr,loadScripts,close-hook',
                String(emitted),
            );
        }

        // --- 9k: 按这台设备上真实的 MZ 1.9.0 main.js 跑启动链 ---
        // 真实 main.js 的两处坑（Huntsman，v1.9.0）：
        //   :62  nw.Window.get().on("close", ...)     —— 缺 .on 会在 run() 里直接抛
        //   :145 effekseer.initRuntime(wasm, onLoad, onError) —— **无判空**
        // 若桩把 window.effekseer 置成 undefined，145 行必抛 → onLoad 不会被调用
        // → SceneManager.run 永不执行 → 黑屏。所以桩必须是「带 initRuntime 的静默实现」，
        // 并且要主动回调 onLoad，让游戏照常启动，只是没有粒子。
        {
            const boot = makeMzV2Env({ engine: 'MZ', version: 'v2', effekseer: false, audioStreaming: true });
            boot.runCode(NWJS_POLYFILL_CODE, '__nwjs_polyfill.js');
            boot.runCode(RpgEffekseerGateStubSource(), 'earlyHtml-stub.js');
            const trace = boot.runCode(`
                window.__trace = [];
                window.SceneManager = { run: function () { window.__trace.push("sceneRun"); } };
                // 模拟 effekseer.min.js 求值：它会给 window.effekseer 赋值
                try {
                    window.effekseer = {
                        createContext: function () { return { init: function () {}, setRestorationOfStatesFlag: function () {} }; },
                        initRuntime: function (url, onLoad) {
                            window.__trace.push("initRuntime:" + url);
                            if (typeof onLoad === "function") onLoad();
                        }
                    };
                } catch (e) { window.__trace.push("assign-threw:" + e.message); }

                // 真实 MZ 1.9 main.js 的启动链（去掉 DOM 部分）
                try {
                    if (typeof nw === "object") {
                        nw.Window.get().on("close", function () { nw.App.quit(); });
                        window.__trace.push("closeHook");
                    }
                    // :142-146 initEffekseerRuntime —— 无判空
                    effekseer.initRuntime("js/libs/effekseer.wasm",
                        function () { window.__trace.push("onLoad"); window.SceneManager.run(); },
                        function () { window.__trace.push("onError"); });
                } catch (e) {
                    window.__trace.push("threw:" + e.message);
                }
                String(window.__trace.join(","));
            `, 'main.js');
            check(
                '9k Effekseer 桩生效时 MZ 1.9 的启动链仍走到 SceneManager.run（initRuntime 无判空）',
                trace.indexOf('closeHook') >= 0 && trace.indexOf('onLoad') >= 0 && trace.indexOf('threw:') < 0,
                String(trace),
            );
            // 桩生效时仍不能真的创建 Effekseer context（那才是会杀进程的 WASM 实例化）
            const ctx = boot.runCode(`
                (function () {
                    try {
                        var g = window.effekseer;
                        if (!g) return "no-global";
                        if (typeof g.createContext !== "function") return "no-createContext";
                        var c = g.createContext();
                        return c ? "created" : "null";
                    } catch (e) { return "threw:" + e.message; }
                })()
            `, 'ctx.js');
            check(
                '9k 桩生效时不创建 Effekseer context（阻止 WASM 实例化）',
                ctx === 'null' || ctx === 'no-createContext',
                String(ctx),
            );
        }

        // --- 9l: 插件的 playtest 探测必须为 false ---
        // 真实样本（Huntsman，插件 mz3d.js:6986）用
        //   typeof process === 'object' && Object.prototype.toString.call(process) === '[object process]'
        // 判断是否处在 NW.js playtest：为真时会走 fs.promises.writeFile 往
        // global.__dirname 写文件（save/load 的调试数据）。垫片的 process 是普通对象，
        // 得到 "[object Object]" → false，写盘分支不会被走到。
        // 这是**有意**的：既避免往游戏目录写调试文件，也让这类插件的写路径在只读场景下不报错。
        {
            const env = makeMzV2Env({ engine: 'MZ', version: 'v2', effekseer: true, audioStreaming: true });
            env.runCode(NWJS_POLYFILL_CODE, '__nwjs_polyfill.js');
            const probe = env.runCode(`
                String(
                    typeof process === 'object' &&
                    Object.prototype.toString.call(process) === '[object process]'
                )
            `, 'playtest-probe.js');
            check(
                '9l 插件用 [object process] 探测 playtest 时必须判为 false（否则会往游戏目录写调试文件）',
                probe === 'false',
                String(probe),
            );
            // 顺带确认 global.__dirname 存在：mz3d 的 path.resolve(global.__dirname, ...) 依赖它
            const hasDirname = env.runCode('String(typeof global.__dirname === "string" && global.__dirname.length > 0)', 'dirname.js');
            check('9l global.__dirname 必须是可用的字符串路径', hasDirname === 'true', String(hasDirname));
        }

        // --- 9m: 超出 GPU 纹理上限的图必须降采样，而不是上传失败 ---
        // 真实样本（まもの博物館）：542 张图里 156 张宽度 > 4096、130 张 > 8192，
        // 最大 15400x1000。WebGL 的 texImage2D 对超过 GL_MAX_TEXTURE_SIZE 的图直接
        // 报 INVALID_VALUE 并丢弃，表现为「部分画面不显示」（真机日志
        // 11_28-19-34-33_667.log：「WebGL: INVALID_VALUE: texImage2D: width or height
        // out of range」）。必须在 _createBaseTexture 阶段按比例缩到上限内，
        // 并把缩放比记回 Bitmap，让 _createBaseTexture 之后的绘制仍按原尺寸布局。
        {
            const env = makeMzV2Env({ engine: 'MZ', version: 'v2', effekseer: true, audioStreaming: true });
            env.runCode(
                'window.PIXI = { BaseTexture: function (src, opts) { this.source = src; this.width = src.width; this.height = src.height; this.scaleMode = opts && opts.scaleMode; } };\n' +
                'window.Graphics = { width: 816, height: 624, _app: {}, frameCount: 0, _stretchHeight: function () { return 624; } }; \n' +
                'window.Bitmap = function () { this._image = null; this._url = ""; };\n' +
                'Bitmap.prototype._createBaseTexture = function (source) { this._baseTexture = new PIXI.BaseTexture(source); return this._baseTexture; };\n' +
                'window.Utils = window.Utils || {};\n' +
                '// 探到 GPU 上限：真机上由 gl.getParameter(gl.MAX_TEXTURE_SIZE) 给出\n' +
                'window.__tyranorMaxTexture = 4096;',
                'stubs.js',
            );
            env.runCode(MZ_V2_CORE_HOOK_CODE, '__mz_core_hook.js');
            // 模拟一张 15400x1000 的原图：降采样后必须 <= 上限且保持宽高比
            const result = env.runCode(`
                (function () {
                    var bmp = new Bitmap();
                    var fakeImage = { width: 15400, height: 1000, naturalWidth: 15400, naturalHeight: 1000 };
                    bmp._image = fakeImage;
                    bmp._createBaseTexture(fakeImage);
                    var bt = bmp._baseTexture;
                    return JSON.stringify({
                        w: bt.width, h: bt.height,
                        ratio: bmp._tyranorTextureScale || 1,
                        pass: bt.width <= 4096 && bt.height <= 4096
                    });
                })()
            `, 'downscale.js');
            let parsed = null;
            try { parsed = JSON.parse(result); } catch (e) { parsed = null; }
            check(
                '9m 超出纹理上限的图按比例降采样（15400x1000 → <= 4096）',
                parsed && parsed.pass === true,
                String(result),
            );
            check(
                '9m 降采样保持宽高比（15400:1000 → 约 15.4:1）',
                parsed && Math.abs((parsed.w / parsed.h) - 15.4) < 0.1,
                parsed ? `${parsed.w}x${parsed.h}` : String(result),
            );
            // 未超限的图不能被无谓缩小（会白白损失清晰度）
            const small = env.runCode(`
                (function () {
                    var bmp = new Bitmap();
                    var fakeImage = { width: 800, height: 600, naturalWidth: 800, naturalHeight: 600 };
                    bmp._image = fakeImage;
                    bmp._createBaseTexture(fakeImage);
                    return JSON.stringify({ w: bmp._baseTexture.width, h: bmp._baseTexture.height, ratio: bmp._tyranorTextureScale || 1 });
                })()
            `, 'small.js');
            check(
                '9m 未超限的图保持原尺寸（不做无谓降采样）',
                small.indexOf('"w":800') >= 0 && small.indexOf('"h":600') >= 0,
                String(small),
            );

            // 关键回归：PIXI 的 setFrame 在 frame 超出 baseTexture 时会直接抛
            // "Texture Error: frame does not fit inside the base Texture dimensions"。
            // MZ 用 bitmap.width（原图）调 setFrame，所以降采样后必须同步折算帧坐标。
            //
            // 时序要点：hook 追加在 rmmz_core.js **末尾**，那时 Sprite 已定义，
            // 打补丁生效。这里先建一个全新的环境与 Sprite，再装 hook，避免被测试桩覆盖。
            const frameEnv = makeMzV2Env({ engine: 'MZ', version: 'v2', effekseer: true, audioStreaming: true });
            frameEnv.runCode(
                'window.PIXI = { BaseTexture: function (src) { this.source = src; this.width = src.width; this.height = src.height; } };\n' +
                'window.Graphics = { width: 816, height: 624, _app: {} };\n' +
                'window.Bitmap = function () { this._image = null; };\n' +
                'Bitmap.prototype._createBaseTexture = function (source) { this._baseTexture = new PIXI.BaseTexture(source); this._baseTexture.width = source.width; this._baseTexture.height = source.height; };\n' +
                'window.Sprite = function () { this._bitmap = null; this._frames = []; };\n' +
                'Sprite.prototype.setFrame = function (x, y, w, h) { this._frames.push([x, y, w, h]); };',
                'stubs.js',
            );
            frameEnv.runCode(MZ_V2_CORE_HOOK_CODE, '__mz_core_hook.js');
            const frameOut = frameEnv.runCode(`
                (function () {
                    var fakeImage = { width: 15400, height: 1000, naturalWidth: 15400, naturalHeight: 1000 };
                    var bmp = new Bitmap();
                    bmp._image = fakeImage;
                    Object.defineProperty(bmp, "width", { get: function () { return fakeImage.width; } });
                    Object.defineProperty(bmp, "height", { get: function () { return fakeImage.height; } });
                    bmp._createBaseTexture(fakeImage);
                    var sp = new Sprite();
                    sp._bitmap = bmp;
                    sp.setFrame(0, 0, bmp.width, bmp.height);
                    var bt = bmp._baseTexture;
                    var c = sp._frames[0];
                    return JSON.stringify({
                        frame: c,
                        texture: [bt.width, bt.height],
                        fits: c[2] <= bt.width && c[3] <= bt.height,
                    });
                })()
            `, 'frame.js');
            let f = null;
            try { f = JSON.parse(frameOut); } catch (e) { f = null; }
            check(
                '9m 降采样后帧坐标同步折算，不会触发 PIXI 的 frame 越界抛错',
                f && f.fits === true,
                String(frameOut),
            );

            // TilingSprite 是**另一套** setFrame 实现（图块集走它），超限时同样要折算
            const tiling = env.runCode(`
                (function () {
                    if (typeof TilingSprite === 'undefined') return 'no-tilingsprite';
                    var patched = TilingSprite.prototype.setFrame && TilingSprite.prototype.setFrame.__tyranorFrameScaled === true;
                    return String(patched);
                })()
            `, 'tiling.js');
            check(
                '9m TilingSprite 的 setFrame 也被折算（图块集走这条路径）',
                tiling === 'true' || tiling === 'no-tilingsprite',
                String(tiling),
            );
        }

        // --- 9n: 就绪探针的真值表（用真的 JS 引擎执行探针本身） ---
        // 之前探针只判 SceneManager._scene，会在「场景刚 new 出来、数据库一个字节都没加载」
        // 时就判就绪——真机表现是黑屏（日志 11_28-19-34-33_667.log：mz scene ready
        // after 700ms 之后一个 data 请求都没有）。
        //
        // 同时守住两个**字段名**事实（实测 rmmz_core v1.1.0）：
        //   - MZ 的渲染器是 Graphics._app，没有 _renderer（那是 MV/PIXI4 的字段）；
        //   - Graphics.frameCount 只置 0、从不递增，不能作「主循环已跑帧」的判据。
        // 用错任何一个都会让探针恒 false → 每次启动都拖到 15s 超时。
        {
            const probeSrc = fs.readFileSync(
                path.join(__dirname, '..', '..', 'main', 'java', 'com', 'core', 'rpgmaker', 'RpgMakerStartupGate.kt'),
                'utf8',
            );
            const m = probeSrc.match(/READY_PROBE_JS\s*=\s*((?:"[^"]*"\s*\+?\s*)+)/);
            if (!m) {
                check('9n 能从 Kotlin 源码中取出 READY_PROBE_JS', false, '未匹配到定义');
            } else {
                const jsProbe = m[1].split('\n')
                    .map((line) => line.trim())
                    .filter((line) => line.startsWith('"'))
                    .map((line) => line.replace(/^"/, '').replace(/"\s*\+?\s*$/, ''))
                    .join('');
                const runProbe = (setupJs) => {
                    const shim = { frameCount: 0 };
                    shim.window = shim;
                    shim.globalThis = shim;
                    const ctx = vm.createContext(shim);
                    try {
                        vm.runInContext(setupJs, ctx, { filename: 'ready-setup.js' });
                        return vm.runInContext(jsProbe, ctx, { filename: 'ready-probe.js' });
                    } catch (e) {
                        return 'threw:' + e.message;
                    }
                };
                const cases = [
                    ['只有 _scene 不够', 'window.SceneManager={_scene:{}};', false],
                    ['数据库未加载不够', 'window.SceneManager={_scene:{}};window.DataManager={isDatabaseLoaded:function(){return false}};window.Graphics={_app:{}};', false],
                    ['渲染器未建不够', 'window.SceneManager={_scene:{}};window.DataManager={isDatabaseLoaded:function(){return true}};window.Graphics={};', false],
                    ['MZ 真实形状（_app）才就绪', 'window.SceneManager={_scene:{}};window.DataManager={isDatabaseLoaded:function(){return true}};window.Graphics={_app:{}};', true],
                    ['MV 的 _renderer 也兼容', 'window.SceneManager={_scene:{}};window.DataManager={isDatabaseLoaded:function(){return true}};window.Graphics={_renderer:{}};', true],
                    ['只有 frameCount 不算就绪', 'window.SceneManager={_scene:{}};window.DataManager={isDatabaseLoaded:function(){return true}};window.Graphics={frameCount:99};', false],
                ];
                for (const [label, setup, expected] of cases) {
                    const got = runProbe(setup);
                    check('9n ' + label, got === expected, '期望 ' + expected + '，实际 ' + String(got));
                }
                check(
                    '9n 探针不得依赖 frameCount（MZ 里它从不递增）',
                    !jsProbe.includes('frameCount'),
                    jsProbe,
                );
            }
        }

        // --- 9g: managers hook 的存档桥往返 + _stretchHeight 铺满 ---
        const saves = makeMzV2Env({ engine: 'MZ', version: 'v2', effekseer: true, audioStreaming: true });
        saves.runCode(
            'window.Graphics = { _stretchHeight: function () { return document.documentElement.clientHeight * 0.9; } };\n' +
            'window.SceneManager = {};',
            'rmmz_core.js',
        );
        saves.runCode(MZ_V2_CORE_HOOK_CODE, '__mz_core_hook.js');
        check(
            '9g Graphics._stretchHeight 铺满（不留 0.9 的黑边）',
            saves.sandbox.Graphics._stretchHeight() === 720,
            String(saves.sandbox.Graphics._stretchHeight()),
        );
        saves.runCode(MZ_V2_MANAGERS_HOOK_CODE, '__mz_managers_hook.js');
        await saves.sandbox.StorageManager.saveObject('global', { version: 1, title: 'MZ v2 Save' });
        const loadedV2 = await saves.sandbox.StorageManager.loadObject('global');
        check(
            '9g MZ v2 存档桥往返正常（引擎侧仍存明文 JSON）',
            saves.sandbox.StorageManager.exists('global') === true &&
                loadedV2 && loadedV2.title === 'MZ v2 Save' &&
                saves.sandbox.SceneManager.isGameActive() === true,
        );
    }
}

/** 与 RpgEffekseerGate.effekseerStubScript() 同源的桩脚本（去掉 <script> 包裹）。 */
function RpgEffekseerGateStubSource() {
    return `
        (function () {
          var cfg = window.__tyranorRpg;
          if (!cfg || cfg.effekseer !== false) return;
          var silent = {
            initRuntime: function (url, onLoad) {
              if (typeof onLoad === "function") onLoad();
            },
            createContext: function () { return null; }
          };
          try {
            Object.defineProperty(window, "effekseer", {
              configurable: true,
              get: function () { return silent; },
              set: function () {}
            });
          } catch (e) {}
        })();
    `;
}

runTests()
    .then(() => {
        console.log(failed === 0 ? '\nSemua skenario lulus.' : '\nGagal ' + failed + ' skenario.');
        process.exit(failed === 0 ? 0 : 1);
    })
    .catch((err) => {
        console.error('Harness error:', err);
        process.exit(1);
    });
