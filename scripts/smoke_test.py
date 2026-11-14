#!/usr/bin/env python3
"""Paper 1.20.1 smoke test: bot stays registered after the keepalive interval."""
import socket
import json
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
    print("Launching Paper without authenticated clients", flush=True)
    output = open(SERVER / "console.log", "w")
    process = subprocess.Popen(
        ["java", "-Xms256M", "-Xmx1200M", "-jar", "paper.jar", "--nogui"],
        cwd=SERVER, stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT
    )
    viewer_process = None
    viewer_output = None
    try:
        deadline = time.monotonic() + 160
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError(f"Paper exited with code {process.returncode}")
            try:
                result = rcon("list")
                if "SBotTester" in result:
                    print("Registered backend fake player: " + result, flush=True)
                    break
            except (OSError, ValueError, RuntimeError):
                pass
            time.sleep(2)
        else:
            raise AssertionError("Restored bot never registered")

        # Real Minecraft 1.20.1 client, using mineflayer (offline-mode).
        viewer_output = open(SERVER / "viewer.log", "w")
        viewer_process = subprocess.Popen(
            ["node", "scripts/viewer_probe.js"],
            stdout=viewer_output, stderr=subprocess.STDOUT
        )
        ready = SERVER / "viewer.ready"
        end = time.monotonic() + 65
        while not ready.exists() and time.monotonic() < end:
            if viewer_process.poll() is not None:
                raise RuntimeError("Viewer client disconnected during login")
            time.sleep(0.5)
        assert ready.exists(), "Viewer client did not receive Minecraft world"

        rcon("op SBotViewer")
        rcon("forceload add 64 -32")
        rcon("setblock 64 89 -33 stone")
        rcon("tp SBotViewer 64.5 90.0 -32.5")
        time.sleep(3)

        created = rcon("sbot createat 1 world 67.5 90.0 -32.5")
        print("Created: " + created, flush=True)
        roster = rcon("sbot list")
        matched = re.findall(r"[A-Za-z][A-Za-z0-9_]{2,15}_\d{4}", roster)
        assert len(matched) == 1, f"Expected random username in {roster}"
        name = matched[0]
        print("Generated bot " + name, flush=True)

        def coords(line):
            stripped = re.sub(r"§.", "", line)
            pattern = r"Posición:\s*(-?\d+(?:\.\d+)?),\s*(-?\d+(?:\.\d+)?),\s*(-?\d+(?:\.\d+)?)"
            match = re.search(pattern, stripped)
            assert match, f"No coordinates in {line}"
            return tuple(float(value) for value in match.groups())

        def await_visible(stage, timeout=40):
            end = time.monotonic() + timeout
            path = SERVER / "viewer.seen.json"
            while time.monotonic() < end:
                if viewer_process.poll() is not None:
                    raise RuntimeError("Minecraft viewer disconnected")
                if path.exists():
                    result = json.loads(path.read_text())
                    if result["stage"] == stage and result["name"] == name:
                        print(f"VIEWER SAW BOT stage {stage}: {result}", flush=True)
                        return
                time.sleep(0.25)
            raise AssertionError(f"Minecraft client did not see bot at stage {stage}")

        info = rcon(f"sbot info {name}")
        x,y,z = coords(info)
        print("Initial server position:", x,y,z,flush=True)
        assert abs(x-67.5)<0.35 and abs(y-90)<0.35 and abs(z+32.5)<0.35

        (SERVER / "viewer.target.json").write_text(json.dumps(
            {"stage":1,"name":name,"x":67.5,"y":90.0,"z":-32.5,"oldX":67.5}
        ))
        await_visible(1)

        # Move the real client to another location, then run the actual
        # /sbot tp <name> player command. The fake must become visible there.
        (SERVER / "viewer.seen.json").unlink(missing_ok=True)
        rcon("forceload add 80 -32")
        rcon("setblock 80 89 -33 stone")
        rcon("tp SBotViewer 80.5 90.0 -32.5")
        time.sleep(2)
        (SERVER / "viewer.target.json").write_text(json.dumps(
            {"stage":2,"name":name,"oldX":67.5}
        ))
        (SERVER / "viewer.command.json").write_text(json.dumps(
            {"command":f"/sbot tp {name}"}
        ))
        await_visible(2)
        moved = coords(rcon(f"sbot info {name}"))
        print("After /sbot tp, server position:", moved,flush=True)
        assert abs(moved[0] - 80.5) < 4.5 and abs(moved[2] + 32.5) < 4.5

        # Simulated players must still be online after the genuine viewer quits.
        viewer_process.terminate()
        viewer_process.wait(timeout=10)
        viewer_process = None
        time.sleep(65)
        listing = rcon("list")
        status = rcon("sbot status")
        print("After 65 seconds with NO real clients:", listing,flush=True)
        print("SBot status:", status,flush=True)
        assert name in listing and "SBotTester" in listing
        assert "0 reales" in re.sub(r"§.", "", status)
        print("PASS: visible body, player-issued teleport, stable coordinates and persistence", flush=True)
    finally:
        if viewer_process and viewer_process.poll() is None:
            viewer_process.terminate()
            try:
                viewer_process.wait(timeout=8)
            except subprocess.TimeoutExpired:
                viewer_process.kill()
        if viewer_output:
            viewer_output.close()
        viewer_log = SERVER / "viewer.log"
        if viewer_log.exists():
            print("\n==== REAL VIEWER LOG ====\n" + viewer_log.read_text(errors="replace")[-12000:],flush=True)
        print_logs()
        process.terminate()
        try:
            process.wait(timeout=12)
        except subprocess.TimeoutExpired:
            process.kill()
        output.close()


if __name__ == "__main__":
    main()
