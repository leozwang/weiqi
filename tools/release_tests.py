#!/usr/bin/env python3
"""Automated release test pass for the weiqi Android app. The test plan is doc/test_plan.md.

Drives a connected phone over adb: taps UI elements by their text (uiautomator), reads the Go board
from screenshots, and checks the engine and billing log (logcat). It never completes a purchase.
Step b1 checks the release build itself on the computer (no phone needed).

  python3 tools/release_tests.py --out /tmp/release-test           # every step, premium auto-detected
  python3 tools/release_tests.py --out /tmp/release-test t1 t2     # selected steps
  python3 tools/release_tests.py --out /tmp/release-test b1        # the build only, no phone
  python3 tools/release_tests.py --list

The app follows the phone's rotation, so the runner pins portrait for the run (T20 and T21 turn the
screen themselves) and restores the phone's rotation settings at the end.

Requirements: adb, Python 3 with numpy and Pillow, ffmpeg (step t1), Android SDK build-tools with aapt2
and dexdump (step b1; found through $ANDROID_HOME).
"""
import argparse
import io
import json
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

import numpy as np
from PIL import Image

PKG = "com.cwave.weiqi"
ACT = f"{PKG}/{PKG}.MainActivity"
COLS = "ABCDEFGHJKLMNOPQRST"  # GTP columns skip "I"
LEVELS = {"Easy": 100, "Amateur": 500, "Advanced": 1000, "Pro": 2500}
LEVEL_LABELS = list(LEVELS)
HANDICAP_LABELS = ["None"] + [str(h) for h in range(1, 10)]
MODES = ["You are Black", "You are White", "Two Players", "AI vs AI"]
LOG_TAGS = ("KataGoBridge", "KataGoBackend", "GameFragment", "KataGoEngine", "BillingManager",
            "AndroidRuntime", "KataGoLatency", "OpenCLProxy")
SCORE_RX = re.compile(r"^[BW]\+\d+\.5$")  # with a half-point komi every result ends in .5
# Standard handicap stones (same as the iOS EngineTests): 1 stone is Q16, White moves first.
_CORNERS = {"D4", "Q16", "D16", "Q4"}
HANDICAP_STONES = {
    1: {"Q16"}, 2: {"D4", "Q16"}, 3: {"D4", "Q16", "D16"}, 4: _CORNERS, 5: _CORNERS | {"K10"},
    6: _CORNERS | {"D10", "Q10"}, 7: _CORNERS | {"D10", "Q10", "K10"},
    8: _CORNERS | {"D10", "Q10", "K16", "K4"}, 9: _CORNERS | {"D10", "Q10", "K16", "K4", "K10"},
}

ARGS = None
OUT = None
RESULTS = []
PREMIUM = None  # True / False once known
NG_NODES = None
TURN_AUDIT = []  # out-of-turn moves seen in any step (T19)


def gtp(c, r):
    """Board point (column 0-18 from the left, row 0-18 from the top) as a GTP coordinate."""
    return f"{COLS[c]}{19 - r}"


def cr(coord):
    """GTP coordinate to (column, row)."""
    return COLS.index(coord[0].upper()), 19 - int(coord[1:])


# ------------------------------------------------------------------ adb helpers
def adb(*args, timeout=180, binary=False):
    cmd = ["adb"] + (["-s", ARGS.serial] if ARGS and ARGS.serial else []) + list(args)
    r = subprocess.run(cmd, capture_output=True, timeout=timeout)
    return r.stdout if binary else r.stdout.decode("utf-8", "replace")


def adb_cmd(*args):
    return ["adb"] + (["-s", ARGS.serial] if ARGS and ARGS.serial else []) + list(args)


def sh(cmd, timeout=180):
    return adb("shell", cmd, timeout=timeout)


def now_dev():
    """Device time in logcat's -T format ('MM-DD HH:MM:SS.mmm'), used as a log marker."""
    return sh("date +'%m-%d %H:%M:%S.%N'").strip()[:18]


def logs(since):
    out = adb("logcat", "-d", "-v", "time", "-T", since)
    return [l for l in out.splitlines() if any(t in l for t in LOG_TAGS)]


def grep_logs(pattern, since):
    rx = re.compile(pattern)
    return [l for l in logs(since) if rx.search(l)]


def wait_log(pattern, since, timeout=60, interval=1.0):
    t0 = time.time()
    while time.time() - t0 < timeout:
        hits = grep_logs(pattern, since)
        if hits:
            return hits[0]
        time.sleep(interval)
    return None


def wait_count(pattern, since, n, timeout=120):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if len(grep_logs(pattern, since)) >= n:
            return True
        time.sleep(0.7)
    return False


def pid():
    return sh(f"pidof {PKG}").strip()


def focus():
    for l in sh("dumpsys window").splitlines():
        if "mCurrentFocus" in l and "null" not in l:
            return l.strip()
    return ""


def screen_size():
    out = sh("wm size")
    m = re.findall(r"(\d+)x(\d+)", out)
    w, h = map(int, m[-1])  # an override size, if present, is listed last
    return w, h


DISPLAY_ID = None  # physical display to capture on phones with several displays (e.g. foldables)


def display_id():
    """'' on single-display phones; otherwise the display whose screenshot matches `wm size`
    (the main screen of a foldable, not its cover screen)."""
    global DISPLAY_ID
    if DISPLAY_ID is None:
        DISPLAY_ID = ""
        ids = re.findall(r"Display (\d+)", sh("dumpsys SurfaceFlinger --display-id"))
        if len(ids) > 1:
            size = screen_size()
            for d in ids:
                try:
                    im = Image.open(io.BytesIO(adb("exec-out", "screencap", "-p", "-d", d, binary=True)))
                    if im.size == size:
                        DISPLAY_ID = d
                        break
                except Exception:
                    pass
    return DISPLAY_ID


def act(msg):
    with open(os.path.join(OUT, "actions.log"), "a") as f:
        f.write(f"{now_dev()} {msg}\n")


def ensure_foreground():
    """Never send input to another app: if the game is not focused, log it and bring it back."""
    f = focus()
    if PKG in f or "vending" in f.lower():
        return True
    act(f"NOT FOREGROUND ({f[-90:]}); bringing the app back")
    sh(f"am start -n {ACT}")
    time.sleep(3)
    return False


def tap(x, y):
    ensure_foreground()
    act(f"tap {int(x)},{int(y)}")
    sh(f"input tap {int(x)} {int(y)}")


def swipe(x1, y1, x2, y2, ms=300):
    ensure_foreground()
    act(f"swipe {int(x1)},{int(y1)} -> {int(x2)},{int(y2)}")
    sh(f"input swipe {int(x1)} {int(y1)} {int(x2)} {int(y2)} {ms}")


def back():
    act("BACK")
    sh("input keyevent 4")


# ------------------------------------------------------------------ UI tree
def dump(retries=4):
    """UI nodes with label, bounds, center, and the enabled state of the nearest clickable ancestor
    (Compose reports a button's enabled state on the clickable node, not on its text)."""
    root = None
    for _ in range(retries):
        sh("uiautomator dump /sdcard/rt_ui.xml", timeout=40)
        xml = sh("cat /sdcard/rt_ui.xml")
        if "<hierarchy" in xml:
            try:
                root = ET.fromstring(xml[xml.index("<?xml"):] if "<?xml" in xml else xml)
                break
            except ET.ParseError:
                pass
        time.sleep(1.0)
    if root is None:
        return []
    parent = {c: p for p in root.iter() for c in p}
    nodes = []
    for n in root.iter("node"):
        label = n.get("text") or n.get("content-desc") or ""
        b = list(map(int, re.findall(r"-?\d+", n.get("bounds", "[0,0][0,0]"))))
        enabled, cur = n.get("enabled") == "true", n
        while cur is not None and cur.tag == "node":
            if cur.get("clickable") == "true":
                enabled = cur.get("enabled") == "true"
                break
            cur = parent.get(cur)
        nodes.append({"label": label, "bounds": b, "enabled": enabled, "checked": n.get("checked") == "true",
                      "center": ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)})
    return nodes


def find(label, nodes):
    return next((n for n in nodes if n["label"] == label), None)


def labels(nodes):
    return {n["label"] for n in nodes if n["label"]}


def is_enabled(label, nodes):
    n = find(label, nodes)
    return n is not None and n["enabled"]


def tap_label(label, nodes=None, wait=0.7):
    nodes = nodes if nodes is not None else dump()
    n = find(label, nodes)
    if n is None:
        visible = sorted({x["label"] for x in nodes if x["label"]})[:40]
        raise RuntimeError(f"UI element '{label}' not found; visible: {visible}")
    tap(*n["center"])
    time.sleep(wait)
    return n


# ------------------------------------------------------------------ screen and board
def screenshot(name):
    d = display_id()
    data = adb("exec-out", "screencap", "-p", *(["-d", d] if d else []), binary=True)
    p = os.path.join(OUT, name + ".png")
    with open(p, "wb") as f:
        f.write(data)
    return np.asarray(Image.open(p).convert("RGB")).astype(int)


def board_mask(img):
    """Pixels of the board's wood color (0xFFCC66)."""
    return (img[..., 0] > 225) & (img[..., 1] > 170) & (img[..., 1] < 235) & (img[..., 2] < 150)


def board_rect(img):
    """(x0, y0, size) of the board square, wherever the layout puts it (under the status card in portrait,
    beside the panel in landscape). The board's 3% margin always shows an unbroken strip of wood color
    above the first grid line (stones never reach the edge), so the first long run of wood color from
    the top is the board's top edge, and its length is the board's size."""
    h, w, _ = img.shape
    mask = board_mask(img)
    min_run = 0.3 * min(w, h)
    for y in range(h):
        row = mask[y]
        if row.sum() < min_run:
            continue
        edges = np.diff(np.concatenate(([0], row.astype(np.int8), [0])))
        starts, ends = np.where(edges == 1)[0], np.where(edges == -1)[0]
        i = int((ends - starts).argmax())
        if ends[i] - starts[i] >= min_run:
            return int(starts[i]), y, int(ends[i] - starts[i])
    raise RuntimeError("board not found on screen (covered by a dialog?)")


def board_geom(img):
    """(x0, y0, step) of the 19x19 grid: the first line is 3% of the board's size in from its edge."""
    x, y, size = board_rect(img)
    margin = size * 0.03
    step = (size - 2 * margin) / 18
    return x + margin, y + margin, step


def point(c, r, geom):
    x0, y0, step = geom
    return x0 + c * step, y0 + r * step


def read_board(img):
    """{(col, row): 'B'|'W'} sampled just off each intersection, inside a stone's radius."""
    geom = board_geom(img)
    stones = {}
    for r in range(19):
        for c in range(19):
            x, y = point(c, r, geom)
            patch = img[int(y - 14):int(y - 8), int(x + 8):int(x + 14)].reshape(-1, 3).mean(0)
            if patch.max() < 90:
                stones[(c, r)] = "B"
            elif patch[0] > 200 and patch[1] > 200 and patch[2] > 210:  # not the translucent preview stone
                stones[(c, r)] = "W"
    return stones


def count(stones, color):
    return sum(1 for v in stones.values() if v == color)


# ------------------------------------------------------------------ rotation and system bars
ROTATION_SAVED = None  # the phone's (auto-rotate, rotation) settings before the run
WM_OVERRIDDEN = False  # True while T20 simulates a tablet with `wm size` / `wm density`


def set_rotation(r):
    """Turn the screen: 0 portrait, 1 landscape, 2 upside down, 3 reverse landscape (auto-rotate off).
    The app no longer locks portrait, so the runner pins the rotation itself."""
    sh("settings put system accelerometer_rotation 0")
    sh(f"settings put system user_rotation {r}")
    time.sleep(3.0)


def lock_portrait():
    """Pin portrait for the run (most steps tap portrait coordinates); remember the user's settings."""
    global ROTATION_SAVED
    if ROTATION_SAVED is None:
        ROTATION_SAVED = (sh("settings get system accelerometer_rotation").strip(),
                          sh("settings get system user_rotation").strip())
    set_rotation(0)


def restore_phone():
    """Undo what the runner changed on the phone: simulated screen size, rotation settings."""
    global WM_OVERRIDDEN
    if WM_OVERRIDDEN:
        sh("wm size reset")
        sh("wm density reset")
        WM_OVERRIDDEN = False
    if ROTATION_SAVED:
        acc, rot = ROTATION_SAVED
        if rot.isdigit():
            sh(f"settings put system user_rotation {rot}")
        if acc.isdigit():
            sh(f"settings put system accelerometer_rotation {acc}")


