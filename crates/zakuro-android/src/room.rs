//! What the room settings mean and what a failed room says, apart from the sockets in
//! zakuro_core::room so the host tests reach it.
use crate::settings::Advanced;
use zakuro_core::room::DEFAULT_PORT;

/// the name the player goes by in the room, the console's too, as on the desktop.
pub fn nickname(settings: &Advanced) -> String {
    match settings.room_nickname.trim() {
        "" => "Player".into(),
        name => name.chars().take(10).collect(),
    }
}

/// the hosted room's name, "<nickname>'s room" when none is set.
pub fn name(settings: &Advanced) -> String {
    match settings.room_name.trim() {
        "" => format!("{}'s room", nickname(settings)),
        name => name.to_owned(),
    }
}

/// where to join: Room address as host:port, the default port when it has none.
pub fn join_address(settings: &Advanced) -> Result<String, String> {
    match settings.room_address.trim() {
        "" => Err("Room not joined: set the host's address in Settings → Advanced → Room address.".into()),
        address if address.contains(':') => Ok(address.to_owned()),
        address => Ok(format!("{address}:{DEFAULT_PORT}")),
    }
}

/// the port to host on: Room address as a port (or anything ending in :port), the default when empty.
pub fn host_port(settings: &Advanced) -> Result<u16, String> {
    let address = settings.room_address.trim();
    if address.is_empty() {
        return Ok(DEFAULT_PORT);
    }
    // Android lets an app listen only on 1024 and up
    match address.rsplit(':').next().and_then(|port| port.trim().parse::<u16>().ok()) {
        Some(port) if port >= 1024 => Ok(port),
        _ => Err(format!("Room not opened: when hosting, Room address is a port from 1024 to 65535, or empty for {DEFAULT_PORT}.")),
    }
}

/// why the room could not be opened on port, in words a player can act on.
pub fn host_error(port: u16, error: &std::io::Error) -> String {
    match error.kind() {
        std::io::ErrorKind::AddrInUse => format!("Room not opened: port {port} is in use by another app. Set another port in Room address."),
        std::io::ErrorKind::PermissionDenied => format!("Room not opened: port {port} is not allowed. Is the network permission there (nightly build)?"),
        _ => format!("Room not opened on port {port}: {error}."),
    }
}

/// zakuro_core::room's join error, with what to change.
pub fn join_error(error: &str) -> String {
    let hint = if error.contains("has that nickname") {
        " Set another Nickname in Settings → Advanced."
    } else if error.contains("password is wrong") {
        " Check Room password; the host and guests use the same one."
    } else if error.contains("another version") {
        " Update both to the same version."
    } else if error.contains("could not reach") || error.contains("did not answer") {
        " Check the address, that the room is open, and that both are on the same network."
    } else if error.contains("is not an address") {
        " Use the host's address and port, like 192.168.0.10:24873."
    } else {
        ""
    };
    format!("Room not joined: {error}.{hint}")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn with(json: &str) -> Advanced {
        Advanced::parse(json).unwrap()
    }

    #[test]
    fn names_fall_back_like_the_desktop() {
        assert_eq!(nickname(&with("{}")), "Player");
        assert_eq!(nickname(&with(r#"{"room_nickname":" Abcdefghijkl "}"#)), "Abcdefghij");
        assert_eq!(name(&with(r#"{"room_nickname":"Thor"}"#)), "Thor's room");
        assert_eq!(name(&with(r#"{"room_name":" Den "}"#)), "Den");
    }

    #[test]
    fn addresses_and_ports() {
        assert!(join_address(&with("{}")).unwrap_err().contains("set the host's address"));
        assert_eq!(join_address(&with(r#"{"room_address":" 192.168.0.10 "}"#)).unwrap(), "192.168.0.10:24873");
        assert_eq!(join_address(&with(r#"{"room_address":"pc.lan:5000"}"#)).unwrap(), "pc.lan:5000");
        assert_eq!(host_port(&with("{}")), Ok(DEFAULT_PORT));
        assert_eq!(host_port(&with(r#"{"room_address":"25000"}"#)), Ok(25000));
        assert_eq!(host_port(&with(r#"{"room_address":"0.0.0.0:25001"}"#)), Ok(25001));
        for bad in ["80", "0", "70000", "port"] {
            assert!(host_port(&with(&format!(r#"{{"room_address":"{bad}"}}"#))).is_err(), "{bad}");
        }
    }

    #[test]
    fn errors_say_what_to_change() {
        let in_use = std::io::Error::from(std::io::ErrorKind::AddrInUse);
        assert!(host_error(24873, &in_use).contains("in use by another app"));
        assert!(join_error("the room turned you away, someone in the room has that nickname").ends_with("Set another Nickname in Settings → Advanced."));
        assert!(join_error("the room turned you away, the password is wrong").contains("Check Room password"));
        assert!(join_error("could not reach 10.0.0.2:24873, refused").contains("same network"));
        assert_eq!(join_error("the room turned you away, the room is full"), "Room not joined: the room turned you away, the room is full.");
    }

    /// a real room on this machine: a wrong password is refused with the hint, the right one joins
    #[test]
    fn a_wrong_password_reads_as_a_hint() {
        use zakuro_core::room::{Connection, Server};
        let server = Server::start(0, "test", "secret", 4).unwrap();
        let address = format!("127.0.0.1:{}", server.port());
        let refused = Connection::join(&address, "Thor", "nope").err().unwrap();
        assert!(join_error(&refused).contains("Check Room password"), "{refused}");
        assert!(Connection::join(&address, "Thor", "secret").is_ok());
    }
}
