use serde_json::{json, Value};
use std::sync::{Arc, Mutex};

/// The route as the owner sees it in status. `Enabled` means configured and
/// working as far as the last send showed.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum Route {
    #[default]
    Disabled,
    Misconfigured,
    Enabled,
    RateLimited,
    Failing,
    Rejected,
}

impl Route {
    pub fn name(self) -> &'static str {
        match self {
            Route::Disabled => "disabled",
            Route::Misconfigured => "misconfigured",
            Route::Enabled => "enabled",
            Route::RateLimited => "rate_limited",
            Route::Failing => "failing",
            Route::Rejected => "rejected",
        }
    }
}

/// Status shown on the bridge. Holds counts and bounded codes only.
#[derive(Debug, Clone, Default)]
pub struct Status {
    pub route: Route,
    pub last_delivered_at: Option<u64>,
    pub uncertain_count: u64,
    pub dropped_count: u64,
    pub watching_since: Option<u64>,
    pub last_http_status: Option<u16>,
}

impl Status {
    pub fn to_json(&self) -> Value {
        json!({
            "route": self.route.name(),
            "last_delivered_at": self.last_delivered_at,
            "uncertain_count": self.uncertain_count,
            "dropped_count": self.dropped_count,
            "watching_since": self.watching_since,
            "last_http_status": self.last_http_status,
        })
    }
}

/// Status shared with the HTTP handlers.
pub type Shared = Arc<Mutex<Status>>;
