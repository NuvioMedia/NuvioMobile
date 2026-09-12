import Combine
import SwiftUI
import UIKit
import ComposeApp

private let nuvioBackgroundColor = UIColor(
    red: 0.051,
    green: 0.051,
    blue: 0.051,
    alpha: 1.0
)

private enum NuvioComposeHost {
    static let registerPlayerBridge: Void = {
        NuvioPlayerRegistration.register()
    }()

    static func wrap(
        _ contentController: UIViewController,
        disablesInteractiveContentPopGesture: Bool = false,
        onTabBarControllerAvailable: ((UITabBarController) -> Void)? = nil
    ) -> RootComposeViewController {
        _ = registerPlayerBridge
        contentController.view.backgroundColor = nuvioBackgroundColor
        return RootComposeViewController(
            contentController: contentController,
            disablesInteractiveContentPopGesture: disablesInteractiveContentPopGesture,
            onTabBarControllerAvailable: onTabBarControllerAvailable
        )
    }
}

/// A navigation-neutral container for Compose. The MPV player is nested below the
/// Compose controller, so UIKit's immersive-system-UI queries need to be forwarded
/// to the deepest child that requests them.
final class RootComposeViewController: UIViewController {
    private let contentController: UIViewController
    private let disablesInteractiveContentPopGesture: Bool
    private let onTabBarControllerAvailable: ((UITabBarController) -> Void)?

    init(
        contentController: UIViewController,
        disablesInteractiveContentPopGesture: Bool,
        onTabBarControllerAvailable: ((UITabBarController) -> Void)?
    ) {
        self.contentController = contentController
        self.disablesInteractiveContentPopGesture = disablesInteractiveContentPopGesture
        self.onTabBarControllerAvailable = onTabBarControllerAvailable
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        view.backgroundColor = nuvioBackgroundColor
        contentController.view.backgroundColor = nuvioBackgroundColor

        addChild(contentController)
        view.addSubview(contentController.view)
        contentController.view.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            contentController.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            contentController.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            contentController.view.topAnchor.constraint(equalTo: view.topAnchor),
            contentController.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        contentController.didMove(toParent: self)
    }

    override var childForHomeIndicatorAutoHidden: UIViewController? {
        immersiveController(in: contentController) ?? contentController
    }

    override var childForScreenEdgesDeferringSystemGestures: UIViewController? {
        immersiveController(in: contentController) ?? contentController
    }

    override var childForStatusBarHidden: UIViewController? {
        immersiveController(in: contentController) ?? contentController
    }

    override var prefersHomeIndicatorAutoHidden: Bool {
        immersiveController(in: contentController)?.prefersHomeIndicatorAutoHidden ?? false
    }

    override var preferredScreenEdgesDeferringSystemGestures: UIRectEdge {
        immersiveController(in: contentController)?.preferredScreenEdgesDeferringSystemGestures ?? []
    }

    override var prefersStatusBarHidden: Bool {
        immersiveController(in: contentController)?.prefersStatusBarHidden ?? false
    }

    override var preferredStatusBarStyle: UIStatusBarStyle {
        .lightContent
    }

    override var preferredStatusBarUpdateAnimation: UIStatusBarAnimation {
        .fade
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        configureBackGestures(isVisible: true)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        configureBackGestures(isVisible: true)
        if let tabBarController {
            onTabBarControllerAvailable?(tabBarController)
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        configureBackGestures(isVisible: false)
        super.viewWillDisappear(animated)
    }

    func refreshImmersiveSystemUI() {
        setNeedsUpdateOfHomeIndicatorAutoHidden()
        setNeedsUpdateOfScreenEdgesDeferringSystemGestures()
        setNeedsStatusBarAppearanceUpdate()
    }

    private func configureBackGestures(isVisible: Bool) {
        if #available(iOS 26.0, *) {
            navigationController?.interactiveContentPopGestureRecognizer?.isEnabled = false
        }
        navigationController?.interactivePopGestureRecognizer?.isEnabled =
            isVisible ? !disablesInteractiveContentPopGesture : true
    }

    private func immersiveController(in controller: UIViewController?) -> UIViewController? {
        guard let controller else { return nil }

        if controller.prefersHomeIndicatorAutoHidden ||
            !controller.preferredScreenEdgesDeferringSystemGestures.isEmpty ||
            controller.prefersStatusBarHidden {
            return controller
        }

        if let presented = immersiveController(in: controller.presentedViewController) {
            return presented
        }

        for child in controller.children.reversed() {
            if let immersiveChild = immersiveController(in: child) {
                return immersiveChild
            }
        }

        return nil
    }
}

// MARK: - UIKit fallback

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        NuvioComposeHost.wrap(MainViewControllerKt.MainViewController())
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

// MARK: - Native iOS navigation

@available(iOS 16.0, *)
struct RouteWrapper: Hashable, Identifiable {
    let id = UUID()
    let route: AppRoute

    static func == (lhs: RouteWrapper, rhs: RouteWrapper) -> Bool {
        lhs.id == rhs.id
    }

    func hash(into hasher: inout Hasher) {
        hasher.combine(id)
    }
}

@available(iOS 16.0, *)
@MainActor
final class TabNavigationCoordinator: ObservableObject {
    @Published var path: [RouteWrapper] = []

