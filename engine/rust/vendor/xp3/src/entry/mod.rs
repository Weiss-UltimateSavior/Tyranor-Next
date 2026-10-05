mod read;
mod write;

/// FileIndex for xp3 archive.
/// Contains information about file and data offsets.
#[derive(Debug, Clone, Default)]
pub struct XP3FileEntry {
    pub protected: bool,
    pub name: String,
    pub size: u64,
    pub archive_size: u64,
    pub checksum: u32,
    pub timestamp: Option<u64>,
}

#[derive(Debug, Default)]
pub(super) struct XP3Entries {
    pub entries: Vec<XP3FileEntry>,
    pub file_starts: Vec<usize>,
    pub segments: Vec<DataSegment>,
}

impl XP3Entries {
    pub const fn new() -> Self {
        Self {
            entries: vec![],
            file_starts: vec![],
            segments: vec![],
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub(super) struct DataSegment {
    pub compressed: bool,
    pub start: u64,
    pub size: u64,
    pub archive_size: u64,
    pub next: Option<usize>,
}
