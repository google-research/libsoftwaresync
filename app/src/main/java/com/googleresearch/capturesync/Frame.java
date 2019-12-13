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

import android.hardware.camera2.CaptureResult;
import android.media.Image;
import com.googleresearch.capturesync.ImageMetadataSynchronizer.Output;
import java.io.Closeable;

/** A holder for a CaptureResult with multiple outputs. */
public class Frame implements Closeable {
  public final CaptureResult result;
  public final Output output;
  private boolean closed = false;

  public Frame(CaptureResult result, Output output) {
    this.result = result;
    this.output = output;
  }

  @Override
  public void close() {
    if (closed) {
      throw new IllegalStateException("This Frame is already closed");
    }
    for (Image image : output.images) {
      image.close();
    }
    output.close();
    closed = true;
  }
}