    func push(_ route: AppRoute, launchSingleTop: Bool) {
        if launchSingleTop,
           path.last?.route.navigationIdentity == route.navigationIdentity {
            AppKt.disposeRoute(route: route)
            return
        }
        path.append(RouteWrapper(route: route))
    }

    func pop() {
        guard !path.isEmpty else { return }
        var updatedPath = path
        updatedPath.removeLast()
        setPath(updatedPath)
    }

    func replace(_ route: AppRoute) {
        var updatedPath = path
        if updatedPath.isEmpty {
            updatedPath.append(RouteWrapper(route: route))
        } else {
            updatedPath[updatedPath.index(before: updatedPath.endIndex)] = RouteWrapper(route: route)
        }
        setPath(updatedPath)
    }

    func popToRoot() {
        setPath([])
    }

    /// Used by NavigationStack's path binding so interactive swipe-back and
    /// programmatic mutations share the same Kotlin route-disposal behavior.
    func setPath(_ newPath: [RouteWrapper]) {
        let retainedIDs = Set(newPath.map(\.id))
        let removedRoutes = path
            .filter { !retainedIDs.contains($0.id) }
            .map(\.route)

        path = newPath
        removedRoutes.forEach { AppKt.disposeRoute(route: $0) }
    }
}

@available(iOS 16.0, *)
enum NuvioAppTab: String, CaseIterable, Hashable {
    case home = "Home"
    case search = "Search"
    case library = "Library"
    case settings = "Settings"

    var fallbackTitle: String {
        String(localized: String.LocalizationValue(rawValue))
    }

    static func from(kotlinName: String?) -> NuvioAppTab? {
        switch kotlinName?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "home": return .home
        case "search": return .search
        case "library": return .library
        case "settings", "profile": return .settings
        default: return nil
        }
    }

    var iconAssetName: String {
        switch self {
        case .home: return "NuvioTabHome"
        case .search: return "NuvioTabSearch"
        case .library: return "NuvioTabLibrary"
        case .settings: return "NuvioTabProfile"
        }
    }

    var fallbackSystemImage: String {
        switch self {
        case .home: return "house.fill"
        case .search: return "magnifyingglass"
        case .library: return "rectangle.stack.fill"
        case .settings: return "person.crop.circle.fill"
        }
    }
}

private enum NuvioNativeTabIcon {
    private static let legacyStaticIconSize = CGSize(width: 25, height: 25)

    static func image(for tab: NuvioAppTab) -> UIImage {
        if let asset = UIImage(named: tab.iconAssetName) {
            return UIGraphicsImageRenderer(size: legacyStaticIconSize).image { _ in
                asset
                    .withRenderingMode(.alwaysOriginal)
                    .draw(in: CGRect(origin: .zero, size: legacyStaticIconSize))
            }.withRenderingMode(.alwaysTemplate)
        }

        return (UIImage(systemName: tab.fallbackSystemImage) ?? UIImage())
            .withRenderingMode(.alwaysTemplate)
    }

    static func profileAvatar(
        name: String?,
        avatarColor: UIColor?,
        backgroundColor: UIColor?,
        avatarImage: UIImage?,
        selected: Bool,
        accent: UIColor
    ) -> UIImage {
        guard name != nil || avatarColor != nil || avatarImage != nil else {
            return image(for: .settings)
        }

        let size = CGSize(width: 28, height: 28)
        let baseColor = avatarColor
            ?? UIColor(red: 30 / 255, green: 136 / 255, blue: 229 / 255, alpha: 1)
        let fillColor = backgroundColor ?? baseColor.withAlphaComponent(0.15)
        let borderColor = selected ? accent : baseColor.withAlphaComponent(0.5)
        let initial = name?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .prefix(1)
            .uppercased() ?? ""

        return UIGraphicsImageRenderer(size: size).image { _ in
            let rect = CGRect(origin: .zero, size: size).insetBy(dx: 1, dy: 1)
            fillColor.setFill()
            UIBezierPath(ovalIn: rect).fill()

            if let avatarImage {
                UIBezierPath(ovalIn: rect).addClip()
                drawAspectFill(image: avatarImage, in: rect)
            } else if !initial.isEmpty {
                let font = UIFont.systemFont(ofSize: size.height * 0.45, weight: .bold)
                let attributes: [NSAttributedString.Key: Any] = [
                    .font: font,
                    .foregroundColor: baseColor,
                ]
                let textSize = initial.size(withAttributes: attributes)
                initial.draw(
                    at: CGPoint(
                        x: rect.midX - textSize.width / 2,
                        y: rect.midY - textSize.height / 2
                    ),
                    withAttributes: attributes
                )
            } else {
                image(for: .settings)
                    .withTintColor(baseColor, renderingMode: .alwaysOriginal)
                    .draw(in: rect.insetBy(dx: 5.5, dy: 5.5))
            }

            borderColor.setStroke()
            let borderPath = UIBezierPath(ovalIn: rect.insetBy(dx: 0.75, dy: 0.75))
            borderPath.lineWidth = 1.5
            borderPath.stroke()
        }.withRenderingMode(.alwaysOriginal)
    }

