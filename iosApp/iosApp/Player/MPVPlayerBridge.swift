import Foundation
import UIKit
import AVFoundation
import Libmpv
import ComposeApp

// MARK: - Player Bridge Implementation (Kotlin protocol conformance)

final class MPVPlayerBridgeImpl: NSObject, NuvioPlayerBridge {

    private var playerVC: MPVPlayerViewController?

    func createPlayerViewController() -> UIViewController {
        return ensurePlayerViewController()
    }

    private func ensurePlayerViewController() -> MPVPlayerViewController {
        if let playerVC { return playerVC }
        let vc = MPVPlayerViewController()
        self.playerVC = vc
        return vc
    }

    func loadFile(url: String) { ensurePlayerViewController().loadFile(url) }
    func loadFileWithAudio(videoUrl: String, audioUrl: String?, headersJson: String?, subtitlesJson: String?) {
        ensurePlayerViewController().loadFile(
            videoUrl,
            audioUrl: audioUrl,
            requestHeaders: parseRequestHeaders(headersJson),
            subtitles: parseSubtitles(subtitlesJson)
        )
    }

    private func parseSubtitles(_ json: String?) -> [PluginSubtitle] {
        guard
            let json,
            let data = json.data(using: .utf8),
            let raw = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]]
        else {
            return []
        }
        return raw.compactMap { dict in
            guard let url = dict["url"] as? String else { return nil }
            return PluginSubtitle(
                url: url,
                language: dict["language"] as? String ?? "Unknown",
                name: dict["name"] as? String,
                headers: dict["headers"] as? [String: String]
            )
        }
    }

    func play() { playerVC?.playPlayback() }
    func pause() { playerVC?.pausePlayback() }
    func seekTo(positionMs: Int64) { playerVC?.seekToMs(positionMs) }
    func seekBy(offsetMs: Int64) { playerVC?.seekByMs(offsetMs) }
    func retry() { playerVC?.retryPlayback() }
    func updateNowPlayingMetadata(
        title: String,
        subtitle: String?,
        artworkUrl: String?
    ) {
        ensurePlayerViewController().updateNowPlayingMetadata(
            title: title,
            subtitle: subtitle,
            artworkUrl: artworkUrl
        )
    }
    func clearNowPlayingInfo() { playerVC?.clearNowPlayingInfo() }
    func configureVideoOutput(
        hardwareDecoder: String,
        targetColorspaceHint: Bool,
        toneMapping: String,
        hdrComputePeak: Bool,
        targetPrimaries: String,
        targetTransfer: String,
        extendedDynamicRange: Bool,
        deband: Bool,
        interpolation: Bool,
        brightness: Int32,
        contrast: Int32,
        saturation: Int32,
        gamma: Int32
    ) {
        playerVC?.configureVideoOutput(
            hardwareDecoder: hardwareDecoder,
            targetColorspaceHint: targetColorspaceHint,
            toneMapping: toneMapping,
            hdrComputePeak: hdrComputePeak,
            targetPrimaries: targetPrimaries,
            targetTransfer: targetTransfer,
            extendedDynamicRange: extendedDynamicRange,
            deband: deband,
            interpolation: interpolation,
            brightness: Int(brightness),
            contrast: Int(contrast),
            saturation: Int(saturation),
            gamma: Int(gamma)
        )
    }
    func configureAudioOutput(audioOutput: String) {
        playerVC?.configureAudioOutput(audioOutput: audioOutput)
    }
    func setPlaybackSpeed(speed: Float) { playerVC?.setSpeed(speed) }
    func setMuted(muted: Bool) { playerVC?.setMuted(muted) }
    func setResizeMode(mode: Int32) { playerVC?.setResize(Int(mode)) }
    func syncVideoSurfaceLayout(width: Double, height: Double) {
        playerVC?.syncVideoSurfaceLayout(size: CGSize(width: width, height: height))
    }

    // Audio tracks
    func getAudioTrackCount() -> Int32 { Int32(playerVC?.audioTracks.count ?? 0) }
    func getAudioTrackIndex(at: Int32) -> Int32 {
        guard let t = playerVC?.audioTracks, Int(at) < t.count else { return 0 }
        return Int32(t[Int(at)].index)
    }
    func getAudioTrackId(at: Int32) -> String {
        guard let t = playerVC?.audioTracks, Int(at) < t.count else { return "0" }
        return "\(t[Int(at)].id)"
    }
    func getAudioTrackLabel(at: Int32) -> String {
        guard let t = playerVC?.audioTracks, Int(at) < t.count else { return "" }
        return t[Int(at)].title
    }
    func getAudioTrackLang(at: Int32) -> String {
        guard let t = playerVC?.audioTracks, Int(at) < t.count else { return "" }
        return t[Int(at)].lang
    }
    func isAudioTrackSelected(at: Int32) -> Bool {
        guard let t = playerVC?.audioTracks, Int(at) < t.count else { return false }
        return t[Int(at)].selected
    }

    // Subtitle tracks
    func getSubtitleTrackCount() -> Int32 { Int32(playerVC?.subtitleTracks.count ?? 0) }
    func getSubtitleTrackIndex(at: Int32) -> Int32 {
        guard let t = playerVC?.subtitleTracks, Int(at) < t.count else { return 0 }
        return Int32(t[Int(at)].index)
    }
    func getSubtitleTrackId(at: Int32) -> String {
        guard let t = playerVC?.subtitleTracks, Int(at) < t.count else { return "0" }
        return "\(t[Int(at)].id)"
    }
    func getSubtitleTrackLabel(at: Int32) -> String {
        guard let t = playerVC?.subtitleTracks, Int(at) < t.count else { return "" }
        return t[Int(at)].title
    }
    func getSubtitleTrackLang(at: Int32) -> String {
        guard let t = playerVC?.subtitleTracks, Int(at) < t.count else { return "" }
        return t[Int(at)].lang
    }
    func isSubtitleTrackSelected(at: Int32) -> Bool {
        guard let t = playerVC?.subtitleTracks, Int(at) < t.count else { return false }
        return t[Int(at)].selected
    }

    func selectAudioTrack(trackId: Int32) { playerVC?.selectAudio(Int(trackId)) }
    func applyAudioLanguagePreferences(languages: [String]) {
        ensurePlayerViewController().applyAudioLanguagePreferences(languages)
    }
    func selectSubtitleTrack(trackId: Int32) { playerVC?.selectSubtitle(Int(trackId)) }
    func setSubtitleUrl(url: String) { playerVC?.addSubtitleUrl(url) }
    func clearExternalSubtitle() { playerVC?.removeExternalSubtitles() }
    func clearExternalSubtitleAndSelect(trackId: Int32) { playerVC?.removeExternalSubtitlesAndSelect(Int(trackId)) }
    func setSubtitleDelayMs(delayMs: Int32) { playerVC?.setSubtitleDelayMs(Int(delayMs)) }
    func applySubtitleStyle(
        textColor: String,
        backgroundColor: String,
        outlineColor: String,
        outlineSize: Float,
        bold: Bool,
        fontSize: Float,
        subPos: Int32,
        stripSdh: Bool
    ) {
        playerVC?.applySubtitleStyle(
            textColor: textColor,
            backgroundColor: backgroundColor,
            outlineColor: outlineColor,
            outlineSize: outlineSize,
            bold: bold,
            fontSize: fontSize,
            subPos: Int(subPos),
            stripSdh: stripSdh
        )
    }

    // State - refreshes position from mpv on each call (polled from Kotlin every 250ms)
    func getIsLoading() -> Bool { playerVC?.refreshPlaybackState(); return playerVC?.isPlayerLoading ?? true }
    func getIsPlaying() -> Bool { return playerVC?.isPlayerPlaying ?? false }
    func getIsEnded() -> Bool { return playerVC?.isPlayerEnded ?? false }
    func getDurationMs() -> Int64 { return playerVC?.durationMs ?? 0 }
    func getPositionMs() -> Int64 { return playerVC?.positionMs ?? 0 }
    func getBufferedMs() -> Int64 { return playerVC?.bufferedMs ?? 0 }
    func getPlaybackSpeed() -> Float { playerVC?.currentSpeed ?? 1.0 }
    func getErrorMessage() -> String { playerVC?.currentErrorMessage ?? "" }

    func destroy() {
        playerVC?.destroyPlayer()
        playerVC = nil
    }

    private func parseRequestHeaders(_ headersJson: String?) -> [String: String] {
        guard
            let headersJson,
            !headersJson.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
            let data = headersJson.data(using: .utf8),
            let raw = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else {
            return [:]
        }

        var headers: [String: String] = [:]
        headers.reserveCapacity(raw.count)
        raw.forEach { key, value in
            guard let headerValue = value as? String else { return }
            headers[key] = headerValue
        }
        return headers
    }
}

