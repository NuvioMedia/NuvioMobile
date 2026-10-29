import Foundation
import UIKit
import CoreVideo
import Metal

class MetalLayer: CAMetalLayer {
    weak var pictureInPictureSink: PlayerPictureInPictureController?
    private let pictureInPictureLock = NSLock()
    private var consumesVideo = false
    private var presentationGeneration: UInt = 0
    private var presentationObserver: (generation: UInt, callback: (TimeInterval) -> Void)?

    func observeNextPresentedDrawable(_ callback: @escaping (TimeInterval) -> Void) {
        pictureInPictureLock.lock()
        presentationGeneration &+= 1
        presentationObserver = (presentationGeneration, callback)
        pictureInPictureLock.unlock()
    }

    func cancelPresentationObserver() {
        pictureInPictureLock.lock()
        presentationGeneration &+= 1
        presentationObserver = nil
        pictureInPictureLock.unlock()
    }

    override func nextDrawable() -> CAMetalDrawable? {
        pictureInPictureLock.lock()
        let generation = presentationObserver?.generation
        pictureInPictureLock.unlock()
        // Return the same drawable that MoltenVK would normally acquire.
        let drawable = super.nextDrawable()
        guard let drawable, let generation else { return drawable }
        pictureInPictureLock.lock()
        let observer = presentationObserver?.generation == generation ? presentationObserver : nil
        if observer != nil { presentationObserver = nil }
        pictureInPictureLock.unlock()
        if let observer {
            let notify: (TimeInterval) -> Void = { [weak self] time in
                DispatchQueue.main.async { [weak self] in
                    guard let self else { return }
                    self.pictureInPictureLock.lock()
                    let current = self.presentationGeneration == generation
                    self.pictureInPictureLock.unlock()
                    if current { observer.callback(time) }
                }
            }
#if targetEnvironment(simulator)
            notify(CACurrentMediaTime())
#else
            drawable.addPresentedHandler { notify($0.presentedTime) }
#endif
        }
        return drawable
    }

    @objc var nuvioPiPConsumesVideo: Bool {
        get {
            pictureInPictureLock.lock()
            defer { pictureInPictureLock.unlock() }
            return consumesVideo
        }
        set {
            pictureInPictureLock.lock()
            consumesVideo = newValue
            pictureInPictureLock.unlock()
        }
    }

    @objc func nuvioEnqueuePiPFrame(_ pixelBuffer: CVPixelBuffer, presentationTime: Double, isPictureInPictureFrame: Bool) {
        pictureInPictureSink?.submit(pixelBuffer, pts: presentationTime, isPictureInPictureFrame: isPictureInPictureFrame)
    }

    override var drawableSize: CGSize {
        get { return super.drawableSize }
        set {
            if Int(newValue.width) > 1 && Int(newValue.height) > 1 {
                super.drawableSize = newValue
            }
        }
    }

    override var wantsExtendedDynamicRangeContent: Bool {
        get { return super.wantsExtendedDynamicRangeContent }
        set {
            if Thread.isMainThread {
                super.wantsExtendedDynamicRangeContent = newValue
            } else {
                // mpv's vo thread sets this during video-output init while it holds the
                // core lock; a sync hop here deadlocks against main-thread property reads.
                DispatchQueue.main.async {
                    super.wantsExtendedDynamicRangeContent = newValue
                }
            }
        }
    }
}
