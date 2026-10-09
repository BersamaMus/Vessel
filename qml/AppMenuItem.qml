import QtQuick
import QtQuick.Controls.Basic

MenuItem {
    id: item

    leftPadding: 16
    rightPadding: 16
    implicitHeight: 40

    contentItem: Text {
        text: item.text
        color: Theme.textPrimary
        font.pixelSize: 15
        verticalAlignment: Text.AlignVCenter
    }

    background: Rectangle {
        anchors.fill: parent
        anchors.leftMargin: 6
        anchors.rightMargin: 6
        radius: Theme.radiusArt - 4
        color: item.highlighted ? Theme.hover : "transparent"
    }
}
