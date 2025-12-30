package com.tunas.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.util.Log;
import android.view.SurfaceView;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer;
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HandWaveDetector detects hand gestures using MediaPipe for hand landmark detection.
 * Specifically detects when pinky and thumb are extended while other fingers are curled.
 */
public class HandWaveDetector {

    private static final String TAG = "Tunas";

    // Gesture detection parameters
    private static final float FIST_CONFIDENCE_THRESHOLD = 0.7f; // Minimum confidence for fist detection
    private static final long MIN_GESTURE_INTERVAL_MS = 1000; // Minimum time between gestures

    // Camera and processing components
    private final Context context;
    private final LifecycleOwner lifecycleOwner;
    private final SurfaceView surfaceView;
    private ProcessCameraProvider cameraProvider;
    private GestureRecognizer gestureRecognizer;
    private HandLandmarker handLandmarker;
    private ExecutorService cameraExecutor;
    private RunningMode runningMode;
    private long lastGestureTime = 0;

    // Callback interface
    public interface WaveDetectionCallback {
        void onGestureDetected();
    }

    private WaveDetectionCallback callback;

    /**
     * Constructor for HandWaveDetector
     * @param lifecycleOwner The Activity that owns the lifecycle
     * @param surfaceView SurfaceView (can be dummy, not used for display)
     * @param callback Callback to invoke when gesture is detected
     */
    public HandWaveDetector(LifecycleOwner lifecycleOwner, SurfaceView surfaceView, WaveDetectionCallback callback) {
        Log.d(TAG, "HandWaveDetector constructor called");
        this.lifecycleOwner = lifecycleOwner;
        this.surfaceView = surfaceView;
        this.context = lifecycleOwner instanceof Context ? (Context) lifecycleOwner : surfaceView.getContext();
        this.callback = callback;

        Log.d(TAG, "Context: " + (context != null));
        Log.d(TAG, "LifecycleOwner: " + (lifecycleOwner != null));

        Log.d(TAG, "About to create camera executor");
        cameraExecutor = Executors.newSingleThreadExecutor();
        Log.d(TAG, "Camera executor created successfully");

        Log.d(TAG, "About to enter try block");
        try {
            Log.d(TAG, "INSIDE TRY BLOCK: Calling initializeMediaPipe()");
            initializeMediaPipe();
            Log.d(TAG, "INSIDE TRY BLOCK: initializeMediaPipe() returned");
            Log.d(TAG, "INSIDE TRY BLOCK: Calling startCamera()");
            startCamera();
            Log.d(TAG, "INSIDE TRY BLOCK: startCamera() returned");
            Log.d(TAG, "INSIDE TRY BLOCK: HandWaveDetector constructor completed successfully");
        } catch (Exception e) {
            Log.e(TAG, "CRITICAL: Exception caught in HandWaveDetector constructor", e);
            Log.e(TAG, "CRITICAL: Exception message: " + e.getMessage());
            Log.e(TAG, "CRITICAL: Exception class: " + e.getClass().getName());
            if (e.getCause() != null) {
                Log.e(TAG, "CRITICAL: Exception cause: " + e.getCause().getMessage());
                Log.e(TAG, "CRITICAL: Cause class: " + e.getCause().getClass().getName());
            }
            Log.e(TAG, "CRITICAL: Printing full stack trace:");
            e.printStackTrace();
        }
        Log.d(TAG, "After try-catch block");
    }

