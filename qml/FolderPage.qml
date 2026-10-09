import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// One folder of the library: its sub-folders, then its videos, in grids.
ScrollView {
    id: page

    // An absolute path, or "" for the whole library (a card per library folder).
    property string folder: ""
    // The Library tab: the library folder (or all of them) under the name "Library".
    property bool isLibraryTab: false

    readonly property var app: ApplicationWindow.window
    readonly property var subfolders: VideoLibrary.foldersIn(folder)
    readonly property var videos: VideoLibrary.videosIn(folder)
    readonly property int columns: Math.max(1, Math.floor((availableWidth - 2 * Theme.pagePadding + gap) / (220 + gap)))
    readonly property real gap: 16
    readonly property real cardWidth: Math.floor((availableWidth - 2 * Theme.pagePadding - (columns - 1) * gap) / columns)

    contentWidth: availableWidth
    clip: true

    component SectionLabel: Text {
        Layout.leftMargin: Theme.pagePadding
        Layout.topMargin: 24
        Layout.bottomMargin: 12
        color: Theme.textPrimary
        font.pixelSize: 20
        font.weight: Font.Bold
    }

    ColumnLayout {
        width: page.availableWidth
        spacing: 0

        RowLayout {
            Layout.fillWidth: true
            Layout.leftMargin: page.StackView.index > 0 ? 8 : Theme.pagePadding
            Layout.rightMargin: Theme.pagePadding
            Layout.topMargin: 14
            spacing: 8

            IconButton {
                visible: page.StackView.index > 0
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
            }
        }

        EmptyNote {
            visible: page.subfolders.count === 0 && page.videos.count === 0 && !VideoLibrary.scanning
            Layout.topMargin: 24
            iconName: "folder"
            text: page.isLibraryTab ? qsTr("No videos in your library yet. Add folders in Settings.")
                                    : qsTr("No videos in this folder.")
        }

        SectionLabel {
            visible: page.subfolders.count > 0
            text: qsTr("Folders")
        }

        Flow {
            Layout.fillWidth: true
            Layout.leftMargin: Theme.pagePadding
            Layout.rightMargin: Theme.pagePadding
            visible: page.subfolders.count > 0
            spacing: page.gap

            Repeater {
                model: page.subfolders
                delegate: FolderCard {
                    required property var model
                    width: page.cardWidth
                    name: model.name
                    countText: model.countText
                    thumbnail: model.thumbnail
                    onClicked: page.app.openFolder(model.folder)
                }
            }
        }

        SectionLabel {
            visible: page.videos.count > 0
            text: qsTr("Videos")
        }

        Flow {
            Layout.fillWidth: true
            Layout.leftMargin: Theme.pagePadding
            Layout.rightMargin: Theme.pagePadding
            visible: page.videos.count > 0
            spacing: page.gap

            Repeater {
                model: page.videos
                delegate: VideoCard {
                    required property var model
                    width: page.cardWidth
                    title: model.title
                    subtitle: model.remainingText
                    thumbnail: model.thumbnail
                    durationText: model.durationText
                    progress: model.progress
                    onClicked: page.app.play(model.path)
                }
            }
        }

        Item { implicitHeight: 32 }
    }
}
