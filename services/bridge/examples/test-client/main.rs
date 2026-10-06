//! `test-client`: the checked-in headless companion for the cappuccino
//! bridge. Read-only: it issues GET requests and WS reads only, fails
//! nonzero on any mismatch, protocol downgrade (under `--require-h2`),
//! malformed frame, timeout or disconnect.
//!
//! Modes:
//! - `check`  — the acceptance probe (default).
//! - `bench`  — reproducible synthetic benchmark (cold/warm HTTP, stream
//!   churn); reports p50/p95, throughput and getrusage CPU/peak RSS with the
//!   observed protocol. Establishes a baseline; asserts no thresholds.
//!
//! An example target (not a second binary) so `cargo run` / `cargo install`
//! defaults of the bridge crate stay unchanged.

#[path = "probe.rs"]
mod probe;

use probe::{BenchConfig, HttpPreference, ProbeOptions};
use std::time::Duration;

fn usage() -> String {
    "usage: test-client check --base-url URL [--require-h2] [--session NAME] \
     [--expect-event stream_open|error] [--timeout-secs N]\n       \
     test-client bench --base-url URL [--require-h2] [--session NAME] \
     [--iterations N] [--stream-cycles N] [--timeout-secs N]"
        .into()
}

struct Args {
    mode: String,
    base_url: Option<String>,
    require_h2: bool,
    session: Option<String>,
    expect_event: Option<String>,
    timeout: Duration,
    iterations: usize,
    stream_cycles: usize,
}

fn parse_args() -> Result<Args, String> {
    let argv: Vec<String> = std::env::args().skip(1).collect();
    if argv.is_empty() {
        return Err(usage());
    }
    let mut args = Args {
        mode: argv[0].clone(),
        base_url: None,
        require_h2: false,
        session: None,
        expect_event: None,
        timeout: Duration::from_secs(10),
        iterations: 200,
        stream_cycles: 50,
    };
    if !matches!(args.mode.as_str(), "check" | "bench" | "help") {
        return Err(usage());
    }
    let mut i = 1;
    while i < argv.len() {
        let value = |i: &mut usize| -> Result<String, String> {
            *i += 1;
            argv.get(*i)
                .cloned()
                .ok_or_else(|| format!("missing value for {}", argv[*i - 1]))
        };
        match argv[i].as_str() {
            "--base-url" => args.base_url = Some(value(&mut i)?),
            "--require-h2" => args.require_h2 = true,
            "--session" => args.session = Some(value(&mut i)?),
            "--expect-event" => args.expect_event = Some(value(&mut i)?),
            "--timeout-secs" => {
                args.timeout =
                    Duration::from_secs(value(&mut i)?.parse().map_err(|_| "bad timeout")?)
            }
            "--iterations" => {
                args.iterations = value(&mut i)?.parse().map_err(|_| "bad iterations")?
            }
            "--stream-cycles" => {
                args.stream_cycles = value(&mut i)?.parse().map_err(|_| "bad stream-cycles")?
            }
            other => return Err(format!("unknown option {other}\n{}", usage())),
        }
        i += 1;
    }
    if args.mode != "help" && args.base_url.is_none() {
        return Err(format!("--base-url is required\n{}", usage()));
    }
    Ok(args)
}

fn options(args: &Args) -> ProbeOptions {
    let mut options = ProbeOptions::new(args.base_url.clone().unwrap());
    options.preference = if args.require_h2 {
        HttpPreference::RequireH2
    } else {
        HttpPreference::PreferH2
    };
    options.timeout = args.timeout;
    options.session = args.session.clone();
    options.expect_event = args.expect_event.clone();
    options
}

fn main() {
    let args = match parse_args() {
        Ok(args) => args,
        Err(error) => {
            eprintln!("test-client: {error}");
            std::process::exit(2);
        }
    };
    if args.mode == "help" {
        println!("{}", usage());
        return;
    }
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("tokio runtime");
    let result = runtime.block_on(async move {
        match args.mode.as_str() {
            "check" => probe::run_checks(&options(&args)).await.map(|report| {
                for line in report.lines {
                    println!("{line}");
                }
                println!(
                    "transport: {}",
                    if args.require_h2 {
                        "strict-h2"
                    } else if report.protocols.iter().any(|p| *p == "http1") {
                        "http2-preferred (http1 fallback used)"
                    } else {
                        "http2-preferred"
                    }
                );
            }),
            "bench" => probe::run_bench(
                &options(&args),
                &BenchConfig {
                    iterations: args.iterations,
                    warmup: 20,
                    stream_cycles: args.stream_cycles,
                    session: args.session.clone(),
                },
            )
            .await
            .map(|report| println!("{report}")),
            _ => Err(usage()),
        }
    });
    if let Err(error) = result {
        eprintln!("test-client: {error}");
        std::process::exit(1);
    }
}
