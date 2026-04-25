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

import os
import re
import struct
import sys
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
        r"^([SMB])\s*,\s*"  # type
        r"-?\d+\s*,\s*"  # a number
        r"-?\d+\s*,\s*"  # auto-name flag
        r"([^,|]*)(\|(\d+))?\s*,\s*"  # label (may be empty, with optional meter)
        r"-?\d+\s*,\s*"  # subdivision count, 1 means no subdivision, 0 means same as previous
        r"(\d+:\d+:\d+\.\d+)",  # timestamp  H:MM:SS.mmm
        re.IGNORECASE,
    )

    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if line == "SectionStart,Markers":
                in_markers = True
                continue
            if line.startswith("SectionEnd") and in_markers:
                break
            if not in_markers:
                continue

            m = marker_line.match(line)
            if m:
                mtype = m.group(1).upper()
                label = m.group(2).strip()
                meter = m.group(4)
                ts_str = m.group(5)  # H:MM:SS.mmm
                seconds = _ts_to_seconds(ts_str)
                meter = 4 if meter is None else int(meter)
                markers.append(
                    {"type": mtype, "seconds": seconds, "label": label, "meter": meter}
                )

    markers.sort(key=lambda x: x["seconds"])
    return markers


def _ts_to_seconds(ts: str) -> float:
    """Convert 'H:MM:SS.mmm' to total seconds."""
    parts = ts.split(":")
    h = int(parts[0])
    mn = int(parts[1])
    s = float(parts[2])
    return h * 3600 + mn * 60 + s


# ---------------------------------------------------------------------------
# Tempo map derivation
# ---------------------------------------------------------------------------


def _pairs(markers: list[dict], types: list[str]) -> list[tuple]:
    """
    Return (t0, t1, m, text) time pairs for consecutive markers of given types.
    """
    pairs = []
    last_time = None
    last_meter = None
    last_text = None

    for m in markers:
        if m["type"] in types:
            if last_time is not None:
                pairs.append((last_time, m["seconds"], last_meter, last_text))
            last_time = m["seconds"]
            last_meter = m["meter"]
            last_text = m["label"] if m["type"] == "S" else None

    return pairs


def derive_tempo_map(markers: list[dict], beats_per_bar: int = 4) -> list[tuple]:
    """
    Returns a single event list in generated time order. Each item is one of:
      - ('tempo', time_seconds, microseconds_per_beat)
      - ('timesig', time_seconds, numerator, denominator)
      - ('text', time_seconds, text)

    Strategy:
      - S (section) markers are treated as bar downbeats, equivalent to M.
      - If B (beat) markers exist, use all of S+M+B as beat-level markers:
        interval = one beat, us/beat = interval_seconds * 1_000_000.
      - Otherwise use S+M as bar-level markers: interval = one bar,
        us/beat = (interval_seconds / beats_per_bar) * 1_000_000
        (assumed 4/4).
    """
    types = ["S", "M"]

    has_beats = any(m["type"] == "B" for m in markers)
    if has_beats:
        types.append("B")

    valid_pairs = _pairs(markers, types)
    events = []

    # If the first marker does not start at 0, insert a synthetic single-beat
    # lead-in so the musical grid reaches that first marker correctly.
    first_marker_time = markers[0]["seconds"]
    meter = markers[0]["meter"]
    if first_marker_time > 0:
        lead_in_us_per_beat = round(first_marker_time * 1_000_000)
        events.append(("timesig", 0.0, 1, 4))
        events.append(("tempo", 0.0, lead_in_us_per_beat))
        events.append(("timesig", first_marker_time, meter, 4 if meter < 8 else 8))
    else:
        events.append(("timesig", 0.0, meter, 4 if meter < 8 else 8))

    for t0, t1, m, text in valid_pairs:
        interval = t1 - t0
        if interval <= 0:
            print(f"  Negative interval {interval:.3f}s at {t0:.3f}s, exiting")
            sys.exit(1)
        us_per_beat = round((interval / m) * 1_000_000)
        if meter >= 8:
            us_per_beat *= 2
        if us_per_beat <= 0:
            print(f"  Invalid tempo {us_per_beat} us/beat at {t0:.3f}s, exiting")
            sys.exit(1)
        if m != meter:
            meter = m
            events.append(("timesig", t0, meter, 4 if meter < 8 else 8))
        if text is not None:
            events.append(("text", t0, text))
        events.append(("tempo", t0, us_per_beat))

    if not any(e[0] == "tempo" for e in events):
        print("  No tempo events, exiting")
        sys.exit(1)

    event_order = {"timesig": 0, "text": 1, "tempo": 2}

    # Events should already be generated in-order; fail fast if not.
    for i in range(1, len(events)):
        prev = events[i - 1]
        curr = events[i]
        prev_key = (prev[1], event_order.get(prev[0], 99))
        curr_key = (curr[1], event_order.get(curr[0], 99))
        if curr_key < prev_key:
            print(
                "  Internal error: derived events are out of order, exiting "
                f"(index {i - 1}: {prev}, index {i}: {curr})"
            )
            sys.exit(1)
    return events


