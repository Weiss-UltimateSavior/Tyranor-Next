mod stream;

use core::{
    pin::Pin,
    task::{Context, Poll, ready},
};
use std::io::{self, SeekFrom};

use adler32::RollingAdler32;
use async_compression::{Level, tokio::write::ZlibEncoder};
use tokio::io::{AsyncSeek, AsyncSeekExt, AsyncWrite, AsyncWriteExt};

use crate::{
    XP3_CURRENT_VER_IDENTIFIER, XP3_MAGIC, XP3_VERSION_IDENTIFIER,
    entry::{DataSegment, XP3Entries, XP3FileEntry},
    header::XP3Version,
    write::stream::XP3FileStream,
};

#[derive(Debug)]
pub struct XP3Writer<T> {
    start: u64,
    index_offset_pos: u64,
    entries: XP3Entries,
    stream: T,
}

impl<T> XP3Writer<T>
where
    T: AsyncWrite + AsyncSeek + Unpin,
{
    pub async fn new(version: XP3Version, mut stream: T) -> io::Result<Self> {
        let start = stream.stream_position().await?;
        // write magic
        stream.write_all(&XP3_MAGIC).await?;
        stream.write_u8(1).await?;

        let index_offset_pos = match version {
            XP3Version::Old => {
                let pos = stream.stream_position().await?;
                // placeholder index offset value
                stream.write_u64_le(0).await?;
                pos
            }
            XP3Version::Current { minor } => {
                stream.write_u64_le(XP3_CURRENT_VER_IDENTIFIER).await?;
                stream.write_u32_le(minor).await?;
                stream.write_u8(XP3_VERSION_IDENTIFIER).await?;
                stream.write_u64_le(0).await?;
                let pos = stream.stream_position().await?;
                // placeholder index offset value
                stream.write_u64_le(0).await?;
                pos
            }
        };

        Ok(Self {
            start,
            index_offset_pos,
            entries: XP3Entries::new(),
            stream,
        })
    }

    pub async fn file<'a>(
        &'a mut self,
        name: String,
        protected: bool,
        compression: Option<u8>,
    ) -> io::Result<XP3FileWriter<'a, T>> {
        let file_start = self.stream.stream_position().await? - self.start;
        let stream = match compression {
            Some(level) => XP3FileStream::Compressed(ZlibEncoder::with_quality(
                &mut self.stream,
                Level::Precise(level as _),
            )),
            None => XP3FileStream::Raw {
                written: 0,
                stream: &mut self.stream,
            },
        };

        Ok(XP3FileWriter {
            protected,
            name,
            timestamp: None,
            file_start,
            compressed: compression.is_some(),
            checksum: RollingAdler32::new(),
            entries: &mut self.entries,
            stream,
        })
    }

    pub async fn finish(mut self, compression: Option<u8>) -> io::Result<T> {
        let index_start = self.stream.stream_position().await?;
        self.entries.write(compression, &mut self.stream).await?;

        let end = self.stream.stream_position().await?;
        self.stream
            .seek(SeekFrom::Start(self.index_offset_pos))
            .await?;
        self.stream.write_u64_le(index_start - self.start).await?;
        self.stream.seek(SeekFrom::Start(end)).await?;
        self.stream.flush().await?;
        Ok(self.stream)
    }
}
#[must_use]
pub struct XP3FileWriter<'a, T> {
    protected: bool,
    name: String,
    timestamp: Option<u64>,
    file_start: u64,
    compressed: bool,
    entries: &'a mut XP3Entries,
    checksum: RollingAdler32,
    stream: XP3FileStream<&'a mut T>,
}

impl<'a, T> XP3FileWriter<'a, T>
where
    T: AsyncWrite + Unpin,
{
    /// Set file protected flag
    pub fn protected(&mut self, protected: bool) {
        self.protected = protected;
    }

    /// Set file name
    pub fn name(&mut self, name: String) {
        self.name = name;
    }

    /// Set file timestamp
    pub fn timestamp(&mut self, timestamp: Option<u64>) {
        self.timestamp = timestamp;
    }

    /// Finish and add file to archive.
    /// Returns file index
    pub async fn finish(mut self) -> io::Result<usize> {
        self.stream.shutdown().await?;
        let size = self.stream.written_original();
        let archive_size = self.stream.written();

        let start_segment = self.entries.segments.len();
        self.entries.segments.push(DataSegment {
            compressed: self.compressed,
            start: self.file_start,
            size,
            archive_size,
            next: None,
        });
        let id = self.entries.entries.len();
        self.entries.entries.push(XP3FileEntry {
            protected: self.protected,
            name: self.name,
            size,
            archive_size,
            checksum: self.checksum.hash(),
            timestamp: self.timestamp,
        });
        self.entries.file_starts.push(start_segment);
        Ok(id)
    }
}

impl<'a, T> AsyncWrite for XP3FileWriter<'a, T>
where
    T: AsyncWrite + Unpin,
{
    fn poll_write(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &[u8],
    ) -> Poll<io::Result<usize>> {
        let written = ready!(Pin::new(&mut self.stream).poll_write(cx, buf))?;
        self.checksum.update_buffer(&buf[..written]);
        Poll::Ready(Ok(written))
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.stream).poll_flush(cx)
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.stream).poll_shutdown(cx)
    }
}
