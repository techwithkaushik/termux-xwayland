//! JNI socket diagnostics for the Android Host app.
//!
//! Reads from an already-connected AF_UNIX/SOCK_STREAM descriptor. This is
//! not a Wayland protocol implementation: a real client must parse protocol
//! messages and handle SCM_RIGHTS descriptors separately.

use android_logger::Config;
use log::{error, info, warn};
use std::io::{self, Read};
use std::os::fd::{FromRawFd, RawFd};
use std::os::unix::net::UnixStream;
use std::sync::Once;
use std::time::Duration;

const LOG_TAG: &str = "TermuxXWayland";
const LOG_PREVIEW_BYTES: usize = 64;
static LOGGER: Once = Once::new();

fn init_logger() {
    LOGGER.call_once(|| {
        android_logger::init_once(
            Config::default()
                .with_tag(LOG_TAG)
                .with_max_level(log::LevelFilter::Info),
        );
    });
}

/// Duplicate the descriptor before taking Rust ownership.
///
/// # Safety
/// The caller must provide a live descriptor for a connected Unix stream
/// socket and keep the original descriptor open until this function returns.
unsafe fn duplicate_connected_stream(fd: RawFd) -> io::Result<UnixStream> {
    if fd < 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "file descriptor must be non-negative",
        ));
    }

    // dup creates an independent descriptor. Rust owns only this duplicate.
    let duplicate = unsafe { libc::dup(fd) };
    if duplicate < 0 {
        return Err(io::Error::last_os_error());
    }

    // The descriptor returned by dup is transferred to UnixStream exactly once.
    Ok(unsafe { UnixStream::from_raw_fd(duplicate) })
}

fn read_stream(mut stream: UnixStream) -> io::Result<()> {
    stream.set_read_timeout(Some(Duration::from_secs(15)))?;
    let mut buffer = [0_u8; 4096];

    loop {
        match stream.read(&mut buffer) {
            Ok(0) => {
                info!("Peer closed the diagnostic socket");
                return Ok(());
            }
            Ok(count) => {
                // Do not log arbitrary full payloads or possible secrets.
                let preview = buffer[..count.min(LOG_PREVIEW_BYTES)]
                    .iter()
                    .map(|byte| format!("{byte:02x}"))
                    .collect::<Vec<_>>()
                    .join(" ");
                info!("Received {count} bytes; prefix hex: {preview}");
            }
            Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
            Err(error) => return Err(error),
        }
    }
}

/// Kotlin declaration: NativeBridge.nativeReadSocket(fileDescriptor: FileDescriptor): Int
/// Android's FileDescriptor stores its integer handle in the "descriptor" field.
/// Returns 0 on orderly peer close, -1 on failure or timeout.
#[no_mangle]
pub extern "system" fn Java_org_techwithkaushik_termuxxwayland_NativeBridge_nativeReadSocket(
    raw_env: *mut jni::sys::JNIEnv,
    _receiver: jni::sys::jobject,
    file_descriptor: jni::sys::jobject,
) -> jni::sys::jint {
    init_logger();

    let fd = (|| -> Result<jni::sys::jint, jni::errors::Error> {
        let mut env = unsafe { jni::JNIEnv::from_raw(raw_env)? };
        let field = env.get_field(
            unsafe { jni::objects::JObject::from_raw(file_descriptor) },
            "descriptor",
            "I",
        )?;
        Ok(field.i()?)
    })();
    let fd = match fd {
        Ok(fd) => fd,
        Err(error) => {
            error!("Unable to access Android FileDescriptor: {error}");
            return -1;
        }
    };

    // Kotlin must pass a live, connected AF_UNIX/SOCK_STREAM descriptor.
    let stream = match unsafe { duplicate_connected_stream(fd) } {
        Ok(stream) => stream,
        Err(error) => {
            error!("Unable to duplicate socket descriptor: {error}");
            return -1;
        }
    };

    match read_stream(stream) {
        Ok(()) => 0,
        Err(error) => {
            warn!("Native socket reader stopped: {error}");
            -1
        }
    }
}

/// Attempts a single AF_UNIX filesystem-socket connect and returns 0 or -errno.
/// This is a permission/path diagnostic only; it sends no application payload.
#[no_mangle]
pub extern "system" fn Java_org_techwithkaushik_termuxxwayland_NativeBridge_nativeProbeUnixSocket(
    raw_env: *mut jni::sys::JNIEnv,
    _receiver: jni::sys::jobject,
    raw_path: jni::sys::jstring,
) -> jni::sys::jint {
    init_logger();

    let path_result = (|| -> Result<String, jni::errors::Error> {
        let mut env = unsafe { jni::JNIEnv::from_raw(raw_env)? };
        let path = unsafe { jni::objects::JString::from_raw(raw_path) };
        let java_path = env.get_string(&path)?;
        let owned_path = java_path.to_string_lossy().into_owned();
        Ok(owned_path)
    })();
    let path = match path_result {
        Ok(path) => path,
        Err(error) => {
            error!("Unable to read filesystem socket path: {error}");
            return -(libc::EINVAL as jni::sys::jint);
        }
    };

    use std::os::unix::ffi::OsStrExt;
    let path_bytes = std::ffi::OsStr::new(&path).as_bytes();
    let mut address: libc::sockaddr_un = unsafe { std::mem::zeroed() };
    if path_bytes.is_empty()
        || path_bytes[0] != b'/'
        || path_bytes.contains(&0)
        || path_bytes.len() >= address.sun_path.len()
    {
        error!("Filesystem socket path is invalid or too long");
        return -(libc::ENAMETOOLONG as jni::sys::jint);
    }
    address.sun_family = libc::AF_UNIX as libc::sa_family_t;
    unsafe {
        std::ptr::copy_nonoverlapping(
            path_bytes.as_ptr(),
            address.sun_path.as_mut_ptr().cast::<u8>(),
            path_bytes.len(),
        );
    }
    let address_len =
        (std::mem::size_of::<libc::sa_family_t>() + path_bytes.len() + 1) as libc::socklen_t;
    let fd = unsafe { libc::socket(libc::AF_UNIX, libc::SOCK_STREAM | libc::SOCK_CLOEXEC, 0) };
    if fd < 0 {
        let errno = std::io::Error::last_os_error()
            .raw_os_error()
            .unwrap_or(libc::EIO);
        error!("Unable to create Unix socket; errno={errno}");
        return -errno;
    }

    let result = unsafe {
        libc::connect(
            fd,
            (&address as *const libc::sockaddr_un).cast::<libc::sockaddr>(),
            address_len,
        )
    };
    let errno = if result == 0 {
        info!("Filesystem Unix socket connect succeeded: {path}");
        0
    } else {
        let errno = std::io::Error::last_os_error()
            .raw_os_error()
            .unwrap_or(libc::EIO);
        warn!("Filesystem Unix socket connect failed for {path}; errno={errno}");
        -errno
    };
    unsafe { libc::close(fd) };
    errno
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_negative_descriptor() {
        let error = unsafe { duplicate_connected_stream(-1) }.unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
    }

    #[test]
    fn reads_connected_unix_stream_until_peer_closes() {
        use std::os::unix::net::UnixStream as Pair;
        let (writer, reader) = Pair::pair().unwrap();
        writer.shutdown(std::net::Shutdown::Write).unwrap();
        assert!(read_stream(reader).is_ok());
    }
}
