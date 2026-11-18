//! rooms, where consoles on different computers meet for local wireless
//! play. a room is a server each member keeps a TCP connection to. it gives
//! every member a MAC address and passes what one sends on to the member it
//! is for, or to all the others. what goes through is the members' own, the
//! room never looks inside.

use std::collections::VecDeque;
use std::io::{Read, Write};
use std::net::{Shutdown, TcpListener, TcpStream, ToSocketAddrs};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{channel, Sender};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};

pub type Mac = [u8; 6];
/// every member of the room.
pub const BROADCAST: Mac = [0xFF; 6];
/// the port a room listens on unless it is told another.
pub const DEFAULT_PORT: u16 = 24873;
/// rooms and members speaking another version do not meet.
const VERSION: u32 = 1;
/// the largest message, a beacon or a packet with room to spare.
const LARGEST: usize = 64 * 1024;
/// how long joining waits for the room to answer.
const PATIENCE: Duration = Duration::from_secs(5);
/// how long the room waits on a member that takes nothing in before it
/// lets the member go, so one member cannot hold up the others.
const STALL: Duration = Duration::from_secs(2);
/// the longest nickname and game, in bytes, so the list of members a room
/// sends stays well under LARGEST. the apps send at most 10 characters of a
/// nickname, 40 bytes at most.
const NICKNAME_LIMIT: usize = 40;
const GAME_LIMIT: usize = 64;
/// the most a console keeps of what came for it and it did not take yet.
const INBOX_LIMIT: usize = 4 * 1024 * 1024;

/// someone in a room, and the game they play.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Member {
    pub nickname: String,
    pub mac: Mac,
    pub game: String,
}

#[derive(Serialize, Deserialize)]
enum ToRoom {
    Hello { version: u32, nickname: String, password: String },
    /// the game the member plays now, none when it plays nothing.
    Playing(String),
    Packet { to: Mac, body: Vec<u8> },
}

#[derive(Serialize, Deserialize)]
enum FromRoom {
    Welcome { mac: Mac, name: String },
    Refused(String),
    Members(Vec<Member>),
    Packet { from: Mac, body: Vec<u8> },
}

fn write<T: Serialize>(stream: &mut impl Write, message: &T) -> std::io::Result<()> {
    let body = postcard::to_allocvec(message).map_err(std::io::Error::other)?;
    if body.len() > LARGEST {
        return Err(std::io::Error::other("a message too large for a room"));
    }
    let mut framed = Vec::with_capacity(4 + body.len());
    framed.extend_from_slice(&(body.len() as u32).to_le_bytes());
    framed.extend_from_slice(&body);
    stream.write_all(&framed)
}

fn read<T: DeserializeOwned>(stream: &mut impl Read) -> std::io::Result<T> {
    let mut length = [0u8; 4];
    stream.read_exact(&mut length)?;
    let length = u32::from_le_bytes(length) as usize;
    if length > LARGEST {
        return Err(std::io::Error::other("a message too large for a room"));
    }
    let mut body = vec![0; length];
    stream.read_exact(&mut body)?;
    postcard::from_bytes(&body).map_err(std::io::Error::other)
}

/// a room this program holds open for others to join.
pub struct Server {
    port: u16,
    stop: Arc<AtomicBool>,
    seats: Arc<Mutex<Seats>>,
}

struct Seats {
    name: String,
    password: String,
    most: usize,
    /// the members, and the stream each is written to through.
    taken: Vec<(Member, Arc<Mutex<TcpStream>>)>,
    /// the last part of the next MAC address handed out.
    next: u32,
}

impl Seats {
    fn members(&self) -> Vec<Member> {
        self.taken.iter().map(|(member, _)| member.clone()).collect()
    }

    /// tells everyone who is in the room, and again if someone had to go.
    fn announce(&mut self) {
        let members = FromRoom::Members(self.members());
        let before = self.taken.len();
        self.taken.retain(|(_, stream)| give(stream, &members));
        if self.taken.len() < before {
            self.announce();
        }
    }
}

/// writes message to a member, false when the member cannot take it in,
/// its socket then shut and the caller lets it go.
fn give(stream: &Mutex<TcpStream>, message: &FromRoom) -> bool {
    let Ok(mut stream) = stream.lock() else { return false };
    if write(&mut *stream, message).is_err() {
        let _ = stream.shutdown(Shutdown::Both);
        return false;
    }
    true
}

