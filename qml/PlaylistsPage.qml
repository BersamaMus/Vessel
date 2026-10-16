import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// The Playlists tab: every playlist the user made, most recently added to first, as
// cards. Videos are saved to them from their menu (right-click) or the watch page.
Item {
    id: page

    readonly property var app: ApplicationWindow.window
    readonly property var playlists: VideoLibrary.playlists
    readonly property real gap: 16
    // Cells are a card plus the gap after it, and start at the page padding (as FolderPage).
    readonly property real gridWidth: width - 2 * Theme.pagePadding + gap
    readonly property int columns: Math.max(1, Math.floor(gridWidth / (220 + gap)))
    readonly property real cardWidth: Math.floor(gridWidth / columns) - gap

    clip: true

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        PageBar {
            Layout.fillWidth: true
            scrolled: !grid.atYBeginning

            RowLayout {
                Layout.fillWidth: true
                Layout.leftMargin: Theme.pagePadding
                Layout.rightMargin: Theme.pagePadding - 8
                Layout.topMargin: 14
                spacing: 8

                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 0

                    PageTitle {
                        title: qsTr("Playlists")
                        subtitle: page.playlists.count === 1 ? qsTr("1 playlist") : qsTr("%1 playlists").arg(page.playlists.count)
                    }
                    TextAction {
                        Layout.topMargin: 6
                        Layout.leftMargin: -12   // the icon lines up with the title
                        text: qsTr("New playlist")
                        iconName: "add"
                        onClicked: page.app.newPlaylist()
                    }
                }

                AppActions {}
            }
        }

        GridView {
            id: grid
            SmoothWheel { flickable: grid }

            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            cellWidth: Math.floor(page.gridWidth / page.columns)
            cellHeight: Math.round(page.cardWidth * 9 / 16) + 60 + page.gap
            model: page.playlists
            boundsBehavior: Flickable.StopAtBounds
            ScrollBar.vertical: ScrollBar {}

            delegate: Item {
                id: cell
                required property var model
                width: grid.cellWidth
                height: grid.cellHeight

                FolderCard {
                    x: Theme.pagePadding
                    width: page.cardWidth
                    iconName: "playlist"
                    name: cell.model.name
                    countText: cell.model.countText
                    thumbnail: cell.model.thumbnail
                    loading: cell.model.thumbnailPending
                    onClicked: page.app.openPlaylist(cell.model.playlistId)
                    onMenuRequested: page.app.showPlaylistMenu(cell.model.playlistId)
                }
            }

            header: ColumnLayout {
                width: grid.width
                spacing: 0

                EmptyNote {
                    visible: page.playlists.count === 0
                    Layout.topMargin: 12
                    Layout.bottomMargin: 12
                    iconName: "playlist"
                    text: qsTr("Make a playlist, then save videos to it from anywhere: right-click a video, or use Save on the watch page.")
                    actionText: qsTr("New playlist")
                    onActionTriggered: page.app.newPlaylist()
                }
                Item { implicitHeight: 12 }
            }

            footer: Item { width: grid.width; height: 32 }
        }
    }
}
