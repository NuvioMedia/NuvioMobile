import AVKit
import CoreMedia

let playerSceneDidActivateNotification = Notification.Name("NuvioPlayerSceneDidActivate")

/// PiP presents MPV's existing VideoToolbox buffers; MPV remains the audio and playback clock owner.
final class PlayerPictureInPictureController: NSObject,
    AVPictureInPictureControllerDelegate, AVPictureInPictureSampleBufferPlaybackDelegate {
    let displayLayer: AVSampleBufferDisplayLayer
    private weak var owner: MPVPlayerViewController?
    private weak var metalLayer: MetalLayer?
    private var controller: AVPictureInPictureController?
    private var sceneActivationObserver: NSObjectProtocol?
    private let frameQueue: DispatchQueue
    private let prepareFrameRenderer: () -> Bool
    private let enqueueFrame: (CMSampleBuffer) -> Void
    private let clearFrameRenderer: () -> Void
    private let frameLock = NSLock()
    private var pendingFrame: (CVPixelBuffer, Double)?
    private var drainScheduled = false
    private var acceptingFrames = true
    private var feedingRestoreTransition = false
    private var lastPrimeTime: TimeInterval = 0
    private var hasReceivedFrame = false
    private var receivedFrame: Bool {
        frameLock.lock()
        defer { frameLock.unlock() }
        return hasReceivedFrame
    }
    private var timebase: CMTimebase?
    private var cachedFormat: CMVideoFormatDescription?
    private(set) var isStarting = false
    private(set) var isActive = false
    private var isBackground = false
    private var hasPresentedPictureInPicture = false
    private var handoffGeneration: UInt = 0
    private var isForegroundActive = true
    private var shouldReturnToInline = false
    private var stopRequested = false
    private var waitingForInlinePresentation = false
    private var restoringInline = false
    private var inlinePresentationPrepared = false

    private func cancelInlinePresentationWait() {
        waitingForInlinePresentation = false
        restoringInline = false
        inlinePresentationPrepared = false
        frameLock.lock()
        feedingRestoreTransition = false
        frameLock.unlock()
        metalLayer?.cancelPresentationObserver()
    }

    private func prepareInlinePresentation() {
        guard !inlinePresentationPrepared, !isBackground,
              UIApplication.shared.applicationState == .active,
              owner?.viewIfLoaded?.window?.windowScene?.activationState == .foregroundActive,
              owner?.isPlayerPlaying == true, owner?.isPlayerLoading == false,
              let metalLayer else { return }
        inlinePresentationPrepared = true
        waitingForInlinePresentation = true
        // AVKit still needs the existing buffers until its stop animation finishes.
        frameLock.lock()
        feedingRestoreTransition = true
        frameLock.unlock()
        metalLayer.observeNextPresentedDrawable { [weak self] _ in
            guard let self, self.waitingForInlinePresentation, !self.isBackground else { return }
            self.waitingForInlinePresentation = false
            // Keep inline video covered until AVKit's stop animation also finishes.
            if !self.isActive, !self.isStarting {
                self.owner?.setPictureInPicturePlaceholderVisible(false)
            }
        }
        // Arm presentation feedback before allowing MPV's original Metal output.
        metalLayer.nuvioPiPConsumesVideo = false
    }

    var keepsPlaybackAlive: Bool { isStarting || isActive }

    init(owner: MPVPlayerViewController, metalLayer: MetalLayer) {
        let layer = AVSampleBufferDisplayLayer()
        self.displayLayer = layer
        self.owner = owner
        self.metalLayer = metalLayer
        if #available(iOS 17.0, *) {
            let renderer = layer.sampleBufferRenderer
            frameQueue = DispatchQueue(label: "com.nuvio.pip.frames", qos: .userInitiated)
            prepareFrameRenderer = {
                if renderer.status == .failed { renderer.flush() }
                return renderer.isReadyForMoreMediaData
            }
            enqueueFrame = { renderer.enqueue($0) }
            clearFrameRenderer = { renderer.flush(removingDisplayedImage: true, completionHandler: nil) }
        } else {
            // AVSampleBufferDisplayLayer itself remains on the main queue on iOS 16.
            frameQueue = .main
            prepareFrameRenderer = {
                if layer.status == .failed { layer.flush() }
                return layer.isReadyForMoreMediaData
            }
            enqueueFrame = { layer.enqueue($0) }
            clearFrameRenderer = { layer.flushAndRemoveImage() }
        }
        super.init()
        displayLayer.videoGravity = .resizeAspect
        var clock: CMTimebase?
        CMTimebaseCreateWithSourceClock(allocator: kCFAllocatorDefault,
                                       sourceClock: CMClockGetHostTimeClock(), timebaseOut: &clock)
        timebase = clock
        displayLayer.controlTimebase = clock
        guard AVPictureInPictureController.isPictureInPictureSupported() else { return }
        controller = AVPictureInPictureController(contentSource: .init(
            sampleBufferDisplayLayer: displayLayer, playbackDelegate: self))
        controller?.delegate = self
        controller?.canStartPictureInPictureAutomaticallyFromInline = true
        sceneActivationObserver = NotificationCenter.default.addObserver(
            forName: playerSceneDidActivateNotification, object: nil, queue: .main
        ) { [weak self] _ in
            self?.becameActive()
        }
        metalLayer.pictureInPictureSink = self
    }

    deinit {
        if let sceneActivationObserver {
            NotificationCenter.default.removeObserver(sceneActivationObserver)
        }
    }

    /// Called on MPV's VO thread. Keep a single latest buffer and never wait for the main thread.
    func submit(_ buffer: CVPixelBuffer, pts: Double, isPictureInPictureFrame: Bool) {
        frameLock.lock()
        guard acceptingFrames else { frameLock.unlock(); return }
        let now = ProcessInfo.processInfo.systemUptime
        if !isPictureInPictureFrame && !feedingRestoreTransition && now - lastPrimeTime < 0.5 {
            frameLock.unlock()
            return
        }
        lastPrimeTime = now
        pendingFrame = (buffer, pts)
        let schedule = !drainScheduled
        drainScheduled = true
        frameLock.unlock()
        if schedule {
            frameQueue.async { [weak self] in self?.drainFrame() }
        }
    }

    private func drainFrame() {
        frameLock.lock()
        let frame = pendingFrame
        pendingFrame = nil
        drainScheduled = false
        let accepts = acceptingFrames
        frameLock.unlock()
        guard accepts, let (buffer, pts) = frame, pts.isFinite else { return }
        guard prepareFrameRenderer() else { return }
        if cachedFormat.map({ CMVideoFormatDescriptionMatchesImageBuffer($0, imageBuffer: buffer) }) != true {
            var format: CMVideoFormatDescription?
            guard CMVideoFormatDescriptionCreateForImageBuffer(allocator: kCFAllocatorDefault,
                imageBuffer: buffer, formatDescriptionOut: &format) == noErr else { return }
            cachedFormat = format
        }
        guard let format = cachedFormat else { return }
        var timing = CMSampleTimingInfo(duration: .invalid,
            presentationTimeStamp: CMTime(seconds: pts, preferredTimescale: 60000), decodeTimeStamp: .invalid)
        var sample: CMSampleBuffer?
        guard CMSampleBufferCreateReadyWithImageBuffer(allocator: kCFAllocatorDefault,
            imageBuffer: buffer, formatDescription: format, sampleTiming: &timing,
            sampleBufferOut: &sample) == noErr, let sample else { return }
        // The VO already scheduled this frame against MPV's audio clock.
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true) as? [NSMutableDictionary] {
            attachments.first?[kCMSampleAttachmentKey_DisplayImmediately] = true
        }
        frameLock.lock()
        let firstFrame = !hasReceivedFrame
        hasReceivedFrame = true
        frameLock.unlock()
        if firstFrame {
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.frameLock.lock()
                let accepts = self.acceptingFrames
                self.frameLock.unlock()
                if accepts { self.updatePlaybackState() }
            }
        }
        enqueueFrame(sample)
    }

    func updatePlaybackState() {
        if waitingForInlinePresentation,
           owner?.isPlayerPlaying != true || owner?.isPlayerLoading == true {
            cancelInlinePresentationWait()
            owner?.setPictureInPicturePlaceholderVisible(false)
        }
        guard let owner, let timebase else { return }
        let position = Double(owner.positionMs) / 1000
        let now = CMTimeGetSeconds(CMTimebaseGetTime(timebase))
        if !now.isFinite || abs(now - position) > 0.25 {
            CMTimebaseSetTime(timebase, time: CMTime(seconds: position, preferredTimescale: 60000))
        }
        CMTimebaseSetRate(timebase, rate: owner.isPlayerPlaying ? Double(owner.currentSpeed) : 0)
        controller?.invalidatePlaybackState()
    }

    func enteredBackground() -> Bool {
        if waitingForInlinePresentation { owner?.setPictureInPicturePlaceholderVisible(false) }
        cancelInlinePresentationWait()
        handoffGeneration &+= 1
        let generation = handoffGeneration
        isBackground = true
        isForegroundActive = false
        shouldReturnToInline = false
        stopRequested = false
        // Never submit Metal work in the background, including during the PiP handoff.
        metalLayer?.nuvioPiPConsumesVideo = true
        if !keepsPlaybackAlive, receivedFrame, owner?.isPlayerPlaying == true,
           controller?.isPictureInPicturePossible == true {
            // Automatic start can arrive after didEnterBackground. Keep MPV alive
            // for the handoff, with a bounded fallback if the system declines it.
            isStarting = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
                guard let self, self.handoffGeneration == generation,
                      self.isBackground, self.isStarting, !self.isActive else { return }
                self.isStarting = false
                self.owner?.pausePlayback()
            }
        }
        return keepsPlaybackAlive
    }

    func enteredForeground() -> Bool {
        handoffGeneration &+= 1
        let preservePlaybackState = keepsPlaybackAlive || hasPresentedPictureInPicture || controller?.isPictureInPictureActive == true
        let closedInBackground = hasPresentedPictureInPicture && !keepsPlaybackAlive && controller?.isPictureInPictureActive != true
        hasPresentedPictureInPicture = false
        isBackground = false
        isForegroundActive = false
        shouldReturnToInline = preservePlaybackState
        // willEnterForeground prepares the UI; AVKit may not be ready to stop
        // yet. Request inline restoration only after the player scene activates.
        if !isActive, !isStarting, controller?.isPictureInPictureActive != true {
            isStarting = false
            metalLayer?.nuvioPiPConsumesVideo = false
        }
        // A closed PiP session returns through the original background video lifecycle,
        // while preserving the user's paused playback state.
        if closedInBackground { owner?.restoreBackgroundVideo() }
        return preservePlaybackState
    }

    func becameActive() {
        isBackground = false
        isForegroundActive = true
        if restoringInline { prepareInlinePresentation() }
        shouldReturnToInline = shouldReturnToInline || keepsPlaybackAlive || controller?.isPictureInPictureActive == true
        restoreInlineIfReady()
    }

    private func restoreInlineIfReady() {
        guard shouldReturnToInline, isForegroundActive, !stopRequested, let controller else { return }
        if controller.isPictureInPictureActive {
            stopRequested = true
            restoringInline = true
            prepareInlinePresentation()
            controller.stopPictureInPicture()
        } else if !isStarting {
            // AVKit may already have finished a user-initiated enlargement.
            isActive = false
            shouldReturnToInline = false
            metalLayer?.nuvioPiPConsumesVideo = false
        }
        // If PiP is still starting, didStart will complete this request.
    }

    func invalidate() {
        cancelInlinePresentationWait()
        owner?.setPictureInPicturePlaceholderVisible(false)
        handoffGeneration &+= 1
        if let sceneActivationObserver {
            NotificationCenter.default.removeObserver(sceneActivationObserver)
            self.sceneActivationObserver = nil
        }
        frameLock.lock()
        acceptingFrames = false
        pendingFrame = nil
        frameLock.unlock()
        controller?.delegate = nil
        controller?.stopPictureInPicture()
        controller = nil
        metalLayer?.pictureInPictureSink = nil
        displayLayer.removeFromSuperlayer()
        // Serialize teardown after any in-flight enqueue, without waiting on the UI.
        frameQueue.async { [weak self, clearFrameRenderer] in
            self?.cachedFormat = nil
            clearFrameRenderer()
        }
    }

    func pictureInPictureControllerWillStartPictureInPicture(_ pictureInPictureController: AVPictureInPictureController) {
        cancelInlinePresentationWait()
        isStarting = true
        metalLayer?.nuvioPiPConsumesVideo = true
        updatePlaybackState()
    }

    func pictureInPictureControllerDidStartPictureInPicture(_ pictureInPictureController: AVPictureInPictureController) {
        handoffGeneration &+= 1
        isStarting = false
        isActive = true
        hasPresentedPictureInPicture = true
        // Do not include inline artwork in the transition into PiP.
        owner?.setPictureInPicturePlaceholderVisible(true)
        restoreInlineIfReady()
    }

    func pictureInPictureController(_ pictureInPictureController: AVPictureInPictureController,
                                   failedToStartPictureInPictureWithError error: Error) {
        cancelInlinePresentationWait()
        owner?.setPictureInPicturePlaceholderVisible(false)
        handoffGeneration &+= 1
        isStarting = false
        isActive = false
        stopRequested = false
        shouldReturnToInline = false
        metalLayer?.nuvioPiPConsumesVideo = isBackground
        if isBackground { owner?.pausePlayback() }
    }

    func pictureInPictureControllerWillStopPictureInPicture(_ pictureInPictureController: AVPictureInPictureController) {
        restoringInline = true
        prepareInlinePresentation()
    }

    func pictureInPictureControllerDidStopPictureInPicture(_ pictureInPictureController: AVPictureInPictureController) {
        prepareInlinePresentation()
        restoringInline = false
        frameLock.lock()
        feedingRestoreTransition = false
        frameLock.unlock()
        if !inlinePresentationPrepared || !waitingForInlinePresentation {
            owner?.setPictureInPicturePlaceholderVisible(false)
        }
        handoffGeneration &+= 1
        isStarting = false
        isActive = false
        stopRequested = false
        shouldReturnToInline = false
        metalLayer?.nuvioPiPConsumesVideo = isBackground
        if isBackground { owner?.pauseBackgroundPlayback() }
    }

    func pictureInPictureController(_ pictureInPictureController: AVPictureInPictureController,
                                   restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void) {
        // Restore through AVKit's lifecycle while its video still covers the player.
        restoringInline = true
        prepareInlinePresentation()
        completionHandler(owner?.isViewLoaded == true)
    }

    func pictureInPictureController(_ pictureInPictureController: AVPictureInPictureController, setPlaying playing: Bool) {
        owner?.requestSystemPlaying(playing)
        updatePlaybackState()
    }

    func pictureInPictureControllerTimeRangeForPlayback(_ pictureInPictureController: AVPictureInPictureController) -> CMTimeRange {
        guard let owner, owner.durationMs > 0 else {
            return CMTimeRange(start: .negativeInfinity, duration: .positiveInfinity)
        }
        return CMTimeRange(start: .zero, duration: CMTime(seconds: Double(owner.durationMs) / 1000, preferredTimescale: 60000))
    }

    func pictureInPictureControllerIsPlaybackPaused(_ pictureInPictureController: AVPictureInPictureController) -> Bool {
        owner?.isPlayerPlaying != true
    }

    func pictureInPictureController(_ pictureInPictureController: AVPictureInPictureController, didTransitionToRenderSize newRenderSize: CMVideoDimensions) {}

    func pictureInPictureController(_ pictureInPictureController: AVPictureInPictureController,
                                   skipByInterval skipInterval: CMTime, completion completionHandler: @escaping () -> Void) {
        if let owner, owner.durationMs > 0 {
            owner.requestSystemSeekBy(Int64(CMTimeGetSeconds(skipInterval) * 1000))
        }
        updatePlaybackState()
        completionHandler()
    }
}
