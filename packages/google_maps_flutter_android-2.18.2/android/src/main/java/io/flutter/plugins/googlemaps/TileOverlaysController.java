// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.googlemaps;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.TileOverlay;
import com.google.android.gms.maps.model.TileOverlayOptions;
import io.flutter.plugins.googlemaps.Messages.MapsCallbackApi;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import android.os.Handler;
import android.os.Looper;

class TileOverlaysController {

  private final Map<String, TileOverlayController> tileOverlayIdToController;
  private final MapsCallbackApi flutterApi;
  private GoogleMap googleMap;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  TileOverlaysController(MapsCallbackApi flutterApi) {
    this.tileOverlayIdToController = new HashMap<>();
    this.flutterApi = flutterApi;
  }

  void setGoogleMap(GoogleMap googleMap) {
    this.googleMap = googleMap;
  }

  void addTileOverlays(@NonNull List<Messages.PlatformTileOverlay> tileOverlaysToAdd) {
    // Get adaptive batch size based on device memory
    PerformanceUtils perfUtils = PerformanceUtils.getInstance();
    int batchSize = perfUtils.getOverlayBatchSize();
    int threshold = batchSize / 2; // Use half batch size as threshold

    // Process in batches if list is large to avoid UI freeze
    if (tileOverlaysToAdd.size() > threshold) {
      addTileOverlaysInBatches(tileOverlaysToAdd, 0, batchSize);
    } else {
      for (Messages.PlatformTileOverlay tileOverlayToAdd : tileOverlaysToAdd) {
        addTileOverlay(tileOverlayToAdd);
      }
    }
  }
  
  private void addTileOverlaysInBatches(List<Messages.PlatformTileOverlay> tileOverlays, int startIndex, int batchSize) {
    int endIndex = Math.min(startIndex + batchSize, tileOverlays.size());
    
    for (int i = startIndex; i < endIndex; i++) {
      addTileOverlay(tileOverlays.get(i));
    }
    
    if (endIndex < tileOverlays.size()) {
      mainHandler.post(() -> addTileOverlaysInBatches(tileOverlays, endIndex, batchSize));
    }
  }

  void changeTileOverlays(@NonNull List<Messages.PlatformTileOverlay> tileOverlaysToChange) {
    for (Messages.PlatformTileOverlay tileOverlayToChange : tileOverlaysToChange) {
      changeTileOverlay(tileOverlayToChange);
    }
  }

  void removeTileOverlays(List<String> tileOverlayIdsToRemove) {
    if (tileOverlayIdsToRemove == null) {
      return;
    }
    for (String tileOverlayId : tileOverlayIdsToRemove) {
      if (tileOverlayId == null) {
        continue;
      }
      removeTileOverlay(tileOverlayId);
    }
  }

  void clearTileCache(String tileOverlayId) {
    if (tileOverlayId == null) {
      return;
    }
    TileOverlayController tileOverlayController = tileOverlayIdToController.get(tileOverlayId);
    if (tileOverlayController != null) {
      tileOverlayController.clearTileCache();
    }
  }

  @Nullable
  TileOverlay getTileOverlay(String tileOverlayId) {
    if (tileOverlayId == null) {
      return null;
    }
    TileOverlayController tileOverlayController = tileOverlayIdToController.get(tileOverlayId);
    if (tileOverlayController == null) {
      return null;
    }
    return tileOverlayController.getTileOverlay();
  }

  private void addTileOverlay(@NonNull Messages.PlatformTileOverlay platformTileOverlay) {
    TileOverlayBuilder tileOverlayOptionsBuilder = new TileOverlayBuilder();
    String tileOverlayId =
        Convert.interpretTileOverlayOptions(platformTileOverlay, tileOverlayOptionsBuilder);
    TileProviderController tileProviderController =
        new TileProviderController(flutterApi, tileOverlayId);
    tileOverlayOptionsBuilder.setTileProvider(tileProviderController);
    TileOverlayOptions options = tileOverlayOptionsBuilder.build();
    TileOverlay tileOverlay = googleMap.addTileOverlay(options);
    TileOverlayController tileOverlayController = new TileOverlayController(tileOverlay);
    tileOverlayIdToController.put(tileOverlayId, tileOverlayController);
  }

  private void changeTileOverlay(@NonNull Messages.PlatformTileOverlay platformTileOverlay) {
    String tileOverlayId = platformTileOverlay.getTileOverlayId();
    TileOverlayController tileOverlayController = tileOverlayIdToController.get(tileOverlayId);
    if (tileOverlayController != null) {
      Convert.interpretTileOverlayOptions(platformTileOverlay, tileOverlayController);
    }
  }

  private void removeTileOverlay(String tileOverlayId) {
    TileOverlayController tileOverlayController = tileOverlayIdToController.get(tileOverlayId);
    if (tileOverlayController != null) {
      tileOverlayController.remove();
      tileOverlayIdToController.remove(tileOverlayId);
    }
  }

  @SuppressWarnings("unchecked")
  private static String getTileOverlayId(Map<String, ?> tileOverlay) {
    return (String) tileOverlay.get("tileOverlayId");
  }
}
