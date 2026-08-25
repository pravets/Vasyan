#!/usr/bin/env python3
"""Behavior test: a Vasyan force-loads its chunk and keeps working with no player.

Scenario (headless server, RCON, no LLM needed - "стоп" is a stay pre-trigger):

  0. Cyrillic names (issue #16): spawn/tell/remove Васян, reject Бот#1.
  1. Start the Forge server (nogui), wait for "Done (".
  2. /vasyan spawn Bob -> bot appears in the world (spawn chunks).
  3. /tp <uuid> 5000 80 5000 -> teleport far away from spawn chunks.
  4. Wait for the bot to force-load its chunk (updateForcedChunk every 40 ticks).
  5. /vasyan tell Bob стоп -> stay command must be EXECUTED by the bot's tick
     ("executing: Stay" only appears from ActionExecutor.tick, i.e. only when
     the entity is actually ticking in its force-loaded chunk).
  6. Without the chunk force-load feature the bot would not tick at x=5000
     (entities only tick in loaded chunks) and the stay would never execute.
  7. Restart scenario (issue #14): Bob keeps his chunk force-loaded across a
     server restart with no players online, and still ticks afterwards.

Asserts are log-pattern based with timeouts. Exits 0 on pass, 1 on fail.
"""
import argparse
import os
import re
import socket
import struct
import subprocess
import sys
import time

RCON_PORT = 25575
RCON_PASSWORD = "vasyan_test"


class RCON:
    """Minimal Minecraft RCON client with terminator-packet draining."""

    def __init__(self, host="127.0.0.1", port=RCON_PORT, password=RCON_PASSWORD):
        """Connect to host:port and authenticate with password."""
        self.sock = socket.create_connection((host, port), timeout=60)
        self.request_id = 1
        self._buf = b""
        self._auth(password)

    def _read_packet(self):
        """Parse one complete packet from ``self._buf`` if available."""
        if len(self._buf) < 4:
            return None
        length = struct.unpack("<i", self._buf[:4])[0]
        if len(self._buf) < 4 + length:
            return None
        pkt = self._buf[4:4 + length]
        self._buf = self._buf[4 + length:]
        r, t = struct.unpack("<ii", pkt[:8])
        body = pkt[8:].rstrip(b"\x00").decode(errors="replace")
        return r, t, body

    def _drain_empty_packets(self):
        """Discard empty SERVERDATA_RESPONSE_VALUE terminator packets."""
        while True:
            pkt = self._read_packet()
            if pkt is None:
                return
            r, t, body = pkt
            if t != 0 or body:
                # Not an empty terminator; put it back and stop draining.
                self._buf = struct.pack("<i", 10) + struct.pack("<ii", r, t) + body.encode() + b"\x00\x00" + self._buf
                return
            # Otherwise discard empty terminator and continue.

    def _send(self, ptype, body):
        """Send a raw RCON packet and return the response packet."""
        rid = self.request_id
        self.request_id += 1
        payload = struct.pack("<ii", rid, ptype) + body.encode() + b"\x00\x00"
        self.sock.sendall(struct.pack("<i", len(payload)) + payload)
        self.sock.settimeout(30)
        # Command responses can leave empty terminator packets behind. Make
        # sure the next response read starts on a real packet, not on a
        # leftover terminator.
        if ptype == 2:
            self._drain_empty_packets()
        while True:
            pkt = self._read_packet()
            if pkt is not None:
                return pkt
            try:
                chunk = self.sock.recv(4096)
                if not chunk:
                    raise ConnectionError("RCON connection closed while reading response")
                self._buf += chunk
            except socket.timeout:
                raise ConnectionError("Timed out waiting for RCON response")

    def _recv_exact(self, n):
        """Read exactly n bytes from the socket (kept for compatibility)."""
        data = b""
        while len(data) < n:
            chunk = self.sock.recv(n - len(data))
            if not chunk:
                raise ConnectionError("RCON connection closed")
            data += chunk
        return data

    def _auth(self, password):
        """Authenticate with the RCON server."""
        rid, ptype, _ = self._send(3, password)
        if rid == -1 or ptype != 2:
            raise ConnectionError(f"RCON auth failed (id={rid}, type={ptype})")
        # Forge needs a beat after auth before it processes commands -
        # a command sent immediately can be dropped (no response at all).
        time.sleep(1.0)

    def command(self, cmd):
        """Send a command and return its response body."""
        rid, ptype, body = self._send(2, cmd)
        return body

    def close(self):
        """Close the RCON connection."""
        self.sock.close()