def system_bars():
    """Screen rectangles (left, top, right, bottom) of the status bar, navigation bar and display cutout
    in the current rotation, from the window manager's inset sources."""
    bars = {}
    rx = (r"InsetsSource id=\S+ type=(statusBars|navigationBars|displayCutout) "
          r"frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\] visible=(\w+)")
    for m in re.finditer(rx, sh("dumpsys window")):
        box = tuple(int(g) for g in m.groups()[1:5])
        if m.group(1) not in bars and m.group(6) == "true" and box[2] > box[0] and box[3] > box[1]:
            bars[m.group(1)] = box
    return bars


def overlaps(a, b):
    return a[0] < b[2] and b[0] < a[2] and a[1] < b[3] and b[1] < a[3]


# The labeled controls of the game screen (Two Players / against the AI), in both layouts.
CONTROLS = ["围棋 碁 GO!", "Toggle AI Analysis", "PASS", "PLACE", "Undo", "NEW GAME", "Redo"]


def layout_check(case, label, img, nodes):
    """The board and every control are entirely on screen, clear of the system bars and the display cutout,
    and no control covers the board. Returns the board's rectangle."""
    h, w, _ = img.shape
    bars = system_bars()
    x, y, size = board_rect(img)
    board = (x, y, x + size, y + size)
    missing = [c for c in CONTROLS if find(c, nodes) is None]
    problems = [f"board under the {name} {bar}" for name, bar in bars.items() if overlaps(board, bar)]
    if board[2] > w or board[3] > h:
        problems.append(f"board off screen {board}")
    for c in CONTROLS:
        n = find(c, nodes)
        if n is None:
            continue
        b = tuple(n["bounds"])
        if b[0] < 0 or b[1] < 0 or b[2] > w or b[3] > h:
            problems.append(f"{c} off screen {b}")
        problems += [f"{c} under the {name}" for name, bar in bars.items() if overlaps(b, bar)]
        if overlaps(b, board):
            problems.append(f"{c} on the board")
    check(case, f"{label}: board and controls on screen, clear of the system bars and camera cutout",
          not missing and not problems, f"missing={missing} problems={problems[:4]} bars={bars}")
    return board


def bar_colors(img):
    """(share of the app's purple in the status bar, mean brightness behind the navigation bar)."""
    bars = system_bars()
    purple_share = brightness = None
    if "statusBars" in bars:
        l, t, r, b = bars["statusBars"]
        purple_share = float(purple(img[t:b, l:r]).mean())
    if "navigationBars" in bars:
        l, t, r, b = bars["navigationBars"]
        brightness = float(img[t:b, l:r].mean())
    return purple_share, brightness


# ------------------------------------------------------------------ results
def check(case, name, ok, detail=""):
    RESULTS.append({"premium": "on" if PREMIUM else ("off" if PREMIUM is False else "?"), "case": case,
                    "check": name, "pass": bool(ok), "detail": str(detail)[:300]})
    with open(os.path.join(OUT, "results.json"), "w") as f:
        json.dump(RESULTS, f, indent=1)
    suffix = f"  {detail}" if not ok and detail else ""
    print(f"  [{'PASS' if ok else 'FAIL'}] {case}: {name}{suffix}", flush=True)
    return ok


def write_summary():
    model = sh("getprop ro.product.model").strip()
    version = re.findall(r"versionName=(\S+)", sh(f"dumpsys package {PKG}"))
    passed = sum(1 for r in RESULTS if r["pass"])
    lines = [f"# Release test results", "",
             f"Device: {model}; app version: {version[0] if version else '?'}; "
             f"premium: {'on' if PREMIUM else 'off' if PREMIUM is False else 'unknown'}", "",
             f"**{passed} of {len(RESULTS)} checks passed.**", "",
             "| Case | Check | Result | Detail |", "|---|---|---|---|"]
    for r in RESULTS:
        detail = "" if r["pass"] else r["detail"].replace("|", "/")
        lines.append(f"| {r['case']} | {r['check']} | {'Pass' if r['pass'] else '**Fail**'} | {detail} |")
    with open(os.path.join(OUT, "summary.md"), "w") as f:
        f.write("\n".join(lines) + "\n")
    return passed == len(RESULTS)


# ------------------------------------------------------------------ app flows
def launch_and_wait_engine(timeout=600):
    m = now_dev()
    sh(f"am start -n {ACT}")
    line = wait_log(r"Engine Initialization Successful|FATAL|Engine Init Result: -", m, timeout=timeout, interval=2)
    return m, line


def force_stop():
    sh(f"am force-stop {PKG}")
    time.sleep(1)


def in_dialog(nodes):
    return find("START", nodes) is not None


def open_new_game():
    nodes = dump()
    if not in_dialog(nodes):
        tap_label("NEW GAME", nodes, wait=1.2)
        nodes = dump()
    if not in_dialog(nodes):
        raise RuntimeError("New Game dialog did not open")
    return nodes


def reveal_in_row(label, anchor_labels, nodes, max_swipes=3):
    """Swipe a horizontally scrolling row of the New Game dialog until 'label' is fully on screen.
    Returns (node or None, current nodes)."""
    w, _ = screen_size()
    n = find(label, nodes)
    if n is not None and 0.15 * w < n["center"][0] < 0.83 * w:
        return n, nodes
    anchor = next((find(a, nodes) for a in anchor_labels if find(a, nodes)), None)
    if anchor is None:
        return None, nodes
    y = anchor["center"][1]
    for direction in (1, -1):  # first towards the end of the row, then back
        for _ in range(max_swipes):
            if direction > 0:
                swipe(0.76 * w, y, 0.31 * w, y, 400)
            else:
                swipe(0.31 * w, y, 0.76 * w, y, 400)
            time.sleep(0.8)
            nodes = dump()
            n = find(label, nodes)
            if n is not None and 0.15 * w < n["center"][0] < 0.83 * w:
                return n, nodes
    return None, nodes


def row_select(label, anchor_labels, nodes, max_swipes=3):
    """Tap 'label' in a horizontally scrolling row of the New Game dialog, swiping the row if needed."""
    n, _ = reveal_in_row(label, anchor_labels, nodes, max_swipes)
    if n is None:
        return False
    tap(*n["center"])
    time.sleep(0.5)
    return True


def purple(px):
    """Mask of the theme's primary purple (selected chips and radio buttons)."""
    return (px[..., 2] > 150) & (px[..., 0] < 170) & (px[..., 1] < 100)


def chip_selected(img, node):
    """A selected New Game chip is filled with the theme's purple: sample just above its label."""
    x0, y0, x1, _ = node["bounds"]
    strip = img[max(0, y0 - 14):max(1, y0 - 6), x0:x1]
    return strip.size > 0 and float(purple(strip).mean()) > 0.5


def mode_selected(mode, nodes, img):
    """Whether the PLAY AS row 'mode' has its radio button selected."""
    n = find(mode, nodes)
    if n is None:
        return False
    y = n["center"][1]
    if any(m["checked"] for m in nodes):  # the phone reports the radio state: use it
        return any(m["checked"] and abs(m["center"][1] - y) < 80 for m in nodes)
    w = img.shape[1]
    band = img[max(0, y - 30):y + 30, int(0.75 * w):int(0.97 * w)]
    return int(purple(band).sum()) > 40


def dialog_selection(nodes=None):
    """(level, handicap label, mode) selected in the open New Game dialog."""
    nodes = nodes if nodes is not None else dump()
    img = screenshot("dialog_selection")
    found = {}
    for key, options in (("level", LEVEL_LABELS), ("handicap", HANDICAP_LABELS)):
        for opt in options:
            before = nodes
            n, nodes = reveal_in_row(opt, options, nodes)
            if nodes is not before:  # the row was swiped: take a fresh screenshot
                img = screenshot("dialog_selection")
            if n is not None and chip_selected(img, n):
                found[key] = opt
                break
    found["mode"] = next((m for m in MODES if mode_selected(m, nodes, img)), None)
    if found["mode"] is None:  # the selected row may be below the fold
        _, h = screen_size()
        swipe(540, 0.62 * h, 540, 0.37 * h, 400)
        time.sleep(0.8)
        nodes = dump()
        img = screenshot("dialog_selection")
        found["mode"] = next((m for m in MODES if mode_selected(m, nodes, img)), None)
    return found.get("level"), found.get("handicap"), found["mode"]


def select_mode(mode):
    """Tap a PLAY AS option, scrolling the dialog if it is hidden below the fixed footer
    (the premium banner pushes the options down on some phones)."""
    _, h = screen_size()
    nodes = dump()
    n = find(mode, nodes)
    tries = 0
    while (n is None or n["center"][1] > 0.76 * h) and tries < 3:
        swipe(540, 0.62 * h, 540, 0.37 * h, 400)
        time.sleep(0.8)
        nodes = dump()
        n = find(mode, nodes)
        tries += 1
    if n is None:
        raise RuntimeError(f"mode '{mode}' not found")
    tap(*n["center"])
    time.sleep(0.5)


def new_game(level, handicap, mode):
    """Configure and START a game in the New Game dialog. Returns a log marker taken just before START."""
    nodes = open_new_game()
    if not row_select(level, LEVEL_LABELS, nodes):
        raise RuntimeError(f"could not select level {level}")
    hlabel = "None" if handicap == 0 else str(handicap)
    if not row_select(hlabel, HANDICAP_LABELS, dump()):
        raise RuntimeError(f"could not select handicap {handicap}")
    select_mode(mode)
    m = now_dev()
    tap_label("START", dump(), wait=1.0)
    wait_log(r"Komi updated to", m, timeout=20)
    return m


def place(c, r, geom):
    x, y = point(c, r, geom)
    tap(x, y)
    time.sleep(0.6)
    m = now_dev()
    tap_label("PLACE", dump(), wait=0.3)
    return m


def ai_colors(since):
    return [re.search(r"for (black|white)", l).group(1)
            for l in grep_logs(r"Starting AI search \(genmove\) for", since)]


def plays(since):
    out = []
    for l in grep_logs(r"AI play: color=", since):
        mm = re.search(r"color=(\d), move=(\S+), rootPla=(\d)", l)
        if mm:
            out.append((int(mm.group(1)), mm.group(2), int(mm.group(3))))
    return out


def visits_set(since):
    return [int(x) for x in re.findall(r"AI max visits updated to (\d+)", "\n".join(logs(since)))]


def search_stats(since):
    """[(ms, visits of the chosen move, root visits)] of every AI move since the marker."""
    out = []
    for l in grep_logs(r"AI search completed in", since):
        mm = re.search(r"in (\d+) ms\. Move: \S+, visits: (\d+), root visits: (\d+)", l)
        if mm:
            out.append(tuple(int(g) for g in mm.groups()))
    return out


def refused(since):
    """Moves the engine refused (illegal, or not that color's turn)."""
    return grep_logs(r"AI play refused|Refused: not", since)


def wait_game_over(timeout=150):
    """Waits for the result dialog after the second pass. Returns (nodes, score, 'Counting score…' seen)."""
    counting, t0 = False, time.time()
    while time.time() - t0 < timeout:
        nodes = dump(retries=2)
        if find("Counting score…", nodes):
            counting = True
        if find("BACK TO BOARD", nodes):
            score = next((n["label"] for n in nodes if re.match(r"^([BW]\+\d+(\.\d+)?|0)$", n["label"])), None)
            return nodes, score, counting
        time.sleep(0.3)
    return None, None, counting


def run_taps(taps):
    """Many taps in one adb call (much faster than one call per tap). taps = [(x, y, pause_after_s)]."""
    ensure_foreground()
    act(f"batch of {len(taps)} taps: " + " ".join(f"{int(x)},{int(y)}" for x, y, _ in taps))
    sh("; ".join(f"input tap {int(x)} {int(y)}; sleep {p}" for x, y, p in taps), timeout=900)


