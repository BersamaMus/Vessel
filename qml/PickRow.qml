import QtQuick
import QtQuick.Controls.Basic

// One row of Quick picks: a small thumbnail, the title and details.
AbstractButton {
    id: row

    property string title
    property string subtitle
    property url thumbnail
    property real progress: 0

    hoverEnabled: true
    focusPolicy: Qt.NoFocus
    padding: 8

    HoverHandler { cursorShape: Qt.PointingHandCursor }

    background: Rectangle {
        radius: Theme.radiusArt
        color: row.pressed ? Theme.pressed : row.hovered ? Theme.hover : "transparent"
    }

    contentItem: Item {
        Item {
            id: thumb
            anchors.verticalCenter: parent.verticalCenter
            width: 96
            height: 54

            RoundedImage {
                anchors.fill: parent
                source: row.thumbnail
                radius: 8
            }

            Rectangle {
                visible: row.hovered
                anchors.fill: parent
                radius: 8
                color: "#80000000"
                Icon {
                    anchors.centerIn: parent
                    name: "play"
                    size: 28
                    color: Theme.textPrimary
                }
            }

            Rectangle {
                visible: row.progress > 0
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.bottom: parent.bottom
                anchors.margins: 5
                height: 3
                radius: 1.5
                color: "#59FFFFFF"
                Rectangle {
                    width: parent.width * row.progress
                    height: parent.height
                    radius: 1.5
                    color: Theme.accent
                }
            }
        }

        Column {
            anchors.left: thumb.right
            anchors.leftMargin: 14
            anchors.right: parent.right
            anchors.verticalCenter: parent.verticalCenter
            spacing: 3

            Text {
                width: parent.width
                text: row.title
                color: Theme.textPrimary
                font.pixelSize: 15
                font.weight: Font.DemiBold
                elide: Text.ElideRight
            }
            Text {
                width: parent.width
                text: row.subtitle
                color: Theme.textSecondary
                font.pixelSize: 13
                elide: Text.ElideRight
            }
        }
    }
}
