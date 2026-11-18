//! What the room settings mean and what a failed room says, apart from the sockets in
//! zakuro_core::room so the host tests reach it.
use crate::settings::{Advanced, RoomMode};
use zakuro_core::room::{Connection, Server, DEFAULT_PORT};

/// a room joined (and opened here when hosting), with what to tell the player.
pub type Joined = (Option<Server>, Connection, String);

/// opens and joins the room the settings ask for, as the desktop's host_room and join_room do.
/// Blocks up to the core's connect and answer timeouts (and the address lookup), so the runtime
/// runs it on a thread of its own: the native thread must keep reading input, or Android
/// reports the app as not responding. Off is an error no one shows.
pub fn open(settings: &Advanced) -> Result<Joined, String> {
    let nickname = nickname(settings);
    let (server, address) = match settings.room_mode {
        RoomMode::Off => return Err(String::new()),
        RoomMode::Join => (None, join_address(settings)?),
        RoomMode::Host => {
            let port = host_port(settings)?;
            let server = Server::start(port, &name(settings), &settings.room_password, 16).map_err(|error| host_error(port, &error))?;
            let local = format!("127.0.0.1:{}", server.port());
            (Some(server), local)
        }
    };
    let connection = Connection::join(&address, &nickname, &settings.room_password).map_err(|error| join_error(&error))?;
    let said = match &server {
        Some(server) => {
            // connecting a UDP socket sends nothing; it only picks the interface the LAN route uses
            let ip = std::net::UdpSocket::bind("0.0.0.0:0")
                .and_then(|socket| socket.connect("192.0.2.1:9").and_then(|_| socket.local_addr()))
                .map_or_else(|_| "this device's address".to_owned(), |local| local.ip().to_string());
            format!("Hosting room on {ip}:{}", server.port())
        }
        None => format!("Joined the room {} as {nickname}.", connection.room()),
    };
    Ok((server, connection, said))
}

/// the line the pause menu's room dialog shows: the room's name and who is in it, playing what.
pub fn status(room: Option<(&str, &[zakuro_core::room::Member])>, joining: bool) -> String {
    match room {
        _ if joining => "Joining a room…".into(),
        None => "Not in a room.".into(),
        Some((name, [])) => format!("In the room {name}."),
        Some((name, members)) => {
            let who: Vec<String> = members
                .iter()
                .map(|member| if member.game.is_empty() { member.nickname.clone() } else { format!("{} ({})", member.nickname, member.game) })
                .collect();
            format!("In the room {name}: {}.", who.join(", "))
        }
    }
}

/// open on a thread of its own; the runtime polls the receiver once a frame.
pub fn open_in_background(settings: &Advanced) -> Option<std::sync::mpsc::Receiver<Result<Joined, String>>> {
    if settings.room_mode == RoomMode::Off {
        return None;
    }
    let (send, receive) = std::sync::mpsc::channel();
    let settings = settings.clone();
    // a send to a receiver that was dropped (the player left first) drops the room with it
    std::thread::Builder::new().name("room joining".into()).spawn(move || send.send(open(&settings))).ok()?;
    Some(receive)
}

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

    #[test]
    fn status_names_the_room_and_who_plays_what() {
        use zakuro_core::room::Member;
        let member = |nickname: &str, game: &str| Member { nickname: nickname.into(), mac: [0; 6], game: game.into() };
        assert_eq!(status(None, false), "Not in a room.");
        assert_eq!(status(None, true), "Joining a room…");
        assert_eq!(status(Some(("Den", &[])), false), "In the room Den.");
        assert_eq!(status(Some(("Den", &[member("Thor", "Ultra Moon"), member("Pc", "")])), false), "In the room Den: Thor (Ultra Moon), Pc.");
    }

    /// open() hosts on a free port and joins its own room, and joins another one by address
    #[test]
    fn open_hosts_and_joins_a_real_room() {
        let free = std::net::TcpListener::bind("0.0.0.0:0").unwrap().local_addr().unwrap().port();
        let host = with(&format!(r#"{{"room_mode":"host","room_address":"{free}","room_name":"Den","room_nickname":"Thor"}}"#));
        let (server, connection, said) = open(&host).unwrap();
        let server = server.unwrap();
        assert_eq!((server.port(), connection.room()), (free, "Den"));
        assert!(said.starts_with("Hosting room on ") && said.ends_with(&format!(":{free}")), "{said}");
        let guest = with(&format!(r#"{{"room_mode":"join","room_address":"127.0.0.1:{free}","room_nickname":"Pc"}}"#));
        let (none, _guest, said) = open_in_background(&guest).unwrap().recv().unwrap().unwrap();
        assert!(none.is_none() && said == "Joined the room Den as Pc.", "{said}");
        assert!(open_in_background(&with("{}")).is_none());
        assert!(open(&with(r#"{"room_mode":"join"}"#)).err().unwrap().contains("set the host's address"));
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
