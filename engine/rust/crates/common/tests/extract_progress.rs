use archive_common::extract_progress;
use std::sync::Mutex;

// The progress statics are process-wide; serialize tests to avoid interference.
static LOCK: Mutex<()> = Mutex::new(());

#[test]
fn reset_clears_state() {
    let _g = LOCK.lock().unwrap();
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
fn add_bytes_accumulates_and_sets_name() {
    let _g = LOCK.lock().unwrap();
    extract_progress::reset(100);
    extract_progress::set_name("first.bin");
    extract_progress::add_bytes(40);
    extract_progress::add_bytes(35);
    assert_eq!(extract_progress::bytes(), 75);
    assert_eq!(extract_progress::name(), "first.bin");
    extract_progress::set_name("second.dat");
    extract_progress::add_bytes(25);
    assert_eq!(extract_progress::bytes(), 100);
    assert_eq!(extract_progress::name(), "second.dat");
}

#[test]
fn cancel_flag_survives_reset_and_clears_explicitly() {
    let _g = LOCK.lock().unwrap();
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
fn set_file_resets_per_file_counter_and_tracks_bytes() {
    let _g = LOCK.lock().unwrap();
    extract_progress::reset(1000);
    extract_progress::set_file(500);
    assert_eq!(extract_progress::file_total(), 500);
    assert_eq!(extract_progress::file_bytes(), 0);

    // add_bytes feeds both the overall and the per-file counter
    extract_progress::add_bytes(200);
    assert_eq!(extract_progress::file_bytes(), 200);
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

    // reset clears per-file state too
    extract_progress::reset(1000);
    assert_eq!(extract_progress::file_bytes(), 0);
    assert_eq!(extract_progress::file_total(), 0);
}