# ---------------------------------------------------------------------------
# MIDI writer (no external dependency for the actual file format)
# ---------------------------------------------------------------------------

TICKS_PER_BEAT = 480  # standard resolution, Reaper handles this well
# TODO rename to TICKS_PER_QUARTER


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


def _set_tempo_event(ticks: int, microseconds_per_beat: int) -> bytes:
    """Build a Set Tempo meta-event."""
    delta = _var_len(ticks)
    tempo_bytes = struct.pack(">I", microseconds_per_beat)[1:]  # 3 bytes
    return delta + b"\xff\x51\x03" + tempo_bytes


def _end_of_track_event(ticks: int = 0) -> bytes:
    return _var_len(ticks) + b"\xff\x2f\x00"


def _marker_event(ticks: int, label: str) -> bytes:
    """Build a Marker meta-event."""
    label_bytes = label.encode("utf-8")
    return _var_len(ticks) + b"\xff\x06" + _var_len(len(label_bytes)) + label_bytes


def _time_sig_event(ticks: int, numerator=4, denominator=4) -> bytes:
    """4/4 time signature meta-event (denominator encoded as power of 2)."""
    denom_pow = {1: 0, 2: 1, 4: 2, 8: 3, 16: 4}.get(denominator, 2)
    delta = _var_len(ticks)
    return delta + b"\xff\x58\x04" + bytes([numerator, denom_pow, 24, 8])


