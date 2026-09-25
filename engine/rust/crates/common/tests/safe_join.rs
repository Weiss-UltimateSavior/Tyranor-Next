use archive_common::safe_join;
use std::path::PathBuf;

fn joined(archive_path: &str) -> Result<PathBuf, String> {
    safe_join("/tmp/output", archive_path)
}

#[test]
fn accepts_normal_nested_paths() {
    assert_eq!(
        joined("dir/subdir/file.txt").unwrap(),
        PathBuf::from("/tmp/output/dir/subdir/file.txt")
    );
}

#[test]
fn normalizes_backslash_separators() {
    assert_eq!(
        joined(r"dir\subdir\file.txt").unwrap(),
        PathBuf::from("/tmp/output/dir/subdir/file.txt")
    );
}

#[test]
fn rejects_empty_paths() {
    assert_eq!(joined("").unwrap_err(), "empty archive path");
    assert_eq!(joined(".").unwrap_err(), "empty archive path");
    assert_eq!(joined("./").unwrap_err(), "empty archive path");
}

#[test]
fn rejects_parent_directory_components() {
    assert!(joined("../escape.txt").is_err());
    assert!(joined("dir/../escape.txt").is_err());
    assert!(joined(r"dir\..\escape.txt").is_err());
}

#[test]
fn rejects_absolute_paths() {
    assert!(joined("/tmp/escape.txt").is_err());
    assert!(joined("//tmp/escape.txt").is_err());
}

#[test]
fn rejects_backslash_absolute_paths() {
    assert!(joined(r"\tmp\escape.txt").is_err());
    assert!(joined(r"\\server\share\escape.txt").is_err());
}

#[test]
fn rejects_windows_drive_and_colon_paths() {
    assert!(joined(r"C:\Windows\system.ini").is_err());
    assert!(joined("C:/Windows/system.ini").is_err());
    assert!(joined("dir/name:stream").is_err());
}

#[test]
fn rejects_nul_bytes() {
    assert!(joined("dir/file\0.txt").is_err());
}
