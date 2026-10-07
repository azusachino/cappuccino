//! Synchronous fixture resources. Failed Drop cleanup is never clean:
//! a registered owner retains the resource and retries outside the unwinding
//! test. On unrecoverable host errors the test runner must report the named
//! unresolved owner; these bounded tests do not promise universal termination.
use std::fs;
use std::ops::{Deref, DerefMut};
use std::os::unix::fs::{MetadataExt, PermissionsExt};
use std::path::PathBuf;
use std::process::{Child, ExitStatus};
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::time::{Duration, Instant};

fn lock<T>(value: &Mutex<T>) -> MutexGuard<'_, T> {
    value
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

static OWNERS: Mutex<Vec<Arc<CleanupOwner>>> = Mutex::new(Vec::new());

#[derive(Clone, Copy, Default)]
enum Fault {
    #[default]
    None,
    Kill,
    Reap,
    Metadata,
    Remove,
}

pub struct PrivateDir {
    pub path: PathBuf,
    dev: u64,
    ino: u64,
    fault: Fault,
    retry_gate: Option<Arc<std::sync::Barrier>>,
}
impl PrivateDir {
    pub fn new(path: PathBuf) -> Self {
        fs::create_dir(&path).expect("create private fixture directory");
        fs::set_permissions(&path, fs::Permissions::from_mode(0o700)).unwrap();
        let metadata = fs::symlink_metadata(&path).unwrap();
        Self {
            path,
            dev: metadata.dev(),
            ino: metadata.ino(),
            fault: Fault::None,
            retry_gate: None,
        }
    }
    pub fn remove(self) {
        fs::symlink_metadata(&self.path)
            .unwrap_or_else(|error| panic!("explicit directory cleanup metadata: {error}"));
        remove_dir(&self, Fault::None).unwrap_or_else(|error| panic!("{error}"));
    }
}
fn remove_dir(dir: &PrivateDir, fault: Fault) -> Result<(), String> {
    if matches!(fault, Fault::Metadata) {
        return Err("injected metadata failure".into());
    }
    let metadata = match fs::symlink_metadata(&dir.path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(format!("metadata {}: {error}", dir.path.display())),
    };
    if !metadata.is_dir()
        || metadata.uid() != unsafe { libc::geteuid() }
        || metadata.mode() & 0o7777 != 0o700
        || metadata.dev() != dir.dev
        || metadata.ino() != dir.ino
    {
        return Err(format!(
            "preserving replaced/unsafe directory {}",
            dir.path.display()
        ));
    }
    if matches!(fault, Fault::Remove) {
        return Err("injected remove failure".into());
    }
    fs::remove_dir_all(&dir.path).map_err(|error| format!("remove {}: {error}", dir.path.display()))
}
impl Drop for PrivateDir {
    fn drop(&mut self) {
        if let Err(error) = remove_dir(self, self.fault) {
            let retained = Self {
                path: self.path.clone(),
                dev: self.dev,
                ino: self.ino,
                fault: Fault::None,
                retry_gate: None,
            };
            // Retry revalidates the original filesystem identity, not just its name.
            retain(Resource::Directory(retained), error, self.retry_gate.take());
        }
    }
}

pub struct OwnedChild {
    child: Option<Child>,
    fault: Fault,
    retry_gate: Option<Arc<std::sync::Barrier>>,
}
impl From<Child> for OwnedChild {
    fn from(child: Child) -> Self {
        Self {
            child: Some(child),
            fault: Fault::None,
            retry_gate: None,
        }
    }
}
impl Deref for OwnedChild {
    type Target = Child;
    fn deref(&self) -> &Child {
        self.child.as_ref().expect("child still owned")
    }
}
impl DerefMut for OwnedChild {
    fn deref_mut(&mut self) -> &mut Child {
        self.child.as_mut().expect("child still owned")
    }
}
impl OwnedChild {
    pub fn stop(&mut self, deadline: Duration) -> Result<ExitStatus, String> {
        stop_child(self.child.as_mut().unwrap(), deadline, Fault::None)
    }
}
fn stop_child(child: &mut Child, timeout: Duration, fault: Fault) -> Result<ExitStatus, String> {
    if let Some(status) = child
        .try_wait()
        .map_err(|error| format!("try_wait: {error}"))?
    {
        return Ok(status);
    }
    if matches!(fault, Fault::Kill) {
        return Err("injected kill failure".into());
    }
    if let Err(error) = child.kill() {
        if let Some(status) = child
            .try_wait()
            .map_err(|error| format!("after kill error: {error}"))?
        {
            return Ok(status);
        }
        return Err(format!("kill: {error}"));
    }
    if matches!(fault, Fault::Reap) {
        return Err("injected reap failure".into());
    }
    let deadline = Instant::now() + timeout;
    loop {
        match child.try_wait() {
            Ok(Some(status)) => return Ok(status),
            Err(error) => return Err(format!("reap: {error}")),
            Ok(None) if Instant::now() >= deadline => {
                return Err("child reap unresolved at deadline".into())
            }
            Ok(None) => std::thread::sleep(Duration::from_millis(10)),
        }
    }
}
impl Drop for OwnedChild {
    fn drop(&mut self) {
        let Some(child) = self.child.as_mut() else {
            return;
        };
        if let Err(error) = stop_child(child, Duration::from_secs(10), self.fault) {
            retain(
                Resource::Child(self.child.take().unwrap()),
                error,
                self.retry_gate.take(),
            );
        }
    }
}

