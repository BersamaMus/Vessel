import QtQuick
import QtQuick.Controls.Basic

// Video cards in columns that fill the width: a folder's videos, or every video on the
// connected drives. A GridView only makes the cards on screen, so thousands stay quick.
// Anything above the cards (sections, folders) goes in `header`.
GridView {
    id: grid

    // Under each title: what the list is sorted by and how far in, or else where it is.
    // Off, just how far in (the folder's own videos, where "where" is the page itself).
    property bool detailed: false
    readonly property var app: ApplicationWindow.window
    readonly property real gap: 16
    // Cells are a card plus the gap after it, and start at the page padding.
    readonly property real gridWidth: width - 2 * Theme.pagePadding + gap
    readonly property int columns: Math.max(1, Math.floor(gridWidth / (220 + gap)))
    readonly property real cardWidth: Math.floor(gridWidth / columns) - gap

    SmoothWheel { flickable: grid }

    clip: true
    // Cards scrolled off are handed the next row's video instead of being destroyed and
    // made again, so scrolling through thousands doesn't stutter.
    reuseItems: true
    cellWidth: Math.floor(gridWidth / columns)
    cellHeight: Math.round(cardWidth * 9 / 16) + 52 + gap
    boundsBehavior: Flickable.StopAtBounds
    ScrollBar.vertical: ScrollBar {}

    delegate: Item {
        id: cell
        required property var model
        width: grid.cellWidth
        height: grid.cellHeight

        VideoCard {
            x: Theme.pagePadding
            width: grid.cardWidth
            path: cell.model.path
            title: cell.model.title
            subtitle: grid.detailed ? [cell.model.detail, cell.model.remainingText].filter(part => part).join("  •  ")
                                      || cell.model.subtitle
                                    : cell.model.remainingText
            thumbnail: cell.model.thumbnail
            loading: cell.model.thumbnailPending
            durationText: cell.model.durationText
            progress: cell.model.progress
            watched: cell.model.finished
            onClicked: grid.app.play(cell.model.path)
        }
    }

    footer: Item { width: grid.width; height: 32 }
}
