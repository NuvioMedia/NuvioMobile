import AVFoundation
import CallKit

/// Keeps normal playback eligible for Now Playing without taking audio away from calls.
final class PlayerAudioSessionController: NSObject, CXCallObserverDelegate {
    private let callObserver = CXCallObserver()
    private var isActive = false
    private var interruptionObserver: NSObjectProtocol?
    private var inactiveObserver: NSObjectProtocol?
    private var resumptionObserver: NSObjectProtocol?
    private var routeChangeObserver: NSObjectProtocol?
    var onConfigurationChanged: (() -> Void)?
    /// The current output disappeared: headphones unplugged, Bluetooth/AirPlay disconnected.
    var onOutputUnavailable: (() -> Void)?
    /// The system interrupted the session (call, alarm, Siri, another app).
    var onInterruptionBegan: (() -> Void)?
    /// The system recommends resuming after an interruption.
    var onResumeRecommended: (() -> Void)?

    func activate() {
        if !isActive {
            isActive = true
            callObserver.setDelegate(self, queue: .main)
            if #available(iOS 27.0, *) {
                inactiveObserver = NotificationCenter.default.addObserver(
                    forName: AVAudioSession.didBecomeInactiveNotification,
                    object: nil, queue: .main
                ) { [weak self] notification in
                    guard let self, self.isActive,
                          let context = notification.userInfo?[AVAudioSession.deactivationContextKey]
                            as? AVAudioSession.DeactivationContext,
                          context.source == .system else { return }
                    self.onInterruptionBegan?()
                }
                resumptionObserver = NotificationCenter.default.addObserver(
                    forName: AVAudioSession.resumptionRecommendationNotification,
                    object: nil, queue: .main
                ) { [weak self] notification in
                    guard let self, self.isActive,
                          let context = notification.userInfo?[AVAudioSession.resumptionContextKey]
                            as? AVAudioSession.ResumptionContext,
                          context.recommendation == .shouldResume else { return }
                    self.configure()
                    self.onResumeRecommended?()
                }
            } else {
                interruptionObserver = NotificationCenter.default.addObserver(
                    forName: AVAudioSession.interruptionNotification,
                    object: AVAudioSession.sharedInstance(), queue: .main
                ) { [weak self] notification in
                    guard let self, self.isActive,
                          let value = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                          let type = AVAudioSession.InterruptionType(rawValue: value) else { return }
                    switch type {
                    case .began:
                        self.onInterruptionBegan?()
                    case .ended:
                        self.configure()
                        let options = notification.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
                        if AVAudioSession.InterruptionOptions(rawValue: options).contains(.shouldResume) {
                            self.onResumeRecommended?()
                        }
                    @unknown default:
                        break
                    }
                }
            }
            // Apple: pause when the current output disappears, so audio isn't shared unexpectedly.
            routeChangeObserver = NotificationCenter.default.addObserver(
                forName: AVAudioSession.routeChangeNotification,
                object: nil, queue: .main
            ) { [weak self] notification in
                guard let self, self.isActive,
                      let value = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                      AVAudioSession.RouteChangeReason(rawValue: value) == .oldDeviceUnavailable else { return }
                let previous = notification.userInfo?[AVAudioSessionRouteChangePreviousRouteKey]
                    as? AVAudioSessionRouteDescription
                // Built-in speaker/receiver changes can come from call routing; they are not output loss.
                guard !Self.isBuiltInOnly(previous) else { return }
                self.onOutputUnavailable?()
            }
        }
        configure()
    }

    func deactivate() {
        isActive = false
        callObserver.setDelegate(nil, queue: nil)
        if let interruptionObserver {
            NotificationCenter.default.removeObserver(interruptionObserver)
            self.interruptionObserver = nil
        }
        if let inactiveObserver {
            NotificationCenter.default.removeObserver(inactiveObserver)
            self.inactiveObserver = nil
        }
        if let resumptionObserver {
            NotificationCenter.default.removeObserver(resumptionObserver)
            self.resumptionObserver = nil
        }
        if let routeChangeObserver {
            NotificationCenter.default.removeObserver(routeChangeObserver)
            self.routeChangeObserver = nil
        }
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
        // Declare video so iOS does not present playback as music; long-form policies cannot mix.
        let policy: AVAudioSession.RouteSharingPolicy = mixing ? .default : .longFormVideo
        do {
            if session.category != .playback || session.mode != .moviePlayback ||
                session.categoryOptions != options || session.routeSharingPolicy != policy {
                try session.setCategory(.playback, mode: .moviePlayback, policy: policy, options: options)
            }
            try session.setActive(true)
            onConfigurationChanged?()
        } catch {
            print("[NowPlaying] Failed to activate audio session (call mixing: \(mixing)): \(error)")
        }
    }

    private static func isBuiltInOnly(_ route: AVAudioSessionRouteDescription?) -> Bool {
        guard let route, !route.outputs.isEmpty else { return false }
        return route.outputs.allSatisfy {
            $0.portType == .builtInSpeaker || $0.portType == .builtInReceiver
        }
    }

    deinit {
        if let interruptionObserver {
            NotificationCenter.default.removeObserver(interruptionObserver)
        }
        if let inactiveObserver {
            NotificationCenter.default.removeObserver(inactiveObserver)
        }
        if let resumptionObserver {
            NotificationCenter.default.removeObserver(resumptionObserver)
        }
        if let routeChangeObserver {
            NotificationCenter.default.removeObserver(routeChangeObserver)
        }
    }
}