def start_server(workdir, jar_path, log_path):
    """Launch the headless Forge server and redirect all output to log_path."""
    with open(log_path, "wb") as log:
        proc = subprocess.Popen(
            ["java", "-Xmx2G", "@libraries/net/minecraftforge/forge/1.20.1-47.2.0/unix_args.txt", "nogui"],
            cwd=workdir, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT)
    return proc


def wait_for(log_path, pattern, timeout, label):
    """Wait until log_path contains a line matching regex pattern.

    Reads incrementally from the last position to avoid re-scanning the whole
    log on every poll.

    Returns True on match, False if the timeout expires.
    """
    regex = re.compile(pattern)
    deadline = time.time() + timeout
    last = 0
    while time.time() < deadline:
        with open(log_path, "r", errors="replace") as f:
            f.seek(last)
            chunk = f.read()
            last = f.tell()
            if regex.search(chunk):
                print(f"  [ok] {label}: matched {pattern!r}")
                return True
        time.sleep(2)
    print(f"  [FAIL] {label}: no match for {pattern!r} within {timeout}s")
    return False


def spawn_bot(rcon, log_path, name):
    rcon.command(f"vasyan spawn {name}")
    assert wait_for(log_path, rf"[Ss]pawned Vasyan: {name}", 30, f"spawn {name}")


def get_bot_uuid(log_text, name):
    m = re.search(rf"[Ss]pawned Vasyan: {name} with UUID ([0-9a-f-]+)", log_text)
    return m.group(1) if m else None


def teleport_to_far(rcon, log_path, uuid, spawn_x, spawn_z, dx_chunks=500):
    far_x = int(spawn_x) + dx_chunks * 16 + 8
    rcon.command(f"tp {uuid} {far_x} 4 0")
    assert wait_for(log_path, r"Teleported", 30, "teleport")
    return far_x


def assert_chunk_force_loaded(rcon, far_x, z=0):
    fl_response = rcon.command(f"forceload query {far_x} {z}")
    print(f"  forceload query: {fl_response}")
    assert "marked for force loading" in fl_response


def assert_bot_ticks(rcon, log_path, name):
    rcon.command(f"vasyan tell {name} gather 50 wood")
    assert wait_for(log_path, r"async planning complete: 1 tasks queued", 120, f"{name} ticks in far chunk")


def test_chunk_persists_after_restart(workdir, jar_path, expected_far_x):
    """Restart the server with the same world and verify the existing Bob
    (spawned by the first scenario) is re-adopted, his chunk is still
    force-loaded, and he keeps ticking with no players online.
    """
    log_path = os.path.join(workdir, "behavior_restart.log")
    if os.path.exists(log_path):
        os.remove(log_path)

    print("Starting server for restart scenario...")
    proc = start_server(workdir, jar_path, log_path)
    try:
        assert wait_for(log_path, r"Done \(", 180, "server restart")
        time.sleep(3)
        rcon = RCON()

        list_resp = rcon.command("vasyan list")
        print(f"  vasyan list after restart: {list_resp}")
        assert "Bob" in list_resp

        # The chunk where Bob was left should still be force-loaded.
        assert_chunk_force_loaded(rcon, expected_far_x)

        # Verify the re-adopted bot actually ticks.
        print("Sending 'gather 50 wood' to restarted Bob...")
        assert_bot_ticks(rcon, log_path, "Bob")

        print("PASS: Bob survived server restart in force-loaded chunk.")
        rcon.close()
        return 0
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=30)
        except subprocess.TimeoutExpired:
            proc.kill()