    private static func drawAspectFill(image: UIImage, in rect: CGRect) {
        guard image.size.width > 0, image.size.height > 0 else { return }
        let scale = max(rect.width / image.size.width, rect.height / image.size.height)
        let drawSize = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        image.draw(
            in: CGRect(
                x: rect.midX - drawSize.width / 2,
                y: rect.midY - drawSize.height / 2,
                width: drawSize.width,
                height: drawSize.height
            )
        )
    }
}

@available(iOS 16.0, *)
final class NativeTabIconStore: ObservableObject {
    private static let chromeDidChange = Notification.Name("NuvioNativeTabChromeDidChange")
    private static let accentKey = "NuvioNativeTabAccentColor"
    private static let profileNameKey = "NuvioNativeProfileName"
    private static let profileColorKey = "NuvioNativeProfileAvatarColor"
    private static let profileURLKey = "NuvioNativeProfileAvatarURL"
    private static let profileBackgroundKey = "NuvioNativeProfileAvatarBackgroundColor"

    @Published private(set) var revision = 0
    @Published private(set) var accentColor = UIColor(
        red: 0.96,
        green: 0.96,
        blue: 0.96,
        alpha: 1
    )

    private var observer: NSObjectProtocol?
    private var profileAvatarURL: String?
    private var profileAvatarImage: UIImage?
    private var profileAvatarTask: URLSessionDataTask?

    init() {
        UITabBar.appearance().unselectedItemTintColor = UIColor(
            red: 150 / 255,
            green: 156 / 255,
            blue: 163 / 255,
            alpha: 1
        )
        observer = NotificationCenter.default.addObserver(
            forName: Self.chromeDidChange,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.reload()
        }
        reload()
    }

    deinit {
        if let observer {
            NotificationCenter.default.removeObserver(observer)
        }
        profileAvatarTask?.cancel()
    }

    func image(for tab: NuvioAppTab, selected: Bool) -> UIImage {
        guard tab == .settings else {
            return NuvioNativeTabIcon.image(for: tab)
        }

        let defaults = UserDefaults.standard
        return NuvioNativeTabIcon.profileAvatar(
            name: defaults.string(forKey: Self.profileNameKey),
            avatarColor: UIColor(hexString: defaults.string(forKey: Self.profileColorKey)),
            backgroundColor: UIColor(hexString: defaults.string(forKey: Self.profileBackgroundKey)),
            avatarImage: profileAvatarImage,
            selected: selected,
            accent: accentColor
        )
    }

    private func reload() {
        let defaults = UserDefaults.standard
        accentColor = UIColor(hexString: defaults.string(forKey: Self.accentKey))
            ?? UIColor(red: 0.96, green: 0.96, blue: 0.96, alpha: 1)

        let nextURL = defaults.string(forKey: Self.profileURLKey)
        guard nextURL != profileAvatarURL else {
            revision &+= 1
            return
        }

        profileAvatarTask?.cancel()
        profileAvatarTask = nil
        profileAvatarURL = nextURL
        profileAvatarImage = nil
        revision &+= 1

        guard let nextURL, let url = URL(string: nextURL) else { return }
        profileAvatarTask = URLSession.shared.dataTask(with: url) { [weak self] data, _, _ in
            guard let data, let image = UIImage(data: data) else { return }
            DispatchQueue.main.async {
                guard let self, self.profileAvatarURL == nextURL else { return }
                self.profileAvatarImage = image
                self.revision &+= 1
            }
        }
        profileAvatarTask?.resume()
    }
}

@available(iOS 16.0, *)
@MainActor
final class NativeProfileTabInteractionCoordinator: NSObject, UIGestureRecognizerDelegate {
    var onLongPress: (() -> Void)?
    private(set) var isHandlingLongPress = false
    private(set) var suppressesProfileSelection = false
    private weak var tabBarController: UITabBarController?
    private var selectedIndexBeforeLongPress: Int?
    private let competingRecognizers = NSHashTable<UIGestureRecognizer>.weakObjects()
    private lazy var recognizer: UILongPressGestureRecognizer = {
        let recognizer = UILongPressGestureRecognizer(
            target: self,
            action: #selector(handleLongPress(_:))
        )
        recognizer.minimumPressDuration = 0.45
        recognizer.cancelsTouchesInView = true
        recognizer.delegate = self
        return recognizer
    }()

    func attach(to tabBarController: UITabBarController) {
        guard self.tabBarController !== tabBarController else { return }
        self.tabBarController?.tabBar.removeGestureRecognizer(recognizer)
        tabBarController.tabBar.addGestureRecognizer(recognizer)
        self.tabBarController = tabBarController
    }

    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldReceive touch: UITouch
    ) -> Bool {
        guard gestureRecognizer === recognizer,
              let tabBar = tabBarController?.tabBar,
              let profileItem = tabBar.items?.last else {
            return false
        }
        competingRecognizers.removeAllObjects()
        guard #available(iOS 17.0, *),
              let profileFrame = profileItem.frame(in: tabBar) else { return false }
        return profileFrame.contains(touch.location(in: tabBar))
    }

    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        guard gestureRecognizer === recognizer || otherGestureRecognizer === recognizer else {
            return false
        }
        competingRecognizers.add(
            gestureRecognizer === recognizer ? otherGestureRecognizer : gestureRecognizer
        )
        return true
    }

    @objc private func handleLongPress(_ recognizer: UILongPressGestureRecognizer) {
        switch recognizer.state {
        case .began:
            selectedIndexBeforeLongPress = tabBarController?.selectedIndex
            isHandlingLongPress = true
            suppressesProfileSelection = true
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            onLongPress?()
            competingRecognizers.allObjects.forEach { competingRecognizer in
                competingRecognizer.isEnabled = false
                competingRecognizer.isEnabled = true
            }
            if let selectedIndexBeforeLongPress {
                tabBarController?.selectedIndex = selectedIndexBeforeLongPress
            }
        case .ended, .cancelled, .failed:
            let selectedIndex = selectedIndexBeforeLongPress
            isHandlingLongPress = false
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                if let selectedIndex {
                    self.tabBarController?.selectedIndex = selectedIndex
                }
                self.selectedIndexBeforeLongPress = nil
                self.suppressesProfileSelection = false
            }
        default:
            break
        }
    }
}

