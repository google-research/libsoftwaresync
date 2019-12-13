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

import static android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW;
import static android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF;
import static android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO;
import static android.hardware.camera2.CameraMetadata.CONTROL_MODE_AUTO;
import static android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE;
import static android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE;
import static android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
import static android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE;
import static android.hardware.camera2.CaptureRequest.CONTROL_MODE;
import static android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME;
import static android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.view.Surface;
import com.googleresearch.capturesync.ImageMetadataSynchronizer.CaptureRequestTag;
import java.util.ArrayList;
import java.util.List;

/** Helper class for creating common {@link CaptureRequest.Builder} instances. */
public class CaptureRequestFactory {

  private final CameraDevice device;

  public CaptureRequestFactory(CameraDevice camera) {
    device = camera;
  }

  /**
   * Makes a {@link CaptureRequest.Builder} for the viewfinder preview. This always adds the
   * viewfinder.
   */
  public CaptureRequest.Builder makePreview(
      Surface viewfinderSurface,
      List<Surface> imageSurfaces,
      long sensorExposureTimeNs,
      int sensorSensitivity)
      throws CameraAccessException {

    CaptureRequest.Builder builder = device.createCaptureRequest(TEMPLATE_PREVIEW);
    // Manually set exposure and sensitivity using UI sliders on the leader.
    builder.set(CONTROL_AE_MODE, CONTROL_AE_MODE_OFF);
    builder.set(SENSOR_EXPOSURE_TIME, sensorExposureTimeNs);
    builder.set(SENSOR_SENSITIVITY, sensorSensitivity);

    // Auto white balance used, these could be locked and sent from the leader instead.
    builder.set(CONTROL_AWB_MODE, CONTROL_AWB_MODE_AUTO);

    // Auto focus is used since different devices may have different manual focus values.
    builder.set(CONTROL_AF_MODE, CONTROL_AF_MODE_CONTINUOUS_PICTURE);

    if (viewfinderSurface != null) {
      builder.addTarget(viewfinderSurface);
    }
    List<Integer> targetIndices = new ArrayList<>();
    for (int i = 0; i < imageSurfaces.size(); i++) {
      builder.addTarget(imageSurfaces.get(i));
      targetIndices.add(i);
    }
    builder.setTag(new CaptureRequestTag(targetIndices, null));
    return builder;
  }

  public CaptureRequest.Builder makeFrameInjectionRequest(
      long desiredExposureTimeNs, List<Surface> imageSurfaces) throws CameraAccessException {
    CaptureRequest.Builder builder = device.createCaptureRequest(TEMPLATE_PREVIEW);
    builder.set(CONTROL_MODE, CONTROL_MODE_AUTO);
    builder.set(CONTROL_AE_MODE, CONTROL_AE_MODE_OFF);
    builder.set(SENSOR_EXPOSURE_TIME, desiredExposureTimeNs);
    // TODO: Inserting frame duration directly would be more accurate than inserting exposure since
    // {@code frame duration ~ exposure + variable overhead}. However setting frame duration may not
    // be supported on many android devices, so we use exposure time here.

    List<Integer> targetIndices = new ArrayList<>();
    for (int i = 0; i < imageSurfaces.size(); i++) {
      builder.addTarget(imageSurfaces.get(i));
      targetIndices.add(i);
    }
    builder.setTag(new CaptureRequestTag(targetIndices, PhaseAlignController.INJECT_FRAME));

    return builder;
  }
}
