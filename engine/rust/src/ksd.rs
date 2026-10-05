//! Kirikiri KSD mode-2 filter（XP3 内文本条目的内层包裹），XP3 解包的隐性支持。
//!
//! 格式（逆向自 Luv-Ray/krkr-save-tools）：`FE FE 02 FF FE` +
//! [compressed_len:i64] [uncompressed_len:i64] + 2 字节 zlib 头 + raw deflate，
//! 解出为 UTF-16 LE 文本。
//!
//! 无独立入口：krkrsdl3 引擎的 TextStream 读条目时也原生解码该包裹
//!（mode0/1/2 皆支持），故解包产物无论是否解开游戏都能跑；此处顺带
//! 解开是为了让导出的脚本文本可直接编辑。

use flate2::read::DeflateDecoder;
use std::io::Read;

// KSD 只包裹文本条目（真实大小 KB 级）：16 MiB 已远超需要，同时把敌意
// 声明的单条 native 堆分配压到低内存机型可承受的范围。
const MAX_MODE2_OUT: usize = 16 * 1024 * 1024;

/// `data` starts after the 5-byte header (i.e. at offset 0x05):
/// [compressed_len:i64][uncompressed_len:i64][compressed].
fn decompress_mode2(data: &[u8]) -> Result<(Vec<u8>, u64), String> {
    if data.len() < 16 { return Err("KSD: truncated mode2 header".to_string()); }
    let compressed_i = i64::from_le_bytes(data[0..8].try_into().unwrap());
    let uncompressed_i = i64::from_le_bytes(data[8..16].try_into().unwrap());
    // Negative lengths would wrap to huge usize on `as` — reject outright.
    if compressed_i < 0 || uncompressed_i < 0 {
        return Err(format!("KSD: negative length ({compressed_i}, {uncompressed_i})"));
    }
    let compressed_len = compressed_i as usize;
    let uncompressed_len = uncompressed_i as usize;
    // checked_add：compressed_len 来自归档内容（攻击者可控），杜绝任何平台上的溢出/回绕。
    if compressed_len < 2 || 16usize.checked_add(compressed_len).map_or(true, |end| end > data.len()) {
        return Err(format!("KSD: bad compressed_len {compressed_len}"));
    }
    if uncompressed_len > MAX_MODE2_OUT {
        return Err(format!("KSD: uncompressed size too large ({uncompressed_len})"));
    }
    // compressed_len includes the 2-byte zlib header — skip it, then raw deflate
    let deflate_data = &data[16 + 2..16 + compressed_len];
    let dec = DeflateDecoder::new(deflate_data);
    // Cap the DECODED output at the declared size too: a malicious entry can
    // declare a small uncompressed_len while carrying a stream that inflates
    // far larger (bomb). Read::take truncates silently to the declared size.
    let mut limited = dec.take(uncompressed_len as u64);
    let mut out = Vec::with_capacity(uncompressed_len.min(1 << 20));
    limited.read_to_end(&mut out).map_err(|e| format!("KSD: inflate {e}"))?;
    Ok((out, uncompressed_len as u64))
}

/// Probe 解码结果的弱合理性校验（同步自上游 usefulunpack f0620cac）。
/// 真实 KSD mode-2 载荷是 UTF-16 LE 文本：要求 UTF-16 严格合法（无未配对
/// 代理）且除换行/回车/制表外不含控制字符。任何 ≤16 MiB 的条目都能通过
/// 5 字节魔数探测——头字节恰好为 `FE FE 02 FF FE` 的二进制（如某些 TGA）
/// 否则会被"解码"成垃圾覆盖原文件；校验不过就原样透传。
fn plausible_utf16_text(bytes: &[u8]) -> bool {
    if bytes.len() % 2 != 0 { return false; }
    let units = bytes.chunks_exact(2).map(|c| u16::from_le_bytes([c[0], c[1]]));
    std::char::decode_utf16(units).all(|r| {
        r.map(|c| !c.is_control() || matches!(c, '\n' | '\r' | '\t')).unwrap_or(false)
    })
}

