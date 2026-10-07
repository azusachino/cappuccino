//! Cooperative I/O fixture cleanup. A missed deadline is unresolved, not
//! termination: a registered cleanup job owns tasks until their terminal join.
use futures_util::FutureExt;
use std::future::Future;
use std::panic::AssertUnwindSafe;
use std::sync::{Arc, Mutex, MutexGuard};

fn recover_lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}
use std::time::Duration;
use tokio::task::JoinHandle;

struct Cleanup {
    result: Mutex<Option<Vec<String>>>,
    changed: tokio::sync::Notify,
    job: Mutex<Option<JoinHandle<()>>>,
}

impl Cleanup {
    fn start(work: impl Future<Output = Vec<String>> + Send + 'static) -> Arc<Self> {
        let owner = Arc::new(Self {
            result: Mutex::new(None),
            changed: tokio::sync::Notify::new(),
            job: Mutex::new(None),
        });
        // Register before an immediately-completing job can remove itself.
        let mut slot = owner.job.lock().unwrap();
        let running = Arc::clone(&owner);
        *slot = Some(tokio::spawn(async move {
            let mut errors = match AssertUnwindSafe(work).catch_unwind().await {
                Ok(errors) => errors,
                Err(_) => vec!["cleanup job panicked; cleanup was not successful".into()],
            };
            if running.result.is_poisoned() || running.job.is_poisoned() {
                errors.push("cleanup owner mutex poisoned; recovered for resource teardown".into());
            }
            recover_lock(&running.job).take();
            *recover_lock(&running.result) = Some(errors);
            running.changed.notify_waiters();
        }));
        drop(slot);
        owner
    }

    async fn wait(self: &Arc<Self>, deadline: Duration) -> CleanupReport {
        let finished = async {
            loop {
                let notified = self.changed.notified();
                tokio::pin!(notified);
                notified.as_mut().enable();
                if let Some(errors) = recover_lock(&self.result).clone() {
                    return errors;
                }
                notified.await;
            }
        };
        let errors = match tokio::time::timeout(deadline, finished).await {
            Ok(errors) => errors,
            Err(_) => {
                vec!["cleanup unresolved: live cleanup owner still holds pending tasks".into()]
            }
        };
        CleanupReport {
            errors,
            _owner: Arc::clone(self),
        }
    }
}

pub struct CleanupReport {
    pub errors: Vec<String>,
    _owner: Arc<Cleanup>,
}
impl CleanupReport {
    pub fn expect_clean(self) {
        assert!(self.errors.is_empty(), "fixture cleanup: {:?}", self.errors);
    }
}

// Runtime shutdown is the final containment boundary. If shutdown cancels
// the cleanup job, its owned children also receive cancellation. These are
// async I/O fixtures, not non-yielding or blocking tasks.
struct Handles<T>(Vec<JoinHandle<T>>);
impl<T> Drop for Handles<T> {
    fn drop(&mut self) {
        for handle in &self.0 {
            handle.abort();
        }
    }
}

#[derive(Default)]
struct State {
    closed: bool,
    handles: Vec<JoinHandle<()>>,
    cleanup: Option<Arc<Cleanup>>,
}
#[derive(Default)]
pub struct TaskTracker {
    state: Mutex<State>,
}
impl TaskTracker {
    pub fn spawn(&self, work: impl Future<Output = ()> + Send + 'static) -> Result<(), String> {
        let mut state = recover_lock(&self.state);
        if self.state.is_poisoned() {
            return Err("task tracker state poisoned; new task not started".into());
        }
        if state.closed {
            return Err("task tracker is closed; new task not started".into());
        }
        state.handles.push(tokio::spawn(work));
        Ok(())
    }
    fn begin_stop(&self) -> Arc<Cleanup> {
        let poisoned = self.state.is_poisoned();
        let mut state = recover_lock(&self.state);
        state.closed = true;
        if let Some(owner) = &state.cleanup {
            return Arc::clone(owner);
        }
        let mut handles = Handles(std::mem::take(&mut state.handles));
        for handle in &handles.0 {
            handle.abort();
        }
        let owner = Cleanup::start(async move {
            let mut errors = if poisoned {
                vec!["task tracker state poisoned; recovered for resource teardown".into()]
            } else {
                Vec::new()
            };
            for handle in &mut handles.0 {
                if let Err(error) = handle.await {
                    if !error.is_cancelled() {
                        errors.push(format!("fixture task panicked: {error}"));
                    }
                }
            }
            errors
        });
        state.cleanup = Some(Arc::clone(&owner));
        owner
    }
    pub async fn stop(&self) -> CleanupReport {
        self.stop_with_deadline(Duration::from_secs(10)).await
    }
    pub async fn stop_with_deadline(&self, deadline: Duration) -> CleanupReport {
        self.begin_stop().wait(deadline).await
    }
}
impl Drop for TaskTracker {
    fn drop(&mut self) {
        // Ownership belongs to the registered job, not the caller's stop future.
        self.begin_stop();
    }
}

pub async fn cancel_and_join<T: Send + 'static>(
    handle: JoinHandle<T>,
    deadline: Duration,
    require_cancelled: bool,
) -> CleanupReport {
    cancel_with_cleanup(handle, deadline, require_cancelled, || Ok(())).await
}

