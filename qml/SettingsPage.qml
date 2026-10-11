import QtCore
import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Dialogs
import QtQuick.Layouts

// Library folders, the player, subtitles and the rest, under a pinned title.
Item {
    PageBar {
        id: bar
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        scrolled: page.contentY > 1

        RowLayout {
            Layout.fillWidth: true
            Layout.leftMargin: Theme.pagePadding
            Layout.rightMargin: Theme.pagePadding - 8
            Layout.topMargin: 14
            spacing: 8

            PageTitle {
                title: qsTr("Settings")
            }
            AppActions {}
        }
    }

    PageScroll {
        id: page
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: bar.bottom
        anchors.bottom: parent.bottom

        // The window's settings (as a property: reads stay quiet while the page is being taken down).
        readonly property var app: ApplicationWindow.window

        function nativePath(path) {
            return Qt.platform.os === "windows" ? path.replace(/\//g, "\\") : path;
        }

        component SectionTitle: Text {
            Layout.leftMargin: Theme.pagePadding
            Layout.topMargin: 28
            color: Theme.textPrimary
            font.pixelSize: Theme.sectionSize
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

        // A row of pill buttons to pick one of: options are [{value, label}].
        component Choices: Flow {
            id: choices

            property var options: []
            property var current
            signal chosen(var value)

            Layout.fillWidth: true
            Layout.leftMargin: Theme.pagePadding
            Layout.rightMargin: Theme.pagePadding
            Layout.topMargin: 10
            spacing: 8

            Repeater {
                model: choices.options

                delegate: AbstractButton {
                    id: option

                    required property var modelData
                    readonly property bool selected: choices.current === modelData.value

                    hoverEnabled: true
                    focusPolicy: Qt.NoFocus
                    leftPadding: 18
                    rightPadding: 18
                    topPadding: 9
                    bottomPadding: 9
                    onClicked: choices.chosen(modelData.value)

                    HoverHandler { cursorShape: Qt.PointingHandCursor }

                    background: Rectangle {
                        radius: height / 2
                        color: option.selected ? Theme.accent : option.hovered ? Theme.surfaceHover : Theme.surfaceHigh
                    }
                    contentItem: Text {
                        text: option.modelData.label
                        color: option.selected ? Theme.accentText : Theme.textPrimary
                        font.pixelSize: 14
                        font.weight: Font.DemiBold
                    }
                }
            }
        }

        // A label above a row of choices.
        component Label: Text {
            Layout.leftMargin: Theme.pagePadding
            Layout.topMargin: 16
            color: Theme.textPrimary
            font.pixelSize: 15
            font.weight: Font.DemiBold
        }

        ColumnLayout {
            width: page.availableWidth
            spacing: 0

            // --- Library folders -------------------------------------------------
            SectionTitle {
            Layout.topMargin: 12
            text: qsTr("Library folders")
        }
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
                border.color: Theme.borderSoft

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
                                    onClicked: page.app?.removeLibraryFolder(row.modelData)
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
                    onClicked: page.app?.rescanLibrary()
                }
            }

            Note {
                id: folderError
                visible: text !== ""
                color: Theme.error
            }

            // --- Player ------------------------------------------------------------
            SectionTitle { text: qsTr("Player") }
            Note {
                text: qsTr("How far J and L skip back and forward in a video.")
            }

            Choices {
                Layout.topMargin: 14
                options: [5, 10, 15, 30].map(seconds => ({ value: seconds, label: qsTr("%1 seconds").arg(seconds) }))
                current: page.app?.skipSeconds
                onChosen: value => page.app.skipSeconds = value
            }

            // Autoplay on / off.
            AbstractButton {
                id: autoplayRow

                readonly property bool on: page.app?.autoplay ?? false

                Layout.fillWidth: true
                Layout.leftMargin: Theme.pagePadding
                Layout.rightMargin: Theme.pagePadding
                Layout.topMargin: 20
                hoverEnabled: true
                focusPolicy: Qt.NoFocus
                padding: 14
                onClicked: page.app.autoplay = !on

                HoverHandler { cursorShape: Qt.PointingHandCursor }

                background: Rectangle {
                    radius: Theme.radiusCard
                    color: autoplayRow.hovered ? Theme.surfaceHigh : Theme.surface
                    border.color: Theme.borderSoft
                }

                contentItem: RowLayout {
                    spacing: 14

                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 2
                        Text {
                            text: qsTr("Autoplay")
                            color: Theme.textPrimary
                            font.pixelSize: 15
                            font.weight: Font.DemiBold
                        }
                        Text {
                            Layout.fillWidth: true
                            text: qsTr("When a video ends, play the next one in its folder after a short countdown.")
                            color: Theme.textSecondary
                            font.pixelSize: 13
                            wrapMode: Text.Wrap
                        }
                    }

                    AppSwitch {
                        checked: autoplayRow.on
                        onClicked: page.app.autoplay = !autoplayRow.on
                    }
                }
            }

            // --- Subtitles ---------------------------------------------------------
            SectionTitle { text: qsTr("Subtitles") }
            Note {
                text: qsTr("How subtitles look. Styled subtitles (.ass) keep their own colours, but follow the size.")
            }

            // What they'll look like.
            Rectangle {
                Layout.fillWidth: true
                Layout.leftMargin: Theme.pagePadding
                Layout.rightMargin: Theme.pagePadding
                Layout.topMargin: 14
                implicitHeight: 110
                radius: Theme.radiusCard
                gradient: Gradient {
                    GradientStop { position: 0; color: "#3A4A5A" }
                    GradientStop { position: 1; color: "#1A2228" }
                }
                clip: true

                Rectangle {
                    anchors.horizontalCenter: parent.horizontalCenter
                    anchors.bottom: parent.bottom
                    anchors.bottomMargin: 14
                    width: sample.implicitWidth + 16
                    height: sample.implicitHeight + 6
                    radius: 4
                    color: "#C0000000"
                    visible: page.app?.subtitleBackground === "box"
                }
                Text {
                    id: sample
                    anchors.horizontalCenter: parent.horizontalCenter
                    anchors.bottom: parent.bottom
                    anchors.bottomMargin: 17
                    text: qsTr("I'll see you tomorrow.")
                    color: page.app?.subtitleColor === "yellow" ? "#FFE14D" : "white"
                    font.pixelSize: 22 * (({ small: 0.8, medium: 1, large: 1.25, xlarge: 1.5 })[page.app?.subtitleSize] ?? 1)
                    font.weight: Font.DemiBold
                    style: page.app?.subtitleBackground === "outline" ? Text.Outline : Text.Normal
                    styleColor: "black"
                }
            }

            Label { text: qsTr("Size") }
            Choices {
                options: [{ value: "small", label: qsTr("Small") }, { value: "medium", label: qsTr("Medium") },
                          { value: "large", label: qsTr("Large") }, { value: "xlarge", label: qsTr("Extra large") }]
                current: page.app?.subtitleSize
                onChosen: value => page.app.subtitleSize = value
            }

            Label { text: qsTr("Colour") }
            Choices {
                options: [{ value: "white", label: qsTr("White") }, { value: "yellow", label: qsTr("Yellow") }]
                current: page.app?.subtitleColor
                onChosen: value => page.app.subtitleColor = value
            }

            Label { text: qsTr("Background") }
            Choices {
                options: [{ value: "outline", label: qsTr("Outline") }, { value: "box", label: qsTr("Dark box") },
                          { value: "none", label: qsTr("None") }]
                current: page.app?.subtitleBackground
                onChosen: value => page.app.subtitleBackground = value
            }

            // --- Default video player ---------------------------------------------
            SectionTitle {
                visible: Qt.platform.os === "windows"
                text: qsTr("Default video player")
            }
            Note {
                visible: Qt.platform.os === "windows"
                text: qsTr("Vessel is offered under \"Open with\" for videos. To open them in Vessel when you double-click, choose it in Windows' Default apps.")
            }
            AbstractButton {
                id: defaultsButton
                visible: Qt.platform.os === "windows"
                Layout.leftMargin: Theme.pagePadding
                Layout.topMargin: 14
                hoverEnabled: true
                focusPolicy: Qt.NoFocus
                leftPadding: 18
                rightPadding: 18
                topPadding: 10
                bottomPadding: 10
                text: qsTr("Open Default apps")
                onClicked: Qt.openUrlExternally("ms-settings:defaultapps?registeredAppUser=Vessel")
                HoverHandler { cursorShape: Qt.PointingHandCursor }
                background: Rectangle {
                    radius: height / 2
                    color: defaultsButton.hovered ? Theme.surfaceHover : Theme.surfaceHigh
                }
                contentItem: Text {
                    text: defaultsButton.text
                    color: Theme.textPrimary
                    font.pixelSize: 14
                    font.weight: Font.DemiBold
                }
            }

            // --- About -------------------------------------------------------------
            SectionTitle { text: qsTr("About") }
            Note {
                text: qsTr("Vessel %1. Plays the videos on this computer, on drives you plug in and on phones connected by USB. It never goes online.")
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
}
