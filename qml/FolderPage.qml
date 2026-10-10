import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// One folder of the library: its sub-folders, then its videos, in grids. Sorting
// (other than by name) or filtering lists every video in it, sub-folders included.
//
// The videos are the cells of a GridView, which only makes the cards on screen, so
// a drive with thousands of videos stays quick; everything above them is its header.
GridView {
    id: page

    // An absolute path, or "" for the whole library (a card per library folder).
    property string folder: ""
    // The Library tab: the library folder (or all of them) under the name "Library".
    property bool isLibraryTab: false

    readonly property var app: ApplicationWindow.window
    readonly property var subfolders: VideoLibrary.foldersIn(folder)
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

    clip: true
    cellWidth: Math.floor(gridWidth / columns)
    cellHeight: Math.round(cardWidth * 9 / 16) + 52 + gap
    model: flat ? results : videos
    boundsBehavior: Flickable.StopAtBounds
    ScrollBar.vertical: ScrollBar {}
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
        font.pixelSize: 20
        font.weight: Font.Bold
    }

    delegate: Item {
        id: cell
        required property var model
        width: page.cellWidth
        height: page.cellHeight

        VideoCard {
            x: Theme.pagePadding
            width: page.cardWidth
            title: cell.model.title
            // Sorted or filtered: what it's sorted by, or how far in, or else where it is.
            subtitle: page.flat ? [cell.model.detail, cell.model.remainingText].filter(part => part).join("  •  ")
                                  || cell.model.subtitle
                                : cell.model.remainingText
            thumbnail: cell.model.thumbnail
            loading: cell.model.thumbnailPending
            durationText: cell.model.durationText
            progress: cell.model.progress
            onClicked: page.app.play(cell.model.path)
        }
    }

    footer: Item { width: page.width; height: 32 }

    header: ColumnLayout {
        width: page.width
        spacing: 0

        RowLayout {
            Layout.fillWidth: true
            Layout.leftMargin: page.StackView.index > 0 ? 8 : Theme.pagePadding
            Layout.rightMargin: Theme.pagePadding
            Layout.topMargin: 14
            spacing: 8

            IconButton {
                visible: page.StackView.index > 0
                Layout.alignment: Qt.AlignTop
                iconName: "back"
                tip: qsTr("Back")
                onClicked: page.StackView.view.pop()
            }

            ColumnLayout {
                Layout.fillWidth: true
                spacing: 2

                Text {
                    Layout.fillWidth: true
                    text: page.isLibraryTab ? qsTr("Library") : VideoLibrary.folderName(page.folder)
                    color: Theme.textPrimary
                    font.pixelSize: 26
                    font.weight: Font.ExtraBold
                    font.letterSpacing: -0.5
                    elide: Text.ElideRight
                }
                Text {
                    text: {
                        const n = VideoLibrary.count >= 0 ? VideoLibrary.videoCountIn(page.folder) : 0;
                        return n === 1 ? qsTr("1 video") : qsTr("%1 videos").arg(n);
                    }
                    color: Theme.textSecondary
                    font.pixelSize: 14
                }
                WorkProgress {
                    Layout.fillWidth: true
                    Layout.topMargin: 6
                    folder: page.folder
                }
            }

            IconButton {
                Layout.alignment: Qt.AlignTop
                iconName: "search"
                tip: qsTr("Search (Ctrl+F)")
                onClicked: page.app.openSearch()
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

        EmptyNote {
            visible: !page.flat && page.subfolders.count === 0 && page.videos.count === 0 && !VideoLibrary.scanning
            Layout.topMargin: 24
            iconName: "folder"
            text: page.isLibraryTab ? qsTr("No videos in your library yet. Add folders in Settings.")
                                    : qsTr("No videos in this folder.")
        }

        // --- Sorted or filtered ------------------------------------------
        SectionLabel {
            visible: page.flat && results.count > 0
            text: results.count === 1 ? qsTr("1 video") : qsTr("%1 videos").arg(results.count)
        }

        EmptyNote {
            visible: page.flat && results.count === 0
            Layout.topMargin: 24
            iconName: "search"
            text: qsTr("No videos here match these filters.")
        }

        // --- Folders, then videos -----------------------------------------
        SectionLabel {
            visible: !page.flat && page.started !== null && page.started.count > 0
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
                title: model.title
                subtitle: model.remainingText
                thumbnail: model.thumbnail
                loading: model.thumbnailPending
                durationText: model.durationText
                progress: model.progress
                onClicked: page.app.play(model.path)
            }
        }

        SectionLabel {
            visible: !page.flat && page.subfolders.count > 0
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
                    thumbnail: model.thumbnail
                    loading: model.thumbnailPending
                    onClicked: page.app.openFolder(model.folder)
                }
            }
        }

        SectionLabel {
            visible: !page.flat && page.videos.count > 0
            text: qsTr("Videos")
        }
    }
}
