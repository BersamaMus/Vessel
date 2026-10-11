import QtQuick
import QtQuick.Controls.Basic

// A menu row. Checkable items get a tick next to the chosen one.
MenuItem {
    id: item

    leftPadding: 16
    rightPadding: 16
    implicitHeight: 40
    // Leaves room for a tick even when it can't have one, to line up with checkable items.
    property bool reserveCheck: false

    indicator: null   // the tick is drawn below

    contentItem: Row {
        spacing: 10

        Item {
            visible: item.checkable || item.reserveCheck
            anchors.verticalCenter: parent.verticalCenter
            width: 20
            height: 20
            Icon {
                anchors.centerIn: parent
                visible: item.checked
                name: "check"
                size: 20
                color: Theme.accent
            }
        }

        Text {
            anchors.verticalCenter: parent.verticalCenter
            width: Math.min(implicitWidth, 320)
            text: item.text
            color: item.checked ? Theme.accent : Theme.textPrimary
            font.pixelSize: 15
            elide: Text.ElideMiddle
        }
    }

    background: Rectangle {
        anchors.fill: parent
        anchors.leftMargin: 6
        anchors.rightMargin: 6
        radius: Theme.radiusArt - 4
        color: item.highlighted ? Theme.hover : "transparent"
    }
}