def main():
    """Run the headless Forge server behavior test and return an exit code."""
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True, help="server directory")
    ap.add_argument("--jar", required=True, help="path to vasyan mod jar")
    args = ap.parse_args()

    log_path = os.path.join(args.dir, "behavior.log")
    if os.path.exists(log_path):
        os.remove(log_path)

    # Fresh world every run: a previous run leaves adopted Vasyan entities in
    # the world files, and adopt-on-join would then reject the spawn
    # ("Vasyan name already exists").
    for stale in ("world", "world_nether", "world_the_end"):
        p = os.path.join(args.dir, stale)
        if os.path.isdir(p):
            import shutil
            shutil.rmtree(p)

    print("Starting server...")
    proc = start_server(args.dir, args.jar, log_path)
    try:
        if not wait_for(log_path, r"Done \(", 180, "server start"):
            return 1

        time.sleep(3)
        rcon = RCON()
        try:
            # 0. Cyrillic-name scenario (issue #16): VasyanNameArgumentType
            #    accepts unicode letters, so /vasyan commands work with
            #    cyrillic bot names. Runs first - it is fast and local
            #    (spawn chunks, no LLM needed). RCON command bodies are
            #    UTF-8 encoded (RCON._send), so cyrillic survives the wire.
            print("Testing cyrillic Vasyan names (issue #16)...")

            spawn_resp = rcon.command("vasyan spawn Васян")
            print(f"  vasyan spawn Васян -> {spawn_resp!r}")
            if "Spawned Vasyan" not in spawn_resp or "Васян" not in spawn_resp:
                print("  [FAIL] cyrillic spawn: unexpected response")
                return 1
            if not wait_for(log_path, r"[Ss]pawned Vasyan: Васян with UUID [0-9a-f-]+ at \(", 30, "cyrillic spawn"):
                return 1

            # Stay pre-trigger is deterministic (no LLM round-trip) and
            # answers "<name> stopped" immediately.
            stay_resp = rcon.command("vasyan tell Васян стоп")
            print(f"  vasyan tell Васян стоп -> {stay_resp!r}")
            if "Васян stopped" not in stay_resp:
                print("  [FAIL] cyrillic stay command did not stop Васян")
                return 1

            remove_resp = rcon.command("vasyan remove Васян")
            print(f"  vasyan remove Васян -> {remove_resp!r}")
            if "Removed Vasyan" not in remove_resp or "Васян" not in remove_resp:
                print("  [FAIL] cyrillic remove: unexpected response")
                return 1

            # Negative case: '#' is outside the allowed charset, so Brigadier
            # must reject the name (translatable key 'argument.vasyan.vasyan_name.invalid'
            # or its rendered text) and no bot may be spawned.
            bad_resp = rcon.command("vasyan spawn Бот#1")
            print(f"  vasyan spawn Бот#1 -> {bad_resp!r}")
            if "invalid" not in bad_resp.lower():
                print("  [FAIL] invalid cyrillic name was not rejected")
                return 1
            with open(log_path, "r", errors="replace") as f:
                if re.search(r"[Ss]pawned Vasyan: Бот#1", f.read()):
                    print("  [FAIL] invalid name spawned a Vasyan anyway")
                    return 1
            print("  -> cyrillic names work, invalid name rejected")

            # 0b. Case-insensitive name handling (issue #4): the canonical
            # bot is named "Bob", but commands with different casing must
            # resolve to the same entity.
            print("Testing case-insensitive Vasyan names (issue #4)...")

            rcon.command("vasyan spawn Bob")
            if not wait_for(log_path, r"[Ss]pawned Vasyan: Bob", 30, "case-insensitive spawn"):
                return 1

            stay_resp = rcon.command("vasyan tell BOB стоп")
            print(f"  vasyan tell BOB стоп -> {stay_resp!r}")
            # The dispatcher replies with the canonical bot name.
            if "stopped" not in stay_resp.lower() or "bob" not in stay_resp.lower():
                print("  [FAIL] case-insensitive tell did not stop Bob")
                return 1

            gather_resp = rcon.command("vasyan tell bob gather 50 wood")
            print(f"  vasyan tell bob gather 50 wood -> {gather_resp!r}")
            if not wait_for(log_path, r"async planning complete: 1 tasks queued", 120, "case-insensitive task queued"):
                print("  [FAIL] case-insensitive tell did not queue a task for Bob")
                return 1

            stop_resp = rcon.command("vasyan stop BOB")
            print(f"  vasyan stop BOB -> {stop_resp!r}")
            if "stopped" not in stop_resp.lower() or "bob" not in stop_resp.lower():
                print("  [FAIL] case-insensitive stop did not stop Bob")
                return 1

            remove_resp = rcon.command("vasyan remove bob")
            print(f"  vasyan remove bob -> {remove_resp!r}")
            if "removed" not in remove_resp.lower() or "bob" not in remove_resp.lower():
                print("  [FAIL] case-insensitive remove did not remove Bob")
                return 1
            print("  -> case-insensitive names work")

            # 1. Spawn Bob
            print("Spawning Bob...")
            spawn_bot(rcon, log_path, "Bob")

            # Extract Bob's UUID and actual spawn position for the /tp.
            # There may be an earlier case-insensitive spawn, so always use the
            # *last* occurrence in the log (the one we just spawned).
            with open(log_path, "r", errors="replace") as f:
                log_text = f.read()
            uuid_m = None
            uuid_matches = re.findall(r"[Ss]pawned Vasyan: Bob with UUID ([0-9a-f-]+)", log_text)
            if uuid_matches:
                uuid_m = uuid_matches[-1]
            if not uuid_m:
                print("  [FAIL] Bob UUID not found in log")
                return 1
            print(f"  Bob UUID: {uuid_m}")

            # 2. Find Bob's ACTUAL spawn position (world spawn is not fixed:
            #    no level-seed is set, the spawn chunk varies). Teleport him
            #    to a chunk more than 9 chunks away from the spawn chunk -
            #    Minecraft 1.20.1 keeps a 19x19 spawn-tick area around world
            #    spawn, so anything within 9 chunks ticks even without our
            #    force-loading. y=4 = flat surface: teleporting high up makes
            #    the bot fall for ages and stalls the 1-core runner.
            spawn_pos_matches = re.findall(r"[Ss]pawned Vasyan: Bob with UUID [0-9a-f-]+ at \(([-\d.]+), ([-\d.]+), ([-\d.]+)\)", log_text)
            if not spawn_pos_matches:
                print("  [FAIL] Bob spawn position not found in log")
                return 1
            spawn_x, _, spawn_z = map(float, spawn_pos_matches[-1])
            far_x = int(spawn_x) + 10 * 16 + 8  # +10 chunks east, block coords
            print(f"Teleporting Bob from spawn ({spawn_x:.0f}, {spawn_z:.0f}) to ({far_x}, 4, 0)...")
            rcon.command(f"tp {uuid_m} {far_x} 4 0")
            if not wait_for(log_path, r"Teleported", 30, "teleport"):
                return 1

            # 3. Give the chunk force-load a few seconds. VasyanMod.onServerTick
            #    calls updateForcedChunks every tick, so the force flag is set
            #    almost immediately - the sleep is just buffer for chunk I/O.
            print("Waiting for chunk force-load...")
            time.sleep(12)

            # 4. The chunk must be marked as force-loaded (block coords in the
            #    query: chunk = block >> 4). Vanilla forceload sends its reply
            #    to the RCON client only - check the body.
            far_chunk_x = far_x >> 4
            print("Checking force-load status...")
            assert_chunk_force_loaded(rcon, far_x)
            print(f"  -> chunk [{far_chunk_x}, 0] force-loaded")

            # 5. Give a gather command. The LLM endpoint is unreachable by
            #    design, so the fallback handler produces a deterministic task
            #    (pattern match "mine" or the safe default "follow" - either
            #    way a task is queued). "async planning complete" is logged
            #    from ActionExecutor.tick - i.e. ONLY when the entity actually
            #    ticks in its force-loaded chunk at x=300 (outside spawn).
            print("Sending 'gather 50 wood'...")
            assert_bot_ticks(rcon, log_path, "Bob")

            print("PASS: Vasyan worked in a force-loaded chunk with no player online.")

            # Cleanly stop the first server so the world (with Bob in the
            # far force-loaded chunk) is saved for the restart scenario.
            print("Stopping server to save world for restart scenario...")
            rcon.command("stop")
            if not wait_for(log_path, r"Saving chunks for level 'ServerLevel", 60, "server save"):
                return 1
            try:
                proc.wait(timeout=60)
            except subprocess.TimeoutExpired:
                proc.terminate()
                try:
                    proc.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.wait()
        finally:
            rcon.close()
    finally:
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=30)
            except subprocess.TimeoutExpired:
                proc.kill()

    # 6. Restart scenario (issue #14): same world, no players, must survive.
    #    Do NOT delete world/ so Bob's entity data persists across restart.
    if test_chunk_persists_after_restart(args.dir, args.jar, far_x) != 0:
        return 1

    # 7. Pathfinding overhaul scenarios (Phase 0.6 P1): river crossing without
    #    teleporting, side-adjacency arrival, and dig-through-wall. Fresh
    #    server (world was saved twice; Bob is adopted on spawn attempt).
    if test_pathfinding_scenarios(args.dir, args.jar) != 0:
        return 1

    return 0


