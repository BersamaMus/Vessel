import QtQuick
import QtQuick.Controls.Basic

// Blimp's slider: a yellow track with a white thumb, thicker on hover.
Slider {
    id: control

    hoverEnabled: true
    focusPolicy: Qt.NoFocus
    padding: 0
    implicitHeight: 20

    HoverHandler { cursorShape: Qt.PointingHandCursor }

    background: Item {
        x: control.leftPadding
        y: control.topPadding + (control.availableHeight - height) / 2
        width: control.availableWidth
        height: control.hovered || control.pressed ? 6 : 4

        Rectangle {
            anchors.fill: parent
            radius: height / 2
            color: "#40FFFFFF"
        }
        Rectangle {
            width: control.visualPosition * parent.width
            height: parent.height
            radius: height / 2
            color: Theme.accent
        }
    }

    handle: Rectangle {
        x: control.leftPadding + control.visualPosition * (control.availableWidth - width)
        y: control.topPadding + (control.availableHeight - height) / 2
        width: 14
        height: 14
        radius: 7
        color: "white"
        scale: control.pressed ? 1.15 : 1
        Behavior on scale { NumberAnimation { duration: 100 } }
    }
}
