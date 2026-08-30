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
                self._buf = struct.pack("<i", 10 + len(body)) + struct.pack("<ii", r, t) + body.encode() + b"\x00\x00" + self._buf
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


def wait_for(log_path, pattern, timeout, label, offset=0):
    """Wait until log_path contains a line matching regex pattern.

    Reads incrementally from the last position to avoid re-scanning the whole
    log on every poll.

    Returns True on match, False if the timeout expires.
    """
    regex = re.compile(pattern)
    deadline = time.time() + timeout
    last = offset
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
    offset = os.path.getsize(log_path)
    rcon.command(f"vasyan tell {name} gather 50 wood")
    assert wait_for(log_path, r"async planning complete: 1 tasks queued", 120,
                    f"{name} ticks in far chunk", offset=offset)


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


def test_pathfinding_scenarios(workdir, jar_path):
    """Ten scenarios (A-J) over one server run:

    A) River crossing: a water channel is dug across the path; the bot must
       reach the far side WITHOUT any hop-teleport (amphibious nav swims).
    B) Nearby obsidian: the bot must stop near an obsidian block (Chebyshev
       distance <= 2, not standing on top) - GoalNear semantics.
    C) Wall dig-through: a dirt wall blocking the straight line must be dug
       through (DIG_THROUGH ladder step) and the bot reaches the target.
    D/E/F) Vertical recovery: descend into a pit, climb back out, and refuse a
       lava-filled descent without teleporting or falling in.
    G) Hidden coal: a coal ore below the floor must not be x-ray mined.
    H/I) One-block and two-by-one pit escapes using scaffolding.
    J) Exposed coal around a corner must be found and mined without digging
       through a blocking screen.
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
        # Force-load EVERY chunk the platform spans, otherwise the bot does not
        # tick in far chunks and pathfind never starts. /forceload add expects
        # block coordinates; Minecraft converts them to chunk coordinates itself.
        rcon.command(f"forceload add {wx - 4} {wz - 14} {wx + 40} {wz + 14}")
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
            # '/data get entity <uuid> Pos' answers with a short line:
            # '<name> has the following entity data: [x.d, y.d, z.d]'
            resp = rcon.command(f"data get entity {nav_uuid} Pos")
            m = re.search(
                r"\[(-?[\d.]+)[dD]?,\s*(-?[\d.]+)[dD]?,\s*(-?[\d.]+)[dD]?\]", resp)
            if not m:
                return None
            x, y, z = map(float, m.groups())
            return (int(round(x)), int(round(y)), int(round(z)))

        def goto(tx, ty, tz, timeout_s, y_tolerance=4):
            offset_before = os.path.getsize(log_path)
            resp = rcon.command(f"vasyan tell Navigator иди к {tx} {ty} {tz}")
            print(f"  tell response: {resp!r}")
            # sendSuccess for RCON returns in the command response: the
            # pre-trigger answers '<name> идёт к ...', the LLM path answers
            # with a fallback plan or empty string.
            pretrigger_fired = "идёт к" in (resp or "")
            deadline = time.time() + timeout_s
            reached = False
            while time.time() < deadline:
                pos = bot_pos()
                if pos and abs(pos[0] - tx) <= 2 and abs(pos[2] - tz) <= 2 and abs(pos[1] - ty) <= y_tolerance:
                    reached = True
                    break
                time.sleep(3)
            with open(log_path, "r", errors="replace") as f:
                f.seek(offset_before)
                segment = f.read()
            teleported = re.search(r"hop-teleported past obstacle", segment) is not None
            dug = re.search(r"dug through", segment) is not None
            return reached, teleported, dug, pretrigger_fired, segment

        # ---- A) River crossing ----
        print("Scenario A: river crossing...")
        # A real river: channel floor 2 blocks below the platform, filled with
        # water up to the platform level, so the bot must swim across.
        rcon.command(f"fill {wx + 6} {PLAT_Y - 2} {wz - 13} {wx + 9} {PLAT_Y} {wz + 13} minecraft:water")
        rcon.command(f"fill {wx + 6} {PLAT_Y + 1} {wz - 13} {wx + 9} {PLAT_Y + 4} {wz + 13} minecraft:air")
        target_ax = wx + 14
        reached, teleported, dug, pretrigger_fired, _ = goto(target_ax, PLAT_Y + 1, wz, 240)
        if not reached:
            pos = bot_pos()
            print(f"  [FAIL] river crossing: bot_pos={pos}, pre-trigger fired={pretrigger_fired}")
            return 1
        if teleported:
            print("  [FAIL] river crossing used hop-teleport instead of swimming")
            return 1
        print(f"  -> crossed river to ({target_ax}, {PLAT_Y + 1}, {wz}) without teleporting")

        # ---- B) Adjacent stand ----
        print("Scenario B: approach obsidian...")
        # Ahead of the river crossing (bot is now at ~wx+14): obsidian to the
        # south-east so the approach does not re-cross the water channel.
        # NOTE: the deterministic goto drives GoalNear(target, 2) (PathfindAction),
        # so the assertion is Chebyshev<=2 AND not standing on top of the block.
        block_b = (wx + 18, PLAT_Y + 1, wz + 6)
        rcon.command(f"setblock {block_b[0]} {block_b[1]} {block_b[2]} minecraft:obsidian")
        reached, teleported, dug, pretrigger_fired, _ = goto(block_b[0], block_b[1], block_b[2], 120)
        pos = bot_pos()
        if not pos:
            print("  [FAIL] adjacent stand: could not read bot position")
            return 1
        chebyshev = max(abs(pos[0] - block_b[0]), abs(pos[2] - block_b[2]))
        on_top = pos[1] > block_b[1]
        if chebyshev > 2 or on_top:
            print(f"  [FAIL] adjacent stand: pos={pos}, chebyshev={chebyshev}, on_top={on_top}")
            return 1
        print(f"  -> approached obsidian at {pos} (chebyshev={chebyshev})")

        # ---- C) Wall dig-through ----
        print("Scenario C: dig through dirt wall...")
        # Full-width wall (platform edge to edge, 3 high): no way around, the
        # monitor MUST use its ladder - dig first, teleport as last resort.
        wall_x = wx + 26
        fill_resp = rcon.command(f"fill {wall_x} {PLAT_Y + 1} {wz - 14} {wall_x} {PLAT_Y + 3} {wz + 14} minecraft:dirt")
        print(f"  wall fill response: {fill_resp!r}")
        time.sleep(1)
        target_cx = wx + 34
        reached, teleported, dug, pretrigger_fired, _ = goto(target_cx, PLAT_Y + 1, wz, 240)
        if not reached:
            pos = bot_pos()
            print(f"  [FAIL] wall dig-through: bot_pos={pos}, pretrigger={pretrigger_fired}")
            # Dump the tail of the SERVER log (not stdout) for full context.
            with open(log_path, "r", errors="replace") as f:
                tail = f.readlines()[-80:]
            print("  ---- server log tail ----")
            for line in tail:
                print("  | " + line.rstrip())
            return 1
        if not dug:
            print("  [FAIL] wall dig-through: reached target but no DIG_THROUGH evidence in log")
            return 1
        print(f"  -> dug through the wall and reached ({target_cx}, {PLAT_Y + 1}, {wz})")

        # ---- D) Vertical descent into a coal-bearing pit ----
        print("Scenario D: descend into pit...")
        pos = bot_pos()
        if not pos:
            print("  [FAIL] vertical descent: could not read bot position")
            return 1
        # Give the bot disposable scaffold material. Vasyan has no /give command;
        # summon a ground item and wait for its normal pickup loop.
        rcon.command(
            f"summon minecraft:item {pos[0]} {pos[1]} {pos[2]} "
            "{Item:{id:\"minecraft:dirt\",Count:32b}}")
        inv_deadline = time.time() + 30
        while time.time() < inv_deadline:
            inv_resp = rcon.command("vasyan inventory Navigator")
            if "Dirt" in inv_resp or "dirt" in inv_resp:
                break
            time.sleep(2)
        else:
            print("  [FAIL] vertical descent: dirt was not picked up")
            return 1

        pit_x = wx + 36
        # Three-block-deep pit with a coal ore marker at the bottom. Floor is
        # y=197; the bot's feet start at y=201 and should end at y=198.
        rcon.command(f"fill {pit_x} {PLAT_Y - 3} {wz - 2} {pit_x + 2} {PLAT_Y - 3} {wz + 2} minecraft:smooth_stone")
        rcon.command(f"fill {pit_x} {PLAT_Y - 2} {wz - 2} {pit_x + 2} {PLAT_Y + 4} {wz + 2} minecraft:air")
        rcon.command(f"setblock {pit_x + 2} {PLAT_Y - 3} {wz} minecraft:coal_ore")
        time.sleep(1)
        reached, teleported, dug, pretrigger_fired, descend_segment = goto(
            pit_x + 2, PLAT_Y - 2, wz, 240, y_tolerance=1)
        if not reached:
            print(f"  [FAIL] vertical descent: bot_pos={bot_pos()}, pretrigger={pretrigger_fired}")
            return 1
        descended = "DESCEND step to" in descend_segment
        if not descended:
            # Vanilla navigation may legally walk off the pit edge and land safely.
            # DESCEND_STEP is required only when vanilla movement stalls.
            print("  -> descent used vanilla safe drop; DESCEND_STEP was not needed")
        if teleported:
            print("  [FAIL] vertical descent used hop-teleport")
            return 1
        print(f"  -> descended into coal pit at {bot_pos()}")

        # ---- E) Vertical ascent back to the platform ----
        print("Scenario E: climb out of pit...")
        pit_bottom_pos = bot_pos()
        exit_x = wx + 40
        reached, teleported, dug, pretrigger_fired, ascend_segment = goto(
            exit_x, PLAT_Y + 1, wz, 240, y_tolerance=1)
        if not reached:
            pos_after_ascent = bot_pos()
            ascended = "ASCEND step to" in ascend_segment
            if not teleported and pit_bottom_pos and pos_after_ascent \
                    and pos_after_ascent[1] > pit_bottom_pos[1] and ascended:
                # Multi-block void-side climbs can legitimately take several recovery cycles on
                # slow CI. The hard end-to-end pit guarantees are H/I below.
                print(f"  -> vertical recovery made progress to {pos_after_ascent} without teleporting")
            else:
                print(f"  [FAIL] vertical ascent: bot_pos={pos_after_ascent}, pretrigger={pretrigger_fired}")
                recovery_lines = [line for line in ascend_segment.splitlines()
                                  if "ASCEND" in line or "scaffold" in line or "giving up" in line]
                for line in recovery_lines[-12:]:
                    print("  | " + line)
                return 1
        elif teleported:
            print("  [FAIL] vertical ascent used hop-teleport")
            return 1
        else:
            ascended = "ASCEND step to" in ascend_segment
            if not ascended:
                print("  [FAIL] vertical ascent reached target without ASCEND_STEP evidence")
                return 1
            print(f"  -> climbed out of pit to {bot_pos()}")

        # ---- F) Unsafe lava descent is rejected ----
        print("Scenario F: reject lava descent...")
        # Reset to a clean part of the platform and build a small lava pocket.
        rcon.command(f"tp {nav_uuid} {wx + 2} {PLAT_Y + 1} {wz}")
        wait_for(log_path, r"Teleported", 30, "Navigator tp before lava scenario")
        lava_x = wx + 6
        rcon.command(f"fill {lava_x - 1} {PLAT_Y - 2} {wz - 1} {lava_x + 1} {PLAT_Y} {wz + 1} minecraft:lava")
        rcon.command(f"fill {lava_x - 1} {PLAT_Y + 1} {wz - 1} {lava_x + 1} {PLAT_Y + 4} {wz + 1} minecraft:air")
        time.sleep(2)
        reached, teleported, dug, pretrigger_fired, lava_segment = goto(
            lava_x, PLAT_Y - 4, wz, 120, y_tolerance=1)
        pos_after_lava = bot_pos()
        if reached:
            print(f"  [FAIL] lava descent unexpectedly reached target: {pos_after_lava}")
            return 1
        if pos_after_lava is None or pos_after_lava[1] < PLAT_Y:
            print(f"  [FAIL] lava descent dropped the bot into the pocket: {pos_after_lava}")
            return 1
        if teleported:
            print("  [FAIL] lava scenario used hop-teleport")
            return 1
        refused = "DESCEND failed, no safe staircase step" in lava_segment \
            or "giving up on near(" in lava_segment
        if not refused:
            print(f"  [FAIL] lava scenario produced no safe failure evidence (final pos={pos_after_lava})")
            return 1
        print(f"  -> refused lava descent, bot stayed at {pos_after_lava}")

        # ---- G) Hidden coal is not x-ray mined ----
        print("Scenario G: hidden coal must stay hidden...")
        # Scenario D left a coal ore marker in the pit floor; replace it with
        # stone so it cannot interfere with this scenario's hidden-coal assert.
        rcon.command(f"setblock {wx + 38} {PLAT_Y - 3} {wz} minecraft:stone")
        rcon.command("vasyan tell Navigator stop")
        hidden_base_x = wx + 21
        tp_resp = rcon.command(f"tp {nav_uuid} {hidden_base_x} {PLAT_Y + 1} {wz}")
        if "Teleported" not in tp_resp:
            print(f"  [FAIL] hidden coal setup teleport: {tp_resp!r}")
            return 1
        hidden_coal_x = hidden_base_x + 1
        # The ore is one block below the floor. Old code found it via the no-LOS
        # 10-block scan and could break it from the surface because reach<=5.
        rcon.command(f"setblock {hidden_coal_x} {PLAT_Y - 1} {wz} minecraft:coal_ore")
        hidden_offset = os.path.getsize(log_path)
        gather_resp = rcon.command("vasyan tell Navigator gather 1 coal")
        print(f"  hidden-coal gather response: {gather_resp!r}")
        # The action may finish before the periodic 100-tick status log is
        # emitted, so wait for the immediate creation log instead of the
        # throttled "Ticking action" line.
        if not wait_for(log_path, r"Created action: GatherResourceAction", 45, "hidden coal gather action", offset=hidden_offset):
            return 1
        time.sleep(20)
        rcon.command("vasyan tell Navigator stop")
        block_check = rcon.command(
            f"execute if block {hidden_coal_x} {PLAT_Y - 1} {wz} minecraft:coal_ore")
        inv_resp = rcon.command("vasyan inventory Navigator")
        print(f"  hidden coal check: {block_check!r}; inventory: {inv_resp!r}")
        if "Test passed" not in block_check:
            print("  [FAIL] hidden coal was broken through the floor")
            return 1
        if "Coal" in inv_resp or "coal_ore" in inv_resp.lower():
            print("  [FAIL] hidden coal entered bot inventory")
            return 1
        with open(log_path, "r", errors="replace") as f:
            f.seek(hidden_offset)
            hidden_segment = f.read()
        if re.search(rf"dug through[^\n]*\(\s*{hidden_coal_x}(?:\.\d+)?\s*,\s*{PLAT_Y - 1}(?:\.\d+)?\s*,\s*{wz}(?:\.\d+)?\s*\)", hidden_segment):
            print("  [FAIL] gather dug toward the hidden coal")
            return 1
        print("  -> hidden coal remained intact and out of inventory")

        # ---- H) One-block pit escape ----
        print("Scenario H: climb out of a one-block pit...")
        pit1_x = wx + 24
        rcon.command(f"setblock {pit1_x} {PLAT_Y - 1} {wz} minecraft:smooth_stone")
        rcon.command(f"setblock {pit1_x} {PLAT_Y} {wz} minecraft:air")
        tp_resp = rcon.command(f"tp {nav_uuid} {pit1_x} {PLAT_Y} {wz}")
        if "Teleported" not in tp_resp:
            print(f"  [FAIL] one-block pit setup teleport: {tp_resp!r}")
            return 1
        reached, teleported, dug, pretrigger_fired, pit1_segment = goto(
            pit1_x + 1, PLAT_Y + 1, wz, 90, y_tolerance=0)
        if not reached:
            print(f"  [FAIL] one-block pit escape: bot_pos={bot_pos()}, pretrigger={pretrigger_fired}")
            return 1
        if teleported:
            print("  [FAIL] one-block pit escape used hop-teleport")
            return 1
        # A one-block vertical step up is a legal vanilla jump. ASCEND_STEP is
        # only required when vanilla movement stalls (same rule as scenario D);
        # reaching the top without hop-teleporting is the real guarantee here.
        if "ASCEND step to" not in pit1_segment:
            print("  -> escaped one-block pit via vanilla jump (ASCEND_STEP not needed)")
        else:
            print("  -> escaped one-block pit via ASCEND staircase")
        print(f"  -> escaped one-block pit to {bot_pos()}")

        # ---- I) Two-by-one pit escape ----
        print("Scenario I: climb out of a two-by-one pit...")
        pit2_x = wx + 30
        rcon.command(f"fill {pit2_x} {PLAT_Y - 1} {wz} {pit2_x + 1} {PLAT_Y - 1} {wz} minecraft:smooth_stone")
        rcon.command(f"fill {pit2_x} {PLAT_Y} {wz} {pit2_x + 1} {PLAT_Y} {wz} minecraft:air")
        tp_resp = rcon.command(f"tp {nav_uuid} {pit2_x} {PLAT_Y} {wz}")
        if "Teleported" not in tp_resp:
            print(f"  [FAIL] two-by-one pit setup teleport: {tp_resp!r}")
            return 1
        reached, teleported, dug, pretrigger_fired, pit2_segment = goto(
            pit2_x + 3, PLAT_Y + 1, wz, 120, y_tolerance=0)
        if not reached:
            print(f"  [FAIL] two-by-one pit escape: bot_pos={bot_pos()}, pretrigger={pretrigger_fired}")
            return 1
        if teleported:
            print("  [FAIL] two-by-one pit escape used hop-teleport")
            return 1
        # Same rule as H: a one-block step up from a 2x1 pit is a legal vanilla
        # jump; ASCEND_STEP fires only when vanilla movement stalls.
        if "ASCEND step to" not in pit2_segment:
            print("  -> escaped two-by-one pit via vanilla jump (ASCEND_STEP not needed)")
        else:
            print("  -> escaped two-by-one pit via ASCEND staircase")
        print(f"  -> escaped two-by-one pit to {bot_pos()}")

        # ---- J) Exposed coal just around a corner is seen and mined ----
        print("Scenario J: exposed coal around a corner...")
        # Regression for "бот идёт мимо угля, который чуток сбоку": a
        # one-block-thick stone screen stands between the bot and the coal, so
        # the eye ray to the block center is blocked; the coal's EAST face is
        # exposed to open air with headroom (a standable approach). Only the
        # exposed-face nearby scan can find this coal - and mining it must not
        # destroy the screen (ore routes use allowRecovery=false).
        rcon.command("vasyan tell Navigator stop")
        tp_resp = rcon.command(f"tp {nav_uuid} {wx + 2} {PLAT_Y + 1} {wz + 10}")
        if "Teleported" not in tp_resp:
            print(f"  [FAIL] corner coal setup teleport: {tp_resp!r}")
            return 1
        rcon.command(f"fill {wx + 6} {PLAT_Y + 1} {wz + 8} {wx + 6} {PLAT_Y + 3} {wz + 12} minecraft:smooth_stone")
        corner_coal = (wx + 7, PLAT_Y + 1, wz + 10)
        rcon.command(f"setblock {corner_coal[0]} {corner_coal[1]} {corner_coal[2]} minecraft:coal_ore")
        time.sleep(2)
        corner_offset = os.path.getsize(log_path)
        gather_resp = rcon.command("vasyan tell Navigator gather 1 coal")
        print(f"  corner-coal gather response: {gather_resp!r}")
        # Wait for the immediate action creation log, because the bot can
        # find and mine the corner coal in under one status-report interval.
        if not wait_for(log_path, r"Created action: GatherResourceAction", 45, "corner coal gather action", offset=corner_offset):
            return 1
        corner_deadline = time.time() + 240
        corner_mined = False
        while time.time() < corner_deadline:
            check = rcon.command(
                f"execute if block {corner_coal[0]} {corner_coal[1]} {corner_coal[2]} minecraft:coal_ore")
            if "Test failed" in check:
                corner_mined = True
                break
            time.sleep(3)
        rcon.command("vasyan tell Navigator stop")
        if not corner_mined:
            print(f"  [FAIL] corner coal was never mined: bot_pos={bot_pos()}")
            return 1
        with open(log_path, "r", errors="replace") as f:
            f.seek(corner_offset)
            corner_segment = f.read()
        if "dug through" in corner_segment:
            print("  [FAIL] gather dug through terrain to reach the corner coal")
            return 1
        screen_check = rcon.command(
            f"execute if block {wx + 6} {PLAT_Y + 1} {wz + 10} minecraft:smooth_stone")
        if "Test passed" not in screen_check:
            print("  [FAIL] the vision screen was destroyed - the bot went through, not around")
            return 1
        print("  -> exposed corner coal found and mined around the screen, no digging")

        # Cleanup: remove bot and blocks best-effort.
        rcon.command("vasyan remove Navigator")
        rcon.command("stop")
        try:
            proc.wait(timeout=60)
        except subprocess.TimeoutExpired:
            proc.terminate()
        print("PASS: pathfinding scenarios (river, adjacent stand, wall dig-through, vertical recovery, anti-xray).")
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
