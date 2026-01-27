// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.googlemaps;

import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.GoogleMap;
import com.google.maps.android.collections.MarkerManager;
import io.flutter.plugins.googlemaps.Messages.MapsCallbackApi;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

class MarkersController {
  private final HashMap<String, MarkerBuilder> markerIdToMarkerBuilder;
  private final HashMap<String, MarkerController> markerIdToController;
  private final HashMap<String, String> googleMapsMarkerIdToDartMarkerId;
  private final @NonNull MapsCallbackApi flutterApi;
  private MarkerManager.Collection markerCollection;
  private final ClusterManagersController clusterManagersController;
  private final AssetManager assetManager;
  private final float density;
  private final Convert.BitmapDescriptorFactoryWrapper bitmapDescriptorFactoryWrapper;
  private GoogleMap googleMap;

  // Track which non-clustered markers are currently visible on map
  private final HashMap<String, Boolean> markerIdToVisibilityState;

  // Track if map is ready
  private boolean isMapReady = false;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  // Cache manager for high-performance marker filtering
  private MarkerCacheManager markerCacheManager;

  // Reference to controller for loading overlay
  private GoogleMapController mapController;

  // FILTER LOGIC COMMENTED OUT - Can be restored if needed
  // Performance optimizations for filter updates
  // private Runnable pendingFilterUpdate = null;
  // private Set<String> currentFilteredIds = null;
  // private LatLngBounds cachedVisibleBounds = null;
  // private long lastBoundsUpdateTime = 0;
  // private static final long BOUNDS_CACHE_DURATION_MS = 100; // Cache bounds for 100ms

  MarkersController(
      @NonNull MapsCallbackApi flutterApi,
      ClusterManagersController clusterManagersController,
      AssetManager assetManager,
      float density,
      Convert.BitmapDescriptorFactoryWrapper bitmapDescriptorFactoryWrapper) {
    this.markerIdToMarkerBuilder = new HashMap<>();
    this.markerIdToController = new HashMap<>();
    this.googleMapsMarkerIdToDartMarkerId = new HashMap<>();
    this.markerIdToVisibilityState = new HashMap<>();
    this.flutterApi = flutterApi;
    this.clusterManagersController = clusterManagersController;
    this.assetManager = assetManager;
    this.density = density;
    this.bitmapDescriptorFactoryWrapper = bitmapDescriptorFactoryWrapper;
  }

  void setCollection(MarkerManager.Collection markerCollection) {
    this.markerCollection = markerCollection;
  }

  void setGoogleMap(GoogleMap googleMap) {
    this.googleMap = googleMap;

    // Set up map loaded callback to ensure proper initialization
    if (googleMap != null) {
      googleMap.setOnMapLoadedCallback(new GoogleMap.OnMapLoadedCallback() {
        @Override
        public void onMapLoaded() {
          isMapReady = true;
          // Update visibility for all markers once map is ready
          mainHandler.post(() -> updateVisibleMarkers());
        }
      });

      // Also trigger after a short delay as backup
      mainHandler.postDelayed(() -> {
        if (!isMapReady) {
          isMapReady = true;
          updateVisibleMarkers();
        }
      }, 200);
    }
  }

  void setMarkerCacheManager(MarkerCacheManager markerCacheManager) {
    this.markerCacheManager = markerCacheManager;
  }

  void setMapController(GoogleMapController mapController) {
    this.mapController = mapController;
  }

  /**
   * Notify Flutter that marker loading has started
   * Flutter will show its own loader UI
   */
  private void notifyLoadingStarted(int totalMarkers) {
    try {
      // Send async callback to Flutter
      mainHandler.post(() -> {
        // Using method channel to send loading event
        if (mapController != null) {
          mapController.notifyMarkerLoadingStarted(totalMarkers);
        }
      });
    } catch (Exception e) {
      android.util.Log.e("MarkersController", "Error notifying loading started", e);
    }
  }

  /**
   * Notify Flutter that marker loading has finished
   * Flutter will hide its loader UI
   */
  private void notifyLoadingFinished() {
    try {
      // Send async callback to Flutter
      mainHandler.post(() -> {
        if (mapController != null) {
          mapController.notifyMarkerLoadingFinished();
        }
      });
    } catch (Exception e) {
      android.util.Log.e("MarkersController", "Error notifying loading finished", e);
    }
  }

