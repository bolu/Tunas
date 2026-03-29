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

def detect_bars_transcribe(input_file, output_wav, output_xsc, spec):
    """Detect bars and output in Transcribe format, plus audio with clicks."""

    # Check for meters_given mode and validate conditions
    meters_given = spec.get('meters_given', False)
    if meters_given:
        sections = spec.get('sections')
        if sections is None:
            print("Error: meters_given mode requires sections to be defined in the spec")
            sys.exit(1)

        # Check that ellipsis is not used
        if ... in sections:
            print("Error: meters_given mode does not support ellipsis (...) in sections")
            sys.exit(1)

        # Each section: [name, bar_count, meter_spec] where meter_spec is either an int or a list of bar_count elements
        for i, section in enumerate(sections):
            if not isinstance(section, list) or len(section) != 3:
                print(f"Error: In meters_given mode, section {i} must be a list of 3 elements [name, number_of_bars, meter_spec], got: {section}")
                sys.exit(1)
            _name, bar_count, meter_spec = section
            if isinstance(meter_spec, int):
                if meter_spec < 1:
                    print(f"Error: In meters_given mode, section {i} meter must be >= 1, got: {meter_spec}")
                    sys.exit(1)
            elif isinstance(meter_spec, list):
                if len(meter_spec) != bar_count:
                    print(f"Error: In meters_given mode, section {i} meter list length ({len(meter_spec)}) must equal bar_count ({bar_count})")
                    sys.exit(1)
                for j, m in enumerate(meter_spec):
                    if not isinstance(m, int) or m < 1:
                        print(f"Error: In meters_given mode, section {i} bar {j} meter must be a positive int, got: {m}")
                        sys.exit(1)
            else:
                print(f"Error: In meters_given mode, section {i} third element must be int (beats per bar) or a list of per-bar meters, got: {type(meter_spec).__name__}")
                sys.exit(1)

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

    # Save to temporary file (full-rate WAV for analysis; removed after run)
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as temp_file:
        temp_path = temp_file.name
        audio.export(temp_path, format="wav")

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

    # Extract bar positions (and subdivision beat times for XSC when beat_markers)
    if meters_given:
        # In meters_given mode, use section definitions to determine bar times
        sections = spec.get('sections')
        bar_times = []
        beat_times = []
        beat_idx = 0

        for section_name, num_bars, meter_spec in sections:
            if isinstance(meter_spec, int):
                meters_per_bar = [meter_spec] * num_bars
            else:
                meters_per_bar = meter_spec

            for meter in meters_per_bar:
                if beat_idx >= len(downbeats):
                    print(f"Warning: Not enough beats detected for section {section_name}")
                    break

                # The first beat of each bar is the bar start time
                bar_times.append(downbeats[beat_idx, 0])
                if spec.get('beat_markers'):
                    for off in range(1, meter):
                        if beat_idx + off < len(downbeats):
                            beat_times.append(downbeats[beat_idx + off, 0])

                # Skip to the next bar (meter beats per bar)
                beat_idx += meter
        
        if spec.get('beat_markers'):
            for i in range(beat_idx, len(downbeats)):
                beat_times.append(downbeats[i, 0])
    else:
        # Standard mode: extract bar positions from downbeats
        bar_times = downbeats[downbeats[:, 1] == 1][:, 0]
        if spec.get('beat_markers'):
            beat_times = downbeats[downbeats[:, 1] > 1][:, 0].tolist()

    # Extract all beat positions if beat_click is enabled
    click_beat_times = None
    if spec.get('beat_click'):
        click_beat_times = downbeats[downbeats[:, 1] > 0][:, 0]

    # Apply force equal spacing if specified
    force_equal_spacing = spec.get('force_equal_spacing')
    if force_equal_spacing:
        print("Applying force equal spacing...")
        bar_times = apply_force_equal_spacing(bar_times, force_equal_spacing)

    # Generate and write XSC file
    if output_xsc:
        write_xsc_file(bar_times, beat_times, output_wav, output_xsc, spec)

    # Generate audio with clicks
    if output_wav:
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
            if spec.get('beat_click') and click_beat_times is not None:
                print("Adding beat clicks...")
                beat_click = generate_click(800)

                # Create a set of bar times for fast lookup
                bar_times_set = set(bar_times)

                # Overlay beat clicks at each beat position (excluding downbeats)
                for beat_time in click_beat_times:
                    if beat_time not in bar_times_set:
                        position_ms = int(beat_time * 1000)  # Convert to milliseconds
                        audio = audio.overlay(beat_click, position=position_ms)

        wav_audio = (
            audio.set_frame_rate(22050).set_channels(1)
        )
        wav_audio.export(output_wav, format="wav")
        if no_click:
            print(f"Audio without clicks saved to {output_wav}")
        else:
            print(f"Audio with clicks saved to {output_wav}")