    /**
     * Initialize MediaPipe Hand Landmarker
     */
    private void initializeMediaPipe() {
        try {
            Log.d(TAG, "Initializing MediaPipe Hand Landmarker with downloaded model...");

            // Create BaseOptions with the downloaded model file
            BaseOptions baseOptions = BaseOptions.builder()
                    .setModelAssetPath("hand_landmarker.task")
                    .setDelegate(Delegate.CPU)
                    .build();

            // Configure for live stream processing with gesture detection settings
            runningMode = RunningMode.LIVE_STREAM;
            HandLandmarker.HandLandmarkerOptions options = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setMinHandDetectionConfidence(0.5f)
                    .setMinHandPresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setNumHands(1)
                    .setRunningMode(runningMode)
                    .setResultListener(this::processHandLandmarks)
                    .build();

            // Create the hand landmarker
            handLandmarker = HandLandmarker.createFromOptions(context, options);
            Log.d(TAG, "MediaPipe Hand Landmarker initialized successfully");

        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize MediaPipe Hand Landmarker", e);
            Log.e(TAG, "Error: " + e.getMessage());
            if (e.getCause() != null) {
                Log.e(TAG, "Cause: " + e.getCause().getMessage());
            }
        }
    }

    /**
     * Start the camera with CameraX and begin gesture detection
     */
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(context);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();

