#import "FGMClusterManagersController.h"

#import "FGMMarkerUserData.h"
#import "FLTGoogleMapJSONConversions.h"
#import "MyClusterIconGenerator.h"

@interface FGMClusterManagersController ()

/// A dictionary mapping unique cluster manager identifiers to their corresponding cluster managers.
@property(strong, nonatomic)
    NSMutableDictionary<NSString *, GMUClusterManager *> *clusterManagerIdentifierToManagers;

/// The callback handler interface for calls to Flutter.
@property(strong, nonatomic) FGMMapsCallbackApi *callbackHandler;

/// The current map instance on which the cluster managers are operating.
@property(strong, nonatomic) GMSMapView *mapView;

// CHANGE 1: Added flag to track clustering operations
@property(atomic, assign) BOOL isClusteringInProgress;

// CHANGE 2: Added dispatch queue for thread-safe clustering operations
@property(strong, nonatomic) dispatch_queue_t clusterQueue;

@end

@implementation FGMClusterManagersController

- (instancetype)initWithMapView:(GMSMapView *)mapView
                callbackHandler:(FGMMapsCallbackApi *)callbackHandler {
  self = [super init];
  if (self) {
    _callbackHandler = callbackHandler;
    _mapView = mapView;
    _clusterManagerIdentifierToManagers = [[NSMutableDictionary alloc] init];
    
    // CHANGE 3: Initialize clustering state and queue
    _isClusteringInProgress = NO;
    _clusterQueue = dispatch_queue_create("com.google.maps.cluster.queue", DISPATCH_QUEUE_SERIAL);
    
    // NSLog(@"[FGM] ✅ ClusterManagersController initialized");
  }
  return self;
}

- (void)addClusterManagers:(NSArray<FGMPlatformClusterManager *> *)clusterManagersToAdd {
  for (FGMPlatformClusterManager *clusterManager in clusterManagersToAdd) {
    NSString *identifier = clusterManager.identifier;
    [self addClusterManager:identifier];
  }
}

- (void)addClusterManager:(NSString *)identifier {
  // CHANGE 4: Increased cluster distance for better stability
  // Lower values = more aggressive clustering, higher = more individual markers
  id<GMUClusterAlgorithm> algorithm =
    [[GMUNonHierarchicalDistanceBasedAlgorithm alloc] initWithClusterDistancePoints:120];

  // Fixed color for all clusters #00AF9E
  UIColor *fixedColor = [UIColor colorWithRed:0.0 green:0.68 blue:0.62 alpha:1.0];
  id<GMUClusterIconGenerator> iconGenerator = [[MyClusterIconGenerator alloc] initWithColor:fixedColor];

  id<GMUClusterRenderer> renderer =
      [[GMUDefaultClusterRenderer alloc] initWithMapView:self.mapView
                                    clusterIconGenerator:iconGenerator];

  GMUClusterManager *clusterManager = 
      [[GMUClusterManager alloc] initWithMap:self.mapView algorithm:algorithm renderer:renderer];
  
  self.clusterManagerIdentifierToManagers[identifier] = clusterManager;
  
  // NSLog(@"[FGM] ✅ Added cluster manager: %@", identifier);
}

- (void)removeClusterManagersWithIdentifiers:(NSArray<NSString *> *)identifiers {
  // CHANGE 5: Wait for any ongoing clustering to complete before removing
  dispatch_sync(_clusterQueue, ^{
    for (NSString *identifier in identifiers) {
      GMUClusterManager *clusterManager =
          [self.clusterManagerIdentifierToManagers objectForKey:identifier];
      if (!clusterManager) {
        continue;
      }
      
      // CHANGE 6: Clear items on main thread to avoid conflicts
      dispatch_async(dispatch_get_main_queue(), ^{
        [clusterManager clearItems];
      });
      
      [self.clusterManagerIdentifierToManagers removeObjectForKey:identifier];
      // NSLog(@"[FGM] ✅ Removed cluster manager: %@", identifier);
    }
  });
}

- (nullable GMUClusterManager *)clusterManagerWithIdentifier:(NSString *)identifier {
  return [self.clusterManagerIdentifierToManagers objectForKey:identifier];
}

- (void)invokeClusteringForEachClusterManager {
  // CHANGE 7: Prevent concurrent clustering operations
  if (self.isClusteringInProgress) {
    // NSLog(@"[FGM] Clustering already in progress, skipping...");
    return;
  }
  
  self.isClusteringInProgress = YES;
  
  // CHANGE 8: Use serial queue for safe clustering
  dispatch_async(_clusterQueue, ^{
    @try {
      // NSLog(@"[FGM]  Starting clustering for %lu managers", 
      //       (unsigned long)self.clusterManagerIdentifierToManagers.count);
      
      // CHANGE 9: Add small delay between cluster manager updates
      for (GMUClusterManager *clusterManager in [self.clusterManagerIdentifierToManagers allValues]) {
        @try {
          // Cluster on main thread as GMUClusterManager expects
          dispatch_sync(dispatch_get_main_queue(), ^{
            [clusterManager cluster];
          });
          
          // Small delay to prevent overwhelming the clustering algorithm
          [NSThread sleepForTimeInterval:0.05]; // 50ms
          
        } @catch (NSException *exception) {
          // NSLog(@"[FGM] ❌ Clustering failed for manager: %@", exception.reason);
        }
      }
      
      // NSLog(@"[FGM] ✅ Clustering completed");
      
    } @catch (NSException *exception) {
      // NSLog(@"[FGM] ❌ Clustering error: %@", exception.reason);
      
    } @finally {
      self.isClusteringInProgress = NO;
    }
  });
}