  /**
   * OPTIMIZED: Batch add markers and render immediately
   * For non-clustered markers, show all immediately without waiting for camera
   * Uses adaptive batch sizes based on device memory for optimal performance
   */
  void addMarkers(@NonNull List<Messages.PlatformMarker> markersToAdd) {
    if (markersToAdd.isEmpty()) {
      return;
    }

    // Get adaptive batch size based on device memory
    PerformanceUtils perfUtils = PerformanceUtils.getInstance();
    int batchSize = perfUtils.getMarkerBatchSize();
    int threshold = perfUtils.getBatchingThreshold();

    // Process in batches if list is large to avoid UI freeze
    if (markersToAdd.size() > threshold) {
      // Notify Flutter that loading started (Flutter will show its own loader)
      notifyLoadingStarted(markersToAdd.size());

      // ANDROID LOADER COMMENTED OUT - Flutter handles UI
      // if (mapController != null) {
      //   mapController.showLoadingOverlay();
      // }

      addMarkersInBatches(markersToAdd, 0, batchSize);
    } else {
      processMarkers(markersToAdd, true);
    }
  }
  
  private void addMarkersInBatches(List<Messages.PlatformMarker> markers, int startIndex, int batchSize) {
    int endIndex = Math.min(startIndex + batchSize, markers.size());
    List<Messages.PlatformMarker> batch = markers.subList(startIndex, endIndex);

    boolean isLastBatch = (endIndex == markers.size());
    processMarkers(batch, isLastBatch);

    if (!isLastBatch) {
      mainHandler.post(() -> addMarkersInBatches(markers, endIndex, batchSize));
    } else {
      // Notify Flutter that loading finished (Flutter will hide its loader)
      // Add slight delay to ensure markers are rendered
      mainHandler.postDelayed(() -> {
        notifyLoadingFinished();

        // ANDROID LOADER COMMENTED OUT - Flutter handles UI
        // if (mapController != null) {
        //   mapController.hideLoadingOverlay();
        // }
      }, 100);
    }
  }

  private void processMarkers(List<Messages.PlatformMarker> markersToAdd, boolean triggerClustering) {
    // Get current visible region (with padding) if available
    LatLngBounds visibleBounds = getExpandedVisibleBounds();
    
    // Group markers by cluster manager ID for batch processing
    Map<String, List<MarkerBuilder>> clusterGroups = new HashMap<>();
    List<MarkerBuilder> nonClusteredMarkers = new ArrayList<>();
    
    for (Messages.PlatformMarker markerToAdd : markersToAdd) {
      String markerId = markerToAdd.getMarkerId();
      String clusterManagerId = markerToAdd.getClusterManagerId();
      MarkerBuilder markerBuilder = new MarkerBuilder(markerId, clusterManagerId);
      
      Convert.interpretMarkerOptions(
          markerToAdd, markerBuilder, assetManager, density, bitmapDescriptorFactoryWrapper);
      
      // Store marker builder for future rebuilds
      markerIdToMarkerBuilder.put(markerId, markerBuilder);
      
      if (clusterManagerId == null) {
        nonClusteredMarkers.add(markerBuilder);
      } else {
        clusterGroups.computeIfAbsent(clusterManagerId, k -> new ArrayList<>()).add(markerBuilder);
      }
    }
    
    // Add non-clustered markers to collection IMMEDIATELY
    for (MarkerBuilder builder : nonClusteredMarkers) {
      // If map is not ready yet OR bounds unavailable, add all markers
      if (!isMapReady || visibleBounds == null) {
        addMarkerToCollection(builder.markerId(), builder);
        markerIdToVisibilityState.put(builder.markerId(), true);
      } 
      // If map is ready and we have bounds, filter by visible region
      else if (isMarkerInBounds(builder, visibleBounds)) {
        addMarkerToCollection(builder.markerId(), builder);
        markerIdToVisibilityState.put(builder.markerId(), true);
      } else {
        // Mark as not visible - will be shown when user pans to this area
        markerIdToVisibilityState.put(builder.markerId(), false);
      }
    }
    
    // Batch add clustered markers
    boolean hasClusteredMarkers = false;
    for (Map.Entry<String, List<MarkerBuilder>> entry : clusterGroups.entrySet()) {
      for (MarkerBuilder builder : entry.getValue()) {
        clusterManagersController.addItem(builder);
        hasClusteredMarkers = true;
      }
    }
    
    // CRITICAL: Force immediate clustering for clustered markers
    // Only trigger on the last batch to avoid redundant clustering
    if (hasClusteredMarkers && triggerClustering) {
      // Post with minimal delay to allow batch operations to complete
      mainHandler.postDelayed(() -> {
        clusterManagersController.forceClusterUpdate();
      }, 50);
    }
  }

