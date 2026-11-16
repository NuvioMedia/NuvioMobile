import AVFoundation
import AVKit
import CoreMedia
import ComposeApp
import Foundation
import UIKit

/// iOS 15 compatibility player.
///
/// The MPVKit gpu-next/MoltenVK renderer has not been verified on iOS 15
/// devices. Constructing its custom Metal output on these older systems can
/// terminate the entire app instead of returning a recoverable stream error.
/// Keep the modern MPV player on iOS 16+, and use Apple's AVPlayer for
/// standard MP4/HLS playback on iOS 15. The UI bridge remains identical.
///
/// AVFoundation does not decode every MKV or advanced codec that MPV handles.
/// Unsupported formats should return a playback error, not crash the app.
final class LegacyAVPlayerBridgeImpl: NSObject, NuvioPlayerBridge {
    private let player = AVPlayer()
    private let videoController = AVPlayerViewController()
    private let audioSessionController = PlayerAudioSessionController()
    private var endObserver: NSObjectProtocol?
    private var systemControlHandler: PlayerSystemControlHandler?
    private var subtitleHandler: SubtitleTextHandler?
    private var sourceURL: URL?
    private var lastError: String?
    private var hasEnded = false
    private var shouldPlay = false
    private var playbackSpeed: Float = 1.0

    override init() {
        super.init()
        videoController.player = player
        // Compose renders the playback controls above this UIKit surface.
        videoController.showsPlaybackControls = false
        videoController.allowsPictureInPicturePlayback = true
        player.actionAtItemEnd = .pause
    }

    func createPlayerViewController() -> UIViewController { videoController }

    func loadFile(url: String) {
        loadFileWithAudio(videoUrl: url, audioUrl: nil, headersJson: nil, subtitlesJson: nil)
    }

