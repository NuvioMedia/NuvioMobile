import Combine
import UIKit

@MainActor
final class SystemUI: ObservableObject {
    static let shared = SystemUI()

    private init() {}

    private(set) weak var activePlayer: UIViewController?
    @Published private(set) var isPlayerImmersive = false

    func playerDidBecomeVisible(_ player: UIViewController) {
        guard activePlayer !== player else { return }
        activePlayer = player
        isPlayerImmersive = true
        refresh()
    }

    func playerDidBecomeHidden(_ player: UIViewController) {
        guard activePlayer === player || activePlayer == nil else { return }
        activePlayer = nil
        isPlayerImmersive = false
        refresh()
    }

    private func refresh() {
        for scene in UIApplication.shared.connectedScenes {
            guard let windowScene = scene as? UIWindowScene else { continue }
            for window in windowScene.windows {
                guard let root = window.rootViewController else { continue }
                markNeedsUpdate(root)
            }
        }
    }

    private func markNeedsUpdate(_ controller: UIViewController) {
        controller.setNeedsUpdateOfHomeIndicatorAutoHidden()
        controller.setNeedsUpdateOfScreenEdgesDeferringSystemGestures()
        controller.setNeedsStatusBarAppearanceUpdate()

        for child in controller.children {
            markNeedsUpdate(child)
        }
        if let presented = controller.presentedViewController {
            markNeedsUpdate(presented)
        }
    }
}