def diagonal_game(first, preplaced=frozenset()):
    """The finished game of the iOS score tests. Black owns every point with x + y >= 18 (190 points),
    White every point with x + y <= 17 (171). Each side is one group with two one-point eyes, filled from
    its own corner outwards, so nothing is ever captured or in doubt. Moves alternate; a side with no
    stones left passes; two passes end the game. Returns [(color, GTP move or 'pass')]."""
    def points(sums):
        return [gtp(x, s - x) for s in sums for x in range(19) if 0 <= s - x <= 18]
    black_eyes, white_eyes = {gtp(18, 18), gtp(16, 18)}, {gtp(0, 0), gtp(2, 0)}
    queues = {"black": [p for p in points(range(36, 17, -1)) if p not in black_eyes and p not in preplaced],
              "white": [p for p in points(range(0, 18)) if p not in white_eyes]}
    seq, color = [], first
    while queues["black"] or queues["white"]:
        seq.append((color, queues[color].pop(0) if queues[color] else "pass"))
        color = "white" if color == "black" else "black"
    other = "white" if color == "black" else "black"
    return seq + [(color, "pass"), (other, "pass")]


def play_fast(seq, geom, since, batch=12):
    """Plays [(color, move)] in a Two Players game by tapping, checking after every batch that the engine
    accepted each move. Returns (ok, detail)."""
    nodes = dump()
    place_xy, pass_xy = find("PLACE", nodes)["center"], find("PASS", nodes)["center"]
    sent = 0
    for i in range(0, len(seq), batch):
        chunk = seq[i:i + batch]
        taps = []
        for _, mv in chunk:
            if mv == "pass":
                taps.append((*pass_xy, 0.3))
            else:
                x, y = point(*cr(mv), geom)
                taps += [(x, y, 0.05), (*place_xy, 0.12)]
        run_taps(taps)
        sent += len(chunk)
        if not wait_count(r"AI play: color=", since, sent, timeout=20):
            got = len(grep_logs(r"AI play: color=", since))
            return False, f"the engine got {got} of {sent} moves (batch ending {chunk[-1]})"
        bad = refused(since)
        if bad:
            return False, bad[0][-100:]
    return True, ""


def meminfo():
    heap = gl = None
    for l in sh(f"dumpsys meminfo {PKG}").splitlines():
        parts = l.split()
        if len(parts) > 8 and parts[0] == "Native" and parts[1] == "Heap":
            heap = int(parts[8])
        if len(parts) > 2 and parts[0] == "GL" and parts[1] == "mtrack":
            gl = int(parts[2])
    return heap, gl


def premium_on():
    """Premium state: from --premium, or detected from the New Game dialog (promo banner => locked)."""
    global PREMIUM
    if PREMIUM is None:
        if ARGS.premium != "auto":
            PREMIUM = ARGS.premium == "on"
        else:
            if not pid():
                launch_and_wait_engine()
                time.sleep(2)
            nodes = open_new_game()
            PREMIUM = find("Unlock Premium AI", nodes) is None
            tap_label("CANCEL", nodes, wait=1.0)
        print(f"  premium detected: {'on' if PREMIUM else 'off'}", flush=True)
    return PREMIUM


# ------------------------------------------------------------------ steps
def analyze_tuning_frames(videos):
    """Count app frames with black blocks in the status strip or a mostly dark board.
    The outer 8.5% of the status strip is ignored: some phones record their rounded corners as black."""
    bad, app = 0, 0
    for i, local in enumerate(videos):
        fdir = os.path.join(OUT, f"tune_frames_{i}")
        os.makedirs(fdir, exist_ok=True)
        if not os.listdir(fdir):
            subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", local, "-vf", "fps=5",
                            os.path.join(fdir, "f_%05d.png")])
        for f in sorted(os.listdir(fdir)):
            a = np.asarray(Image.open(os.path.join(fdir, f)).convert("RGB")).astype(int)
            h, w, _ = a.shape
            r, g, b = a[int(0.096 * h), int(0.556 * w)]
            if not (r < 170 and g < 90 and b > 150):
                continue  # not the app's purple top bar: another screen
            app += 1
            status = a[: int(0.062 * h), int(0.085 * w):int(0.915 * w)]
            board = a[int(0.27 * h):int(0.686 * h), int(0.037 * w):int(0.963 * w)]
            if int((status.max(axis=2) < 25).sum()) > 30 or float((board.max(axis=2) < 60).mean()) > 0.30:
                bad += 1
                act(f"T1 flagged frame {fdir}/{f}")
    return bad, app


def t1():
    """T1: first-run GPU tuning (clears app data), recorded and scanned for artifacts."""
    force_stop()
    sh(f"pm clear {PKG}")
    w, h = screen_size()
    vh = int(round(720 * h / w / 16)) * 16
    m = now_dev()
    sh(f"am start -n {ACT}")
    t0, done, videos, seg = time.time(), None, [], 0
    while time.time() - t0 < 600 and done is None:
        remote = f"/sdcard/rt_tune_{seg}.mp4"
        sh(f"rm -f {remote}")
        disp = f"--display-id {display_id()} " if display_id() else ""
        p = subprocess.Popen(adb_cmd("shell", f"screenrecord {disp}--size 720x{vh} --bit-rate 8000000 --time-limit 170 {remote}"))
        while p.poll() is None:
            done = wait_log(r"Engine Initialization Successful|FATAL", m, timeout=5, interval=2)
            if done:
                time.sleep(4)
                sh("pkill -INT screenrecord")  # SIGINT lets screenrecord finish the MP4 file
                try:
                    p.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    p.terminate()
                break
        local = os.path.join(OUT, f"tune_{seg}.mp4")
        adb("pull", remote, local)
        videos.append(local)
        seg += 1
    secs = time.time() - t0
    check("T1", "engine initialized after tuning", done is not None and "Successful" in done, done)
    check("T1", "tuning ran and finished", bool(grep_logs(r"Performing autotuning", m)) and bool(grep_logs(r"Done tuning", m)),
          f"{secs:.0f}s")
    backend = grep_logs(r"VERIFIED RUNTIME BACKEND", m)
    check("T1", "backend verified", bool(backend), backend[-1] if backend else "none")
    bad, app = analyze_tuning_frames(videos)
    check("T1", "no black blocks, lines or triangles in the recording", app > 0 and bad == 0,
          f"{bad} flagged of {app} app frames (see actions.log; watch the video to confirm)")
    # T17: a clean install opens New Game with the first-launch defaults.
    time.sleep(3)
    nodes = dump()
    if in_dialog(nodes):
        sel = dialog_selection(nodes)
        check("T17", "first launch: New Game opens on Amateur, no handicap, You are Black",
              sel == ("Amateur", "None", "You are Black"), f"selected: {sel}")
    else:
        check("T17", "first launch opens the New Game dialog", False, sorted(labels(nodes))[:20])


def t2():
    """T2: normal start with saved tuning."""
    force_stop()
    t0 = time.time()
    m, line = launch_and_wait_engine(timeout=60)
    secs = time.time() - t0
    check("T2", "engine ready after relaunch", line is not None and "Successful" in line, f"{secs:.1f}s")
    check("T2", "loaded saved tuning (no re-tune)", bool(grep_logs(r"Loaded tuning parameters", m))
          and not grep_logs(r"Performing autotuning", m))
    check("T2", "ready within 15 s", secs < 15, f"{secs:.1f}s")
    time.sleep(2)
    img = screenshot("t2_after_start")
    check("T2", "no black blocks after start", int((img[:150].max(axis=2) < 25).sum()) < 30)
    # An update must take effect: reinstalling (as Play does for an update) re-extracts the bundled
    # gtp.cfg instead of keeping the old copy, and keeps the saved GPU tuning.
    if os.path.exists(ARGS.apk):
        out = adb("install", "-r", ARGS.apk, timeout=300)
        m, line = launch_and_wait_engine(timeout=120)
        check("T2", "after an update the app re-extracts its config and keeps its GPU tuning",
              "Success" in out and line is not None and "Successful" in line
              and bool(grep_logs(r"Successfully extracted gtp\.cfg", m)) and not grep_logs(r"Performing autotuning", m),
              f"install={out.strip()[-40:]} extracted={bool(grep_logs(r'Successfully extracted gtp', m))}")
    else:
        check("T2", "update check: APK to reinstall found", False, f"{ARGS.apk} missing (use --apk)")


def purchase_attempt(tap_fn, label):
    """P2/P3: online, a locked item must open the Play purchase sheet; close it, never buy."""
    m = now_dev()
    tap_fn()
    time.sleep(3)
    f = focus()
    sheet = "vending" in f.lower() or "billing" in f.lower()
    blog = grep_logs(r"BillingManager", m)
    check("P2", f"{label}: the Play purchase sheet opens", sheet,
          f"focus={f[-70:]} log={blog[-1][-80:] if blog else None}")
    if sheet:
        back()
        time.sleep(2.5)
        check("P3", f"{label}: closing the sheet logs a cancel", bool(grep_logs(r"User canceled purchase flow", m)))


def t3():
    """T3 difficulty, plus P1-P4 (premium off) or P10 (premium on)."""
    force_stop()
    m0, _ = launch_and_wait_engine(timeout=600)
    time.sleep(2)
    if premium_on():
        nodes = open_new_game()
        locks = [n for n in nodes if n["label"] == "Premium Locked"]
        promo = find("Unlock Premium AI", nodes) is not None
        check("P10", "no lock icons, no promo banner", not locks and not promo, f"locks={len(locks)} promo={promo}")
        for lvl in LEVEL_LABELS:
            m = new_game(lvl, 0, "You are Black")
            v = visits_set(m)
            check("T3", f"{lvl} -> max visits {LEVELS[lvl]}", v and v[-1] == LEVELS[lvl], v)
        return
    check("P1", "billing connects at startup", bool(grep_logs(r"Billing Setup Successful", m0)),
          grep_logs(r"BillingManager", m0)[-1:] or "no billing log")
    nodes = open_new_game()
    check("P1", "promo banner shown", find("Unlock Premium AI", nodes) is not None)
    easy = find("Easy", nodes)
    if easy:  # Advanced is clipped at the row's right edge; scroll it into view before looking for its lock
        w, _ = screen_size()
        swipe(0.65 * w, easy["center"][1], 0.42 * w, easy["center"][1], 500)
        time.sleep(0.8)
    nodes = dump()
    adv = find("Advanced", nodes)
    locks = [n for n in nodes if n["label"] == "Premium Locked"]
    adv_lock = adv is not None and any(adv["bounds"][0] - 120 <= l["center"][0] <= adv["bounds"][2] + 120
                                       and abs(l["center"][1] - adv["center"][1]) < 120 for l in locks)
    check("P1", "Advanced shows a lock", adv_lock)
    tap_label("CANCEL", dump(), wait=1.0)
    for lvl in ["Easy", "Amateur"]:
        m = new_game(lvl, 0, "You are Black")
        v = visits_set(m)
        check("T3", f"{lvl} -> max visits {LEVELS[lvl]}", v and v[-1] == LEVELS[lvl], v)
    for lvl in ["Advanced", "Pro"]:
        nodes = open_new_game()
        purchase_attempt(lambda: row_select(lvl, LEVEL_LABELS, nodes), lvl)
        nodes = dump()
        if lvl == "Pro":
            check("P1", "Pro shows a lock", any(n["label"] == "Premium Locked" for n in nodes))
        if not in_dialog(nodes):
            nodes = open_new_game()
        m = now_dev()
        tap_label("START", nodes, wait=1.0)
        wait_log(r"Komi updated to", m, timeout=20)
        v = visits_set(m)
        check("P2", f"{lvl} not applied without premium (game starts at Amateur 500)", v and v[-1] == 500, v)
    open_new_game()
    swipe(540, 900, 540, 1500, 400)  # the banner is at the top of the dialog
    time.sleep(0.8)
    nodes = dump()
    promo = find("Unlock Premium AI", nodes)
    if promo:
        purchase_attempt(lambda: tap(*promo["center"]), "banner")
    else:
        check("P2", "banner is tappable", False, "banner not found")
    nodes = dump()
    if in_dialog(nodes):
        tap_label("CANCEL", nodes, wait=1.0)
    # P4: billing offline.
    try:
        sh("cmd connectivity airplane-mode enable")
        sh("svc wifi disable")
        sh("svc data disable")
        time.sleep(3)
        force_stop()
        m4, _ = launch_and_wait_engine(timeout=60)
        time.sleep(3)
        nodes = open_new_game()
        p_before = pid()
        row_select("Advanced", LEVEL_LABELS, nodes)
        time.sleep(3)
        sheet = "vending" in focus().lower()
        if sheet:  # Play's billing service is local, so it may still show its sheet offline
            screenshot("p4_offline_sheet")
            back()
            time.sleep(2.5)
        check("P4", "offline: tapping a locked level does not crash the app", pid() == p_before and p_before != "",
              f"sheet_opened={sheet}")
        nodes = dump()
        if not in_dialog(nodes):
            open_new_game()
        swipe(540, 900, 540, 1500, 400)
        time.sleep(0.8)
        nodes = dump()
        check("P4", "offline: premium stays locked (banner still shown)", find("Unlock Premium AI", nodes) is not None)
        tap_label("CANCEL", nodes, wait=1.0)
        m = new_game("Easy", 0, "You are Black")
        v = visits_set(m)
        check("P4", "offline: Easy still works", v and v[-1] == 100, v)
    finally:
        sh("cmd connectivity airplane-mode disable")
        sh("svc wifi enable")
        sh("svc data enable")
        time.sleep(5)