def write_tempo_midi(
    map_events: list[tuple],
    out_path: str,
):
    """
    Write a Type 1 MIDI file with tempo/time-sig/marker meta-events on track 0
    and an empty track 1. Type 1 is required for Reaper to recognise and
    offer to import the tempo map.

    Tick placement strategy:
      - Tempo events are always exported on a fixed musical grid.
      - If source has only bar markers: one event every bar (4/4 -> 1920 ticks).
      - If source has beat markers: one event every beat (480 ticks).
    """
    # --- Track 0: tempo map ---
    tempo_track = bytearray()

    print("  Writing MIDI events:")

    abs_tick = 0
    delta = 0
    next_delta = 0
    printed_tempo = 0
    skipped_tempo = 0
    printed_text = 0
    skipped_text = 0

    for event in map_events:
        etype = event[0]

        if etype == "text":
            _, _, text = event
            tempo_track += _marker_event(delta, text)
            if printed_text < 10:
                print(f"    [TX] Track 0 @ tick {abs_tick}: META Marker {text!r}")
                printed_text += 1
            else:
                skipped_text += 1
            delta = 0  # following events at the same musical position should stay there
        elif etype == "timesig":
            _, _, numerator, denominator = event
            tempo_track += _time_sig_event(
                delta, numerator=numerator, denominator=denominator
            )
            print(
                f"    [TS] Track 0 @ tick {abs_tick}: "
                f"META TimeSignature {numerator}/{denominator}"
            )
            delta = (
                0  # tempo event will follow. we want it after 0 ticks (in same place)
            )
            next_delta = (
                TICKS_PER_BEAT * numerator
            )  # then another event will follow, after this number of ticks
            if denominator == 8:
                next_delta = round(next_delta / 2)
        elif etype == "tempo":
            _, _, uspb = event
            tempo_track += _set_tempo_event(delta, uspb)
            if printed_tempo < 10:
                bpm = 60_000_000.0 / uspb
                print(
                    f"    [{printed_tempo + 1:2d}] Track 0 @ tick {abs_tick}: "
                    f"META SetTempo {uspb} us/beat ({bpm:.2f} BPM)"
                )
                printed_tempo += 1
            else:
                skipped_tempo += 1
            abs_tick += delta
            delta = next_delta
        else:
            print(f"  Internal error: unknown event type {etype}, exiting")
            sys.exit(1)

        abs_tick += delta
        prev_delta = delta

    if skipped_tempo > 0:
        print(f"    ... and {skipped_tempo} more tempo events")
    if skipped_text > 0:
        print(f"    ... and {skipped_text} more marker events")

    tempo_track += _end_of_track_event(0)
    print(f"    [E0] Track 0 @ tick {abs_tick}: META EndOfTrack")

    # --- Track 1: empty placeholder (required for Type 1) ---
    empty_track = bytearray()
    empty_track += _end_of_track_event(0)
    print("    [E1] Track 1 @ tick 0: META EndOfTrack")

    # --- Assemble file: Type 1, 2 tracks ---
    header = b"MThd" + struct.pack(">IHHH", 6, 1, 2, TICKS_PER_BEAT)
    track0 = b"MTrk" + struct.pack(">I", len(tempo_track)) + bytes(tempo_track)
    track1 = b"MTrk" + struct.pack(">I", len(empty_track)) + bytes(empty_track)

    with open(out_path, "wb") as f:
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
        mid_path = str(Path(xsc_path).with_suffix("")) + " tempo map.mid"

    print(f"Parsing: {xsc_path}")
    markers = parse_xsc(xsc_path)

    beats = [m for m in markers if m["type"] == "B"]
    meas = [m for m in markers if m["type"] == "M"]
    sects = [m for m in markers if m["type"] == "S"]
    print(
        f"  Found {len(sects)} section, {len(meas)} measure, {len(beats)} beat markers"
    )

    map_events = derive_tempo_map(markers)
    tempo_events = [e for e in map_events if e[0] == "tempo"]
    timesig_events = [e for e in map_events if e[0] == "timesig"]
    text_events = [e for e in map_events if e[0] == "text"]
    print(
        f"  Derived {len(map_events)} total event(s): "
        f"{len(tempo_events)} tempo, {len(timesig_events)} time signature, "
        f"{len(text_events)} marker text"
    )
    for e in map_events[:10]:
        if e[0] == "tempo":
            _, t, uspb = e
            print(f"    {t:8.3f}s  →  Tempo {uspb} us/beat")
        elif e[0] == "timesig":
            _, t, num, den = e
            print(f"    {t:8.3f}s  →  TimeSig {num}/{den}")
        else:
            _, t, text = e
            print(f"    {t:8.3f}s  →  Marker {text!r}")
    if len(map_events) > 10:
        print(f"    ... and {len(map_events) - 10} more")

    has_beats = len(beats) > 0
    if has_beats:
        print(f"  Not supported: beat markers, exiting")
        sys.exit(1)

    write_tempo_midi(map_events, mid_path)

    print()
    print("To import into Reaper:")
    print("  1. Preferences -> Media -> MIDI -> enable both:")
    print(
        "     - 'Always prompt to import tempo from MIDI files with simple tempo maps'"
    )
    print("     - Turn OFF 'Automatically adjust media to project tempo'")
    print("  2. Start with an empty project (no existing tempo markers)")
    print("  3. Insert -> Media File..., select the .mid, tick 'Import tempo map'")
    print("  4. Delete the empty MIDI item from the timeline if you don't need it")


if __name__ == "__main__":
    main()
