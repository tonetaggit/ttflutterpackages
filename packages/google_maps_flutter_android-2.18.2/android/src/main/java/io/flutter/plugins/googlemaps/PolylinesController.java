// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.googlemaps;

import android.content.res.AssetManager;
import androidx.annotation.NonNull;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import io.flutter.plugins.googlemaps.Messages.MapsCallbackApi;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import android.os.Handler;
import android.os.Looper;

class PolylinesController {

  private final Map<String, PolylineController> polylineIdToController;
  private final Map<String, String> googleMapsPolylineIdToDartPolylineId;
  private final @NonNull MapsCallbackApi flutterApi;
  private GoogleMap googleMap;
  private final float density;
  private final AssetManager assetManager;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  PolylinesController(
      @NonNull MapsCallbackApi flutterApi, AssetManager assetManager, float density) {
    this.assetManager = assetManager;
    this.polylineIdToController = new HashMap<>();
    this.googleMapsPolylineIdToDartPolylineId = new HashMap<>();
    this.flutterApi = flutterApi;
    this.density = density;
  }

  void setGoogleMap(GoogleMap googleMap) {
    this.googleMap = googleMap;
  }

  void addPolylines(@NonNull List<Messages.PlatformPolyline> polylinesToAdd) {
    // Get adaptive batch size based on device memory
    PerformanceUtils perfUtils = PerformanceUtils.getInstance();
    int batchSize = perfUtils.getCirclePolygonBatchSize();
    int threshold = batchSize / 2; // Use half batch size as threshold

    // Process in batches if list is large to avoid UI freeze
    if (polylinesToAdd.size() > threshold) {
      addPolylinesInBatches(polylinesToAdd, 0, batchSize);
    } else {
      for (Messages.PlatformPolyline polylineToAdd : polylinesToAdd) {
        addPolyline(polylineToAdd);
      }
    }
  }
  
  private void addPolylinesInBatches(List<Messages.PlatformPolyline> polylines, int startIndex, int batchSize) {
    int endIndex = Math.min(startIndex + batchSize, polylines.size());
    
    for (int i = startIndex; i < endIndex; i++) {
      addPolyline(polylines.get(i));
    }
    
    if (endIndex < polylines.size()) {
      mainHandler.post(() -> addPolylinesInBatches(polylines, endIndex, batchSize));
    }
  }

  void changePolylines(@NonNull List<Messages.PlatformPolyline> polylinesToChange) {
    for (Messages.PlatformPolyline polylineToChange : polylinesToChange) {
      changePolyline(polylineToChange);
    }
  }

  void removePolylines(@NonNull List<String> polylineIdsToRemove) {
    for (String polylineId : polylineIdsToRemove) {
      final PolylineController polylineController = polylineIdToController.remove(polylineId);
      if (polylineController != null) {
        polylineController.remove();
        googleMapsPolylineIdToDartPolylineId.remove(polylineController.getGoogleMapsPolylineId());
      }
    }
  }

  boolean onPolylineTap(String googlePolylineId) {
    String polylineId = googleMapsPolylineIdToDartPolylineId.get(googlePolylineId);
    if (polylineId == null) {
      return false;
    }
    flutterApi.onPolylineTap(polylineId, new NoOpVoidResult());
    PolylineController polylineController = polylineIdToController.get(polylineId);
    if (polylineController != null) {
      return polylineController.consumeTapEvents();
    }
    return false;
  }

  private void addPolyline(@NonNull Messages.PlatformPolyline polyline) {
    PolylineBuilder polylineBuilder = new PolylineBuilder(density);
    String polylineId =
        Convert.interpretPolylineOptions(polyline, polylineBuilder, assetManager, density);
    PolylineOptions options = polylineBuilder.build();
    addPolyline(polylineId, options, polylineBuilder.consumeTapEvents());
  }

  private void addPolyline(
      String polylineId, PolylineOptions polylineOptions, boolean consumeTapEvents) {
    final Polyline polyline = googleMap.addPolyline(polylineOptions);
    PolylineController controller = new PolylineController(polyline, consumeTapEvents, density);
    polylineIdToController.put(polylineId, controller);
    googleMapsPolylineIdToDartPolylineId.put(polyline.getId(), polylineId);
  }

  private void changePolyline(@NonNull Messages.PlatformPolyline polyline) {
    String polylineId = polyline.getPolylineId();
    PolylineController polylineController = polylineIdToController.get(polylineId);
    if (polylineController != null) {
      Convert.interpretPolylineOptions(polyline, polylineController, assetManager, density);
    }
  }

  private static String getPolylineId(Map<String, ?> polyline) {
    return (String) polyline.get("polylineId");
  }
}
