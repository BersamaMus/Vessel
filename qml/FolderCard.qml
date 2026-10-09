import QtQuick
import QtQuick.Controls.Basic

// A folder shown as a small stack of thumbnails, with its name and size.
AbstractButton {
    id: card

    property string name
    property string countText
    property url thumbnail

    hoverEnabled: true
    focusPolicy: Qt.NoFocus
    padding: 0
    implicitWidth: 240
    implicitHeight: contentItem.implicitHeight

    HoverHandler { cursorShape: Qt.PointingHandCursor }

    background: null

    contentItem: Column {
        spacing: 0

        Item {
            width: card.width
            height: Math.round(width * 9 / 16) + 8

            // The "stack" behind the cover.
            Rectangle {
                anchors.horizontalCenter: parent.horizontalCenter
                y: 0
                width: parent.width - 28
                height: 20
                radius: Theme.radiusArt
                color: "#2A2A2A"
            }
            Rectangle {
                anchors.horizontalCenter: parent.horizontalCenter
                y: 4
                width: parent.width - 14
                height: 20
                radius: Theme.radiusArt
                color: "#3A3A3A"
            }

            Item {
                id: cover
                y: 8
                width: parent.width
                height: parent.height - 8
                scale: card.hovered ? 1.02 : 1
                Behavior on scale { NumberAnimation { duration: 150; easing.type: Easing.OutCubic } }

                RoundedImage {
                    anchors.fill: parent
                    source: card.thumbnail
                    placeholderIcon: "folder"
                }

                Rectangle {
                    anchors.left: parent.left
                    anchors.bottom: parent.bottom
                    anchors.margins: 8
                    width: 30
                    height: 30
                    radius: 8
                    color: "#CC000000"
                    Icon {
                        anchors.centerIn: parent
                        name: "folder"
                        size: 18
                        color: Theme.accent
                    }
                }
            }
        }

        Item { width: 1; height: 10 }

        Text {
            width: card.width
            text: card.name
            color: Theme.textPrimary
            font.pixelSize: 15
            font.weight: Font.DemiBold
            elide: Text.ElideRight
        }

        Text {
            width: card.width
            topPadding: 2
            text: card.countText
            color: Theme.textSecondary
            font.pixelSize: 13
            elide: Text.ElideRight
        }
    }
}
