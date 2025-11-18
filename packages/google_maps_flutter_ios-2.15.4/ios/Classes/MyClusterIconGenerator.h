#import <Foundation/Foundation.h>

#import <UIKit/UIKit.h>
#import <GoogleMaps/GoogleMaps.h>
@import GoogleMapsUtils;

@interface MyClusterIconGenerator : NSObject<GMUClusterIconGenerator>

- (instancetype)initWithColor:(UIColor *)color;

@end