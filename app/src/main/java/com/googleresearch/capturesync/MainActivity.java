/**
 * Copyright 2019 The Google Research Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.googleresearch.capturesync;

import android.Manifest.permission;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraDevice.StateCallback;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;
import android.widget.Toast;
import com.googleresearch.capturesync.softwaresync.SoftwareSyncLeader;
import com.googleresearch.capturesync.softwaresync.TimeUtils;
import com.googleresearch.capturesync.softwaresync.phasealign.PhaseConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

/** Main activity for the libsoftwaresync demo app using the camera 2 API. */
public class MainActivity extends Activity {
  private static final String TAG = "MainActivity";

  private boolean permissionsGranted = false;

  // Phase config file to use for phase alignment, configs are located in the raw folder.
  private final int phaseConfigFile = R.raw.pixel3_phaseconfig;

  // Camera controls.
  private HandlerThread cameraThread;
  private Handler cameraHandler;
  private Handler send2aHandler;
  private CameraManager cameraManager;

  private String cameraId;
  private CameraDevice cameraDevice;
  private CameraCharacteristics cameraCharacteristics;

  // Cached camera characteristics.
  private Size viewfinderResolution;
  private Size rawImageResolution;
  private Size yuvImageResolution;

  // Top level UI windows.
  private int lastOrientation = Configuration.ORIENTATION_UNDEFINED;

  // UI controls.
  private Button captureStillButton;
  private Button phaseAlignButton;
  private SeekBar exposureSeekBar;
  private SeekBar sensitivitySeekBar;
  private TextView statusTextView;
  private TextView sensorExposureTextView;
  private TextView sensorSensitivityTextView;
  private TextView softwaresyncStatusTextView;
  private TextView phaseTextView;

  // Local variables tracking current manual exposure and sensitivity values.
  private long currentSensorExposureTimeNs = seekBarValueToExposureNs(10);
  private int currentSensorSensitivity = seekBarValueToSensitivity(3);

  // High level camera controls.
  private CameraController cameraController;
  private CameraCaptureSession captureSession;

  /**
   * Manages SoftwareSync setup/teardown. Since softwaresync should only run when the camera is
   * running, it is instantiated in openCamera() and closed inside closeCamera().
   */
  private SoftwareSyncController softwareSyncController;

  private AutoFitSurfaceView surfaceView;