struct PluginSubtitle {
    let url: String
    let language: String
    let name: String?
    let headers: [String: String]?
}

// MARK: - Track Info

struct TrackInfo {
    let index: Int
    let id: Int
    let type: String
    let title: String
    let lang: String
    let selected: Bool
}

private struct PendingLoadRequest {
    let urlString: String
    let audioUrl: String?
    let requestHeaders: [String: String]
    let subtitles: [PluginSubtitle]
    let queuedAtUptime: TimeInterval
}

// MARK: - MPV Player View Controller

final class MPVPlayerViewController: UIViewController {

    private static let defaultAudioOutput = "audiounit"

    private struct CachedNowPlayingMetadata {
        let title: String
        let subtitle: String?
        let artworkUrl: String?
    }

    private let errorStateLock = NSLock()
    private var metalLayer = MetalLayer()
    private var lastAppliedDrawableSize: CGSize = .zero
    private var externallyManagedViewSize: CGSize?
    private var pendingSurfaceLayoutWorkItems: [DispatchWorkItem] = []
    private var pendingLoadRequest: PendingLoadRequest?
    private var pendingLoadRetryWorkItem: DispatchWorkItem?
    private var mpv: OpaquePointer?
    private var cachedNowPlayingMetadata: CachedNowPlayingMetadata?
    private lazy var nowPlayingController = PlayerNowPlayingController(owner: self)
    private lazy var eventQueue = DispatchQueue(label: "mpv-events", qos: .userInitiated)
    private var recentPlaybackLogs: [String] = []
    private var activeRequestHeaders: [String: String] = [:]
    private var preferredAudioLanguages: [String] = []

    // Cached track lists
    var audioTracks: [TrackInfo] = []
    var subtitleTracks: [TrackInfo] = []

    // State (polled from Kotlin every 250ms)
    var isPlayerLoading: Bool = true
    var isPlayerPlaying: Bool = false
    var isPlayerEnded: Bool = false
    var durationMs: Int64 = 0
    var positionMs: Int64 = 0
    var bufferedMs: Int64 = 0
    var currentSpeed: Float = 1.0
    var currentErrorMessage: String {
        errorStateLock.lock()
        defer { errorStateLock.unlock() }
        return _currentErrorMessage ?? ""
    }
    private var _currentErrorMessage: String?

    override var canBecomeFirstResponder: Bool {
        true
    }

    override var prefersHomeIndicatorAutoHidden: Bool {
        true
    }

    override var preferredScreenEdgesDeferringSystemGestures: UIRectEdge {
        [.bottom, .left, .right]
    }

    override var prefersStatusBarHidden: Bool {
        true
    }

    override var preferredStatusBarUpdateAnimation: UIStatusBarAnimation {
        .fade
    }

    // MARK: - Lifecycle

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        view.layer.masksToBounds = true

        metalLayer.contentsGravity = .resize
        metalLayer.contentsScale = view.window?.screen.nativeScale ?? UIScreen.main.nativeScale
        metalLayer.framebufferOnly = true
        metalLayer.backgroundColor = UIColor.black.cgColor
        metalLayer.wantsExtendedDynamicRangeContent = true
        metalLayer.anchorPoint = CGPoint(x: 0, y: 0)
        metalLayer.position = .zero
        view.layer.addSublayer(metalLayer)
        layoutMetalLayer()

