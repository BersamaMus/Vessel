pragma Singleton
import QtQuick

// The palette and shapes: black surfaces with a light purple accent.
QtObject {
    readonly property color background: "#000000"
    readonly property color surface: "#0F0F0F"
    readonly property color surfaceHigh: "#1C1C1C"
    readonly property color surfaceHover: "#2E2E2E"     // a card or pill under the mouse
    readonly property color surfacePressed: "#262626"
    readonly property color dialog: "#141414"
    readonly property color accent: "#C9A7FF"
    readonly property color accentText: "#1E0B3D"   // text and icons on the accent
    readonly property color accentSoft: Qt.rgba(accent.r, accent.g, accent.b, 0.18)   // selected tabs, soft buttons
    readonly property color boost: "#F97316"          // volume past 100%
    readonly property color error: "#F05252"
    readonly property color hover: "#14FFFFFF"
    readonly property color pressed: "#26FFFFFF"
    readonly property color textPrimary: "#FFFFFF"
    readonly property color textSecondary: "#ADADAD"
    readonly property color textTertiary: "#858585"

    // Lines: around popups, menus and dialogs; around cards; and stronger, for tracks.
    readonly property color border: "#26FFFFFF"
    readonly property color borderSoft: "#1FFFFFFF"
    readonly property color borderStrong: "#33FFFFFF"
    // Grey buttons on dialogs and over the video ("Cancel", "Keep watching").
    readonly property color control: "#2A2A2A"
    readonly property color controlHover: "#3A3A3A"
    // Behind a modal dialog; behind labels laid over thumbnails (durations).
    readonly property color scrim: "#99000000"
    readonly property color badge: "#CC000000"
    // The unfilled part of a progress line on a thumbnail.
    readonly property color track: "#59FFFFFF"

    // Text sizes: a page's title, its sections, and smaller headings in side panels.
    readonly property int pageTitleSize: 26
    readonly property int sectionSize: 20
    readonly property int subsectionSize: 16

    readonly property real radiusArt: 12
    readonly property real radiusCard: 16
    readonly property real radiusDialog: 24
    readonly property real pagePadding: 20
}
