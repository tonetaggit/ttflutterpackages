#import "MyClusterIconGenerator.h"

@interface MyClusterIconGenerator ()
@property(nonatomic, strong) UIColor *color;
@end

@implementation MyClusterIconGenerator

// Implement the initializer declared in the header
- (instancetype)initWithColor:(UIColor *)color {
    self = [super init];
    if (self) {
        _color = color;
    }
    return self;
}

// Your existing icon drawing method
- (UIImage *)iconForSize:(NSUInteger)size {
    CGFloat dimension = 38.0;
    CGRect rect = CGRectMake(0, 0, dimension, dimension);

    UIGraphicsBeginImageContextWithOptions(rect.size, NO, 0.0);
    CGContextRef ctx = UIGraphicsGetCurrentContext();

    // Single dark circle background #1E1E1E
    UIColor *bgColor = [UIColor colorWithRed:30.0/255.0 green:30.0/255.0 blue:30.0/255.0 alpha:1.0];
    CGContextSetFillColorWithColor(ctx, bgColor.CGColor);
    UIBezierPath *circle = [UIBezierPath bezierPathWithRoundedRect:rect cornerRadius:dimension / 2];
    [circle fill];

    // Text (cluster count) in #FFFAFA
    NSString *text = [NSString stringWithFormat:@"%lu", (unsigned long)size];
    NSMutableParagraphStyle *style = [[NSMutableParagraphStyle alloc] init];
    style.alignment = NSTextAlignmentCenter;

    UIColor *textColor = [UIColor colorWithRed:255.0/255.0 green:250.0/255.0 blue:250.0/255.0 alpha:1.0];
    NSDictionary *attrs = @{
        NSFontAttributeName: [UIFont boldSystemFontOfSize:12],
        NSForegroundColorAttributeName: textColor,
        NSParagraphStyleAttributeName: style
    };

    CGSize textSize = [text sizeWithAttributes:attrs];
    CGRect textRect = CGRectMake(
        (dimension - textSize.width) / 2,
        (dimension - textSize.height) / 2,
        textSize.width,
        textSize.height
    );
    [text drawInRect:textRect withAttributes:attrs];

    UIImage *icon = UIGraphicsGetImageFromCurrentImageContext();
    UIGraphicsEndImageContext();

    return icon;
}


@end
