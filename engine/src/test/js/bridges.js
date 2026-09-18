// 仿真两个原生桥（TyranorFs / TyranorEnv），供 harness 使用。
// 与 Kotlin 侧 RpgMakerFsBridge / RpgMakerEnvBridge 同语义，用 Node 原生能力实现，
// 因此可与真 Node 的行为逐项对照——仿真层若有偏差会被 harness 的断言直接暴露。
const nodeRequire = require;
const fs = nodeRequire('fs');
const nodePath = nodeRequire('path');
const crypto = nodeRequire('crypto');
const zlib = nodeRequire('zlib');
// 显式捕获宿主 Buffer：harness 会删除 global.Buffer 以模拟 WebView（无原生 Buffer），
// 若此处引用全局 Buffer 会在运行期抛 ReferenceError（表现为所有原生能力静默返回空串）。
const NodeBuffer = nodeRequire('buffer').Buffer;

function createBridges(gameRoot, contentRoot) {
    const rootReal = fs.realpathSync.native(gameRoot);

    function toAbs(p) {
        const s = String(p == null ? '' : p).replace(/\\/g, '/').replace(/^file:\/\//, '');
        if (!s) return null;
        return nodePath.isAbsolute(s) ? s : nodePath.join(contentRoot, s);
    }
    function inside(p) {
        const abs = toAbs(p);
        if (abs === null) return null;
        let c;
        try { c = fs.realpathSync.native(abs); }
        catch (e) { c = nodePath.resolve(abs).replace(/[\\/]+$/, ''); }
        return (c === rootReal || c.startsWith(rootReal + nodePath.sep)) ? c : null;
    }

    const fsBridge = {
        baseDir: () => contentRoot,
        dataDir: () => nodePath.join(gameRoot, 'AppData'),
        exists: (p) => inside(p) !== null && fs.existsSync(inside(p)),
        isFile: (p) => { const f = inside(p); return f !== null && fs.existsSync(f) && fs.statSync(f).isFile(); },
        isDir: (p) => { const f = inside(p); return f !== null && fs.existsSync(f) && fs.statSync(f).isDirectory(); },
        readText: (p) => { const f = inside(p); return (f && fs.existsSync(f) && fs.statSync(f).isFile()) ? fs.readFileSync(f, 'utf8') : null; },
        readBase64: (p) => { const f = inside(p); return (f && fs.existsSync(f) && fs.statSync(f).isFile()) ? fs.readFileSync(f).toString('base64') : null; },
        readdir: (p) => { const f = inside(p); return (f && fs.existsSync(f) && fs.statSync(f).isDirectory()) ? JSON.stringify(fs.readdirSync(f)) : '[]'; },
        stat: (p) => {
            const f = inside(p); if (!f || !fs.existsSync(f)) return '';
            const s = fs.statSync(f);
            return JSON.stringify({ file: s.isFile(), dir: s.isDirectory(), size: s.size, mtime: s.mtimeMs });
        },
        writeText: (p, d) => { const f = inside(p); if (!f) return false; fs.mkdirSync(nodePath.dirname(f), { recursive: true }); fs.writeFileSync(f, d == null ? '' : String(d)); return true; },
        writeBase64: (p, d) => { const f = inside(p); if (!f) return false; fs.mkdirSync(nodePath.dirname(f), { recursive: true }); fs.writeFileSync(f, NodeBuffer.from(d || '', 'base64')); return true; },
        makeDirs: (p) => { const f = inside(p); if (!f) return false; fs.mkdirSync(f, { recursive: true }); return true; },
        // 目录与文件都要能删（Kotlin 侧 File.delete() 两者皆可，仿真需对齐）
        remove: (p) => {
            const f = inside(p);
            if (!f) return true;
            if (!fs.existsSync(f)) return true;
            if (fs.lstatSync(f).isDirectory()) fs.rmdirSync(f); else fs.unlinkSync(f);
            return true;
        },
        setTimes: (p, mtime) => { const f = inside(p); if (!f) return false; try { fs.utimesSync(f, mtime / 1000, mtime / 1000); return true; } catch (e) { return false; } },
    };

    const b64 = (bufOrStr) => NodeBuffer.isBuffer(bufOrStr) ? bufOrStr.toString('base64') : NodeBuffer.from(String(bufOrStr), 'binary').toString('base64');
    const unb64 = (s) => NodeBuffer.from(String(s || ''), 'base64');
    const hexOrB64 = (buf, out) => String(out || 'hex').toLowerCase() === 'base64' ? buf.toString('base64') : buf.toString('hex');

    const envBridge = {
        argv: () => JSON.stringify(['--' + '0'.repeat(32)]),
        digest: (algo, dataB64, out) => {
            try { return hexOrB64(crypto.createHash(String(algo || 'sha256').replace('-', '')).update(unb64(dataB64)).digest(), out); }
            catch (e) { return ''; }
        },
        hmac: (algo, keyB64, dataB64, out) => {
            try { return hexOrB64(crypto.createHmac(String(algo || 'sha256').replace('-', ''), unb64(keyB64)).update(unb64(dataB64)).digest(), out); }
            catch (e) { return ''; }
        },
        randomBytes: (n) => (n > 0 ? crypto.randomBytes(n).toString('base64') : ''),
        randomUuid: () => crypto.randomUUID(),
        pbkdf2: (pwB64, saltB64, iter, len, digest) => {
            try {
                return crypto.pbkdf2Sync(unb64(pwB64), unb64(saltB64), iter, len, String(digest || 'sha1')).toString('base64');
            } catch (e) { return ''; }
        },
        timingSafeEqual: (a, b) => {
            const x = unb64(a), y = unb64(b);
            return x.length === y.length && crypto.timingSafeEqual(x, y);
        },
        cipher: (algo, keyB64, ivB64, dataB64, encrypt, autoPadding) => {
            try {
                const name = String(algo || '').toLowerCase();
                const parts = name.split('-');
                if (parts[0] !== 'aes') return '';
                const keyLen = parts[1] === '128' ? 16 : parts[1] === '192' ? 24 : parts[1] === '256' ? 32 : 0;
                if (!keyLen) return '';
                const key = unb64(keyB64);
                if (key.length !== keyLen) return '';
                const mode = parts[2];
                const ivLen = mode === 'ecb' ? 0 : 16;
                const iv = unb64(ivB64);
                if (ivLen && iv.length !== ivLen) return '';
                // 解密必须走 createDecipheriv：用 createCipheriv 会得到「加密后的乱码」
                const factory = encrypt ? crypto.createCipheriv : crypto.createDecipheriv;
                const c = factory(name, key, ivLen ? iv : null, { autoPadding: autoPadding !== false });
                return NodeBuffer.concat([c.update(unb64(dataB64)), c.final()]).toString('base64');
            } catch (e) { return ''; }
        },
        zlib: (mode, dataB64, level) => {
            try {
                const input = unb64(dataB64);
                const opts = { level: Number.isInteger(level) && level >= 0 && level <= 9 ? level : -1 };
                switch (mode) {
                    case 'inflate': return zlib.inflateSync(input).toString('base64');
                    case 'inflateRaw': return zlib.inflateRawSync(input).toString('base64');
                    case 'gunzip': return zlib.gunzipSync(input).toString('base64');
                    case 'unzip': return zlib.unzipSync(input).toString('base64');
                    case 'deflate': return zlib.deflateSync(input, opts).toString('base64');
                    case 'deflateRaw': return zlib.deflateRawSync(input, opts).toString('base64');
                    case 'gzip': return zlib.gzipSync(input, opts).toString('base64');
                    default: return '';
                }
            } catch (e) { return ''; }
        },
        crc32: (dataB64) => {
            const table = createBridges._crcTable || (createBridges._crcTable = (() => {
                const t = new Int32Array(256);
                for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1; t[n] = c; }
                return t;
            })());
            const buf = unb64(dataB64);
            let crc = -1;
            for (let i = 0; i < buf.length; i++) crc = (crc >>> 8) ^ table[(crc ^ buf[i]) & 0xFF];
            return ((crc ^ -1) >>> 0).toString(16).padStart(8, '0');
        },
        totalMemory: () => 512 * 1024 * 1024,
        maxMemory: () => 1024 * 1024 * 1024,
        freeMemory: () => 256 * 1024 * 1024,
    };

    return { fsBridge, envBridge };
}

module.exports = { createBridges };