/// a game as the room shows it, without control characters and cut to
/// GAME_LIMIT on a character's boundary.
fn tidy(game: &str) -> String {
    let mut game: String = game.chars().filter(|c| !c.is_control()).collect();
    let mut end = game.len().min(GAME_LIMIT);
    while !game.is_char_boundary(end) {
        end -= 1;
    }
    game.truncate(end);
    game
}

impl Server {
    /// opens a room on port, 0 for any free one, for up to most members.
    pub fn start(port: u16, name: &str, password: &str, most: usize) -> std::io::Result<Server> {
        let listener = TcpListener::bind(("0.0.0.0", port))?;
        let port = listener.local_addr()?.port();
        listener.set_nonblocking(true)?;
        let stop = Arc::new(AtomicBool::new(false));
        let seats = Arc::new(Mutex::new(Seats {
            name: name.to_owned(),
            password: password.to_owned(),
            most: most.max(2),
            taken: Vec::new(),
            next: 1,
        }));
        let (stopping, room) = (stop.clone(), seats.clone());
        std::thread::Builder::new().name("room".into()).spawn(move || {
            while !stopping.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((stream, address)) => {
                        let (stopping, room) = (stopping.clone(), room.clone());
                        let _ = std::thread::Builder::new()
                            .name("room member".into())
                            .spawn(move || seat(stream, address.to_string(), &room, &stopping));
                    }
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => std::thread::sleep(Duration::from_millis(50)),
                    Err(error) => {
                        log::warn!("the room stopped taking members, {error}");
                        break;
                    }
                }
            }
        })?;
        log::info!("room {name} open on port {port}");
        Ok(Server { port, stop, seats })
    }

    pub fn port(&self) -> u16 {
        self.port
    }

    pub fn members(&self) -> Vec<Member> {
        self.seats.lock().map(|seats| seats.members()).unwrap_or_default()
    }
}

impl Drop for Server {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Ok(seats) = self.seats.lock() {
            for (_, stream) in &seats.taken {
                if let Ok(stream) = stream.lock() {
                    let _ = stream.shutdown(Shutdown::Both);
                }
            }
        }
    }
}

/// a member's time in the room, from its hello to its leaving.
fn seat(mut stream: TcpStream, address: String, seats: &Mutex<Seats>, stop: &AtomicBool) {
    // on Windows a stream takes the listener's not blocking, and the member
    // would be gone at the first read with nothing to read yet
    let _ = stream.set_nonblocking(false);
    let _ = stream.set_nodelay(true);
    let _ = stream.set_read_timeout(Some(PATIENCE));
    let Ok(ToRoom::Hello { version, nickname, password }) = read(&mut stream) else { return };
    let _ = stream.set_read_timeout(None);
    let _ = stream.set_write_timeout(Some(STALL));
    let Ok(writer) = stream.try_clone() else { return };
    let writer = Arc::new(Mutex::new(writer));
    let mac = {
        let Ok(mut room) = seats.lock() else { return };
        let refusal = if version != VERSION {
            Some("the room is for another version of Zakuro".to_owned())
        } else if nickname.len() > NICKNAME_LIMIT || nickname.chars().any(char::is_control) {
            Some(format!("the nickname is longer than {NICKNAME_LIMIT} bytes or has control characters"))
        } else if !room.password.is_empty() && password != room.password {
            Some("the password is wrong".to_owned())
        } else if room.taken.len() >= room.most {
            Some("the room is full".to_owned())
        } else if room.taken.iter().any(|(member, _)| member.nickname == nickname) {
            Some("someone in the room has that nickname".to_owned())
        } else {
            None
        };
        if let Some(reason) = refusal {
            let _ = write(&mut stream, &FromRoom::Refused(reason));
            return;
        }
        // locally administered addresses, Z and K, then the member's number
        let number = room.next;
        room.next += 1;
        let mac = [0x02, 0x5A, 0x4B, (number >> 16) as u8, (number >> 8) as u8, number as u8];
        let welcome = FromRoom::Welcome { mac, name: room.name.clone() };
        if write(&mut stream, &welcome).is_err() {
            return;
        }
        room.taken.push((Member { nickname: nickname.clone(), mac, game: String::new() }, writer));
        room.announce();
        mac
    };
    log::info!("{nickname} joined the room from {address}");
    while !stop.load(Ordering::Relaxed) {
        let Ok(message) = read::<ToRoom>(&mut stream) else { break };
        let Ok(mut room) = seats.lock() else { break };
        // a member the room let go is done
        if !room.taken.iter().any(|(member, _)| member.mac == mac) {
            break;
        }
        match message {
            ToRoom::Packet { to, body } => {
                let packet = FromRoom::Packet { from: mac, body };
                let before = room.taken.len();
                room.taken.retain(|(member, stream)| member.mac == mac || (to != BROADCAST && member.mac != to) || give(stream, &packet));
                if room.taken.len() < before {
                    room.announce();
                }
            }
            ToRoom::Playing(game) => {
                if let Some((member, _)) = room.taken.iter_mut().find(|(member, _)| member.mac == mac) {
                    member.game = tidy(&game);
                }
                room.announce();
            }
            ToRoom::Hello { .. } => {}
        }
    }
    if let Ok(mut room) = seats.lock() {
        room.taken.retain(|(member, _)| member.mac != mac);
        room.announce();
    }
    log::info!("{nickname} left the room");
}

