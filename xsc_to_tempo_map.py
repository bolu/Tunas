#!/usr/bin/env python3
"""
xsc_to_tempo_map.py
-------------------
Converts a Transcribe! (.xsc) file into a MIDI tempo map (.mid)
compatible with Reaper.

Usage:
    python3 xsc_to_tempo_map.py input.xsc [output.mid]

If output path is omitted, the .mid file is saved next to the .xsc file
with " tempo map" appended to the name, e.g. "mysong tempo map.mid".

How it works:
    1. Parses section/measure/beat markers from the .xsc file.
    2. Derives MIDI tempo values directly as microseconds/beat from
       marker intervals (no BPM conversion path).
    3. Writes a Type 1 MIDI file containing Set Tempo meta-events.
       Type 1 is required — Reaper will not prompt to import the tempo
       map from a Type 0 file.

Reaper import:
    Before importing, enable both settings in Preferences -> Media -> MIDI:
      - "Always prompt to import tempo from MIDI files with simple tempo maps"
      - "Automatically adjust media to project tempo" should be OFF so that
        Reaper prompts you instead of silently ignoring the tempo map

    The project must be empty (no existing tempo markers) before importing,
    otherwise Reaper may not offer to apply the tempo map.

    Then import via Insert -> Media File..., select the .mid file, and
    tick "Import tempo map" when the dialog appears. You can delete the
    resulting empty MIDI item from the timeline afterwards — the tempo
    map will remain in the project.
"""

import sys
import re
import os
import struct
from pathlib import Path


# ---------------------------------------------------------------------------
# XSC parsing
# ---------------------------------------------------------------------------

def parse_xsc(path: str) -> list[dict]:
    """
    Returns a list of marker dicts sorted by time:
        {'type': 'S'|'M'|'B', 'seconds': float, 'label': str}
    """
    markers = []
    in_markers = False

    marker_line = re.compile(
        r'^([SMB])\s*,\s*'       # type
        r'-?\d+\s*,\s*'          # a number
        r'-?\d+\s*,\s*'          # auto-name flag
        r'([^,]*)\s*,\s*'        # label (may be empty)
        r'-?\d+\s*,\s*'          # subdivision count, 1 means no subdivision, 0 means same as previous
        r'(\d+:\d+:\d+\.\d+)',   # timestamp  H:MM:SS.mmm
        re.IGNORECASE
    )

    with open(path, 'r', encoding='utf-8', errors='replace') as f:
        for line in f:
            line = line.strip()
            if line == 'SectionStart,Markers':
                in_markers = True
                continue
            if line.startswith('SectionEnd') and in_markers:
                break
            if not in_markers:
                continue

            m = marker_line.match(line)
            if m:
                mtype  = m.group(1).upper()
                label  = m.group(2).strip()
                ts_str = m.group(3)   # H:MM:SS.mmm
                seconds = _ts_to_seconds(ts_str)
                markers.append({'type': mtype, 'seconds': seconds, 'label': label})

    markers.sort(key=lambda x: x['seconds'])
    return markers


def _ts_to_seconds(ts: str) -> float:
    """Convert 'H:MM:SS.mmm' to total seconds."""
    parts = ts.split(':')
    h  = int(parts[0])
    mn = int(parts[1])
    s  = float(parts[2])
    return h * 3600 + mn * 60 + s


# ---------------------------------------------------------------------------
# Tempo map derivation
# ---------------------------------------------------------------------------

def _pairs(markers: list[dict], types: list[str]) -> list[tuple[float, float]]:
    """
    Return (t0, t1) time pairs for consecutive markers of given types.
    """
    pairs = []
    last_time = None

    for m in markers:
        if m['type'] in types:
            if last_time is not None:
                pairs.append((last_time, m['seconds']))
            last_time = m['seconds']

    return pairs


def derive_tempo_map(markers: list[dict], beats_per_bar: int = 4) -> list[tuple[float, int]]:
    """
    Returns [(time_seconds, microseconds_per_beat), ...] sorted by time.

    Strategy:
      - S (section) markers are treated as bar downbeats, equivalent to M.
      - If B (beat) markers exist, use all of S+M+B as beat-level markers:
        interval = one beat, us/beat = interval_seconds * 1_000_000.
      - Otherwise use S+M as bar-level markers: interval = one bar,
        us/beat = (interval_seconds / beats_per_bar) * 1_000_000
        (assumed 4/4).
    """
    types = ['S', 'M']
    beat_scale = beats_per_bar  # each interval is one bar (assume 4/4)

    has_beats = any(m['type'] == 'B' for m in markers)
    if has_beats:
        types.append('B')
        beat_scale = 1  # each interval is one beat

    valid_pairs = _pairs(markers, types)
    tempo_events = []

    for t0, t1 in valid_pairs:
        interval = t1 - t0
        if interval <= 0:
            print(f"  Negative interval {interval:.3f}s at {t0:.3f}s, exiting")
            sys.exit(1)
        us_per_beat = round((interval / beat_scale) * 1_000_000)
        if us_per_beat <= 0:
            print(f"  Invalid tempo {us_per_beat} us/beat at {t0:.3f}s, exiting")
            sys.exit(1)
        tempo_events.append((t0, us_per_beat))

    if not tempo_events:
        print("  No tempo events, exiting")
        sys.exit(1)

    return tempo_events


# ---------------------------------------------------------------------------
# MIDI writer (no external dependency for the actual file format)
# ---------------------------------------------------------------------------

TICKS_PER_BEAT = 480   # standard resolution, Reaper handles this well


