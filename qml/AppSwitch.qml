import QtQuick
import QtQuick.Controls.Basic

// An on/off switch: yellow when on. Optionally shows an icon in the knob.
// It shows `checked` but doesn't flip it: handle clicked() and bind `checked`.
AbstractButton {
    id: control

    property string knobIcon
    property string tip

    checkable: false
    hoverEnabled: true
    focusPolicy: Qt.NoFocus
    implicitWidth: 44
    implicitHeight: 24

    ToolTip.visible: hovered && tip !== ""
    ToolTip.text: tip
    ToolTip.delay: 600
    HoverHandler { cursorShape: Qt.PointingHandCursor }

    background: Rectangle {
        radius: height / 2
        color: control.checked ? Theme.accent : (control.hovered ? "#4A4A4A" : "#3A3A3A")
        Behavior on color { ColorAnimation { duration: 150 } }
    }

    contentItem: Item {
        Rectangle {
            readonly property real inset: 3
            x: control.checked ? parent.width - width - inset : inset
            anchors.verticalCenter: parent.verticalCenter
            width: control.height - 2 * inset
            height: width
            radius: width / 2
            color: control.checked ? Theme.accentText : Theme.textSecondary
            Behavior on x { NumberAnimation { duration: 150; easing.type: Easing.OutCubic } }

            Icon {
                visible: control.knobIcon !== ""
                anchors.centerIn: parent
                name: control.knobIcon
                size: parent.width - 4
                color: control.checked ? Theme.accent : "#3A3A3A"
            }
        }
    }
}