def t4():
    """T4: every handicap level: standard star points, komi, who moves first, and no Undo before a move."""
    # The New Game dialog says who receives the handicap stones and who moves first.
    footers = {"You are Black": "You play Black and receive the handicap stones. The AI (White) moves first.",
               "You are White": "The AI plays Black and receives the handicap stones. You (White) move first.",
               "Two Players": "Black receives the handicap stones. White moves first."}
    open_new_game()
    shown = {}
    _, h = screen_size()
    for mode, text in footers.items():
        select_mode(mode)
        swipe(540, 0.37 * h, 540, 0.62 * h, 400)  # back to the top, where the handicap row is
        time.sleep(0.8)
        row_select("2", HANDICAP_LABELS, dump())
        nodes = dump()
        screenshot(f"t4_new_game_h2_{mode.replace(' ', '_')}")
        shown[mode] = find(text, nodes) is not None
    row_select("None", HANDICAP_LABELS, dump())
    nodes = dump()
    no_line = not any(find(t, nodes) for t in footers.values())
    check("T4", "New Game explains who gets the handicap stones and who moves first (no line without handicap)",
          all(shown.values()) and no_line, f"shown={shown} hidden_at_none={no_line}")
    tap_label("CANCEL", nodes, wait=1.0)
    for h in range(1, 10):
        m = new_game("Easy", h, "You are White")  # White moves first, so nothing happens until you move
        time.sleep(2.0)
        st = read_board(screenshot(f"t4_h{h}"))
        black = {gtp(c, r) for (c, r), v in st.items() if v == "B"}
        nodes = dump()
        check("T4", f"{h}: Black stones on {' '.join(sorted(HANDICAP_STONES[h]))}",
              black == HANDICAP_STONES[h] and count(st, "W") == 0, f"black={sorted(black)} white={count(st, 'W')}")
        check("T4", f"{h}: komi 0.5, White moves first, nothing to undo",
              bool(grep_logs(r"Komi updated to 0\.5", m)) and not ai_colors(m) and is_enabled("PASS", nodes)
              and not is_enabled("Undo", nodes),
              f"ai={ai_colors(m)} pass={is_enabled('PASS', nodes)} undo={is_enabled('Undo', nodes)}")
    m = new_game("Easy", 0, "You are Black")
    time.sleep(2.5)
    st = read_board(screenshot("t4_h0"))
    nodes = dump()
    check("T4", "None: komi 7.5, empty board, Black (you) moves first, nothing to undo",
          bool(grep_logs(r"Komi updated to 7\.5", m)) and not st and not ai_colors(m) and is_enabled("PASS", nodes)
          and not is_enabled("Undo", nodes), f"stones={len(st)} ai={ai_colors(m)}")
    for h in (1, 2, 9):
        m = new_game("Easy", h, "You are Black")
        wait_count(r"AI search completed", m, 1, 60)
        time.sleep(1.5)
        st = read_board(screenshot(f"t4_black_h{h}"))
        nodes = dump()
        check("T4", f"You are Black + {h}: the AI opens as White; Undo stays off until you move",
              ai_colors(m) == ["white"] and count(st, "B") == h and count(st, "W") == 1 and not is_enabled("Undo", nodes),
              f"ai={ai_colors(m)} B={count(st, 'B')} W={count(st, 'W')} undo={is_enabled('Undo', nodes)}")


def t5():
    """T5: every play-as mode."""
    lvl = "Easy"
    m = new_game(lvl, 0, "You are Black")
    time.sleep(2.5)
    check("T5", "You are Black: the AI waits for your first move", not ai_colors(m) and is_enabled("PASS", dump()))
    m = new_game(lvl, 0, "You are White")
    wait_count(r"AI search completed", m, 1, 60)
    time.sleep(1.5)
    st = read_board(screenshot("t5_white_h0"))
    nodes = dump()
    check("T5", "You are White: the AI opens as Black; Undo stays off until you move",
          ai_colors(m) == ["black"] and count(st, "B") == 1 and not is_enabled("Undo", nodes),
          f"ai={ai_colors(m)} undo={is_enabled('Undo', nodes)}")
    m = new_game(lvl, 2, "You are White")
    time.sleep(2.5)
    img = screenshot("t5_white_h2_start")
    st = read_board(img)
    check("T5", "You are White + handicap 2: two black stones, you move first", count(st, "B") == 2 and not ai_colors(m),
          f"B={count(st, 'B')} ai={ai_colors(m)}")
    m2 = place(15, 15, board_geom(img))
    wait_count(r"AI search completed", m2, 1, 60)
    p = plays(m2)
    check("T5", "White + handicap 2: your move goes in as White, AI replies as Black",
          p and p[0][0] == 2 and p[0][2] == 2 and ai_colors(m2) == ["black"], f"plays={p} ai={ai_colors(m2)}")
    m = new_game(lvl, 0, "Two Players")
    time.sleep(1.0)
    geom = board_geom(screenshot("t5_two_start"))
    place(3, 3, geom)
    time.sleep(1.0)
    check("T5", "Two Players: White is a person too (PASS on at White's turn, no AI move)",
          is_enabled("PASS", dump()) and not ai_colors(m))
    place(15, 3, geom)
    time.sleep(1.5)
    st = read_board(screenshot("t5_two_after"))
    p = plays(m)
    check("T5", "Two Players: no AI moves, colors alternate by hand",
          not ai_colors(m) and [x[0] for x in p] == [1, 2] and count(st, "B") == 1 and count(st, "W") == 1,
          f"plays={p} ai={ai_colors(m)}")
    m = new_game(lvl, 0, "AI vs AI")
    ok = wait_count(r"AI search completed", m, 4, 90)
    cols = ai_colors(m)
    check("T5", "AI vs AI: autoplay alternates colors", ok and all(cols[i] != cols[i + 1] for i in range(len(cols) - 1)), cols)
    pause = find("PAUSE", dump())
    if pause:
        tap(*pause["center"])
    else:
        w, h = screen_size()
        tap(0.357 * w, 0.789 * h)
    time.sleep(4)
    n1 = len(grep_logs(r"AI search completed", m))
    time.sleep(4)
    n2 = len(grep_logs(r"AI search completed", m))
    check("T5", "AI vs AI: PAUSE stops play", n1 == n2, f"{n1}->{n2}")
    nodes = dump()
    check("T5", "AI vs AI: Undo and Redo are off", not is_enabled("Undo", nodes) and not is_enabled("Redo", nodes))
    step = find("STEP", nodes)
    if step:
        tap(*step["center"])
        time.sleep(4)
        n3 = len(grep_logs(r"AI search completed", m))
        check("T5", "AI vs AI: STEP plays exactly one move", n3 == n2 + 1, f"{n2}->{n3}")
    else:
        check("T5", "AI vs AI: STEP button present", False)
    # Resume self-play, open New Game while it runs, and start a game: no old move may leak into it.
    m_auto = now_dev()
    tap_label("AUTO", dump(), wait=0.5)
    wait_count(r"AI search completed", m_auto, 2, 60)
    opened = False
    for _ in range(20):
        nodes = dump(retries=2)
        if in_dialog(nodes):
            opened = True
            break
        ng = find("NEW GAME", nodes)
        if ng and ng["enabled"]:
            tap(*ng["center"])
            time.sleep(1.0)
        else:
            time.sleep(0.2)
    check("T5", "AI vs AI: New Game opens during self-play (pausing it)", opened)
    m = new_game(lvl, 0, "You are Black")
    time.sleep(4)
    st = read_board(screenshot("t5_after_ai_vs_ai"))
    check("T5", "a game started from AI vs AI begins clean (empty board, no AI move)", not st and not ai_colors(m),
          f"stones={len(st)} ai={ai_colors(m)}")


def t6_8():
    """T6 moves, T7 pass and game end, T8 undo and redo."""
    global NG_NODES
    new_game("Amateur", 0, "You are Black")
    time.sleep(1.5)
    NG_NODES = dump()
    geom = board_geom(screenshot("t6_start"))
    m1 = place(3, 15, geom)
    busy_img = screenshot("t6_while_thinking")  # NEW GAME must be gray (disabled) while the AI searches
    if not grep_logs(r"AI search completed", m1):
        ng = find("NEW GAME", NG_NODES) if NG_NODES else None
        if ng:
            r_, g_, b_ = busy_img[ng["center"][1], ng["center"][0] - 60]
            check("T6", "NEW GAME disabled while the AI thinks", not (g_ > 150 and r_ < 120), f"pixel={r_, g_, b_}")
    wait_count(r"AI search completed", m1, 1, 90)
    time.sleep(1.2)
    st = read_board(screenshot("t6_after_move"))
    p = plays(m1)
    check("T6", "stone placed where tapped (D4) as Black", st.get((3, 15)) == "B" and p and p[0][1] == "D4" and p[0][0] == 1,
          f"plays={p}")
    check("T6", "AI replied with White", ai_colors(m1) == ["white"] and count(st, "W") == 1, ai_colors(m1))
    x, y = point(3, 15, geom)
    tap(x, y)
    time.sleep(0.6)
    plc = find("PLACE", dump())
    check("T6", "occupied point: PLACE stays disabled", plc is not None and not plc["enabled"])
    m2 = now_dev()
    tap_label("PASS", dump(), wait=0.5)
    wait_count(r"AI search completed", m2, 1, 90)
    time.sleep(1.2)
    p = plays(m2)
    check("T7", "PASS goes in as Black's pass, AI replies as White",
          p and p[0][1] == "pass" and p[0][0] == 1 and ai_colors(m2) == ["white"], f"plays={p} ai={ai_colors(m2)}")
    before = read_board(screenshot("t8_before_undo"))
    m_undo = now_dev()
    tap_label("Undo", dump(), wait=2.5)
    after_undo = read_board(screenshot("t8_after_undo"))
    check("T8", "Undo removes the AI reply together with your move (a pass)",
          count(after_undo, "W") == count(before, "W") - 1 and count(after_undo, "B") == count(before, "B"),
          f"W {count(before, 'W')}->{count(after_undo, 'W')}")
    nodes = dump()
    pas = find("PASS", nodes)
    check("T8", "after Undo it is your turn (PASS enabled)", pas is not None and pas["enabled"])
    time.sleep(2)
    check("T8", "after Undo the AI does not move by itself", not ai_colors(m_undo), ai_colors(m_undo))
    m3 = now_dev()
    tap_label("Redo", dump(), wait=2.5)
    p = plays(m3)
    after_redo = read_board(screenshot("t8_after_redo"))
    check("T8", "Redo replays your pass and the AI reply with alternating colors",
          [x[0] for x in p] == [1, 2] and p[0][1] == "pass" and after_redo == before, f"plays={p}")
    # T7: two passes end the game with a result; BACK TO BOARD keeps the finished game on the board.
    new_game("Easy", 0, "Two Players")
    time.sleep(1)
    tap_label("PASS", dump(), wait=1.0)
    tap_label("PASS", dump(), wait=0.2)
    over, score, _ = wait_game_over()
    check("T7", "two passes end the game with a result ending in .5", over is not None and bool(score) and bool(SCORE_RX.match(score)),
          score)
    if over:
        tap_label("BACK TO BOARD", over, wait=1.2)
        nodes = dump()
        check("T7", "BACK TO BOARD closes the dialog and keeps the result on the board",
              find("BACK TO BOARD", nodes) is None and find(f"Result: {score}", nodes) is not None
              and not is_enabled("PASS", nodes), sorted(labels(nodes))[:15])
    # T8: Two Players: Undo and Redo are one move each.
    m = new_game("Easy", 0, "Two Players")
    time.sleep(1)
    geom = board_geom(screenshot("t8_two_start"))
    place(0, 0, geom)
    time.sleep(0.8)
    place(18, 18, geom)
    time.sleep(1.0)
    tap_label("Undo", dump(), wait=1.5)
    st = read_board(screenshot("t8_two_undo"))
    nodes = dump()
    check("T8", "Two Players: Undo takes back one move (White's)", st == {(0, 0): "B"} and is_enabled("PASS", nodes), st)
    m_r = now_dev()
    tap_label("Redo", nodes, wait=1.5)
    st = read_board(screenshot("t8_two_redo"))
    check("T8", "Two Players: Redo replays one move", st == {(0, 0): "B", (18, 18): "W"} and [x[0] for x in plays(m_r)] == [2],
          f"{st} plays={plays(m_r)}")
    # T8: You are White: the AI opens; Undo returns to your turn and keeps the AI's opening move.
    m = new_game("Easy", 0, "You are White")
    wait_count(r"AI search completed", m, 1, 60)
    time.sleep(1.2)
    img = screenshot("t8_white_open")
    opening = read_board(img)
    geom = board_geom(img)
    free = next(pt for pt in [(2, 2), (16, 16), (2, 16), (16, 2)] if pt not in opening)
    mw = place(free[0], free[1], geom)
    wait_count(r"AI search completed", mw, 1, 60)
    time.sleep(1.2)
    tap_label("Undo", dump(), wait=2.0)
    st = read_board(screenshot("t8_white_undo"))
    nodes = dump()
    check("T8", "You are White: Undo returns to your turn, keeping the AI's opening move",
          st == opening and is_enabled("PASS", nodes) and not is_enabled("Undo", nodes), f"{st} vs {opening}")
    # T8: Undo never removes handicap stones and cannot go past the setup.
    m = new_game("Easy", 2, "You are White")
    time.sleep(1.5)
    img = screenshot("t8_h2_start")
    setup = read_board(img)
    mh = place(15, 15, board_geom(img))
    wait_count(r"AI search completed", mh, 1, 60)
    time.sleep(1.2)
    tap_label("Undo", dump(), wait=2.0)
    st = read_board(screenshot("t8_h2_undo"))
    nodes = dump()
    check("T8", "handicap: Undo keeps the handicap stones and cannot go past them",
          st == setup and count(st, "B") == 2 and is_enabled("PASS", nodes) and not is_enabled("Undo", nodes),
          f"{st} vs {setup}")


