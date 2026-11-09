import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// "Search online…" in the subtitles menu: subtitles for the video from OpenSubtitles, by
// its title (which can be changed) and in one language. Picking one downloads it next to
// the video and turns it on. One downloaded for this video before is marked, and picking it
// turns the saved file on again, without using up a download. Once the day's free downloads
// are used up, picking one opens its page on the website instead, to download it there and
// drop it on the video.
AppDialog {
    id: dialog

    // The window (Main), for the language last searched in.
    property var app
    property string path
    // What to search for at first: the video's title.
    property string query
    property string language
    // Downloaded for this video before: {fileId: file} (see VideoLibrary.downloadedSubtitles).
    property var downloads: ({})
    readonly property bool available: search.available
    readonly property SubtitleSearch search: SubtitleSearch {
        fallbackFolder: VideoLibrary.subtitleDownloadFolder()
        // Out of downloads: remembered between runs.
        resetAt: dialog.app ? dialog.app.subtitleLimitReset : ""
        onLimitChanged: if (dialog.app) dialog.app.subtitleLimitReset = resetAt
        onDownloaded: (videoPath, fileId, file) => {
            VideoLibrary.noteDownloadedSubtitle(videoPath, fileId, file);
            if (videoPath !== dialog.path)
                return;
            dialog.close();
            dialog.subtitleChosen(file);
        }
    }

    // A subtitle file for the video was downloaded, or picked again: `file` is where it's saved.
    signal subtitleChosen(string file)

    function run() {
        search.search(path, queryField.text, language);
    }

    function pick(result) {
        const saved = downloads[result.fileId];
        if (saved) {
            close();
            subtitleChosen(saved);
        } else if (search.limitReached)
            Qt.openUrlExternally(result.url);
        else
            search.download(result.fileId);
    }

    title: qsTr("Search subtitles online")
    confirmText: qsTr("Close")
    cancelVisible: false
    preferredWidth: 600
    onOpened: {
        downloads = VideoLibrary.downloadedSubtitles(path);
        queryField.text = query;
        language = app.subtitleLanguage !== "" ? app.subtitleLanguage : search.systemLanguage();
        run();
    }
    onClosed: search.cancel()

    RowLayout {
        Layout.fillWidth: true
        spacing: 8

        AppTextField {
            id: queryField
            Layout.fillWidth: true
            placeholderText: qsTr("Title")
            onAccepted: dialog.run()
        }
        DropButton {
            options: dialog.search.languages()
            current: dialog.language
            onChosen: value => {
                dialog.language = value;
                dialog.app.subtitleLanguage = value;
                dialog.run();
            }
        }
        IconButton {
            iconName: "search"
            tip: qsTr("Search")
            onClicked: dialog.run()
        }
    }

    // Out of downloads for today: the way round it.
    Rectangle {
        visible: dialog.search.limitReached
        Layout.fillWidth: true
        implicitHeight: limitText.implicitHeight + 28
        radius: Theme.radiusArt
        color: Theme.accentSoft

        Column {
            id: limitText
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.verticalCenter: parent.verticalCenter
            anchors.margins: 16
            spacing: 4

            Text {
                width: parent.width
                text: qsTr("Daily download limit reached")
                color: Theme.accent
                font.pixelSize: 14
                font.weight: Font.Bold
            }
            Text {
                width: parent.width
                text: qsTr("Pick a subtitle to open it on OpenSubtitles.com and download it there, then drop the file onto the video. Ones you've downloaded before still turn on here. %1")
                      .arg(dialog.search.resetText)
                color: Theme.textPrimary
                font.pixelSize: 13
                wrapMode: Text.Wrap
            }
        }
    }

    // Searching, what went wrong, or that nothing was found.
    RowLayout {
        readonly property string message: dialog.search.searching ? qsTr("Searching OpenSubtitles…")
                                        : dialog.search.error !== "" ? dialog.search.error
                                        : dialog.search.searched && dialog.search.results.length === 0
                                          ? qsTr("No subtitles found. Try another title or language.") : ""
        visible: message !== ""
        Layout.fillWidth: true
        spacing: 10

        Icon {
            visible: dialog.search.searching
            name: "refresh"
            size: 20
            color: Theme.accent
            RotationAnimation on rotation {
                running: dialog.search.searching && dialog.visible
                loops: Animation.Infinite
                from: 0
                to: 360
                duration: 900
                onStopped: parent.rotation = 0
            }
        }
        Text {
            Layout.fillWidth: true
            text: parent.message
            color: dialog.search.error !== "" && !dialog.search.searching ? Theme.error : Theme.textSecondary
            font.pixelSize: 14
            wrapMode: Text.Wrap
        }
    }

    ListView {
        id: list
        Layout.fillWidth: true
        Layout.preferredHeight: Math.min(contentHeight, 360)
        Layout.leftMargin: -8
        Layout.rightMargin: -8
        visible: count > 0
        clip: true
        model: dialog.search.results
        boundsBehavior: Flickable.StopAtBounds
        ScrollBar.vertical: ScrollBar {}

        delegate: AbstractButton {
            id: option

            required property var modelData
            readonly property bool downloading: dialog.search.downloading === modelData.fileId
            readonly property bool saved: dialog.downloads[modelData.fileId] !== undefined

            width: list.width
            height: 60
            hoverEnabled: true
            focusPolicy: Qt.NoFocus
            enabled: dialog.search.downloading === 0 || downloading
            onClicked: if (!downloading) dialog.pick(modelData)

            HoverHandler { cursorShape: Qt.PointingHandCursor }
            ToolTip.visible: hovered && ToolTip.text !== ""
            ToolTip.text: [modelData.title, saved ? qsTr("Downloaded before: click to turn it on") : ""]
                          .filter(part => part !== "").join("\n")
            ToolTip.delay: 600

            background: Rectangle {
                radius: Theme.radiusArt - 4
                color: option.pressed ? Theme.pressed : option.hovered ? Theme.hover : "transparent"
            }
            contentItem: RowLayout {
                spacing: 12

                Icon {
                    Layout.leftMargin: 8
                    name: "subtitles"
                    color: option.modelData.hashMatch ? Theme.accent : Theme.textSecondary
                }
                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 3

                    Text {
                        Layout.fillWidth: true
                        text: option.modelData.name
                        color: option.enabled ? Theme.textPrimary : Theme.textTertiary
                        font.pixelSize: 14
                        elide: Text.ElideMiddle
                    }
                    Row {
                        Layout.fillWidth: true
                        spacing: 0

                        Text {
                            visible: option.saved
                            text: qsTr("Downloaded") + "  •  "
                            color: Theme.accent
                            font.pixelSize: 12
                            font.weight: Font.DemiBold
                        }
                        Text {
                            visible: option.modelData.hashMatch
                            text: qsTr("Made for this file") + "  •  "
                            color: Theme.accent
                            font.pixelSize: 12
                        }
                        Text {
                            text: [option.modelData.language,
                                   qsTr("%1 downloads").arg(Number(option.modelData.downloads).toLocaleString(Qt.locale(), "f", 0)),
                                   option.modelData.hearingImpaired ? qsTr("Hearing impaired") : "",
                                   option.modelData.machine ? qsTr("Machine translated") : ""]
                                  .filter(part => part !== "").join("  •  ")
                            color: Theme.textTertiary
                            font.pixelSize: 12
                        }
                    }
                }
                Item {
                    Layout.rightMargin: 8
                    implicitWidth: 24
                    implicitHeight: 24

                    Icon {
                        anchors.centerIn: parent
                        name: option.downloading ? "refresh" : option.saved ? "check"
                            : dialog.search.limitReached ? "open_in_new" : "download"
                        color: option.downloading || option.saved ? Theme.accent : Theme.textSecondary
                        RotationAnimation on rotation {
                            running: option.downloading
                            loops: Animation.Infinite
                            from: 0
                            to: 360
                            duration: 900
                            onStopped: parent.rotation = 0
                        }
                    }
                }
            }
        }
    }

    Text {
        Layout.fillWidth: true
        text: qsTr("Subtitles from OpenSubtitles.com. Searching sends the video's title and a fingerprint of the file, never the file itself.")
        color: Theme.textTertiary
        font.pixelSize: 12
        wrapMode: Text.Wrap
    }
}
