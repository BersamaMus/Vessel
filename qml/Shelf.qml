import QtQuick

// A sideways-scrolling row (or several rows) of cards. On desktop it pages
// with arrow buttons so the mouse wheel keeps scrolling the page.
Item {
    id: shelf

    property alias model: grid.model
    property alias delegate: grid.delegate
    property int rows: 1
    property real cellWidth: 256
    property real cellHeight: 200
    // Where the arrows sit vertically (the middle of the thumbnails).
    property real arrowCenterY: height / 2

    implicitHeight: grid.height

    GridView {
        id: grid

        readonly property real minX: originX - leftMargin
        readonly property real maxX: Math.max(minX, originX + contentWidth + rightMargin - width)

        function page(direction) {
            const visibleCells = Math.max(1, Math.floor((width - leftMargin - rightMargin) / cellWidth));
            contentX = Math.max(minX, Math.min(maxX, contentX + direction * visibleCells * cellWidth));
        }

        width: parent.width
        height: shelf.rows * shelf.cellHeight
        flow: GridView.FlowTopToBottom
        cellWidth: shelf.cellWidth
        cellHeight: shelf.cellHeight
        leftMargin: Theme.pagePadding
        rightMargin: Theme.pagePadding
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        interactive: Qt.platform.os === "android" || Qt.platform.os === "ios"

        Behavior on contentX {
            enabled: !grid.interactive
            NumberAnimation { duration: 350; easing.type: Easing.OutCubic }
        }
    }

    HoverHandler { id: hover }

    component Arrow: IconButton {
        width: 40
        height: 40
        y: shelf.arrowCenterY - height / 2
        iconColor: Theme.textPrimary
        opacity: hover.hovered && enabled ? 1 : 0
        visible: opacity > 0
        background: Rectangle {
            radius: 20
            color: parent.pressed ? "#3A3A3A" : "#E62A2A2A"
            border.color: "#26FFFFFF"
        }
        Behavior on opacity { NumberAnimation { duration: 150 } }
    }

    Arrow {
        x: 8
        iconName: "chevron_left"
        enabled: grid.contentX > grid.minX + 1
        onClicked: grid.page(-1)
    }

    Arrow {
        x: shelf.width - width - 8
        iconName: "chevron_right"
        enabled: grid.contentX < grid.maxX - 1
        onClicked: grid.page(1)
    }
}