/// what the connection to a room and the console's side of it share.
struct Shared {
    mac: Mac,
    room: String,
    open: AtomicBool,
    members: Mutex<Vec<Member>>,
    /// what came for the console, from whom, oldest first.
    inbox: Mutex<VecDeque<(Mac, Vec<u8>)>>,
}

/// this program in a room.
pub struct Connection {
    stream: TcpStream,
    outbox: Sender<ToRoom>,
    shared: Arc<Shared>,
}

impl Connection {
    /// joins the room at address, host:port, under nickname.
    pub fn join(address: &str, nickname: &str, password: &str) -> Result<Connection, String> {
        let target = address
            .to_socket_addrs()
            .map_err(|error| format!("{address} is not an address, {error}"))?
            .next()
            .ok_or_else(|| format!("{address} is not an address"))?;
        let mut stream = TcpStream::connect_timeout(&target, PATIENCE).map_err(|error| format!("could not reach {address}, {error}"))?;
        let _ = stream.set_nodelay(true);
        let hello = ToRoom::Hello { version: VERSION, nickname: nickname.to_owned(), password: password.to_owned() };
        write(&mut stream, &hello).map_err(|error| format!("could not greet the room, {error}"))?;
        stream.set_read_timeout(Some(PATIENCE)).map_err(|error| error.to_string())?;
        let (mac, room) = match read::<FromRoom>(&mut stream) {
            Ok(FromRoom::Welcome { mac, name }) => (mac, name),
            Ok(FromRoom::Refused(reason)) => return Err(format!("the room turned you away, {reason}")),
            Ok(_) => return Err("the room answered something else".into()),
            Err(error) => return Err(format!("the room did not answer, {error}")),
        };
        stream.set_read_timeout(None).map_err(|error| error.to_string())?;
        let shared = Arc::new(Shared {
            mac,
            room,
            open: AtomicBool::new(true),
            members: Mutex::new(Vec::new()),
            inbox: Mutex::new(VecDeque::new()),
        });

        // what this side sends goes through a thread of its own, the
        // console never waits on the network
        let (outbox, outgoing) = channel::<ToRoom>();
        let mut writer = stream.try_clone().map_err(|error| error.to_string())?;
        let sending = shared.clone();
        std::thread::Builder::new()
            .name("room sending".into())
            .spawn(move || {
                for message in outgoing {
                    if write(&mut writer, &message).is_err() {
                        sending.open.store(false, Ordering::Relaxed);
                        break;
                    }
                }
            })
            .map_err(|error| error.to_string())?;
        let mut reader = stream.try_clone().map_err(|error| error.to_string())?;
        let receiving = shared.clone();
        std::thread::Builder::new()
            .name("room receiving".into())
            .spawn(move || {
                while let Ok(message) = read::<FromRoom>(&mut reader) {
                    match message {
                        FromRoom::Members(members) => {
                            if let Ok(mut known) = receiving.members.lock() {
                                *known = members;
                            }
                        }
                        FromRoom::Packet { from, body } => {
                            if let Ok(mut inbox) = receiving.inbox.lock() {
                                // a console that stopped taking them does not
                                // pile them up for good
                                let mut bytes = body.len() + inbox.iter().map(|(_, body)| body.len()).sum::<usize>();
                                while bytes > INBOX_LIMIT || inbox.len() >= 4096 {
                                    let Some((_, oldest)) = inbox.pop_front() else { break };
                                    bytes -= oldest.len();
                                }
                                inbox.push_back((from, body));
                            }
                        }
                        FromRoom::Welcome { .. } | FromRoom::Refused(_) => {}
                    }
                }
                receiving.open.store(false, Ordering::Relaxed);
                log::info!("left the room");
            })
            .map_err(|error| error.to_string())?;
        log::info!("joined the room {} as {nickname}", shared.room);
        Ok(Connection { stream, outbox, shared })
    }

