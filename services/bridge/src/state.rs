//! Owner-only filesystem state for the standalone bridge lifecycle.

use std::{
    ffi::CString,
    fs::File,
    io::{self, Read},
    os::fd::{AsRawFd, FromRawFd},
    os::unix::fs::MetadataExt,
    path::{Component, Path, PathBuf},
};

const DIR_MODE: u32 = 0o700;
const FILE_MODE: u32 = 0o600;

pub struct StateDir {
    path: PathBuf,
    dir: File,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct FileIdentity {
    dev: u64,
    ino: u64,
}

#[derive(Clone, Copy, Debug)]
struct PathInfo {
    dev: u64,
    ino: u64,
    mode: u32,
    uid: u32,
    nlink: u64,
}

impl StateDir {
    pub fn open(path: &Path) -> Result<Self, String> {
        if !path.is_absolute() {
            return Err(format!(
                "state directory must be absolute: {}",
                path.display()
            ));
        }
        let mut names = Vec::new();
        for component in path.components() {
            match component {
                Component::RootDir => {}
                Component::Normal(name) => names.push(name.to_owned()),
                _ => return Err(format!("unsafe state directory path: {}", path.display())),
            }
        }
        if names.is_empty() {
            return Err("refusing filesystem root as bridge state directory".into());
        }

        let mut current =
            File::open("/").map_err(|error| format!("open filesystem root: {error}"))?;
        let uid = unsafe { libc::geteuid() };
        let mut resolved = PathBuf::from("/");
        for (index, name) in names.iter().enumerate() {
            let final_component = index + 1 == names.len();
            let text = name
                .to_str()
                .ok_or_else(|| format!("non-UTF8 state path component in {}", path.display()))?;
            let c_name = CString::new(text).map_err(|_| "NUL in state directory path")?;
            let mut created = false;
            let next = match open_directory_at(&current, &c_name) {
                Ok(file) => file,
                Err(error) if error.raw_os_error() == Some(libc::ENOENT) => {
                    if unsafe {
                        libc::mkdirat(
                            current.as_raw_fd(),
                            c_name.as_ptr(),
                            DIR_MODE as libc::mode_t,
                        )
                    } == 0
                    {
                        created = true;
                    } else if io::Error::last_os_error().raw_os_error() != Some(libc::EEXIST) {
                        return Err(format!(
                            "create state directory {}: {}",
                            resolved.join(name).display(),
                            io::Error::last_os_error()
                        ));
                    }
                    open_directory_at(&current, &c_name).map_err(|error| {
                        format!(
                            "open created state directory {}: {error}",
                            resolved.join(name).display()
                        )
                    })?
                }
                Err(error) => {
                    return Err(format!(
                        "open state directory component {}: {error}",
                        resolved.join(name).display()
                    ));
                }
            };
            let metadata = next
                .metadata()
                .map_err(|error| format!("inspect {}: {error}", resolved.join(name).display()))?;
            let mode = metadata.mode() & 0o7777;
            if !metadata.is_dir() {
                return Err(format!(
                    "state path component is not a directory: {}",
                    resolved.join(name).display()
                ));
            }
            if final_component {
                if metadata.uid() != uid {
                    return Err(format!(
                        "state directory {} is not owned by effective uid {uid}",
                        resolved.join(name).display()
                    ));
                }
                if created {
                    set_fd_mode(&next, DIR_MODE).map_err(|error| {
                        format!(
                            "set private mode on {}: {error}",
                            resolved.join(name).display()
                        )
                    })?;
                } else if mode != DIR_MODE {
                    return Err(format!(
                        "state directory {} must be owner-only mode 0700 (found {mode:04o}); refusing to chmod it",
                        resolved.join(name).display()
                    ));
                }
                verify_directory(&next, &resolved.join(name))?
            } else {
                let trusted_owner = metadata.uid() == 0 || metadata.uid() == uid;
                let safe_writable =
                    mode & 0o022 == 0 || (metadata.uid() == 0 && mode & 0o1000 != 0);
                if !trusted_owner || !safe_writable {
                    return Err(format!(
                        "unsafe parent directory {} (owner {}, mode {mode:04o})",
                        resolved.join(name).display(),
                        metadata.uid()
                    ));
                }
                if created {
                    set_fd_mode(&next, DIR_MODE).map_err(|error| {
                        format!(
                            "set private mode on {}: {error}",
                            resolved.join(name).display()
                        )
                    })?;
                }
            }
            resolved.push(name);
            current = next;
        }
        Ok(Self {
            path: resolved,
            dir: current,
        })
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    pub fn try_clone(&self) -> Result<Self, String> {
        Ok(Self {
            path: self.path.clone(),
            dir: self
                .dir
                .try_clone()
                .map_err(|error| format!("clone state directory handle: {error}"))?,
        })
    }

    pub fn open_existing(&self, name: &str) -> Result<Option<File>, String> {
        self.open_existing_with_access(name, libc::O_RDONLY)
    }

    fn open_existing_with_access(
        &self,
        name: &str,
        access: libc::c_int,
    ) -> Result<Option<File>, String> {
        validate_name(name)?;
        let c_name = CString::new(name).expect("validated filename");
        let fd = unsafe {
            libc::openat(
                self.dir.as_raw_fd(),
                c_name.as_ptr(),
                access | libc::O_NONBLOCK | libc::O_CLOEXEC | libc::O_NOFOLLOW,
                0,
            )
        };
        if fd < 0 {
            let error = io::Error::last_os_error();
            if error.raw_os_error() == Some(libc::ENOENT) {
                return Ok(None);
            }
            return Err(format!(
                "open state file {}: {error}",
                self.path.join(name).display()
            ));
        }
        let file = unsafe { File::from_raw_fd(fd) };
        verify_private_file(&file, &self.path.join(name))?;
        Ok(Some(file))
    }

    pub fn create_file(&self, name: &str) -> Result<File, String> {
        validate_name(name)?;
        let c_name = CString::new(name).expect("validated filename");
        let fd = unsafe {
            libc::openat(
                self.dir.as_raw_fd(),
                c_name.as_ptr(),
                libc::O_RDWR
                    | libc::O_CREAT
                    | libc::O_EXCL
                    | libc::O_CLOEXEC
                    | libc::O_NOFOLLOW
                    | libc::O_NONBLOCK,
                FILE_MODE,
            )
        };
        if fd < 0 {
            return Err(format!(
                "create state file {}: {}",
                self.path.join(name).display(),
                io::Error::last_os_error()
            ));
        }
        let file = unsafe { File::from_raw_fd(fd) };
        let uid = unsafe { libc::geteuid() };
        if file
            .metadata()
            .map_err(|error| format!("inspect new state file: {error}"))?
            .uid()
            != uid
        {
            return Err(format!(
                "new state file has unexpected owner: {}",
                self.path.join(name).display()
            ));
        }
        set_fd_mode(&file, FILE_MODE).map_err(|error| {
            format!(
                "set private mode on {}: {error}",
                self.path.join(name).display()
            )
        })?;
        verify_private_file(&file, &self.path.join(name))?;
        Ok(file)
    }

    pub fn open_or_create_file(&self, name: &str) -> Result<File, String> {
        if let Some(file) = self.open_existing_with_access(name, libc::O_RDWR)? {
            return Ok(file);
        }
        match self.create_file(name) {
            Ok(file) => Ok(file),
            Err(create_error) => self
                .open_existing_with_access(name, libc::O_RDWR)?
                .ok_or(create_error),
        }
    }

    pub fn require_absent(&self, name: &str) -> Result<(), String> {
        validate_name(name)?;
        match self.path_info(name)? {
            None => Ok(()),
            Some(_) => Err(format!(
                "refusing to replace existing state path {}",
                self.path.join(name).display()
            )),
        }
    }

    pub fn socket_path(&self, name: &str) -> Result<PathBuf, String> {
        validate_name(name)?;
        Ok(self.path.join(name))
    }

    pub fn created_socket_identity(&self, name: &str) -> Result<FileIdentity, String> {
        let info = self.path_info(name)?.ok_or_else(|| {
            format!(
                "control socket disappeared: {}",
                self.path.join(name).display()
            )
        })?;
        if info.mode & libc::S_IFMT as u32 != libc::S_IFSOCK as u32
            || info.uid != unsafe { libc::geteuid() }
        {
            return Err(format!(
                "unsafe newly-created control socket: {}",
                self.path.join(name).display()
            ));
        }
        Ok(FileIdentity {
            dev: info.dev,
            ino: info.ino,
        })
    }

    pub fn socket_identity(&self, name: &str) -> Result<FileIdentity, String> {
        let info = self.path_info(name)?.ok_or_else(|| {
            format!(
                "control socket disappeared: {}",
                self.path.join(name).display()
            )
        })?;
        let uid = unsafe { libc::geteuid() };
        if info.mode & libc::S_IFMT as u32 != libc::S_IFSOCK as u32
            || info.uid != uid
            || info.mode & 0o7777 != FILE_MODE
            || info.nlink != 1
        {
            return Err(format!(
                "unsafe control socket path: {}",
                self.path.join(name).display()
            ));
        }
        Ok(FileIdentity {
            dev: info.dev,
            ino: info.ino,
        })
    }

    pub fn remove_owned_socket_path(&self, name: &str) -> Result<(), String> {
        let Some(info) = self.path_info(name)? else {
            return Ok(());
        };
        if info.mode & libc::S_IFMT as u32 != libc::S_IFSOCK as u32
            || info.uid != unsafe { libc::geteuid() }
        {
            return Err(format!(
                "refusing to remove unsafe control socket path {}",
                self.path.join(name).display()
            ));
        }
        self.remove_socket(
            name,
            FileIdentity {
                dev: info.dev,
                ino: info.ino,
            },
        )
    }

    pub fn remove_socket(&self, name: &str, identity: FileIdentity) -> Result<(), String> {
        let Some(info) = self.path_info(name)? else {
            return Ok(());
        };
        let uid = unsafe { libc::geteuid() };
        if info.dev != identity.dev
            || info.ino != identity.ino
            || info.mode & libc::S_IFMT as u32 != libc::S_IFSOCK as u32
            || info.uid != uid
        {
            return Err(format!(
                "refusing to remove changed control socket {}",
                self.path.join(name).display()
            ));
        }
        let c_name = CString::new(name).expect("validated filename");
        if unsafe { libc::unlinkat(self.dir.as_raw_fd(), c_name.as_ptr(), 0) } != 0 {
            return Err(format!(
                "remove control socket {}: {}",
                self.path.join(name).display(),
                io::Error::last_os_error()
            ));
        }
        Ok(())
    }

    pub fn remove_owned_file(&self, name: &str, expected: &str) -> Result<(), String> {
        let Some(mut file) = self.open_existing(name)? else {
            return Ok(());
        };
        let metadata = file
            .metadata()
            .map_err(|error| format!("inspect {}: {error}", self.path.join(name).display()))?;
        let identity = FileIdentity {
            dev: metadata.dev(),
            ino: metadata.ino(),
        };
        let mut contents = String::new();
        file.read_to_string(&mut contents)
            .map_err(|error| format!("read {}: {error}", self.path.join(name).display()))?;
        if contents != expected {
            return Err(format!(
                "refusing to remove state file with unexpected contents: {}",
                self.path.join(name).display()
            ));
        }
        let Some(current) = self.path_info(name)? else {
            return Ok(());
        };
        if current.dev != identity.dev
            || current.ino != identity.ino
            || current.mode & libc::S_IFMT as u32 != libc::S_IFREG as u32
        {
            return Err(format!(
                "refusing to remove changed state file: {}",
                self.path.join(name).display()
            ));
        }
        let c_name = CString::new(name).expect("validated filename");
        if unsafe { libc::unlinkat(self.dir.as_raw_fd(), c_name.as_ptr(), 0) } != 0 {
            return Err(format!(
                "remove state file {}: {}",
                self.path.join(name).display(),
                io::Error::last_os_error()
            ));
        }
        Ok(())
    }

    fn path_info(&self, name: &str) -> Result<Option<PathInfo>, String> {
        validate_name(name)?;
        let c_name = CString::new(name).expect("validated filename");
        let mut stat = unsafe { std::mem::zeroed::<libc::stat>() };
        if unsafe {
            libc::fstatat(
                self.dir.as_raw_fd(),
                c_name.as_ptr(),
                &mut stat,
                libc::AT_SYMLINK_NOFOLLOW,
            )
        } != 0
        {
            let error = io::Error::last_os_error();
            if error.raw_os_error() == Some(libc::ENOENT) {
                return Ok(None);
            }
            return Err(format!(
                "inspect state path {}: {error}",
                self.path.join(name).display()
            ));
        }
        Ok(Some(PathInfo {
            dev: stat.st_dev as u64,
            ino: stat.st_ino as u64,
            mode: stat.st_mode as u32,
            uid: stat.st_uid,
            nlink: stat.st_nlink as u64,
        }))
    }
}

fn validate_name(name: &str) -> Result<(), String> {
    if name.is_empty()
        || name == "."
        || name == ".."
        || !name
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b"._-".contains(&byte))
    {
        return Err(format!("unsafe state filename: {name:?}"));
    }
    Ok(())
}

fn open_directory_at(parent: &File, name: &CString) -> io::Result<File> {
    let fd = unsafe {
        libc::openat(
            parent.as_raw_fd(),
            name.as_ptr(),
            libc::O_RDONLY | libc::O_DIRECTORY | libc::O_CLOEXEC | libc::O_NOFOLLOW,
            0,
        )
    };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(unsafe { File::from_raw_fd(fd) })
}

fn verify_directory(file: &File, path: &Path) -> Result<(), String> {
    let metadata = file
        .metadata()
        .map_err(|error| format!("inspect {}: {error}", path.display()))?;
    let uid = unsafe { libc::geteuid() };
    let mode = metadata.mode() & 0o7777;
    if !metadata.is_dir() || metadata.uid() != uid || mode != DIR_MODE {
        return Err(format!("unsafe state directory {} (owner {}, mode {mode:04o}); require current owner and mode 0700", path.display(), metadata.uid()));
    }
    Ok(())
}

fn verify_private_file(file: &File, path: &Path) -> Result<(), String> {
    let metadata = file
        .metadata()
        .map_err(|error| format!("inspect {}: {error}", path.display()))?;
    let mode = metadata.mode() & 0o7777;
    let uid = unsafe { libc::geteuid() };
    if !metadata.is_file() || metadata.uid() != uid || mode != FILE_MODE || metadata.nlink() != 1 {
        return Err(format!("unsafe state file {} (owner {}, mode {mode:04o}, links {}); require current owner, regular file, mode 0600, one link", path.display(), metadata.uid(), metadata.nlink()));
    }
    Ok(())
}

fn set_fd_mode(file: &File, mode: u32) -> io::Result<()> {
    if unsafe { libc::fchmod(file.as_raw_fd(), mode as libc::mode_t) } != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}
