pragma Singleton
import QtQuick

// The palette and shapes, matching Blimp: black surfaces with a yellow accent.
QtObject {
    readonly property color background: "#000000"
    readonly property color surface: "#0F0F0F"
    readonly property color surfaceHigh: "#1C1C1C"
    readonly property color accent: "#FACC15"
    readonly property color accentText: "#1F1A00"
    readonly property color accentSoft: "#2EFACC15"   // selected tabs, soft buttons
    readonly property color hover: "#14FFFFFF"
    readonly property color pressed: "#26FFFFFF"
    readonly property color textPrimary: "#FFFFFF"
    readonly property color textSecondary: "#ADADAD"
    readonly property color textTertiary: "#858585"

    readonly property real radiusArt: 12
    readonly property real radiusCard: 16
    readonly property real pagePadding: 20
}
