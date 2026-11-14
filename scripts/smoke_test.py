#!/usr/bin/env python3
"""Paper 1.20.1 smoke test: bot stays registered after the keepalive interval."""
import socket
import re
import struct
import subprocess
import time
from pathlib import Path

SERVER = Path("server")


def exact(sock, size):
    data = bytearray()
    while len(data) < size:
        chunk = sock.recv(size - len(data))
        if not chunk:
            raise ConnectionError("RCON closed the connection")
        data.extend(chunk)
    return bytes(data)


def rcon(command):
    def send(sock, rid, typ, value):
        payload = struct.pack("<ii", rid, typ) + value.encode() + b"\x00\x00"
        sock.sendall(struct.pack("<i", len(payload)) + payload)

    def recv(sock):
        size = struct.unpack("<i", exact(sock, 4))[0]
        if size < 10 or size > 4_000_000:
            raise RuntimeError("Invalid RCON packet")
        body = exact(sock, size)
        rid, typ = struct.unpack("<ii", body[:8])
        return rid, typ, body[8:].rstrip(b"\x00").decode(errors="replace")

    with socket.create_connection(("127.0.0.1", 25575), timeout=45) as sock:
        sock.settimeout(45)
        send(sock, 10, 3, "sbot-smoke-test")
        for _ in range(3):
            rid, typ, _ = recv(sock)
            if rid == -1:
                raise RuntimeError("Bad RCON password")
            if rid == 10 and typ == 2:
                break
        else:
            raise RuntimeError("No RCON authentication response")
        send(sock, 11, 2, command)
        for _ in range(3):
            rid, _, msg = recv(sock)
            if rid == 11:
                return msg
        raise RuntimeError("No command response")


def print_logs():
    path = SERVER / "logs/latest.log"
    if path.exists():
        print("\n==== Paper latest.log ====\n" + "\n".join(path.read_text(errors="replace").splitlines()[-85:]), flush=True)


def main():
    print("Launching Paper with no genuine Minecraft clients", flush=True)
    output = open(SERVER / "console.log", "w")
    process = subprocess.Popen(
        ["java", "-Xms256M", "-Xmx1200M", "-jar", "paper.jar", "--nogui"],
        cwd=SERVER, stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT
    )
    try:
        deadline = time.monotonic() + 160
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError(f"Paper exited with code {process.returncode}")
            try:
                result = rcon("list")
                if "SBotTester" in result:
                    print("Player is registered in real /list: " + result, flush=True)
                    break
            except (OSError, ValueError, RuntimeError):
                pass
            time.sleep(2)
        else:
            raise AssertionError("Bot never became a real server-list entry")
        # Create a randomly named player at a known location via the same
        # manager used by /sbot create (console variant for unattended CI).
        # /sbot create is normally invoked by a real player inside an already
        # loaded chunk. Preload the test destination to emulate that condition.
        loaded = rcon("forceload add 64 -32")
        print("Chunk readiness: " + loaded, flush=True)
        created = rcon("sbot createat 1 world 64.5 90.0 -32.5")
        print("Create at requested position: " + created, flush=True)
        assert "1" in created, "Bot creation did not report one player"
        roster = rcon("sbot list")
        print("SBot roster: " + roster, flush=True)
        matched = re.findall(r"[A-Za-z][A-Za-z0-9_]{2,15}_\d{4}", roster)
        assert len(matched) == 1, f"Expected one generated username, got {matched!r}"
        name = matched[0]
        assert name not in ("Notch", "Dream", "Grian", "jeb_")
        def check_position(line):
            stripped = re.sub(r"§.", "", line)
            match = re.search(r"Posición:\s*(-?\d+(?:\.\d+)?),\s*(-?\d+(?:\.\d+)?),\s*(-?\d+(?:\.\d+)?)", stripped)
            assert match, f"Could not parse player position: {line}"
            actual = tuple(float(v) for v in match.groups())
            desired = (64.5, 90.0, -32.5)
            assert all(abs(x - target) < 0.35 for x, target in zip(actual, desired)), (
                f"Bot spawned at {actual}, expected near {desired}"
            )

        info = rcon(f"sbot info {name}")
        print("Actual bot position: " + info, flush=True)
        check_position(info)
        time.sleep(65)
        listing = rcon("list")
        status = rcon("sbot status")
        later_info = rcon(f"sbot info {name}")
        print("Actual bot position after 65 seconds: " + later_info, flush=True)
        check_position(later_info)
        assert name in listing, f"Generated bot missing after 65 seconds: {listing}"
        print("After 65 seconds: " + listing, flush=True)
        print("SBot status: " + status, flush=True)
        assert "SBotTester" in listing, "Bot disappeared!"
        assert "SBot" in status, "/sbot status was not available"
        print("PASS: both native players stayed connected; random name and requested position verified", flush=True)
    finally:
        print_logs()
        process.terminate()
        try:
            process.wait(timeout=12)
        except subprocess.TimeoutExpired:
            process.kill()
        output.close()


if __name__ == "__main__":
    main()
