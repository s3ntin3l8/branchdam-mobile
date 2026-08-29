import SwiftUI

public struct BrandMonogramView: View {
    public var size: CGFloat
    public var brandColor: Color

    public init(size: CGFloat = 64, brandColor: Color = Color(red: 43/255, green: 166/255, blue: 154/255)) {
        self.size = size
        self.brandColor = brandColor
    }

    public var body: some View {
        Canvas { context, canvasSize in
            let scale = canvasSize.width / 64.0

            // 1. Stem: rect x=6, y=5, width=9, height=54, rx=4.5
            let stemRect = CGRect(x: 6 * scale, y: 5 * scale, width: 9 * scale, height: 54 * scale)
            let stemPath = Path(roundedRect: stemRect, cornerRadius: 4.5 * scale)
            context.fill(stemPath, with: .color(brandColor))

            // 2. Main loop: circle cx=28, cy=40, r=14.5, stroke=7.5
            let loopCenter = CGPoint(x: 28 * scale, y: 40 * scale)
            let loopRect = CGRect(x: (28 - 14.5) * scale, y: (40 - 14.5) * scale, width: 29 * scale, height: 29 * scale)
            let loopPath = Path(ellipseIn: loopRect)
            context.stroke(loopPath, with: .color(brandColor), lineWidth: 7.5 * scale)

            // 3. Connector line: M43 40 H48, width=6, round caps
            var linePath = Path()
            linePath.move(to: CGPoint(x: 43 * scale, y: 40 * scale))
            linePath.addLine(to: CGPoint(x: 48 * scale, y: 40 * scale))
            context.stroke(linePath, with: .color(brandColor), style: StrokeStyle(lineWidth: 6 * scale, lineCap: .round))

            // 4. Child node: circle cx=54, cy=40, r=6
            let nodeRect = CGRect(x: (54 - 6) * scale, y: (40 - 6) * scale, width: 12 * scale, height: 12 * scale)
            let nodePath = Path(ellipseIn: nodeRect)
            context.fill(nodePath, with: .color(brandColor))
        }
        .frame(width: size, height: size)
    }
}

#Preview {
    BrandMonogramView(size: 128)
        .padding()
        .background(Color(red: 15/255, green: 23/255, blue: 42/255))
}
