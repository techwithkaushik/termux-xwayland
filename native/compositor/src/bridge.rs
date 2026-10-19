//! Authenticated abstract Unix-domain socket smoke-test server.
//!
//! This is a transport/rendering test endpoint, not a Wayland compositor.
//! The socket is local IPC (AF_UNIX abstract namespace), not TCP/IP.

use crate::protocol::{Message, MessageKind};
use std::env;
use std::io::{self, Read, Write};
use std::mem;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::os::unix::net::UnixStream;
use std::time::Duration;

const SOCKET_PREFIX: &str = "termux_xwayland_";
const TOKEN_ENV: &str = "TERMUX_XWAYLAND_TOKEN_HEX";

fn decode_token(value: &str) -> io::Result<[u8; 32]> {
    if value.len() != 64 || !value.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "token must be exactly 64 hexadecimal characters",
        ));
    }
    let mut token = [0_u8; 32];
    for (i, slot) in token.iter_mut().enumerate() {
        let start = i * 2;
        *slot = u8::from_str_radix(&value[start..start + 2], 16).map_err(|_| {
            io::Error::new(io::ErrorKind::InvalidInput, "invalid hexadecimal token")
        })?;
    }
    Ok(token)
}

fn socket_name_for_token(token: &[u8; 32]) -> String {
    let fingerprint = token[..8]
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect::<String>();
    format!("{SOCKET_PREFIX}{fingerprint}")
}

fn constant_time_eq(left: &[u8], right: &[u8]) -> bool {
    if left.len() != right.len() {
        return false;
    }
    left.iter()
        .zip(right)
        .fold(0_u8, |diff, (a, b)| diff | (a ^ b))
        == 0
}

trait TimedStream: Read + Write {
    fn set_read_timeout(&self, timeout: Option<Duration>) -> io::Result<()>;
    fn set_write_timeout(&self, timeout: Option<Duration>) -> io::Result<()>;
}

impl TimedStream for UnixStream {
    fn set_read_timeout(&self, timeout: Option<Duration>) -> io::Result<()> {
        UnixStream::set_read_timeout(self, timeout)
    }

    fn set_write_timeout(&self, timeout: Option<Duration>) -> io::Result<()> {
        UnixStream::set_write_timeout(self, timeout)
    }
}

fn abstract_address(name: &str) -> io::Result<(libc::sockaddr_un, libc::socklen_t)> {
    let bytes = name.as_bytes();
    // Abstract namespace names start with NUL and have no trailing NUL.
    let capacity = unsafe { mem::zeroed::<libc::sockaddr_un>() }.sun_path.len();
    if bytes.is_empty() || bytes.contains(&0) || bytes.len() + 1 > capacity {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "invalid abstract socket name",
        ));
    }

    let mut address: libc::sockaddr_un = unsafe { mem::zeroed() };
    address.sun_family = libc::AF_UNIX as libc::sa_family_t;
    for (index, byte) in bytes.iter().enumerate() {
        address.sun_path[index + 1] = *byte as libc::c_char;
    }
    let length =
        (mem::offset_of!(libc::sockaddr_un, sun_path) + 1 + bytes.len()) as libc::socklen_t;
    Ok((address, length))
}

fn bind_abstract_listener(name: &str) -> io::Result<OwnedFd> {
    let (address, address_len) = abstract_address(name)?;
    let fd = unsafe { libc::socket(libc::AF_UNIX, libc::SOCK_STREAM | libc::SOCK_CLOEXEC, 0) };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    let listener = unsafe { OwnedFd::from_raw_fd(fd) };
    let result = unsafe {
        libc::bind(
            listener.as_raw_fd(),
            &address as *const libc::sockaddr_un as *const libc::sockaddr,
            address_len,
        )
    };
    if result < 0 {
        return Err(io::Error::last_os_error());
    }
    if unsafe { libc::listen(listener.as_raw_fd(), 1) } < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(listener)
}

fn write_test_frame<W: Write>(stream: &mut W) -> io::Result<()> {
    // Small deterministic RGBA test pattern, not a compositor-generated desktop.
    const WIDTH: u16 = 320;
    const HEIGHT: u16 = 180;
    let mut payload = Vec::with_capacity(8 + WIDTH as usize * HEIGHT as usize * 4);
    payload.extend_from_slice(&WIDTH.to_be_bytes());
    payload.extend_from_slice(&HEIGHT.to_be_bytes());
    payload.extend_from_slice(&[1, 0, 0, 0]); // RGBA8888, reserved bytes are zero.
    for y in 0..HEIGHT {
        for x in 0..WIDTH {
            let r = ((x as u32 * 255) / (WIDTH as u32 - 1)) as u8;
            let g = ((y as u32 * 255) / (HEIGHT as u32 - 1)) as u8;
            let b = if ((x / 20) + (y / 20)) % 2 == 0 {
                220
            } else {
                48
            };
            payload.extend_from_slice(&[r, g, b, 255]);
        }
    }
    Message::new(MessageKind::Frame, payload)?.write_to(stream)
}

