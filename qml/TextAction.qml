import QtQuick
import QtQuick.Controls.Basic

// Blimp's yellow text button ("Play all", "See all").
AbstractButton {
    id: button

    property string iconName

    hoverEnabled: true
    focusPolicy: Qt.NoFocus
    leftPadding: 12
    rightPadding: 14
    topPadding: 8
    bottomPadding: 8

    background: Rectangle {
        radius: height / 2
        color: button.pressed ? Theme.accentSoft : button.hovered ? Qt.rgba(0.98, 0.8, 0.08, 0.1) : "transparent"
    }

    contentItem: Row {
        spacing: 6
        Icon {
            visible: button.iconName !== ""
            anchors.verticalCenter: parent.verticalCenter
            name: button.iconName
            size: 20
            color: Theme.accent
        }
        Text {
            anchors.verticalCenter: parent.verticalCenter
            text: button.text
            color: Theme.accent
            font.pixelSize: 15
            font.weight: Font.DemiBold
        }
    }
}