  /**
   * Update visible markers when camera moves
   * Call this from GoogleMapController when camera becomes idle
   */
  public void updateVisibleMarkers() {
    // Null safety checks
    if (googleMap == null || markerCollection == null) {
      return;
    }

    try {
      LatLngBounds visibleBounds = getExpandedVisibleBounds();
      if (visibleBounds == null) {
        return;
      }

      // Update visibility for all non-clustered markers
      // Create a copy of entry set to avoid ConcurrentModificationException
      Set<Map.Entry<String, MarkerBuilder>> entries =
          new HashMap<>(markerIdToMarkerBuilder).entrySet();

      for (Map.Entry<String, MarkerBuilder> entry : entries) {
        try {
          String markerId = entry.getKey();
          MarkerBuilder builder = entry.getValue();

          if (markerId == null || builder == null) {
            continue;
          }

          // Skip clustered markers (handled by ClusterManagersController)
          if (builder.clusterManagerId() != null) {
            continue;
          }

          boolean shouldBeVisible = isMarkerInBounds(builder, visibleBounds);
          Boolean currentlyVisible = markerIdToVisibilityState.get(markerId);

          if (currentlyVisible == null) {
            currentlyVisible = false;
          }

          // Marker should be visible but isn't - add it
          if (shouldBeVisible && !currentlyVisible) {
            addMarkerToCollection(markerId, builder);
            markerIdToVisibilityState.put(markerId, true);
          }
          // Marker shouldn't be visible but is - remove it
          else if (!shouldBeVisible && currentlyVisible) {
            MarkerController controller = markerIdToController.get(markerId);
            if (controller != null) {
              try {
                controller.removeFromCollection(markerCollection);
                markerIdToController.remove(markerId);
                String googleMarkerId = controller.getGoogleMapsMarkerId();
                if (googleMarkerId != null) {
                  googleMapsMarkerIdToDartMarkerId.remove(googleMarkerId);
                }
              } catch (Exception e) {
                // Log but don't crash if marker removal fails
                // Marker may have already been removed
              }
            }
            markerIdToVisibilityState.put(markerId, false);
          }
        } catch (Exception e) {
          // Continue processing other markers if one fails
          // This prevents a single bad marker from breaking visibility updates
        }
      }
    } catch (Exception e) {
      // Catch-all to prevent crashes during visibility updates
      // Map may be in transitional state
    }
  }

  /**
   * Get visible bounds with padding (20% expansion)
   * Includes robust error handling for map projection edge cases
   */
  private LatLngBounds getExpandedVisibleBounds() {
    if (googleMap == null) {
      return null;
    }

    try {
      LatLngBounds bounds = googleMap.getProjection().getVisibleRegion().latLngBounds;
      if (bounds == null) {
        return null;
      }
      return expandBounds(bounds, 1.2); // 20% padding
    } catch (IllegalStateException e) {
      // Map not ready yet or in transitional state
      return null;
    } catch (Exception e) {
      // Any other projection errors (rare but possible)
      return null;
    }
  }

  // FILTER LOGIC COMMENTED OUT - Can be restored if needed
  /**
   * Get cached visible bounds to avoid expensive projection calls
   * Caches bounds for 100ms to reduce CPU overhead
   */
  // private LatLngBounds getCachedVisibleBounds() {
  //   long currentTime = System.currentTimeMillis();
  //
  //   // Return cached bounds if still valid
  //   if (cachedVisibleBounds != null &&
  //       (currentTime - lastBoundsUpdateTime) < BOUNDS_CACHE_DURATION_MS) {
  //     return cachedVisibleBounds;
  //   }
  //
  //   // Update cache
  //   cachedVisibleBounds = getExpandedVisibleBounds();
  //   lastBoundsUpdateTime = currentTime;
  //   return cachedVisibleBounds;
  // }

