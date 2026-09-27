//! 共享基础设施：进度槽、进度 IO 包装、同步-异步桥与路径/JSON 工具。
//!
//! 由原 `archive_common` crate 收敛而来（XP3 单格式后不再需要独立 crate）：
//! rar/分卷等无关路径的代码（split_volumes、BoundedWriter 等）已删除。

use jni::objects::JString;
use jni::JNIEnv;
use std::collections::BTreeSet;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Mutex;

/// Byte-based progress stores. The cdylib statically links this module, so the
/// statics below are process-wide for this library.
macro_rules! progress_store {
    ($name:ident) => {
        pub mod $name {
            use super::*;

            static BYTES: AtomicU64 = AtomicU64::new(0);
            static TOTAL: AtomicU64 = AtomicU64::new(0);
            static FILE_BYTES: AtomicU64 = AtomicU64::new(0);
            static FILE_TOTAL: AtomicU64 = AtomicU64::new(0);
            static FNAME: Mutex<String> = Mutex::new(String::new());
            static CANCEL: AtomicBool = AtomicBool::new(false);

            pub fn reset(total_bytes: u64) {
                BYTES.store(0, Ordering::Relaxed);
                TOTAL.store(total_bytes, Ordering::Relaxed);
                FILE_BYTES.store(0, Ordering::Relaxed);
                FILE_TOTAL.store(0, Ordering::Relaxed);
                // NOTE: the CANCEL flag is deliberately preserved — a cancel
                // pressed during a pre-scan must survive into the extraction
                // phase. Call clear_cancel() at the very start of a fresh
                // operation (before any pre-scan) instead.
                *FNAME.lock().unwrap_or_else(|e| e.into_inner()) = String::new();
            }

            /// Clears the cancel flag. Call once at the start of each new
            /// operation (JNI entry, before the pre-scan) so a stale cancel
            /// from a previous operation can't poison this one.
            pub fn clear_cancel() { CANCEL.store(false, Ordering::Relaxed); }

            /// Marks the start of a new member: resets the per-file byte
            /// counter and records the member's total size.
            pub fn set_file(total: u64) {
                FILE_TOTAL.store(total, Ordering::Relaxed);
                FILE_BYTES.store(0, Ordering::Relaxed);
            }

            /// Accumulates into both the overall and the current-file counter.
            pub fn add_bytes(n: u64) {
                BYTES.fetch_add(n, Ordering::Relaxed);
                FILE_BYTES.fetch_add(n, Ordering::Relaxed);
            }

            pub fn set_name(name: &str) { *FNAME.lock().unwrap_or_else(|e| e.into_inner()) = name.to_string(); }

            /// Expands the overall total mid-run (XP3 KSD unwrap can grow output
            /// beyond the declared entry size) so BYTES/TOTAL stays consistent.
            /// Saturating: a hostile index can pin TOTAL at u64::MAX, and a
            /// wrapping fetch_add would collapse it back to ~0.
            pub fn add_total(n: u64) {
                let _ = TOTAL.fetch_update(Ordering::Relaxed, Ordering::Relaxed, |t| Some(t.saturating_add(n)));
            }

            /// Shrinks the overall total when actual output is smaller than the
            /// declared entry size (KSD unwrap yielding less), same goal as add_total.
            pub fn sub_total(n: u64) {
                let _ = TOTAL.fetch_update(Ordering::Relaxed, Ordering::Relaxed, |t| Some(t.saturating_sub(n)));
            }

            pub fn cancel() { CANCEL.store(true, Ordering::Relaxed); }
            pub fn cancelled() -> bool { CANCEL.load(Ordering::Relaxed) }
            pub fn bytes() -> u64 { BYTES.load(Ordering::Relaxed) }
            pub fn total_bytes() -> u64 { TOTAL.load(Ordering::Relaxed) }
            pub fn file_bytes() -> u64 { FILE_BYTES.load(Ordering::Relaxed) }
            pub fn file_total() -> u64 { FILE_TOTAL.load(Ordering::Relaxed) }
            pub fn name() -> String { FNAME.lock().unwrap_or_else(|e| e.into_inner()).clone() }
        }
    }
}

progress_store!(extract_progress);
progress_store!(compress_progress);