        setupMpv()
        activateAudioSessionForPlayback()
        setupNotifications()
        refreshImmersiveSystemUI()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        refreshImmersiveSystemUI()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        layoutMetalLayer()
        attemptStartPendingLoad()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        refreshImmersiveSystemUI()
        becomeFirstResponder()
        UIApplication.shared.beginReceivingRemoteControlEvents()
        publishCachedNowPlayingInfoIfNeeded()
        syncVideoSurfaceLayout()
        attemptStartPendingLoad()
    }

    override func viewSafeAreaInsetsDidChange() {
        super.viewSafeAreaInsetsDidChange()
        syncVideoSurfaceLayout()
        refreshImmersiveSystemUI()
        attemptStartPendingLoad()
    }

    override func viewWillTransition(to size: CGSize, with coordinator: UIViewControllerTransitionCoordinator) {
        super.viewWillTransition(to: size, with: coordinator)

        syncVideoSurfaceLayoutNow(scheduleDeferredPasses: false)
        coordinator.animate(alongsideTransition: { [weak self] _ in
            self?.syncVideoSurfaceLayoutNow(scheduleDeferredPasses: false)
        }, completion: { [weak self] _ in
            self?.syncVideoSurfaceLayout()
            self?.attemptStartPendingLoad()
        })
    }

    func syncVideoSurfaceLayout(size: CGSize) {
        if Thread.isMainThread {
            syncVideoSurfaceLayoutNow(size: size, scheduleDeferredPasses: true)
        } else {
            DispatchQueue.main.async { [weak self] in
                self?.syncVideoSurfaceLayoutNow(size: size, scheduleDeferredPasses: true)
            }
        }
    }

    private func syncVideoSurfaceLayout() {
        if Thread.isMainThread {
            syncVideoSurfaceLayoutNow(size: nil, scheduleDeferredPasses: true)
        } else {
            DispatchQueue.main.async { [weak self] in
                self?.syncVideoSurfaceLayoutNow(size: nil, scheduleDeferredPasses: true)
            }
        }
    }

    private func syncVideoSurfaceLayoutNow(size: CGSize? = nil, scheduleDeferredPasses: Bool) {
        guard isViewLoaded else { return }
        if let size, size.width > 1, size.height > 1 {
            externallyManagedViewSize = size
            applyExternallyManagedViewSize(size)
        }
        view.setNeedsLayout()
        view.layoutIfNeeded()
        layoutMetalLayer()

        if scheduleDeferredPasses {
            scheduleDeferredSurfaceLayoutPasses()
        }
    }

    private func scheduleDeferredSurfaceLayoutPasses() {
        pendingSurfaceLayoutWorkItems.forEach { $0.cancel() }
        pendingSurfaceLayoutWorkItems.removeAll(keepingCapacity: true)

        [0.0, 0.05, 0.15, 0.35].forEach { delay in
            let workItem = DispatchWorkItem { [weak self] in
                self?.syncVideoSurfaceLayoutNow(scheduleDeferredPasses: false)
            }
            pendingSurfaceLayoutWorkItems.append(workItem)
            DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: workItem)
        }
    }

    private func applyExternallyManagedViewSize(_ size: CGSize) {
        let targetBounds = CGRect(origin: .zero, size: size)
        if view.bounds != targetBounds {
            view.bounds = targetBounds
        }

        var targetFrame = view.frame
        if targetFrame.size != size {
            targetFrame.size = size
            view.frame = targetFrame
        }
    }

    private func layoutMetalLayer() {
        let bounds = CGRect(origin: .zero, size: externallyManagedViewSize ?? view.bounds.size)
        guard bounds.width > 1, bounds.height > 1 else { return }

        let scale = view.window?.screen.nativeScale ?? UIScreen.main.nativeScale
        let drawableSize = CGSize(
            width: (bounds.width * scale).rounded(.toNearestOrAwayFromZero),
            height: (bounds.height * scale).rounded(.toNearestOrAwayFromZero)
        )

        CATransaction.begin()
        CATransaction.setDisableActions(true)
        metalLayer.contentsScale = scale
        metalLayer.position = .zero
        metalLayer.bounds = CGRect(origin: .zero, size: bounds.size)
        if drawableSize != lastAppliedDrawableSize {
            // mpv's moltenvk context polls drawableSize and resizes its swapchain.
            metalLayer.drawableSize = drawableSize
            lastAppliedDrawableSize = drawableSize
        }
        CATransaction.commit()
    }

    // MARK: - MPV Setup

    private func setupMpv() {
        mpv = mpv_create()
        guard mpv != nil else {
            print("[MPV] Failed to create mpv instance")
            return
        }

        checkError(mpv_request_log_messages(mpv, "warn"))

        var layerPointer = Int64(Int(bitPattern: Unmanaged.passUnretained(metalLayer).toOpaque()))
        checkError(mpv_set_option(mpv, "wid", MPV_FORMAT_INT64, &layerPointer))
        checkError(mpv_set_option_string(mpv, "vo", "gpu-next"))
        checkError(mpv_set_option_string(mpv, "gpu-api", "vulkan"))
        checkError(mpv_set_option_string(mpv, "gpu-context", "moltenvk"))
        checkError(mpv_set_option_string(mpv, "hwdec", "videotoolbox"))
        checkError(mpv_set_option_string(mpv, "ao", Self.defaultAudioOutput))
        checkError(mpv_set_option_string(mpv, "audio-channels", "auto"))
        checkError(mpv_set_option_string(mpv, "audio-fallback-to-null", "yes"))
        checkError(mpv_set_option_string(mpv, "vulkan-swap-mode", "fifo"))
        checkError(mpv_set_option_string(mpv, "vulkan-queue-count", "1"))
        checkError(mpv_set_option_string(mpv, "vulkan-async-compute", "no"))
        checkError(mpv_set_option_string(mpv, "vulkan-async-transfer", "no"))
        checkError(mpv_set_option_string(mpv, "vulkan-disable-interop", "yes"))
        checkError(mpv_set_option_string(mpv, "video-rotate", "no"))
        checkError(mpv_set_option_string(mpv, "subs-match-os-language", "yes"))
        checkError(mpv_set_option_string(mpv, "subs-fallback", "yes"))
        configureBundledSubtitleFont()
        checkError(mpv_set_option_string(mpv, "keep-open", "yes"))
        checkError(mpv_set_option_string(mpv, "target-colorspace-hint", "yes"))
        checkError(mpv_set_option_string(mpv, "tone-mapping", "auto"))
        checkError(mpv_set_option_string(mpv, "hdr-compute-peak", "yes"))

        checkError(mpv_initialize(mpv))
        applyAudioLanguagePreferences(preferredAudioLanguages)

        // Observe properties
        mpv_observe_property(mpv, 0, "pause", MPV_FORMAT_FLAG)
        mpv_observe_property(mpv, 0, "paused-for-cache", MPV_FORMAT_FLAG)
        mpv_observe_property(mpv, 0, "core-idle", MPV_FORMAT_FLAG)
        mpv_observe_property(mpv, 0, "eof-reached", MPV_FORMAT_FLAG)
        mpv_observe_property(mpv, 0, "seeking", MPV_FORMAT_FLAG)
        mpv_observe_property(mpv, 0, "track-list", MPV_FORMAT_NODE)
        mpv_observe_property(mpv, 0, "aid", MPV_FORMAT_INT64)

        mpv_set_wakeup_callback(mpv, { ctx in
            let vc = unsafeBitCast(ctx, to: MPVPlayerViewController.self)
            vc.readEvents()
        }, UnsafeMutableRawPointer(Unmanaged.passUnretained(self).toOpaque()))
    }

    private func configureBundledSubtitleFont() {
        guard let fontURL = Bundle.main.url(
            forResource: "NotoSansCJKsc-Regular",
            withExtension: "otf"
        ) else {
            print("[MPV] Bundled CJK subtitle font is missing")
            return
        }

        let fontDirectory = fontURL.deletingLastPathComponent().path
        fontDirectory.withCString { path in
            checkError(mpv_set_option_string(mpv, "sub-fonts-dir", path))
        }
        checkError(mpv_set_option_string(mpv, "sub-font", "Noto Sans CJK SC"))
        print("[MPV] Using bundled CJK subtitle font: \(fontURL.lastPathComponent)")
    }

    private func setupNotifications() {
        NotificationCenter.default.addObserver(self, selector: #selector(enterBackground),
                                               name: UIApplication.didEnterBackgroundNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(enterForeground),
                                               name: UIApplication.willEnterForegroundNotification, object: nil)
    }

    @objc private func enterBackground() {
        guard mpv != nil else { return }
        pausePlayback()
        setStringProperty("vid", "no")
    }

    @objc private func enterForeground() {
        guard mpv != nil else { return }
        setStringProperty("vid", "auto")
        playPlayback()
    }

    // MARK: - Playback API

    func loadFile(_ urlString: String, audioUrl: String? = nil, requestHeaders: [String: String] = [:], subtitles: [PluginSubtitle] = []) {
        let request = PendingLoadRequest(
            urlString: urlString,
            audioUrl: audioUrl,
            requestHeaders: requestHeaders,
            subtitles: subtitles,
            queuedAtUptime: ProcessInfo.processInfo.systemUptime
        )

        if Thread.isMainThread {
            queueLoad(request)
        } else {
            DispatchQueue.main.async { [weak self] in
                self?.queueLoad(request)
            }
        }
    }

    private func queueLoad(_ request: PendingLoadRequest) {
        pendingLoadRequest = request
        attemptStartPendingLoad()
    }

    private func attemptStartPendingLoad() {
        guard let request = pendingLoadRequest else { return }
        guard mpv != nil else { return }
        layoutMetalLayer()
        guard isViewportReadyForPlayback(queuedAtUptime: request.queuedAtUptime) else {
            schedulePendingLoadRetry()
            return
        }

        pendingLoadRequest = nil
        pendingLoadRetryWorkItem?.cancel()
        pendingLoadRetryWorkItem = nil
        startLoad(request)
    }

    private func startLoad(_ request: PendingLoadRequest) {
        guard mpv != nil else { return }
        layoutMetalLayer()
        clearPlaybackError()
        let sanitizedHeaders = sanitizeRequestHeaders(request.requestHeaders)
        activeRequestHeaders = sanitizedHeaders
        applyRequestHeaders(sanitizedHeaders)
        isPlayerLoading = true
        isPlayerEnded = false
        applyAudioLanguagePreferences(preferredAudioLanguages)
        command("loadfile", args: [request.urlString, "replace"])
        if let audioUrl = request.audioUrl, !audioUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self] in
                self?.command("audio-add", args: [audioUrl, "select"], checkForErrors: false)
            }
        }

        for subtitle in request.subtitles {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
                self?.addSubtitle(subtitle, mode: "auto")
            }
        }
    }

    private func isViewportReadyForPlayback(queuedAtUptime: TimeInterval) -> Bool {
        guard isViewLoaded, view.window != nil else { return false }
        let bounds = view.bounds
        guard bounds.width > 1, bounds.height > 1 else { return false }
        if bounds.width >= bounds.height { return true }

        let age = ProcessInfo.processInfo.systemUptime - queuedAtUptime
        return age >= 0.9
    }

    private func schedulePendingLoadRetry() {
        guard pendingLoadRetryWorkItem == nil else { return }

        let workItem = DispatchWorkItem { [weak self] in
            self?.pendingLoadRetryWorkItem = nil
            self?.attemptStartPendingLoad()
        }
        pendingLoadRetryWorkItem = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05, execute: workItem)
    }

    func playPlayback() {
        guard mpv != nil else { return }
        publishNowPlayingForPlaybackSession()
        setFlag("pause", false)
        isPlayerPlaying = true
        syncNowPlayingPlaybackState(isPlaying: true)
    }

    func pausePlayback() {
        guard mpv != nil else { return }
        setFlag("pause", true)
        isPlayerPlaying = false
        syncNowPlayingPlaybackState(isPlaying: false)
    }

    func seekToMs(_ ms: Int64) {
        guard mpv != nil else { return }
        let seconds = Double(ms) / 1000.0
        command("seek", args: [String(format: "%.3f", seconds), "absolute"])
    }

    func seekByMs(_ ms: Int64, exact: Bool = false) {
        guard mpv != nil else { return }
        let seconds = Double(ms) / 1000.0
        let seekMode = exact ? "relative+exact" : "relative"
        command("seek", args: [String(format: "%.3f", seconds), seekMode])
    }

    func retryPlayback() {
        guard mpv != nil else { return }
        if let path = getString("path") {
            clearPlaybackError()
            applyRequestHeaders(activeRequestHeaders)
            let pos = getDouble("time-pos")
            command("loadfile", args: [path, "replace"])
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
                self?.command("seek", args: [String(format: "%.3f", pos), "absolute"])
            }
        }
    }

    func configureVideoOutput(
        hardwareDecoder: String,
        targetColorspaceHint: Bool,
        toneMapping: String,
        hdrComputePeak: Bool,
        targetPrimaries: String,
        targetTransfer: String,
        extendedDynamicRange: Bool,
        deband: Bool,
        interpolation: Bool,
        brightness: Int,
        contrast: Int,
        saturation: Int,
        gamma: Int
    ) {
        metalLayer.wantsExtendedDynamicRangeContent = extendedDynamicRange
        guard mpv != nil else { return }

        setStringProperty("hwdec", hardwareDecoder)
        setStringProperty("target-colorspace-hint", targetColorspaceHint ? "yes" : "no")
        setStringProperty("tone-mapping", toneMapping)
        setStringProperty("hdr-compute-peak", hdrComputePeak ? "yes" : "no")
        setStringProperty("target-prim", targetPrimaries)
        setStringProperty("target-trc", targetTransfer)
        setStringProperty("deband", deband ? "yes" : "no")
        setStringProperty("interpolation", interpolation ? "yes" : "no")
        setVideoEqualizer("brightness", brightness)
        setVideoEqualizer("contrast", contrast)
        setVideoEqualizer("saturation", saturation)
        setVideoEqualizer("gamma", gamma)
    }

    func configureAudioOutput(audioOutput: String) {
        guard mpv != nil else { return }
        let resolvedAudioOutput: String
        if audioOutput.contains("avfoundation") {
            resolvedAudioOutput = Self.defaultAudioOutput
        } else {
            resolvedAudioOutput = audioOutput
        }
        setStringProperty("ao", resolvedAudioOutput)
    }

    func setSpeed(_ speed: Float) {
        guard mpv != nil else { return }
        var s = Double(speed)
        mpv_set_property(mpv, "speed", MPV_FORMAT_DOUBLE, &s)
    }

    func setMuted(_ muted: Bool) {
        guard mpv != nil else { return }
        setFlag("mute", muted)
    }

    func setResize(_ mode: Int) {
        guard mpv != nil else { return }
        switch mode {
        case 1: // Fill
            setStringProperty("panscan", "1.0")
            setStringProperty("video-unscaled", "no")
        case 2: // Zoom
            setStringProperty("panscan", "1.0")
            setStringProperty("video-unscaled", "no")
        default: // Fit
            setStringProperty("panscan", "0.0")
            setStringProperty("video-unscaled", "no")
        }
    }

    // MARK: - Track selection

    func selectAudio(_ trackId: Int) {
        guard mpv != nil else { return }
        var id = Int64(trackId)
        mpv_set_property(mpv, "aid", MPV_FORMAT_INT64, &id)
    }

    func applyAudioLanguagePreferences(_ languages: [String]) {
        preferredAudioLanguages = languages
        guard mpv != nil else { return }
        setStringProperty("alang", languages.joined(separator: ","))
        if let currentId = getString("aid"), Int(currentId) != nil {
            setStringProperty("aid", currentId)
        }
        setStringProperty("aid", "auto")
    }

    func selectSubtitle(_ trackId: Int) {
        guard mpv != nil else { return }
        if trackId < 0 {
            setStringProperty("sid", "no")
        } else {
            var id = Int64(trackId)
            mpv_set_property(mpv, "sid", MPV_FORMAT_INT64, &id)
        }
    }

    func addSubtitleUrl(_ url: String) {
        guard mpv != nil else { return }
        command("sub-add", args: [url, "select"])
    }

    private func addSubtitle(_ subtitle: PluginSubtitle, mode: String) {
        guard mpv != nil else { return }
        let subtitleHeaders = sanitizeRequestHeaders(subtitle.headers ?? [:])
        let previousHeaders = activeRequestHeaders

        if !subtitleHeaders.isEmpty {
            applyRequestHeaders(previousHeaders.merging(subtitleHeaders) { _, subtitleValue in subtitleValue })
        }

        command(
            "sub-add",
            args: [subtitle.url, mode, subtitle.name ?? subtitle.language, subtitle.language],
            checkForErrors: false
        )

        if !subtitleHeaders.isEmpty {
            applyRequestHeaders(previousHeaders)
        }
    }

    func removeExternalSubtitles() {
        guard mpv != nil else { return }
        let count = getInt("track-list/count")
        for i in stride(from: count - 1, through: 0, by: -1) {
            let type = getString("track-list/\(i)/type") ?? ""
            let external = getFlag("track-list/\(i)/external")
            if type == "sub" && external {
                let id = getInt("track-list/\(i)/id")
                command("sub-remove", args: ["\(id)"], checkForErrors: false)
            }
        }
        setStringProperty("sid", "no")
    }

    func removeExternalSubtitlesAndSelect(_ trackId: Int) {
        guard mpv != nil else { return }
        let count = getInt("track-list/count")
        for i in stride(from: count - 1, through: 0, by: -1) {
            let type = getString("track-list/\(i)/type") ?? ""
            let external = getFlag("track-list/\(i)/external")
            if type == "sub" && external {
                let id = getInt("track-list/\(i)/id")
                command("sub-remove", args: ["\(id)"], checkForErrors: false)
            }
        }
        if trackId >= 0 {
            selectSubtitle(trackId)
        } else {
            setStringProperty("sid", "no")
        }
    }

    func setSubtitleDelayMs(_ delayMs: Int) {
        guard mpv != nil else { return }
        var delaySeconds = Double(max(-60_000, min(60_000, delayMs))) / 1000.0
        checkError(mpv_set_property(mpv, "sub-delay", MPV_FORMAT_DOUBLE, &delaySeconds))
    }

    func applySubtitleStyle(
        textColor: String,
        backgroundColor: String,
        outlineColor: String,
        outlineSize: Float,
        bold: Bool,
        fontSize: Float,
        subPos: Int,
        stripSdh: Bool
    ) {
        guard mpv != nil else { return }

        checkError(mpv_set_property_string(mpv, "sub-ass-override", "no"))
        checkError(mpv_set_property_string(mpv, "sub-color", textColor))
        checkError(mpv_set_property_string(mpv, "sub-back-color", backgroundColor))
        checkError(mpv_set_property_string(mpv, "sub-outline-color", outlineColor))
        checkError(mpv_set_property_string(mpv, "sub-border-style", backgroundColor.hasPrefix("#00") ? "outline-and-shadow" : "opaque-box"))
        setStringProperty("sub-bold", bold ? "yes" : "no")

        var outline = Double(outlineSize)
        checkError(mpv_set_property(mpv, "sub-outline-size", MPV_FORMAT_DOUBLE, &outline))

        var size = Double(fontSize)
        checkError(mpv_set_property(mpv, "sub-font-size", MPV_FORMAT_DOUBLE, &size))

        var position = Int64(subPos)
        checkError(mpv_set_property(mpv, "sub-pos", MPV_FORMAT_INT64, &position))
        setStringProperty("sub-filter-sdh", stripSdh ? "yes" : "no")
        setStringProperty("sub-filter-sdh-harder", stripSdh ? "yes" : "no")
    }

    func destroyPlayer() {
        NotificationCenter.default.removeObserver(self)
        UIApplication.shared.endReceivingRemoteControlEvents()
        resignFirstResponder()
        pendingLoadRetryWorkItem?.cancel()
        pendingLoadRetryWorkItem = nil
        pendingSurfaceLayoutWorkItems.forEach { $0.cancel() }
        pendingSurfaceLayoutWorkItems.removeAll(keepingCapacity: false)
        pendingLoadRequest = nil
        nowPlayingController.invalidate()
        clearPlaybackError()
        deactivateAudioSession()
        guard let ctx = mpv else { return }
        mpv = nil  // nil first so event loop stops reading
        mpv_terminate_destroy(ctx)
    }

    private func activateAudioSessionForPlayback() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .moviePlayback)
            try session.setActive(true)
        } catch {
            print("[NowPlaying] Failed to activate audio session: \(error)")
        }
    }

    private func deactivateAudioSession() {
        do {
            try AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        } catch {
            print("[NowPlaying] Failed to deactivate audio session: \(error)")
        }
    }

    // MARK: - State Update

    /// Lightweight state refresh — called by Kotlin polling (every 250ms).
    /// Only reads cheap scalar properties; does NOT re-enumerate tracks.
    func refreshPlaybackState() {
        guard mpv != nil else { return }
        let duration = getDouble("duration")
        let position = getDouble("time-pos")
        let cached = getDouble("demuxer-cache-time")
        let speed = getDouble("speed")
        let paused = getFlag("pause")
        let eofReached = getFlag("eof-reached")
        let idle = getFlag("core-idle")
        let seeking = getFlag("seeking")
        let bufferingCache = getFlag("paused-for-cache")

        isPlayerLoading = (idle && !paused && !eofReached) || seeking || bufferingCache
        isPlayerPlaying = !paused && !idle && !eofReached
        isPlayerEnded = eofReached
        durationMs = Int64(duration * 1000)
        positionMs = Int64(max(position, 0) * 1000)
        bufferedMs = Int64(max(position + cached, 0) * 1000)
        currentSpeed = Float(speed > 0 ? speed : 1.0)

        let shouldPublishNowPlayingState = !isPlayerLoading || isPlayerPlaying || durationMs > 0 || positionMs > 0
        if shouldPublishNowPlayingState {
            syncNowPlayingPlaybackState(isPlaying: isPlayerPlaying)
        }
    }

    private func syncNowPlayingPlaybackState(isPlaying: Bool) {
        nowPlayingController.syncPlayback(
            positionMs: positionMs,
            durationMs: durationMs,
            isPlaying: isPlaying,
            playbackSpeed: currentSpeed
        )
    }

    /// Full state + track refresh — called from MPV event loop on property changes.
    func updateState() {
        refreshPlaybackState()
        refreshTracks()
    }

    private func refreshTracks() {
        guard mpv != nil else { return }
        var audio = [TrackInfo]()
        var subs = [TrackInfo]()
        let count = getInt("track-list/count")
        var audioIdx = 0
        var subIdx = 0

        for i in 0..<count {
            let type = getString("track-list/\(i)/type") ?? ""
            let id = getInt("track-list/\(i)/id")
            let title = getTrackString(i, "title")
            let lang = getTrackString(i, "lang")
            let codec = getTrackString(i, "codec")
            let decoderDescription = getTrackString(i, "decoder-desc")
            let channels = getTrackString(i, "demux-channels")
            let channelCount = getInt("track-list/\(i)/demux-channel-count")
            let selected = getFlag("track-list/\(i)/selected")
            let displayTitle = formatTrackTitle(
                type: type,
                index: type == "audio" ? audioIdx : subIdx,
                title: title,
                lang: lang,
                codec: codec,
                decoderDescription: decoderDescription,
                channels: channels,
                channelCount: channelCount
            )

            if type == "audio" {
                audio.append(TrackInfo(index: audioIdx, id: id, type: type, title: displayTitle, lang: lang, selected: selected))
                audioIdx += 1
            } else if type == "sub" {
                subs.append(TrackInfo(index: subIdx, id: id, type: type, title: displayTitle, lang: lang, selected: selected))
                subIdx += 1
            }
        }
        audioTracks = audio
        subtitleTracks = subs
    }

    func updateNowPlayingMetadata(
        title: String,
        subtitle: String?,
        artworkUrl: String?
    ) {
        cachedNowPlayingMetadata = CachedNowPlayingMetadata(
            title: title,
            subtitle: subtitle,
            artworkUrl: artworkUrl
        )
        nowPlayingController.updateMetadata(
            title: title,
            subtitle: subtitle,
            artworkUrl: artworkUrl
        )
        publishNowPlayingForPlaybackSession()
    }

    func clearNowPlayingInfo() {
        cachedNowPlayingMetadata = nil
        nowPlayingController.clear()
    }

    private func publishCachedNowPlayingInfoIfNeeded() {
        guard let metadata = cachedNowPlayingMetadata else { return }
        nowPlayingController.updateMetadata(
            title: metadata.title,
            subtitle: metadata.subtitle,
            artworkUrl: metadata.artworkUrl
        )
    }

    private func publishNowPlayingForPlaybackSession() {
        activateAudioSessionForPlayback()
        if isViewLoaded, view.window != nil {
            becomeFirstResponder()
        }
        UIApplication.shared.beginReceivingRemoteControlEvents()
        publishCachedNowPlayingInfoIfNeeded()
        syncNowPlayingPlaybackState(isPlaying: isPlayerPlaying)
    }

    private func getTrackString(_ index: Int, _ field: String) -> String {
        (getString("track-list/\(index)/\(field)") ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func formatTrackTitle(
        type: String,
        index: Int,
        title: String,
        lang: String,
        codec: String,
        decoderDescription: String,
        channels: String,
        channelCount: Int
    ) -> String {
        let base = ifNotBlank(title)
            ?? localizedLanguageName(lang)
            ?? (type == "sub" ? "Subtitle \(index + 1)" : "Track \(index + 1)")
        let codecName = codecDisplayName(codec) ?? codecDisplayName(decoderDescription)
        let channelName = type == "audio" ? channelLayoutName(channels: channels, channelCount: channelCount) : nil
        let details = [channelName, codecName]
            .compactMap { $0 }
            .filter { detail in !base.localizedCaseInsensitiveContains(detail) }
        return details.isEmpty ? base : "\(base) (\(details.joined(separator: ", ")))"
    }

    private func ifNotBlank(_ value: String) -> String? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private func localizedLanguageName(_ languageCode: String) -> String? {
        guard let code = ifNotBlank(languageCode) else { return nil }
        return Locale.current.localizedString(forLanguageCode: code) ?? code
    }

    private func channelLayoutName(channels: String, channelCount: Int) -> String? {
        if let normalized = ifNotBlank(channels), normalized != "unknown" {
            let lower = normalized.lowercased()
            if lower == "mono" { return "Mono" }
            if lower == "stereo" { return "Stereo" }
            return normalized
        }
        switch channelCount {
        case 1:
            return "Mono"
        case 2:
            return "Stereo"
        case 6:
            return "5.1"
        case 8:
            return "7.1"
        case let count where count > 0:
            return "\(count)ch"
        default:
            return nil
        }
    }

    private func codecDisplayName(_ value: String) -> String? {
        guard let raw = ifNotBlank(value) else { return nil }
        let codec = raw.lowercased()
        if codec.contains("eac3") || codec.contains("e-ac-3") || codec.contains("e ac-3") {
            return codec.contains("joc") || codec.contains("atmos") ? "E-AC-3-JOC" : "E-AC-3"
        }
        if codec.contains("truehd") || codec.contains("true hd") { return "TrueHD" }
        if codec.contains("ac3") || codec.contains("ac-3") { return "AC-3" }
        if codec.contains("dts-hd") || codec.contains("dtshd") || codec.contains("dts hd") { return "DTS-HD" }
        if codec.contains("dts") || codec == "dca" { return "DTS" }
        if codec.contains("aac") { return "AAC" }
        if codec.contains("mp3") || codec.contains("mpeg audio") { return "MP3" }
        if codec.contains("mp2") { return "MP2" }
        if codec.contains("opus") { return "Opus" }
        if codec.contains("vorbis") { return "Vorbis" }
        if codec.contains("flac") { return "FLAC" }
        if codec.contains("alac") { return "ALAC" }
        if codec.contains("pcm") || codec.contains("wav") { return "WAV" }
        if codec.contains("amr_wb") || codec.contains("amr-wb") { return "AMR-WB" }
        if codec.contains("amr_nb") || codec.contains("amr-nb") { return "AMR-NB" }
        if codec.contains("amr") { return "AMR" }
        if codec.contains("iamf") { return "IAMF" }
        if codec.contains("mpegh") || codec.contains("mpeg-h") { return "MPEG-H" }
        if codec.contains("pgs") || codec.contains("hdmv") { return "PGS" }
        if codec.contains("subrip") || codec == "srt" { return "SRT" }
        if codec.contains("ass") || codec.contains("ssa") { return "SSA" }
        if codec.contains("webvtt") || codec == "vtt" { return "VTT" }
        if codec.contains("ttml") { return "TTML" }
        if codec.contains("mov_text") || codec.contains("tx3g") { return "TX3G" }
        if codec.contains("dvb") { return "DVB" }
        return raw
    }

    private func clearPlaybackError() {
        errorStateLock.lock()
        recentPlaybackLogs.removeAll(keepingCapacity: true)
        _currentErrorMessage = nil
        errorStateLock.unlock()
    }

    private func appendPlaybackLog(prefix: String, level: String, text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        guard level == "warn" || level == "error" || level == "fatal" else { return }

        let formatted = "[\(prefix)] \(trimmed)"
        errorStateLock.lock()
        recentPlaybackLogs.append(formatted)
        if recentPlaybackLogs.count > 4 {
            recentPlaybackLogs.removeFirst(recentPlaybackLogs.count - 4)
        }
        errorStateLock.unlock()
    }

    private func setPlaybackError(_ fallback: String) {
        let trimmedFallback = fallback.trimmingCharacters(in: .whitespacesAndNewlines)
        errorStateLock.lock()
        var parts = recentPlaybackLogs.suffix(3)
        if !trimmedFallback.isEmpty && !parts.contains(trimmedFallback) {
            parts.append(trimmedFallback)
        }
        _currentErrorMessage = parts.isEmpty ? "Unable to play this stream." : parts.joined(separator: "\n")
        errorStateLock.unlock()
    }

    // MARK: - Event Loop

    private func readEvents() {
        eventQueue.async { [weak self] in
            guard let self, let mpv = self.mpv else { return }

            while true {
                let event = mpv_wait_event(mpv, 0)
                guard let eventPtr = event else { break }
                if eventPtr.pointee.event_id == MPV_EVENT_NONE { break }

                switch eventPtr.pointee.event_id {
                case MPV_EVENT_PROPERTY_CHANGE:
                    DispatchQueue.main.async { self.updateState() }
                case MPV_EVENT_FILE_LOADED:
                    DispatchQueue.main.async {
                        self.clearPlaybackError()
                        self.isPlayerLoading = false
                        self.updateState()
                        self.publishNowPlayingForPlaybackSession()
                        self.logCurrentAudioOutput()
                    }
                case MPV_EVENT_PLAYBACK_RESTART:
                    DispatchQueue.main.async {
                        self.updateState()
                        self.publishNowPlayingForPlaybackSession()
                    }
                case MPV_EVENT_END_FILE:
                    if let data = eventPtr.pointee.data {
                        let endFile = UnsafePointer<mpv_event_end_file>(OpaquePointer(data)).pointee
                        if endFile.reason == MPV_END_FILE_REASON_ERROR {
                            let errorText = String(cString: mpv_error_string(endFile.error))
                            self.setPlaybackError("[mpv] \(errorText)")
                            print("[MPV] End file error: \(errorText)")
                        }
                    }
                case MPV_EVENT_SHUTDOWN:
                    return
                case MPV_EVENT_LOG_MESSAGE:
                    if let msg = UnsafeMutablePointer<mpv_event_log_message>(OpaquePointer(eventPtr.pointee.data)) {
                        let prefix = String(cString: msg.pointee.prefix!)
                        let level = String(cString: msg.pointee.level!)
                        let text = String(cString: msg.pointee.text!)
                        self.appendPlaybackLog(prefix: prefix, level: level, text: text)
                        print("[MPV][\(prefix)] \(level): \(text)", terminator: "")
                    }
                default:
                    break
                }
            }
        }
    }

    // MARK: - MPV Helpers

    private func command(_ command: String, args: [String?] = [], checkForErrors: Bool = true) {
        guard mpv != nil else { return }
        var cargs = makeCArgs(command, args).map { $0.flatMap { UnsafePointer<CChar>(strdup($0)) } }
        defer { for ptr in cargs where ptr != nil { free(UnsafeMutablePointer(mutating: ptr!)) } }
        let ret = mpv_command(mpv, &cargs)
        if checkForErrors { checkError(ret) }
    }

    private func makeCArgs(_ command: String, _ args: [String?]) -> [String?] {
        var strArgs = args
        strArgs.insert(command, at: 0)
        strArgs.append(nil)
        return strArgs
    }

    private func getDouble(_ name: String) -> Double {
        guard mpv != nil else { return 0.0 }
        var data = Double()
        mpv_get_property(mpv, name, MPV_FORMAT_DOUBLE, &data)
        return data
    }

    private func getString(_ name: String) -> String? {
        guard mpv != nil else { return nil }
        let cstr = mpv_get_property_string(mpv, name)
        let str: String? = cstr == nil ? nil : String(cString: cstr!)
        mpv_free(cstr)
        return str
    }

    private func getFlag(_ name: String) -> Bool {
        guard mpv != nil else { return false }
        var data = Int64()
        mpv_get_property(mpv, name, MPV_FORMAT_FLAG, &data)
        return data > 0
    }

    private func setFlag(_ name: String, _ flag: Bool) {
        guard mpv != nil else { return }
        var data: Int = flag ? 1 : 0
        mpv_set_property(mpv, name, MPV_FORMAT_FLAG, &data)
    }

    private func setStringProperty(_ name: String, _ value: String) {
        guard mpv != nil else { return }
        checkError(mpv_set_property_string(mpv, name, value))
    }

    private func setVideoEqualizer(_ name: String, _ value: Int) {
        guard mpv != nil else { return }
        var clamped = Int64(max(-100, min(100, value)))
        checkError(mpv_set_property(mpv, name, MPV_FORMAT_INT64, &clamped))
    }

    private func getInt(_ name: String) -> Int {
        guard mpv != nil else { return 0 }
        var data = Int64()
        mpv_get_property(mpv, name, MPV_FORMAT_INT64, &data)
        return Int(data)
    }

    private func logCurrentAudioOutput() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            guard let self, self.mpv != nil else { return }
            let currentAo = self.getString("current-ao") ?? "unknown"
            let channels = self.getString("audio-out-params/hr-channels")
                ?? self.getString("audio-params/hr-channels")
                ?? "unknown"
            let channelCount = self.getInt("audio-out-params/channel-count")
            let codec = self.getString("audio-codec-name") ?? "unknown"
            print("[MPV] Audio output: ao=\(currentAo), channels=\(channels), channelCount=\(channelCount), codec=\(codec)")
        }
    }

    private func checkError(_ status: CInt) {
        if status < 0 {
            print("[MPV] API error: \(String(cString: mpv_error_string(status)))")
        }
    }

    private func sanitizeRequestHeaders(_ headers: [String: String]) -> [String: String] {
        guard !headers.isEmpty else { return [:] }

        var sanitized: [String: String] = [:]
        sanitized.reserveCapacity(headers.count)
        headers.forEach { rawKey, rawValue in
            let key = rawKey.trimmingCharacters(in: .whitespacesAndNewlines)
            let value = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !key.isEmpty, !value.isEmpty else { return }
            guard key.caseInsensitiveCompare("Range") != .orderedSame else { return }
            sanitized[key] = value
        }
        return sanitized
    }

    private func applyRequestHeaders(_ headers: [String: String]) {
        guard mpv != nil else { return }
        if headers.isEmpty {
            checkError(mpv_set_property_string(mpv, "http-header-fields", ""))
            return
        }

        let serialized = headers
            .sorted { $0.key.localizedCaseInsensitiveCompare($1.key) == .orderedAscending }
            .map { key, value in
                let escapedValue = value
                    .replacingOccurrences(of: "\\", with: "\\\\")
                    .replacingOccurrences(of: ",", with: "\\,")
                return "\(key): \(escapedValue)"
            }
            .joined(separator: ",")
        checkError(mpv_set_property_string(mpv, "http-header-fields", serialized))
    }

    private func refreshImmersiveSystemUI() {
        setNeedsUpdateOfHomeIndicatorAutoHidden()
        setNeedsUpdateOfScreenEdgesDeferringSystemGestures()
        setNeedsStatusBarAppearanceUpdate()

        var currentParent = parent
        while let controller = currentParent {
            controller.setNeedsUpdateOfHomeIndicatorAutoHidden()
            controller.setNeedsUpdateOfScreenEdgesDeferringSystemGestures()
            controller.setNeedsStatusBarAppearanceUpdate()
            if let rootController = controller as? RootComposeViewController {
                rootController.refreshImmersiveSystemUI()
            }
            currentParent = controller.parent
        }
    }
}

// MARK: - Bridge Creator (implements Kotlin protocol)

final class MPVPlayerBridgeCreator: NSObject, NuvioPlayerBridgeCreator {
    func createBridge() -> any NuvioPlayerBridge {
        return MPVPlayerBridgeImpl()
    }
}

// MARK: - Registration (called from Swift app startup)

enum NuvioPlayerRegistration {
    static func register() {
        NuvioPlayerBridgeFactory.shared.registerFactory(creator: MPVPlayerBridgeCreator())
    }
}
