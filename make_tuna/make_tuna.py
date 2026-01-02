import madmom
import numpy as np
from pydub import AudioSegment
from pydub.effects import normalize
from pydub.generators import Sine
from pydub.silence import detect_leading_silence
import sys
import re
from pathlib import Path
import os
import tempfile
import ast

def parse_time_format(time_str):
    """Parse 'hours:minutes.seconds' format to seconds.
    Format: H:MM.SS where MM.SS means MM minutes and SS hundredths of a second.
    Example: '0:22.71' = 0 hours + 22 minutes + 0.71 seconds = 1320.71 seconds"""
    parts = time_str.split(':')
    if len(parts) != 2:
        raise ValueError(f"Invalid time format: {time_str}. Expected 'H:MM.SS'")

    hours_str, minutes_seconds_str = parts
    hours = int(hours_str)

    # Parse minutes.seconds part
    minutes_seconds_parts = minutes_seconds_str.split('.')
    if len(minutes_seconds_parts) != 2:
        raise ValueError(f"Invalid minutes.seconds format: {minutes_seconds_str}. Expected 'MM.SS'")

    minutes = int(minutes_seconds_parts[0])
    hundredths = int(minutes_seconds_parts[1])

    total_seconds = hours * 3600 + minutes * 60 + hundredths / 100.0
    return total_seconds

