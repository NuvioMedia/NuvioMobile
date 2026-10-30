import XCTest
import UIKit
import ComposeApp
@testable import Nuvio

@MainActor
final class SubtitlePlaybackTests: XCTestCase {
    private let factory = RecordingPlayerFactory()

    func testTextStylingTracksSeekDelayAndReset() async throws {
        NuvioPlayerBridgeFactory.shared.registerFactory(creator: factory)
        let settings = PlayerSettingsRepository.shared
        settings.ensureLoaded()
        let defaults = UserDefaults.standard
        let original = defaults.dictionaryRepresentation().filter { $0.key.contains("subtitle") }
        let fontId = "custom-\(UUID().uuidString.lowercased())"
        let fontDirectory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("subtitle-fonts")
        try FileManager.default.createDirectory(at: fontDirectory, withIntermediateDirectories: true)
        let fontFile = fontDirectory.appendingPathComponent(fontId)
        let bundledFont = Bundle.main.bundleURL.appendingPathComponent("compose-resources/composeResources/nuvio.composeapp.generated.resources/font/jetbrains_sans_regular.ttf")
        try FileManager.default.copyItem(at: bundledFont, to: fontFile)
        settings.setPreferredSubtitleLanguage(language: "en")
        settings.setSubtitleStyle(style: makeStyle(font: "serif", radius: 16))
        let bundle = Bundle(for: Self.self)
        let video = try XCTUnwrap(bundle.url(forResource: "video", withExtension: "mp4"))
        let subtitles = try XCTUnwrap(bundle.url(forResource: "captions", withExtension: "srt"))
        let launch = PlayerLaunch(
            profileId: 1, title: "Subtitle test", sourceUrl: video.absoluteString,
            sourceAudioUrl: nil, sourceHeaders: [:], sourceResponseHeaders: [:],
            externalSubtitles: [StreamSubtitle(url: subtitles.absoluteString, language: "en", name: "Captions", headers: nil)],
            streamType: nil, logo: nil, poster: nil, background: nil, seasonNumber: nil, episodeNumber: nil,
            episodeTitle: nil, episodeThumbnail: nil, streamTitle: "Fixture", streamSubtitle: nil, bingeGroup: nil,
            pauseDescription: nil, providerName: "Local", providerAddonId: nil, contentType: nil, videoId: nil,
            parentMetaId: "subtitle-test", parentMetaType: "movie", torrentInfoHash: nil, torrentFileIdx: nil,
            torrentFilename: nil, torrentTrackers: [], initialPositionMs: 0, initialProgressFraction: nil,
            contentLanguage: "en", sourceFilename: nil, stream: nil
        )
        let launchId = PlayerLaunchStore.shared.put(launch: launch)
        let window = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first?.windows.first)
        let previousController = window.rootViewController
        window.rootViewController = MainViewControllerKt.ScreenViewController(
            route: PlayerRoute(launchId: launchId, title: "Subtitle test"),
            onNavigate: { _, _ in }, onGoBack: {}, onReplace: { _ in }, onActivate: { _ in },
            appGateController: AppGateController(), verticalBar: VerticalBar(), hinge: Hinge()
        )
        window.makeKeyAndVisible()
        defer {
            try? FileManager.default.removeItem(at: fontFile)
            window.rootViewController = previousController
            defaults.dictionaryRepresentation().keys.filter { $0.contains("subtitle") }.forEach { defaults.removeObject(forKey: $0) }
            original.forEach { defaults.set($0.value, forKey: $0.key) }
            settings.onProfileChanged()
            NuvioPlayerRegistration.register()
            PlayerLaunchStore.shared.remove(launchId: launchId)
            factory.bridge?.destroy()
        }
        try await until { factory.bridge?.getSubtitleTrackCount() ?? 0 > 0 }
        let bridge = try XCTUnwrap(factory.bridge)
        bridge.selectSubtitleTrack(trackId: Int32(bridge.getSubtitleTrackId(at: 0))!)
        bridge.seekTo(positionMs: 3_000)
        bridge.play()
        try await until { bridge.getProperty(name: "sub-text") == "First caption" }
        bridge.pause()
        try await until { bridge.getProperty(name: "sub-visibility") == "no" }
        try await Task.sleep(nanoseconds: 300_000_000)
        attachScreenshot(window, name: "ios-rounded")
        settings.setSubtitleStyle(style: makeStyle(font: fontId, radius: 16, outline: false))
        try await until { Double(bridge.getProperty(name: "sub-outline-size")) == 0 }
        try await Task.sleep(nanoseconds: 800_000_000)
        attachScreenshot(window, name: "ios-custom-font")
        settings.setSubtitleStyle(style: makeStyle(font: "mono", radius: 0))
        try await until { Double(bridge.getProperty(name: "sub-outline-size")) == 2 }
        try await Task.sleep(nanoseconds: 800_000_000)
        attachScreenshot(window, name: "ios-square")
        bridge.setSubtitleDelayMs(delayMs: 5_000)
        try await until { bridge.getProperty(name: "sub-text").isEmpty }
        bridge.setSubtitleDelayMs(delayMs: 0)
        try await until { bridge.getProperty(name: "sub-text") == "First caption" }
        bridge.seekTo(positionMs: 6_000)
        try await until { bridge.getProperty(name: "sub-text").isEmpty }
        bridge.seekTo(positionMs: 9_000)
        try await until { bridge.getProperty(name: "sub-text").contains("Second line") }
        attachScreenshot(window, name: "ios-multiline")
        bridge.seekTo(positionMs: 14_500)
        try await until { bridge.getProperty(name: "sub-text").contains("مرحبا") }
        attachScreenshot(window, name: "ios-rtl")
        settings.setSubtitleStyle(style: SubtitleStyleState.companion.DEFAULT)
        try await until { bridge.getProperty(name: "sub-visibility") == "yes" }
        settings.setSubtitleStyle(style: makeStyle(font: "serif", radius: 16))
        let ass = try XCTUnwrap(bundle.url(forResource: "styled", withExtension: "ass"))
        bridge.setSubtitleUrl(url: ass.absoluteString)
        try await until { bridge.getProperty(name: "current-tracks/sub/codec") == "ass" }
        try await until { bridge.getProperty(name: "sub-visibility") == "yes" }
        XCTAssertEqual(bridge.getProperty(name: "sub-ass-override"), "no")
        attachScreenshot(window, name: "ios-ass")
        bridge.selectSubtitleTrack(trackId: -1)
        try await until { bridge.getProperty(name: "sub-text").isEmpty }
        XCTAssertEqual(bridge.getErrorMessage(), "")
    }

    private func makeStyle(font: String, radius: Int32, outline: Bool = true) -> SubtitleStyleState {
        SubtitleStyleState(
            textColor: 0xFFFFFFFF00000000, backgroundColor: 0xA600000000000000,
            outlineColor: 0xFF00000000000000, outlineEnabled: outline, outlineWidth: 2,
            bold: false, fontSizeSp: 28, bottomOffset: 20, stripSdh: false,
            useForcedSubtitles: false, showOnlyPreferredLanguages: false,
            appearance: SubtitleAppearance(fontId: font, backgroundRadius: radius, shadowEnabled: true, shadowOpacity: 80, shadowSize: 3)
        )
    }

    private func until(file: StaticString = #filePath, line: UInt = #line, _ condition: () -> Bool) async throws {
        for _ in 0..<300 {
            if condition() { return }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        let details = ["time-pos", "duration", "sid", "sub-text", "current-tracks/sub/codec", "sub-visibility"].map { "\($0)=\(factory.bridge?.getProperty(name: $0) ?? "unavailable")" }.joined(separator: ", ")
        XCTFail("Timed out waiting for the subtitle engine: \(details)", file: file, line: line)
        throw NSError(domain: "SubtitleTests", code: 1)
    }

    private func attachScreenshot(_ window: UIWindow, name: String) {
        let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }
        let attachment = XCTAttachment(image: image)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}

private final class RecordingPlayerFactory: NSObject, NuvioPlayerBridgeCreator {
    var bridge: MPVPlayerBridgeImpl?

    func createBridge() -> any NuvioPlayerBridge {
        let bridge = MPVPlayerBridgeImpl()
        self.bridge = bridge
        return bridge
    }
}
