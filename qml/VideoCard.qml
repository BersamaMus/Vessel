import QtQuick
import QtQuick.Controls.Basic

// A 16:9 thumbnail with the title underneath; shows a play button on hover
// and a progress bar for videos that were started.
AbstractButton {
    id: card

    property string title
    property string subtitle
    property url thumbnail
    property string durationText
    property real progress: 0

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
            id: frame
            width: card.width
            height: Math.round(width * 9 / 16)

            RoundedImage {
                anchors.fill: parent
                source: card.thumbnail
            }

            Rectangle {
                anchors.fill: parent
                radius: Theme.radiusCard
                color: "black"
                opacity: card.hovered ? 0.3 : 0
                Behavior on opacity { NumberAnimation { duration: 150 } }
            }

            Rectangle {
                anchors.centerIn: parent
                width: 48
                height: 48
                radius: 24
                color: Theme.accent
                opacity: card.hovered ? 1 : 0
                scale: card.hovered ? (card.pressed ? 0.92 : 1) : 0.8
                Behavior on opacity { NumberAnimation { duration: 150 } }
                Behavior on scale { NumberAnimation { duration: 150; easing.type: Easing.OutCubic } }
                Icon {
                    anchors.centerIn: parent
                    name: "play"
                    size: 28
                    color: Theme.accentText
                }
            }

            Rectangle {
                visible: card.durationText !== ""
                anchors.right: parent.right
                anchors.bottom: parent.bottom
                anchors.margins: 8
                anchors.bottomMargin: card.progress > 0 ? 18 : 8
                width: durationLabel.implicitWidth + 12
                height: 22
                radius: 6
                color: "#CC000000"
                Text {
                    id: durationLabel
                    anchors.centerIn: parent
                    text: card.durationText
                    color: Theme.textPrimary
                    font.pixelSize: 12
                    font.weight: Font.DemiBold
                }
            }

            Rectangle {
                visible: card.progress > 0
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.bottom: parent.bottom
                anchors.margins: 10
                height: 4
                radius: 2
                color: "#59FFFFFF"
                Rectangle {
                    width: parent.width * card.progress
                    height: parent.height
                    radius: 2
                    color: Theme.accent
                }
            }
        }

        Item { width: 1; height: 10 }

        Text {
            width: card.width
            text: card.title
            color: Theme.textPrimary
            font.pixelSize: 15
            font.weight: Font.DemiBold
            elide: Text.ElideRight
        }

        Text {
            width: card.width
            topPadding: 2
            text: card.subtitle
            color: Theme.textSecondary
            font.pixelSize: 13
            elide: Text.ElideRight
        }
    }
}
