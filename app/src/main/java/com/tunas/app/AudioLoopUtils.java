package com.tunas.app;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.source.ClippingMediaSource;
import com.google.android.exoplayer2.source.LoopingMediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.TransferListener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Utility class for creating seamless looped audio playback for musical practice.
 *
 * APPROACHES:
 * 1. Decode compressed audio to PCM and wrap in WAV format
 * 2. Decode to raw PCM and configure ExoPlayer accordingly
 * 3. Use ExoPlayer's built-in clipping and looping mechanisms
 *
 * This provides gap-free playback by serving properly formatted audio data to ExoPlayer.
 */
public class AudioLoopUtils {

    /**
     * Custom DataSource that serves looped audio data from memory.
     * Provides seamless looping by serving the same audio clip multiple times
     * as a continuous data stream.
     */
    public static class LoopedAudioDataSource implements DataSource {
        private final byte[] audioData;
        private final long loopCount;
        private long position;
        private boolean opened;

        public LoopedAudioDataSource(byte[] audioData, long loopCount) {
            this.audioData = audioData;
            this.loopCount = loopCount;
            this.position = 0;
            this.opened = false;
            Log.d("Tunas", "LoopedAudioDataSource: Created with audioData.length=" + audioData.length +
                  ", loopCount=" + loopCount + ", totalSize=" + (audioData.length * loopCount));
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            opened = true;
            position = dataSpec.position;
            long totalSize = audioData.length * loopCount;
            Log.d("Tunas", "LoopedAudioDataSource.open: Opened with position=" + position +
                  ", audioData.length=" + audioData.length + ", loopCount=" + loopCount +
                  ", totalSize=" + totalSize + ", uri=" + (dataSpec.uri != null ? dataSpec.uri.toString() : "null") +
                  ", flags=" + dataSpec.flags + ", length=" + dataSpec.length);

            if (totalSize <= 0) {
                Log.e("Tunas", "LoopedAudioDataSource.open: Invalid total size: " + totalSize);
                throw new IOException("Invalid data size");
            }

            // Return the total size (original clip size * loop count)
            return totalSize;
        }

        @Override
        public int read(byte[] buffer, int offset, int readLength) throws IOException {
            if (!opened) {
                Log.e("Tunas", "LoopedAudioDataSource.read: DataSource not opened!");
                throw new IOException("DataSource not opened");
            }

            if (readLength == 0) {
                return 0;
            }

            // Debug: Log that read was called
            if (position == 0) {
                Log.d("Tunas", "LoopedAudioDataSource.read: FIRST READ CALL - readLength=" + readLength);
            }

            // Calculate which loop we're in and position within that loop
            long virtualPosition = position;
            long loopSize = audioData.length;
            long loopIndex = virtualPosition / loopSize;

            // If we've exceeded the loop count, end of stream
            if (loopIndex >= loopCount) {
                Log.d("Tunas", "LoopedAudioDataSource.read: End of stream reached (loopIndex=" +
                      loopIndex + " >= loopCount=" + loopCount + ")");
                return C.RESULT_END_OF_INPUT;
            }

            // Position within the current loop
            long positionInLoop = virtualPosition % loopSize;

            // Calculate how much we can read from this loop
            long remainingInLoop = loopSize - positionInLoop;
            int actualReadLength = (int) Math.min(readLength, remainingInLoop);

            // Copy data from the audio buffer
            System.arraycopy(audioData, (int) positionInLoop, buffer, offset, actualReadLength);

            position += actualReadLength;

            // Log read operations periodically
            if (position % (1024 * 1024) < actualReadLength) { // Log roughly every 1MB
                Log.d("Tunas", "LoopedAudioDataSource.read: Read " + actualReadLength +
                      " bytes (position=" + position + ", loopIndex=" + loopIndex +
                      ", positionInLoop=" + positionInLoop + ", remaining=" + (audioData.length - positionInLoop) + ")");
            }

            return actualReadLength;
        }

        @Override
        public Uri getUri() {
            return Uri.parse("looped_audio"); // Return the same URI as MediaItem
        }

        @Override
        public void close() throws IOException {
            Log.d("Tunas", "LoopedAudioDataSource.close: Closing data source at position=" + position);
            opened = false;
        }

        @Override
        public void addTransferListener(TransferListener transferListener) {
            // Optional: implement if you need transfer tracking
        }
    }

    /**
     * Factory for creating LoopedAudioDataSource instances.
     */
    public static class LoopedAudioDataSourceFactory implements DataSource.Factory {
        private final byte[] audioData;
        private final long loopCount;