  private final SurfaceHolder.Callback surfaceCallback =
      new SurfaceHolder.Callback() {

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
          Log.i(TAG, "Surface created.");
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
          Log.i(TAG, "Surface changed.");
          viewfinderSurface = holder.getSurface();
          openCamera();
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
          Log.i(TAG, "destroyed.");
        }
      };
  private Surface viewfinderSurface;
  private PhaseAlignController phaseAlignController;
  private int numCaptures;
  private Toast latestToast;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    Log.v(TAG, "onCreate");

    checkPermissions();
    if (permissionsGranted) {
      onCreateWithPermission();
    } else {
      // Wait for user to finish permissions before setting up the app.
    }
  }

  private void onCreateWithPermission() {
    setContentView(R.layout.activity_main);
    send2aHandler = new Handler();
    createUi();

    setupPhaseAlignController();

    // Query for camera characteristics and cache them.
    try {
      cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
      cacheCameraCharacteristics();
    } catch (CameraAccessException e) {
      Toast.makeText(this, R.string.error_msg_cant_open_camera2, Toast.LENGTH_LONG).show();
      Log.e(TAG, String.valueOf(R.string.error_msg_cant_open_camera2));
      finish();
    }

    // Set the aspect ratio now that we know the viewfinder resolution.
    surfaceView.setAspectRatio(viewfinderResolution.getWidth(), viewfinderResolution.getHeight());

    // Process the initial configuration (for i.e. initial orientation)
    // We need this because #onConfigurationChanged doesn't get called when
    // the app launches
    maybeUpdateConfiguration(getResources().getConfiguration());
  }

  private void setupPhaseAlignController() {
    // Set up phase aligner.
    PhaseConfig phaseConfig;
    try {
      phaseConfig = loadPhaseConfigFile();
    } catch (JSONException e) {
      throw new IllegalArgumentException("Error reading JSON file: ", e);
    }
    phaseAlignController = new PhaseAlignController(phaseConfig, this);
  }

  /**
   * Called when "configuration" changes, as defined in the manifest. In our case, when the
   * orientation changes, screen size changes, or keyboard is hidden.
   */
  @Override
  public void onConfigurationChanged(Configuration newConfig) {
    super.onConfigurationChanged(newConfig);
    maybeUpdateConfiguration(getResources().getConfiguration());
  }

  private void maybeUpdateConfiguration(Configuration newConfig) {
    if (lastOrientation != newConfig.orientation) {
      lastOrientation = newConfig.orientation;
      updateViewfinderLayoutParams();
    }
  }

  /** Resize the SurfaceView to be centered on screen. */
  private void updateViewfinderLayoutParams() {
    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) surfaceView.getLayoutParams();

    // displaySize is set by the OS: it's how big the display is.
    Point displaySize = new Point();
    getWindowManager().getDefaultDisplay().getRealSize(displaySize);
    Log.i(TAG, String.format("display resized, now %d x %d", displaySize.x, displaySize.y));

    // Fit an image inside a rectangle maximizing the resulting area and centering (coordinates are
    // rounded down).
    params.width =
        Math.min(
            displaySize.x,
            displaySize.y * viewfinderResolution.getWidth() / viewfinderResolution.getHeight());
    params.height =
        Math.min(
            displaySize.x * viewfinderResolution.getHeight() / viewfinderResolution.getWidth(),
            displaySize.y);
    params.gravity = Gravity.CENTER;

    surfaceView.setLayoutParams(params);
  }

  @Override
  public void onResume() {
    Log.d(TAG, "onResume");
    super.onResume(); // Required.

    surfaceView
        .getHolder()
        .setFixedSize(viewfinderResolution.getWidth(), viewfinderResolution.getHeight());
    surfaceView.getHolder().addCallback(surfaceCallback);
    Log.d(TAG, "Surfaceview size: " + surfaceView.getWidth() + ", " + surfaceView.getHeight());
    surfaceView.setVisibility(View.VISIBLE);

    startCameraThread();
  }

  @Override
  public void onPause() {
    Log.d(TAG, "onPause");
    closeCamera();
    stopCameraThread();
    // Make the SurfaceView GONE so that on resume, surfaceCreated() is called,
    // and on pause, surfaceDestroyed() is called.
    surfaceView.getHolder().removeCallback(surfaceCallback);
    surfaceView.setVisibility(View.GONE);

    super.onPause(); // required
  }

  private void startCameraThread() {
    cameraThread = new HandlerThread("CameraThread");
    cameraThread.start();
    cameraHandler = new Handler(cameraThread.getLooper());
  }

  private void stopCameraThread() {
    cameraThread.quitSafely();
    try {
      cameraThread.join();
      cameraThread = null;
      cameraHandler = null;
    } catch (InterruptedException e) {
      Log.e(TAG, "Failed to stop camera thread", e);
    }
  }

  @SuppressLint("MissingPermission")
  private void openCamera() {
    try {
      Log.d(TAG, "resumeCamera");

      StateCallback cameraStateCallback =
          new StateCallback() {
            @Override
            public void onOpened(CameraDevice openedCameraDevice) {
              cameraDevice = openedCameraDevice;
              startSoftwareSync();
              initCameraController();
              configureCaptureSession(); // calls startPreview();
            }

            @Override
            public void onDisconnected(CameraDevice cameraDevice) {}

            @Override
            public void onError(CameraDevice cameraDevice, int i) {}
          };
      cameraManager.openCamera(cameraId, cameraStateCallback, cameraHandler); // Starts chain.

    } catch (CameraAccessException e) {
      Log.e(TAG, "Cannot open camera!", e);
      finish();
    }
  }

  /* Set up UI controls and listeners based on if device is currently a leader of client. */
  private void setLeaderClientControls(boolean isLeader) {
    if (isLeader) {
      // Leader, all controls visible and set.
      captureStillButton.setVisibility(View.VISIBLE);
      phaseAlignButton.setVisibility(View.VISIBLE);
      exposureSeekBar.setVisibility(View.VISIBLE);
      sensitivitySeekBar.setVisibility(View.VISIBLE);

      captureStillButton.setOnClickListener(
          view -> {
            if (cameraController.getOutputSurfaces().isEmpty()) {
              Log.e(TAG, "No output surfaces found.");
              Toast.makeText(this, R.string.error_msg_no_outputs, Toast.LENGTH_LONG).show();
              return;
            }

            long currentTimestamp = softwareSyncController.softwareSync.getLeaderTimeNs();
            // Trigger request some time in the future (~500ms for example) so all devices have time
            // to receive the request (delayed due to network latency) and prepare for triggering.
            // Note: If the user keeps a running circular buffer of images, they can select frames
            // in the near past as well, allowing for 'instantaneous' captures on all devices.
            long futureTimestamp = currentTimestamp + Constants.FUTURE_TRIGGER_DELAY_NS;

            Log.d(
                TAG,
                String.format(
                    "Trigger button, sending timestamp %,d at %,d",
                    futureTimestamp, currentTimestamp));

            // Broadcast desired synchronized capture time to all devices.
            ((SoftwareSyncLeader) softwareSyncController.softwareSync)
                .broadcastRpc(
                    SoftwareSyncController.METHOD_SET_TRIGGER_TIME,
                    String.valueOf(futureTimestamp));
          });

      phaseAlignButton.setOnClickListener(
          view -> {
            Log.d(TAG, "Broadcasting phase alignment request.");
            // Request phase alignment on all devices.
            ((SoftwareSyncLeader) softwareSyncController.softwareSync)
                .broadcastRpc(SoftwareSyncController.METHOD_DO_PHASE_ALIGN, "");
          });

      exposureSeekBar.setOnSeekBarChangeListener(
          new OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
              currentSensorExposureTimeNs = seekBarValueToExposureNs(value);
              sensorExposureTextView.setText(
                  "Exposure: " + prettyExposureValue(currentSensorExposureTimeNs));
              Log.i(
                  TAG,
                  "Exposure Seekbar "
                      + value
                      + " to set exposure "
                      + currentSensorExposureTimeNs
                      + " : "
                      + prettyExposureValue(currentSensorExposureTimeNs));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
              // Do it immediately on leader for immediate feedback, but doesn't update clients
              // without
              // clicking the 2A button.
              startPreview();
              scheduleBroadcast2a();
            }
          });

      sensitivitySeekBar.setOnSeekBarChangeListener(
          new OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
              currentSensorSensitivity = seekBarValueToSensitivity(value);
              sensorSensitivityTextView.setText("Sensitivity: " + currentSensorSensitivity);
              Log.i(
                  TAG,
                  "Sensitivity Seekbar "
                      + value
                      + " to set sensitivity "
                      + currentSensorSensitivity);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
              // Do it immediately on leader for immediate feedback, but doesn't update clients
              // without
              // clicking the 2A button.
              startPreview();
              scheduleBroadcast2a();
            }
          });
    } else {
      // Client. All controls invisible.
      captureStillButton.setVisibility(View.INVISIBLE);
      phaseAlignButton.setVisibility(View.INVISIBLE);
      exposureSeekBar.setVisibility(View.INVISIBLE);
      sensitivitySeekBar.setVisibility(View.INVISIBLE);

      captureStillButton.setOnClickListener(null);
      phaseAlignButton.setOnClickListener(null);
      exposureSeekBar.setOnSeekBarChangeListener(null);
      sensitivitySeekBar.setOnSeekBarChangeListener(null);
    }
  }

  private void startSoftwareSync() {
    // Start softwaresync, close it first if it's already running.
    if (softwareSyncController != null) {
      softwareSyncController.close();
      softwareSyncController = null;
    }
    try {
      softwareSyncController =
          new SoftwareSyncController(this, phaseAlignController, softwaresyncStatusTextView);
      setLeaderClientControls(softwareSyncController.isLeader());
    } catch (IllegalStateException e) {
      // If wifi is disabled, start pick wifi activity.
      Log.e(
          TAG,
          "Couldn't start SoftwareSync due to " + e + ", requesting user pick a wifi network.");
      finish(); // Close current app, expect user to restart.
      startActivity(new Intent(WifiManager.ACTION_PICK_WIFI_NETWORK));
    }
  }

  private PhaseConfig loadPhaseConfigFile() throws JSONException {
    // Load phase config file and pass to phase aligner.

    JSONObject json;
    try {
      InputStream inputStream = getResources().openRawResource(phaseConfigFile);
      byte[] buffer = new byte[inputStream.available()];
      //noinspection ResultOfMethodCallIgnored
      inputStream.read(buffer);
      inputStream.close();
      json = new JSONObject(new String(buffer, StandardCharsets.UTF_8));
    } catch (JSONException | IOException e) {
      throw new IllegalArgumentException("Error reading JSON file: ", e);
    }
    return PhaseConfig.parseFromJSON(json);
  }

  private void closeCamera() {
    stopPreview();
    captureSession = null;

    if (cameraController != null) {
      cameraController.close();
      cameraController = null;
    }

    if (cameraDevice != null) {
      Log.d(TAG, "Closing camera...");
      cameraDevice.close();
      Log.d(TAG, "Camera closed.");
    }

    // Close softwaresync whenever camera is stopped.
    if (softwareSyncController != null) {
      softwareSyncController.close();
      softwareSyncController = null;
    }
  }

  /**
   * Gathers useful camera characteristics like available resolutions and cache them so we don't
   * have to query the CameraCharacteristics struct again.
   */
  private void cacheCameraCharacteristics() throws CameraAccessException {
    cameraId = null;
    for (String id : cameraManager.getCameraIdList()) {
      if (cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
          == Constants.DEFAULT_CAMERA_FACING) {
        cameraId = id;
        break;
      }
    }
    cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);

    StreamConfigurationMap scm =
        cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

    // We always capture the viewfinder. Its resolution is special: it's set chosen in Constants.
    Size[] viewfinderOutputSizes = scm.getOutputSizes(SurfaceTexture.class);
    if (viewfinderOutputSizes != null) {
      Log.i(TAG, "Available viewfinder resolutions:");
      for (Size s : viewfinderOutputSizes) {
        Log.i(TAG, s.toString());
      }
    } else {
      throw new IllegalStateException("Viewfinder unavailable!");
    }
    viewfinderResolution =
        Collections.max(Arrays.asList(viewfinderOutputSizes), new CompareSizesByArea());

    Size[] rawOutputSizes = scm.getOutputSizes(ImageFormat.RAW10);
    if (rawOutputSizes != null) {
      Log.i(TAG, "Available Bayer RAW resolutions:");
      for (Size s : rawOutputSizes) {
        Log.i(TAG, s.toString());
      }
    } else {
      Log.i(TAG, "Bayer RAW unavailable!");
    }
    rawImageResolution = Collections.max(Arrays.asList(rawOutputSizes), new CompareSizesByArea());

    Size[] yuvOutputSizes = scm.getOutputSizes(ImageFormat.YUV_420_888);
    if (yuvOutputSizes != null) {
      Log.i(TAG, "Available YUV resolutions:");
      for (Size s : yuvOutputSizes) {
        Log.i(TAG, s.toString());
      }
    } else {
      Log.i(TAG, "YUV unavailable!");
    }
    yuvImageResolution = Collections.max(Arrays.asList(yuvOutputSizes), new CompareSizesByArea());
    Log.i(TAG, "Chosen viewfinder resolution: " + viewfinderResolution);
    Log.i(TAG, "Chosen raw resolution: " + rawImageResolution);
    Log.i(TAG, "Chosen yuv resolution: " + yuvImageResolution);
  }

  public void setUpcomingCaptureStill(long upcomingTriggerTimeNs) {
    cameraController.setUpcomingCaptureStill(upcomingTriggerTimeNs);
    double timeTillSec =
        TimeUtils.nanosToSeconds(
            (double)
                (upcomingTriggerTimeNs - softwareSyncController.softwareSync.getLeaderTimeNs()));
    runOnUiThread(
        () -> {
          if (latestToast != null) {
            latestToast.cancel();
          }
          latestToast =
              Toast.makeText(
                  this,
                  String.format("Capturing in %.2f seconds", timeTillSec),
                  Toast.LENGTH_SHORT);
          latestToast.show();
        });
  }

  public void notifyCapturing(String name) {
    runOnUiThread(
        () -> {
          if (latestToast != null) {
            latestToast.cancel();
          }
          latestToast = Toast.makeText(this, "Capturing " + name + "...", Toast.LENGTH_SHORT);
          latestToast.show();
        });
  }

  public void notifyCaptured(String name) {
    numCaptures++;
    runOnUiThread(
        () -> {
          if (latestToast != null) {
            latestToast.cancel();
          }
          latestToast = Toast.makeText(this, "Captured " + name, Toast.LENGTH_LONG);
          latestToast.show();
          statusTextView.setText(String.format("%d captures", numCaptures));
        });
  }

  /** Compares two {@code Size}s based on their areas. */
  static class CompareSizesByArea implements Comparator<Size> {

    @Override
    public int compare(Size lhs, Size rhs) {
      // We cast here to ensure the multiplications won't overflow
      return Long.signum(
          (long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
    }
  }

  public void injectFrame(long desiredExposureTimeNs) {
    try {
      CaptureRequest.Builder builder =
          cameraController
              .getRequestFactory()
              .makeFrameInjectionRequest(
                  desiredExposureTimeNs, cameraController.getOutputSurfaces());
      captureSession.capture(
          builder.build(), cameraController.getSynchronizerCaptureCallback(), cameraHandler);
    } catch (CameraAccessException e) {
      throw new IllegalStateException("Camera capture failure during frame injection.", e);
    }
  }

  private void createUi() {
    Window appWindow = getWindow();
    appWindow.setFlags(
        WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
    // Disable sleep / screen off.
    appWindow.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    // Create the SurfaceView.
    surfaceView = findViewById(R.id.viewfinder_surface_view);

    // TextViews.
    statusTextView = findViewById(R.id.status_text);
    softwaresyncStatusTextView = findViewById(R.id.softwaresync_text);
    sensorExposureTextView = findViewById(R.id.sensor_exposure);
    sensorSensitivityTextView = findViewById(R.id.sensor_sensitivity);
    phaseTextView = findViewById(R.id.phase);

    // Controls.
    captureStillButton = findViewById(R.id.capture_still_button);
    phaseAlignButton = findViewById(R.id.phase_align_button);
    exposureSeekBar = findViewById(R.id.exposure_seekbar);
    sensitivitySeekBar = findViewById(R.id.sensitivity_seekbar);
    sensorExposureTextView.setText("Exposure: " + prettyExposureValue(currentSensorExposureTimeNs));
    sensorSensitivityTextView.setText("Sensitivity: " + currentSensorSensitivity);
  }

  private void scheduleBroadcast2a() {
    send2aHandler.removeCallbacks(null); // Replace delayed callback with latest 2a values.
    send2aHandler.postDelayed(
        () -> {
          Log.d(TAG, "Broadcasting current 2A values.");
          String payload =
              String.format("%d,%d", currentSensorExposureTimeNs, currentSensorSensitivity);
          // Send 2A values to all devices
          ((SoftwareSyncLeader) softwareSyncController.softwareSync)
              .broadcastRpc(SoftwareSyncController.METHOD_SET_2A, payload);
        },
        500);
  }

  void set2aAndUpdatePreview(long sensorExposureTimeNs, int sensorSensitivity) {
    currentSensorExposureTimeNs = sensorExposureTimeNs;
    currentSensorSensitivity = sensorSensitivity;
    sensorExposureTextView.setText("Exposure: " + prettyExposureValue(currentSensorExposureTimeNs));
    sensorSensitivityTextView.setText("Sensitivity: " + currentSensorSensitivity);
    Log.i(
        TAG,
        String.format(
            " Updating 2A to Exposure %d (%s), Sensitivity %d",
            currentSensorExposureTimeNs,
            prettyExposureValue(currentSensorExposureTimeNs),
            currentSensorSensitivity));
    startPreview();
  }

  void updatePhaseTextView(long phaseErrorNs) {
    phaseTextView.setText(
        String.format("Phase Error: %.2f ms", TimeUtils.nanosToMillis((double) phaseErrorNs)));
  }

  private long seekBarValueToExposureNs(int value) {
    // Convert 0-10 values ranging from 1/32 to 1/16,000 of a second.
    int[] steps = {32, 60, 125, 250, 500, 1000, 2000, 4000, 8000, 12000, 16000};
    int denominator = steps[10 - value];
    double exposureSec = 1. / denominator;
    return (long) (exposureSec * 1_000_000_000);
  }

  private String prettyExposureValue(long exposureNs) {
    return String.format("1/%.0f", 1. / TimeUtils.nanosToSeconds((double) exposureNs));
  }

  private int seekBarValueToSensitivity(int value) {
    // Convert 0-10 values to 0-800 sensor sensitivities.
    return (value * 800) / 10;
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (hasFocus) {
      if (Constants.USE_FULL_SCREEN_IMMERSIVE) {
        findViewById(android.R.id.content)
            .setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
      }
    }
  }

  /** Create {@link #cameraController}, and subscribe to status change events. */
  private void initCameraController() {
    cameraController =
        new CameraController(
            cameraCharacteristics,
            Constants.SAVE_RAW ? rawImageResolution : null,
            Constants.SAVE_YUV ? yuvImageResolution : null,
            phaseAlignController,
            this,
            softwareSyncController.softwareSync);
  }

  private void configureCaptureSession() {
    Log.d(TAG, "Creating capture session.");

    List<Surface> outputSurfaces = new ArrayList<>();
    Log.d(TAG, "Surfaceview size: " + surfaceView.getWidth() + ", " + surfaceView.getHeight());
    Log.d(TAG, "viewfinderSurface valid? " + viewfinderSurface.isValid());
    outputSurfaces.add(viewfinderSurface);
    outputSurfaces.addAll(cameraController.getOutputSurfaces());
    if (cameraController.getOutputSurfaces().isEmpty()) {
      Log.e(TAG, "No output surfaces found.");
    }

    Log.d(TAG, "Outputs " + cameraController.getOutputSurfaces());

    try {
      CameraCaptureSession.StateCallback sessionCallback =
          new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(CameraCaptureSession cameraCaptureSession) {
              Log.d(TAG, "camera capture configured.");
              captureSession = cameraCaptureSession;
              cameraController.configure(cameraDevice); // pass in device.
              startPreview();
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
              Log.d(TAG, "camera capture configure failed.");
            }
          };
      cameraDevice.createCaptureSession(outputSurfaces, sessionCallback, cameraHandler);
    } catch (CameraAccessException e) {
      Log.e(TAG, "Unable to reconfigure capture request", e);
    }
  }

  private void startPreview() {
    Log.d(TAG, "Starting preview.");

    try {
      CaptureRequest.Builder previewRequestBuilder =
          cameraController
              .getRequestFactory()
              .makePreview(
                  viewfinderSurface,
                  cameraController.getOutputSurfaces(),
                  currentSensorExposureTimeNs,
                  currentSensorSensitivity);

      captureSession.stopRepeating();
      captureSession.setRepeatingRequest(
          previewRequestBuilder.build(),
          cameraController.getSynchronizerCaptureCallback(),
          cameraHandler);

    } catch (CameraAccessException e) {
      Log.w(TAG, "Unable to create preview.");
    }
  }

  private void stopPreview() {
    Log.d(TAG, "Stopping preview.");
    if (captureSession == null) {
      return;
    }
    try {
      captureSession.stopRepeating();
      Log.d(TAG, "Done: session is now ready.");
    } catch (CameraAccessException e) {
      Log.d(TAG, "Could not stop repeating.");
    }
  }

  private void checkPermissions() {
    List<String> requests = new ArrayList<>(3);

    if (checkSelfPermission(permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      requests.add(permission.CAMERA);
    }
    if (checkSelfPermission(permission.READ_EXTERNAL_STORAGE)
        != PackageManager.PERMISSION_GRANTED) {
      requests.add(permission.READ_EXTERNAL_STORAGE);
    }
    if (checkSelfPermission(permission.WRITE_EXTERNAL_STORAGE)
        != PackageManager.PERMISSION_GRANTED) {
      requests.add(permission.WRITE_EXTERNAL_STORAGE);
    }
    if (checkSelfPermission(permission.INTERNET) != PackageManager.PERMISSION_GRANTED) {
      requests.add(permission.INTERNET);
    }
    if (checkSelfPermission(permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
      requests.add(permission.ACCESS_WIFI_STATE);
    }

    if (requests.size() > 0) {
      String[] requestsArray = new String[requests.size()];
      requestsArray = requests.toArray(requestsArray);
      requestPermissions(requestsArray, /*requestCode=*/ 0);
    } else {
      permissionsGranted = true;
    }
  }

  /** Wait for permissions to continue onCreate. */
  @Override
  public void onRequestPermissionsResult(
      int requestCode, String[] permissions, int[] grantResults) {
    if (grantResults.length < 3) {
      Log.e(TAG, "Wrong number of permissions returned: " + grantResults.length);
      Toast.makeText(this, R.string.error_msg_no_permission, Toast.LENGTH_LONG).show();
      finish();
    }
    for (int grantResult : grantResults) {
      if (grantResult != PackageManager.PERMISSION_GRANTED) {
        permissionsGranted = false;
        Log.e(TAG, "Permission not granted");
        Toast.makeText(this, R.string.error_msg_no_permission, Toast.LENGTH_LONG).show();
        return;
      }
    }

    // All permissions granted. Continue startup.
    onCreateWithPermission();
  }
}
