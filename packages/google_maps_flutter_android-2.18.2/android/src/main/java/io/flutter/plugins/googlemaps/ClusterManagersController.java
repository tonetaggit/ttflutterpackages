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
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.maps.android.clustering.Cluster;
import com.google.maps.android.clustering.ClusterItem;
import com.google.maps.android.clustering.ClusterManager;
import com.google.maps.android.clustering.view.DefaultClusterRenderer;
import com.google.maps.android.collections.MarkerManager;
import io.flutter.plugins.googlemaps.Messages.MapsCallbackApi;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import android.graphics.Paint;
import android.graphics.Color;
/**
 * Controls cluster managers and exposes interfaces for adding and removing cluster items for
 * specific cluster managers.
 */
class ClusterManagersController
    implements GoogleMap.OnCameraIdleListener,
        ClusterManager.OnClusterClickListener<MarkerBuilder> {
  @NonNull private final Context context;
  @NonNull private final HashMap<String, ClusterManager<MarkerBuilder>> clusterManagerIdToManager;
  @NonNull private final MapsCallbackApi flutterApi;
  @Nullable private MarkerManager markerManager;
  @Nullable private GoogleMap googleMap;

  @Nullable
  private ClusterManager.OnClusterItemClickListener<MarkerBuilder> clusterItemClickListener;

  @Nullable
  private ClusterManagersController.OnClusterItemRendered<MarkerBuilder>
      clusterItemRenderedListener;

  ClusterManagersController(@NonNull MapsCallbackApi flutterApi, Context context) {
    this.clusterManagerIdToManager = new HashMap<>();
    this.context = context;
    this.flutterApi = flutterApi;
  }

  void init(GoogleMap googleMap, MarkerManager markerManager) {
    this.markerManager = markerManager;
    this.googleMap = googleMap;
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

  /** Adds new ClusterManager to the controller. */
  void addClusterManager(String clusterManagerId) {
    ClusterManager<MarkerBuilder> clusterManager =
        new ClusterManager<MarkerBuilder>(context, googleMap, markerManager);
    ClusterRenderer<MarkerBuilder> clusterRenderer =
        new ClusterRenderer<MarkerBuilder>(context, googleMap, clusterManager, this);
    clusterManager.setRenderer(clusterRenderer);
    initListenersForClusterManager(clusterManager, this, clusterItemClickListener);
    clusterManagerIdToManager.put(clusterManagerId, clusterManager);
  }

  /** Removes ClusterManagers by given cluster manager IDs from the controller. */
  public void removeClusterManagers(@NonNull List<String> clusterManagerIdsToRemove) {
    for (String clusterManagerId : clusterManagerIdsToRemove) {
      removeClusterManager(clusterManagerId);
    }
  }

  /**
   * Removes the ClusterManagers by the given cluster manager ID from the controller. The reference
   * to this cluster manager is removed from the clusterManagerIdToManager and it will be garbage
   * collected later.
   */
  private void removeClusterManager(Object clusterManagerId) {
    final ClusterManager<MarkerBuilder> clusterManager =
        clusterManagerIdToManager.remove(clusterManagerId);
    if (clusterManager == null) {
      return;
    }
    initListenersForClusterManager(clusterManager, null, null);
    clusterManager.clearItems();
    clusterManager.cluster();
  }

  /** Adds item to the ClusterManager it belongs to. */
  public void addItem(MarkerBuilder item) {
    ClusterManager<MarkerBuilder> clusterManager =
        clusterManagerIdToManager.get(item.clusterManagerId());
    if (clusterManager != null) {
      clusterManager.addItem(item);
      clusterManager.cluster();
    }
  }

  /** Removes item from the ClusterManager it belongs to. */
  public void removeItem(MarkerBuilder item) {
    ClusterManager<MarkerBuilder> clusterManager =
        clusterManagerIdToManager.get(item.clusterManagerId());
    if (clusterManager != null) {
      clusterManager.removeItem(item);
      clusterManager.cluster();
    }
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

  @Override
  public void onCameraIdle() {
    for (Map.Entry<String, ClusterManager<MarkerBuilder>> entry :
        clusterManagerIdToManager.entrySet()) {
      entry.getValue().onCameraIdle();
    }
  }

  @Override
  public boolean onClusterClick(Cluster<MarkerBuilder> cluster) {
    if (cluster.getSize() > 0) {
      MarkerBuilder[] builders = cluster.getItems().toArray(new MarkerBuilder[0]);
      String clusterManagerId = builders[0].clusterManagerId();
      flutterApi.onClusterTap(
          Convert.clusterToPigeon(clusterManagerId, cluster), new NoOpVoidResult());
    }
    return false; // let default zoom behavior happen
  }

  /**
   * ClusterRenderer builds marker options for new markers to be rendered to the map.
   */
  private static class ClusterRenderer<T extends MarkerBuilder> extends DefaultClusterRenderer<T> {
    private final ClusterManagersController clusterManagersController;
    private final Context context;

    public ClusterRenderer(
        Context context,
        GoogleMap map,
        ClusterManager<T> clusterManager,
        ClusterManagersController clusterManagersController) {
      super(context, map, clusterManager);
      this.context = context;
      this.clusterManagersController = clusterManagersController;
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
        // Called when cluster needs updating — force update to your icon
        //  markerOptions.icon(getClusterIconWithCount(cluster.getSize()));
             marker.setIcon(getClusterIconWithCount(cluster.getSize())); // ✅ correct



    }

    private BitmapDescriptor getClusterIcon() {
      Drawable vectorDrawable = ContextCompat.getDrawable(context, R.drawable.ic_cluster);
      if (vectorDrawable == null) {
        throw new IllegalStateException("Cluster icon drawable not found!");
      }

      int width = vectorDrawable.getIntrinsicWidth();
      int height = vectorDrawable.getIntrinsicHeight();
      vectorDrawable.setBounds(0, 0, width, height);

      Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
      Canvas canvas = new Canvas(bitmap);
      vectorDrawable.draw(canvas);

      return BitmapDescriptorFactory.fromBitmap(bitmap);
    }
    private BitmapDescriptor getClusterIconWithCount(int count) {
    Drawable vectorDrawable = ContextCompat.getDrawable(context, R.drawable.ic_cluster);
    if (vectorDrawable == null) {
        throw new IllegalStateException("Cluster icon drawable not found!");
    }

    int width = vectorDrawable.getIntrinsicWidth();
    int height = vectorDrawable.getIntrinsicHeight();
    vectorDrawable.setBounds(0, 0, width, height);

    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);

    // Draw the vector (your SVG background)
    vectorDrawable.draw(canvas);

    // Now draw the count number
    Paint textPaint = new Paint();
    textPaint.setColor(Color.WHITE);
    textPaint.setTextSize(32f);
    textPaint.setAntiAlias(true);
    textPaint.setTextAlign(Paint.Align.CENTER);

    Paint.FontMetrics fontMetrics = textPaint.getFontMetrics();
    float x = width / 2f;
    float y = height / 2f - (fontMetrics.ascent + fontMetrics.descent) / 2f;

    canvas.drawText(String.valueOf(count), x, y, textPaint);

    return BitmapDescriptorFactory.fromBitmap(bitmap);
}
  }

  /** Interface for handling when a cluster item is rendered. */
  public interface OnClusterItemRendered<T extends ClusterItem> {
    void onClusterItemRendered(@NonNull T item, @NonNull Marker marker);
  }
}
