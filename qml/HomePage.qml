import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// Continue watching, Quick picks, Recently added and Folders, under a bar with the
// logo and buttons that stays put while the page scrolls.
Item {
    id: home

    PageBar {
        id: bar
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        scrolled: page.contentItem.contentY > 1

        RowLayout {
            Layout.fillWidth: true
            Layout.leftMargin: Theme.pagePadding
            Layout.rightMargin: 8
            Layout.topMargin: 14
            spacing: 4

            AppLogo {}
            Item { Layout.fillWidth: true }
            IconButton {
                iconName: "search"
                tip: qsTr("Search (Ctrl+F)")
                onClicked: page.app.openSearch()
            }
            IconButton {
                iconName: "folder_open"
                tip: qsTr("Open a file (Ctrl+O)")
                onClicked: page.app.openFileDialog()
            }
            IconButton {
                iconName: "more"
                tip: qsTr("More")
                onClicked: menu.popup()

                AppMenu {
                    id: menu
                    AppMenuItem {
                        text: qsTr("Rescan library")
                        onTriggered: page.app.rescanLibrary()
                    }
                    AppMenuItem {
                        text: qsTr("Open a file…")
                        onTriggered: page.app.openFileDialog()
                    }
                }
            }
        }
    }

    ScrollView {
        id: page

        readonly property var app: ApplicationWindow.window
        readonly property bool hasVideos: VideoLibrary.libraryCount > 0
        readonly property real cardWidth: 260
        readonly property real cardCellHeight: Math.round(cardWidth * 9 / 16) + 62

        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: bar.bottom
        anchors.bottom: parent.bottom
        contentWidth: availableWidth
        clip: true

        ColumnLayout {
            width: page.availableWidth
            spacing: 0

            EmptyNote {
                visible: !page.hasVideos
                Layout.topMargin: 24
                iconName: "movie"
                text: VideoLibrary.scanning
                      ? qsTr("Looking for videos in your library folders…")
                      : qsTr("No videos found in your library folders. Choose which folders to use in Settings.")
            }

            // --- Continue watching -------------------------------------------
            ShelfHeader {
                visible: page.hasVideos
                title: qsTr("Continue watching")
            }

            EmptyNote {
                visible: page.hasVideos && VideoLibrary.continueWatching.count === 0
                text: qsTr("Videos you start watching will show up here, so you can pick up where you left off.")
            }

            Shelf {
                Layout.fillWidth: true
                visible: VideoLibrary.continueWatching.count > 0
                model: VideoLibrary.continueWatching
                cellWidth: page.cardWidth + 16
                cellHeight: page.cardCellHeight
                arrowCenterY: page.cardWidth * 9 / 32
                delegate: VideoCard {
                    required property var model
                    width: page.cardWidth
                    title: model.title
                    subtitle: model.remainingText
                    thumbnail: model.thumbnail
                    loading: model.thumbnailPending
                    durationText: model.durationText
                    progress: model.progress
                    onClicked: page.app.play(model.path)
                }
            }

            // --- Quick picks -------------------------------------------------
            ShelfHeader {
                visible: page.hasVideos
                title: qsTr("Quick picks")

                IconButton {
                    iconName: "refresh"
                    iconColor: Theme.textSecondary
                    tip: qsTr("Refresh")
                    onClicked: VideoLibrary.reshuffleQuickPicks()
                }
                IconButton {
                    iconName: "shuffle"
                    iconColor: Theme.textSecondary
                    tip: qsTr("Play a random pick")
                    onClicked: {
                        const picks = VideoLibrary.quickPicks;
                        page.app.play(picks.pathAt(Math.floor(Math.random() * picks.count)));
                    }
                }
            }

            Shelf {
                id: picks
                Layout.fillWidth: true
                visible: page.hasVideos
                model: VideoLibrary.quickPicks
                rows: 4
                cellWidth: Math.max(340, Math.min(460, (page.availableWidth - 2 * Theme.pagePadding) / 2.3))
                cellHeight: 72
                delegate: PickRow {
                    required property var model
                    width: picks.cellWidth - 8
                    height: picks.cellHeight - 2
                    title: model.title
                    subtitle: model.durationText !== "" ? model.subtitle + "  •  " + model.durationText : model.subtitle
                    thumbnail: model.thumbnail
                    loading: model.thumbnailPending
                    progress: model.progress
                    onClicked: page.app.play(model.path)
                }
            }

            // --- Recently added ----------------------------------------------
            ShelfHeader {
                visible: page.hasVideos
                title: qsTr("Recently added")
            }

            Shelf {
                Layout.fillWidth: true
                visible: page.hasVideos
                model: VideoLibrary.recentlyAdded
                cellWidth: page.cardWidth + 16
                cellHeight: page.cardCellHeight
                arrowCenterY: page.cardWidth * 9 / 32
                delegate: VideoCard {
                    required property var model
                    width: page.cardWidth
                    title: model.title
                    subtitle: model.subtitle
                    thumbnail: model.thumbnail
                    loading: model.thumbnailPending
                    durationText: model.durationText
                    progress: model.progress
                    onClicked: page.app.play(model.path)
                }
            }

            // --- Folders -----------------------------------------------------
            ShelfHeader {
                visible: VideoLibrary.folders.count > 0
                title: qsTr("Folders")
                actionLabel: qsTr("See all")
                onActionTriggered: page.app.showLibrary()
            }

            Shelf {
                Layout.fillWidth: true
                visible: VideoLibrary.folders.count > 0
                model: VideoLibrary.folders
                cellWidth: page.cardWidth + 16
                cellHeight: page.cardCellHeight + 8
                arrowCenterY: 8 + page.cardWidth * 9 / 32
                delegate: FolderCard {
                    required property var model
                    width: page.cardWidth
                    name: model.name
                    countText: model.countText
                    thumbnail: model.thumbnail
                    loading: model.thumbnailPending
                    onClicked: page.app.openFolder(model.folder)
                }
            }

            Item { implicitHeight: 32 }
        }
    }
}
