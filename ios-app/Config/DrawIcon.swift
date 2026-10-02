// SPDX-License-Identifier: AGPL-3.0-only
// Reproduce the original iOS port vector icon: swift Config/DrawIcon.swift
import AppKit

let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 1024, pixelsHigh: 1024,
                             bitsPerSample: 8, samplesPerPixel: 3, hasAlpha: false, isPlanar: false,
                             colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
NSColor(calibratedRed: 0.09, green: 0.49, blue: 0.39, alpha: 1).setFill()
NSBezierPath(rect: NSRect(x: 0, y: 0, width: 1024, height: 1024)).fill()
let leaf = NSBezierPath()
leaf.move(to: NSPoint(x: 295, y: 325))
leaf.curve(to: NSPoint(x: 759, y: 761), controlPoint1: NSPoint(x: 235, y: 645), controlPoint2: NSPoint(x: 450, y: 808))
leaf.curve(to: NSPoint(x: 295, y: 325), controlPoint1: NSPoint(x: 832, y: 420), controlPoint2: NSPoint(x: 555, y: 264))
NSColor.white.setFill(); leaf.fill()
let stem = NSBezierPath()
stem.move(to: NSPoint(x: 271, y: 263))
stem.curve(to: NSPoint(x: 683, y: 666), controlPoint1: NSPoint(x: 355, y: 445), controlPoint2: NSPoint(x: 523, y: 572))
stem.lineWidth = 28; stem.lineCapStyle = .round
NSColor(calibratedRed: 0.09, green: 0.49, blue: 0.39, alpha: 1).setStroke(); stem.stroke()
NSGraphicsContext.restoreGraphicsState()
guard let png = bitmap.representation(using: .png, properties: [:]) else { fatalError("Icon rendering failed") }
try png.write(to: URL(fileURLWithPath: "App/Assets.xcassets/AppIcon.appiconset/AppIcon.png"))
