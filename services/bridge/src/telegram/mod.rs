//! Telegram attention alerts (issue #11). Send-only: the bridge posts plain text
//! to one private group and never reads updates. Design:
//! docs/design/telegram-attention-alerts.md.

mod config;
mod message;
mod notice;
mod send;
mod status;
#[cfg(test)]
mod test_support;
mod token;
mod tracker;
mod worker;

pub use config::{settings, File, Settings};
pub use status::{Route, Shared, Status};
pub use worker::watch;