/// The progress statics are process-wide; ALL tests touching them (the store
/// tests here AND the pack/extract round-trips in lib.rs) must serialize on
/// this lock — merging the crates into one test binary made cross-test cancel
/// interference possible.
#[cfg(test)]
pub(crate) static TEST_LOCK: Mutex<()> = Mutex::new(());

/// Wraps a `Write` and accumulates written bytes into a progress store.
/// Also aborts the write with an `Other` error when the cancel flag is raised,
/// so large single members stop promptly. NOTE: `Other` — never `Interrupted` —
/// because std's `io::copy`/`write_all` retry Interrupted forever, which would
/// spin at 100% CPU on cancel instead of aborting.
pub struct ProgressWriter<W> {
    inner: W,
    sink: fn(u64),
    check: fn() -> bool,
}

impl<W> ProgressWriter<W> {
    pub fn extract(inner: W) -> Self { Self { inner, sink: extract_progress::add_bytes, check: extract_progress::cancelled } }
}

impl<W: std::io::Write> std::io::Write for ProgressWriter<W> {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        if (self.check)() {
            return Err(std::io::Error::new(std::io::ErrorKind::Other, "cancelled"));
        }
        let n = self.inner.write(buf)?;
        (self.sink)(n as u64);
        Ok(n)
    }
    fn flush(&mut self) -> std::io::Result<()> { self.inner.flush() }
}

/// Wraps a `Read` and accumulates read bytes into a progress store. Checks the
/// cancel flag on every read so compression aborts promptly when cancelled.
pub struct ProgressReader<R> {
    inner: R,
    sink: fn(u64),
    check: fn() -> bool,
}

impl<R> ProgressReader<R> {
    pub fn compress(inner: R) -> Self { Self { inner, sink: compress_progress::add_bytes, check: compress_progress::cancelled } }
}

impl<R: std::io::Read> std::io::Read for ProgressReader<R> {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        if (self.check)() {
            return Err(std::io::Error::new(std::io::ErrorKind::Other, "cancelled"));
        }
        let n = self.inner.read(buf)?;
        (self.sink)(n as u64);
        Ok(n)
    }
}

impl<R: std::io::BufRead> std::io::BufRead for ProgressReader<R> {
    fn fill_buf(&mut self) -> std::io::Result<&[u8]> {
        if (self.check)() {
            return Err(std::io::Error::new(std::io::ErrorKind::Other, "cancelled"));
        }
        self.inner.fill_buf()
    }
    fn consume(&mut self, amt: usize) {
        (self.sink)(amt as u64);
        self.inner.consume(amt);
    }
}

pub fn s(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(|v| v.into()).unwrap_or_default()
}

use core::pin::Pin;
use core::task::{Context, Poll, RawWaker, RawWakerVTable, Waker};
use std::future::Future;

pub fn oneshot_async<Fut: Future>(fut: Fut) -> Fut::Output {
    const VTABLE: RawWakerVTable = RawWakerVTable::new(|_| RAW, |_| {}, |_| {}, |_| {});
    const RAW: RawWaker = RawWaker::new(&(), &VTABLE);
    let waker = unsafe { Waker::from_raw(RAW) };
    let mut cx = Context::from_waker(&waker);
    let mut fut = fut;
    let mut fut = unsafe { Pin::new_unchecked(&mut fut) };
    let mut polls: u32 = 0;
    loop {
        match fut.as_mut().poll(&mut cx) {
            Poll::Ready(v) => return v,
            Poll::Pending => {
                polls += 1;
                // The XP3 readers are synchronous (always Ready/Err), so
                // Pending should never persist. A future stuck in Pending
                // would otherwise spin at 100% CPU forever — bail after a
                // large number of polls; the panic is converted to an error
                // by the JNI `guarded` wrapper.
                if polls > 1_000_000 {
                    panic!("oneshot_async: future never completed");
                }
                std::thread::yield_now();
            }
        }
    }
}

