import QtQuick

// A sideways-scrolling row (or several rows) of cards. Drag it (click and swipe), turn the
// wheel with Shift held, or page with the arrow buttons; the plain wheel keeps scrolling the
// page, since a sideways-only Flickable passes up-and-down wheel turns on to its parent.
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
            cancelFlick();
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
        flickableDirection: Flickable.HorizontalFlick
        boundsBehavior: Flickable.StopAtBounds

        SmoothWheel {
            flickable: grid
            horizontal: true
            acceptedModifiers: Qt.ShiftModifier
        }

        // Only the arrows' jumps animate: dragging and flicking move the view without
        // writing contentX, and the wheel's glide is an animation, which Behaviors skip.
        Behavior on contentX {
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
            color: parent.pressed ? Theme.controlHover : "#E62A2A2A"
            border.color: Theme.border
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
