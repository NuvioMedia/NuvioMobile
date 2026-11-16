import Foundation
import UIKit
import CoreGraphics
import Libmpv

/// Internal libmpv video output for iOS 15.
///
/// The previous GPU path embeds gpu-next/Vulkan/MoltenVK into a custom
/// CAMetalLayer, which terminated the app when video opened on iOS 15.
/// The libmpv software render API decodes the same MKV/MP4/HEVC/other formats
/// without creating any Vulkan or Metal video output.
///
/// Software rendering has a real performance cost, so we cap the output to
/// 768x432 and 24 FPS on older iPhones. No external player is used.
final class MPVSoftwareVideoRenderer {
    private weak var imageView: UIImageView?
    private let renderQueue = DispatchQueue(label: "nuvio.mpv.software-render", qos: .userInitiated)
    private var context: OpaquePointer?
    private var width = 640
    private var height = 360
    private var stopped = false
    private var lastPresentUptime: TimeInterval = 0

    init(imageView: UIImageView) {
        self.imageView = imageView
    }

    /// Called on main after mpv_initialize, before the first loadfile.
    /// The render API must have a context attached before video starts.
    func start(mpv: OpaquePointer) -> String? {
        var failure: String?
        renderQueue.sync {
            var renderer: OpaquePointer?
            let result = "sw".withCString { type -> CInt in
                var params = [
                    mpv_render_param(type: MPV_RENDER_PARAM_API_TYPE,
                                     data: UnsafeMutableRawPointer(mutating: type)),
                    mpv_render_param(type: MPV_RENDER_PARAM_INVALID, data: nil)
                ]
                return params.withUnsafeMutableBufferPointer {
                    mpv_render_context_create(&renderer, mpv, $0.baseAddress)
                }
            }
            guard result >= 0, let renderer else {
                failure = String(cString: mpv_error_string(result))
                return
            }
            context = renderer
            mpv_render_context_set_update_callback(renderer, { userData in
                guard let userData else { return }
                let owner = Unmanaged<MPVSoftwareVideoRenderer>
                    .fromOpaque(userData).takeUnretainedValue()
                owner.requestDraw()
            }, Unmanaged.passUnretained(self).toOpaque())
        }
        return failure
    }

    /// UIView size (in points), not native display pixels. Pixel count is
    /// intentionally limited because libmpv's software renderer is CPU-bound.
    func resize(to size: CGSize) {
        guard size.width > 1, size.height > 1 else { return }
        let factor = min(1.0, 768.0 / size.width, 432.0 / size.height)
        let targetWidth = max(2, Int(size.width * factor).roundedToEven)
        let targetHeight = max(2, Int(size.height * factor).roundedToEven)
        renderQueue.async { [weak self] in
            guard let self, !self.stopped else { return }
            if self.width != targetWidth || self.height != targetHeight {
                self.width = targetWidth
                self.height = targetHeight
                self.drawFrame(force: true)
            }
        }
    }

    private func requestDraw() {
        renderQueue.async { [weak self] in self?.drawFrame(force: false) }
    }

    /// This method runs only on renderQueue, never the UI thread.
    private func drawFrame(force: Bool) {
        guard !stopped, let context else { return }
        let now = ProcessInfo.processInfo.systemUptime
        if !force && now - lastPresentUptime < (1.0 / 24.0) { return }

        // The software renderer requires a suitably aligned writable buffer.
        let stride = ((width * 4 + 63) / 64) * 64
        let count = stride * height
        var allocated: UnsafeMutableRawPointer?
        guard posix_memalign(&allocated, 64, count) == 0,
              let allocated else { return }
        defer { free(allocated) }

        var dimensions = [Int32(width), Int32(height)]
        var bytesPerRow = stride

        let result: CInt = "rgb0".withCString { format in
            dimensions.withUnsafeMutableBufferPointer { sizeBuffer in
                withUnsafeMutablePointer(to: &bytesPerRow) { stridePointer in
                    var params = [
                        mpv_render_param(type: MPV_RENDER_PARAM_SW_SIZE,
                            data: UnsafeMutableRawPointer(sizeBuffer.baseAddress!)),
                        mpv_render_param(type: MPV_RENDER_PARAM_SW_FORMAT,
                            data: UnsafeMutableRawPointer(mutating: format)),
                        mpv_render_param(type: MPV_RENDER_PARAM_SW_STRIDE,
                            data: UnsafeMutableRawPointer(stridePointer)),
                        mpv_render_param(type: MPV_RENDER_PARAM_SW_POINTER,
                            data: allocated),
                        mpv_render_param(type: MPV_RENDER_PARAM_INVALID, data: nil)
                    ]
                    return params.withUnsafeMutableBufferPointer {
                        mpv_render_context_render(context, $0.baseAddress)
                    }
                }
            }
        }
        guard result >= 0 else {
            NSLog("[Nuvio iOS 15] libmpv software render error: %d", result)
            return
        }
        lastPresentUptime = now

        // Copy before freeing the aligned buffer. UIKit is updated only on
        // the main queue, so no mpv render callback may touch a UIKit view.
        let bytes = Data(bytes: allocated, count: count)
        guard let provider = CGDataProvider(data: bytes as CFData),
              let image = CGImage(
                  width: width, height: height,
                  bitsPerComponent: 8, bitsPerPixel: 32,
                  bytesPerRow: stride, space: CGColorSpaceCreateDeviceRGB(),
                  bitmapInfo: CGBitmapInfo.byteOrder32Big.union(
                      CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue)),
                  provider: provider, decode: nil,
                  shouldInterpolate: true, intent: .defaultIntent
              ) else { return }
        DispatchQueue.main.async { [weak self] in
            self?.imageView?.image = UIImage(cgImage: image)
        }
    }

    /// Must complete before mpv_terminate_destroy; otherwise libmpv's
    /// renderer can access a destroyed core from its update callback.
    func stop() {
        renderQueue.sync {
            stopped = true
            if let context {
                mpv_render_context_set_update_callback(context, nil, nil)
                mpv_render_context_free(context)
                self.context = nil
            }
        }
    }
}

private extension Int {
    var roundedToEven: Int { self & ~1 }
}