def t9():
    """T9: Cancel in the New Game dialog must not change the running game."""
    new_game("Amateur", 0, "You are Black")
    time.sleep(1.5)
    geom = board_geom(screenshot("t9_start"))
    m1 = place(3, 15, geom)
    wait_count(r"AI search completed", m1, 1, 90)
    time.sleep(1.2)
    st0 = read_board(screenshot("t9_before_cancel"))
    nodes = open_new_game()
    row_select("Easy", LEVEL_LABELS, nodes)
    row_select("2", HANDICAP_LABELS, dump())
    select_mode("You are White")
    m4 = now_dev()
    tap_label("CANCEL", dump(), wait=1.2)
    st1 = read_board(screenshot("t9_after_cancel"))
    check("T9", "Cancel leaves the board unchanged", st1 == st0)
    free = next(p for p in [(15, 15), (15, 3), (3, 3), (9, 9), (2, 13), (13, 2), (16, 9)] if p not in st1)
    m5 = place(free[0], free[1], geom)
    wait_count(r"AI search completed", m5, 1, 90)
    p = plays(m5)
    check("T9", "after Cancel you still play Black, the AI White, same difficulty",
          p and p[0][0] == 1 and ai_colors(m5) == ["white"] and not visits_set(m4),
          f"plays={p} ai={ai_colors(m5)} visits={visits_set(m4)}")


def t10():
    """T10: save and restore after a pass, and restore while the AI was thinking."""
    new_game("Easy", 0, "You are Black")
    time.sleep(1.5)
    geom = board_geom(screenshot("t10_start"))
    m1 = place(2, 2, geom)
    wait_count(r"AI search completed", m1, 1, 60)
    time.sleep(1)
    m2 = now_dev()
    tap_label("PASS", dump(), wait=0.5)
    wait_count(r"AI search completed", m2, 1, 60)
    time.sleep(1.5)
    before = read_board(screenshot("t10_before_restart"))
    force_stop()
    m3, _ = launch_and_wait_engine(timeout=60)
    time.sleep(5)
    after = read_board(screenshot("t10_after_restart"))
    p = plays(m3)
    alternating = all(p[i][0] != p[i + 1][0] for i in range(len(p) - 1)) and all(x[0] == x[2] for x in p)
    check("T10", "restore replays the moves with alternating colors, including the pass",
          len(p) == 4 and alternating and any(x[1] == "pass" for x in p), p)
    check("T10", "restored position identical", after == before, f"{len(before)} vs {len(after)} stones")
    check("T10", "no extra AI move after restore on your turn", not ai_colors(m3), ai_colors(m3))
    new_game("Pro" if premium_on() else "Amateur", 0, "You are Black")
    time.sleep(1.5)
    geom = board_geom(screenshot("t10b_start"))
    # Close the app while the AI is still searching. On fast GPUs a search takes about a second, so
    # close right after the move, and retry if the AI still finished first (then the case wasn't set up).
    points = [(3, 3), (15, 15), (15, 3)]
    closed_mid_search = False
    for attempt, (c, r) in enumerate(points):
        if attempt:
            new_game("Pro" if premium_on() else "Amateur", 0, "You are Black")
            time.sleep(1.5)
        mp = place(c, r, geom)
        time.sleep(0.3)
        force_stop()
        closed_mid_search = bool(grep_logs(r"Starting AI search", mp)) and not grep_logs(r"AI search completed", mp)
        if closed_mid_search:
            break
        m_relaunch, _ = launch_and_wait_engine(timeout=60)
        time.sleep(2)
    if not closed_mid_search:
        check("T10", "closed during the AI's turn: the AI moves after restore", False,
              "the AI always finished before the app was closed; case not set up")
        return
    m5, _ = launch_and_wait_engine(timeout=60)
    ok = wait_count(r"AI search completed", m5, 1, 120)
    check("T10", "closed during the AI's turn: the AI moves after restore", ok and ai_colors(m5)[:1] == ["white"],
          ai_colors(m5))


def t11():
    """T11: analysis toggle."""
    new_game("Easy", 0, "You are Black")
    time.sleep(1.5)
    geom = board_geom(screenshot("t11_start"))
    m1 = place(3, 3, geom)
    wait_count(r"AI search completed", m1, 1, 60)
    time.sleep(1)
    m2 = now_dev()
    tap_label("Toggle AI Analysis", dump(), wait=0.5)
    got = wait_log(r"Analysis received", m2, timeout=30)
    time.sleep(1.5)
    img = screenshot("t11_analysis_on")
    x0, y0, step = geom
    board = img[int(y0):int(y0 + 18 * step), int(x0):int(x0 + 18 * step)]
    red = int(((board[..., 0] > 170) & (board[..., 1] < 140) & (board[..., 2] < 140)).sum())
    check("T11", "analysis data received and candidate moves drawn", got is not None and red > 200, f"red_px={red}")
    # Tapping a candidate previews it there; PLACE plays it.
    st = read_board(img)
    best, best_px = None, 0
    for r in range(19):
        for c in range(19):
            if (c, r) in st:
                continue
            x, y = point(c, r, geom)
            box = img[int(y - 0.45 * step):int(y + 0.45 * step), int(x - 0.45 * step):int(x + 0.45 * step)]
            ring = int(((box[..., 0] > 140) & (box[..., 0] < 215) & (box[..., 1] < 70) & (box[..., 2] < 70)).sum())
            if ring > best_px:
                best, best_px = (c, r), ring
    if best:
        m3 = place(best[0], best[1], geom)
        wait_count(r"AI play: color=1", m3, 1, 10)
        p = plays(m3)
        check("T11", f"tapping the best candidate ({gtp(*best)}) and PLACE plays it",
              bool(p) and p[0][1] == gtp(*best) and p[0][0] == 1, f"plays={p[:1]}")
        wait_count(r"AI search completed", m3, 1, 60)
        time.sleep(1)
    else:
        check("T11", "best candidate found on the board", False)
    tap_label("Toggle AI Analysis", dump(), wait=1.0)
    check("T11", "toggled off, app still running", pid() != "")


def t12():
    """T12: stability and memory over about 20 moves."""
    lvl = "Pro" if premium_on() else "Amateur"
    m = new_game(lvl, 0, "You are Black")
    time.sleep(1.5)
    geom = board_geom(screenshot("t12_start"))
    p0 = pid()
    samples, played = [], 0
    for (c, r) in [(c, r) for r in (2, 5, 8, 11, 14, 16) for c in (2, 5, 8, 11, 14, 16)]:
        if played >= 20:
            break
        if (c, r) in read_board(screenshot("t12_current")):
            continue
        mm = place(c, r, geom)
        if not wait_count(r"AI search completed", mm, 1, 120):
            break
        time.sleep(0.8)
        played += 1
        samples.append(meminfo())
    check("T12", f"played {played} moves on {lvl} without a crash", played >= 15 and pid() == p0, f"pid {p0}->{pid()}")
    gls = [s[1] for s in samples if s[1]]
    check("T12", "GPU memory (GL mtrack) flat", bool(gls) and max(gls) - min(gls) < 8 * 1024,
          f"GL {min(gls) if gls else None}..{max(gls) if gls else None} KB")
    heaps = [s[0] for s in samples if s[0]]
    if len(heaps) >= 6:
        first, last = (heaps[2] - heaps[0]) / 2, (heaps[-1] - heaps[-3]) / 2
        check("T12", "native heap growth slows down (bounded cache)", last <= max(first, 1500),
              f"first {first:.0f} KB/move, last {last:.0f} KB/move, {heaps[0]}->{heaps[-1]} KB")
    crashes = grep_logs(r"FATAL EXCEPTION", m)
    check("T12", "no crash in the log", not crashes, crashes[:1])


