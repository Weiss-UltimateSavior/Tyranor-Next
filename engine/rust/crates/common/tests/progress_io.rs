use archive_common::{ProgressReader, ProgressWriter};
use archive_common::{compress_progress, extract_progress};
use std::io::{Read, Write};
use std::sync::Mutex;

static LOCK: Mutex<()> = Mutex::new(());

#[test]
fn progress_writer_aborts_on_cancel() {
    let _g = LOCK.lock().unwrap();
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
    let _g = LOCK.lock().unwrap();
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
}

#[test]
fn cancelled_io_copy_aborts_instead_of_spinning() {
    // Regression guard: io::copy must NOT retry the cancel error forever —
    // with ErrorKind::Interrupted it spins at 100% CPU (verified on stable
    // std). With Other it returns promptly.
    let _g = LOCK.lock().unwrap();
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
