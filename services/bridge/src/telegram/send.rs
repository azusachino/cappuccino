use super::token::Token;
use serde_json::{json, Value};
use std::time::Duration;

pub(super) const API_BASE: &str = "https://api.telegram.org";
pub(super) const REQUEST_TIMEOUT: Duration = Duration::from_secs(10);
pub(super) const MAX_RATE_LIMIT_TRIES: u32 = 3;
pub(super) const MAX_RETRY_AFTER_SECS: u64 = 60;
pub(super) const SERVER_RETRY_DELAY: Duration = Duration::from_secs(30);

/// The result of one send, as far as the bridge can tell.
#[derive(Debug, PartialEq, Eq)]
pub enum Outcome {
    Delivered,
    RateLimited {
        retry_after: u64,
    },
    Rejected(u16),
    Failing(u16),
    /// Timeout or transport error: the message may or may not have arrived.
    Uncertain,
}

/// Posts to the Bot API. Redirects are refused and no error text is kept, since a
/// reqwest error can name the URL and therefore the token.
pub struct Sender {
    client: reqwest::Client,
    base: String,
}

impl Sender {
    pub fn new(base: &str, timeout: Duration) -> Result<Self, String> {
        // reqwest takes the process default TLS provider; iroh passes its own explicitly, so
        // installing ring here does not change it. An existing default is kept.
        let _ = rustls::crypto::ring::default_provider().install_default();
        let client = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(timeout)
            .build()
            .map_err(|_| "telegram client could not start".to_string())?;
        Ok(Sender {
            client,
            base: base.trim_end_matches('/').to_string(),
        })
    }

    pub async fn send(&self, token: &Token, chat_id: &str, text: &str) -> Outcome {
        let url = format!("{}/bot{}/sendMessage", self.base, token.0);
        let body = json!({ "chat_id": chat_id, "text": text });
        let response = match self.client.post(url).json(&body).send().await {
            Ok(response) => response,
            Err(_) => return Outcome::Uncertain,
        };
        let status = response.status().as_u16();
        match status {
            200 => Outcome::Delivered,
            429 => {
                let retry_after = response
                    .json::<Value>()
                    .await
                    .ok()
                    .and_then(|body| body["parameters"]["retry_after"].as_u64())
                    .unwrap_or(1);
                Outcome::RateLimited {
                    retry_after: retry_after.min(MAX_RETRY_AFTER_SECS),
                }
            }
            500..=599 => Outcome::Failing(status),
            _ => Outcome::Rejected(status),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::telegram::test_support::*;

    #[tokio::test]
    async fn outcomes_map_from_status_codes() {
        let long = Duration::from_secs(5);
        let (base, _) = serve(200, Duration::ZERO).await;
        assert_eq!(
            sender(&base, long).send(&token(), "-1", "hi").await,
            Outcome::Delivered
        );
        let (base, _) = serve(429, Duration::ZERO).await;
        assert_eq!(
            sender(&base, long).send(&token(), "-1", "hi").await,
            Outcome::RateLimited { retry_after: 7 }
        );
        let (base, _) = serve(400, Duration::ZERO).await;
        assert_eq!(
            sender(&base, long).send(&token(), "-1", "hi").await,
            Outcome::Rejected(400)
        );
        let (base, _) = serve(500, Duration::ZERO).await;
        assert_eq!(
            sender(&base, long).send(&token(), "-1", "hi").await,
            Outcome::Failing(500)
        );
    }

    #[tokio::test]
    async fn redirects_are_refused_and_timeouts_are_uncertain() {
        let (base, _) = serve(302, Duration::ZERO).await;
        assert_eq!(
            sender(&base, Duration::from_secs(5))
                .send(&token(), "-1", "hi")
                .await,
            Outcome::Rejected(302)
        );
        let (base, _) = serve(200, Duration::from_secs(2)).await;
        assert_eq!(
            sender(&base, Duration::from_millis(200))
                .send(&token(), "-1", "hi")
                .await,
            Outcome::Uncertain
        );
    }
}