    func loadFileWithAudio(videoUrl: String, audioUrl: String?, headersJson: String?, subtitlesJson: String?) {
        guard let url = URL(string: videoUrl), ["https", "http", "file"].contains(url.scheme?.lowercased() ?? "") else {
            lastError = "This video URL is invalid."
            return
        }
        endObserver.map(NotificationCenter.default.removeObserver)
        endObserver = nil
        sourceURL = url
        hasEnded = false
        lastError = nil

        let requestHeaders: [String: String]
        if let json = headersJson, let data = json.data(using: .utf8),
           let decoded = try? JSONSerialization.jsonObject(with: data) as? [String: String] {
            requestHeaders = decoded.filter { $0.key.caseInsensitiveCompare("Range") != .orderedSame }
        } else {
            requestHeaders = [:]
        }
        // AVURLAsset supports HTTP headers for streams that need a referer
        // or authorization token. No headers are added for ordinary streams.
        let asset = AVURLAsset(
            url: url,
            options: requestHeaders.isEmpty ? nil : ["AVURLAssetHTTPHeaderFieldsKey": requestHeaders]
        )
        let item = AVPlayerItem(asset: asset)
        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main
        ) { [weak self] _ in
            self?.hasEnded = true
            self?.shouldPlay = false
        }
        player.replaceCurrentItem(with: item)
        if let audioUrl, !audioUrl.isEmpty {
            // Separate video/audio URLs are an MPV feature, not an AVPlayer
            // feature. Leave video playback running and surface the limitation.
            lastError = "Separate audio tracks require a newer iOS version or an external player."
        }
        if shouldPlay { play() }
    }

    func play() {
        shouldPlay = true
        if hasEnded { seekTo(positionMs: 0) }
        hasEnded = false
        audioSessionController.activate()
        player.play()
        if playbackSpeed != 1.0 { player.rate = playbackSpeed }
    }

    func pause() {
        shouldPlay = false
        player.pause()
    }

    func seekTo(positionMs: Int64) {
        let seconds = max(0, Double(positionMs) / 1_000)
        player.seek(to: CMTime(seconds: seconds, preferredTimescale: 600))
        hasEnded = false
    }

    func seekBy(offsetMs: Int64) {
        seekTo(positionMs: max(0, getPositionMs() + offsetMs))
    }

    func setSystemControlHandler(handler: PlayerSystemControlHandler?) {
        systemControlHandler = handler
    }

    func getKeepsPlaybackInBackground() -> Bool { false }
    func retry() {
        guard let sourceURL else { return }
        loadFile(url: sourceURL.absoluteString)
        play()
    }

    func updateNowPlayingMetadata(title: String, subtitle: String?, artworkUrl: String?) {}
    func clearNowPlayingInfo() {}

    func configureVideoOutput(
        hardwareDecoder: String, targetColorspaceHint: Bool, toneMapping: String,
        hdrComputePeak: Bool, targetPrimaries: String, targetTransfer: String,
        extendedDynamicRange: Bool, deband: Bool, interpolation: Bool,
        brightness: Int32, contrast: Int32, saturation: Int32, gamma: Int32
    ) {
        // AVFoundation chooses a supported video decoder and renderer itself.
    }

    func configureAudioOutput(audioOutput: String) {}
    func setPlaybackSpeed(speed: Float) {
        playbackSpeed = max(0.25, min(4.0, speed))
        if getIsPlaying() { player.rate = playbackSpeed }
    }
    func setMuted(muted: Bool) { player.isMuted = muted }
    func setResizeMode(mode: Int32) {
        videoController.videoGravity = mode == 0 ? .resizeAspect : .resizeAspectFill
    }
    func syncVideoSurfaceLayout(width: Double, height: Double) {}

    private func trackGroup(_ characteristic: AVMediaCharacteristic) -> AVMediaSelectionGroup? {
        player.currentItem?.asset.mediaSelectionGroup(forMediaCharacteristic: characteristic)
    }

    private func track(_ index: Int32, characteristic: AVMediaCharacteristic) -> AVMediaSelectionOption? {
        let tracks = trackGroup(characteristic)?.options ?? []
        guard index >= 0 && Int(index) < tracks.count else { return nil }
        return tracks[Int(index)]
    }

    private func selected(_ index: Int32, characteristic: AVMediaCharacteristic) -> Bool {
        guard let group = trackGroup(characteristic),
              let option = track(index, characteristic: characteristic) else { return false }
        return player.currentItem?.currentMediaSelection.selectedMediaOption(in: group) === option
    }

    private func selectTrack(_ id: Int32, characteristic: AVMediaCharacteristic) {
        guard let item = player.currentItem,
              let group = trackGroup(characteristic) else { return }
        let index = Int(id) - 1
        let option = index >= 0 && index < group.options.count ? group.options[index] : nil
        item.select(option, in: group)
    }

    func getAudioTrackCount() -> Int32 { Int32(trackGroup(.audible)?.options.count ?? 0) }
    func getAudioTrackIndex(at: Int32) -> Int32 { at }
    func getAudioTrackId(at: Int32) -> String { String(at + 1) }
    func getAudioTrackLabel(at: Int32) -> String {
        track(at, characteristic: .audible)?.displayName ?? ""
    }
    func getAudioTrackLang(at: Int32) -> String {
        track(at, characteristic: .audible)?.locale?.languageCode ?? ""
    }
    func isAudioTrackSelected(at: Int32) -> Bool { selected(at, characteristic: .audible) }

    func getSubtitleTrackCount() -> Int32 { Int32(trackGroup(.legible)?.options.count ?? 0) }
    func getSubtitleTrackIndex(at: Int32) -> Int32 { at }
    func getSubtitleTrackId(at: Int32) -> String { String(at + 1) }
    func getSubtitleTrackLabel(at: Int32) -> String {
        track(at, characteristic: .legible)?.displayName ?? ""
    }
    func getSubtitleTrackLang(at: Int32) -> String {
        track(at, characteristic: .legible)?.locale?.languageCode ?? ""
    }
    func isSubtitleTrackSelected(at: Int32) -> Bool { selected(at, characteristic: .legible) }
    func selectAudioTrack(trackId: Int32) { selectTrack(trackId, characteristic: .audible) }
    func applyAudioLanguagePreferences(languages: [String]) {}
    func selectSubtitleTrack(trackId: Int32) { selectTrack(trackId, characteristic: .legible) }
    func setSubtitleUrl(url: String) {}
    func replaceExternalSubtitle(replacedUrl: String, path: String) {}
    func clearExternalSubtitle() {}
    func clearExternalSubtitleAndSelect(trackId: Int32) { selectSubtitleTrack(trackId: trackId) }
    func setSubtitleDelayMs(delayMs: Int32) {}
    func setSubtitleTextHandler(handler: SubtitleTextHandler?) { subtitleHandler = handler }

    func applySubtitleStyle(
        textColor: String, backgroundColor: String, outlineColor: String,
        outlineSize: Float, bold: Bool, fontSize: Float, subPos: Int32,
        stripSdh: Bool, styledOverlay: Bool
    ) {}

    func getIsLoading() -> Bool {
        guard let item = player.currentItem else { return true }
        return item.status == .unknown ||
            (shouldPlay && player.timeControlStatus == .waitingToPlayAtSpecifiedRate && item.status != .failed)
    }
    func getIsOpening() -> Bool {
        guard let item = player.currentItem else { return true }
        return item.status == .unknown
    }
    func getIsPlaying() -> Bool { player.timeControlStatus == .playing }
    func getIsEnded() -> Bool { hasEnded }

    private func milliseconds(_ time: CMTime) -> Int64 {
        let seconds = CMTimeGetSeconds(time)
        guard seconds.isFinite && seconds > 0 else { return 0 }
        return Int64(min(seconds * 1_000, Double(Int64.max)))
    }

    func getDurationMs() -> Int64 { milliseconds(player.currentItem?.duration ?? .invalid) }
    func getPositionMs() -> Int64 { milliseconds(player.currentTime()) }

    func getBufferedMs() -> Int64 {
        guard let item = player.currentItem else { return 0 }
        let range = item.loadedTimeRanges.last?.timeRangeValue
        guard let range else { return 0 }
        return milliseconds(CMTimeRangeGetEnd(range))
    }

    func getPlaybackSpeed() -> Float { playbackSpeed }

    func getErrorMessage() -> String {
        if let lastError { return lastError }
        if let error = player.currentItem?.error {
            return "This iOS 15 player cannot open the stream. For MKV or unsupported codecs, install VLC and enable External Player in Nuvio's settings. Details: \(error.localizedDescription)"
        }
        if let error = player.error {
            return "Playback failed. Install VLC for broader iOS 15 streaming support. Details: \(error.localizedDescription)"
        }
        return ""
    }

    func getProperty(name: String) -> String {
        switch name {
        case "duration": return String(Double(getDurationMs()) / 1000.0)
        case "time-pos": return String(Double(getPositionMs()) / 1000.0)
        default: return ""
        }
    }

    func destroy() {
        pause()
        endObserver.map(NotificationCenter.default.removeObserver)
        endObserver = nil
        player.replaceCurrentItem(with: nil)
        videoController.player = nil
        audioSessionController.deactivate()
        systemControlHandler = nil
        subtitleHandler = nil
        sourceURL = nil
    }
}

final class LegacyAVPlayerBridgeCreator: NSObject, NuvioPlayerBridgeCreator {
    func createBridge() -> any NuvioPlayerBridge { LegacyAVPlayerBridgeImpl() }
}
