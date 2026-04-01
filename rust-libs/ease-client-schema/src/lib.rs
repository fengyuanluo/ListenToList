mod v2;
mod v3;
mod v4;
mod v5;

uniffi::setup_scaffolding!();

pub use v2::upgrade_v1_to_v2;
pub use v3::upgrade_v2_to_v3;
pub use v4::upgrade_v3_to_v4;
pub use v5::*;
