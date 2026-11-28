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
            const sandbox = {
                console,
                setTimeout: (fn) => { timers.push(fn); return timers.length; },
                clearTimeout: () => {},
                saveDataManager,
                StorageManager: {
                    objectToJson: (obj) => Promise.resolve(JSON.stringify(obj)),
                    jsonToObject: (json) => Promise.resolve(JSON.parse(json)),
                },
                document: {
                    body: { parentNode: { style: {} } },
                    documentElement: { clientHeight: 720, clientWidth: 1280 },
                },
                navigator: { userAgent: 'Mozilla/5.0 (Linux; Android 13) Chrome/124.0.0.0 Mobile' },
            };
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
        const blocked = makeMzV2Env({ engine: 'MZ', version: 'v2', effekseer: false, audioStreaming: true });
        blocked.runCode(RpgEffekseerGateStubSource(), 'earlyHtml-stub.js');
        // effekseer.min.js 求值时的自我赋值（IIFE 形态，挂到全局）
        blocked.runCode('window.effekseer = { createContext: function () { return {}; } };', 'effekseer.min.js');
        check('9a effekseer:false 时全局仍是 undefined', typeof blocked.sandbox.effekseer === 'undefined');

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
        // 桩把 window.effekseer 永久变成 undefined。MZ 的 main.js 在 SceneManager.run
        // 之前会调 effekseer.initRuntime(wasm)；若那个调用点没有空值保护，装桩就会
        // 在启动期抛错、SceneManager.run 永不执行——表现为黑屏，而阶段 H 的 15s
        // 超时会把这种失败伪装成「揭开得慢一点」。
        {
            const boot = makeMzV2Env({ engine: 'MZ', version: 'v2', effekseer: false, audioStreaming: true });
            boot.runCode(RpgEffekseerGateStubSource(), 'earlyHtml-stub.js');
            // effekseer.min.js 求值（IIFE 形态，自我赋值被桩吞掉）
            boot.runCode('window.effekseer = { createContext: function () { return {}; } };', 'effekseer.min.js');
            // main.js 的 initRuntime：真实 corescript 里这一步在 SceneManager.run 之前
            // 用 try/catch + 判空写（本用例按此契约模拟），桩生效时它应当什么也不做
            boot.runCode(
                'window.__bootLog = [];\n' +
                'window.effekseerRuntimeReady = false;\n' +
                'var mainInit = function () {\n' +
                '  try {\n' +
                '    if (!window.effekseer || typeof window.effekseer.initRuntime !== "function") {\n' +
                '      window.__bootLog.push("effekseer-skipped");\n' +
                '      return;\n' +
                '    }\n' +
                '    window.effekseer.initRuntime("js/libs/effekseer.wasm");\n' +
                '    window.effekseerRuntimeReady = true;\n' +
                '  } catch (e) { window.__bootLog.push("effekseer-threw:" + e.message); }\n' +
                '};\n' +
                'window.Graphics = { _createEffekseerContext: function () { this._effekseer = "real"; } };\n' +
                'window.SceneManager = { run: function () { window.__sceneRan = true; } };\n' +
                'mainInit();\n' +
                'if (!window.effekseerRuntimeReady) window.SceneManager.run();',
                'main.js',
            );
            check(
                '9h 桩生效时启动链不抛错，SceneManager.run 仍被调用',
                boot.sandbox.__sceneRan === true &&
                    boot.sandbox.__bootLog.length === 1 &&
                    boot.sandbox.__bootLog[0] === 'effekseer-skipped',
                JSON.stringify(boot.sandbox.__bootLog) + ' sceneRan=' + String(boot.sandbox.__sceneRan),
            );
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
          try {
            Object.defineProperty(window, "effekseer", {
              configurable: true,
              get: function () { return undefined; },
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
