use std::io::{self, Cursor, ErrorKind};

use async_compression::tokio::bufread::ZlibDecoder;
use byteorder::{LittleEndian, ReadBytesExt};
use tokio::io::{AsyncRead, AsyncReadExt, BufReader};

use crate::{
    XP3_INDEX_ADLR_IDENTIFIER, XP3_INDEX_FILE_IDENTIFIER, XP3_INDEX_INFO_IDENTIFIER,
    XP3_INDEX_SEGM_IDENTIFIER, XP3_INDEX_TIME_IDENTIFIER, XP3_PROTECTED_FLAG,
    entry::{DataSegment, XP3Entries, XP3FileEntry},
    read::error::XP3OpenError,
};

// ── Local UsefulUnpack patch (upstream xp3 0.4.2) ──────────────────────────
// The index is attacker-controlled data: an archive ≤4 GB can declare a huge
// compressed index whose inflate output is unbounded (`read_to_end` on the
// decoder — ~1032× amplification) and push an unbounded number of entries,
// OOMing the process at `open` time before the caller ever sees an entry.
// Cap the total index bytes and the entry count; also bounds-check the
// UTF-16 name slice below (a truncated INFO section used to panic).
const MAX_INDEX_BYTES: u64 = 256 * 1024 * 1024;
const MAX_ENTRIES: usize = 1_000_000;

impl XP3Entries {
    pub async fn open(stream: &mut (impl AsyncRead + Unpin)) -> Result<Self, XP3OpenError> {
        let compressed = stream.read_u8().await? != 0;
        let size = stream.read_u64_le().await?;

        let entries = Self::default();
        Ok(if compressed {
            let original_size = stream.read_u64_le().await?;
            entries
                .open_inner(
                    ZlibDecoder::new(BufReader::new(stream.take(size))),
                    original_size,
                )
                .await?
        } else {
            entries.open_inner(stream.take(size), size).await?
        })
    }

    async fn open_inner(
        mut self,
        mut stream: impl AsyncRead + Unpin,
        original_size: u64,
    ) -> Result<Self, XP3OpenError> {
        let mut read = 0;
        let mut decoded_total = 0u64;
        let mut buf = vec![];
        while read < original_size {
            let (key, index_size) = read_index(&mut stream).await?;
            // index_size 来自归档数据（攻击者可控）：加法必须 checked，溢出回绕
            // 会把 cap 检查整个绕过（CodeRabbit CWE-190）。
            if index_size > MAX_INDEX_BYTES
                || read.checked_add(index_size).map_or(true, |end| end > original_size)
            {
                return Err(XP3OpenError::Io(ErrorKind::UnexpectedEof.into()));
            }
            let n = (&mut stream).take(index_size).read_to_end(&mut buf).await? as u64;
            decoded_total += n;
            if decoded_total > MAX_INDEX_BYTES {
                return Err(XP3OpenError::Io(
                    io::Error::new(ErrorKind::InvalidData, "xp3 index: decompressed size exceeds limit"),
                ));
            }

            match key {
                XP3_INDEX_FILE_IDENTIFIER => {
                    self.read_file_index(&buf).await?;
                }

                _ => {
                    // Unknown identifier
                }
            }
            buf.clear();
            read += index_size + 12;
        }

        Ok(self)
    }

    async fn read_file_index(&mut self, data: &[u8]) -> Result<(), XP3OpenError> {
        if self.entries.len() >= MAX_ENTRIES {
            return Err(XP3OpenError::Io(
                io::Error::new(ErrorKind::InvalidData, "xp3 index: too many entries"),
            ));
        }
        let total_size = data.len() as u64;
        let mut cursor = Cursor::new(data);

        let mut entry = XP3FileEntry::default();
        let mut start_segment_index: Option<usize> = None;
        let mut prev_segment_index: Option<usize> = None;
        while cursor.position() < total_size {
            let (key, index_size) = read_index(&mut cursor).await?;
            let end = cursor.position().checked_add(index_size).ok_or_else(|| {
                io::Error::new(ErrorKind::InvalidData, "xp3 index: chunk size overflow")
            })?;
            if end > total_size {
                return Err(XP3OpenError::Io(ErrorKind::UnexpectedEof.into()));
            }

            let mut sub_data =
                Cursor::new(&cursor.get_ref()[cursor.position() as usize..][..index_size as usize]);
            match key {
                XP3_INDEX_INFO_IDENTIFIER => {
                    entry.protected = ReadBytesExt::read_u32::<LittleEndian>(&mut sub_data)?
                        == XP3_PROTECTED_FLAG;
                    entry.size = ReadBytesExt::read_u64::<LittleEndian>(&mut sub_data)?;
                    entry.archive_size = ReadBytesExt::read_u64::<LittleEndian>(&mut sub_data)?;

                    let name_len = ReadBytesExt::read_u16::<LittleEndian>(&mut sub_data)? as usize;
                    let name_start = sub_data.position() as usize;
                    if name_start.checked_add(name_len * 2).map_or(true, |end| end > sub_data.get_ref().len()) {
                        return Err(XP3OpenError::Io(ErrorKind::UnexpectedEof.into()));
                    }
                    entry.name = char::decode_utf16(
                        sub_data.get_ref()[name_start..][..name_len * 2]
                            .chunks_exact(2)
                            .map(|chunk| u16::from_le_bytes([chunk[0], chunk[1]])),
                    )
                    .map(|r| r.unwrap_or(char::REPLACEMENT_CHARACTER))
                    .collect();
                }

                XP3_INDEX_SEGM_IDENTIFIER => {
                    let count = sub_data.get_ref().len() / 28;
                    for _ in 0..count {
                        let id = self.segments.len();

                        let compressed =
                            ReadBytesExt::read_u32::<LittleEndian>(&mut sub_data)? != 0;
                        let start = ReadBytesExt::read_u64::<LittleEndian>(&mut sub_data)?;
                        let size = ReadBytesExt::read_u64::<LittleEndian>(&mut sub_data)?;
                        let archive_size = ReadBytesExt::read_u64::<LittleEndian>(&mut sub_data)?;
                        self.segments.push(DataSegment {
                            compressed,
                            start,
                            size,
                            archive_size,
                            next: None,
                        });

                        if start_segment_index.is_none() {
                            start_segment_index = Some(id);
                        }
                        if let Some(prev) = prev_segment_index.replace(id) {
                            self.segments[prev].next = Some(id);
                        }
                    }
                }

                XP3_INDEX_ADLR_IDENTIFIER => {
                    entry.checksum = ReadBytesExt::read_u32::<LittleEndian>(&mut sub_data)?;
                }

                XP3_INDEX_TIME_IDENTIFIER => {
                    entry.timestamp = Some(ReadBytesExt::read_u64::<LittleEndian>(&mut sub_data)?);
                }

                _ => {
                    // SKIP sections we don't know yet.
                }
            }

            cursor.set_position(end);
        }

        let Some(start_segment_index) = start_segment_index else {
            return Err(XP3OpenError::InvalidSection(XP3_INDEX_SEGM_IDENTIFIER));
        };

        self.entries.push(entry);
        self.file_starts.push(start_segment_index);

        Ok(())
    }
}

// Read index key and size
async fn read_index(stream: &mut (impl AsyncRead + Unpin)) -> io::Result<(u32, u64)> {
    Ok((stream.read_u32_le().await?, stream.read_u64_le().await?))
}