- (nullable NSArray<FGMPlatformCluster *> *)
    clustersWithIdentifier:(NSString *)identifier
                     error:(FlutterError *_Nullable __autoreleasing *_Nonnull)error {
  GMUClusterManager *clusterManager =
      [self.clusterManagerIdentifierToManagers objectForKey:identifier];

  if (!clusterManager) {
    *error = [FlutterError
        errorWithCode:@"Invalid clusterManagerId"
              message:@"getClusters called with invalid clusterManagerId"
              details:[NSString stringWithFormat:@"clusterManagerId was: '%@'", identifier]];
    return nil;
  }

  // CHANGE 10: Added try-catch for safer cluster retrieval
  @try {
    NSUInteger integralZoom = (NSUInteger)floorf(_mapView.camera.zoom + 0.5f);
    
    // CHANGE 11: Validate zoom level is reasonable
    if (integralZoom > 21) {
      // NSLog(@"[FGM] ⚠️ Zoom level too high: %lu, clamping to 21", (unsigned long)integralZoom);
      integralZoom = 21;
    }
    
    // NSLog(@"[FGM] 📊 Getting clusters at zoom level: %lu", (unsigned long)integralZoom);
    
    NSArray<id<GMUCluster>> *clusters = [clusterManager.algorithm clustersAtZoom:integralZoom];
    
    // CHANGE 12: Validate cluster results
    if (!clusters) {
      // NSLog(@"[FGM] ⚠️ Clustering returned nil, returning empty array");
      return @[];
    }
    
    // NSLog(@"[FGM] ✅ Retrieved %lu clusters", (unsigned long)clusters.count);
    
    NSMutableArray<FGMPlatformCluster *> *response =
        [[NSMutableArray alloc] initWithCapacity:clusters.count];
    
    for (id<GMUCluster> cluster in clusters) {
      @try {
        FGMPlatformCluster *platFormCluster = FGMGetPigeonCluster(cluster, identifier);
        if (platFormCluster) {
          [response addObject:platFormCluster];
        }
      } @catch (NSException *exception) {
        // NSLog(@"[FGM] ⚠️ Failed to convert cluster: %@", exception.reason);
      }
    }
    
    return response;
    
  } @catch (NSException *exception) {
    // NSLog(@"[FGM] ❌ Error getting clusters: %@", exception.reason);
    
    *error = [FlutterError
        errorWithCode:@"ClusteringError"
              message:@"Failed to retrieve clusters"
              details:exception.reason];
    return nil;
  }
}

- (void)didTapCluster:(GMUStaticCluster *)cluster {
  // CHANGE 13: Added nil check and validation
  if (!cluster || cluster.items.count == 0) {
    // NSLog(@"[FGM] ⚠️ Tapped cluster is nil or empty");
    return;
  }
  
  NSString *clusterManagerId = [self clusterManagerIdentifierForCluster:cluster];
  if (!clusterManagerId) {
    // NSLog(@"[FGM] ⚠️ Could not find cluster manager for tapped cluster");
    return;
  }
  
  @try {
    FGMPlatformCluster *platFormCluster = FGMGetPigeonCluster(cluster, clusterManagerId);
    
    if (!platFormCluster) {
      // NSLog(@"[FGM] ⚠️ Failed to create platform cluster");
      return;
    }
    
    [self.callbackHandler didTapCluster:platFormCluster
                             completion:^(FlutterError *_Nullable error){
                               if (error) {
                                //  NSLog(@"[FGM] ⚠️ Cluster tap callback error: %@", error.message);
                               }
                             }];
  } @catch (NSException *exception) {
    // NSLog(@"[FGM] ❌ Error handling cluster tap: %@", exception.reason);
  }
}

#pragma mark - Private methods

/// Returns the cluster manager identifier for given cluster.
- (nullable NSString *)clusterManagerIdentifierForCluster:(GMUStaticCluster *)cluster {
  // CHANGE 14: Added more validation
  if (!cluster || !cluster.items || cluster.items.count == 0) {
    // NSLog(@"[FGM] ⚠️ Cannot get identifier from empty cluster");
    return nil;
  }
  
  id firstItem = cluster.items.firstObject;
  
  if ([firstItem isKindOfClass:[GMSMarker class]]) {
    GMSMarker *firstMarker = (GMSMarker *)firstItem;
    NSString *identifier = FGMGetClusterManagerIdentifierFromMarker(firstMarker);
    
    if (!identifier) {
      // NSLog(@"[FGM] ⚠️ Marker missing cluster manager identifier");
    }
    
    return identifier;
  }
  
  // NSLog(@"[FGM] ⚠️ Cluster item is not a GMSMarker: %@", NSStringFromClass([firstItem class]));
  return nil;
}

// CHANGE 15: Added cleanup method
- (void)dealloc {
  // NSLog(@"[FGM] 🧹 Cleaning up ClusterManagersController");
  
  // Clear all cluster managers
  for (GMUClusterManager *manager in [self.clusterManagerIdentifierToManagers allValues]) {
    [manager clearItems];
  }
  
  [self.clusterManagerIdentifierToManagers removeAllObjects];
}

@end