  /**
   * Expand bounds by a factor (e.g., 1.2 = 20% larger)
   * Includes null safety and validation
   */
  private LatLngBounds expandBounds(LatLngBounds bounds, double factor) {
    if (bounds == null || bounds.southwest == null || bounds.northeast == null) {
      return bounds; // Return original if null
    }

    try {
      double latDiff = bounds.northeast.latitude - bounds.southwest.latitude;
      double lngDiff = bounds.northeast.longitude - bounds.southwest.longitude;

      double latExpansion = latDiff * (factor - 1.0) / 2.0;
      double lngExpansion = lngDiff * (factor - 1.0) / 2.0;

      return new LatLngBounds(
          new LatLng(
              bounds.southwest.latitude - latExpansion,
              bounds.southwest.longitude - lngExpansion
          ),
          new LatLng(
              bounds.northeast.latitude + latExpansion,
              bounds.northeast.longitude + lngExpansion
          )
      );
    } catch (Exception e) {
      // Return original bounds if expansion fails
      return bounds;
    }
  }

  /**
   * Check if marker is within bounds
   * Includes null safety checks
   */
  private boolean isMarkerInBounds(MarkerBuilder builder, LatLngBounds bounds) {
    if (builder == null || bounds == null) {
      return false;
    }

    try {
      LatLng position = builder.getPosition();
      return position != null && bounds.contains(position);
    } catch (Exception e) {
      // Return false if position check fails
      return false;
    }
  }

  void changeMarkers(@NonNull List<Messages.PlatformMarker> markersToChange) {
    boolean hasClusteredChanges = false;
    
    for (Messages.PlatformMarker markerToChange : markersToChange) {
      changeMarker(markerToChange);
      
      // Check if this is a clustered marker
      MarkerBuilder builder = markerIdToMarkerBuilder.get(markerToChange.getMarkerId());
      if (builder != null && builder.clusterManagerId() != null) {
        hasClusteredChanges = true;
      }
    }
    
    // CRITICAL: Force UI update after changing clustered markers
    if (hasClusteredChanges) {
      mainHandler.postDelayed(() -> {
        clusterManagersController.forceClusterUpdate();
      }, 50);
    }
  }

  void removeMarkers(@NonNull List<String> markerIdsToRemove) {
    boolean hasClusteredRemovals = false;
    
    for (String markerId : markerIdsToRemove) {
      // Check if this is a clustered marker before removing
      MarkerBuilder builder = markerIdToMarkerBuilder.get(markerId);
      if (builder != null && builder.clusterManagerId() != null) {
        hasClusteredRemovals = true;
      }
      
      removeMarker(markerId);
    }
    
    // CRITICAL: Force UI update after removing clustered markers
    if (hasClusteredRemovals) {
      mainHandler.postDelayed(() -> {
        clusterManagersController.forceClusterUpdate();
      }, 50);
    }
  }

  private void removeMarker(String markerId) {
    final MarkerBuilder markerBuilder = markerIdToMarkerBuilder.remove(markerId);
    if (markerBuilder == null) {
      return;
    }
    final MarkerController markerController = markerIdToController.remove(markerId);
    final String clusterManagerId = markerBuilder.clusterManagerId();
    if (clusterManagerId != null) {
      // Remove marker from clusterManager.
      clusterManagersController.removeItem(markerBuilder);
    } else if (markerController != null && this.markerCollection != null) {
      // Remove marker from map and markerCollection
      markerController.removeFromCollection(markerCollection);
    }

    if (markerController != null) {
      googleMapsMarkerIdToDartMarkerId.remove(markerController.getGoogleMapsMarkerId());
    }
    
    // Remove visibility state
    markerIdToVisibilityState.remove(markerId);
  }

