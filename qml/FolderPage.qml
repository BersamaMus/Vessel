import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// One folder of the library: its sub-folders, then its videos, in grids. Sorting
// (other than by name) or filtering lists every video in it, sub-folders included.
//
// The title, buttons and filters stay at the top while the rest scrolls. The videos
// are the cells of a GridView, which only makes the cards on screen, so a drive with
// thousands of videos stays quick; the folders and shelves above them are its header.
Item {
    id: page

    // An absolute path, or "" for the whole library.
    property string folder: ""
    // A tab's own page, under the tab's name: "videos" (the Videos folder) or "library" (every
    // folder with videos in it, on the computer's own drives); "" for any other folder.
    property string tab: ""
    readonly property string tabTitle: tab === "videos" ? qsTr("Videos") : tab === "library" ? qsTr("Library") : ""

    readonly property var app: ApplicationWindow.window
    readonly property var subfolders: tab === "library" ? VideoLibrary.folders : VideoLibrary.foldersIn(folder)
    readonly property var videos: VideoLibrary.videosIn(folder)
    // A drive's own page leads with what was being watched on it (drive videos stay off Home).
    readonly property var started: VideoLibrary.isDrive(folder) ? VideoLibrary.continueWatchingOn(folder) : null
    readonly property real gap: 16
    // Cells are a card plus the gap after it, and start at the page padding.
    readonly property real gridWidth: width - 2 * Theme.pagePadding + gap
    readonly property int columns: Math.max(1, Math.floor(gridWidth / (220 + gap)))
    readonly property real cardWidth: Math.floor(gridWidth / columns) - gap
    readonly property bool filtered: results.length !== "" || results.progress !== ""
                                     || results.type !== "" || results.resolution !== ""
    // Sorted or filtered: one list of everything in the folder instead of folders, then videos.
    readonly property bool flat: results.sort !== "name" || filtered
    readonly property int videoCount: VideoLibrary.count >= 0 ? VideoLibrary.videoCountIn(folder) : 0

    // Play all or Shuffle: what's listed, in its order, when sorted or filtered; otherwise
    // everything in the folder and the folders inside it.
    function playAll(shuffle) {
        const paths = flat ? results.paths() : VideoLibrary.videoPathsIn(folder);
        app.playAll(paths, tabTitle || VideoLibrary.folderName(folder), shuffle);
    }

    clip: true
    // The thumbnails on show are made first.
    StackView.onActivated: VideoLibrary.prioritize(folder)

    VideoQuery {
        id: results
        folder: page.folder
    }

    component SectionLabel: Text {
        Layout.leftMargin: Theme.pagePadding
        Layout.topMargin: 24
        Layout.bottomMargin: 12
        color: Theme.textPrimary
        font.pixelSize: Theme.sectionSize
        font.weight: Font.Bold
    }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        PageBar {
            Layout.fillWidth: true
            scrolled: !grid.atYBeginning

            RowLayout {
                Layout.fillWidth: true
                Layout.leftMargin: page.StackView.index > 0 ? 8 : Theme.pagePadding
                Layout.rightMargin: Theme.pagePadding - 8
                Layout.topMargin: 14
                spacing: 8

                IconButton {
                    visible: page.StackView.index > 0
                    Layout.alignment: Qt.AlignTop
                    iconName: "back"
                    tip: qsTr("Back (Esc)")
                    onClicked: page.StackView.view.pop()
                }

                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 0

                    PageTitle {
                        title: page.tabTitle || VideoLibrary.folderName(page.folder)
                        subtitle: page.videoCount === 1 ? qsTr("1 video") : qsTr("%1 videos").arg(page.videoCount)
                    }
                    WorkProgress {
                        Layout.fillWidth: true
                        Layout.topMargin: 6
                        folder: page.folder
                    }
                    // Everything here (sub-folders too), or just what the filters left, in the order shown.
                    Row {
                        Layout.topMargin: 6
                        Layout.leftMargin: -12   // the icons line up with the title
                        visible: page.flat ? results.count > 0 : page.videoCount > 0
                        spacing: 4

                        TextAction {
                            text: qsTr("Play all")
                            iconName: "play"
                            onClicked: page.playAll(false)
                        }
                        TextAction {
                            text: qsTr("Shuffle")
                            iconName: "shuffle"
                            onClicked: page.playAll(true)
                        }
                    }
                }

                // A drive's own page.
                IconButton {
                    Layout.alignment: Qt.AlignTop
                    visible: Qt.platform.os === "windows" && VideoLibrary.drives.some(drive => drive.path === page.folder)
                    iconName: "eject"
                    tip: qsTr("Eject")
                    onClicked: page.app.ejectDrive(page.folder)
                }
                IconButton {
                    visible: page.tab === ""
                    Layout.alignment: Qt.AlignTop
                    iconName: "search"
                    tip: qsTr("Search (Ctrl+F)")
                    onClicked: page.app.openSearch()
                }
                AppActions {
                    visible: page.tab !== ""
                }
            }

            FilterBar {
                Layout.fillWidth: true
                Layout.leftMargin: Theme.pagePadding
                Layout.rightMargin: Theme.pagePadding
                Layout.topMargin: 16
                visible: results.types.length > 0
                query: results
            }
        }

        GridView {
            id: grid
            SmoothWheel { flickable: grid }

            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            cellWidth: Math.floor(page.gridWidth / page.columns)
            cellHeight: Math.round(page.cardWidth * 9 / 16) + 52 + page.gap
            model: page.flat ? results : page.videos
            boundsBehavior: Flickable.StopAtBounds
            ScrollBar.vertical: ScrollBar {}

            delegate: Item {
                id: cell
                required property var model
                width: grid.cellWidth
                height: grid.cellHeight

                VideoCard {
                    x: Theme.pagePadding
                    width: page.cardWidth
                    path: cell.model.path
                    title: cell.model.title
                    // Sorted or filtered: what it's sorted by, or how far in, or else where it is.
                    subtitle: page.flat ? [cell.model.detail, cell.model.remainingText].filter(part => part).join("  •  ")
                                          || cell.model.subtitle
                                        : cell.model.remainingText
                    thumbnail: cell.model.thumbnail
                    loading: cell.model.thumbnailPending
                    durationText: cell.model.durationText
                    progress: cell.model.progress
                    watched: cell.model.finished
                    onClicked: page.app.play(cell.model.path)
                }
            }

            footer: Item { width: grid.width; height: 32 }

            header: ColumnLayout {
                width: grid.width
                spacing: 0

                EmptyNote {
                    visible: !page.flat && page.subfolders.count === 0 && page.videos.count === 0 && !VideoLibrary.scanning
                    Layout.topMargin: 12
                    iconName: "folder"
                    text: page.tab === "library" ? qsTr("No videos found on this computer.")
                        : page.tab === "videos" ? qsTr("No videos in your Videos folder. Videos elsewhere on this computer are in the Library.")
                        : qsTr("No videos in this folder.")
                    actionText: page.tab === "library" ? qsTr("Rescan") : page.tab === "videos" ? qsTr("Open Library") : ""
                    onActionTriggered: page.tab === "library" ? page.app.rescanLibrary() : page.app.showLibrary()
                }

                // --- Sorted or filtered ------------------------------------------
                SectionLabel {
                    visible: page.flat && results.count > 0
                    Layout.topMargin: 12
                    text: results.count === 1 ? qsTr("1 video") : qsTr("%1 videos").arg(results.count)
                }

                EmptyNote {
                    visible: page.flat && results.count === 0
                    Layout.topMargin: 12
                    iconName: "search"
                    text: qsTr("No videos here match these filters.")
                }

                // --- Folders, then videos -----------------------------------------
                SectionLabel {
                    visible: !page.flat && page.started !== null && page.started.count > 0
                    Layout.topMargin: 12
                    text: qsTr("Continue watching")
                }

                Shelf {
                    Layout.fillWidth: true
                    visible: !page.flat && page.started !== null && page.started.count > 0
                    model: page.started
                    cellWidth: 276
                    cellHeight: Math.round(260 * 9 / 16) + 62
                    arrowCenterY: 260 * 9 / 32
                    delegate: VideoCard {
                        required property var model
                        width: 260
                        path: model.path
                        title: model.title
                        subtitle: model.remainingText
                        thumbnail: model.thumbnail
                        loading: model.thumbnailPending
                        durationText: model.durationText
                        progress: model.progress
                        watched: model.finished
                        onClicked: page.app.play(model.path)
                    }
                }

                SectionLabel {
                    visible: !page.flat && page.subfolders.count > 0
                    // The first section sits closer under the pinned bar.
                    Layout.topMargin: page.started !== null && page.started.count > 0 ? 24 : 12
                    text: qsTr("Folders")
                }

                Flow {
                    Layout.fillWidth: true
                    Layout.leftMargin: Theme.pagePadding
                    Layout.rightMargin: Theme.pagePadding
                    visible: !page.flat && page.subfolders.count > 0
                    spacing: page.gap

                    Repeater {
                        model: page.subfolders
                        delegate: FolderCard {
                            required property var model
                            width: page.cardWidth
                            name: model.name
                            countText: model.countText
                            folder: model.folder
                            thumbnail: model.thumbnail
                            loading: model.thumbnailPending
                            onClicked: page.app.openFolder(model.folder)
                        }
                    }
                }

                SectionLabel {
                    visible: !page.flat && page.videos.count > 0
                    Layout.topMargin: page.subfolders.count > 0 || (page.started !== null && page.started.count > 0) ? 24 : 12
                    text: qsTr("Videos")
                }
            }
        }
    }
}