def seconds_to_transcribe_time(seconds):
    """Convert seconds to Transcribe format: H:MM:SS.microseconds"""
    hours = int(seconds // 3600)
    minutes = int((seconds % 3600) // 60)
    secs = int(seconds % 60)
    microseconds = int((seconds % 1) * 1000000)
    return f"{hours}:{minutes:02d}:{secs:02d}.{microseconds:06d}"

def apply_force_equal_spacing(bar_times, force_equal_spacing_intervals):
    """Apply force equal spacing to bar times within specified intervals.

    For each interval, keeps first and last markers intact and spaces
    intermediate markers equally between them.
    """
    if not force_equal_spacing_intervals:
        return bar_times

    # Sort bar times
    bar_times = np.sort(bar_times)

    # Process each interval
    for interval in force_equal_spacing_intervals:
        if len(interval) != 2:
            print(f"Warning: Invalid interval format {interval}, skipping")
            continue

        try:
            start_time = parse_time_format(interval[0])
            end_time = parse_time_format(interval[1])
        except ValueError as e:
            print(f"Warning: Could not parse interval {interval}: {e}, skipping")
            continue

        # Find markers within this interval (inclusive)
        within_interval = (bar_times >= start_time) & (bar_times <= end_time)
        interval_indices = np.where(within_interval)[0]

        if len(interval_indices) < 3:
            # Not enough markers to redistribute, skip
            continue

        # Get the markers in this interval
        first_idx = interval_indices[0]
        last_idx = interval_indices[-1]
        intermediate_indices = interval_indices[1:-1]

        if len(intermediate_indices) == 0:
            continue

        # Keep first and last markers as-is
        first_time = bar_times[first_idx]
        last_time = bar_times[last_idx]

        # Calculate equal spacing for intermediate markers
        num_intermediate = len(intermediate_indices)
        time_span = last_time - first_time
        spacing = time_span / (num_intermediate + 1)

        # Redistribute intermediate markers
        for i, idx in enumerate(intermediate_indices):
            new_time = first_time + (i + 1) * spacing
            bar_times[idx] = new_time

    return bar_times

def generate_click(frequency=1000):
    """Generate a short click sound."""
    # Create a short sine wave (50ms duration)
    click = Sine(frequency).to_audio_segment(duration=50)
    # Fade in/out to avoid pops
    click = click.fade_in(5).fade_out(5)
    return click - 17

def fill_ellipsis(sections, bars):
    # Promote all sections to ['Name', length] format
    for i in range(len(sections)):
        if not isinstance(sections[i], list) and sections[i] != ...:
            sections[i] = [None, sections[i]]

    if ... in sections:
        # Replace ellipsis with adequate number of repetitions of the chorus (previous section).
        before = []
        after = []
        was = False
        chorus = None
        for s in sections:
            if s == ...:
                was = True
                chorus = prev
            elif was:
                after.append(s)
            else:
                before.append(s)
            prev = s

        # Adequate means maximum without exceeding total number of bars.
        chorus_length = chorus[1]
        chorus_name = chorus[0] or 'Chorus'
        chorus[0] = f"{chorus_name} 1"

        # Number the choruses starting from 2, obviously
        chorus_number = 2
        while sum(s[1] for s in before + after) + chorus_length <= bars:
            numbered_chorus = [f"{chorus_name} {chorus_number}", chorus_length]
            before.append(numbered_chorus)
            chorus_number += 1

        sections = before + after

    # Make last section longer to include any ending if one exists.
    sections[-1][1] += bars - sum(s[1] for s in sections)

    for i in range(len(sections)):
        if sections[i][0] is None:
            sections[i][0] = f'Section {i+1}'
    return sections

def detect_bars_transcribe(input_file, output_ogg, output_xsc, spec):
    """Detect bars and output in Transcribe format, plus audio with clicks."""

    beats_per_bar = spec.get('meters', [4])
    tempo = spec.get('tempo')

    if tempo is None:
        min_bpm = 55.0
        max_bpm = 215.0
    elif isinstance(tempo, list):
        min_bpm = tempo[0] * 1.0
        max_bpm = tempo[1] * 1.0
    else:
        min_bpm = tempo * 0.94
        max_bpm = tempo * 1.06

    # Get silence threshold from spec (default -80.0 dBFS)
    silence_threshold = spec.get('silence_threshold', -80.0)

    # Load audio and remove silence
    print("Removing silence from audio...")
    audio = AudioSegment.from_file(input_file)

    # Remove leading silence
    start_trim = detect_leading_silence(audio, silence_threshold=silence_threshold)
    audio = audio[start_trim:]

    # Remove trailing silence
    if not spec.get('keep_trailing_silence'):
        end_trim = detect_leading_silence(audio.reverse(), silence_threshold=silence_threshold)
        audio = audio[:-end_trim] if end_trim > 0 else audio

    audio = normalize(audio)

    # Save to temporary file
    with tempfile.NamedTemporaryFile(suffix='.ogg', delete=False) as temp_file:
        temp_path = temp_file.name
        audio.export(temp_path, format="ogg")

    # Process audio for downbeat detection
    print(f"Analyzing audio... {temp_path} {beats_per_bar} {min_bpm} {max_bpm}")
    proc = madmom.features.downbeats.DBNDownBeatTrackingProcessor(
        beats_per_bar=beats_per_bar,
        fps=100,
        min_bpm=min_bpm,
        max_bpm=max_bpm,
        # https://madmom.readthedocs.io/en/v0.16/modules/features/downbeats.html
        transition_lambda=spec.get('transition_lambda', 100)
    )
    act = madmom.features.downbeats.RNNDownBeatProcessor()(temp_path)
    downbeats = proc(act)
    
    # Extract bar positions (downbeats)
    bar_times = downbeats[downbeats[:, 1] == 1][:, 0]

    # Extract all beat positions if beat_click is enabled
    beat_times = None
    if spec.get('beat_click'):
        beat_times = downbeats[downbeats[:, 1] > 0][:, 0]

    # Apply force equal spacing if specified
    force_equal_spacing = spec.get('force_equal_spacing')
    if force_equal_spacing:
        print("Applying force equal spacing...")
        bar_times = apply_force_equal_spacing(bar_times, force_equal_spacing)

    # Convert bar times to time strings
    time_strings = [seconds_to_transcribe_time(t) for t in bar_times]

    # Generate and write XSC file
    if output_xsc:
        write_xsc_file(time_strings, output_ogg, output_xsc, spec)

    # Generate audio with clicks
    if output_ogg:
        no_click = spec.get('no_click', False)
        if no_click:
            print("\nGenerating audio without clicks...")
        else:
            print("\nGenerating audio with clicks...")

        # Generate downbeat click sound (1000Hz) unless no_click is enabled
        if not no_click:
            downbeat_click = generate_click(1000)

            # Overlay downbeat clicks at each bar position
            for bar_time in bar_times:
                position_ms = int(bar_time * 1000)  # Convert to milliseconds
                audio = audio.overlay(downbeat_click, position=position_ms)

            # Generate additional beat clicks if beat_click is enabled
            if spec.get('beat_click') and beat_times is not None:
                print("Adding beat clicks...")
                beat_click = generate_click(800)

                # Create a set of bar times for fast lookup
                bar_times_set = set(bar_times)

                # Overlay beat clicks at each beat position (excluding downbeats)
                for beat_time in beat_times:
                    if beat_time not in bar_times_set:
                        position_ms = int(beat_time * 1000)  # Convert to milliseconds
                        audio = audio.overlay(beat_click, position=position_ms)

        # Export the result
        audio.export(output_ogg, format="ogg")
        if no_click:
            print(f"Audio without clicks saved to {output_ogg}")
        else:
            print(f"Audio with clicks saved to {output_ogg}")

def write_xsc_file(time_strings, output_ogg, output_xsc, spec):
    """Generate and write XSC file from time strings and spec."""
    sections = spec.get('sections')
    if sections is None: sections = [32, ...]

    # Generate Transcribe format output
    lines = []
    lines.append("XSC Transcribe.Document Version 6089.00")
    lines.append("Transcribe!,Windows,9,30,7,S,2")
    lines.append("")
    lines.append("SectionStart,Main")
    lines.append(f"SoundFileName,{output_ogg},Win,x")
    lines.append("WindowSize,0|0|0|0,1")
    lines.append("ViewList,1,0,0.00000000")
    lines.append("SectionEnd,Main")
    lines.append("")
    lines.append("SectionStart,View0")
    lines.append("FitWholeFile,1")
    lines.append("LoopMode,0")
    lines.append("SectionEnd,View0")
    lines.append("")
    lines.append("SectionStart,Markers")
    lines.append(f"Howmany,{len(time_strings)}")

    left = time_strings.copy()
    left.reverse()

    sections = fill_ellipsis(sections, len(left))
    for section in sections:
        section_name, section_length = section
        for i in range(section_length):
            time_str = left.pop()
            marker = 'S' if i == 0 else 'M'
            label = section_name if i == 0 else i+1
            generate = 0
            lines.append(f"{marker},-1,{generate},{label},1,{time_str}")

    lines.append("SectionEnd,Markers")

    output = "\n".join(lines)

    # Write to file
    with open(output_xsc, 'w') as f:
        f.write(output)
    print(f"Saved to {output_xsc}")

def regenerate_xsc_from_existing(ogg_file, xsc_file, spec):
    """Regenerate .xsc file from existing markers when both .ogg and .xsc files exist."""
    print("Both .ogg and .xsc files already exist. Regenerating .xsc file from existing markers...")

    # Read existing .xsc file to extract marker times
    marker_times = []
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
                    # Extract time from the last part (format: H:MM:SS.microseconds)
                    time_str = parts[5]
                    marker_times.append(time_str)
    except Exception as e:
        print(f"Error reading existing .xsc file: {e}")
        sys.exit(1)

    if not marker_times:
        print("No markers found in existing .xsc file")
        sys.exit(1)

    # Use the shared function to regenerate the XSC file
    write_xsc_file(marker_times, ogg_file, xsc_file, spec)

def read_spec(filename):
    try:
        with open(filename, 'r') as f:
            content = f.read()
            return ast.literal_eval(content)
    except FileNotFoundError:
        print(f"File {filename} not found, creating it...")
        with open(filename, 'w') as f:
            f.write("{\n'sections': [\n32,\n...,\n],\n}")
        return {}
    except (ValueError, SyntaxError) as e:
        print(f"Error parsing file: {e}")
        return {}

# Test functions if run with --test argument
if len(sys.argv) == 2 and sys.argv[1] == '--test':
    print("Testing force_equal_spacing functionality...")

    # Test time parsing
    test_times = ['0:22.71', '0:44.57', '1:20.89', '7:25.46']
    expected_seconds = [1320.71, 2640.57, 4800.89, 26700.46]

    print("Testing time parsing:")
    for time_str, expected in zip(test_times, expected_seconds):
        parsed = parse_time_format(time_str)
        print(f"  {time_str} -> {parsed:.2f}s (expected {expected:.2f}s)")
        assert abs(parsed - expected) < 0.01, f"Parsing failed for {time_str}"

    # Test equal spacing
    print("\nTesting equal spacing:")
    # Create test bar times: 1000, 1100, 1150, 1200, 1300 seconds
    bar_times = np.array([1000.0, 1100.0, 1150.0, 1200.0, 1300.0])
    # Use time strings that correspond to 960.4s to 1260.4s (0:16.40 to 0:21.40)
    intervals = [['0:16.40', '0:21.40']]

    result = apply_force_equal_spacing(bar_times.copy(), intervals)
    print(f"  Original: {bar_times}")
    print(f"  After spacing: {result}")

    # The interval 0:16.40 to 0:21.40 covers 960.4s to 1260.4s
    # Markers within interval: 1000, 1100, 1150, 1200 (1300 is outside)
    # First=1000, last=1200, intermediates=1100,1150 (2 intermediates)
    # Time span = 1200 - 1000 = 200s, spacing = 200/(2+1) = 66.666...
    # Intermediates at: 1000 + 66.67 = 1066.67, 1000 + 133.33 = 1133.33
    expected = np.array([1000.0, 1066.67, 1133.33, 1200.0, 1300.0])
    print(f"  Expected: {expected}")

    # Check if results match expected (within tolerance)
    if np.allclose(result, expected, atol=0.1):
        print("  ✓ Equal spacing test passed!")
    else:
        print("  ✗ Equal spacing test failed!")
        print(f"  Got: {result}")
        sys.exit(1)

    print("Test completed successfully!")
    sys.exit(0)

if len(sys.argv) < 2:
    print("Usage: python script.py <filename>")
    print("Example: python script.py nardis.mp3")
    print("Or: python script.py --test")
    sys.exit(1)

filename = sys.argv[1]
basename = Path(filename).stem
spec_file = basename + '.tun'
ogg_file = basename + '.ogg'
xsc_file = basename + '.xsc'

spec = read_spec(spec_file)

# Check if both .ogg and .xsc files already exist
if os.path.exists(ogg_file) and os.path.exists(xsc_file):
    regenerate_xsc_from_existing(ogg_file, xsc_file, spec)
else:
    detect_bars_transcribe(filename, ogg_file, xsc_file, spec)