  void showMarkerInfoWindow(String markerId) {
    MarkerController markerController = markerIdToController.get(markerId);
    if (markerController == null) {
      throw new Messages.FlutterError(
          "Invalid markerId", "showInfoWindow called with invalid markerId", null);
    }
    markerController.showInfoWindow();
  }

  void hideMarkerInfoWindow(String markerId) {
    MarkerController markerController = markerIdToController.get(markerId);
    if (markerController == null) {
      throw new Messages.FlutterError(
          "Invalid markerId", "hideInfoWindow called with invalid markerId", null);
    }
    markerController.hideInfoWindow();
  }

  boolean isInfoWindowShown(String markerId) {
    MarkerController markerController = markerIdToController.get(markerId);
    if (markerController == null) {
      throw new Messages.FlutterError(
          "Invalid markerId", "isInfoWindowShown called with invalid markerId", null);
    }
    return markerController.isInfoWindowShown();
  }

  boolean onMapsMarkerTap(String googleMarkerId) {
    String markerId = googleMapsMarkerIdToDartMarkerId.get(googleMarkerId);
    if (markerId == null) {
      return false;
    }
    return onMarkerTap(markerId);
  }

  boolean onMarkerTap(String markerId) {
    flutterApi.onMarkerTap(markerId, new NoOpVoidResult());
    MarkerController markerController = markerIdToController.get(markerId);
    if (markerController != null) {
      return markerController.consumeTapEvents();
    }
    return false;
  }

  void onMarkerDragStart(String googleMarkerId, LatLng latLng) {
    String markerId = googleMapsMarkerIdToDartMarkerId.get(googleMarkerId);
    if (markerId == null) {
      return;
    }
    flutterApi.onMarkerDragStart(markerId, Convert.latLngToPigeon(latLng), new NoOpVoidResult());
  }

  void onMarkerDrag(String googleMarkerId, LatLng latLng) {
    String markerId = googleMapsMarkerIdToDartMarkerId.get(googleMarkerId);
    if (markerId == null) {
      return;
    }
    flutterApi.onMarkerDrag(markerId, Convert.latLngToPigeon(latLng), new NoOpVoidResult());
  }

  void onMarkerDragEnd(String googleMarkerId, LatLng latLng) {
    String markerId = googleMapsMarkerIdToDartMarkerId.get(googleMarkerId);
    if (markerId == null) {
      return;
    }
    flutterApi.onMarkerDragEnd(markerId, Convert.latLngToPigeon(latLng), new NoOpVoidResult());
  }

  void onInfoWindowTap(String googleMarkerId) {
    String markerId = googleMapsMarkerIdToDartMarkerId.get(googleMarkerId);
    if (markerId == null) {
      return;
    }
    flutterApi.onInfoWindowTap(markerId, new NoOpVoidResult());
  }

  /**
   * Called each time clusterManager adds new visible marker to the map. Creates markerController
   * for marker for realtime marker updates.
   */
  public void onClusterItemRendered(MarkerBuilder markerBuilder, Marker marker) {
    String markerId = markerBuilder.markerId();
    if (markerIdToMarkerBuilder.get(markerId) == markerBuilder) {
      createControllerForMarker(markerBuilder.markerId(), marker, markerBuilder.consumeTapEvents());
    }
  }

  private void addMarkerToCollection(String markerId, MarkerBuilder markerBuilder) {
    if (markerCollection == null) {
      return;
    }
    
    // Remove existing marker if present
    MarkerController existingController = markerIdToController.get(markerId);
    if (existingController != null) {
      existingController.removeFromCollection(markerCollection);
      googleMapsMarkerIdToDartMarkerId.remove(existingController.getGoogleMapsMarkerId());
    }
    
    MarkerOptions options = markerBuilder.build();
    final Marker marker = markerCollection.addMarker(options);
    if (marker != null) {
      createControllerForMarker(markerId, marker, markerBuilder.consumeTapEvents());
    }
  }

  private void createControllerForMarker(String markerId, Marker marker, boolean consumeTapEvents) {
    MarkerController controller = new MarkerController(marker, consumeTapEvents);
    markerIdToController.put(markerId, controller);
    googleMapsMarkerIdToDartMarkerId.put(marker.getId(), markerId);
  }

