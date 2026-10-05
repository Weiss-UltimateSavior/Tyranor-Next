//! 开发诊断工具：检查 XP3 归档（列出条目 / 试解 / 验证魔数）。
//! 用法：cargo run --release --example xp3_inspect -- <archive.xp3> [extractDir]
//!
//! 仅用于本地排查（不进 APK）：对每个条目输出声明 size、实际解出字节数、
//! 常见图片/文本魔数是否完好，用于判定加密（cxdec 等）与口径差异。

use std::fs::File;
use std::io::{BufReader, Read, Seek, Write};
use std::path::Path;
use std::process::ExitCode;

use xp3::read::XP3Archive;

/// 与 src/common.rs 的 oneshot_async 同款：同步 future 专用 executor。
fn block_on<F: std::future::Future>(mut fut: F) -> F::Output {
    use std::pin::Pin;
    use std::task::{Context, Poll, RawWaker, RawWakerVTable, Waker};
    const VTABLE: RawWakerVTable = RawWakerVTable::new(|_| RAW, |_| {}, |_| {}, |_| {});
    const RAW: RawWaker = RawWaker::new(&(), &VTABLE);
    let waker = unsafe { Waker::from_raw(RAW) };
    let mut cx = Context::from_waker(&waker);
    let mut fut = unsafe { Pin::new_unchecked(&mut fut) };
    loop {
        match fut.as_mut().poll(&mut cx) {
            Poll::Ready(v) => return v,
            Poll::Pending => std::thread::yield_now(),
        }
    }
}

/// 同步 reader/seeker 适配（与 src/lib.rs 的 SyncIo 同思路）。
struct Xp3Sync<T>(T);

impl<T: Read + Unpin> tokio::io::AsyncRead for Xp3Sync<T> {
    fn poll_read(
        mut self: std::pin::Pin<&mut Self>,
        _: &mut std::task::Context<'_>,
        buf: &mut tokio::io::ReadBuf<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        match self.0.read(buf.initialize_unfilled()) {
            Ok(n) => {
                buf.set_filled(n);
                std::task::Poll::Ready(Ok(()))
            }
            Err(e) => std::task::Poll::Ready(Err(e)),
        }
    }
}

impl<T: Seek + Unpin> tokio::io::AsyncSeek for Xp3Sync<T> {
    fn start_seek(mut self: std::pin::Pin<&mut Self>, pos: std::io::SeekFrom) -> std::io::Result<()> {
        self.0.seek(pos)?;
        Ok(())
    }
    fn poll_complete(mut self: std::pin::Pin<&mut Self>, _: &mut std::task::Context<'_>) -> std::task::Poll<std::io::Result<u64>> {
        std::task::Poll::Ready(self.0.stream_position())
    }
}

impl<T: std::io::BufRead + Unpin> tokio::io::AsyncBufRead for Xp3Sync<T> {
    fn poll_fill_buf(self: std::pin::Pin<&mut Self>, _: &mut std::task::Context<'_>) -> std::task::Poll<std::io::Result<&[u8]>> {
        std::task::Poll::Ready(self.get_mut().0.fill_buf())
    }
    fn consume(self: std::pin::Pin<&mut Self>, amt: usize) {
        self.get_mut().0.consume(amt);
    }
}