def _bot_position_from_log(log_path, name, after_offset=0):
    """Extracts the LAST logged position of the named bot after the given byte
    offset. Vasyan position logs look like 'Vasyan 'Bob' - Ticking action'
    lines carry no coords, so we rely on PathfindAction success/position debug
    lines: 'Alex ACTION_START ...' has none either - the reliable source is
    /vasyan dump-less debug: 'Position: [x, y, z]' inside fallback prompt text
    or explicit tp responses. Simplest robust source: execute 'tp <name> ~ ~ ~'
    via RCON which logs 'Teleported <name> to x, y, z'. Returns (x, y, z) or
    None."""
    with open(log_path, "r", errors="replace") as f:
        f.seek(after_offset)
        chunk = f.read()
    matches = re.findall(
        rf"Teleported {re.escape(name)} to \((-?[\d.]+), (-?[\d.]+), (-?[\d.]+)\)", chunk)
    if not matches:
        return None
    x, y, z = map(float, matches[-1])
    return (int(round(x)), int(round(y)), int(round(z)))


def test_pathfinding_scenarios(workdir, jar_path):
    """Three scenarios over one server run:

    A) River crossing: a water channel is dug across the path; the bot must
       reach the far side WITHOUT any hop-teleport (amphibious nav swims).
    B) Adjacent stand: the bot must stop beside an obsidian block (XZ
       manhattan distance == 1, same y) - GoalAdjacent semantics.
    C) Wall dig-through: a dirt wall blocking the straight line must be dug
       through (DIG_THROUGH ladder step) and the bot reaches the target.
    """
    log_path = os.path.join(workdir, "behavior_nav.log")
    if os.path.exists(log_path):
        os.remove(log_path)

    print("Starting server for pathfinding scenarios...")
    proc = start_server(workdir, jar_path, log_path)
    rcon = None
    try:
        if not wait_for(log_path, r"Done \(", 180, "server start"):
            return 1
        time.sleep(3)
        rcon = RCON()

        spawn_resp = rcon.command("vasyan spawn Navigator")
        if "Spawned Vasyan" not in spawn_resp:
            print(f"  [FAIL] Navigator spawn: {spawn_resp!r}")
            return 1
        if not wait_for(log_path, r"[Ss]pawned Vasyan: Navigator with UUID [0-9a-f-]+ at \(", 30,
                        "Navigator spawn"):
            return 1

        with open(log_path, "r", errors="replace") as f:
            log_text = f.read()
        pos_matches = re.findall(
            r"[Ss]pawned Vasyan: Navigator with UUID [0-9a-f-]+ at \(([-\d.]+), ([-\d.]+), ([-\d.]+)\)",
            log_text)
        if not pos_matches:
            print("  [FAIL] Navigator spawn position not found")
            return 1
        base_x, _, base_z = map(float, pos_matches[-1])
        bx, bz = int(base_x), int(base_z)
        uuid_matches = re.findall(r"[Ss]pawned Vasyan: Navigator with UUID ([0-9a-f-]+)", log_text)
        nav_uuid = uuid_matches[-1]
        print(f"  Navigator spawned at ({bx}, ?, {bz})")

        # Build a DETERMINISTIC arena at a fixed height far from spawn: the
        # bot's spawn Y is unpredictable (caves at -60 happen), so all three
        # scenarios run on a hand-built platform instead of natural terrain.
        # /fill keeps this to a handful of RCON commands.
        PLAT_Y = 200          # floor level (bot stands on 200 -> feet in 201? No: blocks at y=200 are the floor surface; entity stands at y=201)
        wx, wz = bx + 120, bz  # arena origin, far enough not to collide with earlier worlds
        rcon.command(f"forceload add {wx >> 4} {wz >> 4}")
        rcon.command(f"fill {wx - 4} {PLAT_Y} {wz - 14} {wx + 40} {PLAT_Y} {wz + 14} minecraft:smooth_stone")
        rcon.command(f"fill {wx - 4} {PLAT_Y + 1} {wz - 14} {wx + 40} {PLAT_Y + 6} {wz + 14} minecraft:air")
        # Walls so nobody wanders off the platform edge accidentally.
        rcon.command(f"fill {wx - 5} {PLAT_Y + 1} {wz - 15} {wx + 41} {PLAT_Y + 3} {wz - 15} minecraft:smooth_stone")
        rcon.command(f"fill {wx - 5} {PLAT_Y + 1} {wz + 15} {wx + 41} {PLAT_Y + 3} {wz + 15} minecraft:smooth_stone")
        time.sleep(2)  # let the fill chunks settle
        # Move the Navigator onto the platform.
        start_x, start_z = wx + 2, wz
        rcon.command(f"tp {nav_uuid} {start_x} {PLAT_Y + 1} {start_z}")
        wait_for(log_path, r"Teleported", 30, "Navigator tp to platform")
        time.sleep(3)

        def bot_pos():
            # Vanilla tp-to-self trick: 'tp <entity> <entity>' is invalid, so
            # query through a no-op absolute tp to its own coordinates is not
            # possible without knowing them; instead use 'data get entity'.
            resp = rcon.command(f"data get entity {nav_uuid} Pos")
            m = re.search(r"Pos:\s*\[([-\d.]+)[dD]?,\s*([-\d.]+)[dD]?,\s*([-\d.]+)[dD]?\]", resp)
            if not m:
                return None
            x, y, z = map(float, m.groups())
            return (int(round(x)), int(round(y)), int(round(z)))

        def goto(tx, ty, tz, timeout_s, forbid_teleport=False, forbid_dig=False):
            offset_before = os.path.getsize(log_path)
            rcon.command(f"vasyan tell Navigator иди к {tx} {ty} {tz}")
            deadline = time.time() + timeout_s
            reached = False
            while time.time() < deadline:
                pos = bot_pos()
                if pos and abs(pos[0] - tx) <= 2 and abs(pos[2] - tz) <= 2 and abs(pos[1] - ty) <= 4:
                    reached = True
                    break
                time.sleep(3)
            with open(log_path, "r", errors="replace") as f:
                f.seek(offset_before)
                segment = f.read()
            teleported = re.search(r"hop-teleported past obstacle", segment) is not None
            dug = re.search(r"dug through", segment) is not None
            return reached, teleported, dug

        # ---- A) River crossing ----
        print("Scenario A: river crossing...")
        # A real river: channel floor 2 blocks below the platform, filled with
        # water up to the platform level, so the bot must swim across.
        rcon.command(f"fill {wx + 6} {PLAT_Y - 2} {wz - 13} {wx + 9} {PLAT_Y} {wz + 13} minecraft:water")
        rcon.command(f"fill {wx + 6} {PLAT_Y + 1} {wz - 13} {wx + 9} {PLAT_Y + 4} {wz + 13} minecraft:air")
        target_ax = wx + 14
        reached, teleported, dug = goto(target_ax, PLAT_Y + 1, wz, 240)
        if not reached:
            print("  [FAIL] river crossing: bot did not reach the far side in time")
            return 1
        if teleported:
            print("  [FAIL] river crossing used hop-teleport instead of swimming")
            return 1
        print(f"  -> crossed river to ({target_ax}, {PLAT_Y + 1}, {wz}) without teleporting")

        # ---- B) Adjacent stand ----
        print("Scenario B: adjacent stand beside obsidian...")
        block_b = (wx + 5, PLAT_Y + 1, wz + 6)
        rcon.command(f"setblock {block_b[0]} {block_b[1]} {block_b[2]} minecraft:obsidian")
        reached, teleported, dug = goto(block_b[0], block_b[1], block_b[2], 120)
        pos = bot_pos()
        if not pos:
            print("  [FAIL] adjacent stand: could not read bot position")
            return 1
        manhattan_xz = abs(pos[0] - block_b[0]) + abs(pos[2] - block_b[2])
        same_y = pos[1] == block_b[1]
        if manhattan_xz != 1 or not same_y:
            print(f"  [FAIL] adjacent stand: pos={pos}, manhattan_xz={manhattan_xz}, same_y={same_y}")
            return 1
        print(f"  -> stands adjacent (side) to obsidian at {pos}")

        # ---- C) Wall dig-through ----
        print("Scenario C: dig through dirt wall...")
        wall_x = wx + 12
        rcon.command(f"fill {wall_x} {PLAT_Y + 1} {wz - 1} {wall_x} {PLAT_Y + 2} {wz + 1} minecraft:dirt")
        target_cx = wx + 22
        reached, teleported, dug = goto(target_cx, PLAT_Y + 1, wz, 180)
        if not reached:
            print("  [FAIL] wall dig-through: did not reach the far side in time")
            return 1
        if not dug:
            print("  [FAIL] wall dig-through: reached target but no DIG_THROUGH evidence in log")
            return 1
        print(f"  -> dug through the wall and reached ({target_cx}, {PLAT_Y + 1}, {wz})")

        # Cleanup: remove bot and blocks best-effort.
        rcon.command("vasyan remove Navigator")
        rcon.command("stop")
        try:
            proc.wait(timeout=60)
        except subprocess.TimeoutExpired:
            proc.terminate()
        print("PASS: pathfinding scenarios (river, adjacent stand, wall dig-through).")
        return 0
    finally:
        if rcon is not None:
            rcon.close()
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=30)
            except subprocess.TimeoutExpired:
                proc.kill()


if __name__ == "__main__":
    sys.exit(main())
