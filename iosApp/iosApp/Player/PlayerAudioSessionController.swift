import AVFoundation
import CallKit

/// Keeps normal playback eligible for Now Playing without taking audio away from calls.
final class PlayerAudioSessionController: NSObject, CXCallObserverDelegate {
    private let callObserver = CXCallObserver()
    private var isActive = false
    private var interruptionObserver: NSObjectProtocol?
    private var spatialAudioObservers: [NSObjectProtocol] = []
    private var spatialAudioEnabled = false
    var onConfigurationChanged: (() -> Void)?
    var onSpatialAudioEnabled: (() -> Void)?

    func activate() {
        if !isActive {
            isActive = true
            callObserver.setDelegate(self, queue: .main)
            interruptionObserver = NotificationCenter.default.addObserver(
                forName: AVAudioSession.interruptionNotification,
                object: AVAudioSession.sharedInstance(), queue: .main
            ) { [weak self] notification in
                guard let self, self.isActive,
                      let value = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                      AVAudioSession.InterruptionType(rawValue: value) == .ended else { return }
                // Restore the session, but leave the player's play/pause decision unchanged.
                self.configure()
            }
            let session = AVAudioSession.sharedInstance()
            spatialAudioEnabled = Self.isSpatialAudioEnabled(session)
            spatialAudioObservers = [
                AVAudioSession.spatialPlaybackCapabilitiesChangedNotification,
                AVAudioSession.routeChangeNotification
            ].map { name in
                NotificationCenter.default.addObserver(forName: name, object: session, queue: .main) { [weak self] _ in
                    self?.updateSpatialAudio()
                }
            }
        }
        configure()
    }

    private func updateSpatialAudio() {
        guard isActive else { return }
        let enabled = Self.isSpatialAudioEnabled(AVAudioSession.sharedInstance())
        guard enabled != spatialAudioEnabled else { return }
        spatialAudioEnabled = enabled
        if enabled { onSpatialAudioEnabled?() }
    }

    private static func isSpatialAudioEnabled(_ session: AVAudioSession) -> Bool {
        session.currentRoute.outputs.contains { $0.isSpatialAudioEnabled }
    }

    func deactivate() {
        isActive = false
        callObserver.setDelegate(nil, queue: nil)
        if let interruptionObserver {
            NotificationCenter.default.removeObserver(interruptionObserver)
            self.interruptionObserver = nil
        }
        spatialAudioObservers.forEach { NotificationCenter.default.removeObserver($0) }
        spatialAudioObservers = []
        do {
            try AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        } catch {
            print("[NowPlaying] Failed to deactivate audio session: \(error)")
        }
    }

    func callObserver(_ callObserver: CXCallObserver, callChanged call: CXCall) {
        guard isActive else { return }
        // Include ringing, outgoing and held calls, and keep mixing until the last call ends.
        // Use the event for this call even if the observer's snapshot hasn't caught up yet.
        let hasCall = !call.hasEnded || callObserver.calls.contains {
            $0.uuid != call.uuid && !$0.hasEnded
        }
        configure(hasCall: hasCall)
    }

    private func configure(hasCall: Bool? = nil) {
        let session = AVAudioSession.sharedInstance()
        let mixing = hasCall ?? callObserver.calls.contains { !$0.hasEnded }
        let options: AVAudioSession.CategoryOptions = mixing ? [.mixWithOthers] : []
        do {
            if session.category != .playback || session.mode != .moviePlayback || session.categoryOptions != options {
                try session.setCategory(.playback, mode: .moviePlayback, options: options)
            }
            if !session.supportsMultichannelContent {
                do {
                    try session.setSupportsMultichannelContent(true)
                } catch {
                    print("[NowPlaying] Failed to enable multichannel content: \(error)")
                }
            }
            try session.setActive(true)
            onConfigurationChanged?()
        } catch {
            print("[NowPlaying] Failed to activate audio session (call mixing: \(mixing)): \(error)")
        }
    }

    deinit {
        if let interruptionObserver {
            NotificationCenter.default.removeObserver(interruptionObserver)
        }
        spatialAudioObservers.forEach { NotificationCenter.default.removeObserver($0) }
    }
}
