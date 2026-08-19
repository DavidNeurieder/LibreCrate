use std::collections::VecDeque;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

#[cfg(not(target_os = "linux"))]
pub use stub::*;

#[cfg(not(target_os = "linux"))]
mod stub {
    use super::*;

    pub struct Dnd;

    impl Dnd {
        pub fn new() -> Self {
            Self
        }

        pub fn start(&self, _window_xid: u32) {}

        pub fn pending(&self) -> Arc<Mutex<VecDeque<PathBuf>>> {
            Arc::new(Mutex::new(VecDeque::new()))
        }
    }
}

#[cfg(target_os = "linux")]
pub use linux::*;

#[cfg(target_os = "linux")]
mod linux {
    use super::*;
    use x11rb::connection::Connection;
    use x11rb::protocol::xproto::{
        Atom, AtomEnum, ChangeWindowAttributesAux, ConnectionExt, EventMask, PropMode,
    };
    use x11rb::rust_connection::RustConnection;

    pub struct Dnd {
        pending: Arc<Mutex<VecDeque<PathBuf>>>,
    }

    impl Dnd {
        pub fn new() -> Self {
            Self {
                pending: Arc::new(Mutex::new(VecDeque::new())),
            }
        }

        pub fn pending(&self) -> Arc<Mutex<VecDeque<PathBuf>>> {
            self.pending.clone()
        }

        pub fn start(&self, window_xid: u32) {
            let pending = self.pending.clone();
            std::thread::Builder::new()
                .name("x11-xdnd".into())
                .spawn(move || {
                    if let Err(e) = xdnd_event_loop(window_xid, pending) {
                        tracing::error!("Xdnd thread exited: {e}");
                    }
                })
                .expect("spawn Xdnd thread");
        }
    }

