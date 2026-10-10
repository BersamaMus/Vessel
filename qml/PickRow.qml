import QtQuick
import QtQuick.Controls.Basic

// A list row: a thumbnail, the title and details. Used for Quick picks and
// the watch page's Up next list.
AbstractButton {
    id: row

    property string title
    property string subtitle
    property url thumbnail
    property string durationText
    property real progress: 0
    // The thumbnail is still being made.
    property bool loading: false
    property real thumbWidth: 96
    property int titleLines: 1
    // The video that's playing (in the Up next list).
    property bool current: false

    hoverEnabled: true
    focusPolicy: Qt.NoFocus
    padding: 8
    implicitHeight: Math.max(thumb.height, details.implicitHeight) + topPadding + bottomPadding

    HoverHandler { cursorShape: Qt.PointingHandCursor }

    background: Rectangle {
        radius: Theme.radiusArt
        color: row.current ? "#1FFACC15"
             : row.pressed ? Theme.pressed : row.hovered ? Theme.hover : "transparent"
    }

    contentItem: Item {
        Item {
            id: thumb
            anchors.verticalCenter: parent.verticalCenter
            width: row.thumbWidth
            height: Math.round(row.thumbWidth * 9 / 16)

            RoundedImage {
                anchors.fill: parent
                source: row.thumbnail
                radius: row.thumbWidth > 120 ? 10 : 8
                loading: row.loading
            }

            Rectangle {
                visible: row.hovered || row.current
                anchors.fill: parent
                radius: row.thumbWidth > 120 ? 10 : 8
                color: "#80000000"
                Icon {
                    anchors.centerIn: parent
                    name: row.current ? "pause" : "play"
                    size: 28
                    color: row.current ? Theme.accent : Theme.textPrimary
                }
            }

            Rectangle {
                visible: row.thumbWidth > 120 && row.durationText !== "" && !row.current
                anchors.right: parent.right
                anchors.bottom: parent.bottom
                anchors.margins: 6
                width: badge.implicitWidth + 10
                height: 20
                radius: 5
                color: "#CC000000"
                Text {
                    id: badge
                    anchors.centerIn: parent
                    text: row.durationText
                    color: Theme.textPrimary
                    font.pixelSize: 11
                    font.weight: Font.DemiBold
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
            id: details
            anchors.left: thumb.right
            anchors.leftMargin: 12
            anchors.right: parent.right
            anchors.verticalCenter: parent.verticalCenter
            spacing: 3

            Text {
                width: parent.width
                text: row.title
                color: row.current ? Theme.accent : Theme.textPrimary
                font.pixelSize: row.titleLines > 1 ? 14 : 15
                font.weight: Font.DemiBold
                wrapMode: row.titleLines > 1 ? Text.Wrap : Text.NoWrap
                maximumLineCount: row.titleLines
                elide: Text.ElideRight
            }
            Text {
                width: parent.width
                text: row.current ? qsTr("Now playing") : row.subtitle
                color: Theme.textSecondary
                font.pixelSize: 13
                elide: Text.ElideRight
            }
        }
    }
}
