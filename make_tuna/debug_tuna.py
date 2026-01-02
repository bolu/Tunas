#!/usr/bin/env python3
import sys
from pathlib import Path
import re

def transcribe_time_to_seconds(time_str):
    """Convert Transcribe time format (H:MM:SS.microseconds) to seconds."""
    match = re.match(r'(\d+):(\d{2}):(\d{2})\.(\d{6})', time_str)
    if not match:
        raise ValueError(f"Invalid time format: {time_str}")

    hours, minutes, seconds, microseconds = map(int, match.groups())
    return hours * 3600 + minutes * 60 + seconds + microseconds / 1000000

def read_xsc_markers(xsc_file):
    """Read markers from XSC file and return list of (time_str, label) tuples."""
    markers = []
    try:
        with open(xsc_file, 'r') as f:
            lines = f.readlines()

        in_markers_section = False
        for line in lines:
            line = line.strip()
            if line == "SectionStart,Markers":
                in_markers_section = True
            elif line == "SectionEnd,Markers":
                in_markers_section = False
            elif in_markers_section and ',' in line:
                parts = line.split(',')
                if len(parts) >= 6:
                    marker_type = parts[0]  # 'S' for section start, 'M' for bar marker
                    label = parts[3]
                    time_str = parts[5]
                    markers.append((time_str, label, marker_type))

    except Exception as e:
        print(f"Error reading XSC file: {e}")
        sys.exit(1)

    return markers

def calculate_bar_info(markers):
    """Calculate duration and BPM for each bar marker."""
    if not markers:
        return []

    bar_info = []

    # Convert all times to seconds for easier calculation
    marker_times = []
    for time_str, label, marker_type in markers:
        try:
            time_seconds = transcribe_time_to_seconds(time_str)
            marker_times.append((time_seconds, label, marker_type, time_str))
        except ValueError as e:
            print(f"Skipping invalid marker: {e}")
            continue

    # Calculate duration and BPM for consecutive markers
    for i in range(len(marker_times)):
        current_time, label, marker_type, time_str = marker_times[i]

        if i < len(marker_times) - 1:
            next_time = marker_times[i + 1][0]
            duration_seconds = next_time - current_time
        else:
            # Last marker - can't calculate duration
            duration_seconds = None

        if duration_seconds and duration_seconds > 0:
            # Assuming 4/4 time signature: 4 beats per bar
            beats_per_bar = 4
            duration_per_beat = duration_seconds / beats_per_bar
            bpm = 60 / duration_per_beat if duration_per_beat > 0 else 0
        else:
            bpm = None

        bar_info.append({
            'time_str': time_str,
            'label': label,
            'marker_type': marker_type,
            'duration_seconds': duration_seconds,
            'bpm': bpm
        })

    return bar_info

def main():
    if len(sys.argv) != 2:
        print("Usage: python debug_tuna.py <input_file>")
        print("Example: python debug_tuna.py nardis.mp3")
        print("This will read nardis.xsc and display marker debug information")
        sys.exit(1)

    input_file = sys.argv[1]
    basename = Path(input_file).stem
    xsc_file = basename + '.xsc'

    print(f"Reading markers from: {xsc_file}")

    if not Path(xsc_file).exists():
        print(f"Error: {xsc_file} not found")
        sys.exit(1)

    markers = read_xsc_markers(xsc_file)

    if not markers:
        print("No markers found in the XSC file")
        sys.exit(1)

    print(f"Found {len(markers)} markers")
    print()

    bar_info = calculate_bar_info(markers)

    print("Marker Debug Information:")
    print("-" * 80)
    print(f"{'Time':<15} {'Label':<20} {'Type':<5} {'Duration (s)':<12} {'BPM (4/4)':<10}")
    print("-" * 80)

    for info in bar_info:
        time_str = info['time_str']
        label = info['label']
        marker_type = info['marker_type']
        duration = f"{info['duration_seconds']:.3f}" if info['duration_seconds'] is not None else "N/A"
        bpm = f"{info['bpm']:.1f}" if info['bpm'] is not None else "N/A"

        print(f"{time_str:<15} {label:<20} {marker_type:<5} {duration:<12} {bpm:<10}")

if __name__ == "__main__":
    main()
