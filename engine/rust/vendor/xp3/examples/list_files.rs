use core::error::Error;

use tokio::{fs::File, io::BufReader};
use xp3::read::XP3Archive;

#[tokio::main]
async fn main() -> Result<(), Box<dyn Error>> {
    let stream = BufReader::new(File::open("sample.xp3").await?);
    let xp3 = XP3Archive::open(stream).await?;

    for entry in xp3.entries() {
        println!("file: {:?}", entry);
    }
    Ok(())
}
