"""Restricted Windows-to-WSL PostgreSQL development relay (not a remote DB endpoint)."""
import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import select
import socket
import socketserver
import sys


def ipv4(value):
    address = ipaddress.IPv4Address(value)
    if address.is_unspecified or address.is_multicast or address.is_reserved:
        raise argparse.ArgumentTypeError("A specific unicast IPv4 address is required")
    return str(address)


def start_ticks(pid):
    return Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()[19]


def script_hash():
    return hashlib.sha256(Path(__file__).read_bytes()).hexdigest()


def config(args):
    return dict(listen_host=args.listen_host, port=args.port, allow_client=args.allow_client,
                upstream_host="127.0.0.1", upstream_port=5432, script=str(Path(__file__).resolve()),
                script_sha256=script_hash())


def verify_running(args):
    """Fail closed unless this exact configured relay owns the listening socket."""
    state_path = Path(args.state_file)
    state = json.loads(state_path.read_text())
    expected = config(args)
    if any(state.get(k) != v for k, v in expected.items()):
        raise RuntimeError("Relay configuration/script differs; coordinate an explicit restart")
    pid = int(state["pid"])
    proc = Path(f"/proc/{pid}")
    if proc.stat().st_uid != os.getuid() or state_path.stat().st_uid != os.getuid():
        raise RuntimeError("Relay owner differs")
    if state["start_ticks"] != start_ticks(pid):
        raise RuntimeError("Relay process identity changed")
    cmd = (proc / "cmdline").read_bytes().split(b"\0")
    if str(Path(__file__).resolve()).encode() not in cmd:
        raise RuntimeError("Port owner is not this relay script")
    address = socket.inet_aton(args.listen_host)[::-1].hex().upper()
    endpoint = f"{address}:{args.port:04X}"
    inodes = {line.split()[9] for line in Path("/proc/net/tcp").read_text().splitlines()[1:]
              if line.split()[1] == endpoint and line.split()[3] == "0A"}
    owned = set()
    for fd in (proc / "fd").iterdir():
        try:
            link = os.readlink(fd)
        except FileNotFoundError:
            continue
        if link.startswith("socket:["):
            owned.add(link[8:-1])
    if not (inodes & owned):
        raise RuntimeError("Configured relay does not own the listening socket")
    print(json.dumps(dict(verified=True, **state)), flush=True)


class Relay(socketserver.BaseRequestHandler):
    def handle(self):
        try:
            with socket.create_connection(("127.0.0.1", 5432), timeout=10) as upstream:
                upstream.settimeout(None)
                peers = {self.request: upstream, upstream: self.request}
                while True:
                    readable, _, _ = select.select(list(peers), [], [])
                    for source in readable:
                        data = source.recv(65536)
                        if not data:
                            return
                        peers[source].sendall(data)
        except (OSError, ConnectionError):
            # A rejected/disconnected client or unavailable upstream must not kill the listener.
            return


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = False
    daemon_threads = True

    def verify_request(self, request, client_address):
        return client_address[0] == self.allowed_client


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--listen-host", required=True, type=ipv4)
    parser.add_argument("--allow-client", required=True, type=ipv4)
    parser.add_argument("--port", type=int, default=15432)
    parser.add_argument("--state-file", required=True)
    parser.add_argument("--verify-running", action="store_true")
    parser.add_argument("--check-available", action="store_true")
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        parser.error("port must be between 1024 and 65535")
    if args.check_available:
        with Server((args.listen_host, args.port), Relay):
            print("Relay port available", flush=True)
        return
    if args.verify_running:
        verify_running(args)
        return
    # Bind before publishing state; never replace an unrelated listener or reuse its state.
    with Server((args.listen_host, args.port), Relay) as server:
        server.allowed_client = args.allow_client
        state = dict(config(args), pid=os.getpid(), start_ticks=start_ticks(os.getpid()))
        state_path = Path(args.state_file)
        temporary = state_path.with_name(state_path.name + f".{os.getpid()}.tmp")
        with temporary.open("x") as output:
            os.chmod(temporary, 0o600)
            json.dump(state, output)
        temporary.replace(state_path)
        print(json.dumps(dict(started=True, **state)), flush=True)
        server.serve_forever()


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, KeyError) as error:
        print(f"Relay refused: {error}", file=sys.stderr)
        sys.exit(1)
