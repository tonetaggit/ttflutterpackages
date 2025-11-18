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
    CGFloat dimension = 40.0; // Reduced from 60 to 40
    CGRect rect = CGRectMake(0, 0, dimension, dimension);

    UIGraphicsBeginImageContextWithOptions(rect.size, NO, 0.0);
    CGContextRef ctx = UIGraphicsGetCurrentContext();

    // Outer circle (low opacity)
    CGContextSetFillColorWithColor(ctx, [self.color colorWithAlphaComponent:0.5].CGColor);
    UIBezierPath *outer = [UIBezierPath bezierPathWithRoundedRect:rect cornerRadius:dimension / 2];
    [outer fill];

    // Middle circle
    CGRect middleRect = CGRectInset(rect, 3, 3); // adjusted proportionally
    UIBezierPath *middle = [UIBezierPath bezierPathWithRoundedRect:middleRect cornerRadius:(dimension - 6) / 2];
    [[self.color colorWithAlphaComponent:0.5] setFill];
    [middle fill];

    // Inner circle
    CGRect innerRect = CGRectInset(rect, 6, 6); // adjusted proportionally
    UIBezierPath *inner = [UIBezierPath bezierPathWithRoundedRect:innerRect cornerRadius:(dimension - 12) / 2];
    [self.color setFill];
    [inner fill];

    // Text (cluster count)
    NSString *text = [NSString stringWithFormat:@"%lu", (unsigned long)size];
    NSMutableParagraphStyle *style = [[NSMutableParagraphStyle alloc] init];
    style.alignment = NSTextAlignmentCenter;

    NSDictionary *attrs = @{
        NSFontAttributeName: [UIFont boldSystemFontOfSize:12], // reduced from 20
        NSForegroundColorAttributeName: [UIColor whiteColor],
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