    fn xdnd_event_loop(
        our_window: u32,
        pending: Arc<Mutex<VecDeque<PathBuf>>>,
    ) -> Result<(), Box<dyn std::error::Error>> {
        let (conn, _screen_num) = x11rb::connect(None)?;

        // Intern atoms for the Xdnd protocol
        let xdnd_aware = intern_atom(&conn, b"XdndAware")?;
        let xdnd_enter = intern_atom(&conn, b"XdndEnter")?;
        let xdnd_position = intern_atom(&conn, b"XdndPosition")?;
        let xdnd_leave = intern_atom(&conn, b"XdndLeave")?;
        let xdnd_drop = intern_atom(&conn, b"XdndDrop")?;
        let xdnd_status = intern_atom(&conn, b"XdndStatus")?;
        let xdnd_finished = intern_atom(&conn, b"XdndFinished")?;
        let xdnd_selection = intern_atom(&conn, b"XdndSelection")?;
        let text_uri_list = intern_atom(&conn, b"text/uri-list")?;

        // Register as Xdnd-aware (version 5)
        let version_data = 5u32.to_le_bytes();
        conn.change_property(
            PropMode::REPLACE,
            our_window,
            xdnd_aware,
            AtomEnum::ATOM,
            32,
            1,
            &version_data,
        )?;

        // Select for events on our window so we receive ClientMessage/SelectionNotify
        conn.change_window_attributes(
            our_window,
            &ChangeWindowAttributesAux::new()
                .event_mask(EventMask::SUBSTRUCTURE_NOTIFY),
        )?;

        conn.flush()?;

        loop {
            let (raw, _seq) = conn.wait_for_raw_event_with_sequence()?;
            if raw.is_empty() {
                continue;
            }

            let response_type = raw[0] & 0x7f;

            match response_type {
                33 => {
                    // ClientMessage
                    handle_client_message(
                        &conn,
                        &raw,
                        &pending,
                        our_window,
                        xdnd_enter,
                        xdnd_position,
                        xdnd_leave,
                        xdnd_drop,
                        xdnd_status,
                        xdnd_finished,
                        xdnd_selection,
                        text_uri_list,
                    );
                }
                31 => {
                    // SelectionNotify
                    handle_selection_notify(
                        &conn,
                        &raw,
                        &pending,
                        our_window,
                        xdnd_finished,
                    );
                }
                _ => {}
            }
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn handle_client_message(
        conn: &RustConnection,
        raw: &[u8],
        _pending: &Arc<Mutex<VecDeque<PathBuf>>>,
        our_window: u32,
        xdnd_enter: Atom,
        xdnd_position: Atom,
        xdnd_leave: Atom,
        xdnd_drop: Atom,
        xdnd_status: Atom,
        _xdnd_finished: Atom,
        xdnd_selection: Atom,
        text_uri_list: Atom,
    ) {
        if raw.len() < 32 {
            return;
        }

        let msg_type = u32::from_le_bytes(raw[8..12].try_into().unwrap());
        let data: [u8; 20] = match raw[12..32].try_into() {
            Ok(d) => d,
            Err(_) => return,
        };

        if msg_type == xdnd_enter {
            // Drag entered our window – no action needed, just note it
        } else if msg_type == xdnd_position {
            let source_window = u32::from_le_bytes(data[4..8].try_into().unwrap());
            send_xdnd_status(conn, source_window, our_window, xdnd_status, 1);
        } else if msg_type == xdnd_leave {
            // Drag left our window – nothing to do
        } else if msg_type == xdnd_drop {
            // Request the URI list via ConvertSelection
            if let Err(e) = conn.convert_selection(
                our_window,
                xdnd_selection,
                text_uri_list,
                xdnd_selection,
                0u32,
            ) {
                tracing::error!("convert_selection failed: {e}");
            }
            let _ = conn.flush();
        }
    }

    fn handle_selection_notify(
        conn: &RustConnection,
        raw: &[u8],
        pending: &Arc<Mutex<VecDeque<PathBuf>>>,
        our_window: u32,
        xdnd_finished: Atom,
    ) {
        // SelectionNotify format:
        //   bytes[0]   = response_type (31)
        //   bytes[1]   = unused
        //   bytes[2-3] = sequence
        //   bytes[4-7] = time
        //   bytes[8-11]= requestor
        //   bytes[12-15]= selection
        //   bytes[16-19]= target
        //   bytes[20-23]= property (None if 0)
        if raw.len() < 24 {
            return;
        }

        let requestor = u32::from_le_bytes(raw[8..12].try_into().unwrap());
        let property = u32::from_le_bytes(raw[20..24].try_into().unwrap());

        if requestor != our_window || property == 0 {
            return;
        }

        // Read the URI list from the property
        let reply = match conn
            .get_property(true, our_window, property, AtomEnum::ANY, 0, 0xFFFF)
        {
            Ok(cookie) => match cookie.reply() {
                Ok(reply) => Some(reply),
                Err(e) => {
                    tracing::error!("get_property reply failed: {e}");
                    None
                }
            },
            Err(e) => {
                tracing::error!("get_property request failed: {e}");
                None
            }
        };

        if let Some(ref reply) = reply {
            if reply.format == 8 && reply.value_len > 0 {
                let uris = String::from_utf8_lossy(&reply.value);
                let paths: Vec<PathBuf> = uris
                    .lines()
                    .filter_map(|line| {
                        let line = line.trim();
                        if line.is_empty() || line.starts_with('#') {
                            return None;
                        }
                        Some(decode_file_uri(line))
                    })
                    .collect();

                if !paths.is_empty() {
                    pending.lock().unwrap().extend(paths);
                }
            }
        }

        // Send XdndFinished to signal we're done
        send_xdnd_finished(conn, our_window, our_window, xdnd_finished, 1);

        let _ = conn.flush();
    }

    fn send_xdnd_status(
        conn: &RustConnection,
        dest_window: u32,
        our_window: u32,
        xdnd_status: Atom,
        accept: u32,
    ) {
        send_client_message(
            conn,
            dest_window,
            xdnd_status,
            32,
            &[our_window, accept, 0, 0, 0],
        );
    }

    fn send_xdnd_finished(
        conn: &RustConnection,
        dest_window: u32,
        our_window: u32,
        xdnd_finished: Atom,
        accept: u32,
    ) {
        send_client_message(
            conn,
            dest_window,
            xdnd_finished,
            32,
            &[our_window, accept, 0, 0, 0],
        );
    }

    fn send_client_message(
        conn: &RustConnection,
        dest_window: u32,
        message_type: Atom,
        format: u8,
        data: &[u32; 5],
    ) {
        let mut event = [0u8; 32];
        event[0] = 33; // ClientMessage
        event[1] = format;
        // sequence number (bytes 2-3) = 0 for SendEvent
        event[4..8].copy_from_slice(&dest_window.to_le_bytes());
        event[8..12].copy_from_slice(&message_type.to_le_bytes());
        for (i, val) in data.iter().enumerate() {
            event[12 + i * 4..16 + i * 4].copy_from_slice(&val.to_le_bytes());
        }

        if let Err(e) = conn.send_event(
            false,
            dest_window,
            EventMask::SUBSTRUCTURE_NOTIFY,
            event,
        ) {
            tracing::error!("send_event failed: {e}");
        }
    }

    /// Decode a `file://` URI to a PathBuf, handling percent-encoding.
    fn decode_file_uri(uri: &str) -> PathBuf {
        let path = uri.strip_prefix("file://").unwrap_or(uri);
        // Handle file://localhost/path -> /path
        let path = if let Some(rest) = path.strip_prefix("localhost/") {
            rest
        } else {
            path
        };

        let mut bytes = Vec::with_capacity(path.len());
        let mut iter = path.bytes();
        while let Some(b) = iter.next() {
            if b == b'%' {
                let hi = iter.next().and_then(hex_val);
                let lo = iter.next().and_then(hex_val);
                if let (Some(h), Some(l)) = (hi, lo) {
                    bytes.push((h << 4) | l);
                } else {
                    // Invalid percent encoding; push literal bytes
                    bytes.push(b);
                    if let Some(c) = hi {
                        bytes.push(c);
                    }
                    if let Some(c) = lo {
                        bytes.push(c);
                    }
                }
            } else if b == b'+' {
                bytes.push(b' ');
            } else {
                bytes.push(b);
            }
        }
        PathBuf::from(String::from_utf8_lossy(&bytes).as_ref())
    }

    fn hex_val(b: u8) -> Option<u8> {
        match b {
            b'0'..=b'9' => Some(b - b'0'),
            b'a'..=b'f' => Some(b - b'a' + 10),
            b'A'..=b'F' => Some(b - b'A' + 10),
            _ => None,
        }
    }

    fn intern_atom(
        conn: &RustConnection,
        name: &[u8],
    ) -> Result<Atom, Box<dyn std::error::Error>> {
        Ok(conn.intern_atom(false, name)?.reply()?.atom)
    }
}
