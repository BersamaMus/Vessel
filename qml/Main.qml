import QtCore
import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Dialogs
import QtQuick.Layouts

ApplicationWindow {
    id: window

    // A file passed on the command line.
    property string startFile
    property alias volume: settings.volume
    // How far a double-tap (or J / L) skips, in seconds.
    property alias skipSeconds: settings.skipSeconds
    readonly property bool isFullScreen: visibility === Window.FullScreen
    property int visibilityBeforeFullScreen: Window.Windowed

    function play(path) {
        watch.open(path);
    }

    function openFolder(folder) {
        contentStack.push(folderPage, { folder: folder });
    }

    // 0 Home, 1 Library, 2 External, 3 Settings, as in the rail.
    function showTab(index) {
        rail.currentIndex = index;
        contentStack.replace(null, [homePage, libraryPage, externalPage, settingsPage][index]);
    }

    function showLibrary() {
        showTab(1);
    }

    // A drive's folder, from the "connected" notice.
    function openDrive(path) {
        if (watch.mode === "watch" || watch.mode === "full")
            watch.minimize();
        showTab(2);
        openFolder(path);
    }

    function openFileDialog() {
        fileDialog.open();
    }

    function toggleFullScreen() {
        if (isFullScreen) {
            visibility = visibilityBeforeFullScreen === Window.Maximized ? Window.Maximized : Window.Windowed;
        } else {
            visibilityBeforeFullScreen = visibility;
            visibility = Window.FullScreen;
        }
    }

    width: 1280
    height: 800
    minimumWidth: 720
    minimumHeight: 480
    visible: true
    title: watch.mode !== "closed" && watch.title ? watch.title + " - Better Video Player" : "Better Video Player"
    color: Theme.background

    palette {
        window: Theme.background
        windowText: Theme.textPrimary
        base: Theme.surfaceHigh
        text: Theme.textPrimary
        button: Theme.surfaceHigh
        buttonText: Theme.textPrimary
        highlight: Theme.accent
        highlightedText: Theme.accentText
        toolTipBase: "#2A2A2A"
        toolTipText: Theme.textPrimary
        light: "#3A3A3A"
        midlight: "#2A2A2A"
        mid: "#555555"
        dark: "#111111"
        placeholderText: Theme.textTertiary
    }

    Settings {
        id: settings
        location: VideoLibrary.settingsFile()
        property real volume: 100
        property int skipSeconds: 10
    }

    FileDialog {
        id: fileDialog
        title: qsTr("Open a video")
        currentFolder: StandardPaths.writableLocation(StandardPaths.MoviesLocation)
        nameFilters: [
            qsTr("Videos (*.mp4 *.m4v *.mkv *.mov *.webm *.avi *.wmv *.flv *.ts *.mpg *.mpeg)"),
            qsTr("All files (*)"),
        ]
        onAccepted: window.play(VideoLibrary.localPath(selectedFile))
    }

    Shortcut {
        sequences: [StandardKey.Open]
        onActivated: window.openFileDialog()
    }

    // The library: tabs on the left, pages on the right.
    RowLayout {
        anchors.fill: parent
        spacing: 0

        NavRail {
            id: rail
            Layout.fillHeight: true
            onTabClicked: index => {
                if (index === currentIndex) {
                    contentStack.pop(null);
                    return;
                }
                window.showTab(index);
            }
        }

        StackView {
            id: contentStack
            Layout.fillWidth: true
            Layout.fillHeight: true
            initialItem: homePage

            component Fade: Transition {
                NumberAnimation { property: "opacity"; from: 0; to: 1; duration: 180 }
            }
            component Hold: Transition {
                NumberAnimation { property: "opacity"; from: 1; to: 0; duration: 120 }
            }
            pushEnter: Fade {}
            pushExit: Hold {}
            popEnter: Fade {}
            popExit: Hold {}
            replaceEnter: Fade {}
            replaceExit: Hold {}
        }
    }

    Component {
        id: homePage
        HomePage {}
    }
    Component {
        id: libraryPage
        FolderPage {
            isLibraryTab: true
            folder: VideoLibrary.libraryHome
        }
    }
    Component {
        id: externalPage
        ExternalPage {}
    }
    Component {
        id: settingsPage
        SettingsPage {}
    }
    Component {
        id: folderPage
        FolderPage {}
    }

    DropArea {
        anchors.fill: parent
        onDropped: drop => {
            if (drop.hasUrls)
                window.play(VideoLibrary.localPath(drop.urls[0]));
        }
    }

    // "SANDISK (E:) connected · 34 videos", with a way to open it.
    Rectangle {
        id: notice

        property string drivePath

        function show(message, path) {
            noticeText.text = message;
            drivePath = path;
            opacity = 1;
            noticeTimer.restart();
        }

        x: rail.width + 20
        y: window.height - height - 20
        width: noticeRow.implicitWidth + 28
        height: 52
        radius: Theme.radiusCard
        color: Theme.surfaceHigh
        border.color: "#26FFFFFF"
        opacity: 0
        visible: opacity > 0
        Behavior on opacity { NumberAnimation { duration: 200 } }

        RowLayout {
            id: noticeRow
            anchors.verticalCenter: parent.verticalCenter
            x: 16
            spacing: 12

            Icon {
                name: "usb"
                color: Theme.accent
            }
            Text {
                id: noticeText
                color: Theme.textPrimary
                font.pixelSize: 14
            }
            TextAction {
                text: qsTr("Open")
                onClicked: {
                    notice.opacity = 0;
                    window.openDrive(notice.drivePath);
                }
            }
        }

        Timer {
            id: noticeTimer
            interval: 6000
            onTriggered: notice.opacity = 0
        }
    }

    Connections {
        target: VideoLibrary

        function onDriveConnected(name, path, count) {
            notice.show(count === 0 ? qsTr("%1 connected  •  no videos found").arg(name)
                      : count === 1 ? qsTr("%1 connected  •  1 video").arg(name)
                      : qsTr("%1 connected  •  %2 videos").arg(name).arg(count), path);
        }

        function onDriveRemoved(path) {
            // Back out of the drive's folders, which are now empty.
            while (contentStack.depth > 1 && String(contentStack.currentItem.folder ?? "").startsWith(path))
                contentStack.pop(StackView.Immediate);
            if (notice.drivePath === path)
                notice.opacity = 0;
        }
    }

    // The player, over everything else.
    WatchView {
        id: watch
        anchors.fill: parent
    }

    Component.onCompleted: {
        if (startFile)
            play(startFile);
    }
}
