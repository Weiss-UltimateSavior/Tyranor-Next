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

    // 与 Kotlin RpgMakerFsBridge 逐条对齐的语义。
    // 关键：这个替身过去比本体「更正确」（exists 真查磁盘、write 恒返回 true），
    // 导致「existsSync 不查磁盘 / 写失败被吞 / 16MB 上限」这些缺陷对测试**不可见**。
    // 现在每个方法都按 Kotlin 的行为实现（含其约束），测试才能抓住真实偏差。
    const MAX_READ_BYTES = 16 * 1024 * 1024;
    const MAX_WRITE_BYTES = 16 * 1024 * 1024;    // 与 Kotlin MAX_WRITE_BYTES 对齐
    const MAX_ZLIB_BYTES = 64 * 1024 * 1024;     // 与 Kotlin EnvBridge.MAX_BYTES 对齐
    const fsBridge = {
        baseDir: () => contentRoot,
        dataDir: () => nodePath.join(gameRoot, 'AppData'),
        // Kotlin: resolve(path)?.exists() == true —— 会查磁盘
        exists: (p) => { const f = inside(p); return f !== null && fs.existsSync(f); },
        isFile: (p) => { const f = inside(p); return f !== null && fs.existsSync(f) && fs.statSync(f).isFile(); },
        isDir: (p) => { const f = inside(p); return f !== null && fs.existsSync(f) && fs.statSync(f).isDirectory(); },
        readText: (p) => {
            const f = inside(p);
            if (f === null || !fs.existsSync(f) || !fs.statSync(f).isFile()) return null;
            if (fs.statSync(f).size > MAX_READ_BYTES) return null;   // Kotlin 的超限拒绝
            return fs.readFileSync(f, 'utf8');
        },
        readBase64: (p) => {
            const f = inside(p);
            if (f === null || !fs.existsSync(f) || !fs.statSync(f).isFile()) return null;
            if (fs.statSync(f).size > MAX_READ_BYTES) return null;
            return fs.readFileSync(f).toString('base64');
        },
        readdir: (p) => { const f = inside(p); return (f && fs.existsSync(f) && fs.statSync(f).isDirectory()) ? JSON.stringify(fs.readdirSync(f)) : '[]'; },
        stat: (p) => {
            const f = inside(p); if (!f || !fs.existsSync(f)) return '';
            const s = fs.statSync(f);
            return JSON.stringify({ file: s.isFile(), dir: s.isDirectory(), size: s.size, mtime: s.mtimeMs });
        },
        // Kotlin: ENOENT / EISDIR / E2BIG / EPERM（读失败的原因码）
        errorFor: (p) => {
            const f = inside(p);
            if (f === null) return 'EPERM';
            if (fs.existsSync(f) && fs.statSync(f).isDirectory()) return 'EISDIR';
            if (fs.existsSync(f) && fs.statSync(f).size > MAX_READ_BYTES) return 'E2BIG';
            return 'ENOENT';
        },
        writeText: (p, d) => {
            const f = inside(p); if (f === null) return false;      // 越界 → false（Kotlin 如此）
            const text = d == null ? '' : String(d);
            if (NodeBuffer.byteLength(text, 'utf8') > MAX_WRITE_BYTES) return false;
            fs.mkdirSync(nodePath.dirname(f), { recursive: true });
            fs.writeFileSync(f, text);
            return true;
        },
        writeBase64: (p, d) => {
            const f = inside(p); if (f === null) return false;
            const bytes = NodeBuffer.from(d || '', 'base64');
            if (bytes.length > MAX_WRITE_BYTES) return false;
            fs.mkdirSync(nodePath.dirname(f), { recursive: true });
            fs.writeFileSync(f, bytes);
            return true;
        },
        makeDirs: (p) => { const f = inside(p); if (f === null) return false; fs.mkdirSync(f, { recursive: true }); return true; },
        remove: (p) => {
            const f = inside(p);
            if (f === null) return false;
            if (!fs.existsSync(f)) return true;
            if (fs.lstatSync(f).isDirectory()) fs.rmdirSync(f); else fs.unlinkSync(f);
            return true;
        },
        setTimes: (p, mtime) => { const f = inside(p); if (f === null) return false; try { fs.utimesSync(f, mtime / 1000, mtime / 1000); return true; } catch (e) { return false; } },
    };

    const b64 = (bufOrStr) => NodeBuffer.isBuffer(bufOrStr) ? bufOrStr.toString('base64') : NodeBuffer.from(String(bufOrStr), 'binary').toString('base64');
    const unb64 = (s) => NodeBuffer.from(String(s || ''), 'base64');
    // 与 Kotlin encode() 对齐：unknown → hex；支持 base64url / latin1 / utf8
    const hexOrB64 = (buf, out) => {
        switch (String(out || 'hex').toLowerCase()) {
            case 'base64': return buf.toString('base64');
            case 'base64url': return buf.toString('base64url');
            case 'latin1': case 'binary': return buf.toString('latin1');
            case 'utf8': case 'utf-8': return buf.toString('utf8');
            default: return buf.toString('hex');
        }
    };
    const kSupportedDigests = ['md5', 'sha1', 'sha224', 'sha256', 'sha384', 'sha512'];

    const envBridge = {
        argv: () => JSON.stringify(['--' + '0'.repeat(32)]),
        digest: (algo, dataB64, out) => {
            // 与 Kotlin 对齐：仅支持上表算法，其余返回空串（JS 侧转成抛错）
            const name = String(algo || 'sha256').toLowerCase().replace(/-/g, '');
            if (kSupportedDigests.indexOf(name) < 0) return '';
            try { return hexOrB64(crypto.createHash(name).update(unb64(dataB64)).digest(), out); }
            catch (e) { return ''; }
        },
        hmac: (algo, keyB64, dataB64, out) => {
            // 与 Kotlin 对齐：未知算法返回空串，不能静默降级成 sha256
            const name = String(algo || 'sha256').toLowerCase().replace(/-/g, '');
            if (kSupportedDigests.indexOf(name) < 0) return '';
            try { return hexOrB64(crypto.createHmac(name, unb64(keyB64)).update(unb64(dataB64)).digest(), out); }
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
                    case 'gunzip': return zlib.gunzipSync(input, { maxOutputLength: MAX_ZLIB_BYTES }).toString('base64');
                    case 'unzip': return zlib.unzipSync(input, { maxOutputLength: MAX_ZLIB_BYTES }).toString('base64');
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
