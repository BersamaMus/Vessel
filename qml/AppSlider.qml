import QtQuick
import QtQuick.Controls.Basic

// The slider: a purple track with a white thumb, thicker on hover.
Slider {
    id: control

    // A value past which the track turns orange, with a tick there (volume over 100%); -1 for none.
    property real boostFrom: -1
    readonly property real boostPosition: boostFrom >= 0 && to > from ? (boostFrom - from) / (to - from) : 1

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
            width: Math.min(control.visualPosition, control.boostPosition) * parent.width
            height: parent.height
            radius: height / 2
            color: Theme.accent
        }
        // Past boostFrom (a volume over 100%): orange, from a tick at the boundary.
        Rectangle {
            visible: control.boostFrom >= 0 && control.visualPosition > control.boostPosition
            x: control.boostPosition * parent.width - radius
            width: (control.visualPosition - control.boostPosition) * parent.width + radius
            height: parent.height
            radius: height / 2
            color: Theme.boost
        }
        Rectangle {
            visible: control.boostFrom >= 0
            x: control.boostPosition * parent.width - 1
            anchors.verticalCenter: parent.verticalCenter
            width: 2
            height: 10
            radius: 1
            color: "#99FFFFFF"
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
