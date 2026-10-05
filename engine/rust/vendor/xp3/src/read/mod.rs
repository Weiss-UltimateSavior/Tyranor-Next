pub mod error;
mod stream;

use async_compression::tokio::bufread::ZlibDecoder;
use core::{
    mem,
    pin::Pin,
    task::{Context, Poll},
};
use std::io::SeekFrom;
use tokio::io::{self, AsyncBufRead, AsyncRead, AsyncReadExt, AsyncSeek, AsyncSeekExt, ReadBuf};

use crate::{
    XP3_CURRENT_VER_IDENTIFIER, XP3_MAGIC, XP3_VERSION_IDENTIFIER,
    entry::{DataSegment, XP3Entries, XP3FileEntry},
    header::XP3Version,
    read::{error::XP3OpenError, stream::XP3Stream},
};

#[derive(Debug)]
pub struct XP3Archive<T> {
    pub version: XP3Version,
    entries: XP3Entries,
    start: u64,
    stream: T,
}

impl<T> XP3Archive<T>
where
    T: AsyncBufRead + AsyncSeek + Unpin,
{
    /// Open and index XP3 archive
    pub async fn open(mut stream: T) -> Result<Self, XP3OpenError> {
        let start = stream.stream_position().await?;

        let mut signature = [0; XP3_MAGIC.len()];
        stream.read_exact(&mut signature).await?;
        if signature != XP3_MAGIC {
            return Err(XP3OpenError::InvalidHeader);
        }
        let _ = stream.read_u8().await?;

        let (version, index_start) = match stream.read_u64_le().await? {
            XP3_CURRENT_VER_IDENTIFIER => {
                let minor = stream.read_u32_le().await?;
                if stream.read_u8().await? != XP3_VERSION_IDENTIFIER {
                    return Err(XP3OpenError::InvalidHeader);
                }

                let index_offset = stream.read_u64_le().await?;
                stream.seek(SeekFrom::Current(index_offset as _)).await?;

                let index_start = stream.read_u64_le().await?;
                (XP3Version::Current { minor }, index_start)
            }

            index_start => (XP3Version::Old, index_start),
        };

        stream.seek(SeekFrom::Start(start + index_start)).await?;
        let entries = XP3Entries::open(&mut stream).await?;

        Ok(Self {
            version,
            entries,
            start,
            stream,
        })
    }

    #[inline]
    /// List entries
    pub fn entries(&self) -> &[XP3FileEntry] {
        &self.entries.entries
    }

    /// Open an [`XP3File`] by index
    pub async fn by_index<'a>(&'a mut self, index: usize) -> Option<io::Result<XP3File<'a, T>>> {
        let start = self.entries.segments[*self.entries.file_starts.get(index)?];
        Some(XP3File::open(self.start, &self.entries.segments, start, &mut self.stream).await)
    }

    #[inline]
    pub fn into_inner(self) -> T {
        self.stream
    }
}

pub struct XP3File<'a, T> {
    start: u64,
    segments: &'a [DataSegment],
    state: State<'a, T>,
}

impl<'a, T> XP3File<'a, T>
where
    T: AsyncBufRead + AsyncSeek + Unpin,
{
    async fn open(
        start: u64,
        segments: &'a [DataSegment],
        start_seg: DataSegment,
        stream: &'a mut T,
    ) -> io::Result<Self> {
        stream
            .seek(SeekFrom::Start(start + start_seg.start))
            .await?;
        Ok(XP3File {
            start,
            segments,
            state: State::Read {
                stream: create_file_stream(start_seg.compressed, start_seg.archive_size, stream),
                next: start_seg.next,
            },
        })
    }
}

impl<'a, T> AsyncRead for XP3File<'a, T>
where
    XP3Stream<&'a mut T>: AsyncRead,
    T: AsyncBufRead + AsyncSeek + Unpin,
{
    fn poll_read(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        loop {
            return match mem::replace(&mut self.state, State::Done) {
                State::Read { mut stream, next } => {
                    let filled = buf.filled().len();
                    let remaining = buf.remaining();
                    if Pin::new(&mut stream).poll_read(cx, buf)?.is_pending() {
                        self.state = State::Read { stream, next };
                        return Poll::Pending;
                    }

                    if remaining == 0 || filled != buf.filled().len() {
                        self.state = State::Read { stream, next };
                        return Poll::Ready(Ok(()));
                    }

                    // Start next segment if exists
                    let Some(next) = next else {
                        continue;
                    };

                    let mut stream = stream.into_inner();
                    let next_seg = self.segments[next];
                    Pin::new(&mut stream)
                        .start_seek(SeekFrom::Start(self.start + next_seg.start))?;
                    self.state = State::Seek {
                        stream,
                        next: next_seg.next,
                        compressed: next_seg.compressed,
                        size: next_seg.archive_size,
                    };
                    continue;
                }

                State::Seek {
                    mut stream,
                    next,
                    compressed,
                    size,
                } => {
                    let poll = Pin::new(&mut stream).poll_complete(cx)?;
                    if poll.is_ready() {
                        self.state = State::Read {
                            stream: create_file_stream(compressed, size, stream),
                            next,
                        };
                        continue;
                    } else {
                        self.state = State::Seek {
                            stream,
                            next,
                            compressed,
                            size,
                        };

                        Poll::Pending
                    }
                }

                State::Done => Poll::Ready(Ok(())),
            };
        }
    }
}

enum State<'a, T> {
    Read {
        stream: XP3Stream<&'a mut T>,
        next: Option<usize>,
    },
    Seek {
        stream: &'a mut T,
        next: Option<usize>,
        compressed: bool,
        size: u64,
    },
    Done,
}

fn create_file_stream<T: AsyncBufRead + Unpin + AsyncSeek>(
    compressed: bool,
    size: u64,
    stream: T,
) -> XP3Stream<T> {
    let stream = stream.take(size);

    if compressed {
        XP3Stream::Compressed(ZlibDecoder::new(stream))
    } else {
        XP3Stream::Raw(stream)
    }
}
