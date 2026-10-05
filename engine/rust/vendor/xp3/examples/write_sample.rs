use core::error::Error;

use tokio::{
    fs::File,
    io::{AsyncWriteExt, BufWriter},
};
use xp3::{header::XP3Version, write::XP3Writer};

#[tokio::main]
async fn main() -> Result<(), Box<dyn Error>> {
    let sample_xp3 = BufWriter::new(File::create("sample.xp3").await?);
    let mut writer = XP3Writer::new(XP3Version::Current { minor: 1 }, sample_xp3).await?;

    let mut file = writer.file("sample.txt".into(), true, Some(1)).await?;
    file.write_all("Hello world!".as_bytes()).await?;
    file.finish().await?;
    writer.finish(None).await?;
    Ok(())
}