  private void changeMarker(@NonNull Messages.PlatformMarker marker) {
    String markerId = marker.getMarkerId();

    MarkerBuilder markerBuilder = markerIdToMarkerBuilder.get(markerId);
    if (markerBuilder == null) {
      return;
    }

    String clusterManagerId = marker.getClusterManagerId();
    String oldClusterManagerId = markerBuilder.clusterManagerId();

    // If the cluster ID on the updated marker has changed, the marker needs to
    // be removed and re-added to update its cluster manager state.
    if (!(Objects.equals(clusterManagerId, oldClusterManagerId))) {
      removeMarker(markerId);
      addMarkers(java.util.Collections.singletonList(marker));
      return;
    }

    // Update marker builder.
    Convert.interpretMarkerOptions(
        marker, markerBuilder, assetManager, density, bitmapDescriptorFactoryWrapper);

    // Update existing marker on map (only if currently visible).
    MarkerController markerController = markerIdToController.get(markerId);
    if (markerController != null) {
      Convert.interpretMarkerOptions(
          marker, markerController, assetManager, density, bitmapDescriptorFactoryWrapper);
    }
  }

  /**
   * Android-specific: Update visible markers based on filtered IDs from cache.
   * HIGHLY OPTIMIZED VERSION with:
   * - Delta calculation (only update changed markers)
   * - Pending update cancellation
   * - Cached bounds
   * - Batched async processing
   * - Immediate removal for instant feedback
   *
   * @param filteredMarkerIds Set of marker IDs that should be visible after filter
   */
  // FILTER LOGIC COMMENTED OUT - Can be restored if needed
  // public void updateVisibleMarkersFromCache(@NonNull Set<String> filteredMarkerIds) {
  //   if (markerCacheManager == null || googleMap == null || markerCollection == null) {
  //     return;
  //   }
  //
  //   try {
  //     // OPTIMIZATION 1: Cancel pending filter updates (debouncing)
  //     if (pendingFilterUpdate != null) {
  //       mainHandler.removeCallbacks(pendingFilterUpdate);
  //       pendingFilterUpdate = null;
  //     }
  //
  //     // OPTIMIZATION 2: Early exit if filter hasn't changed
  //     if (currentFilteredIds != null && currentFilteredIds.equals(filteredMarkerIds)) {
  //       return; // No change, skip update
  //     }
  //
  //     // Clear cached bounds to force fresh calculation after filter change
  //     cachedVisibleBounds = null;
  //
  //     // OPTIMIZATION 3: Calculate delta (what actually changed)
  //     Set<String> previousFilteredIds = currentFilteredIds != null ?
  //         new java.util.HashSet<>(currentFilteredIds) : new java.util.HashSet<>();
  //     currentFilteredIds = new java.util.HashSet<>(filteredMarkerIds);
  //
  //     // Markers to add = new filtered IDs that weren't in previous set
  //     Set<String> markersToAdd = new java.util.HashSet<>(filteredMarkerIds);
  //     markersToAdd.removeAll(previousFilteredIds);
  //
  //     // Markers to remove = previous filtered IDs that aren't in new set
  //     Set<String> markersToRemove = previousFilteredIds;
  //     markersToRemove.removeAll(filteredMarkerIds);
  //
  //     // OPTIMIZATION 4: IMMEDIATELY remove markers (instant visual feedback)
  //     removeMarkersImmediate(markersToRemove);
  //
  //     // OPTIMIZATION 5: Add markers in optimized batches
  //     if (!markersToAdd.isEmpty()) {
  //       // Get cached bounds once
  //       LatLngBounds visibleBounds = getCachedVisibleBounds();
  //
  //       // Filter to only visible markers to minimize processing
  //       List<String> visibleMarkersToAdd = new ArrayList<>();
  //       for (String markerId : markersToAdd) {
  //         MarkerBuilder builder = markerIdToMarkerBuilder.get(markerId);
  //         if (builder != null && (visibleBounds == null || isMarkerInBounds(builder, visibleBounds))) {
  //           visibleMarkersToAdd.add(markerId);
  //         }
  //       }
  //
  //       // Process in batches if needed
  //       if (!visibleMarkersToAdd.isEmpty()) {
  //         PerformanceUtils perfUtils = PerformanceUtils.getInstance();
  //         int batchSize = perfUtils.getMarkerBatchSize();
  //
  //         if (visibleMarkersToAdd.size() > batchSize) {
  //           // Large list - batch async
  //           updateVisibleMarkersInBatches(visibleMarkersToAdd, 0, batchSize);
  //         } else {
  //           // Small list - add synchronously (faster)
  //           for (String markerId : visibleMarkersToAdd) {
  //             addMarkerToCollectionFast(markerId);
  //           }
  //         }
  //       }
  //     }
  //
  //   } catch (Exception e) {
  //     android.util.Log.e("MarkersController", "Error updating markers from cache", e);
  //   }
  // }