        public LoopedAudioDataSourceFactory(byte[] audioData, long loopCount) {
            this.audioData = audioData;
            this.loopCount = loopCount;
            Log.d("Tunas", "LoopedAudioDataSourceFactory: Created with audioData.length=" + audioData.length +
                  ", loopCount=" + loopCount);
        }

        @Override
        public DataSource createDataSource() {
            return new LoopedAudioDataSource(audioData, loopCount);
        }
    }


    /**
     * Decodes a portion of compressed audio to PCM data and wraps it in WAV format for seamless looping.
     *
     * @param audioFile The source audio file
     * @param startMs Start time in milliseconds
     * @param endMs End time in milliseconds
     * @param loopCount Number of times the clip will be looped (for WAV header sizing)
     * @return Byte array containing WAV-formatted PCM data ready for ExoPlayer
     * @throws IOException If decoding fails
     */
    public static byte[] decodeAudioClipToWav(File audioFile, long startMs, long endMs, int loopCount) throws IOException {
        Log.d("Tunas", "decodeAudioClipToWav: Starting PCM decoding from " + audioFile.getName() +
              ", startMs=" + startMs + ", endMs=" + endMs);

        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;

        try {
            extractor.setDataSource(audioFile.getAbsolutePath());
            extractor.selectTrack(0);

            MediaFormat format = extractor.getTrackFormat(0);
            String mime = format.getString(MediaFormat.KEY_MIME);
            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

            Log.d("Tunas", "decodeAudioClipToWav: Format - " + mime + ", " + sampleRate + "Hz, " + channelCount + " channels");

            // Seek to start position with maximum precision for musical timing
            long startTimeUs = startMs * 1000;
            extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

            // Fine-tune to exact start position by advancing sample by sample if needed
            long currentSampleTime = extractor.getSampleTime();
            Log.d("Tunas", "decodeAudioClipToWav: Initial seek to " + startTimeUs + "us, got " + currentSampleTime + "us");

            // If we're before the target time, advance until we reach or pass it
            if (currentSampleTime < startTimeUs) {
                while (extractor.advance()) {
                    currentSampleTime = extractor.getSampleTime();
                    if (currentSampleTime >= startTimeUs) {
                        break;
                    }
                }
                Log.d("Tunas", "decodeAudioClipToWav: Advanced to " + currentSampleTime + "us");
            }

            // Calculate timing precision
            long startOffsetUs = currentSampleTime - startTimeUs;
            Log.d("Tunas", "decodeAudioClipToWav: Start timing offset: " + startOffsetUs + "us (" + (startOffsetUs / 1000.0) + "ms)");

            // Create decoder
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(format, null, null, 0);
            decoder.start();

            ByteArrayOutputStream pcmOutput = new ByteArrayOutputStream();
            ByteBuffer[] inputBuffers = decoder.getInputBuffers();
            ByteBuffer[] outputBuffers = decoder.getOutputBuffers();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            long endTimeUs = endMs * 1000;
            boolean done = false;
            boolean inputDone = false;
            long totalPcmBytes = 0;

            while (!done) {
                // Feed input
                if (!inputDone) {
                    int inputBufferIndex = decoder.dequeueInputBuffer(10000);
                    if (inputBufferIndex >= 0) {
                        ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                        inputBuffer.clear();

                        int sampleSize = extractor.readSampleData(inputBuffer, 0);
                        long sampleTime = extractor.getSampleTime();

                        if (sampleSize < 0) {
                            // End of stream
                            decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                            Log.d("Tunas", "decodeAudioClipToWav: End of input stream");
                        } else if (sampleTime >= endTimeUs) {
                            // Reached end time
                            decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                            Log.d("Tunas", "decodeAudioClipToWav: Reached end time " + sampleTime + "us");
                        } else {
                            // Feed sample to decoder
                            decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, sampleTime, 0);
                            extractor.advance();
                        }
                    }
                }

                // Get output
                int outputBufferIndex = decoder.dequeueOutputBuffer(info, 10000);
                if (outputBufferIndex >= 0) {
                    ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];

                    if (info.size > 0) {
                        byte[] pcmChunk = new byte[info.size];
                        outputBuffer.get(pcmChunk);
                        pcmOutput.write(pcmChunk);
                        totalPcmBytes += info.size;
                    }

                    outputBuffer.clear();
                    decoder.releaseOutputBuffer(outputBufferIndex, false);

                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        done = true;
                        Log.d("Tunas", "decodeAudioClipToWav: Decoding complete, total PCM bytes: " + totalPcmBytes);
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outputBuffers = decoder.getOutputBuffers();
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Output format may change, but we don't need to handle it for PCM
                }
            }

            // Apply fades to PCM data for seamless looping
            byte[] pcmData = pcmOutput.toByteArray();
            pcmData = applyLoopFades(pcmData, sampleRate, channelCount);

