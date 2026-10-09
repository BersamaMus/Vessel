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
    readonly property bool isFullScreen: visibility === Window.FullScreen
    property int visibilityBeforeFullScreen: Window.Windowed

    function play(path) {
        if (!path)
            return;
        if (rootStack.depth > 1)
            rootStack.currentItem.saveProgress();
        rootStack.pop(null, StackView.Immediate);
        rootStack.push(playerPage, { path: path });
    }

    function openFolder(folder) {
        contentStack.push(folderPage, { folder: folder });
    }

    function showLibrary() {
        rail.currentIndex = 1;
        contentStack.replace(null, libraryPage);
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
    title: rootStack.depth > 1 ? rootStack.currentItem.title + " - Better Video Player" : "Better Video Player"
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

    StackView {
        id: rootStack
        anchors.fill: parent

        pushEnter: Transition {
            NumberAnimation { property: "opacity"; from: 0; to: 1; duration: 200 }
        }
        pushExit: Transition {
            NumberAnimation { property: "opacity"; from: 1; to: 1; duration: 200 }
        }
        popEnter: Transition {
            NumberAnimation { property: "opacity"; from: 1; to: 1; duration: 200 }
        }
        popExit: Transition {
            NumberAnimation { property: "opacity"; from: 1; to: 0; duration: 200 }
        }

        initialItem: Item {
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
                        currentIndex = index;
                        contentStack.replace(null, index === 0 ? homePage : libraryPage);
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
        }
    }

    Component {
        id: homePage
        HomePage {}
    }
    Component {
        id: libraryPage
        FolderPage { isLibraryTab: true }
    }
    Component {
        id: folderPage
        FolderPage {}
    }
    Component {
        id: playerPage
        PlayerPage {}
    }

    DropArea {
        anchors.fill: parent
        onDropped: drop => {
            if (drop.hasUrls)
                window.play(VideoLibrary.localPath(drop.urls[0]));
        }
    }

    Component.onCompleted: {
        if (startFile)
            play(startFile);
    }
}