fn handle_client<S: TimedStream>(mut stream: S, expected_token: &[u8; 32]) -> io::Result<()> {
    stream.set_read_timeout(Some(Duration::from_secs(15)))?;
    stream.set_write_timeout(Some(Duration::from_secs(5)))?;

    let hello = Message::read_from(&mut stream)?;
    if hello.kind != MessageKind::Hello || !constant_time_eq(&hello.payload, expected_token) {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "bridge authentication rejected",
        ));
    }

    // Report the Termux process effective UID so the Android app can verify
    // that this connection really crosses Android application UID boundaries.
    let termux_uid = unsafe { libc::geteuid() };
    Message::new(MessageKind::Welcome, termux_uid.to_be_bytes().to_vec())?
        .write_to(&mut stream)?;
    write_test_frame(&mut stream)?;
    // The handshake timeout must not silently expire an otherwise idle session.
    stream.set_read_timeout(None)?;
    log::info!("Android bridge client authenticated");

    loop {
        match Message::read_from(&mut stream) {
            Ok(message) if message.kind == MessageKind::Ping && message.payload.is_empty() => {
                Message::new(MessageKind::Pong, Vec::new())?.write_to(&mut stream)?;
            }
            Ok(message) if message.kind == MessageKind::Close && message.payload.is_empty() => {
                let _ = Message::new(MessageKind::Close, Vec::new())?.write_to(&mut stream);
                log::info!("Android bridge client disconnected");
                return Ok(());
            }
            Ok(_) => {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "message not supported by smoke-test server",
                ));
            }
            Err(error)
                if error.kind() == io::ErrorKind::UnexpectedEof
                    || error.kind() == io::ErrorKind::ConnectionReset =>
            {
                return Ok(());
            }
            Err(error) => return Err(error),
        }
    }
}

pub fn run() -> io::Result<()> {
    let raw_token = env::var(TOKEN_ENV).map_err(|_| {
        io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("{TOKEN_ENV} is required; generate a fresh per-session token"),
        )
    })?;
    let token = decode_token(&raw_token)?;
    drop(raw_token);

    let socket_name = socket_name_for_token(&token);
    let listener = bind_abstract_listener(&socket_name)?;
    println!("Termux XWayland bridge listening on abstract AF_UNIX socket @{socket_name}");
    println!("Open the Android app and enter the fresh session token; token is not printed.");

    // One client at a time in this smoke-test implementation.
    loop {
        let fd = unsafe {
            libc::accept(
                listener.as_raw_fd(),
                std::ptr::null_mut(),
                std::ptr::null_mut(),
            )
        };
        if fd < 0 {
            let error = io::Error::last_os_error();
            if error.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            log::warn!("Bridge accept failed: {error}");
            continue;
        }
        let stream = unsafe { UnixStream::from_raw_fd(fd) };
        if let Err(error) = handle_client(stream, &token) {
            log::warn!("Bridge client rejected or disconnected: {error}");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn welcome_uid_payload_is_a_big_endian_u32() {
        let uid = 10_123_u32;
        let payload = uid.to_be_bytes();
        assert_eq!(u32::from_be_bytes(payload), uid);
        assert_eq!(payload.len(), 4);
    }

    #[test]
    fn decodes_32_byte_hex_token() {
        assert_eq!(decode_token(&"ab".repeat(32)).unwrap(), [0xab; 32]);
    }

    #[test]
    fn rejects_wrong_token_lengths_and_non_hex() {
        assert!(decode_token("abcd").is_err());
        assert!(decode_token(&"zz".repeat(32)).is_err());
    }

    #[test]
    fn token_comparison_requires_exact_match() {
        assert!(constant_time_eq(&[1, 2, 3], &[1, 2, 3]));
        assert!(!constant_time_eq(&[1, 2, 3], &[1, 2, 4]));
        assert!(!constant_time_eq(&[1, 2], &[1, 2, 3]));
    }

    #[test]
    fn socket_name_is_derived_from_session_token_without_exposing_full_token() {
        let token = [0xab; 32];
        assert_eq!(
            socket_name_for_token(&token),
            "termux_xwayland_abababababababab"
        );
        assert!(!socket_name_for_token(&token).contains(&"ab".repeat(32)));
    }

    #[test]
    fn abstract_socket_address_uses_linux_abstract_namespace() {
        let test_name = format!("{SOCKET_PREFIX}0123456789abcdef");
        let (address, length) = abstract_address(&test_name).unwrap();
        assert_eq!(address.sun_family as i32, libc::AF_UNIX);
        assert_eq!(address.sun_path[0], 0);
        assert_eq!(
            &address.sun_path[1..1 + test_name.len()]
                .iter()
                .map(|value| *value as u8)
                .collect::<Vec<_>>(),
            test_name.as_bytes()
        );
        assert_eq!(
            length as usize,
            mem::offset_of!(libc::sockaddr_un, sun_path) + 1 + test_name.len()
        );
    }
}
