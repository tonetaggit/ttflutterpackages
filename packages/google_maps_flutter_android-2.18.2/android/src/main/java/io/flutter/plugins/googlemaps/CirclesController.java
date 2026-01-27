// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.googlemaps;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
import io.flutter.plugins.googlemaps.Messages.MapsCallbackApi;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import android.os.Handler;
import android.os.Looper;

class CirclesController {
  @VisibleForTesting final Map<String, CircleController> circleIdToController;
  private final Map<String, String> googleMapsCircleIdToDartCircleId;
  private final @NonNull MapsCallbackApi flutterApi;
  private final float density;
  private GoogleMap googleMap;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  CirclesController(@NonNull MapsCallbackApi flutterApi, float density) {
    this.circleIdToController = new HashMap<>();
    this.googleMapsCircleIdToDartCircleId = new HashMap<>();
    this.flutterApi = flutterApi;
    this.density = density;
  }

  void setGoogleMap(GoogleMap googleMap) {
    this.googleMap = googleMap;
  }

  void addCircles(@NonNull List<Messages.PlatformCircle> circlesToAdd) {
    // Get adaptive batch size based on device memory
    PerformanceUtils perfUtils = PerformanceUtils.getInstance();
    int batchSize = perfUtils.getCirclePolygonBatchSize();
    int threshold = batchSize / 2; // Use half batch size as threshold

    // Process in batches if list is large to avoid UI freeze
    if (circlesToAdd.size() > threshold) {
      addCirclesInBatches(circlesToAdd, 0, batchSize);
    } else {
      for (Messages.PlatformCircle circleToAdd : circlesToAdd) {
        addCircle(circleToAdd);
      }
    }
  }
  
  private void addCirclesInBatches(List<Messages.PlatformCircle> circles, int startIndex, int batchSize) {
    int endIndex = Math.min(startIndex + batchSize, circles.size());
    
    for (int i = startIndex; i < endIndex; i++) {
      addCircle(circles.get(i));
    }
    
    if (endIndex < circles.size()) {
      mainHandler.post(() -> addCirclesInBatches(circles, endIndex, batchSize));
    }
  }

  void changeCircles(@NonNull List<Messages.PlatformCircle> circlesToChange) {
    for (Messages.PlatformCircle circleToChange : circlesToChange) {
      changeCircle(circleToChange);
    }
  }

  void removeCircles(@NonNull List<String> circleIdsToRemove) {
    for (String circleId : circleIdsToRemove) {
      final CircleController circleController = circleIdToController.remove(circleId);
      if (circleController != null) {
        circleController.remove();
        googleMapsCircleIdToDartCircleId.remove(circleController.getGoogleMapsCircleId());
      }
    }
  }

  boolean onCircleTap(String googleCircleId) {
    String circleId = googleMapsCircleIdToDartCircleId.get(googleCircleId);
    if (circleId == null) {
      return false;
    }
    flutterApi.onCircleTap(circleId, new NoOpVoidResult());
    CircleController circleController = circleIdToController.get(circleId);
    if (circleController != null) {
      return circleController.consumeTapEvents();
    }
    return false;
  }

  void addCircle(@NonNull Messages.PlatformCircle circle) {
    CircleBuilder circleBuilder = new CircleBuilder(density);
    String circleId = Convert.interpretCircleOptions(circle, circleBuilder);
    CircleOptions options = circleBuilder.build();
    addCircle(circleId, options, circleBuilder.consumeTapEvents());
  }

  private void addCircle(String circleId, CircleOptions circleOptions, boolean consumeTapEvents) {
    final Circle circle = googleMap.addCircle(circleOptions);
    CircleController controller = new CircleController(circle, consumeTapEvents, density);
    circleIdToController.put(circleId, controller);
    googleMapsCircleIdToDartCircleId.put(circle.getId(), circleId);
  }

  private void changeCircle(@NonNull Messages.PlatformCircle circle) {
    String circleId = circle.getCircleId();
    CircleController circleController = circleIdToController.get(circleId);
    if (circleController != null) {
      Convert.interpretCircleOptions(circle, circleController);
    }
  }
}