    /// the room's name.
    pub fn room(&self) -> &str {
        &self.shared.room
    }

    pub fn mac(&self) -> Mac {
        self.shared.mac
    }

    pub fn open(&self) -> bool {
        self.shared.open.load(Ordering::Relaxed)
    }

    pub fn members(&self) -> Vec<Member> {
        self.shared.members.lock().map(|members| members.clone()).unwrap_or_default()
    }

    /// tells the room the game this member plays, none for none.
    pub fn playing(&self, game: &str) {
        let _ = self.outbox.send(ToRoom::Playing(game.to_owned()));
    }

    /// the console's way into the room, which leaves what came before it.
    pub fn link(&self) -> Link {
        if let Ok(mut inbox) = self.shared.inbox.lock() {
            inbox.clear();
        }
        Link { outbox: self.outbox.clone(), shared: self.shared.clone() }
    }
}

impl Drop for Connection {
    fn drop(&mut self) {
        self.shared.open.store(false, Ordering::Relaxed);
        let _ = self.stream.shutdown(Shutdown::Both);
    }
}

/// what the console sends into a room and takes out of it.
#[derive(Clone)]
pub struct Link {
    outbox: Sender<ToRoom>,
    shared: Arc<Shared>,
}

impl Link {
    pub fn mac(&self) -> Mac {
        self.shared.mac
    }

    /// whether the room is still there.
    pub fn open(&self) -> bool {
        self.shared.open.load(Ordering::Relaxed)
    }

    /// sends body to the member with the address, or to everyone.
    pub fn send(&self, to: Mac, body: Vec<u8>) {
        let _ = self.outbox.send(ToRoom::Packet { to, body });
    }

    /// what came since the last call, and from whom.
    pub fn receive(&self) -> Vec<(Mac, Vec<u8>)> {
        self.shared.inbox.lock().map(|mut inbox| inbox.drain(..).collect()).unwrap_or_default()
    }

