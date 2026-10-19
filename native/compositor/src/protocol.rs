//! Versioned, bounded frame for the local Android ↔ Termux bridge.
//!
//! This module defines framing only. It does not open a socket or claim that a
//! session is authenticated. Authentication and transport policy are separate
//! requirements documented in docs/bridge-protocol.md.

use std::io::{self, Read, Write};

pub const MAGIC: [u8; 4] = *b"TXWB";
pub const VERSION: u8 = 1;
pub const HEADER_LEN: usize = 12;
pub const MAX_PAYLOAD_LEN: usize = 16 * 1024 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum MessageKind {
    Hello = 1,
    Welcome = 2,
    Frame = 3,
    Input = 4,
    Resize = 5,
    Clipboard = 6,
    Ping = 7,
    Pong = 8,
    Close = 9,
}

impl TryFrom<u8> for MessageKind {
    type Error = io::Error;

    fn try_from(value: u8) -> Result<Self, Self::Error> {
        match value {
            1 => Ok(Self::Hello),
            2 => Ok(Self::Welcome),
            3 => Ok(Self::Frame),
            4 => Ok(Self::Input),
            5 => Ok(Self::Resize),
            6 => Ok(Self::Clipboard),
            7 => Ok(Self::Ping),
            8 => Ok(Self::Pong),
            9 => Ok(Self::Close),
            _ => Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "unknown message kind",
            )),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Message {
    pub kind: MessageKind,
    pub payload: Vec<u8>,
}

impl Message {
    pub fn new(kind: MessageKind, payload: Vec<u8>) -> io::Result<Self> {
        if payload.len() > MAX_PAYLOAD_LEN {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "payload too large",
            ));
        }
        Ok(Self { kind, payload })
    }

    pub fn write_to<W: Write>(&self, mut writer: W) -> io::Result<()> {
        if self.payload.len() > MAX_PAYLOAD_LEN {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "payload too large",
            ));
        }
        writer.write_all(&MAGIC)?;
        writer.write_all(&[VERSION, self.kind as u8, 0, 0])?;
        writer.write_all(&(self.payload.len() as u32).to_be_bytes())?;
        writer.write_all(&self.payload)
    }

    pub fn read_from<R: Read>(mut reader: R) -> io::Result<Self> {
        let mut header = [0_u8; HEADER_LEN];
        reader.read_exact(&mut header)?;
        if header[0..4] != MAGIC {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "invalid bridge magic",
            ));
        }
        if header[4] != VERSION {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "unsupported bridge version",
            ));
        }
        if header[6] != 0 || header[7] != 0 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "reserved header bits must be zero",
            ));
        }
        let kind = MessageKind::try_from(header[5])?;
        let payload_len =
            u32::from_be_bytes([header[8], header[9], header[10], header[11]]) as usize;
        if payload_len > MAX_PAYLOAD_LEN {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "payload exceeds limit",
            ));
        }
        let mut payload = vec![0; payload_len];
        reader.read_exact(&mut payload)?;
        Ok(Self { kind, payload })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips_empty_and_nonempty_payloads() {
        for payload in [Vec::new(), b"hello".to_vec(), vec![42; 4096]] {
            let message = Message::new(MessageKind::Hello, payload).unwrap();
            let mut encoded = Vec::new();
            message.write_to(&mut encoded).unwrap();
            assert_eq!(Message::read_from(encoded.as_slice()).unwrap(), message);
        }
    }

    #[test]
    fn rejects_bad_magic() {
        let mut bytes = vec![0; HEADER_LEN];
        bytes[4] = VERSION;
        bytes[5] = MessageKind::Ping as u8;
        assert_eq!(
            Message::read_from(bytes.as_slice()).unwrap_err().kind(),
            io::ErrorKind::InvalidData
        );
    }

    #[test]
    fn rejects_unknown_message_kind() {
        let mut bytes = Vec::from(MAGIC);
        bytes.extend_from_slice(&[VERSION, 255, 0, 0, 0, 0, 0, 0]);
        assert_eq!(
            Message::read_from(bytes.as_slice()).unwrap_err().kind(),
            io::ErrorKind::InvalidData
        );
    }

    #[test]
    fn rejects_unsupported_version() {
        let mut bytes = Vec::from(MAGIC);
        bytes.extend_from_slice(&[VERSION + 1, MessageKind::Ping as u8, 0, 0, 0, 0, 0, 0]);
        assert_eq!(
            Message::read_from(bytes.as_slice()).unwrap_err().kind(),
            io::ErrorKind::InvalidData
        );
    }

    #[test]
    fn rejects_payload_length_over_limit_before_allocating() {
        let mut bytes = Vec::from(MAGIC);
        bytes.extend_from_slice(&[VERSION, MessageKind::Frame as u8, 0, 0]);
        bytes.extend_from_slice(&((MAX_PAYLOAD_LEN as u32) + 1).to_be_bytes());
        assert_eq!(
            Message::read_from(bytes.as_slice()).unwrap_err().kind(),
            io::ErrorKind::InvalidData
        );
    }

    #[test]
    fn rejects_nonzero_reserved_bits() {
        let mut bytes = Vec::from(MAGIC);
        bytes.extend_from_slice(&[VERSION, MessageKind::Ping as u8, 1, 0, 0, 0, 0, 0]);
        assert_eq!(
            Message::read_from(bytes.as_slice()).unwrap_err().kind(),
            io::ErrorKind::InvalidData
        );
    }
}
