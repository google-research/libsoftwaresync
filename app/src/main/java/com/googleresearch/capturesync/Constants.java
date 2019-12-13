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

import android.hardware.camera2.CameraCharacteristics;
import com.googleresearch.capturesync.softwaresync.TimeUtils;

/** Constants including what type of images to save and the save directory. */
public final class Constants {
  public static final int DEFAULT_CAMERA_FACING = CameraCharacteristics.LENS_FACING_BACK;

  /**
   * Delay from capture button press to capture, giving network time to send messages to clients.
   */
  public static final long FUTURE_TRIGGER_DELAY_NS = TimeUtils.millisToNanos(500);

  /* Set at least one of {SAVE_YUV, SAVE_RAW} to true to save any data. */
  public static final boolean SAVE_YUV = true;

  // TODO: Implement saving ImageFormat.RAW10 to DNG.
  // DngCreator works with ImageFormat.RAW_SENSOR but it is slow and power-hungry.
  public static final boolean SAVE_RAW = false;

  // TODO(samansari): Turn SAVE_JPG_FROM_YUV into a checkbox instead.
  /* Set true to save a JPG to the gallery for preview. This is slow but gives you a "postview". */
  public static final boolean SAVE_JPG_FROM_YUV = true;
  public static final int JPG_QUALITY = 95;

  public static final boolean USE_FULL_SCREEN_IMMERSIVE = false;

  private Constants() {}
}