    /// a link no room is behind, which hands what is sent to the other end
    /// of the pair, for two consoles in one program.
    pub fn pair() -> (Link, Link) {
        let make = |mac: Mac| {
            Arc::new(Shared {
                mac,
                room: "pair".into(),
                open: AtomicBool::new(true),
                members: Mutex::new(Vec::new()),
                inbox: Mutex::new(VecDeque::new()),
            })
        };
        let (first, second) = (make([0x02, 0x5A, 0x4B, 0, 0, 1]), make([0x02, 0x5A, 0x4B, 0, 0, 2]));
        let carry = |from: Arc<Shared>, to: Arc<Shared>| {
            let (outbox, outgoing) = channel::<ToRoom>();
            std::thread::spawn(move || {
                for message in outgoing {
                    if let ToRoom::Packet { to: address, body } = message {
                        if address == BROADCAST || address == to.mac {
                            if let Ok(mut inbox) = to.inbox.lock() {
                                inbox.push_back((from.mac, body));
                            }
                        }
                    }
                }
            });
            outbox
        };
        let first_link = Link { outbox: carry(first.clone(), second.clone()), shared: first.clone() };
        let second_link = Link { outbox: carry(second.clone(), first.clone()), shared: second };
        (first_link, second_link)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// two members of a room get their own addresses, see each other, and
    /// what one sends to the other or to everyone reaches the other only.
    #[test]
    fn a_room_passes_packets_between_members() {
        let server = Server::start(0, "test", "", 4).unwrap();
        let address = format!("127.0.0.1:{}", server.port());
        let first = Connection::join(&address, "first", "").unwrap();
        let second = Connection::join(&address, "second", "").unwrap();
        assert_ne!(first.mac(), second.mac());
        assert!(Connection::join(&address, "first", "").is_err(), "a nickname taken");

        let (to_first, to_second) = (first.link(), second.link());
        to_first.send(second.mac(), b"hello".to_vec());
        to_second.send(BROADCAST, b"all".to_vec());
        let wait = |link: &Link| {
            for _ in 0..200 {
                let got = link.receive();
                if !got.is_empty() {
                    return got;
                }
                std::thread::sleep(Duration::from_millis(5));
            }
            Vec::new()
        };
        assert_eq!(wait(&to_second), vec![(first.mac(), b"hello".to_vec())]);
        assert_eq!(wait(&to_first), vec![(second.mac(), b"all".to_vec())]);
        for _ in 0..200 {
            if first.members().len() == 2 {
                break;
            }
            std::thread::sleep(Duration::from_millis(5));
        }
        assert_eq!(first.members().len(), 2);
        drop(second);
        for _ in 0..200 {
            if first.members().len() == 1 {
                break;
            }
            std::thread::sleep(Duration::from_millis(5));
        }
        assert_eq!(server.members().len(), 1);
    }

    /// waits up to ten seconds for what is asked.
    fn until(what: impl Fn() -> bool) -> bool {
        for _ in 0..1000 {
            if what() {
                return true;
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        false
    }

    /// a member that only says hello, through a socket of its own.
    fn raw_member(address: &str, nickname: &str) -> TcpStream {
        let mut stream = TcpStream::connect(address).unwrap();
        write(&mut stream, &ToRoom::Hello { version: VERSION, nickname: nickname.into(), password: String::new() }).unwrap();
        stream.set_read_timeout(Some(PATIENCE)).unwrap();
        assert!(matches!(read::<FromRoom>(&mut stream), Ok(FromRoom::Welcome { .. })));
        stream
    }

    /// a long nickname is refused, and a long game is cut, so the list of
    /// members still reaches everyone.
    #[test]
    fn long_names_do_not_break_the_room() {
        let server = Server::start(0, "test", "", 4).unwrap();
        let address = format!("127.0.0.1:{}", server.port());
        assert!(Connection::join(&address, &"n".repeat(NICKNAME_LIMIT + 1), "").is_err());
        assert!(Connection::join(&address, "bell\u{7}", "").is_err());
        // the longest nickname the apps send, 10 four-byte characters, gets in
        let _longest = Connection::join(&address, &"\u{1F600}".repeat(10), "").unwrap();
        let watcher = Connection::join(&address, "watcher", "").unwrap();
        let mut raw = raw_member(&address, "raw");
        write(&mut raw, &ToRoom::Playing("\u{1}".to_owned() + &"g".repeat(65000))).unwrap();
        let cut = "g".repeat(GAME_LIMIT);
        assert!(until(|| watcher.members().iter().any(|member| member.game == cut)));
        assert!(watcher.open());
        assert_eq!(tidy(&("a".to_owned() + &"\u{e9}".repeat(GAME_LIMIT))).len(), GAME_LIMIT - 1);
        assert!(write(&mut Vec::new(), &FromRoom::Packet { from: BROADCAST, body: vec![0; LARGEST] }).is_err());
    }

    /// a member that never reads is let go, and the room keeps taking others.
    #[test]
    fn a_member_that_never_reads_does_not_freeze_the_room() {
        let server = Server::start(0, "test", "", 4).unwrap();
        let address = format!("127.0.0.1:{}", server.port());
        let _stuck = raw_member(&address, "stuck");
        let sender = Connection::join(&address, "sender", "").unwrap();
        let link = sender.link();
        for _ in 0..400 {
            link.send(BROADCAST, vec![0; 60_000]);
        }
        // long enough for the room to be writing to the stuck member
        std::thread::sleep(Duration::from_millis(300));
        let started = std::time::Instant::now();
        let third = Connection::join(&address, "third", "");
        assert!(third.is_ok(), "{:?}", third.err());
        assert!(started.elapsed() < Duration::from_secs(10));
        assert!(until(|| server.members().iter().all(|member| member.nickname != "stuck")));
    }

    /// what a console does not take is kept up to INBOX_LIMIT, the newest.
    #[test]
    fn the_inbox_is_capped_by_size() {
        let server = Server::start(0, "test", "", 4).unwrap();
        let address = format!("127.0.0.1:{}", server.port());
        let receiver = Connection::join(&address, "receiver", "").unwrap();
        let sender = Connection::join(&address, "sender", "").unwrap();
        let link = sender.link();
        for _ in 0..200 {
            link.send(receiver.mac(), vec![0; 60_000]);
        }
        link.send(receiver.mac(), b"last".to_vec());
        let inbox = &receiver.shared.inbox;
        assert!(until(|| inbox.lock().unwrap().back().is_some_and(|(_, body)| body == b"last")));
        let kept = inbox.lock().unwrap().iter().map(|(_, body)| body.len()).sum::<usize>();
        assert!(kept <= INBOX_LIMIT, "{kept}");
        assert!(kept > INBOX_LIMIT - 60_000);
    }
}