@available(iOS 16.0, *)
@MainActor
final class AppNavigationCoordinator: ObservableObject {
    @Published var selectedTab: NuvioAppTab = .home
    @Published private(set) var isMainContentMounted = false
    @Published private(set) var isMainContentVisible = false
    @Published private(set) var isAppReady = false
    @Published private var localizedTabTitles: [NuvioAppTab: String] = [:]
    @Published private(set) var localizedSwitchProfileTitle = ""
    @Published private(set) var localizedAddProfileTitle = ""
    @Published var isProfileSwitcherPresented = false

    let homeCoordinator = TabNavigationCoordinator()
    let searchCoordinator = TabNavigationCoordinator()
    let libraryCoordinator = TabNavigationCoordinator()
    let settingsCoordinator = TabNavigationCoordinator()
    let appGateController = AppGateController()
    let profileSwitcherController = NativeProfileSwitcherController()
    let profileTabInteraction = NativeProfileTabInteractionCoordinator()

    init() {
        profileTabInteraction.onLongPress = { [weak self] in
            guard let self, self.isAppReady else { return }
            self.isProfileSwitcherPresented = true
        }
    }

    private var allCoordinators: [TabNavigationCoordinator] {
        [homeCoordinator, searchCoordinator, libraryCoordinator, settingsCoordinator]
    }

    func coordinator(for tab: NuvioAppTab) -> TabNavigationCoordinator {
        switch tab {
        case .home: return homeCoordinator
        case .search: return searchCoordinator
        case .library: return libraryCoordinator
        case .settings: return settingsCoordinator
        }
    }

    func activateTab(named tabName: String) {
        guard let tab = NuvioAppTab.from(kotlinName: tabName) else { return }
        if tab == .home || isAppReady {
            selectedTab = tab
        }
    }

    func title(for tab: NuvioAppTab) -> String {
        localizedTabTitles[tab] ?? tab.fallbackTitle
    }

    func updateTabTitles(
        home: String,
        search: String,
        library: String,
        profile: String,
        switchProfile: String,
        addProfile: String
    ) {
        localizedTabTitles = [
            .home: home,
            .search: search,
            .library: library,
            .settings: profile,
        ]
        localizedSwitchProfileTitle = switchProfile
        localizedAddProfileTitle = addProfile
    }

    func updateAppReady(_ ready: Bool) {
        isAppReady = ready
        if !ready {
            isProfileSwitcherPresented = false
            allCoordinators.forEach { $0.popToRoot() }
        }
    }

    func setMainContentMounted(_ mounted: Bool) {
        isMainContentMounted = mounted
        if !mounted {
            isMainContentVisible = false
            selectedTab = .home
        }
    }

    func setMainContentVisible(_ visible: Bool) {
        isMainContentVisible = visible
    }

    func openProfileManagement() {
        isProfileSwitcherPresented = false
        profileSwitcherController.requestManageProfiles()
    }

    func tab(for target: TabNavigationCoordinator) -> NuvioAppTab? {
        NuvioAppTab.allCases.first { coordinator(for: $0) === target }
    }

    func push(
        _ route: AppRoute,
        from origin: TabNavigationCoordinator,
        launchSingleTop: Bool
    ) {
        guard isAppReady else {
            AppKt.disposeRoute(route: route)
            return
        }
        let targetTab = NuvioAppTab.from(kotlinName: route.preferredTabName)
            ?? tab(for: origin)
            ?? selectedTab
        let target = coordinator(for: targetTab)
        selectedTab = targetTab
        target.push(route, launchSingleTop: launchSingleTop)
    }

    func replace(_ route: AppRoute, in target: TabNavigationCoordinator) {
        guard isAppReady else {
            AppKt.disposeRoute(route: route)
            return
        }
        if let targetTab = tab(for: target) {
            selectedTab = targetTab
        }
        target.replace(route)
    }
}

@available(iOS 16.0, *)
struct NativeNavComposeView: UIViewControllerRepresentable {
    let tab: NuvioAppTab
    let usesNativeTabBar: Bool
    let usesTabletFloatingTabBar: Bool
    let coordinator: TabNavigationCoordinator
    let appCoordinator: AppNavigationCoordinator

