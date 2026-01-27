// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.googlemaps;

import android.app.ActivityManager;
import android.content.Context;
import androidx.annotation.NonNull;

/**
 * Utility class for performance optimization settings based on device capabilities.
 * Helps determine optimal batch sizes and thresholds for map operations.
 */
class PerformanceUtils {
  // Singleton instance
  private static volatile PerformanceUtils instance;

  private final int deviceMemoryClass;
  private final boolean isLowRamDevice;

  // Batch size configurations for different device tiers
  private static final int MARKER_BATCH_SIZE_HIGH_END = 500;
  private static final int MARKER_BATCH_SIZE_MID_RANGE = 50;
  private static final int MARKER_BATCH_SIZE_LOW_END = 30;

  private static final int CIRCLE_BATCH_SIZE_HIGH_END = 40;
  private static final int CIRCLE_BATCH_SIZE_MID_RANGE = 20;
  private static final int CIRCLE_BATCH_SIZE_LOW_END = 10;

  private static final int OVERLAY_BATCH_SIZE_HIGH_END = 20;
  private static final int OVERLAY_BATCH_SIZE_MID_RANGE = 10;
  private static final int OVERLAY_BATCH_SIZE_LOW_END = 5;

  private PerformanceUtils(@NonNull Context context) {
    ActivityManager activityManager =
        (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);

    if (activityManager != null) {
      this.deviceMemoryClass = activityManager.getMemoryClass();
      this.isLowRamDevice = activityManager.isLowRamDevice();
    } else {
      // Fallback to conservative defaults if ActivityManager is unavailable
      this.deviceMemoryClass = 128; // Assume low-end device
      this.isLowRamDevice = true;
    }
  }

  /**
   * Initialize the singleton instance with application context.
   * Should be called once during plugin initialization.
   *
   * @param context Application context (not Activity context to avoid leaks)
   */
  public static void initialize(@NonNull Context context) {
    if (instance == null) {
      synchronized (PerformanceUtils.class) {
        if (instance == null) {
          // Use application context to avoid memory leaks
          instance = new PerformanceUtils(context.getApplicationContext());
        }
      }
    }
  }

  /**
   * Get the singleton instance. Falls back to conservative defaults if not initialized.
   *
   * @return PerformanceUtils instance
   */
  @NonNull
  public static PerformanceUtils getInstance() {
    if (instance == null) {
      // Return a default instance with conservative settings
      // This shouldn't happen in normal usage but provides safety
      return new PerformanceUtils() {
        @Override
        public int getMarkerBatchSize() { return MARKER_BATCH_SIZE_LOW_END; }
        @Override
        public int getCirclePolygonBatchSize() { return CIRCLE_BATCH_SIZE_LOW_END; }
        @Override
        public int getOverlayBatchSize() { return OVERLAY_BATCH_SIZE_LOW_END; }
      };
    }
    return instance;
  }

  // Default constructor for fallback instance
  private PerformanceUtils() {
    this.deviceMemoryClass = 128;
    this.isLowRamDevice = true;
  }

  /**
   * Determines optimal batch size for marker operations based on device memory.
   *
   * Device tiers:
   * - High-end: >512MB RAM class, returns 100 markers/batch
   * - Mid-range: 256-512MB RAM class, returns 50 markers/batch
   * - Low-end: <256MB RAM class or isLowRamDevice, returns 30 markers/batch
   *
   * @return Optimal number of markers to process in a single batch
   */
  public int getMarkerBatchSize() {
    if (isLowRamDevice || deviceMemoryClass < 256) {
      return MARKER_BATCH_SIZE_LOW_END;
    } else if (deviceMemoryClass >= 512) {
      return MARKER_BATCH_SIZE_HIGH_END;
    } else {
      return MARKER_BATCH_SIZE_MID_RANGE;
    }
  }

  /**
   * Determines optimal batch size for circles, polygons, and polylines.
   * These are more memory-intensive than markers due to stroke/fill rendering.
   *
   * @return Optimal number of shapes to process in a single batch
   */
  public int getCirclePolygonBatchSize() {
    if (isLowRamDevice || deviceMemoryClass < 256) {
      return CIRCLE_BATCH_SIZE_LOW_END;
    } else if (deviceMemoryClass >= 512) {
      return CIRCLE_BATCH_SIZE_HIGH_END;
    } else {
      return CIRCLE_BATCH_SIZE_MID_RANGE;
    }
  }

  /**
   * Determines optimal batch size for ground overlays and tile overlays.
   * These are the most memory-intensive due to bitmap textures.
   *
   * @return Optimal number of overlays to process in a single batch
   */
  public int getOverlayBatchSize() {
    if (isLowRamDevice || deviceMemoryClass < 256) {
      return OVERLAY_BATCH_SIZE_LOW_END;
    } else if (deviceMemoryClass >= 512) {
      return OVERLAY_BATCH_SIZE_HIGH_END;
    } else {
      return OVERLAY_BATCH_SIZE_MID_RANGE;
    }
  }

  /**
   * Get threshold for when to use batching vs synchronous processing.
   * Small lists can be processed synchronously without UI impact.
   *
   * @return Threshold count - lists larger than this should use batching
   */
  public int getBatchingThreshold() {
    // Use half the batch size as threshold
    // This ensures batching only kicks in when it's actually needed
    return getMarkerBatchSize() / 2;
  }

  /**
   * Checks if device is classified as low-end for performance optimization.
   *
   * @return true if device is low-RAM or has <256MB memory class
   */
  public boolean isLowEndDevice() {
    return isLowRamDevice || deviceMemoryClass < 256;
  }

  /**
   * Get device memory class in MB.
   *
   * @return Memory class threshold in megabytes
   */
  public int getDeviceMemoryClass() {
    return deviceMemoryClass;
  }
}
