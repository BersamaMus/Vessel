import QtQuick
import QtQuick.Controls.Basic

// A round, borderless icon button with an optional tooltip.
AbstractButton {
    id: button

    property string iconName
    property color iconColor: Theme.textPrimary
    property real iconSize: 24
    property string tip

    implicitWidth: 40
    implicitHeight: 40
    hoverEnabled: true
    focusPolicy: Qt.NoFocus   // keep keyboard shortcuts working

    ToolTip.visible: hovered && tip !== ""
    ToolTip.text: tip
    ToolTip.delay: 600

    background: Rectangle {
        radius: width / 2
        color: button.pressed ? Theme.pressed : button.hovered ? Theme.hover : "transparent"
    }

    contentItem: Item {
        Icon {
            anchors.centerIn: parent
            name: button.iconName
            size: button.iconSize
            color: button.enabled ? button.iconColor : Theme.textTertiary
        }
    }
}
