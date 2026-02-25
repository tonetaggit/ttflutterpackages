// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.googlemaps;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.collection.LruCache;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.maps.android.clustering.Cluster;
import com.google.maps.android.clustering.ClusterItem;
import com.google.maps.android.clustering.ClusterManager;
import com.google.maps.android.clustering.view.DefaultClusterRenderer;
import com.google.maps.android.clustering.algo.NonHierarchicalDistanceBasedAlgorithm;
import com.google.maps.android.collections.MarkerManager;
import io.flutter.plugins.googlemaps.Messages.MapsCallbackApi;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collection;
import java.util.ArrayList;
import android.graphics.Paint;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Controls cluster managers and exposes interfaces for adding and removing cluster items for
 * specific cluster managers. OPTIMIZED for visible region only with improved performance.
 */
class ClusterManagersController
    implements GoogleMap.OnCameraIdleListener,
        GoogleMap.OnCameraMoveStartedListener,
        GoogleMap.OnCameraMoveListener,
        ClusterManager.OnClusterClickListener<MarkerBuilder> {
  @NonNull private final Context context;
  @NonNull private final HashMap<String, ClusterManager<MarkerBuilder>> clusterManagerIdToManager;
  @NonNull private final HashMap<String, Collection<MarkerBuilder>> clusterManagerIdToAllItems;
  @NonNull private final MapsCallbackApi flutterApi;
  @Nullable private MarkerManager markerManager;
  @Nullable private GoogleMap googleMap;

  @Nullable
  private ClusterManager.OnClusterItemClickListener<MarkerBuilder> clusterItemClickListener;

  @Nullable
  private ClusterManagersController.OnClusterItemRendered<MarkerBuilder>
      clusterItemRenderedListener;
      
  // Track camera movement state
  private boolean isUserInteracting = false;
  private long lastCameraMoveTime = 0;
  private float lastZoomLevel = 0;
  private LatLngBounds lastVisibleRegion = null;
  
  // Handler for delayed clustering
  private final Handler clusteringHandler = new Handler(Looper.getMainLooper());
  private Runnable pendingClusterTask = null;
  private static final long CLUSTER_DELAY_MS = 150; // Delay after user stops interacting

  // Track if initial clustering is done
  private boolean isInitialClusteringDone = false;

  // Background thread for filtering operations (not for actual clustering)
  // Using single thread executor to ensure sequential processing
  private final ExecutorService filteringExecutor = Executors.newSingleThreadExecutor();
  private final AtomicBoolean isFilteringInProgress = new AtomicBoolean(false);

  ClusterManagersController(@NonNull MapsCallbackApi flutterApi, Context context) {
    this.clusterManagerIdToManager = new HashMap<>();
    this.clusterManagerIdToAllItems = new HashMap<>();
    this.context = context;
    this.flutterApi = flutterApi;
  }

  void init(GoogleMap googleMap, MarkerManager markerManager) {
    this.markerManager = markerManager;
    this.googleMap = googleMap;
    if (googleMap != null) {
      this.lastZoomLevel = googleMap.getCameraPosition().zoom;
      try {
        this.lastVisibleRegion = googleMap.getProjection().getVisibleRegion().latLngBounds;
      } catch (Exception e) {
        this.lastVisibleRegion = null;
      }
      
      // Perform initial clustering after a short delay to ensure map is ready
      clusteringHandler.postDelayed(() -> {
        if (!isInitialClusteringDone) {
          performClustering();
          isInitialClusteringDone = true;
        }
      }, 300);
    }
  }

  void setClusterItemClickListener(
      @Nullable ClusterManager.OnClusterItemClickListener<MarkerBuilder> listener) {
    clusterItemClickListener = listener;
    initListenersForClusterManagers();
  }

  void setClusterItemRenderedListener(
      @Nullable ClusterManagersController.OnClusterItemRendered<MarkerBuilder> listener) {
    clusterItemRenderedListener = listener;
  }

  private void initListenersForClusterManagers() {
    for (Map.Entry<String, ClusterManager<MarkerBuilder>> entry :
        clusterManagerIdToManager.entrySet()) {
      initListenersForClusterManager(entry.getValue(), this, clusterItemClickListener);
    }
  }

  private void initListenersForClusterManager(
      ClusterManager<MarkerBuilder> clusterManager,
      @Nullable ClusterManager.OnClusterClickListener<MarkerBuilder> clusterClickListener,
      @Nullable ClusterManager.OnClusterItemClickListener<MarkerBuilder> clusterItemClickListener) {
    clusterManager.setOnClusterClickListener(clusterClickListener);
    clusterManager.setOnClusterItemClickListener(clusterItemClickListener);
  }

  /** Adds new ClusterManagers to the controller. */
  void addClusterManagers(@NonNull List<Messages.PlatformClusterManager> clusterManagersToAdd) {
    for (Messages.PlatformClusterManager clusterToAdd : clusterManagersToAdd) {
      addClusterManager(clusterToAdd.getIdentifier());
    }
  }

  /** Adds new ClusterManager to the controller with optimized algorithm. */
  void addClusterManager(String clusterManagerId) {
    ClusterManager<MarkerBuilder> clusterManager =
        new ClusterManager<MarkerBuilder>(context, googleMap, markerManager);
    
    // Use NonHierarchicalDistanceBasedAlgorithm for better performance
    NonHierarchicalDistanceBasedAlgorithm<MarkerBuilder> algorithm = 
        new NonHierarchicalDistanceBasedAlgorithm<>();
    clusterManager.setAlgorithm(algorithm);
    
    ClusterRenderer<MarkerBuilder> clusterRenderer =
        new ClusterRenderer<MarkerBuilder>(context, googleMap, clusterManager, this);
    clusterManager.setRenderer(clusterRenderer);
    initListenersForClusterManager(clusterManager, this, clusterItemClickListener);
    clusterManagerIdToManager.put(clusterManagerId, clusterManager);
    
    // Initialize collection for all items
    clusterManagerIdToAllItems.put(clusterManagerId, new ArrayList<>());
  }

  /** Removes ClusterManagers by given cluster manager IDs from the controller. */
  public void removeClusterManagers(@NonNull List<String> clusterManagerIdsToRemove) {
    for (String clusterManagerId : clusterManagerIdsToRemove) {
      removeClusterManager(clusterManagerId);
    }
  }

  /**
   * Removes the ClusterManagers by the given cluster manager ID from the controller.
   */
  private void removeClusterManager(Object clusterManagerId) {
    final ClusterManager<MarkerBuilder> clusterManager =
        clusterManagerIdToManager.remove(clusterManagerId);
    if (clusterManager == null) {
      return;
    }
    
    // Cancel any pending cluster operations
    cancelPendingClustering();
    
    initListenersForClusterManager(clusterManager, null, null);
    clusterManager.clearItems();
    clusterManager.cluster();
    
    // Clean up stored items
    clusterManagerIdToAllItems.remove(clusterManagerId);
    
    // Clean up bitmap cache
    if (clusterManager.getRenderer() instanceof ClusterRenderer) {
      ((ClusterRenderer<?>) clusterManager.getRenderer()).cleanup();
    }
  }

  /**
   * Adds item to storage - will be filtered by visible region during clustering
   * FIXED: Prevents duplicate MarkerBuilder instances with same markerId
   */
  public void addItem(MarkerBuilder item) {
    String clusterManagerId = item.clusterManagerId();
    Collection<MarkerBuilder> allItems = clusterManagerIdToAllItems.get(clusterManagerId);
    if (allItems != null) {
      // Remove any existing item with the same markerId to prevent duplicates
      String newMarkerId = item.markerId();
      allItems.removeIf(existingItem -> existingItem.markerId().equals(newMarkerId));

      // Now add the new item
      allItems.add(item);
    }
  }

  /**
   * Removes item from storage
   * FIXED: Uses markerId for consistent removal
   */
  public void removeItem(MarkerBuilder item) {
    String clusterManagerId = item.clusterManagerId();
    Collection<MarkerBuilder> allItems = clusterManagerIdToAllItems.get(clusterManagerId);
    if (allItems != null) {
      // Remove by markerId to ensure we remove the correct item even if object instance differs
      String markerId = item.markerId();
      allItems.removeIf(existingItem -> existingItem.markerId().equals(markerId));
    }

    // Also remove from cluster manager
    ClusterManager<MarkerBuilder> clusterManager = clusterManagerIdToManager.get(clusterManagerId);
    if (clusterManager != null) {
      clusterManager.removeItem(item);
    }
  }
  
  /**
   * CRITICAL FIX: Force cluster update immediately without any delays
   * This ensures markers appear instantly when added from Flutter
   */
  public void forceClusterUpdate() {
    if (!isInitialClusteringDone) {
      isInitialClusteringDone = true;
    }
    
    // Cancel any pending updates to avoid conflicts
    cancelPendingClustering();
    
    // CRITICAL: Perform clustering synchronously on the calling thread
    // This ensures immediate visual update without waiting for handlers
    performClusteringSynchronously();
  }

  /**
   * Optimized clustering with background filtering.
   * Filtering happens on background thread, clustering on main thread.
   */
  private void performClusteringSynchronously() {
    if (googleMap == null) {
      return;
    }

    // Skip if already filtering in background
    if (isFilteringInProgress.get()) {
      return;
    }

    // Get current visible region on main thread (must be on main thread)
    LatLngBounds currentVisibleRegion = null;
    try {
      currentVisibleRegion = googleMap.getProjection().getVisibleRegion().latLngBounds;
    } catch (Exception e) {
      currentVisibleRegion = lastVisibleRegion;
    }

    if (currentVisibleRegion == null) {
      return; // Can't perform clustering without visible region
    }

    // Update last visible region
    lastVisibleRegion = currentVisibleRegion;

    // Expand bounds slightly to include markers just outside screen (padding)
    final LatLngBounds expandedBounds = expandBounds(currentVisibleRegion, 1.2); // 20% padding

    // Copy data for background thread (avoid concurrent modification)
    final Map<String, ClusterManager<MarkerBuilder>> managersCopy = new HashMap<>(clusterManagerIdToManager);
    final Map<String, Collection<MarkerBuilder>> allItemsCopy = new HashMap<>();
    for (Map.Entry<String, Collection<MarkerBuilder>> entry : clusterManagerIdToAllItems.entrySet()) {
      // Create new ArrayList to avoid concurrent modification
      allItemsCopy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
    }

    // Mark filtering as in progress
    isFilteringInProgress.set(true);

    // Perform filtering on background thread
    filteringExecutor.execute(new Runnable() {
      @Override
      public void run() {
        try {
          // Filter all items on background thread
          final Map<String, Collection<MarkerBuilder>> filteredResults = new HashMap<>();

          for (Map.Entry<String, Collection<MarkerBuilder>> entry : allItemsCopy.entrySet()) {
            String clusterManagerId = entry.getKey();
            Collection<MarkerBuilder> allItems = entry.getValue();

            if (allItems == null || allItems.isEmpty()) {
              filteredResults.put(clusterManagerId, new ArrayList<MarkerBuilder>());
            } else {
              // Filter items to only those in visible region (CPU-intensive part)
              Collection<MarkerBuilder> visibleItems = filterItemsByBounds(allItems, expandedBounds);
              filteredResults.put(clusterManagerId, visibleItems);
            }
          }

          // Post clustering operations back to main thread
          clusteringHandler.post(new Runnable() {
            @Override
            public void run() {
              try {
                // Apply filtered results to cluster managers on main thread
                for (Map.Entry<String, ClusterManager<MarkerBuilder>> entry : managersCopy.entrySet()) {
                  String clusterManagerId = entry.getKey();
                  ClusterManager<MarkerBuilder> clusterManager = entry.getValue();
                  Collection<MarkerBuilder> visibleItems = filteredResults.get(clusterManagerId);

                  if (visibleItems == null) {
                    continue;
                  }

                  // Clear existing items from cluster manager
                  clusterManager.clearItems();

                  // Add only visible items
                  if (!visibleItems.isEmpty()) {
                    clusterManager.addItems(visibleItems);
                  }

                  // CRITICAL: Call cluster() to immediately render the markers
                  // This is what actually makes the markers appear on the map
                  clusterManager.cluster();
                }
              } finally {
                isFilteringInProgress.set(false);
              }
            }
          });
        } catch (Exception e) {
          // Reset flag on error
          isFilteringInProgress.set(false);
        }
      }
    });
  }

  /** Called when ClusterRenderer has rendered new visible marker to the map. */
  void onClusterItemRendered(@NonNull MarkerBuilder item, @NonNull Marker marker) {
    if (clusterItemRenderedListener != null) {
      clusterItemRenderedListener.onClusterItemRendered(item, marker);
    }
  }

  @SuppressWarnings("unchecked")
  private static String getClusterManagerId(Object clusterManagerData) {
    Map<String, Object> clusterMap = (Map<String, Object>) clusterManagerData;
    return (String) clusterMap.get("clusterManagerId");
  }

  /** Requests all current clusters from the algorithm of the requested ClusterManager. */
  public @NonNull Set<? extends Cluster<MarkerBuilder>> getClustersWithClusterManagerId(
      String clusterManagerId) {
    ClusterManager<MarkerBuilder> clusterManager = clusterManagerIdToManager.get(clusterManagerId);
    if (clusterManager == null) {
      throw new Messages.FlutterError(
          "Invalid clusterManagerId",
          "getClusters called with invalid clusterManagerId:" + clusterManagerId,
          null);
    }
    return clusterManager.getAlgorithm().getClusters(googleMap.getCameraPosition().zoom);
  }

  /**
   * CRITICAL: Detect when camera movement starts (zoom/pan)
   */
  @Override
  public void onCameraMoveStarted(int reason) {
    lastCameraMoveTime = System.currentTimeMillis();
    
    if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
      // User is actively zooming or panning
      isUserInteracting = true;
      
      // Cancel any pending clustering - don't cluster while user is interacting
      cancelPendingClustering();
    }
  }

  /**
   * Track camera movement to detect zoom changes
   */
  @Override
  public void onCameraMove() {
    if (googleMap == null) return;
    
    lastCameraMoveTime = System.currentTimeMillis();
    float currentZoom = googleMap.getCameraPosition().zoom;
    
    // Detect if zoom level changed significantly
    if (Math.abs(currentZoom - lastZoomLevel) > 0.01f) {
      cancelPendingClustering();
    }
    
    lastZoomLevel = currentZoom;
  }

  /**
   * OPTIMIZED: Only cluster when camera stops AND user released zoom/pan
   */
  @Override
  public void onCameraIdle() {
    // Calculate time since last movement
    long timeSinceMove = System.currentTimeMillis() - lastCameraMoveTime;
    
    // Update visible region
    if (googleMap != null) {
      try {
        lastVisibleRegion = googleMap.getProjection().getVisibleRegion().latLngBounds;
      } catch (Exception e) {
        lastVisibleRegion = null;
      }
    }
    
    // Reset interaction flag
    isUserInteracting = false;
    
    // If camera just stopped (within 50ms), schedule delayed clustering
    if (timeSinceMove < 50) {
      scheduleDelayedClustering();
    } else {
      // Camera has been idle, perform immediate clustering
      performClustering();
    }
  }

  /**
   * Cancel any pending clustering operations
   */
  private void cancelPendingClustering() {
    if (pendingClusterTask != null) {
      clusteringHandler.removeCallbacks(pendingClusterTask);
      pendingClusterTask = null;
    }
  }

  /**
   * Schedule clustering with delay - only executes if camera stays idle
   */
  private void scheduleDelayedClustering() {
    cancelPendingClustering();
    
    pendingClusterTask = new Runnable() {
      @Override
      public void run() {
        // Double-check that camera is still idle and user is not interacting
        long timeSinceMove = System.currentTimeMillis() - lastCameraMoveTime;
        if (timeSinceMove >= CLUSTER_DELAY_MS && !isUserInteracting) {
          performClustering();
        }
        pendingClusterTask = null;
      }
    };
    
    clusteringHandler.postDelayed(pendingClusterTask, CLUSTER_DELAY_MS);
  }

  /**
   * Perform actual clustering operation (for camera events)
   */
  private void performClustering() {
    performClusteringSynchronously();
  }

  /**
   * Expand bounds by a factor (e.g., 1.2 = 20% larger)
   */
  private LatLngBounds expandBounds(LatLngBounds bounds, double factor) {
    double latDiff = bounds.northeast.latitude - bounds.southwest.latitude;
    double lngDiff = bounds.northeast.longitude - bounds.southwest.longitude;
    
    double latExpansion = latDiff * (factor - 1.0) / 2.0;
    double lngExpansion = lngDiff * (factor - 1.0) / 2.0;
    
    return new LatLngBounds(
        new com.google.android.gms.maps.model.LatLng(
            bounds.southwest.latitude - latExpansion,
            bounds.southwest.longitude - lngExpansion
        ),
        new com.google.android.gms.maps.model.LatLng(
            bounds.northeast.latitude + latExpansion,
            bounds.northeast.longitude + lngExpansion
        )
    );
  }

  /**
   * Filter markers to only those within bounds
   */
  private Collection<MarkerBuilder> filterItemsByBounds(
      Collection<MarkerBuilder> items, 
      LatLngBounds bounds) {
    Collection<MarkerBuilder> visibleItems = new ArrayList<>();
    
    for (MarkerBuilder item : items) {
      com.google.android.gms.maps.model.LatLng position = item.getPosition();
      if (position != null && bounds.contains(position)) {
        visibleItems.add(item);
      }
    }
    
    return visibleItems;
  }

  @Override
  public boolean onClusterClick(Cluster<MarkerBuilder> cluster) {
    if (cluster.getSize() > 0) {
      MarkerBuilder[] builders = cluster.getItems().toArray(new MarkerBuilder[0]);
      String clusterManagerId = builders[0].clusterManagerId();
      flutterApi.onClusterTap(
          Convert.clusterToPigeon(clusterManagerId, cluster), new NoOpVoidResult());
    }
    return false;
  }
  
  /**
   * Cleanup resources
   */
  public void cleanup() {
    cancelPendingClustering();
    clusteringHandler.removeCallbacksAndMessages(null);

    // Shutdown the background filtering executor
    if (filteringExecutor != null && !filteringExecutor.isShutdown()) {
      filteringExecutor.shutdown();
      // Don't wait for termination to avoid blocking - tasks will complete quickly
    }

    for (ClusterManager<MarkerBuilder> clusterManager : clusterManagerIdToManager.values()) {
      if (clusterManager.getRenderer() instanceof ClusterRenderer) {
        ((ClusterRenderer<?>) clusterManager.getRenderer()).cleanup();
      }
      clusterManager.clearItems();
    }

    clusterManagerIdToManager.clear();
    clusterManagerIdToAllItems.clear();
  }

  /**
   * ClusterRenderer with bitmap caching and optimized rendering
   */
  private static class ClusterRenderer<T extends MarkerBuilder> extends DefaultClusterRenderer<T> {
    private final ClusterManagersController clusterManagersController;
    private final Context context;
    
    // Cache cluster icons - increased cache size for better performance
    private final LruCache<Integer, BitmapDescriptor> clusterIconCache;
    private Drawable baseClusterDrawable;
    
    // Pre-load common sizes
    private static final int[] PRELOAD_SIZES = {2, 3, 4, 5, 10, 15, 20, 25, 50, 75, 100, 200, 500, 1000};

    public ClusterRenderer(
        Context context,
        GoogleMap map,
        ClusterManager<T> clusterManager,
        ClusterManagersController clusterManagersController) {
      super(context, map, clusterManager);
      this.context = context;
      this.clusterManagersController = clusterManagersController;
      
      // Initialize cache with capacity for 150 different sizes
      this.clusterIconCache = new LruCache<Integer, BitmapDescriptor>(150) {
        @Override
        protected void entryRemoved(boolean evicted, Integer key, BitmapDescriptor oldValue, BitmapDescriptor newValue) {
          // BitmapDescriptor cleanup handled by Google Maps SDK
        }
      };
      
      // Pre-load common cluster sizes in background
      preloadCommonClusterIcons();
    }
    
    /**
     * Pre-create cluster icons for common sizes
     */
    private void preloadCommonClusterIcons() {
      new Thread(() -> {
        for (int size : PRELOAD_SIZES) {
          getClusterIconWithCount(size);
        }
      }).start();
    }

    @Override
    protected void onBeforeClusterItemRendered(
        @NonNull T item, @NonNull MarkerOptions markerOptions) {
      item.update(markerOptions);
    }

    @Override
    protected void onClusterItemRendered(@NonNull T item, @NonNull Marker marker) {
      super.onClusterItemRendered(item, marker);
      clusterManagersController.onClusterItemRendered(item, marker);
    }

    @Override
    protected void onBeforeClusterRendered(
        @NonNull Cluster<T> cluster, @NonNull MarkerOptions markerOptions) {
      markerOptions.icon(getClusterIconWithCount(cluster.getSize()));
    }

    @Override
    protected void onClusterUpdated(
        @NonNull Cluster<T> cluster,
        @NonNull Marker marker) {
      marker.setIcon(getClusterIconWithCount(cluster.getSize()));
    }
    
    @Override
    public int getColor(int clusterSize) {
      return Color.parseColor("#4285F4"); // Google Blue
    }
    
    /**
     * Get cluster icon with caching
     */
    private BitmapDescriptor getClusterIconWithCount(int count) {
      // Check cache first
      BitmapDescriptor cached = clusterIconCache.get(count);
      if (cached != null) {
        return cached;
      }
      
      // Load base drawable once
      if (baseClusterDrawable == null) {
        baseClusterDrawable = ContextCompat.getDrawable(context, R.drawable.ic_cluster);
        if (baseClusterDrawable == null) {
          // Fallback: create a simple circle drawable programmatically
          return createFallbackClusterIcon(count);
        }
      }
      
      // Create drawable instance
      Drawable drawable = baseClusterDrawable.getConstantState().newDrawable().mutate();
      
      int width = drawable.getIntrinsicWidth();
      int height = drawable.getIntrinsicHeight();
      drawable.setBounds(0, 0, width, height);

      Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
      Canvas canvas = new Canvas(bitmap);

      // Draw background
      drawable.draw(canvas);

      // Draw text
      Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
      textPaint.setColor(Color.parseColor("#FFFAFA"));
      
      String countText = formatClusterCount(count);
      // textPaint.setTextSize(countText.length() > 3 ? 28f : 32f);

      float density = context.getResources().getDisplayMetrics().density;
      float textSizeDp = 12f; // Use dp values
      textPaint.setTextSize(textSizeDp * density);   

      textPaint.setTextAlign(Paint.Align.CENTER);
      textPaint.setFakeBoldText(true);

      Paint.FontMetrics fontMetrics = textPaint.getFontMetrics();
      float x = width / 2f;
      float y = height / 2f - (fontMetrics.ascent + fontMetrics.descent) / 2f;

      canvas.drawText(countText, x, y, textPaint);

      BitmapDescriptor descriptor = BitmapDescriptorFactory.fromBitmap(bitmap);
      
      // Recycle bitmap to free memory
      bitmap.recycle();
      
      // Cache for reuse
      clusterIconCache.put(count, descriptor);
      
      return descriptor;
    }
    
    /**
     * Create a fallback cluster icon if drawable resource is not found
     */
    private BitmapDescriptor createFallbackClusterIcon(int count) {
      int size = 96; // Size in pixels
      Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
      Canvas canvas = new Canvas(bitmap);
      
      // Draw circle background
      Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
      circlePaint.setColor(Color.parseColor("#1E1E1E"));
      circlePaint.setStyle(Paint.Style.FILL);
      canvas.drawCircle(size / 2f, size / 2f, size / 2f, circlePaint);

      // Draw text
      Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
      textPaint.setColor(Color.parseColor("#FFFAFA"));
      String countText = formatClusterCount(count);
      textPaint.setTextSize(countText.length() > 3 ? 28f : 32f);
      textPaint.setTextAlign(Paint.Align.CENTER);
      textPaint.setFakeBoldText(true);
      
      Paint.FontMetrics fontMetrics = textPaint.getFontMetrics();
      float x = size / 2f;
      float y = size / 2f - (fontMetrics.ascent + fontMetrics.descent) / 2f;
      canvas.drawText(countText, x, y, textPaint);
      
      BitmapDescriptor descriptor = BitmapDescriptorFactory.fromBitmap(bitmap);
      bitmap.recycle();
      
      // Cache it
      clusterIconCache.put(count, descriptor);
      
      return descriptor;
    }
    
    /**
     * Format cluster count (you can customize this)
     */
    private String formatClusterCount(int count) {
      return String.valueOf(count);
      // Alternative formatting:
      // if (count < 1000) {
      //   return String.valueOf(count);
      // } else if (count < 10000) {
      //   return String.format("%.1fK", count / 1000.0);
      // } else if (count < 1000000) {
      //   return (count / 1000) + "K";
      // } else {
      //   return String.format("%.1fM", count / 1000000.0);
      // }
    }
    
    /**
     * Cleanup cache
     */
    public void cleanup() {
      clusterIconCache.evictAll();
      baseClusterDrawable = null;
    }
    
    @Override
    protected boolean shouldRenderAsCluster(@NonNull Cluster<T> cluster) {
      return cluster.getSize() >= 2;
    }
  }

  /** Interface for handling when a cluster item is rendered. */
  public interface OnClusterItemRendered<T extends ClusterItem> {
    void onClusterItemRendered(@NonNull T item, @NonNull Marker marker);
  }
}