            // Calculate expected duration vs actual duration for timing analysis
            long expectedDurationUs = (endMs - startMs) * 1000L;
            long actualDurationUs = (long) ((pcmData.length / (channelCount * 2.0)) / sampleRate * 1000000);
            long durationDiffUs = actualDurationUs - expectedDurationUs;

            Log.d("Tunas", "decodeAudioClipToWav: Duration analysis:");
            Log.d("Tunas", "decodeAudioClipToWav:   Requested: " + expectedDurationUs + "us (" + (expectedDurationUs / 1000.0) + "ms)");
            Log.d("Tunas", "decodeAudioClipToWav:   Actual: " + actualDurationUs + "us (" + (actualDurationUs / 1000.0) + "ms)");
            Log.d("Tunas", "decodeAudioClipToWav:   Difference: " + durationDiffUs + "us (" + (durationDiffUs / 1000.0) + "ms)");

            // Create WAV header with total size for all loops
            long totalPcmSize = (long) pcmData.length * loopCount;
            byte[] wavData = createWavHeader(pcmData.length, totalPcmSize, sampleRate, channelCount, 16); // 16-bit PCM
            byte[] finalWav = new byte[wavData.length + pcmData.length];
            System.arraycopy(wavData, 0, finalWav, 0, wavData.length);
            System.arraycopy(pcmData, 0, finalWav, wavData.length, pcmData.length);

            // Debug: Log first 50 bytes of WAV header
            StringBuilder headerHex = new StringBuilder("WAV header bytes: ");
            for (int i = 0; i < Math.min(50, finalWav.length); i++) {
                headerHex.append(String.format("%02X ", finalWav[i]));
            }
            Log.d("Tunas", headerHex.toString());

            // Also log the calculated values
            Log.d("Tunas", "WAV header calc: pcmLength=" + pcmData.length +
                  ", totalPcmSize=" + totalPcmSize +
                  ", totalFileSize=" + (44 + totalPcmSize) +
                  ", riffChunkSize=" + (44 + totalPcmSize - 8) +
                  ", sampleRate=" + sampleRate + ", channels=" + channelCount +
                  ", loopCount=" + loopCount);

