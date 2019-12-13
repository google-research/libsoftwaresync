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

import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCaptureSession.CaptureCallback;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.util.Log;
import android.util.Pair;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * ImageMetadataSynchronizer synchronizes {@link Image} instances (from an {@link ImageReader}) with
 * their metadata ({@link TotalCaptureResult} instances from {@code CaptureListener}) based on their
 * timestamps. In practice, metadata usually (but not always!) comes back before their corresponding
 * Image instances.
 *
 * <p>Standard usage is: Create ImageReader instances (YUV, RAW, etc). Do *not* set listeners on any
 * of them:
 *
 * <pre>{@code
 * ImageReader yuvReader = ImageReader.newInstance(...);
 * ImageReader rawReader = ImageReader.newInstance(...);
 * ArrayList<ImageReader> readers = new List(yuvReader, rawReader, ...);
 * }</pre>
 *
 * <p>Create three handlers:
 *
 * <pre>{@code
 * Handler imageHander = // Handler where images are delivered.
 * Handler captureCallbackHandler = // Handler where TotalCaptureResult instances are delivered.
 * Handler consumerHandler = // Handler where synchronized images + metadata are delivered.
 * }</pre>
 *
 * <p>Create the synchronizer:
 *
 * <pre>{@code
 * sync = new ImageMetadataSynchronizer(readers, imageHandler);
 * }</pre>
 *
 * <p>The consumer subclasses new ImageMetadataSynchronizer.Callback and registers it.
 *
 * <pre>{@code
 * consumer = new ImageMetadataSynchronizer.Callback(...);
 * sync.registerCallback(consumer, consumerHandler);
 * }</pre>
 *
 * <p>CaptureRequest instances must be tagged with an ImageMetadataSynchronizer.CaptureRequestTag.
 * CaptureRequestTag specifies, as an integer list of indices from 'readers', which ImageReaders
 * that CaptureRequest will write its output to. If you need your own tags, use
 * ImageMetadataSynchronizer.CaptureRequestTag's userTag field.
 *
 * <p>When submitting capture requests, the capture callback must be set to that of the
 * synchronizer.
 *
 * <pre>{@code
 * CaptureRequest.Builder b = ... // Create Builder.
 * b.setTag(new ImageMetadataSynchronizer.CaptureRequestTag(targetIndices, arbitraryUserTag).
 * session.capture(b.build(), sync.getCaptureCallback(), captureCallbackHandler);
 * }</pre>
 *
 * <p>Unregister with:
 *
 * <pre>{@code
 * sync.unregisterCallback(consumer, consumerHandler)
 * }</pre>
 *
 * <p>Ownership:
 *
 * <p>ImageMetadataSynchronizer <b>takes ownership</b> of the ImageReader instances passed into it.
 * When {@code ImageMetadataSynchronizer.close()} is called, the internal list of ImageReader
 * instances are closed, along with all outstanding images. You should <b>not</b> hold onto external
 * references to ImageReader instances - it is an error to close() them externally (double close).
 *
 * <p>To handle TotalCaptureResult instances without waiting to synchronize with potentially delayed
 * Image instances (e.g., to give user feedback), simply use an external CaptureCallback then
 * forward the calls to {@code sync.getCaptureCallback()}.
 *
 * <p>The data structure works by maintaining a queue of TotalCaptureResult instances and N queues
 * of Image instances. It enforces the invariant that at least one of the queues should be empty
 * when a piece of data arrives. When a TotalCaptureResult or any Image arrives, it is put into its
 * corresponding queue. The queues are "swept" until at least one of them is empty.
 *
 * <p>Queues are swept by considering the TotalCaptureResult as the master. Peek at the head of the
 * TotalCaptureResult queue. If it's null, there is no match. Otherwise, look in the
 * TotalCaptureResult's CaptureRequestTag to determine which queue it's expecting an image from.
 *
 * <pre>
 *   Peek at the head from each queue. If any of them are null, then they have yet to arrive:
 *     Return no match.
 *   Otherwise, look at their timestamps.
 *   If result.timestamp is *equal to* all firstItems:
 *     Then it is a *match*, dequeue the result and each image. Return the match.
 *   Else if result.timestamp is *less than* any timestamp:
 *     Then an Image was dropped, report this on all registered callbacks.
 *   Else result.timestamp is *greater than* any timestamp:
 *     Then some TotalCaptureResult instances were dropped and the Image is orphaned.
 *     Drop (close()) the Image on the corresponding queue.
 *     *Replace* the Image with the next Image on that queue and try again.
 * </pre>
 */
public class ImageMetadataSynchronizer {
  // TODO(jiawen): Change the constructor interface to a builder so that this class instantiates
  // instances of ImageReader on behalf of the caller.
  // TODO(jiawen): wrap ImageMetadataSynchronizer-owned images in an interface that's not closeable.
  private static final String TAG = "ImageMetadataSynchronizer";

  /**
   * To use {@link ImageMetadataSynchronizer}, each {@link CaptureRequest} must be tagged with an
   * instance of {@link CaptureRequestTag}, which tells the API which targets {@link Image}
   * instances will be written. User tags can still be specified as a second-level tag in {@link
   * #userTag}.
   */
  public static class CaptureRequestTag {

    final ArrayList<Integer> targets;
    final Object userTag;

    /** Construct an empty CaptureRequestTag with targets and an explicit user tag. */
    public CaptureRequestTag(List<Integer> targetIndices, Object userTag) {
      targets = new ArrayList<>(targetIndices);
      this.userTag = userTag;
    }

    /**
     * Extracts a CaptureRequestTag out of a TotalCaptureResult. Returns null if one isn't not set
     * or is not a CaptureRequestTag.
     */
    static CaptureRequestTag getCaptureRequestTag(CaptureResult result) {
      if (result == null) {
        return null;
      }
      Object tag = result.getRequest().getTag();
      if (!(tag instanceof CaptureRequestTag)) {
        return null;
      }
      return (CaptureRequestTag) tag;
    }

    /**
     * Convenience method to extract the Object userTag directly out of a TotalCaptureResult that
     * <b>should</b> (but not necessarily) have a ImageMetadataSynchronizer.CaptureRequestTag as its
     * tag. Returns null if the CaptureRequestTag is null. Otherwise, returns the userTag, which may
     * also be null.
     */
    public static Object getUserTag(CaptureResult result) {
      CaptureRequestTag crt = getCaptureRequestTag(result);
      if (crt == null) {
        return null;
      } else {
        return crt.userTag;
      }
    }
  }

  /**
   * Simple container for a synchronized collection of a TotalCaptureResult and a set of Image's. It
   * is explicitly <b>not</b> AutoCloseable. close() is provided to conveniently close() everything.
   */
  public static class Output {

    /** The TotalCaptureResult. */
    public TotalCaptureResult result;

    /**
     * A sparse list of {@link Image} instances that were synchronized. Only elements at indices
     * corresponding the ones declared in the CaptureRequest are non-null.
     *
     * <p>images has size() equal to the number of ImageReaders declared at initialization.
     *
     * <p>If any Image's were dropped by the HAL (mImagesWereDropped is true), those elements will
     * also be null. I.e., For all int i: images[droppedImageReaderIndices[i]] == null.
     */
    public final ArrayList<Image> images;

    /**
     * Indices of ImageReader's that were dropped by the HAL because they were not acquired() fast
     * enough.
     */
    ArrayList<Integer> droppedImageReaderIndices;

    private final ImageMetadataSynchronizer metadataSynchronizer;

    /** Create an empty SynchronizedOutput with no result and all Image's to null. */
    Output(int nImages, ImageMetadataSynchronizer synchronizer) {
      images = new ArrayList<>();
      for (int i = 0; i < nImages; ++i) {
        images.add(null);
      }
      droppedImageReaderIndices = new ArrayList<>();
      metadataSynchronizer = synchronizer;
    }

    /** Convenience method to {@code close()} all underyling {@code Image} instances. */
    public void close() {
      droppedImageReaderIndices = null;
      for (int i = 0; i < images.size(); ++i) {
        Image img = images.get(i);
        if (img != null) {
          img.close();
          metadataSynchronizer.notifyImageClosed(i);
        }
      }
      images.clear();
      result = null;
    }
  }

  /** Callback interface for ImageMetadataSynchronizer.SynchronizedOutput. */
  public interface Callback {

    /** Every Image in Output.images is acquired(). You need to close() it. */
    void onDataAvailable(Output output);
  }

  /** Whether this synchronizer is closed. Initially false. */
  private boolean closed;

  /** Input CaptureCallback: camera2 calls this to deliver metadata. */
  private CaptureCallback captureCallback;

  /**
   * Queue of pending TotalCaptureResult instances to be delivered once the corresponding Image
   * instances arrive.
   */
  @SuppressWarnings("JdkObsolete")
  private final LinkedList<TotalCaptureResult> pendingCaptureResultQueue = new LinkedList<>();

  /** A copy of the {@code List<ImageReader>} that was passed in. */
  private final List<ImageReader> imageReaders = new ArrayList<>();

  /**
   * ArrayList of queues. One per image reader. Each queue contains pending Image instances to be
   * delivered once everything arrives. This list corresponds 1-to-1 with imageReaders: there is one
   * queue per ImageReader.
   */
  private final List<LinkedList<Image>> pendingImageQueues = new ArrayList<>();

  /** The number of images acquired for each ImageReader. */
  private final List<Integer> imagesAcquired = new ArrayList<>();

  /**
   * If registered, then when we finally synchronize a result, Post a call to mOutputCallback on
   * mOutputHandler. If mOutputHandler is null, calls it on the current thread.
   */
  private final List<Pair<Callback, Handler>> callbacks = new ArrayList<>();

  private synchronized void notifyImageClosed(int readerIndex) {
    int nCurrentlyAcquired = imagesAcquired.get(readerIndex);
    if (nCurrentlyAcquired < 1) {
      throw new IllegalStateException(
          "Output.close() called when synchronizer thinks there are none acquired.");
    }
    imagesAcquired.set(readerIndex, nCurrentlyAcquired - 1);
  }

  /**
   * Create a ImageMetadataSynchronizer for the List of ImageReaders. 'imageReaders' should not
   * contain any duplicates. Each reader's OnImageAvailableListener is set to an internal
   * ImageReader.OnImageAvailableListener of the synchronizer and it is an error to change it
   * externally. We take ownership of each ImageReader and will close them when this
   * ImageMetadataSynchronizer is closed.
   *
   * <p>imageHandler can be set to an arbitrary non-null Handler and is shared across all
   * ImageReader instances.
   *
   * <p>Callback.onDataAvailable() is called with Image's in the same order as imageReaders.
   */
  @SuppressWarnings("JdkObsolete")
  public ImageMetadataSynchronizer(List<ImageReader> imageReaders, Handler imageHandler) {
    closed = false;

    createCaptureCallback();

    this.imageReaders.addAll(imageReaders);
    // Create a queue and a listener per ImageReader.
    int nReaders = imageReaders.size();
    for (int i = 0; i < nReaders; ++i) {
      final int readerIndex = i;
      ImageReader reader = imageReaders.get(readerIndex);
      pendingImageQueues.add(new LinkedList<>());
      imagesAcquired.add(0);

      ImageReader.OnImageAvailableListener listener =
          reader1 -> {
            synchronized (ImageMetadataSynchronizer.this) {
              if (closed) {
                return;
              }
              int nImagesAcquired = imagesAcquired.get(readerIndex);
              if (nImagesAcquired < reader1.getMaxImages()) {
                Image image = reader1.acquireNextImage();
                imagesAcquired.set(readerIndex, nImagesAcquired + 1);
                handleImageLocked(readerIndex, image);
              }
            }
          };
      reader.setOnImageAvailableListener(listener, imageHandler);
    }
  }

  /** Clear all pending queues and clear all queued Image's. */
  public synchronized void close() {
    if (closed) {
      Log.w(TAG, "Already closed!");
      return;
    }
    closed = true;

    // Close every image in every queue, then clear the queue.
    for (LinkedList<Image> q : pendingImageQueues) {
      for (Image img : q) {
        img.close();
      }
      q.clear();
    }
    // Clear the collection of queues.
    pendingImageQueues.clear();

    // Clear the TotalCaptureResult queue.
    pendingCaptureResultQueue.clear();

    for (ImageReader ir : imageReaders) {
      ir.close();
    }
  }

  /**
   * CaptureCallback used by camera2 to deliver TotalCaptureResult's. Either directly pass this
   * callback to CameraCaptureSession.capture(), or if the client receives CaptureCallbacks on a
   * separate path, forward the onCaptureProgressed(), onCaptureCompleted(), onCaptureFailed() etc,
   * here.
   */
  public CaptureCallback getCaptureCallback() {
    return captureCallback;
  }

  /**
   * Register a callback to consume synchronized data. It will be delivered on handler, or the
   * current thread if handler is null.
   *
   * <p>Duplicates are <b>not</b> checked: if the same callback is registered N times, it will be
   * called N times.
   */
  public synchronized void registerCallback(Callback callback, Handler handler) {
    // TODO(jiawen): Consider making only a single callback available, since an Output can only be
    // closed once.
    callbacks.add(Pair.create(callback, handler));
  }

  /** Initialize captureCallback with a function that just calls handleCaptureCompleted(). */
  private void createCaptureCallback() {
    captureCallback =
        new CaptureCallback() {
          @Override
          public void onCaptureCompleted(
              CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            if (closed) {
              return;
            }
            synchronized (ImageMetadataSynchronizer.this) {
              handleCaptureResultLocked(result);
            }
          }
        };
  }

  /**
   * Enforces the invariant by sweeping all internal queues when a TotalCaptureResult arrives.
   * result cannot be null.
   */
  private void handleCaptureResultLocked(TotalCaptureResult result) {
    // TODO(jiawen): Add annotations.
    CaptureRequestTag crt = CaptureRequestTag.getCaptureRequestTag(result);
    if (crt == null) {
      throw new IllegalArgumentException("CaptureResult is missing a CaptureRequestTag.");
    }

    // It has no targets, doesn't affect the queue.
    if (crt.targets.isEmpty()) {
      return;
    }

    pendingCaptureResultQueue.addLast(result);
    sweepQueuesLocked();
  }

  /**
   * Enforces the invariant by sweeping all internal queues when an Image arrives. image cannot be
   * null.
   */
  private void handleImageLocked(int readerIndex, Image image) {
    pendingImageQueues.get(readerIndex).addLast(image);
    sweepQueuesLocked();
  }

  /**
   * Sweeps over all queues. The outer (master) loop sweeps over the pending TotalCaptureResult's
   * until it runs out of matches. The inner loop sweeps over all pending Image queues corresponding
   * to the declared output indices in the TotalCaptureResult.getRequest().getTag(). If there is a
   * match, notifies all callbacks. Otherwise, exits the outer loop.
   */
  private void sweepQueuesLocked() {
    int nImageQueues = pendingImageQueues.size();

    // Outer loop: iterate over the TotalCaptureResult queue.
    while (!pendingCaptureResultQueue.isEmpty()) {
      TotalCaptureResult result = pendingCaptureResultQueue.peekFirst();

      // Create a potential SynchronizedOutput.
      Output potentialOutput = new Output(nImageQueues, this);
      potentialOutput.result = result;

      boolean matchFound = sweepImageQueues(potentialOutput);
      if (matchFound) {
        pendingCaptureResultQueue.removeFirst();
        postCallbackWithSynchronizedOutputLocked(potentialOutput);
      } else {
        return;
      }
    }
  }

  /**
   * Sweeps over all Image queues for a given potential output with its TotalCaptureResult
   * populated. The loop iterates over each of the Image queues corresponding to
   * potentialOutput.result.getRequest().getTag().targets.
   *
   * <p>If the head of each queue matches the TotalCaptureResult's timestamp, it is pulled off the
   * queue and returned as an Output. If {@code TotalCaptureResult.timestamp < an image.timestamp},
   * then an Image was dropped on that queue, return it. If {@codeTotalCaptureResult.timestamp > any
   * image.timestamp}, then a TotalCaptureResult was dropped and we drop the corresponding Image's.
   *
   * @return Whether a match was found. If a match is found, the corresponding Image's are dequeued.
   */
  private boolean sweepImageQueues(Output potentialOutput) {
    TotalCaptureResult result = potentialOutput.result;
    CaptureRequestTag crt = (CaptureRequestTag) result.getRequest().getTag();
    long resultTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);

    // Before entering the loop, populate potentialOutput's images array
    // with the heads of the corresponding ImageQueues.
    for (int readerIndex : crt.targets) {
      Image img = pendingImageQueues.get(readerIndex).peekFirst();
      potentialOutput.images.set(readerIndex, img);
    }
    potentialOutput.droppedImageReaderIndices.clear();

    // If all corresponding image queues heads are null: no match is found:
    // fall through and return null.
    while (allIndexedImagesNotNull(potentialOutput, crt.targets)) {
      // Check if resultTimestamp > any image timestamps. If so, a TotalCaptureResult was skipped.
      // Drop the corresponding Images by:
      //   close()-ing the Image, taking it off its relevant queue, and replacing it in
      //   potentialOutput with the next Image at the head of the corresponding queue.
      // This loop drops one item per queue and returns to the outer loop.
      // The next time in the outer loop, the invariant is again enforced:
      //   If any queue is empty, then items were dropped but the corresponding
      //     item in one of the image queues has yet to arrive.
      //   Else, if we get here again (resultTimestamp > imageTimestamp):
      //     then drop the image again and repeat.
      //   Else (resultTimestamp <= imageTimestamp)
      //     we'll break out of this loop and it will be handled below.
      boolean captureResultSkipped = false;
      for (int readerIndex : crt.targets) {
        if (resultTimestamp > potentialOutput.images.get(readerIndex).getTimestamp()) {
          // Close the image corresponding to the dropped TotalCaptureResult.
          Log.v(TAG, "Dropping Image due to dropped TotalCaptureResult.");
          captureResultSkipped = true;
          potentialOutput.images.get(readerIndex).close();
          // Take it off its queue.
          pendingImageQueues.get(readerIndex).removeFirst();
          // Replace it with the new head of the corresponding pending Image queue.
          potentialOutput.images.set(readerIndex, pendingImageQueues.get(readerIndex).peekFirst());
        }
      }
      // TODO(jiawen): Can potentially report this in the Callback.
      // If any images were dropped, skip the rest of the loop and go again.
      if (captureResultSkipped) {
        continue;
      }

      // At this point, all Image's are non-null, and resultTimestamp <= imageTimestamp.
      // Check if resultTimestamp < imageTimestamp, then an image was dropped.
      // Otherwise, it's a match.
      for (int readerIndex : crt.targets) {
        if (resultTimestamp < potentialOutput.images.get(readerIndex).getTimestamp()) {
          // Add the index to the dropped list.
          potentialOutput.droppedImageReaderIndices.add(readerIndex);
          // Set its corresponding mImage to null. The image is still on the queue.
          potentialOutput.images.set(readerIndex, null);
        } else {
          // We have timestamps are equal and we have a match.
          // Pull the Image off its queue.
          pendingImageQueues.get(readerIndex).removeFirst();
        }
      }
      return true;
    }
    return false;
  }

  /**
   * Returns true if for every index idx in targetIndices, potentialOutput.images.get(idx) is not
   * null.
   */
  private static boolean allIndexedImagesNotNull(
      Output potentialOutput, ArrayList<Integer> targetIndices) {
    for (int idx : targetIndices) {
      if (potentialOutput.images.get(idx) == null) {
        return false;
      }
    }
    return true;
  }

  /** Calls every registered callback with output, on their corresponding threads. */
  private void postCallbackWithSynchronizedOutputLocked(final Output output) {
    if (callbacks.isEmpty()) {
      // Nothing registered, close() it.
      output.close();
      return;
    }

    for (Pair<Callback, Handler> p : callbacks) {
      final Callback callback = p.first;
      if (callback != null) {
        Handler handler = p.second;
        if (handler != null) {
          handler.post(() -> callback.onDataAvailable(output));
        } else {
          // handler is null, call it on the current thread.
          callback.onDataAvailable(output);
        }
      }
    }
  }
}
