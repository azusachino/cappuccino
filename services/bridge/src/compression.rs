//! Negotiated compression for ordinary JSON only; SSE/WS stay untouched.
use axum::body::HttpBody;
use axum::http::{header::CONTENT_TYPE, Response};
use tower_http::compression::predicate::{And, DefaultPredicate, Predicate};
use tower_http::compression::CompressionLayer;

#[derive(Clone)]
pub struct JsonOnly;

impl Predicate for JsonOnly {
    fn should_compress<B: HttpBody>(&self, response: &Response<B>) -> bool {
        response
            .headers()
            .get(CONTENT_TYPE)
            .and_then(|value| value.to_str().ok())
            .is_some_and(|value| {
                value
                    .split(';')
                    .next()
                    .is_some_and(|mime| mime.trim() == "application/json")
            })
    }
}

pub fn json_layer() -> CompressionLayer<And<DefaultPredicate, JsonOnly>> {
    CompressionLayer::new().compress_when(DefaultPredicate::new().and(JsonOnly))
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::{routing::get, Json, Router};
    use bytes::Bytes;
    use http_body_util::{BodyExt, Empty};
    use hyper_util::{client::legacy::Client, rt::TokioExecutor};
    use std::io::Read;

    #[tokio::test]
    async fn gzip_negotiates_losslessly_and_never_compresses_sse() {
        let text = "native conversation content ".repeat(1000);
        let expected = serde_json::to_vec(&serde_json::json!({"text": text})).unwrap();
        let app = Router::new()
            .route(
                "/json",
                get(move || {
                    let text = text.clone();
                    async move { Json(serde_json::json!({"text":text})) }
                }),
            )
            .route(
                "/sse",
                get(|| async {
                    (
                        [(CONTENT_TYPE, "text/event-stream")],
                        "data: first\n\n".repeat(1000),
                    )
                }),
            )
            .layer(json_layer());
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        let client = Client::builder(TokioExecutor::new()).build_http::<Empty<Bytes>>();
        for (path, encoding) in [
            ("json", "gzip"),
            ("json", "identity"),
            ("json", "gzip;q=0"),
            ("sse", "gzip"),
        ] {
            let request = axum::http::Request::builder()
                .uri(format!("http://{address}/{path}"))
                .header("Accept-Encoding", encoding)
                .body(Empty::new())
                .unwrap();
            let response = client.request(request).await.unwrap();
            let compressed = path == "json" && encoding == "gzip";
            assert_eq!(
                response
                    .headers()
                    .get("content-encoding")
                    .map(|h| h.to_str().unwrap()),
                if compressed { Some("gzip") } else { None }
            );
            if compressed {
                assert!(response
                    .headers()
                    .get("vary")
                    .unwrap()
                    .to_str()
                    .unwrap()
                    .contains("accept-encoding"));
            }
            let body = response.into_body().collect().await.unwrap().to_bytes();
            if compressed {
                assert!(body.len() < expected.len());
                let mut decoded = Vec::new();
                flate2::read::GzDecoder::new(body.as_ref())
                    .read_to_end(&mut decoded)
                    .unwrap();
                assert_eq!(decoded, expected);
            } else if path == "json" {
                assert_eq!(body.as_ref(), expected.as_slice());
            } else {
                assert!(body.starts_with(b"data: first\n\n"));
            }
        }
        server.abort();
        assert!(server.await.unwrap_err().is_cancelled());
    }
}
