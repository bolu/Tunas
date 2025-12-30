package com.tunas.app;

import android.util.Log;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class TuneUtils {
    public static final String BASE_DIR = "/storage/emulated/0/Tunas";

    public static List<String> loadTunes() {
        Log.d("Tunas", "loadTunes() called - BASE_DIR: " + BASE_DIR);
        List<String> allTunes = new ArrayList<>();
        File baseDir = new File(BASE_DIR);

        Log.d("Tunas", "loadTunes() - baseDir.exists(): " + baseDir.exists());
        Log.d("Tunas", "loadTunes() - baseDir.isDirectory(): " + baseDir.isDirectory());
        Log.d("Tunas", "loadTunes() - baseDir.canRead(): " + baseDir.canRead());
        Log.d("Tunas", "loadTunes() - baseDir.getAbsolutePath(): " + baseDir.getAbsolutePath());

        if (baseDir.exists() && baseDir.isDirectory()) {
            Log.d("Tunas", "loadTunes() - baseDir exists and is directory, calling listFiles()");
            File[] dirs = baseDir.listFiles();
            Log.d("Tunas", "loadTunes() - dirs array is " + (dirs == null ? "null" : "not null"));
            if (dirs != null) {
                Log.d("Tunas", "loadTunes() - dirs.length: " + dirs.length);
                for (File dir : dirs) {
                    if (dir.isDirectory()) {
                        allTunes.add(dir.getName());
                    }
                }
            } else {
                Log.w("Tunas", "loadTunes() - WARNING: baseDir.listFiles() returned null despite directory existing!");
            }
        } else {
            Log.w("Tunas", "loadTunes() - WARNING: baseDir does not exist or is not a directory!");
            Log.w("Tunas", "loadTunes() - baseDir.exists(): " + baseDir.exists() + ", baseDir.isDirectory(): " + baseDir.isDirectory());
        }

        Log.d("Tunas", "loadTunes() - allTunes size before sorting: " + allTunes.size());
        Collections.sort(allTunes, String.CASE_INSENSITIVE_ORDER);
        Log.d("Tunas", "loadTunes() - allTunes size after sorting: " + allTunes.size());
        Log.d("Tunas", "loadTunes() - returning tunes: " + allTunes);
        return allTunes;
    }

    public static String pickRandomTune(List<String> tunes) {
        if (tunes == null || tunes.isEmpty()) {
            return null;
        }
        Random rand = new Random();
        return tunes.get(rand.nextInt(tunes.size()));
    }


    /**
     * Parses XSC timestamp format (HH:MM:SS.ssssss) to milliseconds
     * @param timestamp XSC timestamp string like "0:00:11.660000"
     * @return timestamp in milliseconds
     */
    public static long parseXscTimestamp(String timestamp) {
        String[] parts = timestamp.split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid timestamp format: " + timestamp);
        }

        int hours = Integer.parseInt(parts[0]);
        int minutes = Integer.parseInt(parts[1]);

        // Split seconds and microseconds
        String[] secondsParts = parts[2].split("\\.");
        if (secondsParts.length != 2) {
            throw new IllegalArgumentException("Invalid seconds format: " + parts[2]);
        }

        int seconds = Integer.parseInt(secondsParts[0]);
        int microseconds = Integer.parseInt(secondsParts[1]);

        // Convert to milliseconds
        long totalMs = (hours * 3600L + minutes * 60L + seconds) * 1000L + microseconds / 1000L;
        return totalMs;
    }
}