pub async fn cancel_with_cleanup<T: Send + 'static>(
    handle: JoinHandle<T>,
    deadline: Duration,
    require_cancelled: bool,
    cleanup: impl FnOnce() -> Result<(), String> + Send + 'static,
) -> CleanupReport {
    let mut handles = Handles(vec![handle]);
    handles.0[0].abort();
    let owner = Cleanup::start(async move {
        let mut errors = match (&mut handles.0[0]).await {
            Err(error) if error.is_cancelled() => Vec::new(),
            Err(error) => vec![format!("fixture task panicked: {error}")],
            Ok(_) if require_cancelled => {
                vec!["probe completed; mid-flight cancellation expected".into()]
            }
            Ok(_) => Vec::new(),
        };
        if let Err(error) = cleanup() {
            errors.push(error);
        }
        errors
    });
    owner.wait(deadline).await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn timeout_and_dropped_report_keep_live_cleanup_owner_until_terminal() {
        let (release, gate) = tokio::sync::oneshot::channel();
        let owner = Cleanup::start(async move {
            gate.await.unwrap();
            Vec::new()
        });
        let weak = Arc::downgrade(&owner);
        let report = owner.wait(Duration::ZERO).await;
        assert!(report
            .errors
            .iter()
            .any(|error| error.contains("unresolved")));
        drop(report);
        drop(owner);
        assert!(
            weak.upgrade().is_some(),
            "pending job must retain its owner"
        );
        release.send(()).unwrap();
        tokio::time::timeout(Duration::from_secs(1), async {
            while weak.upgrade().is_some() {
                tokio::task::yield_now().await;
            }
        })
        .await
        .expect("terminal job releases its owner and registered handle");
    }

    #[tokio::test]
    async fn cleanup_callback_panic_reports_failure_after_child_join() {
        let task = tokio::spawn(std::future::pending::<()>());
        let report = cancel_with_cleanup(task, Duration::from_secs(1), false, || {
            panic!("injected cleanup callback panic");
        })
        .await;
        assert!(report
            .errors
            .iter()
            .any(|error| error.contains("cleanup job panicked")));
        assert!(report._owner.job.lock().unwrap().is_none());
        let weak = Arc::downgrade(&report._owner);
        drop(report);
        assert!(
            weak.upgrade().is_none(),
            "panic cannot retain an owner/job cycle"
        );
    }

    #[tokio::test]
    async fn poisoned_publication_lock_reports_failure_and_releases_owner() {
        let (release, gate) = tokio::sync::oneshot::channel();
        let owner = Cleanup::start(async move {
            gate.await.unwrap();
            Vec::new()
        });
        let _ = std::panic::catch_unwind(AssertUnwindSafe(|| {
            let _guard = owner.result.lock().unwrap();
            panic!("injected publication lock poison");
        }));
        release.send(()).unwrap();
        let report = owner.wait(Duration::from_secs(1)).await;
        assert!(report.errors.iter().any(|error| error.contains("poisoned")));
        let weak = Arc::downgrade(&owner);
        drop(report);
        drop(owner);
        assert!(weak.upgrade().is_none());
    }

    #[tokio::test]
    async fn concurrent_cleanup_waiters_all_observe_terminal_result() {
        let (release, gate) = tokio::sync::oneshot::channel();
        let owner = Cleanup::start(async move {
            gate.await.unwrap();
            Vec::new()
        });
        let mut first = Box::pin(owner.wait(Duration::from_secs(1)));
        let mut second = Box::pin(owner.wait(Duration::from_secs(1)));
        assert!(futures_util::poll!(&mut first).is_pending());
        assert!(futures_util::poll!(&mut second).is_pending());
        release.send(()).unwrap();
        let (first, second) = tokio::join!(first, second);
        first.expect_clean();
        second.expect_clean();
    }

    #[tokio::test]
    async fn cancelling_stop_future_and_dropping_tracker_does_not_cancel_cleanup() {
        struct Guard(Option<tokio::sync::oneshot::Sender<()>>);
        impl Drop for Guard {
            fn drop(&mut self) {
                let _ = self.0.take().unwrap().send(());
            }
        }
        let tracker = TaskTracker::default();
        let (dropped, observe) = tokio::sync::oneshot::channel();
        let guard = Guard(Some(dropped));
        tracker
            .spawn(async move {
                let _guard = guard;
                std::future::pending::<()>().await;
            })
            .unwrap();
        let mut stop = Box::pin(tracker.stop());
        assert!(futures_util::poll!(&mut stop).is_pending());
        let weak = Arc::downgrade(tracker.state.lock().unwrap().cleanup.as_ref().unwrap());
        drop(stop);
        drop(tracker);
        tokio::time::timeout(Duration::from_secs(1), observe)
            .await
            .unwrap()
            .unwrap();
        tokio::time::timeout(Duration::from_secs(1), async {
            while weak.upgrade().is_some() {
                tokio::task::yield_now().await;
            }
        })
        .await
        .expect("all children joined, cleanup owner released");
    }
}