def write_xsc_file(bar_times, beat_times, output_wav, output_xsc, spec):
    """Generate and write XSC file from time strings and spec."""
    sections = spec.get('sections')
    if sections is None: sections = [32]

    # Generate Transcribe format output
    lines = []
    lines.append("XSC Transcribe.Document Version 6089.00")
    lines.append("Transcribe!,Windows,9,30,7,S,2")
    lines.append("")
    lines.append("SectionStart,Main")
    lines.append(f"SoundFileName,{output_wav},Win,x")
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
    lines.append(f"Howmany,{len(bar_times) + len(beat_times)}")

    bars_left = bar_times.copy()
    bars_left.reverse()

    beats_left = beat_times.copy()
    beats_left.reverse()

    sections = fill_ellipsis(sections, len(bar_times))
    for section in sections:
        section_name, section_length = section[:2]
        for i in range(section_length):
            while len(beats_left) and beats_left[-1] < bars_left[-1]:
                time_str = seconds_to_transcribe_time(beats_left.pop())
                lines.append(f"B,-1,0,,1,{time_str}")
            time_str = seconds_to_transcribe_time(bars_left.pop())
            marker = 'S' if i == 0 else 'M'
            label = section_name if i == 0 else i+1
            generate = 0
            lines.append(f"{marker},-1,{generate},{label},1,{time_str}")
    while len(beats_left):
        time_str = seconds_to_transcribe_time(beats_left.pop())
        lines.append(f"B,-1,0,,1,{time_str}")
    lines.append("SectionEnd,Markers")
    output = "\n".join(lines)

    # Write to file
    with open(output_xsc, 'w') as f:
        f.write(output)
    print(f"Saved to {output_xsc}")

def regenerate_xsc_from_existing(wav_file, xsc_file, spec):
    """Regenerate .xsc file from existing markers when both .wav and .xsc files exist."""
    if spec.get('beat_markers'):
        print("Error: XSC regeneration is not supported when beat_markers is enabled.")
        print("Turn off beat_markers in the .tun spec, or delete the .xsc (and optionally .wav) to rebuild from the source audio.")
        sys.exit(1)

    print("Both .wav and .xsc files already exist. Regenerating .xsc file from existing markers...")
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
    write_xsc_file(marker_times, [], wav_file, xsc_file, spec)

def read_spec(filename):
    try:
        with open(filename, 'r') as f:
            content = f.read()
            return ast.literal_eval(content)
    except FileNotFoundError:
        print(f"File {filename} not found, creating it...")
        with open(filename, 'w') as f:
            f.write("{\n'sections': [\n32,\n],\n}")
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

# Disallow parentheses in the input filename to avoid issues with derived file names.
if '(' in filename or ')' in filename:
    print(f"Error: parentheses are not allowed in file names: {filename}")
    sys.exit(1)

basename = Path(filename).stem
spec_file = basename + '.tun'
wav_file = basename + ".wav"
xsc_file = basename + ".xsc"

spec = read_spec(spec_file)

# Check if both .wav and .xsc files already exist
if os.path.exists(wav_file) and os.path.exists(xsc_file):
    regenerate_xsc_from_existing(wav_file, xsc_file, spec)
else:
    detect_bars_transcribe(filename, wav_file, xsc_file, spec)

# --- Spec file format (.tun) ---
#
# The spec is a Python literal dict read with ast.literal_eval (same basename as the
# audio file, extension .tun). Use Python dict/list syntax (single-quoted strings OK).
#
# sections (default [32])
#   Defines how bar markers are grouped and labeled in the Transcribe .xsc output.
#   The total bar count does not have to match the number of detected bars (last section absorbs
#   any remainder). Each entry is one of:
#     - int: bar count for an unnamed section (becomes "Section N").
#     - [name, bar_count]: named section with that many bars.
#     - ... (ellipsis): repeat the previous section as many times as fit without
#       exceeding the total bar count; the repeated section is named "Chorus 1",
#       "Chorus 2", ... (or "<name> 1", "<name> 2" if the previous section had a name).
#   In meters_given mode (see below), each section must be a triple:
#     [name, bar_count, meter]
#   with meter = beats per bar for every bar in the section (int), or
#     [name, bar_count, [meter_bar_1, meter_bar_2, ..., meter_bar_bar_count]]
#   when the time signature varies bar by bar (list length must equal bar_count).
#   Ellipsis is not allowed in meters_given mode.
#
# meters_given (default False)
#   If True, bar boundaries follow section definitions: for each bar, the given number
#   of beats are consumed from the downbeat stream. Sections are [name, bar_count, meter]
#   with meter int or a list of bar_count ints.
#
# meters (default [4])
#   Beats per bar passed to madmom downbeat tracking (list, one value or per-bar
#   pattern as madmom expects).
#
# tempo (default [55, 215])
#   If omitted, BPM search range is 55–215. If a number, range is tempo * [0.94, 1.06].
#   If [min_bpm, max_bpm], that range is used as-is.
#
# silence_threshold (default -80.0)
#   dBFS threshold for trimming leading silence (and trailing unless keep_trailing_silence).
#
# keep_trailing_silence (default False)
#   If truthy, do not trim trailing silence after the leading trim.
#
# transition_lambda (default 100)
#   madmom DBNDownBeatTrackingProcessor transition_lambda.
#
# force_equal_spacing (default False)
#   List of [start, end] intervals. Within each interval (inclusive), intermediate
#   bar markers are redistributed with equal spacing; first and last markers in the
#   interval stay fixed. Times use the same string format as below.
#
# beat_click (default False)
#   If truthy, overlay a click on every beat (800 Hz), not only downbeats (1000 Hz).
#
# beat_markers (default False)
#   If truthy, emit a beat marker to XSC file for every beat (in addition to bar markers).
#
# no_click (default False)
#   If truthy, export .wav without any click overlay.
#
# Time strings (force_equal_spacing intervals)
#   "H:MM.SS" — hours, minutes, and seconds with fractional part in hundredths (not
#   base-60). Example: "0:22.71" is 22 minutes and 0.71 seconds = 1320.71 s wall time.