  // /**
  //  * Immediately remove markers for instant feedback.
  //  * Optimized for bulk removal.
  //  */
  // private void removeMarkersImmediate(@NonNull Set<String> markersToRemove) {
  //   for (String markerId : markersToRemove) {
  //     Boolean visible = markerIdToVisibilityState.get(markerId);
  //     if (visible != null && visible) {
  //       MarkerController controller = markerIdToController.get(markerId);
  //       if (controller != null) {
  //         controller.removeFromCollection(markerCollection);
  //         markerIdToController.remove(markerId);
  //         String googleMarkerId = controller.getGoogleMapsMarkerId();
  //         if (googleMarkerId != null) {
  //           googleMapsMarkerIdToDartMarkerId.remove(googleMarkerId);
  //         }
  //       }
  //       markerIdToVisibilityState.put(markerId, false);
  //     }
  //   }
  // }

  // /**
  //  * Fast marker addition without bounds checks (already filtered).
  //  */
  // private void addMarkerToCollectionFast(@NonNull String markerId) {
  //   MarkerBuilder builder = markerIdToMarkerBuilder.get(markerId);
  //   if (builder != null) {
  //     Boolean currentlyVisible = markerIdToVisibilityState.get(markerId);
  //     if (currentlyVisible == null || !currentlyVisible) {
  //       addMarkerToCollection(markerId, builder);
  //       markerIdToVisibilityState.put(markerId, true);
  //     }
  //   }
  // }

  // /**
  //  * Process filtered markers in batches (OPTIMIZED).
  //  * Uses cached bounds to avoid repeated projection calls.
  //  */
  // private void updateVisibleMarkersInBatches(List<String> visibleMarkerIds, int startIndex, int batchSize) {
  //   if (googleMap == null || markerCollection == null) {
  //     return;
  //   }
  //
  //   int endIndex = Math.min(startIndex + batchSize, visibleMarkerIds.size());
  //
  //   try {
  //     // Process this batch
  //     for (int i = startIndex; i < endIndex; i++) {
  //       String markerId = visibleMarkerIds.get(i);
  //       addMarkerToCollectionFast(markerId);
  //     }
  //
  //     // Schedule next batch if there are more markers
  //     if (endIndex < visibleMarkerIds.size()) {
  //       Runnable nextBatch = () -> updateVisibleMarkersInBatches(visibleMarkerIds, endIndex, batchSize);
  //       pendingFilterUpdate = nextBatch;
  //       mainHandler.post(nextBatch);
  //     } else {
  //       pendingFilterUpdate = null;
  //     }
  //   } catch (Exception e) {
  //     android.util.Log.e("MarkersController", "Error in batch update", e);
  //     pendingFilterUpdate = null;
  //   }
  // }

  /**
   * Cleanup resources
   */
  public void cleanup() {
    // FILTER LOGIC COMMENTED OUT - Can be restored if needed
    // Cancel pending filter updates
    // if (pendingFilterUpdate != null) {
    //   mainHandler.removeCallbacks(pendingFilterUpdate);
    //   pendingFilterUpdate = null;
    // }

    mainHandler.removeCallbacksAndMessages(null);
    markerIdToMarkerBuilder.clear();
    markerIdToController.clear();
    googleMapsMarkerIdToDartMarkerId.clear();
    markerIdToVisibilityState.clear();

    // FILTER LOGIC COMMENTED OUT - Can be restored if needed
    // Clear cache state
    // currentFilteredIds = null;
    // cachedVisibleBounds = null;
  }
}