/// Detects a KSD mode-2 scrambled blob — used as a Kirikiri filter inside XP3
/// archives. Returns the decoded bytes when the magic matches AND the decode
/// passes the UTF-16 plausibility check, else None so the caller writes the
/// content as-is.
pub fn ksd_mode2_decode(data: &[u8]) -> Option<Vec<u8>> {
    if data.len() >= 5
        && data[0] == 0xFE && data[1] == 0xFE && data[2] == 0x02
        && data[3] == 0xFF && data[4] == 0xFE
    {
        decompress_mode2(&data[5..]).ok()
            .filter(|(bytes, _)| plausible_utf16_text(bytes))
            .map(|(bytes, _)| bytes)
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use flate2::write::ZlibEncoder;
    use std::io::Write;

    fn wrap(compressed: &[u8], declared: i64) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(&[0xFE, 0xFE, 2, 0xFF, 0xFE]);
        out.extend_from_slice(&(compressed.len() as i64).to_le_bytes());
        out.extend_from_slice(&declared.to_le_bytes());
        out.extend_from_slice(compressed);
        out
    }

    #[test]
    fn mode2_decompression_bomb_capped() {
        // declared uncompressed_len huge — decode fails, magic path yields None
        let mut enc = ZlibEncoder::new(Vec::new(), flate2::Compression::new(6));
        enc.write_all(&[0x41u8; 16]).unwrap();
        let compressed = enc.finish().unwrap();
        assert!(ksd_mode2_decode(&wrap(&compressed, MAX_MODE2_OUT as i64 + 1)).is_none());
    }

    #[test]
    fn mode2_declared_small_actual_big_clamped() {
        // Declared uncompressed_len = 1 KiB but the deflate stream inflates
        // to 1 MiB: the decoded buffer must be clamped to the declared size,
        // never grow unbounded.
        let mut enc = ZlibEncoder::new(Vec::new(), flate2::Compression::new(6));
        enc.write_all(&[0x41u8; 1024 * 1024]).unwrap();
        let compressed = enc.finish().unwrap();
        assert!(compressed.len() < 4096, "zlib of 1MiB 'A' must be tiny, got {}", compressed.len());
        let buf = wrap(&compressed, 1024);
        let out = ksd_mode2_decode(&buf).expect("magic must match");
        assert_eq!(out.len(), 1024, "decoded buffer must be clamped to the declared size");
    }

    #[test]
    fn non_ksd_magic_passes_through_as_none() {
        assert!(ksd_mode2_decode(b"plain text file").is_none());
        // 魔数不足 5 字节也不得误判。
        assert!(ksd_mode2_decode(&[0xFE, 0xFE, 0x02]).is_none());
    }

    #[test]
    fn mode2_probe_rejects_non_text_magic_collision() {
        // 任何 ≤16MiB 条目都能过 XP3 探测的 5 字节魔数；二进制（TGA 型碰撞）
        // 不得被"解码"成垃圾。载荷必须先 zlib 压缩——否则 deflate 截断会先于
        // UTF-16 校验失败，测试就失去意义。非法 UTF-16（孤立高位代理）→ None。
        let inner = {
            let mut b = vec![0x41u8, 0x00]; // 'A'
            b.extend_from_slice(&[0x00, 0xD8]); // 孤立高位代理 U+D800
            b.extend_from_slice(&[0x42u8, 0x00]); // 'B'，无低位代理跟随
            b
        };
        assert_eq!(ksd_mode2_decode(&wrap_zlib(&inner)), None);

        // UTF-16 合法但含非空白控制字符（U+0001）→ 同样拒绝。
        let inner2 = vec![0x41u8, 0x00, 0x01, 0x00, 0x42u8, 0x00];
        assert_eq!(ksd_mode2_decode(&wrap_zlib(&inner2)), None);
    }

    /// 按真实 KSD 布局构造条目：zlib(inner) + 声明未压缩长度。
    fn wrap_zlib(inner: &[u8]) -> Vec<u8> {
        let mut enc = ZlibEncoder::new(Vec::new(), flate2::Compression::new(6));
        enc.write_all(inner).unwrap();
        let compressed = enc.finish().unwrap();
        wrap(&compressed, inner.len() as i64)
    }
}