    func makeUIViewController(context: Context) -> UIViewController {
        let controller = MainViewControllerKt.MainViewController(
            initialTabName: tab.rawValue,
            useNativeTabBar: usesNativeTabBar,
            useTabletFloatingTabBar: usesTabletFloatingTabBar,
            onNavigate: { route, launchSingleTop in
                appCoordinator.push(
                    route,
                    from: coordinator,
                    launchSingleTop: launchSingleTop.boolValue
                )
            },
            onGoBack: {
                coordinator.pop()
            },
            onReplace: { route in
                appCoordinator.replace(route, in: coordinator)
            },
            onActivate: { tabName in
                appCoordinator.activateTab(named: tabName)
            },
            onTabTitles: { home, search, library, profile, switchProfile, addProfile in
                appCoordinator.updateTabTitles(
                    home: home,
                    search: search,
                    library: library,
                    profile: profile,
                    switchProfile: switchProfile,
                    addProfile: addProfile
                )
            },
            appGateController: appCoordinator.appGateController
        )
        return NuvioComposeHost.wrap(
            controller,
            onTabBarControllerAvailable: { tabBarController in
                appCoordinator.profileTabInteraction.attach(to: tabBarController)
            }
        )
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

@available(iOS 16.0, *)
struct AppGateComposeView: UIViewControllerRepresentable {
    let appCoordinator: AppNavigationCoordinator

    func makeUIViewController(context: Context) -> UIViewController {
        let controller = MainViewControllerKt.AppGateViewController(
            appGateController: appCoordinator.appGateController,
            nativeProfileSwitcherController: appCoordinator.profileSwitcherController,
            onActivate: { tabName in
                appCoordinator.activateTab(named: tabName)
            },
            onAppReady: { ready in
                appCoordinator.updateAppReady(ready.boolValue)
            },
            onMainContentMountChanged: { mounted in
                appCoordinator.setMainContentMounted(mounted.boolValue)
            },
            onMainContentVisibleChanged: { visible in
                appCoordinator.setMainContentVisible(visible.boolValue)
            }
        )
        controller.view.backgroundColor = .clear
        controller.view.isOpaque = false
        return controller
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

@available(iOS 16.0, *)
struct DetailComposeView: UIViewControllerRepresentable {
    let route: AppRoute
    let coordinator: TabNavigationCoordinator
    let appCoordinator: AppNavigationCoordinator

    func makeUIViewController(context: Context) -> UIViewController {
        let controller = MainViewControllerKt.ScreenViewController(
            route: route,
            onNavigate: { newRoute, launchSingleTop in
                appCoordinator.push(
                    newRoute,
                    from: coordinator,
                    launchSingleTop: launchSingleTop.boolValue
                )
            },
            onGoBack: {
                coordinator.pop()
            },
            onReplace: { newRoute in
                appCoordinator.replace(newRoute, in: coordinator)
            },
            onActivate: { tabName in
                appCoordinator.activateTab(named: tabName)
            },
            appGateController: appCoordinator.appGateController
        )
        return NuvioComposeHost.wrap(
            controller,
            disablesInteractiveContentPopGesture: route is PlayerRoute
        )
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

@available(iOS 16.0, *)
struct TabContentView: View {
    let tab: NuvioAppTab
    let usesNativeTabBar: Bool
    let usesTabletFloatingTabBar: Bool
    @ObservedObject var coordinator: TabNavigationCoordinator
    @ObservedObject var appCoordinator: AppNavigationCoordinator

    var body: some View {
        NavigationStack(
            path: Binding(
                get: { coordinator.path },
                set: { coordinator.setPath($0) }
            )
        ) {
            NativeNavComposeView(
                tab: tab,
                usesNativeTabBar: usesNativeTabBar,
                usesTabletFloatingTabBar: usesTabletFloatingTabBar,
                coordinator: coordinator,
                appCoordinator: appCoordinator
            )
            .ignoresSafeArea(.all)
            .navigationTitle(appCoordinator.title(for: tab))
            .navigationBarHidden(true)
            .navigationDestination(for: RouteWrapper.self) { wrapper in
                if appCoordinator.selectedTab == tab {
                    DetailDestinationView(
                        wrapper: wrapper,
                        coordinator: coordinator,
                        appCoordinator: appCoordinator
                    )
                    // A native replace keeps the same NavigationStack depth.
                    // Keying by the wrapper forces SwiftUI to replace the
                    // embedded Compose controller instead of reusing the old
                    // screen with the new route's toolbar preferences.
                    .id(wrapper.id)
                } else {
                    Color.clear
                }
            }
        }
        // Tab-bar visibility is a preference emitted by the active navigation
        // stack. Applying it here keeps the authentication/profile gate truly
        // full-screen on iOS 26, where a modifier on TabView itself is ignored.
        .toolbar(
            usesNativeTabBar && appCoordinator.isMainContentVisible && coordinator.path.isEmpty
                ? Visibility.visible
                : Visibility.hidden,
            for: .tabBar
        )
    }
}

@available(iOS 16.0, *)
private struct NativeToolbarReadabilityFade: View {
    var body: some View {
        Rectangle()
            .fill(
                LinearGradient(
                    stops: [
                        .init(color: Color(uiColor: nuvioBackgroundColor), location: 0),
                        .init(color: Color(uiColor: nuvioBackgroundColor).opacity(0.78), location: 0.55),
                        .init(color: Color(uiColor: nuvioBackgroundColor).opacity(0), location: 1),
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
            )
            .frame(height: 120)
            .ignoresSafeArea(edges: .top)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}

@available(iOS 16.0, *)
private struct DetailDestinationView: View {
    let wrapper: RouteWrapper
    @ObservedObject var coordinator: TabNavigationCoordinator
    @ObservedObject var appCoordinator: AppNavigationCoordinator

    private var usesComposeNavigationHeader: Bool {
        wrapper.route is DetailRoute || wrapper.route is StreamRoute
    }

    private var respectsNativeNavigationSafeArea: Bool {
        wrapper.route is FolderDetailRoute
    }

    private var hidesNativeNavigationBar: Bool {
        wrapper.route.hidesNavigationBar
    }

    private var showsReadabilityFade: Bool {
        !hidesNativeNavigationBar && !usesComposeNavigationHeader
    }

    private var content: some View {
        ZStack(alignment: .top) {
            if respectsNativeNavigationSafeArea {
                DetailComposeView(
                    route: wrapper.route,
                    coordinator: coordinator,
                    appCoordinator: appCoordinator
                )
                .ignoresSafeArea(.all, edges: [.horizontal, .bottom])
            } else {
                DetailComposeView(
                    route: wrapper.route,
                    coordinator: coordinator,
                    appCoordinator: appCoordinator
                )
                .ignoresSafeArea(.all)
            }

            if showsReadabilityFade {
                NativeToolbarReadabilityFade()
            }
        }
        .navigationTitle(wrapper.route.title ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarRole(usesComposeNavigationHeader ? .editor : .automatic)
        .toolbar {
            if usesComposeNavigationHeader {
                ToolbarItem(placement: .principal) {
                    Color.clear.frame(width: 1, height: 1)
                }
            }
        }
        .toolbar(.hidden, for: .tabBar)
        .toolbar(
            hidesNativeNavigationBar ? Visibility.hidden : Visibility.visible,
            for: .navigationBar
        )
    }

    @ViewBuilder
    var body: some View {
        if #available(iOS 26.0, *), !usesComposeNavigationHeader {
            content.navigationSubtitle(wrapper.route.subtitle ?? "")
        } else {
            content
        }
    }
}

@available(iOS 26.0, *)
private struct NativeProfileItem: Identifiable, Equatable {
    let id: Int32
    let name: String
    let avatarColor: UIColor
    let avatarBackgroundColor: UIColor
    let avatarURL: URL?
    let pinEnabled: Bool
    let active: Bool

    init(_ option: NativeProfileOption) {
        id = option.profileIndex
        name = option.name
        avatarColor = UIColor(hexString: option.avatarColorHex)
            ?? UIColor(red: 30 / 255, green: 136 / 255, blue: 229 / 255, alpha: 1)
        avatarBackgroundColor = UIColor(hexString: option.avatarBackgroundColorHex)
            ?? avatarColor.withAlphaComponent(0.16)
        avatarURL = option.avatarImageUrl.flatMap(URL.init(string:))
        pinEnabled = option.pinEnabled
        active = option.active
    }
}

@available(iOS 26.0, *)
@MainActor
private final class NativeProfileSwitcherViewModel: ObservableObject {
    @Published private(set) var profiles: [NativeProfileItem] = []
    @Published private(set) var isLoaded = false
    @Published private(set) var canAddProfile = false
    @Published var lockedProfile: NativeProfileItem?
    @Published var pin = ""
    @Published var errorMessage: String?
    @Published private(set) var isSubmitting = false

    private let controller: NativeProfileSwitcherController

    init(controller: NativeProfileSwitcherController) {
        self.controller = controller
        apply(controller.currentState())
    }

    func startObserving() {
        controller.observeState { [weak self] state in
            self?.apply(state)
        }
    }

    func stopObserving() {
        controller.stopObserving()
    }

    func choose(_ profile: NativeProfileItem, onComplete: @escaping () -> Void) {
        if profile.pinEnabled {
            lockedProfile = profile
            pin = ""
            errorMessage = nil
        } else {
            submit(profile, pin: nil, onComplete: onComplete)
        }
    }

    func updatePin(_ value: String) {
        pin = String(value.filter(\.isNumber).prefix(4))
        errorMessage = nil
    }

    func unlock(onComplete: @escaping () -> Void) {
        guard let lockedProfile, pin.count == 4 else { return }
        submit(lockedProfile, pin: pin, onComplete: onComplete)
    }

    func cancelUnlock() {
        lockedProfile = nil
        pin = ""
        errorMessage = nil
    }

    private func apply(_ state: NativeProfileSwitcherState) {
        profiles = state.profiles.map(NativeProfileItem.init)
        isLoaded = state.isLoaded
        canAddProfile = state.canAddProfile
    }

    private func submit(
        _ profile: NativeProfileItem,
        pin: String?,
        onComplete: @escaping () -> Void
    ) {
        guard !isSubmitting else { return }
        isSubmitting = true
        errorMessage = nil
        controller.chooseProfile(profileIndex: profile.id, pin: pin) { [weak self] result in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.isSubmitting = false
                if result.unlocked {
                    onComplete()
                } else if let message = result.message, !message.isEmpty {
                    self.errorMessage = message
                } else if result.retryAfterSeconds > 0 {
                    self.errorMessage = "Try again in \(result.retryAfterSeconds) seconds."
                } else {
                    self.errorMessage = "Incorrect PIN."
                }
            }
        }
    }
}

@available(iOS 26.0, *)
private struct NativeProfileAvatarView: View {
    let profile: NativeProfileItem

    var body: some View {
        ZStack {
            Circle().fill(Color(uiColor: profile.avatarBackgroundColor))
            if let avatarURL = profile.avatarURL {
                AsyncImage(url: avatarURL) { phase in
                    if let image = phase.image {
                        image
                            .resizable()
                            .scaledToFill()
                    } else {
                        initial
                    }
                }
            } else {
                initial
            }
        }
        .clipShape(Circle())
        .overlay {
            Circle().strokeBorder(
                Color(uiColor: profile.avatarColor).opacity(profile.active ? 1 : 0.45),
                lineWidth: profile.active ? 2.5 : 1.5
            )
        }
    }

    private var initial: some View {
        Text(profile.name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(1).uppercased())
            .font(.system(size: 20, weight: .bold, design: .rounded))
            .foregroundStyle(Color(uiColor: profile.avatarColor))
    }
}

@available(iOS 26.0, *)
private struct NativeProfileSwitcherView: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var model: NativeProfileSwitcherViewModel
    let title: String
    let addProfileTitle: String
    let onManageProfiles: () -> Void

    init(
        controller: NativeProfileSwitcherController,
        title: String,
        addProfileTitle: String,
        onManageProfiles: @escaping () -> Void
    ) {
        _model = StateObject(
            wrappedValue: NativeProfileSwitcherViewModel(controller: controller)
        )
        self.title = title
        self.addProfileTitle = addProfileTitle
        self.onManageProfiles = onManageProfiles
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(title)
                .font(.headline)

            if model.isLoaded {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .top, spacing: 14) {
                        ForEach(model.profiles) { profile in
                            Button {
                                model.choose(profile, onComplete: dismiss.callAsFunction)
                            } label: {
                                VStack(spacing: 6) {
                                    NativeProfileAvatarView(profile: profile)
                                        .frame(width: 52, height: 52)
                                        .overlay(alignment: .bottomTrailing) {
                                            if profile.pinEnabled {
                                                Image(systemName: "lock.fill")
                                                    .font(.system(size: 9, weight: .bold))
                                                    .foregroundStyle(.white)
                                                    .frame(width: 18, height: 18)
                                                    .background(.black.opacity(0.72), in: Circle())
                                            }
                                        }

                                    Text(profile.name)
                                        .font(.caption)
                                        .lineLimit(1)
                                        .frame(width: 64)
                                }
                            }
                            .buttonStyle(.plain)
                            .disabled(model.isSubmitting)
                        }

                        if model.canAddProfile {
                            Button {
                                onManageProfiles()
                            } label: {
                                VStack(spacing: 6) {
                                    Image(systemName: "plus")
                                        .font(.system(size: 19, weight: .semibold))
                                        .frame(width: 52, height: 52)
                                        .background(.secondary.opacity(0.12), in: Circle())
                                    Text(addProfileTitle)
                                        .font(.caption)
                                        .multilineTextAlignment(.center)
                                        .frame(width: 64)
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            } else {
                ProgressView()
                    .frame(maxWidth: .infinity)
            }

            if let lockedProfile = model.lockedProfile {
                Divider()
                Text("Enter PIN for \(lockedProfile.name)")
                    .font(.subheadline.weight(.semibold))

                SecureField("4-digit PIN", text: Binding(
                    get: { model.pin },
                    set: model.updatePin
                ))
                .keyboardType(.numberPad)
                .textContentType(.password)
                .multilineTextAlignment(.center)
                .font(.title3.monospacedDigit())
                .padding(.horizontal, 12)
                .frame(height: 42)
                .background(.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 12))

                if let errorMessage = model.errorMessage {
                    Text(errorMessage)
                        .font(.caption)
                        .foregroundStyle(.red)
                        .fixedSize(horizontal: false, vertical: true)
                }

                HStack {
                    Button("Cancel", action: model.cancelUnlock)
                    Spacer()
                    Button("Unlock") {
                        model.unlock(onComplete: dismiss.callAsFunction)
                    }
                    .disabled(model.pin.count != 4 || model.isSubmitting)
                }
            }
        }
        .padding(18)
        .frame(minWidth: 250, idealWidth: 330, maxWidth: 360)
        .presentationCompactAdaptation(.popover)
        .presentationSizing(.fitted)
        .onAppear(perform: model.startObserving)
        .onDisappear(perform: model.stopObserving)
    }
}

@available(iOS 16.0, *)
struct NativeNavContentView: View {
    @StateObject private var appCoordinator = AppNavigationCoordinator()
    @StateObject private var iconStore = NativeTabIconStore()

    private var usesNativeTabBar: Bool {
        guard UIDevice.current.userInterfaceIdiom == .phone else {
            return false
        }
        if #available(iOS 26.0, *) {
            return true
        }
        return false
    }

    private var usesTabletFloatingTabBar: Bool {
        UIDevice.current.userInterfaceIdiom == .pad
    }

    private var tabSelection: Binding<NuvioAppTab> {
        Binding(
            get: { appCoordinator.selectedTab },
            set: { newTab in
                if newTab == .settings &&
                    appCoordinator.profileTabInteraction.suppressesProfileSelection {
                    return
                }
                if newTab == appCoordinator.selectedTab {
                    NativeTabBridgeKt.nativeTabSelect(tabName: newTab.rawValue)
                    return
                }
                if appCoordinator.isAppReady || newTab == .home {
                    appCoordinator.selectedTab = newTab
                }
            }
        )
    }

    private var legacyTabs: some View {
        TabView(selection: tabSelection) {
            ForEach(NuvioAppTab.allCases, id: \.self) { tab in
                TabContentView(
                    tab: tab,
                    usesNativeTabBar: usesNativeTabBar,
                    usesTabletFloatingTabBar: usesTabletFloatingTabBar,
                    coordinator: appCoordinator.coordinator(for: tab),
                    appCoordinator: appCoordinator
                )
                .tabItem {
                    Label {
                        Text(appCoordinator.title(for: tab))
                    } icon: {
                        Image(
                            uiImage: iconStore.image(
                                for: tab,
                                selected: appCoordinator.selectedTab == tab
                            )
                        )
                        .id(
                            "\(tab.rawValue)-\(iconStore.revision)-" +
                                "\(appCoordinator.selectedTab == tab)"
                        )
                    }
                }
                .tag(tab)
            }
        }
        .tint(Color(uiColor: iconStore.accentColor))
    }

    @available(iOS 26.0, *)
    private var nativeTabs: some View {
        TabView(selection: tabSelection) {
            ForEach(NuvioAppTab.allCases, id: \.self) { tab in
                if tab == .settings {
                    Tab(value: tab) {
                        TabContentView(
                            tab: tab,
                            usesNativeTabBar: usesNativeTabBar,
                            usesTabletFloatingTabBar: usesTabletFloatingTabBar,
                            coordinator: appCoordinator.coordinator(for: tab),
                            appCoordinator: appCoordinator
                        )
                    } label: {
                        Label {
                            Text(appCoordinator.title(for: tab))
                        } icon: {
                            Image(
                                uiImage: iconStore.image(
                                    for: tab,
                                    selected: appCoordinator.selectedTab == tab
                                )
                            )
                            .id(
                                "\(tab.rawValue)-\(iconStore.revision)-" +
                                    "\(appCoordinator.selectedTab == tab)"
                            )
                        }
                    }
                    .popover(
                        isPresented: $appCoordinator.isProfileSwitcherPresented,
                        attachmentAnchor: .rect(.bounds),
                        arrowEdge: .bottom
                    ) {
                        NativeProfileSwitcherView(
                            controller: appCoordinator.profileSwitcherController,
                            title: appCoordinator.localizedSwitchProfileTitle,
                            addProfileTitle: appCoordinator.localizedAddProfileTitle,
                            onManageProfiles: appCoordinator.openProfileManagement
                        )
                    }
                } else {
                    Tab(value: tab) {
                        TabContentView(
                            tab: tab,
                            usesNativeTabBar: usesNativeTabBar,
                            usesTabletFloatingTabBar: usesTabletFloatingTabBar,
                            coordinator: appCoordinator.coordinator(for: tab),
                            appCoordinator: appCoordinator
                        )
                    } label: {
                        Label {
                            Text(appCoordinator.title(for: tab))
                        } icon: {
                            Image(
                                uiImage: iconStore.image(
                                    for: tab,
                                    selected: appCoordinator.selectedTab == tab
                                )
                            )
                            .id(
                                "\(tab.rawValue)-\(iconStore.revision)-" +
                                    "\(appCoordinator.selectedTab == tab)"
                            )
                        }
                    }
                }
            }
        }
        .tint(Color(uiColor: iconStore.accentColor))
        .tabBarMinimizeBehavior(.automatic)
    }

    @ViewBuilder
    var body: some View {
        ZStack {
            Group {
                if appCoordinator.isMainContentMounted {
                    if #available(iOS 26.0, *), usesNativeTabBar {
                        nativeTabs
                    } else {
                        legacyTabs
                    }
                } else {
                    Color(uiColor: nuvioBackgroundColor)
                        .ignoresSafeArea(.all)
                }
            }
            .zIndex(0)

            AppGateComposeView(appCoordinator: appCoordinator)
                .ignoresSafeArea(.all)
                .allowsHitTesting(!appCoordinator.isAppReady)
                .accessibilityHidden(appCoordinator.isAppReady)
                .zIndex(1)
        }
    }
}

struct ContentView: View {
    var body: some View {
        if #available(iOS 16.0, *) {
            NativeNavContentView()
        } else {
            ComposeView()
                .ignoresSafeArea(.all)
        }
    }
}

private extension UIColor {
    convenience init?(hexString: String?) {
        guard var value = hexString?.trimmingCharacters(in: .whitespacesAndNewlines),
              !value.isEmpty else {
            return nil
        }
        if value.hasPrefix("#") {
            value.removeFirst()
        }
        guard value.count == 6, let rgb = UInt64(value, radix: 16) else {
            return nil
        }
        self.init(
            red: CGFloat((rgb >> 16) & 0xFF) / 255,
            green: CGFloat((rgb >> 8) & 0xFF) / 255,
            blue: CGFloat(rgb & 0xFF) / 255,
            alpha: 1
        )
    }
}
