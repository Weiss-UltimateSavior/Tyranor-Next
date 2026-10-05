//! A XP3(krkr) archive library for rust.
//! ## Examples
//! See `examples` directory for various code examples.

mod entry;
pub mod header;
pub mod read;
pub mod write;

pub const XP3_MAGIC: [u8; 10] = [0x58, 0x50, 0x33, 0x0D, 0x0A, 0x20, 0x0A, 0x1A, 0x8B, 0x67];

pub const XP3_CURRENT_VER_IDENTIFIER: u64 = 0x17;

pub const XP3_VERSION_IDENTIFIER: u8 = 128;

pub const XP3_INDEX_FILE_IDENTIFIER: u32 = 1701603654; // File

pub const XP3_INDEX_INFO_IDENTIFIER: u32 = 1868983913; // info
pub const XP3_INDEX_SEGM_IDENTIFIER: u32 = 1835492723; // segm
pub const XP3_INDEX_ADLR_IDENTIFIER: u32 = 1919706209; // adlr
pub const XP3_INDEX_TIME_IDENTIFIER: u32 = 1701669236; // time

pub const XP3_PROTECTED_FLAG: u32 = 0x80000000;
