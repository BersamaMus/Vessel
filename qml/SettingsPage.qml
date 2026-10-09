import QtCore
import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Dialogs
import QtQuick.Layouts

ScrollView {
    id: page

    function nativePath(path) {
        return Qt.platform.os === "windows" ? path.replace(/\//g, "\\") : path;
    }

    contentWidth: availableWidth
    clip: true

    component SectionTitle: Text {
        Layout.leftMargin: Theme.pagePadding
        Layout.topMargin: 28
        color: Theme.textPrimary
        font.pixelSize: 20
        font.weight: Font.Bold
    }

    component Note: Text {
        Layout.fillWidth: true
        Layout.leftMargin: Theme.pagePadding
        Layout.rightMargin: Theme.pagePadding
        Layout.topMargin: 4
        color: Theme.textSecondary
        font.pixelSize: 14
        wrapMode: Text.Wrap
    }

    ColumnLayout {
        width: page.availableWidth
        spacing: 0

        Text {
            Layout.leftMargin: Theme.pagePadding
            Layout.topMargin: 14
            text: qsTr("Settings")
            color: Theme.textPrimary
            font.pixelSize: 26
            font.weight: Font.ExtraBold
            font.letterSpacing: -0.5
        }

        // --- Library folders -------------------------------------------------
        SectionTitle { text: qsTr("Library folders") }
        Note {
            text: qsTr("Videos in these folders appear on Home and in the Library. Removing a folder here doesn't delete anything.")
        }

        Rectangle {
            Layout.fillWidth: true
            Layout.leftMargin: Theme.pagePadding
            Layout.rightMargin: Theme.pagePadding
            Layout.topMargin: 14
            implicitHeight: folderList.implicitHeight + 12
            radius: Theme.radiusCard
            color: Theme.surface
            border.color: "#1FFFFFFF"

            ColumnLayout {
                id: folderList
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.top: parent.top
                anchors.margins: 6
                spacing: 0

                Text {
                    visible: VideoLibrary.libraryFolders.length === 0
                    Layout.margins: 14
                    text: qsTr("No folders yet. Add one to fill your library.")
                    color: Theme.textSecondary
                    font.pixelSize: 14
                }

                Repeater {
                    model: VideoLibrary.libraryFolders

                    delegate: Rectangle {
                        id: row

                        required property string modelData

                        Layout.fillWidth: true
                        implicitHeight: 64
                        radius: Theme.radiusArt
                        color: rowHover.hovered ? Theme.hover : "transparent"

                        HoverHandler { id: rowHover }

                        RowLayout {
                            anchors.fill: parent
                            anchors.leftMargin: 14
                            anchors.rightMargin: 6
                            spacing: 14

                            Icon {
                                name: "folder"
                                color: Theme.accent
                            }
                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 2
                                Text {
                                    Layout.fillWidth: true
                                    text: VideoLibrary.folderName(row.modelData)
                                    color: Theme.textPrimary
                                    font.pixelSize: 15
                                    font.weight: Font.DemiBold
                                    elide: Text.ElideRight
                                }
                                Text {
                                    Layout.fillWidth: true
                                    text: {
                                        const n = VideoLibrary.count >= 0 ? VideoLibrary.videoCountIn(row.modelData) : 0;
                                        return page.nativePath(row.modelData) + "  •  "
                                             + (n === 1 ? qsTr("1 video") : qsTr("%1 videos").arg(n));
                                    }
                                    color: Theme.textSecondary
                                    font.pixelSize: 13
                                    elide: Text.ElideMiddle
                                }
                            }
                            IconButton {
                                iconName: "close"
                                iconColor: Theme.textSecondary
                                tip: qsTr("Remove from library")
                                onClicked: VideoLibrary.removeLibraryFolder(row.modelData)
                            }
                        }
                    }
                }
            }
        }

        RowLayout {
            Layout.leftMargin: Theme.pagePadding - 8
            Layout.topMargin: 10
            spacing: 4

            TextAction {
                text: qsTr("Add folder")
                iconName: "add"
                onClicked: folderDialog.open()
            }
            TextAction {
                text: VideoLibrary.scanning ? qsTr("Scanning…") : qsTr("Rescan")
                iconName: "refresh"
                enabled: !VideoLibrary.scanning
                onClicked: VideoLibrary.rescan()
            }
        }

        Note {
            id: folderError
            visible: text !== ""
            color: "#F05252"
        }

        // --- About -------------------------------------------------------------
        SectionTitle { text: qsTr("About") }
        Note {
            text: qsTr("Better Video Player %1. Plays the videos on this computer and on drives you plug in. It never goes online.")
                  .arg(Qt.application.version)
        }

        Item { implicitHeight: 32 }
    }

    FolderDialog {
        id: folderDialog
        title: qsTr("Add a folder to your library")
        currentFolder: StandardPaths.writableLocation(StandardPaths.HomeLocation)
        onAccepted: folderError.text = VideoLibrary.addLibraryFolder(VideoLibrary.localPath(selectedFolder))
    }
}
