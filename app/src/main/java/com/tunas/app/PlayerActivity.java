package com.tunas.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.ClippingMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.tunas.app.TuneFavorites.StarColor;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class PlayerActivity extends AppCompatActivity {
 
    private TextView fileNameText;
    private EditText tuneNameBox;
    private Button randomButton;
    private Button stopStartBtn;
    private Button buttonLess4;
    private SeekBar playbackSpeedSeekBar;
    private LinearLayout thumbnailContainer;
    private ImageView fullScreenImageView;
    private SurfaceView cameraSurfaceView;
    private HandWaveDetector handWaveDetector;

    // Playback mode buttons
    private Button loopBtn;
    private Button gotoBtn;

    // Fine-tune buttons
    private Button fineTunePrevABtn;
    private Button fineTuneNextABtn;
    private Button fineTunePrevBBtn;
    private Button fineTuneNextBBtn;

    // Shift selection buttons
    private Button shiftSelectionPrevBtn;
    private Button shiftSelectionNextBtn;

    // Position indicator
    private View positionDot;

    // Selection overlay indicators for partial bar highlighting
    private View startBarOverlay;
    private View endBarOverlay;
    
    private List<File> imageFiles;
    private List<File> audioFiles;
    private int currentAudioIndex = 0;
    private List<Long> barPositions;
    private List<Boolean> isSectionMarker; // true if marker starts with "S", false if "M"
    private List<String> sectionNames; // section names for "S" markers, null for "M" markers
    private long audioDuration; // Audio file duration in milliseconds (10 seconds after last bar)

    private ExoPlayer exoPlayer;
    private Handler handler = new Handler();

    private boolean isStopped = true;
    private boolean loopOn = false;
    private boolean gotoOn = false;

    // Position tracking
    private Runnable positionUpdateRunnable;
    private static final long POSITION_UPDATE_INTERVAL_MS = 100; // Update every 100ms

    // Auto-scroll control
    private long lastUserScrollTimeMs = 0; // Timestamp of last user scroll
    private static final long AUTO_SCROLL_TIMEOUT_MS = 5000; // 5 seconds timeout

    // Media source offset tracking
    private long currentMediaSourceStartMs = 0; // Start time of current media source in absolute file time
    private long currentSegmentDurationMs = 0; // Duration of the current segment

    private void updatePositionDot(long currentPositionMs) {
        if (positionDot == null) {
            return;
        }
        if (barPositions == null || barPositions.isEmpty()) {
            positionDot.setVisibility(View.INVISIBLE);
            return;
        }

        // Convert relative position to absolute position in the file
        // Use modulo to handle looping - position wraps around to start of segment
        long positionInSegment = (currentSegmentDurationMs > 0) ?
            (currentPositionMs % currentSegmentDurationMs) : currentPositionMs;
        long absolutePositionMs = currentMediaSourceStartMs + positionInSegment;
        // Log.d("Tunas", "Position calc: currentPositionMs=" + currentPositionMs +
        //       ", currentMediaSourceStartMs=" + currentMediaSourceStartMs +
        //       ", currentSegmentDurationMs=" + currentSegmentDurationMs +
        //       ", positionInSegment=" + positionInSegment + ", absolutePositionMs=" + absolutePositionMs);

        // Find which bar we're currently playing (based on absolute position)
        int currentBarIndex = -1;
        for (int i = 0; i < barPositions.size(); i++) {
            if (absolutePositionMs >= barPositions.get(i)) {
                currentBarIndex = i;
            } else {
                break;
            }
        }

        if (currentBarIndex == -1) {
            positionDot.setVisibility(View.INVISIBLE);
            return;
        }

        // Get the button for this bar (button IDs are 1-based)
        Button currentButton = findViewById(currentBarIndex + 1);
        if (currentButton == null) {
            positionDot.setVisibility(View.INVISIBLE);
            return;
        }

        // Get button position relative to the ScrollView's content FrameLayout
        FrameLayout scrollContent = (FrameLayout) ((ScrollView) findViewById(R.id.barScrollView)).getChildAt(0);
        int[] buttonLocation = new int[2];
        int[] contentLocation = new int[2];
        currentButton.getLocationOnScreen(buttonLocation);
        scrollContent.getLocationOnScreen(contentLocation);

        // Convert to coordinates relative to ScrollView content
        int relativeX = buttonLocation[0] - contentLocation[0];
        int relativeY = buttonLocation[1] - contentLocation[1];

        int buttonWidth = currentButton.getWidth();
        int buttonHeight = currentButton.getHeight();

        // Account for button margins (2dp on all sides)
        int marginPx = (int) (2 * getResources().getDisplayMetrics().density);
        int visualHeight = buttonHeight - (2 * marginPx);
        int visualWidth = buttonWidth - (2 * marginPx);

        // Calculate progress within this specific bar (0.0 to 1.0)
        long barStartTime = barPositions.get(currentBarIndex);
        long barEndTime = (currentBarIndex + 1 < barPositions.size()) ?
            barPositions.get(currentBarIndex + 1) : audioDuration;

        float progressInBar = 0.0f;
        if (barEndTime > barStartTime) {
            progressInBar = (float) (absolutePositionMs - barStartTime) / (barEndTime - barStartTime);
            progressInBar = Math.max(0.0f, Math.min(1.0f, progressInBar));
        }


        // Position vertical bar at the progress point within the button
        int dotX = relativeX + marginPx + (int)(progressInBar * visualWidth) - (positionDot.getWidth() / 2);
        int dotY = relativeY + marginPx; // Top of visual button content

        // Set the height to match visual button height
        ViewGroup.LayoutParams params = positionDot.getLayoutParams();
        params.height = visualHeight;
        positionDot.setLayoutParams(params);

        // Position the bar (always visible since it's now in ScrollView)
        positionDot.setTranslationX(dotX);
        positionDot.setTranslationY(dotY);
        positionDot.setVisibility(View.VISIBLE);

        // Auto-scroll to keep the playing indicator visible and centered
        // Only auto-scroll if 5 seconds have passed since last user scroll
        long currentTimeMs = System.currentTimeMillis();
        boolean allowAutoScroll = (currentTimeMs - lastUserScrollTimeMs) >= AUTO_SCROLL_TIMEOUT_MS;

        ScrollView scrollView = findViewById(R.id.barScrollView);
        if (scrollView != null && allowAutoScroll) {
            int scrollViewHeight = scrollView.getHeight();
            int currentScrollY = scrollView.getScrollY();

            // Calculate the dot's vertical center position
            int dotCenterY = dotY + (positionDot.getHeight() / 2);

            // Check if the dot is outside the visible area (no margin - scroll as soon as it goes out)
            boolean isAboveVisibleArea = dotCenterY < currentScrollY;
            boolean isBelowVisibleArea = dotCenterY > currentScrollY + scrollViewHeight;

            if (isAboveVisibleArea || isBelowVisibleArea) {
                // Calculate target scroll position to center the dot in the middle of visible area
                int targetScrollY = dotCenterY - (scrollViewHeight / 2);

                // Ensure we don't scroll beyond content bounds
                int maxScrollY = scrollContent.getHeight() - scrollViewHeight;
                targetScrollY = Math.max(0, Math.min(targetScrollY, maxScrollY));

                // Smooth scroll to the target position
                scrollView.smoothScrollTo(0, targetScrollY);
            }
        }
    }


    private void updatePartialBarOverlay(View overlay, Button button, FrameLayout scrollContent,
                                       int[] contentLocation, int marginPx, int barIndex,
                                       int startTwelfth, int endTwelfth) {
        Log.d("Tunas", "updatePartialBarOverlay: barIndex=" + barIndex + ", startTwelfth=" + startTwelfth + ", endTwelfth=" + endTwelfth);

        // Set the overlay color to match the button's rainbow color
        int[] rainbowColors = getRainbowColors();
        int numColors = rainbowColors.length;
        int colorIndex = barIndex % numColors;
        // Make it slightly transparent
        int overlayColor = (rainbowColors[colorIndex] & 0xFFFFFF) | 0xCC000000; // Add 80% alpha
        overlay.setBackgroundColor(overlayColor);
        Log.d("Tunas", "updatePartialBarOverlay: setting color to " + String.format("#%08X", overlayColor));

        int[] buttonLocation = new int[2];
        button.getLocationOnScreen(buttonLocation);

        // Convert to coordinates relative to ScrollView content
        int relativeX = buttonLocation[0] - contentLocation[0];
        int relativeY = buttonLocation[1] - contentLocation[1];

        int buttonWidth = button.getWidth();
        int buttonHeight = button.getHeight();
        int visualHeight = buttonHeight - (2 * marginPx);
        int visualWidth = buttonWidth - (2 * marginPx);

        Log.d("Tunas", "updatePartialBarOverlay: button size=" + buttonWidth + "x" + buttonHeight + ", visual=" + visualWidth + "x" + visualHeight);

        // Calculate the portion of the bar to highlight
        float startFraction = startTwelfth / 12.0f;
        float endFraction = (endTwelfth + 1) / 12.0f; // +1 because we want to include the end twelfth
        float highlightWidth = (endFraction - startFraction) * visualWidth;

        // Position the overlay
        int overlayX = relativeX + marginPx + (int)(startFraction * visualWidth);
        int overlayY = relativeY + marginPx;
        int overlayWidth = Math.max(1, (int)highlightWidth);
        int overlayHeight = visualHeight;

        Log.d("Tunas", "updatePartialBarOverlay: startFraction=" + startFraction + ", endFraction=" + endFraction + ", highlightWidth=" + highlightWidth);
        Log.d("Tunas", "updatePartialBarOverlay: overlay pos=(" + overlayX + "," + overlayY + "), size=" + overlayWidth + "x" + overlayHeight);

        // Set overlay dimensions and position
        ViewGroup.LayoutParams params = overlay.getLayoutParams();
        params.width = overlayWidth;
        params.height = overlayHeight;
        overlay.setLayoutParams(params);

        overlay.setTranslationX(overlayX);
        overlay.setTranslationY(overlayY);
        overlay.setVisibility(View.VISIBLE);
        Log.d("Tunas", "updatePartialBarOverlay: overlay set to VISIBLE");
    }

    private void initializePositionTracking() {
        positionUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (exoPlayer != null && exoPlayer.isPlaying()) {
                    long currentPosition = exoPlayer.getCurrentPosition();
                    // Log.d("Tunas", "Player position: " + currentPosition + "ms");
                    updatePositionDot(currentPosition);
                    handler.postDelayed(this, POSITION_UPDATE_INTERVAL_MS);
                }
            }
        };
    }

    private List<String> allTunes;
    private SharedPreferences preferences;
    private Gson gson;
    private TuneFavorites favorites;

    private boolean playerInitialized = false;

    // Selection range state
    private int selectionStartBar = 0; // Start of selection range (inclusive, 0-based)
    private int selectionEndBar = 0; // End of selection range (inclusive, 0-based)

    // Fine-tune selection within bars (0-11 twelfths, where 12 would be next bar)
    private int selectionStartTwelfths = 0; // Twelfths within selectionStartBar
    private int selectionEndTwelfths = 0; // Twelfths within selectionEndBar

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        String tuneName = getIntent().getStringExtra("TUNE_NAME");
        String tunePath = getIntent().getStringExtra("TUNE_PATH");

        fileNameText = findViewById(R.id.fileNameText);
        tuneNameBox = findViewById(R.id.tuneNameBox);
        randomButton = findViewById(R.id.randomButton);
        stopStartBtn = findViewById(R.id.stopStartBtn);
        buttonLess4 = findViewById(R.id.buttonLess4);
        playbackSpeedSeekBar = findViewById(R.id.playbackSpeedSeekBar);
        thumbnailContainer = findViewById(R.id.thumbnailContainer);
        fullScreenImageView = findViewById(R.id.fullScreenImageView);
        cameraSurfaceView = findViewById(R.id.cameraSurfaceView);

        // Initialize playback mode buttons
        loopBtn = findViewById(R.id.loopBtn);
        gotoBtn = findViewById(R.id.gotoBtn);

        // Initialize fine-tune buttons
        fineTunePrevABtn = findViewById(R.id.fineTunePrevABtn);
        fineTuneNextABtn = findViewById(R.id.fineTuneNextABtn);
        fineTunePrevBBtn = findViewById(R.id.fineTunePrevBBtn);
        fineTuneNextBBtn = findViewById(R.id.fineTuneNextBBtn);

        // Initialize shift selection buttons
        shiftSelectionPrevBtn = findViewById(R.id.shiftSelectionPrevBtn);
        shiftSelectionNextBtn = findViewById(R.id.shiftSelectionNextBtn);

        // Initialize position indicator
        positionDot = findViewById(R.id.positionDot);

        // Initialize selection overlay indicators
        startBarOverlay = findViewById(R.id.startBarOverlay);
        endBarOverlay = findViewById(R.id.endBarOverlay);

        // Make TextView clickable
        fileNameText.setClickable(true);
        fileNameText.setFocusable(true);

        if (tuneNameBox != null && tuneName != null) {
            tuneNameBox.setText(tuneName);
        }

        // Initialize ExoPlayer as early as possible
        exoPlayer = new ExoPlayer.Builder(this).build();

        // Initialize position tracking
        initializePositionTracking();

        initializePlayer(tunePath);

        // Gesture detector will be created when enabled via long press
    }

    private void initializePlayer(String tunePath) {
        loadFiles(tunePath);
        setupClickListeners();
        loadTunes();
        setupRandomButton();
        setupStopStartButton();
        setupLess4Button();
        setupPlaybackSpeedSeekBar();
        setupLoopButton();
        setupGotoButton();
        setupFineTuneButtons();
        setupShiftButtons();

        setupScrollViewScrollListener();

        setupThumbnails();

        if (!audioFiles.isEmpty()) {
            displayAudioFileName(0);
            prepareMediaPlayer(0);
        }

        playerInitialized = true;
    }

    private void loadFiles(String path) {
        imageFiles = new ArrayList<>();
        audioFiles = new ArrayList<>();

        File dir = new File(path);
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    String name = file.getName().toLowerCase();
                    if (name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                        name.endsWith(".png") || name.endsWith(".gif")) {
                        imageFiles.add(file);
                    } else if (name.endsWith(".ogg") || name.endsWith(".m4a")) {
                        audioFiles.add(file);
                    }
                }
            }
        }

        Collections.sort(imageFiles);
        // Sort audio files with OGG files before M4A files
        Collections.sort(audioFiles, new Comparator<File>() {
            @Override
            public int compare(File f1, File f2) {
                String name1 = f1.getName().toLowerCase();
                String name2 = f2.getName().toLowerCase();

                boolean isOgg1 = name1.endsWith(".ogg");
                boolean isOgg2 = name2.endsWith(".ogg");

                // OGG files come first
                if (isOgg1 && !isOgg2) return -1;
                if (!isOgg1 && isOgg2) return 1;
                // Same extension, sort alphabetically
                return name1.compareTo(name2);
            }
        });
    }

    private void loadTunes() {
        allTunes = TuneUtils.loadTunes();
    }

    private void setupThumbnails() {
        // Find the HorizontalScrollView that contains the thumbnail container
        View thumbnailScrollView = (View) thumbnailContainer.getParent();

        if (thumbnailContainer == null || imageFiles == null || imageFiles.isEmpty()) {
            // Hide the thumbnail scroll view when there are no images
            if (thumbnailScrollView != null) {
                thumbnailScrollView.setVisibility(View.GONE);
            }
            return;
        }

        // Show the thumbnail scroll view when there are images
        if (thumbnailScrollView != null) {
            thumbnailScrollView.setVisibility(View.VISIBLE);
        }

        thumbnailContainer.removeAllViews();

        final float density = getResources().getDisplayMetrics().density;
        int size = (int) (64 * density); // 64dp square thumbnails
        int margin = (int) (4 * density);

        for (int i = 0; i < imageFiles.size(); i++) {
            final int index = i;
            ImageView thumb = new ImageView(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.leftMargin = margin;
            params.rightMargin = margin;
            thumb.setLayoutParams(params);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setAdjustViewBounds(true);
            thumb.setBackgroundColor(0xFF000000);
            thumb.setImageURI(android.net.Uri.fromFile(imageFiles.get(i)));
            thumb.setClickable(true);

            thumb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showFullScreenImage(index);
                }
            });

            thumbnailContainer.addView(thumb);
        }
    }

    private void showFullScreenImage(int index) {
        if (fullScreenImageView == null || imageFiles == null || imageFiles.isEmpty()) {
            return;
        }
        if (index < 0 || index >= imageFiles.size()) {
            return;
        }

        fullScreenImageView.setImageURI(android.net.Uri.fromFile(imageFiles.get(index)));
        fullScreenImageView.setVisibility(View.VISIBLE);
    }

    private void setupClickListeners() {
        // Click on file name to cycle through all audio files
        fileNameText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                nextAudio();
            }
        });

        // Hide full screen image when clicked
        if (fullScreenImageView != null) {
            fullScreenImageView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    fullScreenImageView.setVisibility(View.GONE);
                }
            });
        }
    }

    private void toggleGestureDetection() {
        if (handWaveDetector != null) {
            // Disable gesture detection - destroy the detector
            Log.d("Tunas", "Destroying gesture detector");
            handWaveDetector.stopCamera();
            handWaveDetector = null;
            updateStopStartButtonLabel();
            Log.d("Tunas", "Gesture detection disabled");
        } else {
            // Enable gesture detection - create new detector
            Log.d("Tunas", "Creating gesture detector");
            createHandWaveDetector();
            updateStopStartButtonLabel();
            Log.d("Tunas", "Gesture detection enabled");
        }
    }

    private void updateStopStartButtonLabel() {
        String baseText = "stop | start";
        if (handWaveDetector != null) {
            stopStartBtn.setText("🤙    " + baseText + "    🤙");
        } else {
            stopStartBtn.setText(baseText);
        }
    }

    private void setupStopStartButton() {
        stopStartBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.d("Tunas", "Stop/Start button clicked, current state: isStopped=" + isStopped);
                if (isStopped) {
                    Log.d("Tunas", "Starting playback manually");

                    // Check if whole file is selected
                    boolean isWholeFileSelected = (barPositions != null && !barPositions.isEmpty()) &&
                                                 (selectionStartBar == 0 && selectionEndBar == barPositions.size() - 1);

                    if (isWholeFileSelected && exoPlayer.getMediaItemCount() > 0) {
                        // Whole file selected - continue from where we left off
                        Log.d("Tunas", "Whole file selected, continuing from current position");

                        // If player has reached end of playback, seek back to beginning first
                        if (exoPlayer.getPlaybackState() == Player.STATE_ENDED) {
                            Log.d("Tunas", "Player at end of playback, seeking to beginning");
                            exoPlayer.seekTo(0);
                        }

                        exoPlayer.play();
                        isStopped = false;
                    } else if (exoPlayer.getMediaItemCount() > 0) {
                        // Not whole file but media source exists - start from selection start (point A)
                        Log.d("Tunas", "Starting from selection start (point A)");
                        if (selectionStartBar >= 0 && barPositions != null && selectionStartBar < barPositions.size()) {
                            exoPlayer.seekTo(0);
                        }
                        exoPlayer.play();
                        isStopped = false;
                    } else {
                        Log.d("Tunas", "No media source available, cannot start playback");
                    }
                } else {
                    Log.d("Tunas", "Pausing playback manually");
                    exoPlayer.pause();
                    isStopped = true;
                }
            }
        });

        stopStartBtn.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                Log.d("Tunas", "Stop/Start button long pressed, toggling gesture detection");
                toggleGestureDetection();
                return true; // Consume the event
            }
        });

        // Set initial button label
        updateStopStartButtonLabel();
    }

    private void setupPlaybackSpeedSeekBar() {
        playbackSpeedSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    // Map progress 0-60 to speed 0.4-1.0 (40%-100%)
                    float speed = 0.4f + (progress / 60.0f) * 0.6f;
                    exoPlayer.setPlaybackParameters(exoPlayer.getPlaybackParameters().withSpeed(speed));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // Not needed
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Not needed
            }
        });

        // Set default to 100% (progress = 60)
        playbackSpeedSeekBar.setProgress(60);
        exoPlayer.setPlaybackParameters(exoPlayer.getPlaybackParameters().withSpeed(1.0f));
    }

    private void setupLoopButton() {
        // Set initial state - looping disabled
        updateLoopButtonState();

        loopBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Stop playback when switching modes
                if (!isStopped) {
                    exoPlayer.pause();
                    isStopped = true;
                }
                // Toggle repeat on/off
                loopOn = !loopOn;
                updateMediaSource();
                updateLoopButtonState();
            }
        });
    }

    private void setupGotoButton() {
        // Set initial state - goto disabled
        updateGotoButtonState();

        gotoBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Toggle goto on/off
                gotoOn = !gotoOn;
                updateGotoButtonState();
            }
        });
    }

    private void setupFineTuneButtons() {
        fineTunePrevABtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                adjustSelection(-1, 0); // Move A point backward by 1/12
            }
        });

        fineTunePrevABtn.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                adjustSelection(-12, 0); // Move A point backward by 1 bar
                return true; // Consume the event
            }
        });

        fineTuneNextABtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                adjustSelection(1, 0); // Move A point forward by 1/12
            }
        });

        fineTuneNextABtn.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                adjustSelection(12, 0); // Move A point forward by 1 bar
                return true; // Consume the event
            }
        });

        fineTunePrevBBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                adjustSelection(0, -1); // Move B point backward by 1/12
            }
        });

        fineTunePrevBBtn.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                adjustSelection(0, -12); // Move B point backward by 1 bar
                return true; // Consume the event
            }
        });

        fineTuneNextBBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                adjustSelection(0, 1); // Move B point forward by 1/12
            }
        });

        fineTuneNextBBtn.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                adjustSelection(0, 12); // Move B point forward by 1 bar
                return true; // Consume the event
            }
        });
    }

    private void setupShiftButtons() {
        shiftSelectionPrevBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                adjustSelection(-1, -1); // Shift both A and B points backward by 1/12
            }
        });

        shiftSelectionPrevBtn.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                adjustSelection(-12, -12); // Shift both A and B points backward by 1 bar
                return true; // Consume the event
            }
        });

        shiftSelectionNextBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                adjustSelection(1, 1); // Shift both A and B points forward by 1/12
            }
        });

        shiftSelectionNextBtn.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                adjustSelection(12, 12); // Shift both A and B points forward by 1 bar
                return true; // Consume the event
            }
        });
    }

    private void adjustSelection(int deltaStart, int deltaEnd) {
        Log.d("Tunas", "adjustSelection: deltaStart=" + deltaStart + ", deltaEnd=" + deltaEnd + ", current startBar=" + selectionStartBar + ", startTwelfths=" + selectionStartTwelfths + ", endBar=" + selectionEndBar + ", endTwelfths=" + selectionEndTwelfths);
        if (barPositions == null || barPositions.isEmpty()) {
            return;
        }

        // First adjust the start point without special checks
        int newStartBar = selectionStartBar;
        int newStartTwelfths = selectionStartTwelfths + deltaStart;

        // Handle bar boundary crossing for start
        while (newStartTwelfths < 0) {
            newStartBar--;
            newStartTwelfths += 12;
        }
        while (newStartTwelfths >= 12) {
            newStartBar++;
            newStartTwelfths -= 12;
        }

        // Second adjust the end point without special checks
        int newEndBar = selectionEndBar;
        int newEndTwelfths = selectionEndTwelfths + deltaEnd;

        // Handle bar boundary crossing for end
        while (newEndTwelfths < 0) {
            newEndBar--;
            newEndTwelfths += 12;
        }
        while (newEndTwelfths >= 12) {
            newEndBar++;
            newEndTwelfths -= 12;
        }

        // Now perform validation checks
        // Check if start went before start of file
        if (newStartBar < 0) {
            return; // Bail - can't go before start of file
        }

        // Check if end went after end of file
        if (newEndBar >= barPositions.size()) {
            return; // Bail - can't go after end of file
        }

        // Check if selection end is still after selection start
        if (newEndBar < newStartBar || (newEndBar == newStartBar && newEndTwelfths < newStartTwelfths)) {
            return; // Bail - selection would be invalid or too small
        }

        // Apply the selection change
        Log.d("Tunas", "adjustSelection: final newStartBar=" + newStartBar + ", newStartTwelfths=" + newStartTwelfths + ", newEndBar=" + newEndBar + ", newEndTwelfths=" + newEndTwelfths);
        handlePlaybackAfterSelectionChange(newStartBar, newEndBar, newStartTwelfths, newEndTwelfths);
    }

    private void setupScrollViewScrollListener() {
        ScrollView scrollView = findViewById(R.id.barScrollView);
        if (scrollView != null) {
            scrollView.setOnScrollChangeListener(new View.OnScrollChangeListener() {
                @Override
                public void onScrollChange(View v, int scrollX, int scrollY, int oldScrollX, int oldScrollY) {
                    // Only update if the scroll position actually changed (user scrolled)
                    if (scrollX != oldScrollX || scrollY != oldScrollY) {
                        lastUserScrollTimeMs = System.currentTimeMillis();
                        Log.d("Tunas", "User scroll detected, disabling auto-scroll for 5 seconds");
                    }
                }
            });
        }
    }

    private void updateLoopButtonState() {
        // Green when enabled, gray when disabled
        loopBtn.setBackgroundColor(loopOn ? 0xFF4CAF50 : 0xFFE0E0E0); 
    }

    private void updateGotoButtonState() {
        // Green when enabled, gray when disabled
        gotoBtn.setBackgroundColor(gotoOn ? 0xFF4CAF50 : 0xFFE0E0E0); 
    }

    private int[] getRainbowColors() {
        // Generate 7 light rainbow colors for better contrast with black text
        return new int[]{
            0xFFFFCCCC, // Light Red
            0xFFFFE4CC, // Light Orange
            0xFFFFFFCC, // Light Yellow
            0xFFCCFFCC, // Light Green
            0xFFCCE5FF, // Light Blue
            0xFFE5CCFF, // Light Indigo
            0xFFF5CCFF  // Light Violet
        };
    }

    private boolean fullySelectBar(int bar) {
        if (bar == selectionStartBar && selectionStartTwelfths != 0) return false;
        if (bar == selectionEndBar && selectionEndTwelfths != 11) return false;
        if (bar < selectionStartBar) return false;
        if (bar > selectionEndBar) return false;
        return true;
    }

    private void highlightBars(int startBar, int endBar, int startTwelfths, int endTwelfths) {
        Log.d("Tunas", "highlightBars: startBar=" + startBar + ", endBar=" + endBar + ", startTwelfths=" + startTwelfths + ", endTwelfths=" + endTwelfths);

        // Set new highlight range
        selectionStartBar = startBar;
        selectionEndBar = endBar;
        selectionStartTwelfths = startTwelfths;
        selectionEndTwelfths = endTwelfths;

        // Get rainbow colors
        int[] rainbowColors = getRainbowColors();
        int numColors = rainbowColors.length;

        // Apply highlighting to all buttons based on selection
        if (barPositions != null) {
            for (int i = 0; i < barPositions.size(); i++) {
                Button button = findViewById(i + 1); // Button IDs are 1-based
                if (button != null) {
                    if (fullySelectBar(i)) {
                        // Fully selected bars (between start and end) - no partial selection
                        int colorIndex = i % numColors;
                        button.setBackgroundColor(rainbowColors[colorIndex]);
                        // Log.d("Tunas", "highlightBars: bar " + i + " set to FULL rainbow (between bars)");
                    } else {
                        // Default gray background for non-selected bars
                        button.setBackgroundColor(0xFFE0E0E0);
                        // Log.d("Tunas", "highlightBars: bar " + i + " set to GRAY (unselected)");
                    }
                } else {
                    Log.d("Tunas", "highlightBars: button " + (i + 1) + " not found");
                }
            }
        }

        // Handle overlay positioning for partial bar highlighting
        updateSelectionOverlaysInHighlightBars();
    }

    private void updateSelectionOverlaysInHighlightBars() {
        Log.d("Tunas", "updateSelectionOverlaysInHighlightBars: startBar=" + selectionStartBar + ", startTwelfths=" + selectionStartTwelfths + ", endBar=" + selectionEndBar + ", endTwelfths=" + selectionEndTwelfths);

        // Hide overlays by default
        if (startBarOverlay != null) {
            startBarOverlay.setVisibility(View.INVISIBLE);
            Log.d("Tunas", "updateSelectionOverlaysInHighlightBars: hiding startBarOverlay");
        }
        if (endBarOverlay != null) {
            endBarOverlay.setVisibility(View.INVISIBLE);
            Log.d("Tunas", "updateSelectionOverlaysInHighlightBars: hiding endBarOverlay");
        }

        if (barPositions == null || barPositions.isEmpty()) {
            Log.d("Tunas", "updateSelectionOverlaysInHighlightBars: no bar positions, returning");
            return;
        }

        // Get the ScrollView content for positioning
        FrameLayout scrollContent = (FrameLayout) ((ScrollView) findViewById(R.id.barScrollView)).getChildAt(0);
        if (scrollContent == null) {
            return;
        }

        int[] contentLocation = new int[2];
        scrollContent.getLocationOnScreen(contentLocation);
        int marginPx = (int) (2 * getResources().getDisplayMetrics().density);

        // Check if bars need partial highlighting
        boolean startBarNeedsOverlay = (selectionStartBar >= 0 && selectionStartBar < barPositions.size() &&
                                      selectionStartTwelfths > 0);
        boolean endBarNeedsOverlay = (selectionEndBar >= 0 && selectionEndBar < barPositions.size() &&
                                    selectionEndTwelfths < 11);

        Log.d("Tunas", "updateSelectionOverlaysInHighlightBars: startBarNeedsOverlay=" + startBarNeedsOverlay + ", endBarNeedsOverlay=" + endBarNeedsOverlay);

        // For single bar selection, only show one overlay covering the selected portion
        if (selectionStartBar == selectionEndBar) {
            Log.d("Tunas", "updateSelectionOverlaysInHighlightBars: single bar selection");
            if (startBarNeedsOverlay || endBarNeedsOverlay) {
                Button button = findViewById(selectionStartBar + 1);
                if (button != null && startBarOverlay != null) {
                    Log.d("Tunas", "updateSelectionOverlaysInHighlightBars: showing startBarOverlay for single bar, startTwelfths=" + selectionStartTwelfths + ", endTwelfths=" + selectionEndTwelfths);
                    updatePartialBarOverlay(startBarOverlay, button, scrollContent, contentLocation,
                                          marginPx, selectionStartBar, selectionStartTwelfths, selectionEndTwelfths);
                } else {
                    Log.d("Tunas", "updateSelectionOverlaysInHighlightBars: button or overlay null for single bar");
                }
            } else {
                Log.d("Tunas", "updateSelectionOverlaysInHighlightBars: no overlay needed for single bar");
            }
            // Hide end overlay for single bar
            if (endBarOverlay != null) {
                endBarOverlay.setVisibility(View.INVISIBLE);
                Log.d("Tunas", "updateSelectionOverlaysInHighlightBars: hiding endBarOverlay for single bar");
            }
        } else {
            Log.d("Tunas", "updateSelectionOverlaysInHighlightBars: multi-bar selection");
            // Multi-bar selection - show separate overlays for start and end bars
            if (startBarNeedsOverlay && startBarOverlay != null) {
                Button startButton = findViewById(selectionStartBar + 1);
                if (startButton != null) {
                    Log.d("Tunas", "updateSelectionOverlaysInHighlightBars: showing startBarOverlay for start bar");
                    updatePartialBarOverlay(startBarOverlay, startButton, scrollContent, contentLocation,
                                          marginPx, selectionStartBar, selectionStartTwelfths, 11);
                }
            }

            if (endBarNeedsOverlay && endBarOverlay != null) {
                Button endButton = findViewById(selectionEndBar + 1);
                if (endButton != null) {
                    Log.d("Tunas", "updateSelectionOverlaysInHighlightBars: showing endBarOverlay for end bar");
                    updatePartialBarOverlay(endBarOverlay, endButton, scrollContent, contentLocation,
                                          marginPx, selectionEndBar, 0, selectionEndTwelfths);
                }
            }
        }
    }

    private void createButtonGrid() {
        LinearLayout buttonContainer = findViewById(R.id.buttonContainer);
        if (buttonContainer == null) {
            return;
        }

        buttonContainer.removeAllViews();

        // Get the number of bars from the loaded positions
        int numBars = (barPositions != null) ? barPositions.size() : 0;
        if (numBars == 0) {
            // No bars loaded, create a single row with a message
            LinearLayout rowLayout = new LinearLayout(this);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            rowLayout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

            Button button = new Button(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMargins(
                (int) (2 * getResources().getDisplayMetrics().density),
                (int) (2 * getResources().getDisplayMetrics().density),
                (int) (2 * getResources().getDisplayMetrics().density),
                (int) (2 * getResources().getDisplayMetrics().density));
            button.setLayoutParams(params);
            button.setText("No bars available");
            button.setEnabled(false);

            rowLayout.addView(button);
            buttonContainer.addView(rowLayout);
            return;
        }


        // Create buttons with section markers and horizontal dividers
        int sectionButtonNumber = 1; // Button number within current section
        int sectionIndex = 0; // Section counter for naming
        int buttonsPerRow = 4;
        int currentRowButtonCount = 0;
        LinearLayout currentRowLayout = null;

        for (int i = 0; i < numBars; i++) {
            boolean isSection = isSectionMarker.get(i);

            // If this is a section marker, add a horizontal divider and potentially start a new row
            if (isSection) {
                // If we have a current row that's not empty, finish it and add it to container
                if (currentRowLayout != null && currentRowButtonCount > 0) {
                    // Fill remaining slots in the current row with invisible spacers to maintain sizing
                    fillRowWithSpacers(currentRowLayout, buttonsPerRow - currentRowButtonCount);
                    buttonContainer.addView(currentRowLayout);
                }

                // Add horizontal divider with section name
                String sectionName = sectionNames.get(i);
                View divider = createSectionDivider(sectionName, sectionIndex);
                buttonContainer.addView(divider);
                sectionIndex++;

                // Start a new row and reset section numbering
                currentRowLayout = createNewRowLayout();
                currentRowButtonCount = 0;
                sectionButtonNumber = 1;
            } else {
                // If we don't have a current row, create one
                if (currentRowLayout == null) {
                    currentRowLayout = createNewRowLayout();
                    currentRowButtonCount = 0;
                }
            }

            // Add the button to the current row
            Button button = createButton(sectionButtonNumber, i + 1); // Display section number, use global index for ID
            currentRowLayout.addView(button);
            currentRowButtonCount++;
            sectionButtonNumber++;

            // If row is full or this is the last button, finish the row
            if (currentRowButtonCount >= buttonsPerRow || i == numBars - 1) {
                // Fill remaining slots with spacers if not a full row
                if (currentRowButtonCount < buttonsPerRow) {
                    fillRowWithSpacers(currentRowLayout, buttonsPerRow - currentRowButtonCount);
                }
                buttonContainer.addView(currentRowLayout);
                currentRowLayout = null;
                currentRowButtonCount = 0;
            }
        }
    }

    private LinearLayout createNewRowLayout() {
        LinearLayout rowLayout = new LinearLayout(this);
        rowLayout.setOrientation(LinearLayout.HORIZONTAL);
        rowLayout.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));
        return rowLayout;
    }

    private Button createButton(int displayNumber, int globalIndex) {
        Button button = new Button(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(
            (int) (2 * getResources().getDisplayMetrics().density),
            (int) (2 * getResources().getDisplayMetrics().density),
            (int) (2 * getResources().getDisplayMetrics().density),
            (int) (2 * getResources().getDisplayMetrics().density));
        button.setLayoutParams(params);
        button.setText(String.valueOf(displayNumber)); // Display section-local number
        button.setId(globalIndex); // Use global index for ID (1-based for button listeners)
        button.setBackgroundColor(0xFFE0E0E0); // Default gray background
        return button;
    }

    private void fillRowWithSpacers(LinearLayout rowLayout, int numSpacers) {
        for (int i = 0; i < numSpacers; i++) {
            View spacer = new View(this);
            LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            spacerParams.setMargins(
                (int) (2 * getResources().getDisplayMetrics().density),
                (int) (2 * getResources().getDisplayMetrics().density),
                (int) (2 * getResources().getDisplayMetrics().density),
                (int) (2 * getResources().getDisplayMetrics().density));
            spacer.setLayoutParams(spacerParams);
            rowLayout.addView(spacer);
        }
    }

    private View createSectionDivider(String rawSectionName, int sectionIndex) {
        // Determine display name: use raw name if meaningful, otherwise "Section N"
        String displayName;
        if (rawSectionName != null && !rawSectionName.isEmpty() && !rawSectionName.equals("1")) {
            displayName = rawSectionName;
        } else {
            displayName = "Section " + (sectionIndex + 1);
        }

        // Create a container for the divider
        LinearLayout dividerContainer = new LinearLayout(this);
        dividerContainer.setOrientation(LinearLayout.HORIZONTAL);
        dividerContainer.setGravity(android.view.Gravity.CENTER_VERTICAL);

        LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        containerParams.setMargins(0, (int) (4 * getResources().getDisplayMetrics().density), 0,
            (int) (4 * getResources().getDisplayMetrics().density));
        dividerContainer.setLayoutParams(containerParams);

        // Left line
        View leftLine = new View(this);
        LinearLayout.LayoutParams leftLineParams = new LinearLayout.LayoutParams(
            0, (int) (2 * getResources().getDisplayMetrics().density), 1f);
        leftLine.setLayoutParams(leftLineParams);
        leftLine.setBackgroundColor(0xFFCCCCCC);
        dividerContainer.addView(leftLine);

        // Section name text
        TextView sectionText = new TextView(this);
        sectionText.setText(displayName);
        sectionText.setTextSize(14);
        sectionText.setTextColor(0xFF666666);
        sectionText.setPadding((int) (8 * getResources().getDisplayMetrics().density), 0,
            (int) (8 * getResources().getDisplayMetrics().density), 0);
        sectionText.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sectionText.setLayoutParams(textParams);
        dividerContainer.addView(sectionText);

        // Right line
        View rightLine = new View(this);
        LinearLayout.LayoutParams rightLineParams = new LinearLayout.LayoutParams(
            0, (int) (2 * getResources().getDisplayMetrics().density), 1f);
        rightLine.setLayoutParams(rightLineParams);
        rightLine.setBackgroundColor(0xFFCCCCCC);
        dividerContainer.addView(rightLine);

        return dividerContainer;
    }

    private void setupBarButtons() {
        // Find all buttons in the layout and set up listeners for numeric ones
        setupBarButtonsRecursive(findViewById(android.R.id.content));
    }

    private void setupBarButtonsRecursive(View view) {
        if (view instanceof Button) {
            Button button = (Button) view;
            int buttonId = button.getId();
            int maxBars = (barPositions != null) ? barPositions.size() : 0;
            // Check if this is a bar button (ID should be between 1 and maxBars)
            if (buttonId >= 1 && buttonId <= maxBars) {
                final int currentBarIndex = buttonId - 1; // Convert to 0-based index
                button.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onBarClicked(currentBarIndex);
                    }
                });
                button.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        onBarLongClicked(currentBarIndex);
                        return true; // Consume the event
                    }
                });
            }
        } else if (view instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) view;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                setupBarButtonsRecursive(viewGroup.getChildAt(i));
            }
        }
    }

    private void updateMediaSource() {
        Log.d("Tunas", "updateMediaSource called");

        // Selection is always valid - use current selection range
        long startMs = calculateSelectionStartMs();
        long endMs = calculateSelectionEndMs();

        Log.d("Tunas", "updateMediaSource: selection from bar " + selectionStartBar +
              " to " + selectionEndBar + ", startMs=" + startMs + ", endMs=" + endMs);

        // Track the media source start time and segment duration for position indicator
        currentMediaSourceStartMs = startMs;
        currentSegmentDurationMs = endMs - startMs;
        Log.d("Tunas", "MediaSource set: startMs=" + startMs + ", endMs=" + endMs +
              ", currentMediaSourceStartMs=" + currentMediaSourceStartMs);

        // Prevent auto play
        exoPlayer.stop();
        exoPlayer.setPlayWhenReady(false);
        exoPlayer.setRepeatMode(exoPlayer.REPEAT_MODE_OFF);

        long durationMs = endMs - startMs;
        if (loopOn && durationMs < 60000 && durationMs > 200) {
            try {
                // Calculate number of repeats to not exceed 5 minutes total
                int repeats;
                // Calculate repeats so total time doesn't exceed 10 minutes (600,000 ms)
                repeats = (int) Math.ceil(300000.0 / durationMs);
                repeats = Math.min(repeats, 20); // Cap at 20 repeats maximum
                repeats = Math.max(repeats, 2); // At least 2 repeats

                exoPlayer.setMediaSource(AudioLoopUtils.createLoopedPcmMediaSource(
                    this, audioFiles.get(currentAudioIndex), startMs, endMs, repeats));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            // Create data source factory for reading files
            DataSource.Factory dataSourceFactory = new com.google.android.exoplayer2.upstream.DefaultDataSource.Factory(this);

            // Fallback to simple clipping without looping
            MediaItem mediaItem = MediaItem.fromUri(android.net.Uri.fromFile(audioFiles.get(currentAudioIndex)));
            ClippingMediaSource clippingSource = new ClippingMediaSource(
                new com.google.android.exoplayer2.source.ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(mediaItem),
                startMs * 1000,
                endMs * 1000
            );
            exoPlayer.setMediaSource(clippingSource);
        }
        exoPlayer.prepare();
    }

    private long calculateSelectionStartMs() {
        if (barPositions == null || barPositions.isEmpty()) {
            return 0;
        }

        long barStartMs = barPositions.get(selectionStartBar);
        long barEndMs = (selectionStartBar + 1 < barPositions.size()) ?
            barPositions.get(selectionStartBar + 1) : audioDuration;

        long barDurationMs = barEndMs - barStartMs;
        // Add the twelfths offset within the bar (each twelfth is barDurationMs / 12)
        long twelfthOffsetMs = (barDurationMs * selectionStartTwelfths) / 12;

        return barStartMs + twelfthOffsetMs;
    }

    private long calculateSelectionEndMs() {
        if (barPositions == null || barPositions.isEmpty()) {
            return audioDuration;
        }

        long barStartMs = barPositions.get(selectionEndBar);
        long barEndMs = (selectionEndBar + 1 < barPositions.size()) ?
            barPositions.get(selectionEndBar + 1) : audioDuration;

        // If this is the last bar and we're at maximum twelfths, return full audio duration
        if (selectionEndBar == barPositions.size() - 1 && selectionEndTwelfths >= 11) {
            return audioDuration;
        }

        long barDurationMs = barEndMs - barStartMs;
        // Add the twelfths offset within the bar (each twelfth is barDurationMs / 12)
        long twelfthOffsetMs = (barDurationMs * (1 + selectionEndTwelfths)) / 12;

        return barStartMs + twelfthOffsetMs;
    }

    private long getCurrentPositionInSegment() {
        long currentPosition = exoPlayer.getCurrentPosition();
        return (currentSegmentDurationMs > 0) ?
            (currentPosition % currentSegmentDurationMs) : currentPosition;
    }

    private void handlePlaybackAfterSelectionChange(int newStartBar, int newEndBar, int newStartTwelfths, int newEndTwelfths) {
        // Capture current state before making changes
        int oldStartBar = selectionStartBar;
        int oldEndBar = selectionEndBar;
        int oldStartTwelfths = selectionStartTwelfths;
        int oldEndTwelfths = selectionEndTwelfths;
        boolean wasPlaying = exoPlayer.isPlaying();
        long positionInCurrentSegment = getCurrentPositionInSegment();

        // Update selection and highlighting
        highlightBars(newStartBar, newEndBar, newStartTwelfths, newEndTwelfths);

        // Determine playback continuation strategy
        boolean pointAChanged = (oldStartBar != newStartBar) || (oldStartTwelfths != newStartTwelfths);
        boolean selectionExpanded = !pointAChanged && ((newEndBar > oldEndBar) || (newEndBar == oldEndBar && newEndTwelfths > oldEndTwelfths));

        long seekPosition;
        boolean shouldContinue;

        if (selectionExpanded && wasPlaying) {
            // Selection expanded, continue from current position
            seekPosition = positionInCurrentSegment;
            shouldContinue = true;
        } else if (!pointAChanged && wasPlaying) {
            // Long press adjustment - check if current position is within new selection
            long selectionStartTime = calculateSelectionStartMs();
            long selectionEndTime = calculateSelectionEndMs();

            long absolutePositionInOldSelection = currentMediaSourceStartMs + positionInCurrentSegment;
            boolean isWithinNewSelection = absolutePositionInOldSelection >= selectionStartTime &&
                                         absolutePositionInOldSelection < selectionEndTime;

            if (isWithinNewSelection) {
                seekPosition = absolutePositionInOldSelection - selectionStartTime;
                shouldContinue = true;
            } else {
                seekPosition = 0;
                shouldContinue = false;
            }
        } else {
            // Point A changed or wasn't playing
            seekPosition = 0;
            shouldContinue = false;
        }

        // Update media source
        updateMediaSource();

        // Handle playback
        if (shouldContinue) {
            exoPlayer.seekTo(seekPosition);
        }
        exoPlayer.play();
        isStopped = false;
        Log.d("Tunas", "handleSelectionChange: " +
              (shouldContinue ? "continuing at " + seekPosition + "ms" : "starting from beginning"));
    }

    private void onBarClicked(int barIndex) {
        Log.d("Tunas", "onBarClicked called with barIndex: " + barIndex);

        // If no bar positions loaded, try to load them first
        if (barPositions == null || barPositions.isEmpty()) {
            Log.d("Tunas", "onBarClicked: loading bar positions for audioIndex: " + currentAudioIndex);
            loadBarPositions(currentAudioIndex);
        }

        if (barPositions == null || barIndex >= barPositions.size()) {
            Log.d("Tunas", "onBarClicked: no bar positions available");
            return;
        }

        // Check if goto mode is enabled
        if (gotoOn) {
            Log.d("Tunas", "onBarClicked: goto mode enabled, checking if bar " + barIndex + " is in selection");
            if (isBarBeginningInSelection(barIndex)) {
                startPlaybackFromBarBeginning(barIndex);
            } else {
                Log.d("Tunas", "onBarClicked: bar " + barIndex + " beginning is not in selection, ignoring");
            }
            return;
        }

        // Check if there's any partial selection (selection doesn't align to bar boundaries)
        boolean hasPartialSelection = (selectionStartTwelfths > 0) || (selectionEndTwelfths < 11);

        int startBar, endBar;

        if (hasPartialSelection) {
            // If there's any partial selection, clicking any bar selects just that bar fully
            startBar = barIndex;
            endBar = barIndex;
            Log.d("Tunas", "onBarClicked: partial selection detected, selecting single bar " + barIndex + " fully");
        } else {
            // Normal expansion logic when selection is aligned to bar boundaries
            int numBars = barPositions.size();

            if (selectionStartBar == barIndex && selectionEndBar == barIndex) {
                // Currently single bar selected, expand to 2 bars
                startBar = barIndex;
                endBar = Math.min(barIndex + 1, numBars - 1);
                Log.d("Tunas", "onBarClicked: single bar selected, expanding to 2 bars: " + startBar + " to " + endBar);
            } else if (selectionStartBar == barIndex && selectionEndBar == barIndex + 1) {
                // Currently 2 bars selected, expand to 4 bars
                startBar = barIndex;
                endBar = Math.min(barIndex + 3, numBars - 1);
                Log.d("Tunas", "onBarClicked: 2 bars selected, expanding to 4 bars: " + startBar + " to " + endBar);
            } else if (selectionStartBar == barIndex && selectionEndBar == barIndex + 3) {
                // Currently 4 bars selected, expand to 8 bars
                startBar = barIndex;
                endBar = Math.min(barIndex + 7, numBars - 1);
                Log.d("Tunas", "onBarClicked: 4 bars selected, expanding to 8 bars: " + startBar + " to " + endBar);
            } else if (selectionStartBar == barIndex && selectionEndBar == barIndex + 7) {
                // Currently 8 bars selected, expand to end of file
                startBar = barIndex;
                endBar = numBars - 1;
                Log.d("Tunas", "onBarClicked: 8 bars selected, expanding to end of file: " + startBar + " to " + endBar);
            } else {
                // Any other selection state, start over with single bar
                startBar = barIndex;
                endBar = barIndex;
                Log.d("Tunas", "onBarClicked: other selection state, selecting single bar " + barIndex);
            }
        }

        // Handle the selection change with all associated logic
        handlePlaybackAfterSelectionChange(startBar, endBar, 0, 11);
    }

    private boolean isBarBeginningInSelection(int barIndex) {
        // Check if the bar index is within the selection range
        return barIndex >= selectionStartBar && barIndex <= selectionEndBar;
    }

    private void startPlaybackFromBarBeginning(int barIndex) {
        Log.d("Tunas", "startPlaybackFromBarBeginning: starting playback from bar " + barIndex);

        if (barPositions == null || barIndex >= barPositions.size()) {
            Log.d("Tunas", "startPlaybackFromBarBeginning: invalid bar index or no bar positions");
            return;
        }

        long seekPositionMs = 0;

        if (barIndex == selectionStartBar) {
            Log.d("Tunas", "startPlaybackFromBarBeginning: simply starting from beginning of selection");
        } else {
            // Calculate the absolute time position of the beginning of the bar
            long barStartMs = barPositions.get(barIndex);

            // Calculate the relative position within the current selection
            long selectionStartMs = calculateSelectionStartMs();
            seekPositionMs = barStartMs - selectionStartMs;

            Log.d("Tunas", "startPlaybackFromBarBeginning: barStartMs=" + barStartMs + ", selectionStartMs=" + selectionStartMs + ", seekPositionMs=" + seekPositionMs);
        }

        // Seek to the beginning of the bar within the current selection
        exoPlayer.seekTo(seekPositionMs);

        // Start playback if not already playing
        if (isStopped) {
            exoPlayer.play();
            isStopped = false;
        }

        Log.d("Tunas", "startPlaybackFromBarBeginning: playback started from bar " + barIndex);
    }

    private void onBarLongClicked(int barIndex) {
        Log.d("Tunas", "onBarLongClicked called with barIndex: " + barIndex);

        // If no bar positions loaded, ignore
        if (barPositions == null || barPositions.isEmpty() || barIndex >= barPositions.size()) {
            Log.d("Tunas", "onBarLongClicked: no bar positions available or invalid index");
            return;
        }

        // Set the long-clicked bar as point B (end of selection), keep point A unchanged
        int newEndBar = barIndex;

        // Ignore if user tries to set point B before point A
        if (newEndBar < selectionStartBar) {
            Log.d("Tunas", "onBarLongClicked: ignoring attempt to set end before start");
            return;
        }

        Log.d("Tunas", "onBarLongClicked: setting end bar to " + newEndBar + ", start remains " + selectionStartBar);

        // Handle the selection change with all associated logic
        handlePlaybackAfterSelectionChange(selectionStartBar, newEndBar, selectionStartTwelfths, 11);
    }

    private void loadBarPositions(int audioIndex) {
        Log.d("Tunas", "loadBarPositions called for audioIndex: " + audioIndex);
        barPositions = new ArrayList<>();
        isSectionMarker = new ArrayList<>();
        sectionNames = new ArrayList<>();
        if (audioFiles.isEmpty() || audioIndex >= audioFiles.size()) {
            Log.d("Tunas", "loadBarPositions: no audio files available");
            showBarFileInfoDialog(0, "No audio files available");
            return;
        }

        File audioFile = audioFiles.get(audioIndex);
        String audioName = audioFile.getName();
        String baseName = audioName.substring(0, audioName.lastIndexOf('.'));
        File xscFile = new File(audioFile.getParent(), baseName + ".xsc");

        Log.d("Tunas", "loadBarPositions: looking for xsc file: " + xscFile.getAbsolutePath());

        // Check if this is an M4A file - if so, create fake bars instead of looking for XSC
        if (audioName.toLowerCase().endsWith(".m4a")) {
            Log.d("Tunas", "loadBarPositions: M4A file detected, creating fake bars");
            createFakeBarsForM4A(audioFile);
        } else if (xscFile.exists() && xscFile.isFile()) {
            Log.d("Tunas", "loadBarPositions: xsc file exists, reading...");
            try (BufferedReader reader = new BufferedReader(new FileReader(xscFile))) {
                String line;
                boolean inMarkersSection = false;
                int lineNumber = 0;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    if (line.equals("SectionStart,Markers")) {
                        inMarkersSection = true;
                        Log.d("Tunas", "loadBarPositions: entered markers section");
                    } else if (line.equals("SectionEnd,Markers")) {
                        inMarkersSection = false;
                        Log.d("Tunas", "loadBarPositions: exited markers section");
                    } else if (inMarkersSection && (line.startsWith("S,") || line.startsWith("M,"))) {
                        // Parse marker line like: S,-1,0,Section name,1,0:00:00.060000
                        String[] parts = line.split(",");
                        if (parts.length >= 6) {
                            String timestampStr = parts[5]; // Last field contains the timestamp
                            try {
                                long position = TuneUtils.parseXscTimestamp(timestampStr);
                                boolean isSection = line.startsWith("S,");
                                barPositions.add(position);
                                isSectionMarker.add(isSection);

                                // Extract section name for "S" markers (field 4, 0-indexed as parts[3])
                                String sectionName = null;
                                if (isSection && parts.length >= 4) {
                                    sectionName = parts[3].trim();
                                    // Remove quotes if present
                                    if (sectionName.startsWith("\"") && sectionName.endsWith("\"")) {
                                        sectionName = sectionName.substring(1, sectionName.length() - 1);
                                    }
                                }
                                sectionNames.add(sectionName);

                            } catch (Exception e) {
                                Log.d("Tunas", "loadBarPositions: skipping invalid timestamp in line " + lineNumber + ": '" + timestampStr + "' - " + e.getMessage());
                            }
                        } else {
                            Log.d("Tunas", "loadBarPositions: skipping malformed marker line " + lineNumber + ": '" + line + "'");
                        }
                    }
                }
                Log.d("Tunas", "loadBarPositions: loaded " + barPositions.size() + " bar positions total");

                // If the first bar is more than 0.2s after the beginning, insert an intro section
                if (!barPositions.isEmpty() && barPositions.get(0) > 200) {
                    Log.d("Tunas", "loadBarPositions: first bar is " + barPositions.get(0) + "ms after start, inserting intro section");
                    barPositions.add(0, 0L); // Insert bar at time 0
                    isSectionMarker.add(0, true); // Mark as section start
                    sectionNames.add(0, null); // No special name, will get "Section 1"
                }

                // showBarFileInfoDialog(barPositions.size(), "XSC file loaded successfully");
            } catch (IOException e) {
                Log.d("Tunas", "loadBarPositions: error reading xsc file: " + e.getMessage());
                showBarFileInfoDialog(0, "Error reading xsc file: " + e.getMessage());
            }
        } else {
            Log.d("Tunas", "loadBarPositions: xsc file not found for " + audioName);
            showBarFileInfoDialog(0, "No xsc file found for " + audioName);
        }

        // Calculate audio duration as 10 seconds after the last bar position
        if (!barPositions.isEmpty()) {
            long lastBarPosition = 0;
            for (long position : barPositions) {
                lastBarPosition = Math.max(lastBarPosition, position);
            }
            audioDuration = lastBarPosition + 10000; // 10 seconds after last bar
            Log.d("Tunas", "loadBarPositions: calculated audioDuration=" + audioDuration + "ms (lastBar=" + lastBarPosition + "ms + 10s)");

            // Initialize selection to whole file (all bars)
            selectionStartBar = 0;
            selectionEndBar = barPositions.size() - 1;
            selectionStartTwelfths = 0;
            selectionEndTwelfths = 11; // End at 11/12 of last bar (effectively full bar)
            Log.d("Tunas", "loadBarPositions: initialized selection to whole file (bars " + selectionStartBar + " to " + selectionEndBar + ")");
        } else {
            audioDuration = 0; // Default when no bars loaded
            Log.d("Tunas", "loadBarPositions: no bar positions, set audioDuration=0");
            selectionStartBar = 0;
            selectionEndBar = 0;
        }
    }

    private void createFakeBarsForM4A(File audioFile) {
        Log.d("Tunas", "createFakeBarsForM4A: Creating fake bars for " + audioFile.getName());

        // Get audio duration using MediaExtractor
        long durationMs = 0;
        try {
            MediaExtractor extractor = new MediaExtractor();
            extractor.setDataSource(audioFile.getAbsolutePath());
            MediaFormat format = extractor.getTrackFormat(0);
            durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000; // Convert to milliseconds
            extractor.release();
            Log.d("Tunas", "createFakeBarsForM4A: Audio duration = " + durationMs + "ms");
        } catch (Exception e) {
            Log.e("Tunas", "createFakeBarsForM4A: Failed to get audio duration", e);
            showBarFileInfoDialog(0, "Error reading M4A file duration: " + e.getMessage());
            return;
        }

        // Clear existing data
        barPositions.clear();
        isSectionMarker.clear();
        sectionNames.clear();

        // Calculate number of full minutes in the audio
        int totalMinutes = (int) (durationMs / 60000); // 60 seconds per minute
        Log.d("Tunas", "createFakeBarsForM4A: Total minutes = " + totalMinutes);

        for (int minute = 0; minute <= totalMinutes; minute++) {
            // Create section marker at the start of each minute
            long sectionStartMs = minute * 60000L; // 60 seconds * 1000 ms

            // Don't create sections beyond the audio duration
            if (sectionStartMs >= durationMs) {
                break;
            }

            // Calculate section end time (either next minute or audio end)
            long sectionEndMs = Math.min((minute + 1) * 60000L, durationMs);
            int endMinute = minute;
            // -1 because we show the last second in the range
            int endSecond = (int) ((sectionEndMs - (minute * 60000L)) / 1000L) - 1;

            // Add section marker
            barPositions.add(sectionStartMs);
            isSectionMarker.add(true);
            sectionNames.add(String.format("%d:00 - %d:%02d", minute, endMinute, endSecond));

            // Add bars for each second in this minute (0 to endSecond)
            for (int second = 1; second <= endSecond; second++) {
                long barPositionMs = sectionStartMs + (second * 1000L);

                // Don't add bars beyond the audio duration
                if (barPositionMs >= durationMs) {
                    break;
                }

                barPositions.add(barPositionMs);
                isSectionMarker.add(false);
                sectionNames.add(null); // No special name for bars
            }
        }

        // Set audio duration to the actual file duration
        audioDuration = durationMs;

        // Initialize selection to whole file (all bars)
        selectionStartBar = 0;
        selectionEndBar = barPositions.size() - 1;
        selectionStartTwelfths = 0;
        selectionEndTwelfths = 11; // End at 11/12 of last bar (effectively full bar)
        Log.d("Tunas", "createFakeBarsForM4A: initialized selection to whole file (bars " + selectionStartBar + " to " + selectionEndBar + ")");

        Log.d("Tunas", "createFakeBarsForM4A: Created " + barPositions.size() + " fake bars for M4A file");
    }

    private void prepareMediaPlayer(int index) {
        if (audioFiles.isEmpty()) return;

        // Load bar positions for this audio file
        loadBarPositions(index);

        // Create buttons based on loaded bar positions
        createButtonGrid();
        setupBarButtons();

        // Update selection highlighting after layout is complete
        findViewById(R.id.buttonContainer).post(new Runnable() {
            @Override
            public void run() {
                highlightBars(selectionStartBar, selectionEndBar, selectionStartTwelfths, selectionEndTwelfths);
            }
        });

        // Add listener to handle playback state changes
        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                Log.d("Tunas", "onPlaybackStateChanged: state=" + playbackState + " (0=IDLE, 1=BUFFERING, 2=READY, 3=ENDED)");
                if (playbackState == Player.STATE_ENDED && !loopOn) {
                    // Playback naturally ended and looping is disabled, so mark as stopped
                    isStopped = true;
                    Log.d("Tunas", "onPlaybackStateChanged: playback ended, setting isStopped=true");
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                Log.d("Tunas", "onIsPlayingChanged: isPlaying=" + isPlaying + ", isStopped=" + isStopped +
                      ", currentMediaSourceStartMs before=" + currentMediaSourceStartMs);
                if (isPlaying) {
                    // Start position tracking when playback begins
                    Log.d("Tunas", "Starting position tracking");
                    handler.post(positionUpdateRunnable);
                } else {
                    // Stop position tracking when playback stops
                    Log.d("Tunas", "Stopping position tracking, resetting currentMediaSourceStartMs to 0");
                    handler.removeCallbacks(positionUpdateRunnable);
                }
            }

            @Override
            public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
                Log.d("Tunas", "onPositionDiscontinuity: old=" + oldPosition.positionMs + "ms, new=" + newPosition.positionMs + "ms, reason=" + reason);
            }
        });

        isStopped = true;
        updateMediaSource();
    }

    private void showBarFileInfoDialog(int barCount, String message) {
        String title = barCount > 0 ? "Bar File Loaded" : "Bar File Information";
        String fullMessage = message;
        if (barCount > 0) {
            fullMessage += "\n\nLoaded " + barCount + " bar position(s).";
        }

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(fullMessage)
                .setPositiveButton("OK", (dialog, which) -> {
                    // User confirmed, continue normally
                    dialog.dismiss();
                })
                .setCancelable(false) // Prevent dismissing by back button
                .show();
    }

    private void displayAudioFileName(int index) {
        if (!audioFiles.isEmpty() && index >= 0 && index < audioFiles.size()) {
            fileNameText.setText(audioFiles.get(index).getName());
            currentAudioIndex = index;
        }
    }

    private void nextAudio() {
        Log.d("Tunas", "nextAudio called");
        if (!audioFiles.isEmpty()) {
            // Stop playback when changing audio file
            if (!isStopped) {
                Log.d("Tunas", "nextAudio: pausing current playback");
                exoPlayer.pause();
                isStopped = true;
            }

            currentAudioIndex = (currentAudioIndex + 1) % audioFiles.size();
            displayAudioFileName(currentAudioIndex);
            // Reset state when changing audio files
            currentMediaSourceStartMs = 0; // Reset offset
            currentSegmentDurationMs = 0; // Reset segment duration
            // Hide position dot and selection overlays when switching files
            if (positionDot != null) {
                positionDot.setVisibility(View.INVISIBLE);
            }
            if (startBarOverlay != null) {
                startBarOverlay.setVisibility(View.INVISIBLE);
            }
            if (endBarOverlay != null) {
                endBarOverlay.setVisibility(View.INVISIBLE);
            }
            Log.d("Tunas", "nextAudio: reset bar tracking and stopped monitoring, switching to audio index " + currentAudioIndex);
            prepareMediaPlayer(currentAudioIndex);
        }
    }

    private void setupRandomButton() {
        randomButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String randomTune = TuneUtils.pickRandomTune(allTunes);
                if (randomTune != null) {
                    openTune(randomTune);
                }
            }
        });
    }

    private void setupLess4Button() {
        if (buttonLess4 == null) {
            return;
        }

        buttonLess4.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (exoPlayer == null || barPositions == null || barPositions.isEmpty()) {
                    return;
                }
                long selectionStartMs = calculateSelectionStartMs();
                long positionInSegment = getCurrentPositionInSegment();
                long absoluteMs = currentMediaSourceStartMs + positionInSegment;

                // Find current bar index (last bar where bar start <= absoluteMs)
                int currentBarIndex = -1;
                for (int i = 0; i < barPositions.size(); i++) {
                    if (absoluteMs >= barPositions.get(i)) {
                        currentBarIndex = i;
                    } else {
                        break;
                    }
                }

                double progressInBar = 0.0;
                if (currentBarIndex >= 0) {
                    long barStartMs = barPositions.get(currentBarIndex);
                    long barEndMs = (currentBarIndex + 1 < barPositions.size()) ?
                        barPositions.get(currentBarIndex + 1) : audioDuration;
                    if (barEndMs > barStartMs) {
                        progressInBar = (double) (absoluteMs - barStartMs) / (barEndMs - barStartMs);
                        progressInBar = Math.max(0.0, Math.min(1.0, progressInBar));
                    }
                }

                int targetBarIndex = currentBarIndex - 4;
                long targetAbsoluteMs;
                if (currentBarIndex < 0 || targetBarIndex < selectionStartBar) {
                    targetAbsoluteMs = selectionStartMs;
                } else {
                    long targetBarStartMs = barPositions.get(targetBarIndex);
                    long targetBarEndMs = (targetBarIndex + 1 < barPositions.size()) ?
                        barPositions.get(targetBarIndex + 1) : audioDuration;
                    targetAbsoluteMs = targetBarStartMs + (long) (progressInBar * (targetBarEndMs - targetBarStartMs));
                }

                long seekPositionMs = targetAbsoluteMs - selectionStartMs;
                exoPlayer.seekTo(seekPositionMs);
                updatePositionDot(exoPlayer.getCurrentPosition());
            }
        });
    }

    private void createHandWaveDetector() {
        Log.d("Tunas", "createHandWaveDetector() called");

        if (cameraSurfaceView != null) {
            Log.d("Tunas", "Creating gesture detector");
            try {
                handWaveDetector = new HandWaveDetector(this, cameraSurfaceView, new HandWaveDetector.WaveDetectionCallback() {
                    @Override
                    public void onGestureDetected() {
                        // Simulate clicking the stop/start button when gesture is detected
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (stopStartBtn != null) {
                                    Log.d("Tunas", "Gesture detected - triggering stop/start button");
                                    stopStartBtn.performClick();
                                }
                            }
                        });
                    }
                });
                Log.d("Tunas", "HandWaveDetector created successfully");

            } catch (Exception e) {
                Log.e("Tunas", "Exception during HandWaveDetector creation", e);
                Log.e("Tunas", "Exception message: " + e.getMessage());
                if (e.getCause() != null) {
                    Log.e("Tunas", "Exception cause: " + e.getCause().getMessage());
                }
            }
        } else {
            Log.w("Tunas", "Camera surface view is null, gesture detection disabled");
        }
    }

    private void openTune(String tuneName) {
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra("TUNE_NAME", tuneName);
        intent.putExtra("TUNE_PATH", TuneUtils.BASE_DIR + "/" + tuneName);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Stop position tracking
        if (handler != null && positionUpdateRunnable != null) {
            handler.removeCallbacks(positionUpdateRunnable);
        }
        if (exoPlayer != null) {
            exoPlayer.release();
            exoPlayer = null;
        }
        // Stop hand wave detector and release camera resources
        if (handWaveDetector != null) {
            handWaveDetector.stopCamera();
            handWaveDetector = null;
        }
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // No permission checking needed since MainActivity already handles this
    }
}