def _var_len(value: int) -> bytes:
    """Encode an integer as a MIDI variable-length quantity."""
    result = []
    result.append(value & 0x7F)
    value >>= 7
    while value:
        result.append((value & 0x7F) | 0x80)
        value >>= 7
    result.reverse()
    return bytes(result)


def _set_tempo_event(delta_ticks: int, microseconds_per_beat: int) -> bytes:
    """Build a Set Tempo meta-event."""
    delta = _var_len(delta_ticks)
    tempo_bytes = struct.pack('>I', microseconds_per_beat)[1:]  # 3 bytes
    return delta + b'\xFF\x51\x03' + tempo_bytes


def _end_of_track_event(delta_ticks: int = 0) -> bytes:
    return _var_len(delta_ticks) + b'\xFF\x2F\x00'


def _time_sig_event(delta_ticks: int, numerator=4, denominator=4) -> bytes:
    """4/4 time signature meta-event (denominator encoded as power of 2)."""
    denom_pow = {1: 0, 2: 1, 4: 2, 8: 3, 16: 4}.get(denominator, 2)
    delta = _var_len(delta_ticks)
    return delta + b'\xFF\x58\x04' + bytes([numerator, denom_pow, 24, 8])


def write_tempo_midi(
    tempo_events: list[tuple[float, int]],
    out_path: str,
    ticks_per_event: int,
):
    """
    Write a Type 1 MIDI file with tempo/time-sig meta-events on track 0
    and an empty track 1. Type 1 is required for Reaper to recognise and
    offer to import the tempo map.

    Tick placement strategy:
      - Tempo events are always exported on a fixed musical grid.
      - If source has only bar markers: one event every bar (4/4 -> 1920 ticks).
      - If source has beat markers: one event every beat (480 ticks).
    """
    # --- Track 0: tempo map ---
    tempo_track = bytearray()

    # Opening time signature (4/4)
    tempo_track += _time_sig_event(0)
    print("  Writing MIDI events:")
    print("    [TS] Track 0 @ tick 0: META TimeSignature 4/4")

    tick_events = [
        (i * ticks_per_event, uspb)
        for i, (_seconds, uspb) in enumerate[tuple[float, int]](tempo_events)
    ]

    prev_tick = 0
    printed_main_loop = 0
    for abs_tick, uspb in tick_events:
        delta = abs_tick - prev_tick
        tempo_track += _set_tempo_event(delta, uspb)
        if printed_main_loop < 10:
            bpm = 60_000_000.0 / uspb
            print(
                f"    [{printed_main_loop + 1:2d}] Track 0 @ tick {abs_tick}: "
                f"META SetTempo {uspb} us/beat ({bpm:.2f} BPM)"
            )
            printed_main_loop += 1
        prev_tick = abs_tick

    if len(tick_events) > 10:
        print(f"    ... and {len(tick_events) - 10} more tempo events")

    tempo_track += _end_of_track_event(0)
    print(f"    [E0] Track 0 @ tick {prev_tick}: META EndOfTrack")

    # --- Track 1: empty placeholder (required for Type 1) ---
    empty_track = bytearray()
    empty_track += _end_of_track_event(0)
    print("    [E1] Track 1 @ tick 0: META EndOfTrack")

    # --- Assemble file: Type 1, 2 tracks ---
    header = b'MThd' + struct.pack('>IHHH', 6, 1, 2, TICKS_PER_BEAT)
    track0 = b'MTrk' + struct.pack('>I', len(tempo_track)) + bytes(tempo_track)
    track1 = b'MTrk' + struct.pack('>I', len(empty_track)) + bytes(empty_track)

    with open(out_path, 'wb') as f:
        f.write(header + track0 + track1)

    print(f"Written: {out_path}")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    xsc_path = sys.argv[1]
    if not os.path.isfile(xsc_path):
        print(f"ERROR: file not found: {xsc_path}")
        sys.exit(1)

    if len(sys.argv) >= 3:
        mid_path = sys.argv[2]
    else:
        mid_path = str(Path(xsc_path).with_suffix('')) + ' tempo map.mid'

    print(f"Parsing: {xsc_path}")
    markers = parse_xsc(xsc_path)

    beats  = [m for m in markers if m['type'] == 'B']
    meas   = [m for m in markers if m['type'] == 'M']
    sects  = [m for m in markers if m['type'] == 'S']
    print(f"  Found {len(sects)} section, {len(meas)} measure, {len(beats)} beat markers")

    tempo_map = derive_tempo_map(markers)
    print(f"  Derived {len(tempo_map)} tempo event(s):")
    for t, uspb in tempo_map[:10]:
        print(f"    {t:8.3f}s  →  {uspb} us/beat")
    if len(tempo_map) > 10:
        print(f"    ... and {len(tempo_map) - 10} more")

    has_beats = len(beats) > 0
    ticks_per_event = TICKS_PER_BEAT if has_beats else (TICKS_PER_BEAT * 4)
    write_tempo_midi(tempo_map, mid_path, ticks_per_event)

    print()
    print("To import into Reaper:")
    print("  1. Preferences -> Media -> MIDI -> enable both:")
    print("     - 'Always prompt to import tempo from MIDI files with simple tempo maps'")
    print("     - Turn OFF 'Automatically adjust media to project tempo'")
    print("  2. Start with an empty project (no existing tempo markers)")
    print("  3. Insert -> Media File..., select the .mid, tick 'Import tempo map'")
    print("  4. Delete the empty MIDI item from the timeline if you don't need it")


if __name__ == '__main__':
    main()
