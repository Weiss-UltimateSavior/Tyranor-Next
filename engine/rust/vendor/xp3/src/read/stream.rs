use core::{
    pin::Pin,
    task::{Context, Poll},
};
use std::io::{self};

use async_compression::tokio::bufread::ZlibDecoder;
use pin_project::pin_project;
use tokio::io::{AsyncBufRead, AsyncRead, ReadBuf, Take};

#[derive(Debug)]
#[pin_project]
pub struct XP3Entry<'a, T> {
    #[pin]
    stream: Take<&'a mut XP3Stream<T>>,
}

impl<'a, T> AsyncRead for XP3Entry<'a, T>
where
    &'a mut XP3Stream<T>: AsyncRead,
{
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        self.project().stream.poll_read(cx, buf)
    }
}

#[derive(Debug)]
#[pin_project(project = XP3StreamProj)]
pub enum XP3Stream<T> {
    Compressed(#[pin] ZlibDecoder<Take<T>>),
    Raw(#[pin] Take<T>),
}

impl<T: AsyncBufRead> XP3Stream<T> {
    pub fn into_inner(self) -> T {
        match self {
            XP3Stream::Compressed(stream) => stream.into_inner().into_inner(),
            XP3Stream::Raw(stream) => stream.into_inner(),
        }
    }
}

impl<T: AsyncBufRead> AsyncRead for XP3Stream<T> {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        match self.project() {
            XP3StreamProj::Compressed(stream) => stream.poll_read(cx, buf),
            XP3StreamProj::Raw(stream) => stream.poll_read(cx, buf),
        }
    }
}
