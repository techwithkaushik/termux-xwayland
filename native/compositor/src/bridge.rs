//! Authenticated loopback smoke-test server.
//!
//! This is a bridge handshake test endpoint, not a Wayland compositor or frame
//! server. The session token is supplied through an environment variable and
//! is never printed.

use crate::protocol::{Message, MessageKind};
use std::env;
use std::io;
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::time::Duration;

const DEFAULT_PORT: u16 = 38765;
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

fn constant_time_eq(left: &[u8], right: &[u8]) -> bool {
    if left.len() != right.len() {
        return false;
    }
    left.iter()
        .zip(right)
        .fold(0_u8, |diff, (a, b)| diff | (a ^ b))
        == 0
}

fn handle_client(mut stream: TcpStream, expected_token: &[u8; 32]) -> io::Result<()> {
    stream.set_read_timeout(Some(Duration::from_secs(15)))?;
    stream.set_write_timeout(Some(Duration::from_secs(5)))?;

    let hello = Message::read_from(&mut stream)?;
    if hello.kind != MessageKind::Hello || !constant_time_eq(&hello.payload, expected_token) {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "bridge authentication rejected",
        ));
    }

    Message::new(MessageKind::Welcome, Vec::new())?.write_to(&mut stream)?;
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
                    "message not supported by handshake-only server",
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

    let port = env::var("TERMUX_XWAYLAND_PORT")
        .ok()
        .map(|value| value.parse::<u16>())
        .transpose()
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "invalid port"))?
        .unwrap_or(DEFAULT_PORT);
    let address = SocketAddr::from(([127, 0, 0, 1], port));
    let listener = TcpListener::bind(address)?;
    let bound_address = listener.local_addr()?;
    println!("Termux XWayland bridge listening on {bound_address}");
    println!("Enter the port and session token in the Android app; token is not printed.");

    // One client at a time in this smoke-test implementation.
    for incoming in listener.incoming() {
        match incoming {
            Ok(stream) => {
                let peer = stream.peer_addr().ok();
                if let Err(error) = handle_client(stream, &token) {
                    log::warn!("Bridge client rejected or disconnected ({peer:?}): {error}");
                }
            }
            Err(error) => log::warn!("Bridge accept failed: {error}"),
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

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
}
