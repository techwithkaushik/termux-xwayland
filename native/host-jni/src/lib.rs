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



/// Validate the bounded RGBA8888 wire payload without allocating a second pixel buffer.
fn validate_rgba_payload(payload: &[u8]) -> Result<(usize, usize, &[u8]), libc::c_int> {
    if payload.len() < 8 {
        return Err(libc::EINVAL);
    }
    let width = u16::from_be_bytes([payload[0], payload[1]]) as usize;
    let height = u16::from_be_bytes([payload[2], payload[3]]) as usize;
    if width == 0 || width > 640 || height == 0 || height > 360
        || payload[4] != 1 || payload[5..8] != [0, 0, 0]
    {
        return Err(libc::EINVAL);
    }
    let pixel_bytes = width.checked_mul(height)
        .and_then(|n| n.checked_mul(4))
        .ok_or(libc::EOVERFLOW)?;
    if payload.len() != 8 + pixel_bytes {
        return Err(libc::EMSGSIZE);
    }
    Ok((width, height, &payload[8..]))
}

#[cfg(target_os = "android")]
#[repr(C)]
struct NativeWindowBuffer {
    width: libc::c_int,
    height: libc::c_int,
    stride: libc::c_int,
    format: libc::c_int,
    bits: *mut std::ffi::c_void,
    reserved: [u32; 6],
}

#[cfg(target_os = "android")]
#[repr(C)]
struct NativeRect {
    left: libc::c_int,
    top: libc::c_int,
    right: libc::c_int,
    bottom: libc::c_int,
}

#[cfg(target_os = "android")]
#[link(name = "android")]
extern "C" {
    fn ANativeWindow_fromSurface(env: *mut jni::sys::JNIEnv, surface: jni::sys::jobject) -> *mut std::ffi::c_void;
    fn ANativeWindow_release(window: *mut std::ffi::c_void);
    fn ANativeWindow_setBuffersGeometry(window: *mut std::ffi::c_void, width: libc::c_int, height: libc::c_int, format: libc::c_int) -> libc::c_int;
    fn ANativeWindow_lock(window: *mut std::ffi::c_void, buffer: *mut NativeWindowBuffer, dirty: *mut NativeRect) -> libc::c_int;
    fn ANativeWindow_unlockAndPost(window: *mut std::ffi::c_void) -> libc::c_int;
}

/// Native Surface presentation for bounded RGBA8888 bridge frames.
/// The Android compositor owns final composition; this copies rows into an
/// ANativeWindow buffer and never constructs a Java Bitmap.
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_org_techwithkaushik_termuxxwayland_NativeBridge_nativeRenderRgba(
    raw_env: *mut jni::sys::JNIEnv,
    _receiver: jni::sys::jobject,
    raw_surface: jni::sys::jobject,
    raw_payload: jni::sys::jbyteArray,
) -> jni::sys::jint {
    init_logger();
    let result = (|| -> Result<(), libc::c_int> {
        let mut env = unsafe { jni::JNIEnv::from_raw(raw_env) }
            .map_err(|_| libc::EINVAL)?;
        let array = unsafe { jni::objects::JByteArray::from_raw(raw_payload) };
        let length = env.get_array_length(&array).map_err(|_| libc::EINVAL)? as usize;
        // 640 x 360 RGBA plus the fixed 8-byte header.
        if !(8..=921_608).contains(&length) {
            return Err(libc::EMSGSIZE);
        }
        let mut signed_bytes = vec![0_i8; length];
        env.get_byte_array_region(&array, 0, &mut signed_bytes)
            .map_err(|_| libc::EINVAL)?;
        let payload: Vec<u8> = signed_bytes.into_iter().map(|byte| byte as u8).collect();
        let (width, height, pixels) = validate_rgba_payload(&payload)?;

        let window = unsafe { ANativeWindow_fromSurface(raw_env, raw_surface) };
        if window.is_null() {
            return Err(libc::ENODEV);
        }
        let release = |window| unsafe { ANativeWindow_release(window) };
        // WINDOW_FORMAT_RGBA_8888 is 1 in Android's native_window.h.
        let geometry = unsafe {
            ANativeWindow_setBuffersGeometry(window, width as i32, height as i32, 1)
        };
        if geometry != 0 {
            release(window);
            return Err(if geometry < 0 { -geometry } else { libc::EIO });
        }
        let mut buffer = NativeWindowBuffer {
            width: 0, height: 0, stride: 0, format: 0,
            bits: std::ptr::null_mut(), reserved: [0; 6],
        };
        let locked = unsafe { ANativeWindow_lock(window, &mut buffer, std::ptr::null_mut()) };
        if locked != 0 {
            release(window);
            return Err(if locked < 0 { -locked } else { libc::EIO });
        }

        let copy_result = (|| -> Result<(), libc::c_int> {
            if buffer.bits.is_null() || buffer.width < width as i32
                || buffer.height < height as i32 || buffer.stride < width as i32
                || buffer.format != 1
            {
                return Err(libc::EINVAL);
            }
            let destination = buffer.bits.cast::<u8>();
            for row in 0..height {
                let source_offset = row * width * 4;
                let destination_offset = row * buffer.stride as usize * 4;
                unsafe {
                    std::ptr::copy_nonoverlapping(
                        pixels.as_ptr().add(source_offset),
                        destination.add(destination_offset),
                        width * 4,
                    );
                }
            }
            Ok(())
        })();
        let posted = unsafe { ANativeWindow_unlockAndPost(window) };
        release(window);
        copy_result?;
        if posted != 0 {
            return Err(if posted < 0 { -posted } else { libc::EIO });
        }
        Ok(())
    })();

    match result {
        Ok(()) => 0,
        Err(errno) => {
            warn!("Native Surface frame presentation failed; errno={errno}");
            -errno
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn validates_rgba_frame_dimensions_and_payload_length() {
        let mut frame = vec![0_u8; 8 + 2 * 3 * 4];
        frame[0..2].copy_from_slice(&2_u16.to_be_bytes());
        frame[2..4].copy_from_slice(&3_u16.to_be_bytes());
        frame[4] = 1;
        let (width, height, pixels) = validate_rgba_payload(&frame).unwrap();
        assert_eq!((width, height, pixels.len()), (2, 3, 24));
        assert_eq!(validate_rgba_payload(&frame[..frame.len() - 1]).unwrap_err(), libc::EMSGSIZE);
    }

    #[test]
    fn rejects_oversized_or_invalid_rgba_frames() {
        let mut frame = vec![0_u8; 8 + 641 * 1 * 4];
        frame[0..2].copy_from_slice(&641_u16.to_be_bytes());
        frame[2..4].copy_from_slice(&1_u16.to_be_bytes());
        frame[4] = 1;
        assert_eq!(validate_rgba_payload(&frame).unwrap_err(), libc::EINVAL);
        assert_eq!(validate_rgba_payload(&[0; 7]).unwrap_err(), libc::EINVAL);
    }

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