            Log.d("Tunas", "decodeAudioClipToWav: Created WAV data, total size: " + finalWav.length + " bytes (header + 1 clip)");
            return finalWav;

        } finally {
            if (decoder != null) {
                decoder.stop();
                decoder.release();
            }
            extractor.release();
        }
    }

    /**
     * Creates a WAV header for PCM data with specified total size.
     * For looped playback, totalSize should be pcmDataLength * loopCount.
     */
    private static byte[] createWavHeader(long pcmDataLength, long totalSize, int sampleRate, int channels, int bitsPerSample) {
        long totalFileSize = 44 + totalSize; // 44 bytes header + total PCM data
        long riffChunkSize = totalFileSize - 8;   // RIFF size = total file size - 8
        long byteRate = sampleRate * channels * bitsPerSample / 8;

        ByteBuffer header = ByteBuffer.allocate(44);
        header.order(java.nio.ByteOrder.LITTLE_ENDIAN);

        // RIFF header
        header.put("RIFF".getBytes());
        header.putInt((int) riffChunkSize);
        header.put("WAVE".getBytes());

        // Format chunk
        header.put("fmt ".getBytes());
        header.putInt(16); // Chunk size
        header.putShort((short) 1); // PCM format
        header.putShort((short) channels);
        header.putInt(sampleRate);
        header.putInt((int) byteRate);
        header.putShort((short) (channels * bitsPerSample / 8));
        header.putShort((short) bitsPerSample);

        // Data chunk - indicates total size for all loops
        header.put("data".getBytes());
        header.putInt((int) totalSize);

        return header.array();
    }

    /**
     * Applies fade-in and fade-out to PCM data for seamless looping.
     * Uses 100ms fades to eliminate popping sounds between loop iterations.
     *
     * @param pcmData Raw PCM data (16-bit signed, little-endian)
     * @param sampleRate Sample rate in Hz
     * @param channelCount Number of channels
     * @return PCM data with fades applied
     */
    private static byte[] applyLoopFades(byte[] pcmData, int sampleRate, int channelCount) {
        if (pcmData.length < 4) {
            // Not enough data for fades
            return pcmData;
        }

        // Calculate fade duration in samples (10ms for smooth transition)
        int fadeSamples = (int) Math.ceil(sampleRate * 0.01); // 10ms fade
        int bytesPerSample = 2 * channelCount; // 16-bit = 2 bytes per sample per channel
        int fadeBytes = fadeSamples * bytesPerSample;

        // Ensure fade doesn't exceed available data
        fadeBytes = Math.min(fadeBytes, pcmData.length / 2);

        Log.d("Tunas", "applyLoopFades: Applying " + (fadeBytes / bytesPerSample) + " sample fades (" +
              (fadeBytes * 1000.0 / (sampleRate * bytesPerSample)) + "ms) to " + pcmData.length + " bytes of PCM data");

        // Create a copy to modify
        byte[] fadedData = pcmData.clone();

        // Apply fade-out at the end and fade-in at the beginning for seamless looping
        applyFade(fadedData, pcmData.length - fadeBytes, fadeBytes, channelCount, true);  // fade out at end
        applyFade(fadedData, 0, fadeBytes, channelCount, false);  // fade in at beginning

        // Log the boundary values and check for original audio discontinuities
        int fadeSampleCount = fadeBytes / bytesPerSample;
        if (fadeSampleCount > 0) {
            // Check the last sample of fade-out (end of audio)
            int lastSampleByte = pcmData.length - bytesPerSample;
            int lastSample = (fadedData[lastSampleByte + 1] << 8) | (fadedData[lastSampleByte] & 0xFF);
            if (lastSample > 32767) lastSample -= 65536;

            // Check the first sample of fade-in (beginning of audio)
            int firstSample = (fadedData[1] << 8) | (fadedData[0] & 0xFF);
            if (firstSample > 32767) firstSample -= 65536;

            // Also check original samples right at the loop point for discontinuities
            int origLastSample = (pcmData[lastSampleByte + 1] << 8) | (pcmData[lastSampleByte] & 0xFF);
            if (origLastSample > 32767) origLastSample -= 65536;
            int origFirstSample = (pcmData[1] << 8) | (pcmData[0] & 0xFF);
            if (origFirstSample > 32767) origFirstSample -= 65536;

            Log.d("Tunas", String.format("applyLoopFades: Boundary check - faded: last=%d, first=%d | original: last=%d, first=%d",
                  lastSample, firstSample, origLastSample, origFirstSample));
        }

        return fadedData;
    }


    /**
     * Applies a cosine fade to a portion of PCM data for click-free transitions.
     *
     * @param pcmData PCM data array to modify in-place
     * @param startByte Starting byte position in the array
     * @param fadeBytes Number of bytes to fade
     * @param channelCount Number of channels
     * @param isFadeOut true for fade-out (to silence), false for fade-in (from silence)
     */
    private static void applyFade(byte[] pcmData, int startByte, int fadeBytes, int channelCount, boolean isFadeOut) {
        int bytesPerSample = 2 * channelCount; // 16-bit samples
        int numSamples = fadeBytes / bytesPerSample;

        Log.d("Tunas", String.format("applyFade: %s fade, startByte=%d, fadeBytes=%d, numSamples=%d, channelCount=%d",
              isFadeOut ? "OUT" : "IN", startByte, fadeBytes, numSamples, channelCount));

        for (int i = 0; i < numSamples; i++) {
            // Calculate fade factor (0.0 to 1.0) using cosine curve for smoother derivatives
            float t = (float) i / (numSamples - 1); // normalized position 0.0 to 1.0
            float fadeFactor;
            if (isFadeOut) {
                // Fade out: start at 1.0, go to 0.0 with cosine curve (smoother)
                fadeFactor = (float) (0.5 * (1.0 + Math.cos(Math.PI * t)));
            } else {
                // Fade in: start at 0.0, go to 1.0 with cosine curve (smoother)
                fadeFactor = (float) (0.5 * (1.0 - Math.cos(Math.PI * t)));
            }


            // Apply fade to each channel in this sample
            int sampleStartByte = startByte + i * bytesPerSample;
            for (int channel = 0; channel < channelCount; channel++) {
                int channelByteOffset = sampleStartByte + channel * 2;

                // Read 16-bit signed sample (little-endian)
                int originalSample = (pcmData[channelByteOffset + 1] << 8) | (pcmData[channelByteOffset] & 0xFF);
                if (originalSample > 32767) originalSample -= 65536; // Convert to signed

                // Apply fade
                int fadedSample = (int) (originalSample * fadeFactor);

                // Clamp to 16-bit range
                fadedSample = Math.max(-32768, Math.min(32767, fadedSample));


                // Write back as 16-bit signed (little-endian)
                pcmData[channelByteOffset] = (byte) (fadedSample & 0xFF);
                pcmData[channelByteOffset + 1] = (byte) ((fadedSample >> 8) & 0xFF);
            }
        }
    }


    /**
     * Creates a MediaSource for seamless looped playback using decoded PCM data in WAV format.
     *
     * @param audioFile The source audio file
     * @param startMs Start time in milliseconds
     * @param endMs End time in milliseconds
     * @param loopCount Number of times to loop the clip
     * @return ProgressiveMediaSource that plays the decoded WAV audio seamlessly
     * @throws IOException If decoding fails
     */
    public static ProgressiveMediaSource createLoopedMediaSource(File audioFile, long startMs, long endMs, int loopCount) throws IOException {
        Log.d("Tunas", "createLoopedMediaSource: Creating PCM-based looped media source for " + audioFile.getName() +
              ", startMs=" + startMs + ", endMs=" + endMs + ", loopCount=" + loopCount);

        // Decode the clip to PCM WAV format
        byte[] wavData = decodeAudioClipToWav(audioFile, startMs, endMs, loopCount);
        Log.d("Tunas", "createLoopedMediaSource: Decoded to " + wavData.length + " bytes of WAV data");

        // Check memory usage
        if (!isMemoryUsageAcceptable(wavData, loopCount)) {
            Log.w("Tunas", "createLoopedMediaSource: Memory usage may be too high - " +
                  estimateMemoryUsage(wavData, loopCount) + " bytes estimated");
        }

        // Create the looped data source factory
        LoopedAudioDataSourceFactory factory = new LoopedAudioDataSourceFactory(wavData, loopCount);
        Log.d("Tunas", "createLoopedMediaSource: Created looped data source factory");

        // Create media source from the WAV data using DefaultMediaSourceFactory
        MediaItem mediaItem = new MediaItem.Builder()
            .setUri("data:audio/wav;base64,looped")  // Custom URI to indicate WAV format
            .setMimeType("audio/wav")  // Explicitly set MIME type
            .build();

        ProgressiveMediaSource mediaSource = new ProgressiveMediaSource.Factory(factory)
            .createMediaSource(mediaItem);

        Log.d("Tunas", "createLoopedMediaSource: Created MediaSource for WAV data");
        return mediaSource;
    }

    /**
     * Decodes a portion of compressed audio to raw PCM data (no WAV header).
     * This is useful when you want to configure ExoPlayer to handle raw PCM directly.
     *
     * @param audioFile The source audio file
     * @param startMs Start time in milliseconds
     * @param endMs End time in milliseconds
     * @return Byte array containing raw PCM data
     * @throws IOException If decoding fails
     */
    public static byte[] decodeAudioClipToPcm(File audioFile, long startMs, long endMs) throws IOException {
        Log.d("Tunas", "decodeAudioClipToPcm: Starting raw PCM decoding from " + audioFile.getName() +
              ", startMs=" + startMs + ", endMs=" + endMs);

        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;

        try {
            extractor.setDataSource(audioFile.getAbsolutePath());
            extractor.selectTrack(0);

            MediaFormat format = extractor.getTrackFormat(0);
            String mime = format.getString(MediaFormat.KEY_MIME);
            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

            Log.d("Tunas", "decodeAudioClipToPcm: Format - " + mime + ", " + sampleRate + "Hz, " + channelCount + " channels");

        // Seek to start position (allowing some tolerance for keyframes)
        long startTimeUs = startMs * 1000;
        extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        long actualSeekPosition = extractor.getSampleTime();
        long seekOffsetUs = startTimeUs - actualSeekPosition;

        Log.d("Tunas", "decodeAudioClipToPcm: Seeking to " + startTimeUs + "us, landed at " +
              actualSeekPosition + "us (offset: " + seekOffsetUs + "us = " + (seekOffsetUs / 1000.0) + "ms)");

            // Create decoder
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(format, null, null, 0);
            decoder.start();

            ByteArrayOutputStream pcmOutput = new ByteArrayOutputStream();
            ByteBuffer[] inputBuffers = decoder.getInputBuffers();
            ByteBuffer[] outputBuffers = decoder.getOutputBuffers();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            long endTimeUs = endMs * 1000;
            boolean done = false;
            boolean inputDone = false;
            long totalPcmBytes = 0;

            while (!done) {
                // Feed input
                if (!inputDone) {
                    int inputBufferIndex = decoder.dequeueInputBuffer(10000);
                    if (inputBufferIndex >= 0) {
                        ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                        inputBuffer.clear();

                        int sampleSize = extractor.readSampleData(inputBuffer, 0);
                        long sampleTime = extractor.getSampleTime();

                        if (sampleSize < 0) {
                            // End of stream
                            decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                            Log.d("Tunas", "decodeAudioClipToPcm: End of input stream");
                        } else if (sampleTime >= endTimeUs) {
                            // Reached end time
                            decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                            Log.d("Tunas", "decodeAudioClipToPcm: Reached end time " + sampleTime + "us");
                        } else {
                            // Feed sample to decoder
                            decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, sampleTime, 0);
                            extractor.advance();
                        }
                    }
                }

                // Get output
                int outputBufferIndex = decoder.dequeueOutputBuffer(info, 10000);
                if (outputBufferIndex >= 0) {
                    ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];

                    if (info.size > 0) {
                        byte[] pcmChunk = new byte[info.size];
                        outputBuffer.get(pcmChunk);
                        pcmOutput.write(pcmChunk);
                        totalPcmBytes += info.size;
                    }

                    outputBuffer.clear();
                    decoder.releaseOutputBuffer(outputBufferIndex, false);

                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        done = true;
                        Log.d("Tunas", "decodeAudioClipToPcm: Decoding complete, total PCM bytes: " + totalPcmBytes);
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outputBuffers = decoder.getOutputBuffers();
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Output format may change, but we don't need to handle it for PCM
                }
            }

            byte[] rawPcmData = pcmOutput.toByteArray();

            // Calculate timing and trim to exact boundaries
            long expectedDurationUs = (endMs - startMs) * 1000L;
            long actualDurationUs = (long) ((rawPcmData.length / (channelCount * 2.0)) / sampleRate * 1000000);
            long durationDiffUs = actualDurationUs - expectedDurationUs;

            Log.d("Tunas", "decodeAudioClipToPcm: Pre-trim duration analysis:");
            Log.d("Tunas", "decodeAudioClipToPcm:   Requested: " + expectedDurationUs + "us (" + (expectedDurationUs / 1000.0) + "ms)");
            Log.d("Tunas", "decodeAudioClipToPcm:   Decoded: " + actualDurationUs + "us (" + (actualDurationUs / 1000.0) + "ms)");
            Log.d("Tunas", "decodeAudioClipToPcm:   Seek offset: " + seekOffsetUs + "us (" + (seekOffsetUs / 1000.0) + "ms)");

            // Trim PCM data to exact start/end boundaries
            byte[] pcmData = trimPcmToExactTime(rawPcmData, sampleRate, channelCount, seekOffsetUs, startTimeUs, endTimeUs);

            long finalDurationUs = (long) ((pcmData.length / (channelCount * 2.0)) / sampleRate * 1000000);
            Log.d("Tunas", "decodeAudioClipToPcm: Post-trim duration: " + finalDurationUs + "us (" + (finalDurationUs / 1000.0) + "ms)");
            Log.d("Tunas", "decodeAudioClipToPcm: Created raw PCM data, total size: " + pcmData.length + " bytes");

            return pcmData;

        } finally {
            if (decoder != null) {
                decoder.stop();
                decoder.release();
            }
            extractor.release();
        }
    }

    /**
     * Creates a MediaSource using ExoPlayer's built-in LoopingMediaSource and ClippingMediaSource.
     * This leverages ExoPlayer's native mechanisms for seamless looping.
     *
     * @param audioFile The source audio file
     * @param startMs Start time in milliseconds
     * @param endMs End time in milliseconds
     * @param loopCount Number of times to loop the clip
     * @param dataSourceFactory Factory for creating data sources to read the file
     * @return LoopingMediaSource that provides seamless looped playback
     */
    public static LoopingMediaSource createNativeLoopedMediaSource(
            File audioFile, long startMs, long endMs, int loopCount, DataSource.Factory dataSourceFactory) {

        Log.d("Tunas", "createNativeLoopedMediaSource: Creating native ExoPlayer looped media source for " + audioFile.getName() +
              ", startMs=" + startMs + ", endMs=" + endMs + ", loopCount=" + loopCount);

        // Create MediaItem from the file
        MediaItem mediaItem = MediaItem.fromUri(android.net.Uri.fromFile(audioFile));
        Log.d("Tunas", "createNativeLoopedMediaSource: Created MediaItem from file");

        // Create ProgressiveMediaSource for the file
        ProgressiveMediaSource progressiveSource = new ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(mediaItem);
        Log.d("Tunas", "createNativeLoopedMediaSource: Created ProgressiveMediaSource");

        // Create ClippingMediaSource to clip the desired portion
        long startUs = startMs * 1000;
        long endUs = endMs * 1000;
        ClippingMediaSource clippingSource = new ClippingMediaSource(
            progressiveSource,
            startUs,
            endUs
        );
        Log.d("Tunas", "createNativeLoopedMediaSource: Created ClippingMediaSource from " + startUs + "us to " + endUs + "us");

        // Create LoopingMediaSource for seamless repetition
        LoopingMediaSource loopingSource = new LoopingMediaSource(clippingSource, loopCount);
        Log.d("Tunas", "createNativeLoopedMediaSource: Created native LoopingMediaSource with " + loopCount + " loops");

        return loopingSource;
    }



    /**
     * Creates a MediaSource that serves multiple concatenated WAV clips for truly gapless looping.
     * This bypasses ExoPlayer's LoopingMediaSource to ensure no gaps between loops.
     *
     * @param context Android context
     * @param audioFile The source audio file
     * @param startMs Start time in milliseconds
     * @param endMs End time in milliseconds
     * @param loopCount Number of times to loop the clip
     * @return ProgressiveMediaSource with custom looped data source
     * @throws IOException If audio decoding fails
     */
    public static ProgressiveMediaSource createLoopedPcmMediaSource(Context context, File audioFile, long startMs, long endMs, int loopCount) throws IOException {
        Log.d("Tunas", "createLoopedPcmMediaSource: Creating gapless looped media source using concatenated PCM data");
        Log.d("Tunas", "createLoopedPcmMediaSource: Loop boundaries - startMs=" + startMs + ", endMs=" + endMs + ", duration=" + (endMs - startMs) + "ms, loopCount=" + loopCount);

        // Decode the clip to raw PCM data (no WAV header)
        byte[] pcmData = decodeAudioClipToPcm(audioFile, startMs, endMs);
        Log.d("Tunas", "createLoopedPcmMediaSource: Decoded single clip to " + pcmData.length + " bytes of raw PCM");

        // Apply fades to the PCM data for seamless looping
        pcmData = applyLoopFades(pcmData, getSampleRate(audioFile), getChannelCount(audioFile));
        Log.d("Tunas", "createLoopedPcmMediaSource: Applied fades to PCM data");

        // Check memory usage for the looped data
        if (!isMemoryUsageAcceptable(pcmData, loopCount)) {
            Log.w("Tunas", "createLoopedPcmMediaSource: Memory usage may be too high - " +
                  estimateMemoryUsage(pcmData, loopCount) + " bytes estimated for " + loopCount + " loops");
        }

        // Create WAV wrapper for the looped PCM data
        byte[] loopedWavData = createLoopedWavData(pcmData, getSampleRate(audioFile), getChannelCount(audioFile), loopCount);
        Log.d("Tunas", "createLoopedPcmMediaSource: Created looped WAV data: " + loopedWavData.length + " bytes");

        // Create a simple data source factory for the complete looped WAV
        SimpleDataSourceFactory factory = new SimpleDataSourceFactory(loopedWavData);
        Log.d("Tunas", "createLoopedPcmMediaSource: Created data source factory");

        // Create media source with proper WAV format
        // Try a simple URI without special characters
        MediaItem mediaItem = new MediaItem.Builder()
            .setUri("looped_audio")
            .build(); // Let ExoPlayer auto-detect MIME type from WAV header

        ProgressiveMediaSource mediaSource;
        try {
            mediaSource = new ProgressiveMediaSource.Factory(factory)
                .createMediaSource(mediaItem);

            Log.d("Tunas", "createLoopedPcmMediaSource: Created ProgressiveMediaSource for gapless WAV looping");
            Log.d("Tunas", "createLoopedPcmMediaSource: MediaItem URI=" + mediaItem.mediaId);
        } catch (Exception e) {
            Log.e("Tunas", "createLoopedPcmMediaSource: Failed to create media source", e);
            throw new RuntimeException("Failed to create looped media source", e);
        }

        return mediaSource;
    }


    /**
     * Get sample rate from audio file.
     */
    private static int getSampleRate(File audioFile) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(audioFile.getAbsolutePath());
            extractor.selectTrack(0);
            MediaFormat format = extractor.getTrackFormat(0);
            return format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        } finally {
            extractor.release();
        }
    }

    /**
     * Get channel count from audio file.
     */
    private static int getChannelCount(File audioFile) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(audioFile.getAbsolutePath());
            extractor.selectTrack(0);
            MediaFormat format = extractor.getTrackFormat(0);
            return format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        } finally {
            extractor.release();
        }
    }

    /**
     * Create a single WAV file containing looped PCM data.
     */
    private static byte[] createLoopedWavData(byte[] pcmData, int sampleRate, int channelCount, int loopCount) {
        // Calculate total PCM size
        int pcmSizePerLoop = pcmData.length;
        int totalPcmSize = pcmSizePerLoop * loopCount;

        // Create WAV header using existing method
        byte[] header = createWavHeader(pcmSizePerLoop, totalPcmSize, sampleRate, channelCount, 16);

        // WAV data = header + looped PCM
        byte[] wavData = new byte[header.length + totalPcmSize];

        // Copy header
        System.arraycopy(header, 0, wavData, 0, header.length);

        // Copy PCM data loopCount times
        for (int loop = 0; loop < loopCount; loop++) {
            int offset = header.length + (loop * pcmSizePerLoop);
            System.arraycopy(pcmData, 0, wavData, offset, pcmSizePerLoop);
        }

        return wavData;
    }

    /**
     * Simple data source factory that serves complete data.
     */
    public static class SimpleDataSourceFactory implements DataSource.Factory {
        private final byte[] data;

        public SimpleDataSourceFactory(byte[] data) {
            this.data = data;
        }

        @Override
        public DataSource createDataSource() {
            return new DataSource() {
                private boolean opened = false;
                private long position = 0;

                @Override
                public long open(DataSpec dataSpec) throws IOException {
                    opened = true;
                    position = dataSpec.position;
                    Log.d("Tunas", "SimpleDataSource.open: Opened with position=" + position + ", totalSize=" + data.length);
                    return data.length;
                }

                @Override
                public int read(byte[] buffer, int offset, int readLength) throws IOException {
                    if (!opened) {
                        throw new IOException("DataSource not opened");
                    }

                    if (position >= data.length) {
                        return C.RESULT_END_OF_INPUT;
                    }

                    int bytesToRead = (int) Math.min(readLength, data.length - position);
                    System.arraycopy(data, (int) position, buffer, offset, bytesToRead);
                    position += bytesToRead;

                    return bytesToRead;
                }

                @Override
                public Uri getUri() {
                    return Uri.parse("simple_audio");
                }

                @Override
                public void close() throws IOException {
                    opened = false;
                }

                @Override
                public void addTransferListener(TransferListener transferListener) {
                    // No-op for simple in-memory data source
                }

                @Override
                public Map<String, List<String>> getResponseHeaders() {
                    return Collections.emptyMap();
                }
            };
        }
    }

    /**
     * Trims PCM data to exact start/end time boundaries.
     */
    private static byte[] trimPcmToExactTime(byte[] pcmData, int sampleRate, int channelCount, long seekOffsetUs, long requestedStartUs, long requestedEndUs) {
        int bytesPerSample = channelCount * 2; // 16-bit PCM
        int totalSamples = pcmData.length / bytesPerSample;

        // Calculate sample positions for trimming
        long decodedStartUs = requestedStartUs - seekOffsetUs; // When decoding actually started
        long trimStartSample = Math.max(0, (long) Math.ceil((requestedStartUs - decodedStartUs) * sampleRate / 1000000.0));
        long trimEndSample = Math.min(totalSamples, (long) Math.floor((requestedEndUs - decodedStartUs) * sampleRate / 1000000.0));

        // Ensure we have valid bounds
        trimStartSample = Math.max(0, Math.min(trimStartSample, totalSamples));
        trimEndSample = Math.max(trimStartSample, Math.min(trimEndSample, totalSamples));

        int trimmedSamples = (int) (trimEndSample - trimStartSample);
        int trimmedBytes = trimmedSamples * bytesPerSample;

        Log.d("Tunas", "trimPcmToExactTime: Trimming " + totalSamples + " samples to [" + trimStartSample + ", " + trimEndSample + "] = " + trimmedSamples + " samples");

        if (trimmedBytes <= 0) {
            Log.w("Tunas", "trimPcmToExactTime: No valid samples after trimming, returning empty array");
            return new byte[0];
        }

        byte[] trimmedData = new byte[trimmedBytes];
        System.arraycopy(pcmData, (int) trimStartSample * bytesPerSample, trimmedData, 0, trimmedBytes);

        return trimmedData;
    }

    /**
     * Gets the format information for an audio file.
     * Useful for debugging or format validation.
     *
     * @param audioFile The audio file to analyze
     * @return MediaFormat containing track information
     * @throws IOException If format detection fails
     */
    public static MediaFormat getAudioFormat(File audioFile) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(audioFile.getAbsolutePath());
            return extractor.getTrackFormat(0);
        } finally {
            extractor.release();
        }
    }

    /**
     * Estimates memory usage for a looped clip.
     *
     * @param clipData The extracted clip data
     * @param loopCount Number of loops
     * @return Estimated memory usage in bytes
     */
    public static long estimateMemoryUsage(byte[] clipData, int loopCount) {
        return clipData.length * loopCount;
    }

    /**
     * Checks if the device has enough memory for the looped audio.
     * Uses a conservative estimate of 50% of available heap.
     *
     * @param clipData The extracted clip data
     * @param loopCount Number of loops
     * @return true if memory usage is acceptable
     */
    public static boolean isMemoryUsageAcceptable(byte[] clipData, int loopCount) {
        long estimatedUsage = estimateMemoryUsage(clipData, loopCount);
        long maxHeap = Runtime.getRuntime().maxMemory();
        long conservativeLimit = maxHeap / 2; // Use max 50% of heap

        return estimatedUsage < conservativeLimit;
    }
}
