#[derive(Debug, Clone, Copy)]
/// XP3 Archive version
pub enum XP3Version {
    Old,
    Current { minor: u32 },
}