                // Set up image analysis for MediaPipe
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::processImage);

                // Use front camera
                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;

                // Bind use cases
                cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        imageAnalysis);

                Log.d(TAG, "CameraX initialized successfully - gesture detection active");

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Failed to initialize CameraX", e);
            }
        }, ContextCompat.getMainExecutor(context));
    }

    /**
     * Stop the camera and release MediaPipe resources
     */
    public void stopCamera() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            cameraProvider = null;
        }

        if (handLandmarker != null) {
            handLandmarker.close();
            handLandmarker = null;
        }

        if (gestureRecognizer != null) {
            gestureRecognizer.close();
            gestureRecognizer = null;
        }

        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
            cameraExecutor = null;
        }

        Log.d(TAG, "Camera and MediaPipe resources released");
    }

    /**
     * Process camera image with MediaPipe
     */
    private void processImage(@NonNull ImageProxy imageProxy) {
        try {
            // Convert ImageProxy to Bitmap for MediaPipe
            Bitmap bitmap = imageProxyToBitmap(imageProxy);
            if (bitmap == null) return;

            // Convert to MediaPipe image
            MPImage mpImage = new BitmapImageBuilder(bitmap).build();

            // Send to hand landmarker
            if (handLandmarker != null) {
                // Check if we're using live stream mode or image mode
                if (runningMode == RunningMode.LIVE_STREAM) {
                    // Live stream mode: async detection with callback
                    long timestamp = System.currentTimeMillis();
                    handLandmarker.detectAsync(mpImage, timestamp);
                } else {
                    // Image mode: synchronous detection
                    Log.d(TAG, "Processing image with MediaPipe (IMAGE mode)");
                    HandLandmarkerResult result = handLandmarker.detect(mpImage);
                    processHandLandmarks(result, mpImage);
                }
            } else {
                Log.w(TAG, "HandLandmarker is null, cannot process image");
            }

        } catch (Exception e) {
            Log.e(TAG, "Error processing image", e);
        } finally {
            imageProxy.close();
        }
    }

    /**
     * Convert ImageProxy to Bitmap
     */
    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        Image image = imageProxy.getImage();
        if (image == null) return null;

        // Convert YUV to RGB
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);

        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21,
                image.getWidth(), image.getHeight(), null);

        // Convert to Bitmap
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 100, out);
        byte[] imageBytes = out.toByteArray();
        return android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
    }

    /**
     * Process hand landmarks to detect fist gesture
     */
    private void processHandLandmarks(HandLandmarkerResult result, MPImage input) {
        // Check if any hands were detected
        if (result.landmarks().isEmpty()) {
            return; // No hands detected
        }

        // Check if enough time has passed since last gesture
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastGestureTime < MIN_GESTURE_INTERVAL_MS) {
            return;
        }

        // Get landmarks for the first detected hand
        java.util.List<NormalizedLandmark> handLandmarks = result.landmarks().get(0);

        // Gesture detection: check if fingers are curled (tips closer to palm than PIP joints)
        boolean isFist = detectGesture(handLandmarks);

        if (isFist) {
            Log.d(TAG, "Pinky and thumb extended gesture detected!");
            lastGestureTime = currentTime;

            if (callback != null) {
                callback.onGestureDetected();
            }
        }
    }

    /**
     * Detect if hand is making a gesture where pinky and thumb are extended, other fingers curled
     */
    private boolean detectGesture(java.util.List<NormalizedLandmark> landmarks) {
        if (landmarks.size() < 21) return false; // Not enough landmarks

        // MediaPipe hand landmark indices:
        // 0: wrist, 1-4: thumb, 5-8: index, 9-12: middle, 13-16: ring, 17-20: pinky

        // Check distance from fingertip to wrist vs PIP joint to wrist
        float wristY = landmarks.get(0).y();
        float wristX = landmarks.get(0).x();

        // Thumb: tip(4) vs MCP(2) - thumb has different anatomy
        double thumbDistToWrist = Math.sqrt(Math.pow(landmarks.get(4).x() - wristX, 2) + Math.pow(landmarks.get(4).y() - wristY, 2));
        double thumbMcpDistToWrist = Math.sqrt(Math.pow(landmarks.get(2).x() - wristX, 2) + Math.pow(landmarks.get(2).y() - wristY, 2));
        boolean thumbCurled = thumbDistToWrist < thumbMcpDistToWrist;

        // Index: tip(8) vs PIP(6)
        double indexDistToWrist = Math.sqrt(Math.pow(landmarks.get(8).x() - wristX, 2) + Math.pow(landmarks.get(8).y() - wristY, 2));
        double indexPipDistToWrist = Math.sqrt(Math.pow(landmarks.get(6).x() - wristX, 2) + Math.pow(landmarks.get(6).y() - wristY, 2));
        boolean indexCurled = indexDistToWrist < indexPipDistToWrist;

        // Middle: tip(12) vs PIP(10)
        double middleDistToWrist = Math.sqrt(Math.pow(landmarks.get(12).x() - wristX, 2) + Math.pow(landmarks.get(12).y() - wristY, 2));
        double middlePipDistToWrist = Math.sqrt(Math.pow(landmarks.get(10).x() - wristX, 2) + Math.pow(landmarks.get(10).y() - wristY, 2));
        boolean middleCurled = middleDistToWrist < middlePipDistToWrist;

        // Ring: tip(16) vs PIP(14)
        double ringDistToWrist = Math.sqrt(Math.pow(landmarks.get(16).x() - wristX, 2) + Math.pow(landmarks.get(16).y() - wristY, 2));
        double ringPipDistToWrist = Math.sqrt(Math.pow(landmarks.get(14).x() - wristX, 2) + Math.pow(landmarks.get(14).y() - wristY, 2));
        boolean ringCurled = ringDistToWrist < ringPipDistToWrist;

        // Pinky: tip(20) vs PIP(18)
        double pinkyDistToWrist = Math.sqrt(Math.pow(landmarks.get(20).x() - wristX, 2) + Math.pow(landmarks.get(20).y() - wristY, 2));
        double pinkyPipDistToWrist = Math.sqrt(Math.pow(landmarks.get(18).x() - wristX, 2) + Math.pow(landmarks.get(18).y() - wristY, 2));
        boolean pinkyCurled = pinkyDistToWrist < pinkyPipDistToWrist;

        // Gesture: pinky and thumb extended (not curled), index/middle/ring curled
        return !thumbCurled && indexCurled && middleCurled && ringCurled && !pinkyCurled;
    }


    /**
     * Check if the detector is currently active
     * @return true if MediaPipe gesture detection is running, false otherwise
     */
    public boolean isActive() {
        return handLandmarker != null && cameraProvider != null;
    }


    /**
     * Get status information for debugging
     * @return Status string
     */
    public String getStatus() {
        return "Active: " + isActive() +
               ", HandLandmarker: " + (handLandmarker != null) +
               ", CameraProvider: " + (cameraProvider != null);
    }

}