fn magic_kind(bytes: &[u8]) -> &'static str {
    if bytes.starts_with(b"\x89PNG\r\n\x1a\n") {
        "PNG"
    } else if bytes.starts_with(b"\xFF\xD8\xFF") {
        "JPEG"
    } else if bytes.starts_with(b"GIF8") {
        "GIF"
    } else if bytes.len() >= 2 && &bytes[0..2] == b"BM" {
        "BMP"
    } else if bytes.starts_with(b"OggS") {
        "OGG"
    } else if bytes.starts_with(b"RIFF") {
        "RIFF"
    } else if bytes.starts_with(b"\x1A\x45\xDF\xA3") {
        "MKV"
    } else if bytes.starts_with(&[0xFE, 0xFE, 0x02, 0xFF, 0xFE]) {
        "KSD?"
    } else if bytes.starts_with(b"ID3") || bytes.first() == Some(&0xFF) {
        "MP3?"
    } else if bytes.iter().take(8).all(|b| b.is_ascii_graphic() || *b == b' ') {
        "TEXT?"
    } else {
        "?"
    }
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 2 {
        eprintln!("usage: xp3_inspect <archive.xp3> [extractDir]");
        return ExitCode::from(2);
    }
    let input = &args[1];
    let extract_dir = args.get(2).map(PathBuf::from);

    // 诊断工具常用来检查来源不明的归档：条目名先做与 safe_join 等价的清洗，
    // 拒绝绝对路径/`..`/`:`/NUL，扁平化为安全的本地相对名。
    fn sanitize_entry_name(name: &str) -> String {
        use std::collections::hash_map::DefaultHasher;
        use std::hash::{Hash, Hasher};
        let norm = name.replace('\\', "/");
        if norm.starts_with('/') || norm.contains('\0') {
            return format!("unsafe_{:016x}.bin", {
                let mut h = DefaultHasher::new();
                norm.hash(&mut h);
                h.finish()
            });
        }
        let flat: String = norm
            .split('/')
            .filter(|c| !c.is_empty() && *c != "." && *c != ".." && !c.contains(':'))
            .collect::<Vec<_>>()
            .join("_");
        if flat.is_empty() {
            format!("empty_{:016x}.bin", {
                let mut h = DefaultHasher::new();
                norm.hash(&mut h);
                h.finish()
            })
        } else {
            flat
        }
    }

    let file = match File::open(input) {
        Ok(f) => f,
        Err(e) => {
            eprintln!("open failed: {e}");
            return ExitCode::FAILURE;
        }
    };
    let mut archive = match block_on(XP3Archive::open(Xp3Sync(BufReader::new(file)))) {
        Ok(a) => a,
        Err(e) => {
            eprintln!("open as XP3 failed: {e}");
            return ExitCode::FAILURE;
        }
    };

    println!("entries: {}", archive.entries().len());
    let mut mismatch = 0usize;
    let mut extracted = 0usize;
    let mut err_count = 0usize;
    let len = archive.entries().len();
    for i in 0..len {
        let name = sanitize_entry_name(&archive.entries()[i].name.replace('\\', "/"));
        let declared = archive.entries()[i].size;
        let dest = extract_dir.as_ref().map(|d| d.join(&name));
        if let Some(dest) = &dest {
            if let Some(p) = dest.parent() {
                let _ = std::fs::create_dir_all(p);
            }
        }
        let mut reader = match block_on(archive.by_index(i)) {
            Some(Ok(f)) => f,
            _ => {
                println!("[{i:5}] ERR  by_index-failed  declared={declared}  {name}");
                err_count += 1;
                continue;
            }
        };
        let mut content = Vec::with_capacity(declared.min(64 * 1024 * 1024) as usize);
        if block_on(tokio::io::AsyncReadExt::read_to_end(&mut reader, &mut content)).is_err() {
            println!("[{i:5}] ERR  read-failed     declared={declared}  {name}");
            err_count += 1;
            continue;
        }
        let kind = magic_kind(&content);
        let mismatched = content.len() as u64 != declared;
        if mismatched {
            mismatch += 1;
        }
        if let Some(dest) = &dest {
            if let Ok(mut f) = File::create(dest) {
                let _ = f.write_all(&content);
                extracted += 1;
            }
        }
        if mismatched || i < 5 || extracted % 2000 == 0 {
            println!(
                "[{i:5}] {}  declared={:>12} actual={:>12} {:>5}  {}",
                if mismatched { "SIZE-DIFF" } else { "ok" },
                declared,
                content.len(),
                kind,
                name,
            );
        }
    }
    println!("--- entries={len} size-mismatch={mismatch} errors={err_count} extracted={extracted}");
    ExitCode::SUCCESS
}

use std::path::PathBuf;
