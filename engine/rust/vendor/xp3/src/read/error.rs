use std::io;

#[derive(Debug, thiserror::Error)]
pub enum XP3OpenError {
    #[error("Invalid xp3 header")]
    InvalidHeader,
    #[error("Invalid xp3 section: {0:#X}")]
    InvalidSection(u32),
    #[error(transparent)]
    Io(#[from] io::Error),
}