def t13():
    """T13: a higher level really searches more, and no move uses more visits than its level allows."""
    levels = LEVEL_LABELS if premium_on() else ["Easy", "Amateur"]
    stats = {}
    for lvl in levels:
        stats[lvl] = []
        for (c, r) in [(15, 3), (3, 15)]:  # Q16 and D4, each in a fresh game (no search tree to reuse)
            new_game(lvl, 0, "You are Black")
            time.sleep(1.2)
            mm = place(c, r, board_geom(screenshot("t13_start")))
            wait_count(r"AI search completed", mm, 1, 180)
            stats[lvl] += search_stats(mm)[:1]
        visits = [v for _, v, _ in stats[lvl]]
        check("T13", f"{lvl}: the AI's move used at most {LEVELS[lvl]} visits",
              len(visits) == 2 and max(visits) <= LEVELS[lvl], f"(ms, visits, root visits) = {stats[lvl]}")
    med = {lvl: sorted(v for _, v, _ in stats[lvl])[len(stats[lvl]) // 2] for lvl in levels if stats[lvl]}
    for lo, hi in zip(levels, levels[1:]):
        if lo in med and hi in med:
            check("T13", f"{hi} searches far more than {lo} (more than 1.5x the visits)", med[hi] > 1.5 * med[lo],
                  f"{lo} {med[lo]} visits, {hi} {med[hi]} visits; ms: {stats[lo]} / {stats[hi]}")


def t14():
    """T14: rules (ko, suicide), the illegal-move message, and GTP coordinates."""
    new_game("Easy", 0, "Two Players")
    time.sleep(1)
    geom = board_geom(screenshot("t14_coords_start"))
    m = now_dev()
    for (c, r) in [(0, 0), (8, 9), (18, 18)]:
        place(c, r, geom)
        time.sleep(0.6)
    st = read_board(screenshot("t14_coords"))
    check("T14", "coordinates: taps at the corners and the J column reach the engine as A19, J10, T1",
          [x[1] for x in plays(m)] == ["A19", "J10", "T1"] and st == {(0, 0): "B", (8, 9): "W", (18, 18): "B"},
          f"plays={plays(m)} board={st}")

    # Ko in the top-left corner (the iOS ko test): Black B19 A18 B17, White C19 B18 D18 C17.
    new_game("Easy", 0, "Two Players")
    time.sleep(1)
    geom = board_geom(screenshot("t14_ko_start"))
    m = now_dev()
    for mv in ["B19", "C19", "A18", "B18", "B17", "D18", "K10", "C17", "C18"]:
        place(*cr(mv), geom)
        time.sleep(0.6)
    st = read_board(screenshot("t14_ko_taken"))
    check("T14", "ko: Black C18 captures White B18", st.get(cr("C18")) == "B" and cr("B18") not in st and not refused(m),
          f"refused={refused(m)}")
    mk = place(*cr("B18"), geom)  # White retakes at once
    time.sleep(1.5)
    st2 = read_board(screenshot("t14_ko_retake"))
    check("T14", "ko: White may not retake at once; the board stays the same",
          bool(grep_logs(r"AI play refused: illegal move B18", mk)) and st2 == st, f"refused={refused(mk)}")
    check("T14", "ko: the refused move shows the illegal-move message",
          bool(grep_logs(r"Illegal move refused: white B18", mk)))
    mt = now_dev()
    for mv in ["K4", "Q16", "B18"]:  # White plays a ko threat, Black answers, now White may retake
        place(*cr(mv), geom)
        time.sleep(0.6)
    st3 = read_board(screenshot("t14_ko_after_threat"))
    check("T14", "ko: after a ko threat elsewhere White may retake",
          not refused(mt) and st3.get(cr("B18")) == "W" and cr("C18") not in st3, f"refused={refused(mt)}")

    # Multi-stone suicide (the iOS suicide test): Black A1 is walled in by White A2, B2, C1.
    new_game("Easy", 0, "Two Players")
    time.sleep(1)
    geom = board_geom(screenshot("t14_suicide_start"))
    for mv in ["A1", "A2", "K10", "B2", "K11", "C1"]:
        place(*cr(mv), geom)
        time.sleep(0.6)
    st = read_board(screenshot("t14_suicide_setup"))
    ms = place(*cr("B1"), geom)
    time.sleep(1.5)
    st2 = read_board(screenshot("t14_suicide"))
    check("T14", "Chinese rules: multi-stone suicide (Black B1) is refused; the board stays the same",
          bool(grep_logs(r"AI play refused: illegal move B1\b", ms)) and st2 == st and bool(grep_logs(r"Illegal move refused", ms)),
          f"refused={refused(ms)}")


def t15():
    """T15: exact Chinese area scoring of a finished game (the iOS score tests); scoring keeps the komi."""
    for name, h, first, pre, expected, komi in [
            ("even game", 0, "black", frozenset(), "B+11.5", "7.5"),
            ("handicap 2", 2, "white", frozenset({"D4", "Q16"}), "B+16.5", "0.5")]:
        m = new_game("Easy", h, "Two Players")
        time.sleep(1.5)
        geom = board_geom(screenshot(f"t15_{h}_start"))
        seq = diagonal_game(first, pre)
        t0 = time.time()
        ok, detail = play_fast(seq, geom, m)
        check("T15", f"{name}: all {len(seq)} moves and passes played by tapping", ok, detail)
        if not ok:
            continue
        over, score, _ = wait_game_over()  # 'Counting score…' is checked in T16 (scoring a full board takes ~1 s)
        if over:  # close the dialog first: its dim overlay hides the board
            tap_label("BACK TO BOARD", over, wait=1.0)
        st = read_board(screenshot(f"t15_{h}_final"))
        check("T15", f"{name}: final position has 188 black and 169 white stones",
              count(st, "B") == 188 and count(st, "W") == 169, f"B={count(st, 'B')} W={count(st, 'W')}")
        check("T15", f"{name}: scored exactly {expected} (komi {komi}"
              + (", White +1 per handicap stone)" if h else ")"), score == expected,
              f"score={score} ({time.time() - t0:.0f}s)")
        check("T15", f"{name}: scoring leaves the komi at {komi}",
              bool(grep_logs(rf"komi after scoring {re.escape(komi)}\b", m)), grep_logs(r"Final score", m)[-1:])
        if not over:
            continue
        if h == 0:
            # Undo at game over resumes play; ending the game again gives the same result (same komi).
            mu = now_dev()
            tap_label("Undo", dump(), wait=1.5)
            check("T15", "Undo at game over resumes play", is_enabled("PASS", dump()))
            tap_label("PASS", dump(), wait=0.2)
            over2, score2, _ = wait_game_over()
            check("T15", "after Undo and a new pass the result is the same", score2 == score,
                  f"{score} then {score2}; {grep_logs(r'Final score', mu)[-1:]}")
            if over2:
                tap_label("BACK TO BOARD", over2, wait=1.0)


def t16():
    """T16: game over survives a relaunch without reopening the dialog; Undo resumes play."""
    new_game("Easy", 0, "Two Players")
    time.sleep(1)
    geom = board_geom(screenshot("t16_start"))
    place(3, 3, geom)
    time.sleep(0.8)
    tap_label("PASS", dump(), wait=1.0)
    tap_label("PASS", dump(), wait=0.2)
    over, score, counting = wait_game_over()
    check("T16", "two passes: 'Counting score…', then the result dialog", over is not None and counting,
          f"score={score} counting={counting}")
    if not over:
        return
    tap_label("BACK TO BOARD", over, wait=1.0)
    x, y = point(15, 15, geom)
    tap(x, y)
    time.sleep(0.6)
    nodes = dump()
    check("T16", "after BACK TO BOARD the board is locked (no PASS, no PLACE) and shows the result",
          not is_enabled("PASS", nodes) and not is_enabled("PLACE", nodes) and find(f"Result: {score}", nodes) is not None)
    force_stop()
    m, _ = launch_and_wait_engine(timeout=60)
    time.sleep(5)
    nodes = dump()
    check("T16", "after a relaunch the game is still over, with the same result and no dialog",
          find("BACK TO BOARD", nodes) is None and find("Game Over", nodes) is not None
          and find(f"Result: {score}", nodes) is not None and not is_enabled("PASS", nodes), sorted(labels(nodes))[:15])
    check("T16", "a finished game does not move or score again after the relaunch",
          not ai_colors(m) and not grep_logs(r"Final score", m))
    mu = now_dev()
    tap_label("Undo", nodes, wait=1.5)
    nodes = dump()
    check("T16", "Undo after game over resumes play", is_enabled("PASS", nodes) and find("Game Over", nodes) is None)
    tap_label("PASS", nodes, wait=0.5)
    p = plays(mu)
    check("T16", "after Undo it is Black's turn again (the undone pass was Black's)", bool(p) and p[-1][:2] == (1, "pass"), p)
    over2, _, _ = wait_game_over()
    if over2:
        tap_label("BACK TO BOARD", over2, wait=1.0)


def t17():
    """T17: the chosen mode, handicap and level are saved and restored after a relaunch."""
    combos = [("You are White", 3, "Easy"), ("Two Players", 9, "Amateur"), ("AI vs AI", 0, "Easy"),
              ("You are Black", 1, "Amateur")]
    if premium_on():
        combos += [("You are Black", 0, "Pro"), ("You are White", 5, "Advanced")]
    for mode, h, lvl in combos:
        new_game(lvl, h, mode)
        time.sleep(1.0)
        if mode == "AI vs AI":
            pause = find("PAUSE", dump())
            if pause:
                tap(*pause["center"])
        time.sleep(3)
        force_stop()
        m, _ = launch_and_wait_engine(timeout=60)
        wait_log(r"Komi updated to", m, timeout=20)
        time.sleep(3)
        v = visits_set(m)
        komi = "0.5" if h else "7.5"
        check("T17", f"{mode}, handicap {h}, {lvl}: restored with {LEVELS[lvl]} visits, komi {komi}"
              + (f", {h} handicap stones" if h else ""),
              v[-1:] == [LEVELS[lvl]] and bool(grep_logs(rf"Komi updated to {re.escape(komi)}", m))
              and (not h or bool(grep_logs(rf"AI fixed_handicap {h}:", m))), f"visits={v}")
        nodes = open_new_game()
        sel = dialog_selection(nodes)
        expected = (lvl, "None" if h == 0 else str(h), mode)
        check("T17", f"{mode}, handicap {h}, {lvl}: New Game shows the saved choices", sel == expected, f"selected: {sel}")
        tap_label("CANCEL", dump(), wait=1.0)


LOCALES = {
    "ja-JP": {"new_game": "新規対局", "title": "新規対局の開始", "start": "開始", "cancel": "キャンセル",
              "play_as": "手番の選択", "easy": "初級", "none": "なし", "two_players": "二人対局",
              "black": "黒番 (自分)", "footer": "あなたが黒番で置き石を置きます。AI（白）が先に打ちます。",
              "pass": "パス", "game_over": "終局", "back": "盤面に戻る", "concluded": "対局が終了しました。"},
    "zh-CN": {"new_game": "新对局", "title": "新建对局", "start": "开始", "cancel": "取消",
              "play_as": "玩家持子", "easy": "入门级", "none": "无", "two_players": "玩家对局",
              "black": "持黑 (您)", "footer": "你执黑并获得让子，AI（白）先行。",
              "pass": "停一手", "game_over": "棋局结束", "back": "返回棋盘", "concluded": "对局已结束。"},
}
ENGLISH_ONLY = ["CANCEL", "START", "PLAY AS", "New Game", "BACK TO BOARD", "Game Over", "The game has concluded."]


def t18():
    """T18: Japanese and Simplified Chinese: New Game dialog, handicap line, result dialog."""
    if "set-app-locales" not in sh("cmd locale help 2>&1"):
        check("T18", "per-app language supported (Android 13+)", False, "cmd locale set-app-locales missing")
        return
    try:
        for tag, t in LOCALES.items():
            sh(f"cmd locale set-app-locales {PKG} --locales {tag}")
            force_stop()
            launch_and_wait_engine(timeout=60)
            time.sleep(3)
            nodes = dump()
            if find(t["title"], nodes) is None:
                tap_label(t["new_game"], nodes, wait=1.2)
                nodes = dump()
            row_select(t["easy"], [t["easy"]], nodes)
            row_select("2", HANDICAP_LABELS, dump())
            n, _ = None, None
            for _ in range(3):  # PLAY AS options sit below the handicap row
                nodes = dump()
                n = find(t["black"], nodes)
                if n and n["center"][1] < 0.76 * screen_size()[1]:
                    break
                swipe(540, 0.62 * screen_size()[1], 540, 0.37 * screen_size()[1], 400)
                time.sleep(0.8)
            if n:
                tap(*n["center"])
                time.sleep(0.6)
            nodes = dump()
            screenshot(f"t18_{tag}_new_game")
            want = [t["title"], t["start"], t["cancel"], t["play_as"], t["footer"]]
            missing = [s for s in want if find(s, nodes) is None]
            english = [s for s in ENGLISH_ONLY if find(s, nodes) is not None]
            check("T18", f"{tag}: New Game dialog and handicap line translated", not missing and not english,
                  f"missing={missing} english={english}")
            # A two-pass game in Two Players shows the translated result dialog.
            row_select(t["none"], HANDICAP_LABELS[1:] + [t["none"]], dump())
            for _ in range(3):
                nodes = dump()
                n = find(t["two_players"], nodes)
                if n and n["center"][1] < 0.76 * screen_size()[1]:
                    break
                swipe(540, 0.62 * screen_size()[1], 540, 0.37 * screen_size()[1], 400)
                time.sleep(0.8)
            if n:
                tap(*n["center"])
                time.sleep(0.6)
            tap_label(t["start"], dump(), wait=2.0)
            tap_label(t["pass"], dump(), wait=1.0)
            tap_label(t["pass"], dump(), wait=0.5)
            nodes, t0 = [], time.time()
            while time.time() - t0 < 120 and find(t["back"], nodes) is None:
                time.sleep(1)
                nodes = dump(retries=2)
            screenshot(f"t18_{tag}_game_over")
            missing = [s for s in (t["game_over"], t["back"], t["concluded"], t["new_game"]) if find(s, nodes) is None]
            english = [s for s in ENGLISH_ONLY if find(s, nodes) is not None]
            check("T18", f"{tag}: result dialog translated", not missing and not english, f"missing={missing} english={english}")
            if find(t["back"], nodes):
                tap_label(t["back"], nodes, wait=1.0)
    finally:
        sh(f"cmd locale set-app-locales {PKG} --locales ''")
        force_stop()
        launch_and_wait_engine(timeout=60)
        time.sleep(2)
    check("T18", "app language restored to the phone's language", find("NEW GAME", dump()) is not None
          or in_dialog(dump()), sh(f"cmd locale get-app-locales {PKG}").strip())


def upgrade():
    """U1 (optional, needs --previous-apk): a game saved by the previous release restores after updating."""
    if not ARGS.previous_apk:
        check("U1", "previous release APK given (--previous-apk)", False, "not run")
        return
    adb("uninstall", PKG)
    out = adb("install", ARGS.previous_apk, timeout=300)
    check("U1", "previous release installed", "Success" in out, out.strip()[-120:])
    launch_and_wait_engine(timeout=900)
    time.sleep(3)
    m0 = new_game("Easy", 2, "You are Black")  # the previous release's labels are the same
    wait_count(r"AI search completed", m0, 1, 60)
    time.sleep(2)
    img = screenshot("u1_before")
    geom = board_geom(img)
    st = read_board(img)
    free = next(pt for pt in [(2, 2), (16, 16), (2, 16)] if pt not in st)
    mp = place(free[0], free[1], geom)
    wait_count(r"AI search completed", mp, 1, 60)
    time.sleep(1.5)
    before = read_board(screenshot("u1_saved"))
    out = adb("install", "-r", ARGS.apk, timeout=300)
    check("U1", "this build installed over it", "Success" in out, out.strip()[-120:])
    m, _ = launch_and_wait_engine(timeout=900)
    time.sleep(5)
    after = read_board(screenshot("u1_restored"))
    check("U1", "the saved game comes back unchanged after the update", after == before and not refused(m),
          f"{len(before)} vs {len(after)} stones; refused={refused(m)}")


# ------------------------------------------------------------------ B1: the release build (no phone)
def build_tools():
    """Newest Android SDK build-tools folder (aapt2, dexdump)."""
    sdk = (os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
           or os.path.expanduser("~/Library/Android/sdk"))
    root = os.path.join(sdk, "build-tools")
    versions = sorted(os.listdir(root), key=lambda v: [int(x) if x.isdigit() else -1 for x in re.split(r"[.-]", v)])
    return os.path.join(root, versions[-1])


def parse_mapping(path):
    """R8 mapping file -> (header {key: value}, classes {obfuscated: original},
    methods {(obfuscated class, obfuscated method): {original methods}}). For inlined code R8 writes the
    inlined frames first and the method they were inlined into last; only that outer method is kept."""
    header, classes, outer = {}, {}, {}
    cls_obf = cls_orig = None
    member_rx = re.compile(r"\s+(?:(\d+):(\d+):)?\S+ ([^\s(]+)\(.*?\)(?::\d+(?::\d+)?)? -> (\S+)$")
    with open(path, encoding="utf-8", errors="replace") as f:
        for n, line in enumerate(f):
            line = line.rstrip("\n")
            if line.startswith("#"):
                m = re.match(r"# (\w+): (.*)", line)
                if m:
                    header.setdefault(m.group(1), m.group(2).strip())
            elif not line.startswith(" "):
                m = re.match(r"(\S+) -> (\S+):$", line)
                if m:
                    cls_orig, cls_obf = m.groups()
                    classes[cls_obf] = cls_orig
            elif cls_obf:
                m = member_rx.match(line)
                if m:
                    orig = m.group(3) if "." in m.group(3) else f"{cls_orig}.{m.group(3)}"
                    group = (m.group(1), m.group(2)) if m.group(1) else n
                    outer[(cls_obf, m.group(4), group)] = orig  # a later frame of the group wins
    methods = {}
    for (cls, name, _), orig in outer.items():
        methods.setdefault((cls, name), set()).add(orig)
    return header, classes, methods


def dex_methods(dex_files, dexdump):
    """{(class, method): set of invoked (class, method)} and {class: native method names} of the dex files."""
    calls, natives = {}, {}
    invoke_rx = re.compile(r"invoke-\w+(?:/range)? \{[^}]*\}, L([^;]+);\.([^:]+):")
    for dex in dex_files:
        out = subprocess.run([dexdump, "-d", dex], capture_output=True, text=True, errors="replace").stdout
        cls = name = cur = None
        for line in out.splitlines():
            if "Class descriptor" in line:
                m = re.search(r"'L(.+);'", line)
                cls, cur = (m.group(1).replace("/", "."), None) if m else (None, None)
            elif line.startswith("      name"):
                name = line.split("'")[1] if "'" in line else None
            elif line.startswith("      type") and "'(" in line and cls and name:
                cur = (cls, name)
                calls.setdefault(cur, set())
            elif line.startswith("      access") and "NATIVE" in line and cur:
                natives.setdefault(cls, set()).add(cur[1])
            elif "invoke-" in line and cur:
                m = invoke_rx.search(line)
                if m:
                    calls[cur].add((m.group(1).replace("/", "."), m.group(2)))
    return calls, natives


# Window APIs deprecated by Android 15's edge-to-edge (Play Console: "deprecated APIs or parameters").
DEPRECATED_E2E = ("setStatusBarColor", "setNavigationBarColor", "setNavigationBarDividerColor",
                  "setStatusBarContrastEnforced", "getStatusBarColor", "getNavigationBarColor",
                  "getNavigationBarDividerColor")


def deprecated_e2e_callers(calls, methods, classes):
    """Original (de-obfuscated) methods that call a deprecated edge-to-edge Window API, directly or through
    R8's synthetic outlines and lambdas."""
    callers_of = {}
    for caller, targets in calls.items():
        for t in targets:
            callers_of.setdefault(t, set()).add(caller)

    def originals(key):
        return methods.get(key) or {f"{classes.get(key[0], key[0])}.{key[1]}"}

    def synthetic(name):
        return any(s in name for s in ("$$ExternalSynthetic", "$$Lambda", "R8$$", "-$$"))

    found, seen = set(), set()
    todo = [c for c, ts in calls.items() if any(t[0] == "android.view.Window" and t[1] in DEPRECATED_E2E for t in ts)]
    while todo:
        key = todo.pop()
        if key in seen:
            continue
        seen.add(key)
        names = originals(key)
        if all(synthetic(o) for o in names):
            todo.extend(callers_of.get(key, ()))
        else:
            found |= {o for o in names if not synthetic(o)}
    return found


def b1():
    """B1: the release build itself, checked on the computer (no phone): R8 obfuscation (Play Console:
    "DEX code optimization"), no deprecated edge-to-edge API calls except Google's enableEdgeToEdge(),
    no orientation or resizability restrictions, and the R8 mapping file that belongs to the APK."""
    apk = ARGS.apk
    mapping = ARGS.mapping or os.path.join(os.path.dirname(apk), "app_proguard.map")
    if not check("B1", "release APK found", os.path.exists(apk), apk):
        return
    bt = build_tools()
    manifest = subprocess.run([os.path.join(bt, "aapt2"), "dump", "xmltree", "--file", "AndroidManifest.xml", apk],
                              capture_output=True, text=True).stdout
    version = re.search(r"versionName\(0x0101021c\)=\"([^\"]+)\"", manifest)
    code = re.search(r"versionCode\(0x0101021b\)=(\d+)", manifest)
    target = re.search(r"targetSdkVersion\(0x01010270\)=(\d+)", manifest)
    act(f"B1 {apk}: versionName={version and version.group(1)} versionCode={code and code.group(1)}")
    restrictions = [l.strip()[:90] for l in manifest.splitlines()
                    if re.search(r":(screenOrientation|maxAspectRatio|minAspectRatio)\(", l)
                    or re.search(r":resizeableActivity\(0x[0-9a-f]+\)=false", l)]
    check("B1", "manifest: no orientation lock, aspect-ratio limit or resizeableActivity=false",
          bool(manifest) and not restrictions, restrictions[:3] or "aapt2 printed nothing")
    check("B1", "manifest: targets SDK 35 or newer (Android 15 edge-to-edge)",
          target is not None and int(target.group(1)) >= 35, target and target.group(1))

    tmp = os.path.join(OUT, "b1_dex")
    os.makedirs(tmp, exist_ok=True)
    subprocess.run(["unzip", "-o", "-q", apk, "classes*.dex", "-d", tmp])
    dex_files = sorted(os.path.join(tmp, f) for f in os.listdir(tmp) if f.endswith(".dex"))
    markers = set()
    for d in dex_files:
        markers |= set(re.findall(rb"~~R8\{[^}]*\}", open(d, "rb").read()))
    marker = markers.pop().decode() if len(markers) == 1 else ""
    check("B1", "the code was compiled by R8 in release mode", '"compilation-mode":"release"' in marker, marker or markers)
    if not check("B1", "R8 mapping file found", os.path.exists(mapping), f"{mapping} (use --mapping)"):
        return
    header, classes, methods = parse_mapping(mapping)
    map_id = header.get("pg_map_id", "?")
    check("B1", "the mapping file belongs to this APK (same pg-map-id)", f'"pg-map-id":"{map_id}"' in marker,
          f"mapping {map_id}; dex marker {marker[:120]}")

    out_classes = {o: c for o, c in classes.items() if not o.startswith("R8$$REMOVED")}
    renamed = sum(1 for o, c in out_classes.items() if o != c)
    share = renamed / max(1, len(out_classes))
    check("B1", f"obfuscation: {100 * share:.0f}% of {len(out_classes)} classes renamed (Play wants 25%+)", share > 0.5)
    kept_app = sorted(c for o, c in out_classes.items() if o == c and c.startswith("com.cwave."))
    expected_kept = ["com.cwave.weiqi.MainActivity", "com.cwave.weiqi.WeiqiApplication",
                     "com.cwave.weiqi.katago.KataGoBridge", "com.cwave.weiqi.katago.KataGoBridgeEigen"]
    check("B1", "app code obfuscated except the manifest entry points and the JNI bridges", kept_app == expected_kept,
          f"names kept: {kept_app}")
    calls, natives = dex_methods(dex_files, os.path.join(bt, "dexdump"))
    jni = {"init", "sendGtpCommand", "getBoardState", "shutdown"}
    bridges = {c: natives.get(c, set()) for c in ("com.cwave.weiqi.katago.KataGoBridge",
                                                   "com.cwave.weiqi.katago.KataGoBridgeEigen")}
    check("B1", "JNI: both bridges keep their native methods' names (the .so files look them up by name)",
          all(v == jni for v in bridges.values()), bridges)
    material = sorted(c for c in out_classes.values() if c.startswith("com.google.android.material."))
    check("B1", "Material Components removed (BottomSheetDialog, SheetDialog, EdgeToEdgeUtils)", not material,
          material[:3])
    callers = deprecated_e2e_callers(calls, methods, classes)
    others = sorted(c for c in callers if not c.startswith("androidx.activity.EdgeToEdge"))
    check("B1", "deprecated edge-to-edge Window APIs: only called by androidx enableEdgeToEdge()",
          not others, f"other callers: {others[:4]}")

    # The bundle uploaded to Play is built with the same R8 rules and carries its own mapping file, which
    # Play Console reads to de-obfuscate crash reports (no separate upload).
    aab = os.path.join(os.path.dirname(apk), "release.aab")
    if check("B1", "release.aab found (bazel build //src:release_bundle)", os.path.exists(aab), aab):
        raw = subprocess.run(["unzip", "-p", aab, "base/dex/classes*.dex"], capture_output=True).stdout
        aab_markers = {m.decode() for m in re.findall(rb"~~R8\{[^}]*\}", raw)}
        embedded = subprocess.run(["unzip", "-p", aab, "BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map"],
                                  capture_output=True).stdout[:4000].decode("utf-8", "replace")
        aab_id = re.search(r"# pg_map_id: (\S+)", embedded)
        check("B1", "release.aab: R8 release build with its mapping file inside (BUNDLE-METADATA)",
              aab_id is not None and any('"compilation-mode":"release"' in m and f'"pg-map-id":"{aab_id.group(1)}"' in m
                                         for m in aab_markers),
              f"dex markers={aab_markers} embedded mapping id={aab_id and aab_id.group(1)}")
    shutil.rmtree(tmp, ignore_errors=True)


# ------------------------------------------------------------------ T20, T21: rotation, large screens, edge to edge
def game_screen():
    """The game screen in front, with no dialog open."""
    if not pid():
        launch_and_wait_engine(timeout=120)
        time.sleep(2)
    nodes = dump()
    if in_dialog(nodes):
        tap_label("CANCEL", nodes, wait=1.0)
        nodes = dump()
    if find("BACK TO BOARD", nodes):
        tap_label("BACK TO BOARD", nodes, wait=1.0)
        nodes = dump()
    return nodes


def restarted_since(m):
    """Log lines that only appear when the activity (and its fragment) is created again."""
    return grep_logs(r"Initializing KataGoBridge|Falling back to KataGoBridgeEigen|Starting KataGo Engine Init", m)


def t20():
    """T20: rotation, landscape and large screens (Play: no orientation or resizability restrictions)."""
    global WM_OVERRIDDEN
    set_rotation(0)
    force_stop()
    launch_and_wait_engine(timeout=120)
    time.sleep(2)
    new_game("Easy", 0, "Two Players")  # no AI moves: the board only changes when the test plays
    time.sleep(1.0)
    geom = board_geom(screenshot("t20_portrait_start"))
    place(3, 3, geom)
    time.sleep(0.8)
    place(15, 15, geom)
    time.sleep(1.0)
    before = read_board(screenshot("t20_portrait"))
    p0, m = pid(), now_dev()

    set_rotation(1)
    img, nodes = screenshot("t20_landscape"), dump()
    h, w, _ = img.shape
    check("T20", "the app turns to landscape with the phone and switches to the side-by-side layout",
          w > h and bool(grep_logs(r"Layout: side-by-side", m)), f"screen {w}x{h}; {grep_logs(r'Layout:', m)[-1:]}")
    check("T20", "rotating does not restart the activity or the engine", pid() == p0 and not restarted_since(m),
          restarted_since(m)[:1])
    board = layout_check("T20", "landscape", img, nodes)
    beside = all(find(c, nodes)["bounds"][0] >= board[2] for c in CONTROLS if find(c, nodes))
    check("T20", "landscape: the board takes the full height, the controls are beside it",
          board[3] - board[1] > 0.75 * h and beside, f"board={board} screen={w}x{h}")
    check("T20", "landscape: the game is unchanged", read_board(img) == before)
    mp = place(9, 9, board_geom(img))  # Black to move
    time.sleep(1.5)
    after = read_board(screenshot("t20_landscape_move"))
    p = plays(mp)
    check("T20", "landscape: tapping a point and PLACE plays there (K10)", after.get((9, 9)) == "B" and p[:1]
          and p[0][1] == "K10", f"plays={p}")
    nodes = open_new_game()
    dimg = screenshot("t20_landscape_new_game")
    dh, dw, _ = dimg.shape
    fits = [x for x in ("New Game", "START", "CANCEL") if find(x, nodes)
            and find(x, nodes)["bounds"][2] <= dw and find(x, nodes)["bounds"][3] <= dh]
    check("T20", "landscape: the New Game dialog fits (title, START and CANCEL on screen)", len(fits) == 3, fits)
    tap_label("CANCEL", nodes, wait=1.0)

    set_rotation(3)
    layout_check("T20", "reverse landscape (cutout and navigation bar on the other side)",
                 screenshot("t20_reverse_landscape"), dump())
    m3 = now_dev()
    set_rotation(0)
    img, nodes = screenshot("t20_portrait_back"), dump()
    check("T20", "back in portrait: stacked layout, same game, no restart",
          bool(grep_logs(r"Layout: stacked", m3)) and read_board(img) == after and pid() == p0
          and not restarted_since(m), f"{grep_logs(r'Layout:', m3)[-1:]}")
    layout_check("T20", "portrait", img, nodes)

    # The AI's reply still arrives when the phone is turned while it searches.
    new_game("Amateur", 0, "You are Black")
    time.sleep(1.5)
    mp = place(3, 15, board_geom(screenshot("t20_ai_start")))
    set_rotation(1)
    got = wait_count(r"AI search completed", mp, 1, 90)
    time.sleep(1.5)
    st = read_board(screenshot("t20_ai_landscape"))
    check("T20", "turning the phone while the AI thinks: its move still arrives, nothing restarts",
          got and st.get((3, 15)) == "B" and count(st, "W") == 1 and pid() == p0 and not restarted_since(m),
          f"search done={got} B={count(st, 'B')} W={count(st, 'W')}")
    set_rotation(0)

    # A large screen: an 800 x 1280 dp tablet, simulated on the phone. The density change re-creates the
    # activity, which must put the game back on screen from its saved state.
    before = read_board(screenshot("t20_before_tablet"))
    try:
        WM_OVERRIDDEN = True
        m5 = now_dev()
        sh("wm size 1600x2560")
        sh("wm density 320")
        time.sleep(6)
        ensure_foreground()
        wait_log(r"Layout: stacked, 800x", m5, timeout=30)
        time.sleep(2)
        img, nodes = screenshot("t20_tablet_portrait"), dump()
        try:
            restored = read_board(img) == before and find("NEW GAME", nodes) is not None
        except RuntimeError:
            restored = False
        check("T20", "after the activity is re-created (density change) the saved game is back on screen",
              bool(restarted_since(m5)) and restored, f"re-created={bool(restarted_since(m5))} restored={restored}")
        layout = [l for l in grep_logs(r"Layout:", m5) if "800x" in l]
        size = re.search(r"board (\d+)dp", layout[-1]) if layout else None
        check("T20", "tablet portrait (800x1280 dp): stacked layout with a board of 700 dp or more",
              size is not None and "stacked" in layout[-1] and int(size.group(1)) >= 700, layout[-1:])
        layout_check("T20", "tablet portrait", img, nodes)
        m6 = now_dev()
        set_rotation(1)
        img, nodes = screenshot("t20_tablet_landscape"), dump()
        layout = grep_logs(r"Layout:", m6)
        check("T20", "tablet landscape (1280x800 dp): side-by-side layout", bool(layout) and "side-by-side" in layout[-1],
              layout[-1:])
        layout_check("T20", "tablet landscape", img, nodes)
    finally:
        sh("wm size reset")
        sh("wm density reset")
        WM_OVERRIDDEN = False
        set_rotation(0)
        time.sleep(4)

    # Dark mode switched on and off (e.g. by its sunset schedule) also re-creates the activity.
    night = sh("cmd uimode night").strip().split(":")[-1].strip()
    if night in ("yes", "no", "auto"):
        m7 = now_dev()
        try:
            sh("cmd uimode night " + ("no" if night == "yes" else "yes"))
            time.sleep(4)
        finally:
            sh(f"cmd uimode night {night}")
        time.sleep(5)
        ensure_foreground()
        img, nodes = screenshot("t20_after_dark_mode"), dump()
        try:
            restored = read_board(img) == before and find("NEW GAME", nodes) is not None
        except RuntimeError:
            restored = False
        check("T20", "after dark mode is switched on and off the game is on screen, unchanged",
              restored, f"re-created={bool(restarted_since(m7))} restored={restored}")
    else:
        check("T20", "dark mode can be switched for the re-creation check", False, f"cmd uimode night: {night}")


def t21():
    """T21: edge to edge (Android 15+ always draws apps behind the system bars): the app's colors fill the
    status and navigation bar areas, its content stays clear of them, in both orientations."""
    set_rotation(0)
    game_screen()
    for rot, label in ((0, "portrait"), (1, "landscape")):
        set_rotation(rot)
        img, nodes = screenshot(f"t21_{label}"), dump()
        purple_share, nav_light = bar_colors(img)
        check("T21", f"{label}: the app's purple fills the status bar (light icons on it)",
              purple_share is not None and purple_share > 0.6, f"purple share {purple_share}")
        check("T21", f"{label}: the app's light background shows behind the navigation bar",
              nav_light is None or nav_light > 200, f"mean brightness {nav_light}")
        layout_check("T21", label, img, nodes)
    set_rotation(0)


STEPS = [
    ("b1", b1, "B1 release build checks on the computer: R8 obfuscation, deprecated APIs, manifest, mapping file"),
    ("t1", t1, "T1 first-run GPU tuning (clears app data, records the screen)"),
    ("t2", t2, "T2 normal start"),
    ("t3", t3, "T3 difficulty + P1-P4 (premium off) or P10 (premium on); opens and closes the Play sheet, airplane mode for P4"),
    ("t4", t4, "T4 every handicap level"),
    ("t5", t5, "T5 every play-as mode"),
    ("t6-8", t6_8, "T6 moves, T7 pass and game end, T8 undo and redo"),
    ("t9", t9, "T9 cancel"),
    ("t10", t10, "T10 save and restore"),
    ("t11", t11, "T11 analysis"),
    ("t12", t12, "T12 stability and memory (about 20 moves)"),
    ("t13", t13, "T13 search budget per level"),
    ("t14", t14, "T14 rules: ko, suicide, illegal-move message, coordinates"),
    ("t15", t15, "T15 exact score of two finished games (about 10 minutes of tapping)"),
    ("t16", t16, "T16 game over, relaunch, Undo"),
    ("t17", t17, "T17 settings saved and restored"),
    ("t18", t18, "T18 Japanese and Chinese (changes the app language, then restores it)"),
    ("t20", t20, "T20 rotation, landscape, large screens (turns the screen; simulates a tablet with wm size)"),
    ("t21", t21, "T21 edge to edge: system bar colors and insets in portrait and landscape"),
]
HOST_STEPS = {"b1"}  # steps that need no phone
OPTIONAL_STEPS = [
    ("upgrade", upgrade, "U1 update from the previous release (uninstalls the app; needs --previous-apk)"),
]


def audit_turns(since):
    """T19: every move the app sends must be for the side to move; the engine refuses anything else."""
    for color, move, root in plays(since):
        if color != root:
            TURN_AUDIT.append(f"play color={color} move={move} rootPla={root}")
    TURN_AUDIT.extend(l[-80:] for l in grep_logs(r"Refused: not", since))


def main():
    global ARGS, OUT
    if sys.platform == "darwin" and not os.environ.get("RT_NO_SLEEP"):
        # A Mac that idle-sleeps pauses the runner for 15 minutes at a time (and breaks its timeouts):
        # run under caffeinate, which only prevents idle sleep while this command runs.
        os.environ["RT_NO_SLEEP"] = "1"
        os.execvp("caffeinate", ["caffeinate", "-i", sys.executable] + sys.argv)
    parser = argparse.ArgumentParser(description="Automated release test pass (doc/test_plan.md).")
    parser.add_argument("steps", nargs="*", help="steps to run (default: all except optional ones); see --list")
    parser.add_argument("--out", default="/tmp/weiqi-release-test", help="output folder")
    parser.add_argument("--premium", choices=["auto", "on", "off"], default="auto",
                        help="premium state of the installed build (default: detect from the New Game dialog)")
    parser.add_argument("--serial", help="adb serial of the phone to use")
    parser.add_argument("--apk", default="bazel-bin/src/release.apk", help="this build (used by 'b1', 't2', 'upgrade')")
    parser.add_argument("--mapping", help="R8 mapping file of --apk (default: app_proguard.map next to it)")
    parser.add_argument("--previous-apk", help="APK of the previous release (used by 'upgrade')")
    parser.add_argument("--list", action="store_true", help="list the steps and exit")
    ARGS = parser.parse_args()
    if ARGS.list:
        for name, _, desc in STEPS:
            print(f"{name:8s} {desc}")
        for name, _, desc in OPTIONAL_STEPS:
            print(f"{name:8s} (optional) {desc}")
        return 0
    all_steps = dict((s[0], s[1]) for s in STEPS + OPTIONAL_STEPS)
    names = ARGS.steps or [s[0] for s in STEPS]
    unknown = [n for n in names if n not in all_steps]
    if unknown:
        parser.error(f"unknown steps: {unknown}; use --list")
    OUT = ARGS.out
    os.makedirs(OUT, exist_ok=True)
    phone = any(n not in HOST_STEPS for n in names)
    if phone and not adb("get-state").strip() == "device":
        print("No phone connected (adb get-state).", file=sys.stderr)
        return 2
    if phone:
        adb("logcat", "-G", "16M")  # keep a whole step's engine log (the scoring games log ~800 lines)
        lock_portrait()
    try:
        for name in names:
            print(f"== {name} ==", flush=True)
            m = now_dev() if phone else None
            try:
                all_steps[name]()
            except Exception as e:  # record the failure and continue with the next step
                check(name.upper(), "step ran to completion", False, repr(e))
            if name in HOST_STEPS:
                continue
            try:
                audit_turns(m)
            except Exception:
                pass
    finally:
        if phone:
            restore_phone()
    if phone:
        check("T19", "the app never sent a move or search for the wrong color (all steps)", not TURN_AUDIT, TURN_AUDIT[:3])
        try:
            premium_on()  # record the premium state in the summary even if no selected step needed it
        except Exception:
            pass
    ok = write_summary()
    passed = sum(1 for r in RESULTS if r["pass"])
    print(f"\n{passed} of {len(RESULTS)} checks passed. Results: {os.path.join(OUT, 'summary.md')}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
