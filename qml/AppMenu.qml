import QtQuick
import QtQuick.Controls.Basic

// A dark popup menu with rounded corners.
Menu {
    topPadding: 6
    bottomPadding: 6

    background: Rectangle {
        implicitWidth: 220
        radius: Theme.radiusCard
        color: Theme.surfaceHigh
        border.color: "#26FFFFFF"
    }
}
