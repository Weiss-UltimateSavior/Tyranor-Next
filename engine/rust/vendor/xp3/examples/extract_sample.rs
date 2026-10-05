use core::error::Error;
use std::path::Path;

use tokio::{
    fs::{self, File},
    io::{BufReader, BufWriter, copy},
};
use xp3::read::XP3Archive;

#[tokio::main]
async fn main() -> Result<(), Box<dyn Error>> {
    let stream = BufReader::new(File::open("sample.xp3").await?);
    let mut xp3 = XP3Archive::open(stream).await?;

    let dir = Path::new("xp3_test");
    for i in 0..xp3.entries().len() {
        let entry = &xp3.entries()[i];
        println!("Extracting: {}", entry.name);

        let path = dir.join(&entry.name);
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).await?;
        }

        let mut out = BufWriter::new(File::create(path).await?);
        let mut file = xp3.by_index(i).await.unwrap()?;
        copy(&mut file, &mut out).await?;
    }

    Ok(())
}
