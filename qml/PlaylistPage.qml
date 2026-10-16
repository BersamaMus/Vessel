import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// One playlist: its videos in order, to play from the top, shuffled, or from any one of
// them. Each can be moved up or down or taken out; the playlist can be renamed or deleted.
// Videos on a drive that isn't plugged in are left out until it is.
Item {
    id: page

    property string playlistId

    readonly property var app: ApplicationWindow.window
    readonly property var videos: VideoLibrary.playlistVideos(playlistId)
    readonly property string name: VideoLibrary.playlistRevision >= 0 ? VideoLibrary.playlistName(playlistId) : ""

    clip: true

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        PageBar {
            Layout.fillWidth: true
            scrolled: !list.atYBeginning

            RowLayout {
                Layout.fillWidth: true
                Layout.leftMargin: 8
                Layout.rightMargin: Theme.pagePadding
                Layout.topMargin: 14
                spacing: 8

                IconButton {
                    Layout.alignment: Qt.AlignTop
                    iconName: "back"
                    tip: qsTr("Back (Esc)")
                    onClicked: page.StackView.view.pop()
                }

                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 0

                    PageTitle {
                        title: page.name
                        subtitle: page.videos.count === 0 ? qsTr("No videos")
                                : page.videos.count === 1 ? qsTr("1 video") : qsTr("%1 videos").arg(page.videos.count)
                    }
                    Row {
                        Layout.topMargin: 6
                        Layout.leftMargin: -12   // the icons line up with the title
                        visible: page.videos.count > 0
                        spacing: 4

                        TextAction {
                            text: qsTr("Play all")
                            iconName: "play"
                            onClicked: page.app.playPlaylist(page.playlistId, false)
                        }
                        TextAction {
                            text: qsTr("Shuffle")
                            iconName: "shuffle"
                            onClicked: page.app.playPlaylist(page.playlistId, true)
                        }
                    }
                }

                IconButton {
                    Layout.alignment: Qt.AlignTop
                    iconName: "more"
                    tip: qsTr("More")
                    onClicked: page.app.showPlaylistMenu(page.playlistId)
                }
            }
        }

        EmptyNote {
            visible: page.videos.count === 0
            Layout.topMargin: 24
            iconName: "playlist_add"
            text: qsTr("Nothing here yet. Right-click a video anywhere and choose Save to playlist, or use Save on the watch page.")
        }

        // Keeps the bar at the top while there's no list to fill the page.
        Item {
            visible: !list.visible
            Layout.fillHeight: true
        }

        ListView {
            id: list
            SmoothWheel { flickable: list }

            Layout.fillWidth: true
            Layout.fillHeight: true
            Layout.topMargin: 12
            visible: page.videos.count > 0
            model: page.videos
            boundsBehavior: Flickable.StopAtBounds
            leftMargin: Theme.pagePadding - 8
            rightMargin: Theme.pagePadding - 8
            bottomMargin: 24
            reuseItems: true
            ScrollBar.vertical: ScrollBar {}

            delegate: Item {
                id: entry

                required property var model
                required property int index

                width: Math.min(list.width - list.leftMargin - list.rightMargin, 900)
                height: row.height

                // Its place in the list.
                Text {
                    id: number
                    width: 32
                    anchors.verticalCenter: parent.verticalCenter
                    horizontalAlignment: Text.AlignHCenter
                    text: entry.index + 1
                    color: Theme.textTertiary
                    font.pixelSize: 14
                    font.weight: Font.DemiBold
                }

                PickRow {
                    id: row
                    anchors.left: number.right
                    anchors.right: parent.right
                    thumbWidth: 160
                    titleLines: 2
                    path: entry.model.path
                    title: entry.model.title
                    subtitle: [entry.model.subtitle, entry.model.finished ? qsTr("Watched") : entry.model.remainingText]
                              .filter(part => part).join("  •  ")
                    thumbnail: entry.model.thumbnail
                    loading: entry.model.thumbnailPending
                    durationText: entry.model.durationText
                    progress: entry.model.progress
                    watched: entry.model.finished
                    onClicked: page.app.playPlaylist(page.playlistId, false, entry.model.path)

                    IconButton {
                        anchors.right: parent.right
                        anchors.rightMargin: 8
                        anchors.verticalCenter: parent.verticalCenter
                        visible: row.hovered || hovered || (rowMenu.opened && rowMenu.row === entry.index)
                        iconName: "more"
                        iconColor: Theme.textSecondary
                        tip: qsTr("More")
                        onClicked: {
                            rowMenu.row = entry.index;
                            rowMenu.path = entry.model.path;
                            rowMenu.popup();
                        }
                    }
                }
            }
        }
    }

    // A video's place in the playlist.
    AppMenu {
        id: rowMenu

        property int row: -1
        property string path

        width: 230

        AppMenuItem {
            text: qsTr("Play from here")
            onTriggered: page.app.playPlaylist(page.playlistId, false, rowMenu.path)
        }
        MenuSeparator {
            contentItem: Rectangle {
                implicitHeight: 1
                color: Theme.border
            }
        }
        AppMenuItem {
            enabled: rowMenu.row > 0
            text: qsTr("Move to top")
            onTriggered: VideoLibrary.moveInPlaylist(page.playlistId, rowMenu.path, page.videos.pathAt(0))
        }
        AppMenuItem {
            enabled: rowMenu.row > 0
            text: qsTr("Move up")
            onTriggered: VideoLibrary.moveInPlaylist(page.playlistId, rowMenu.path, page.videos.pathAt(rowMenu.row - 1))
        }
        AppMenuItem {
            enabled: rowMenu.row >= 0 && rowMenu.row < page.videos.count - 1
            text: qsTr("Move down")
            // Before the one after next, or to the end.
            onTriggered: VideoLibrary.moveInPlaylist(page.playlistId, rowMenu.path,
                                                     rowMenu.row + 2 < page.videos.count ? page.videos.pathAt(rowMenu.row + 2) : "")
        }
        AppMenuItem {
            enabled: rowMenu.row >= 0 && rowMenu.row < page.videos.count - 1
            text: qsTr("Move to bottom")
            onTriggered: VideoLibrary.moveInPlaylist(page.playlistId, rowMenu.path, "")
        }
        MenuSeparator {
            contentItem: Rectangle {
                implicitHeight: 1
                color: Theme.border
            }
        }
        AppMenuItem {
            text: qsTr("Remove from playlist")
            onTriggered: page.app.removeFromPlaylist(page.playlistId, rowMenu.path)
        }
    }
}
