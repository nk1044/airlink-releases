import AppKit
import BudsCore
import SwiftUI
import Testing
@testable import AirLinkApp

@Suite @MainActor struct PanelLayoutTests {
    /// Regression: the hosting view used to end up twice the panel's size, showing only a corner
    /// of the content.
    @Test func contentFillsThePanelExactly() {
        let store = DeviceStore(defaults: UserDefaults(suiteName: "airlink-layout")!)
        store.deviceAppeared(profile: RealmeBudsAir6Pro()) {}
        let hosting = NSHostingView(rootView: PanelContent(store: store, loginItem: LoginItem(),
                                                           preferences: Preferences()) { _ in })
        let size = hosting.fittingSize
        #expect(size.width == Theme.popoverWidth)
        #expect(size.height > 100 && size.height < 600)

        let panel = MenuPanel(contentRect: NSRect(x: 200, y: 200, width: size.width, height: size.height))
        panel.contentView = PanelController.container(for: hosting, size: size)
        #expect(panel.contentView?.frame.size == size)
        #expect(hosting.frame == panel.contentView?.bounds)

        // Growing the panel (e.g. a message line appears) keeps the content filling it.
        panel.setFrame(NSRect(x: 200, y: 180, width: size.width, height: size.height + 20), display: false)
        #expect(hosting.frame.size == NSSize(width: size.width, height: size.height + 20))
    }
}
