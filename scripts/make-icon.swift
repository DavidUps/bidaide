// Renders the macOS app icon from packaging/icon/glyph.svg (the Bidaide logo, shared with the IDE plugin).
// Usage: swift scripts/make-icon.swift  ->  App/Assets.xcassets/AppIcon.appiconset
import AppKit

let root = URL(fileURLWithPath: CommandLine.arguments[0]).deletingLastPathComponent().deletingLastPathComponent()
let glyph = NSImage(contentsOf: root.appendingPathComponent("packaging/icon/glyph.svg"))!
let output = root.appendingPathComponent("App/Assets.xcassets/AppIcon.appiconset")
try? FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)

func render(_ pixels: Int) -> Data {
    let size = CGFloat(pixels)
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: pixels, pixelsHigh: pixels, bitsPerSample: 8,
                               samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB,
                               bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)

    // Apple's grid: an 824pt rounded square centered in 1024pt, with a soft drop shadow.
    let scale = size / 1024
    let card = NSRect(x: 100 * scale, y: 100 * scale, width: 824 * scale, height: 824 * scale)
    let shape = NSBezierPath(roundedRect: card, xRadius: 185 * scale, yRadius: 185 * scale)
    NSGraphicsContext.saveGraphicsState()
    let shadow = NSShadow()
    shadow.shadowColor = NSColor.black.withAlphaComponent(0.35)
    shadow.shadowBlurRadius = 20 * scale
    shadow.shadowOffset = NSSize(width: 0, height: -8 * scale)
    shadow.set()
    NSColor(srgbRed: 0.05, green: 0.07, blue: 0.09, alpha: 1).setFill()
    shape.fill()
    NSGraphicsContext.restoreGraphicsState()

    NSGradient(colors: [NSColor(srgbRed: 0.16, green: 0.18, blue: 0.24, alpha: 1),
                        NSColor(srgbRed: 0.05, green: 0.07, blue: 0.09, alpha: 1)])!
        .draw(in: shape, angle: -90)
    NSColor.white.withAlphaComponent(0.08).setStroke()
    shape.lineWidth = 2 * scale
    shape.stroke()

    let inset = card.insetBy(dx: 110 * scale, dy: 110 * scale)
    glyph.draw(in: inset)

    NSGraphicsContext.restoreGraphicsState()
    return rep.representation(using: .png, properties: [:])!
}

var images: [[String: String]] = []
for points in [16, 32, 128, 256, 512] {
    for factor in [1, 2] {
        let name = "icon_\(points)x\(points)\(factor == 2 ? "@2x" : "").png"
        try render(points * factor).write(to: output.appendingPathComponent(name))
        images.append(["idiom": "mac", "size": "\(points)x\(points)", "scale": "\(factor)x", "filename": name])
    }
}
let contents: [String: Any] = ["images": images, "info": ["author": "xcode", "version": 1]]
try JSONSerialization.data(withJSONObject: contents, options: [.prettyPrinted, .sortedKeys])
    .write(to: output.appendingPathComponent("Contents.json"))
try JSONSerialization.data(withJSONObject: ["info": ["author": "xcode", "version": 1]], options: .prettyPrinted)
    .write(to: output.deletingLastPathComponent().appendingPathComponent("Contents.json"))
print(output.path)
