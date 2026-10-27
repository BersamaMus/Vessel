import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// One folder of the library, seen two ways (the pills under the title): Folders, its
// sub-folders and then its own videos; or Videos, every video in it and its sub-folders
// in one grid, with the sort and filters. A folder with no sub-folders only has Videos.
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
    // Whether there are sub-folders, so the Folders view is offered. While a drive is still
    // being looked through they can turn up after the page has opened; it then waits until
    // the list is scrolled back to the top to switch, rather than jumping back there.
    readonly property bool hasFolders: subfolders.count > 0 && (grid.atYBeginning || hadFolders)
    property bool hadFolders: false
    onHasFoldersChanged: hadFolders = hasFolders
    Component.onCompleted: hadFolders = hasFolders
    readonly property bool filtered: results.filtered
    // In the order they're in on disk, with nothing filtered out.
    readonly property bool plain: results.sort === "name" && !filtered
    // The Videos view (the choice is shared by every folder page): one list of everything
    // in the folder, sub-folders included, instead of folders and then videos.
    readonly property bool flat: !hasFolders || (app !== null && app.folderView === "videos")
    readonly property int videoCount: VideoLibrary.count >= 0 ? VideoLibrary.videoCountIn(folder) : 0

    // Play all or Shuffle: what's listed, in its order, in the Videos view; otherwise
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
        active: page.flat
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

            ViewTabs {
                Layout.leftMargin: Theme.pagePadding
                Layout.topMargin: 16
                visible: page.hasFolders
                options: [
                    { value: "folders", label: qsTr("Folders") },
                    { value: "videos", label: qsTr("Videos") },
                ]
                current: page.flat ? "videos" : "folders"
                onChosen: value => page.app.folderView = value
            }

            // Only the Videos view is sorted and filtered.
            FilterBar {
                Layout.fillWidth: true
                Layout.leftMargin: Theme.pagePadding
                Layout.rightMargin: Theme.pagePadding
                Layout.topMargin: page.hasFolders ? 12 : 16
                visible: page.flat && results.types.length > 0
                query: results
            }
        }

        VideoGrid {
            id: grid
            Layout.fillWidth: true
            Layout.fillHeight: true
            model: page.flat ? results : page.videos
            // In the Videos view, what it's sorted by, or else which folder it's in (unless
            // there's only this one).
            detailed: page.flat && (page.hasFolders || !page.plain)

            header: ColumnLayout {
                width: grid.width
                spacing: 0

                EmptyNote {
                    visible: !page.hasFolders && page.videoCount === 0 && !VideoLibrary.scanning
                    Layout.topMargin: 12
                    iconName: "folder"
                    text: page.tab === "library" ? qsTr("No videos found on this computer.")
                        : page.tab === "videos" ? qsTr("No videos in your Videos folder. Videos elsewhere on this computer are in the Library.")
                        : qsTr("No videos in this folder.")
                    actionText: page.tab === "library" ? qsTr("Rescan") : page.tab === "videos" ? qsTr("Open Library") : ""
                    onActionTriggered: page.tab === "library" ? page.app.rescanLibrary() : page.app.showLibrary()
                }

                // A drive's page leads with this in the Folders view (or, on a drive with no
                // folders, above its videos while they're in name order).
                SectionLabel {
                    id: startedLabel
                    visible: page.started !== null && page.started.count > 0
                             && (!page.flat || (!page.hasFolders && page.plain))
                    Layout.topMargin: 12
                    text: qsTr("Continue watching")
                }

                Shelf {
                    Layout.fillWidth: true
                    visible: startedLabel.visible
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

                // --- Videos: everything in the folder, sorted and filtered ----------
                SectionLabel {
                    visible: page.flat && results.count > 0
                    // The first section sits closer under the pinned bar.
                    Layout.topMargin: startedLabel.visible ? 24 : 12
                    text: results.count === 1 ? qsTr("1 video") : qsTr("%1 videos").arg(results.count)
                }

                EmptyNote {
                    visible: page.flat && results.count === 0 && page.filtered
                    Layout.topMargin: 12
                    iconName: "search"
                    text: qsTr("No videos here match these filters.")
                }

                // --- Folders: the sub-folders, then the folder's own videos ---------
                SectionLabel {
                    visible: !page.flat
                    Layout.topMargin: startedLabel.visible ? 24 : 12
                    text: qsTr("Folders")
                }

                Flow {
                    Layout.fillWidth: true
                    Layout.leftMargin: Theme.pagePadding
                    Layout.rightMargin: Theme.pagePadding
                    visible: !page.flat
                    spacing: grid.gap

                    Repeater {
                        model: page.subfolders
                        delegate: FolderCard {
                            required property var model
                            width: grid.cardWidth
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
                    text: qsTr("Videos in this folder")
                }
            }
        }
    }
}