enum Resource {
    Child(Child),
    Directory(PrivateDir),
}
impl Resource {
    fn label(&self) -> String {
        match self {
            Self::Child(child) => format!("child pid={}", child.id()),
            Self::Directory(dir) => format!(
                "directory {} dev={} ino={}",
                dir.path.display(),
                dir.dev,
                dir.ino
            ),
        }
    }
    fn retry(&mut self) -> Result<(), String> {
        match self {
            Self::Child(child) => {
                stop_child(child, Duration::from_secs(1), Fault::None).map(|_| ())
            }
            Self::Directory(dir) => remove_dir(dir, Fault::None),
        }
    }
}
struct CleanupOwner {
    label: String,
    initial_error: String,
    resource: Mutex<Option<Resource>>,
    worker: Mutex<Option<std::thread::JoinHandle<()>>>,
    recovered: Mutex<bool>,
    changed: Condvar,
}
fn retain(resource: Resource, error: String, gate: Option<Arc<std::sync::Barrier>>) {
    let owner = Arc::new(CleanupOwner {
        label: resource.label(),
        initial_error: error,
        resource: Mutex::new(Some(resource)),
        worker: Mutex::new(None),
        recovered: Mutex::new(false),
        changed: Condvar::new(),
    });
    lock(&OWNERS).push(Arc::clone(&owner));
    let running = Arc::clone(&owner);
    let mut slot = lock(&owner.worker);
    let spawned = std::thread::Builder::new()
        .name("fixture-cleanup-owner".into())
        .spawn(move || {
            if let Some(gate) = gate {
                gate.wait();
            }
            loop {
                let mut resource = lock(&running.resource);
                if resource.as_mut().unwrap().retry().is_ok() {
                    resource.take();
                    drop(resource);
                    lock(&OWNERS).retain(|entry| !Arc::ptr_eq(entry, &running));
                    *lock(&running.recovered) = true;
                    running.changed.notify_all();
                    return;
                }
                drop(resource);
                std::thread::sleep(Duration::from_millis(100));
            }
        });
    match spawned {
        Ok(worker) => *slot = Some(worker),
        Err(error) => eprintln!(
            "unresolved registered fixture owner {}: worker startup failed: {error}",
            owner.label
        ),
    }
    drop(slot);
    let message = format!("UNSUCCESSFUL fixture cleanup: {}; responsible owner=fixture-cleanup-owner [{}] (test runner must reconcile if process exits unresolved)", owner.initial_error, owner.label);
    if std::thread::panicking() {
        eprintln!("{message}");
    } else {
        panic!("{message}");
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::panic::AssertUnwindSafe;
    use std::sync::atomic::{AtomicU64, Ordering};
    static NEXT: AtomicU64 = AtomicU64::new(0);

    fn observe(label: &str, gate: Arc<std::sync::Barrier>) {
        let owner = lock(&OWNERS)
            .iter()
            .find(|owner| owner.label == label)
            .cloned()
            .expect("registered live cleanup owner");
        assert!(lock(&owner.resource).is_some());
        assert!(!*lock(&owner.recovered));
        assert!(!owner.initial_error.is_empty());
        gate.wait();
        let done = lock(&owner.recovered);
        let (done, timed) = owner
            .changed
            .wait_timeout_while(done, Duration::from_secs(2), |done| !*done)
            .unwrap();
        assert!(
            *done && !timed.timed_out(),
            "resource terminal cleanup observed"
        );
        drop(done);
        lock(&owner.worker)
            .take()
            .unwrap()
            .join()
            .expect("cleanup worker joined");
        assert!(lock(&owner.resource).is_none());
        assert!(!lock(&OWNERS).iter().any(|entry| Arc::ptr_eq(entry, &owner)));
    }

    fn failed_drop<T>(owner: T, unwind: bool) {
        let failure = std::panic::catch_unwind(AssertUnwindSafe(|| {
            if unwind {
                let _owner = owner;
                panic!("original test body panic");
            }
            drop(owner);
        }))
        .expect_err("Drop cleanup failure cannot masquerade as success");
        if unwind {
            assert_eq!(
                failure.downcast_ref::<&str>(),
                Some(&"original test body panic")
            );
        }
    }

    #[test]
    fn failed_child_kill_and_reap_drop_retain_owner_until_terminal() {
        for (fault, unwind) in [Fault::Kill, Fault::Reap]
            .into_iter()
            .flat_map(|fault| [false, true].map(|unwind| (fault, unwind)))
        {
            let child = std::process::Command::new("sleep")
                .arg("30")
                .spawn()
                .unwrap();
            let label = format!("child pid={}", child.id());
            let gate = Arc::new(std::sync::Barrier::new(2));
            let mut owner = OwnedChild::from(child);
            owner.fault = fault;
            owner.retry_gate = Some(Arc::clone(&gate));
            failed_drop(owner, unwind);
            observe(&label, gate);
        }
    }

    #[test]
    fn failed_metadata_and_remove_drop_retain_identity_until_terminal() {
        for (fault, unwind) in [Fault::Metadata, Fault::Remove]
            .into_iter()
            .flat_map(|fault| [false, true].map(|unwind| (fault, unwind)))
        {
            let path = std::env::temp_dir().join(format!(
                "capp-resource-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            let mut owner = PrivateDir::new(path.clone());
            let label = format!(
                "directory {} dev={} ino={}",
                owner.path.display(),
                owner.dev,
                owner.ino
            );
            let gate = Arc::new(std::sync::Barrier::new(2));
            owner.fault = fault;
            owner.retry_gate = Some(Arc::clone(&gate));
            failed_drop(owner, unwind);
            observe(&label, gate);
            assert!(!path.exists());
        }
    }
}