pub struct SyncIo<T>(pub T);
impl<T: std::io::Read + Unpin> tokio::io::AsyncRead for SyncIo<T> {
    fn poll_read(mut self: Pin<&mut Self>, _: &mut Context<'_>, buf: &mut tokio::io::ReadBuf<'_>) -> Poll<std::io::Result<()>> {
        match self.0.read(buf.initialize_unfilled()) { Ok(n) => { buf.set_filled(n); Poll::Ready(Ok(())) } Err(e) => Poll::Ready(Err(e)) }
    }
}
impl<T: std::io::BufRead + Unpin> tokio::io::AsyncBufRead for SyncIo<T> {
    fn poll_fill_buf(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<std::io::Result<&[u8]>> { Poll::Ready(self.get_mut().0.fill_buf()) }
    fn consume(self: Pin<&mut Self>, amt: usize) { self.get_mut().0.consume(amt); }
}
impl<T: std::io::Seek + Unpin> tokio::io::AsyncSeek for SyncIo<T> {
    fn start_seek(self: Pin<&mut Self>, pos: std::io::SeekFrom) -> std::io::Result<()> { self.get_mut().0.seek(pos)?; Ok(()) }
    fn poll_complete(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<std::io::Result<u64>> { Poll::Ready(self.get_mut().0.stream_position()) }
}
impl<T: std::io::Write + Unpin> tokio::io::AsyncWrite for SyncIo<T> {
    fn poll_write(self: Pin<&mut Self>, _: &mut Context<'_>, buf: &[u8]) -> Poll<std::io::Result<usize>> { Poll::Ready(self.get_mut().0.write(buf)) }
    fn poll_flush(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<std::io::Result<()>> { Poll::Ready(self.get_mut().0.flush()) }
    fn poll_shutdown(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<std::io::Result<()>> { Poll::Ready(Ok(())) }
}

pub fn json_escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if c.is_control() => { out.push_str(&format!("\\u{:04x}", c as u32)); }
            c => out.push(c),
        }
    }
    out
}

pub fn derive_dirs(paths: &[&str]) -> BTreeSet<String> {
    let mut dirs = BTreeSet::new();
    for path in paths {
        let parts: Vec<&str> = path.split('/').collect();
        for i in 1..parts.len() { dirs.insert(parts[..i].join("/")); }
    }
    dirs
}

pub fn safe_join(output: &str, archive_path: &str) -> Result<PathBuf, String> {
    let mut dest = Path::new(output).to_path_buf();
    let normalized = archive_path.replace('\\', "/");
    if normalized.starts_with('/') || normalized.contains('\0') {
        return Err(format!("unsafe archive path: {archive_path}"));
    }
    let mut pushed = false;

    for comp in normalized.split('/') {
        if comp.is_empty() || comp == "." {
            continue;
        }
        if comp == ".." || comp.contains(':') {
            return Err(format!("unsafe archive path: {archive_path}"));
        }
        dest.push(comp);
        pushed = true;
    }

    if !pushed {
        return Err("empty archive path".to_string());
    }

    Ok(dest)
}

pub fn extract_result_json(total: u32, success: u32, error: u32) -> String {
    format!(r#"{{"total":{},"success":{},"error":{}}}"#, total, success, error)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::path::PathBuf;

    fn locked() -> std::sync::MutexGuard<'static, ()> {
        TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn joined(archive_path: &str) -> Result<PathBuf, String> {
        safe_join("/tmp/output", archive_path)
    }

    #[test]
    fn safe_join_accepts_normal_nested_paths() {
        assert_eq!(
            joined("dir/subdir/file.txt").unwrap(),
            PathBuf::from("/tmp/output/dir/subdir/file.txt")
        );
    }

    #[test]
    fn safe_join_normalizes_backslash_separators() {
        assert_eq!(
            joined(r"dir\subdir\file.txt").unwrap(),
            PathBuf::from("/tmp/output/dir/subdir/file.txt")
        );
    }

    #[test]
    fn safe_join_rejects_empty_paths() {
        assert_eq!(joined("").unwrap_err(), "empty archive path");
        assert_eq!(joined(".").unwrap_err(), "empty archive path");
        assert_eq!(joined("./").unwrap_err(), "empty archive path");
    }

    #[test]
    fn progress_writer_aborts_on_cancel() {
        let _g = locked();
        extract_progress::clear_cancel();
        extract_progress::reset(1024);
        let mut out = Vec::new();
        {
            let mut w = ProgressWriter::extract(&mut out);
            w.write_all(&[1u8; 100]).unwrap();
        }
        assert_eq!(extract_progress::bytes(), 100);

        extract_progress::cancel();
        let mut w = ProgressWriter::extract(&mut out);
        let err = w.write(&[1u8; 16]).unwrap_err();
        // Other — not Interrupted: std's write_all/io::copy retry Interrupted
        // forever, which would spin at 100% CPU instead of aborting.
        assert_eq!(err.kind(), std::io::ErrorKind::Other);

        // an explicit clear_cancel() at a fresh operation start re-enables writes
        extract_progress::clear_cancel();
        extract_progress::reset(1024);
        let mut w = ProgressWriter::extract(&mut out);
        w.write_all(&[2u8; 32]).unwrap();
        assert_eq!(extract_progress::bytes(), 32);
    }

    #[test]
    fn progress_reader_aborts_on_compress_cancel() {
        let _g = locked();
        compress_progress::clear_cancel();
        compress_progress::reset(1024);
        let src = vec![7u8; 512];
        let mut r = ProgressReader::compress(&src[..]);
        let mut buf = [0u8; 64];
        let n = r.read(&mut buf).unwrap();
        assert_eq!(n, 64);
        assert_eq!(compress_progress::bytes(), 64);

        compress_progress::cancel();
        let err = r.read(&mut buf).unwrap_err();
        assert_eq!(err.kind(), std::io::ErrorKind::Other);

        // Mirror the JNI entry contract: a fresh operation clears the cancel
        // flag — leaving it set here would poison every later pack test.
        compress_progress::clear_cancel();
    }

    #[test]
    fn cancelled_io_copy_aborts_instead_of_spinning() {
        // Regression guard: io::copy must NOT retry the cancel error forever —
        // with ErrorKind::Interrupted it spins at 100% CPU (verified on stable
        // std). With Other it returns promptly.
        let _g = locked();
        extract_progress::clear_cancel();
        extract_progress::reset(1024);
        let mut out = Vec::new();
        extract_progress::cancel();
        let mut w = ProgressWriter::extract(&mut out);
        let src = vec![9u8; 4096];
        let res = std::io::copy(&mut &src[..], &mut w);
        assert!(res.is_err(), "io::copy must abort on cancel, got {res:?}");
        assert_eq!(res.unwrap_err().kind(), std::io::ErrorKind::Other);
        extract_progress::clear_cancel();
    }

    #[test]
    fn progress_reset_clears_state() {
        let _g = locked();
        extract_progress::clear_cancel();
        extract_progress::reset(1024);
        extract_progress::add_bytes(256);
        extract_progress::reset(2048);
        assert_eq!(extract_progress::bytes(), 0);
        assert_eq!(extract_progress::total_bytes(), 2048);
        assert!(!extract_progress::cancelled());
        assert_eq!(extract_progress::name(), "");
    }

    #[test]
    fn progress_cancel_flag_survives_reset_and_clears_explicitly() {
        let _g = locked();
        extract_progress::clear_cancel();
        assert!(!extract_progress::cancelled());
        extract_progress::cancel();
        assert!(extract_progress::cancelled());
        // A cancel pressed during a pre-scan must survive into the extraction
        // phase — reset() deliberately preserves the flag.
        extract_progress::reset(1);
        assert!(extract_progress::cancelled());
        // Only an explicit clear_cancel() at a fresh operation start clears it.
        extract_progress::clear_cancel();
        assert!(!extract_progress::cancelled());
    }

    #[test]
    fn progress_set_file_tracks_per_file_counter() {
        let _g = locked();
        extract_progress::reset(1000);
        extract_progress::set_file(500);
        assert_eq!(extract_progress::file_total(), 500);
        assert_eq!(extract_progress::file_bytes(), 0);

        extract_progress::add_bytes(200);
        extract_progress::add_bytes(300);
        assert_eq!(extract_progress::file_bytes(), 500);
        assert_eq!(extract_progress::bytes(), 500);

        // next member resets the per-file counter but keeps overall bytes
        extract_progress::set_file(400);
        assert_eq!(extract_progress::file_total(), 400);
        assert_eq!(extract_progress::file_bytes(), 0);
        extract_progress::add_bytes(400);
        assert_eq!(extract_progress::file_bytes(), 400);
        assert_eq!(extract_progress::bytes(), 900);
    }
}
