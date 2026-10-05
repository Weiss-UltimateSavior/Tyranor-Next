use std::io::{self, Write};

use byteorder::{LittleEndian, WriteBytesExt};
use flate2::{Compression, write::ZlibEncoder};
use tokio::io::{AsyncWrite, AsyncWriteExt};

use crate::{
    XP3_INDEX_ADLR_IDENTIFIER, XP3_INDEX_FILE_IDENTIFIER, XP3_INDEX_INFO_IDENTIFIER,
    XP3_INDEX_SEGM_IDENTIFIER, XP3_INDEX_TIME_IDENTIFIER,
    entry::{DataSegment, XP3Entries, XP3FileEntry},
};

impl XP3Entries {
    pub async fn write(
        &self,
        compression: Option<u8>,
        stream: &mut (impl AsyncWrite + Unpin),
    ) -> io::Result<()> {
        let buf = if let Some(level) = compression {
            stream.write_u8(1).await?;

            let mut encoder = ZlibEncoder::new(vec![], Compression::new(level as _));
            self.write_index(&mut encoder)?;

            let original_size = encoder.total_in();
            let buf = encoder.finish()?;
            stream.write_u64_le(buf.len() as _).await?;
            stream.write_u64_le(original_size).await?;
            buf
        } else {
            stream.write_u8(0).await?;

            let mut buf = vec![];
            self.write_index(&mut buf)?;

            stream.write_u64_le(buf.len() as _).await?;
            buf
        };

        stream.write_all(&buf).await?;
        Ok(())
    }

    fn write_index(&self, writer: &mut impl Write) -> io::Result<()> {
        let mut buf = vec![];
        let mut string_buf = vec![];
        for (entry, &segment_start) in self.entries.iter().zip(self.file_starts.iter()) {
            write_file(
                entry,
                &self.segments,
                Some(segment_start),
                &mut string_buf,
                &mut buf,
            )?;

            write_segment(XP3_INDEX_FILE_IDENTIFIER, buf.len() as _, writer)?;
            writer.write_all(&buf)?;
            buf.clear();
            string_buf.clear();
        }

        Ok(())
    }
}

fn write_file(
    entry: &XP3FileEntry,
    segments: &[DataSegment],
    segment_start: Option<usize>,
    string_buf: &mut Vec<u16>,
    writer: &mut impl Write,
) -> io::Result<()> {
    string_buf.extend(entry.name.encode_utf16());
    write_segment(
        XP3_INDEX_INFO_IDENTIFIER,
        22 + string_buf.len() as u64 * 2,
        writer,
    )?;
    writer.write_u32::<LittleEndian>(entry.protected as u32)?;
    writer.write_u64::<LittleEndian>(entry.size)?;
    writer.write_u64::<LittleEndian>(entry.archive_size)?;

    writer.write_u16::<LittleEndian>(string_buf.len() as u16)?;
    for &mut ch in &mut *string_buf {
        writer.write_u16::<LittleEndian>(ch)?;
    }

    if let Some(timestamp) = entry.timestamp {
        write_segment(XP3_INDEX_TIME_IDENTIFIER, 8, writer)?;
        writer.write_u64::<LittleEndian>(timestamp)?;
    }

    write_segment(XP3_INDEX_ADLR_IDENTIFIER, 4, writer)?;
    writer.write_u32::<LittleEndian>(entry.checksum)?;

    let mut next_index = segment_start;
    while let Some(seg_index) = next_index {
        let segment = segments[seg_index];
        next_index = segment.next;

        write_segment(XP3_INDEX_SEGM_IDENTIFIER, 28, writer)?;
        writer.write_u32::<LittleEndian>(segment.compressed as u32)?;
        writer.write_u64::<LittleEndian>(segment.start)?;
        writer.write_u64::<LittleEndian>(segment.size)?;
        writer.write_u64::<LittleEndian>(segment.archive_size)?;
    }

    Ok(())
}

fn write_segment(id: u32, size: u64, writer: &mut impl Write) -> io::Result<()> {
    writer.write_u32::<LittleEndian>(id)?;
    writer.write_u64::<LittleEndian>(size)?;
    Ok(())
}
