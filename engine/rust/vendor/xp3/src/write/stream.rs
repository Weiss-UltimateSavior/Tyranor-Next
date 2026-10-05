use core::{
    pin::Pin,
    task::{Context, Poll, ready},
};
use std::io;

use async_compression::tokio::write::ZlibEncoder;
use pin_project::pin_project;
use tokio::io::AsyncWrite;

#[derive(Debug)]
#[pin_project(project = XP3StreamProj)]
pub enum XP3FileStream<T> {
    Compressed(#[pin] ZlibEncoder<T>),
    Raw {
        written: u64,
        #[pin]
        stream: T,
    },
}

impl<T: AsyncWrite> XP3FileStream<T> {
    pub fn written(&self) -> u64 {
        match *self {
            XP3FileStream::Compressed(ref stream) => stream.total_out(),
            XP3FileStream::Raw { written, .. } => written,
        }
    }

    pub fn written_original(&self) -> u64 {
        match *self {
            XP3FileStream::Compressed(ref stream) => stream.total_in(),
            XP3FileStream::Raw { written, .. } => written,
        }
    }
}

impl<T: AsyncWrite> AsyncWrite for XP3FileStream<T> {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &[u8],
    ) -> Poll<io::Result<usize>> {
        match self.project() {
            XP3StreamProj::Compressed(stream) => stream.poll_write(cx, buf),
            XP3StreamProj::Raw { stream, written } => {
                let written_size = ready!(stream.poll_write(cx, buf))?;
                *written += written_size as u64;
                Poll::Ready(Ok(written_size))
            }
        }
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        match self.project() {
            XP3StreamProj::Compressed(stream) => stream.poll_flush(cx),
            XP3StreamProj::Raw { stream, .. } => stream.poll_flush(cx),
        }
    }

    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        match self.project() {
            XP3StreamProj::Compressed(stream) => stream.poll_shutdown(cx),
            XP3StreamProj::Raw { stream, .. } => stream.poll_shutdown(cx),
        }
    }
}
