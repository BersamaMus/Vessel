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
    // Play the next video in the folder when one ends.
    property alias autoplay: settings.autoplay
    readonly property bool isFullScreen: visibility === Window.FullScreen
    // Set once the user has confirmed closing while a video plays.
    property bool closeConfirmed: false

    onClosing: close => {
        if (watch.playing && !closeConfirmed) {
            close.accepted = false;
            closeDialog.open();
        }
    }
    property int visibilityBeforeFullScreen: Window.Windowed

    function play(path) {
        watch.open(path);
    }

    function openFolder(folder) {
        contentStack.push(folderPage, { folder: folder });
    }

    // The search page, over whichever page is showing (or back to it, if it already is).
    // It searches what that tab is about: on External, the drive being looked at (or
    // every drive, from the list of them); anywhere else, the library.
    function openSearch() {
        if (watch.mode === "watch" || watch.mode === "full")
            watch.minimize();
        const current = contentStack.currentItem;
        if (current instanceof SearchPage) {
            current.focusField();
            return;
        }
        let scope = "library";
        if (rail.currentIndex === 2)
            scope = VideoLibrary.driveOf(current.folder ?? "") || "drives";
        else if (rail.currentIndex === 3)
            scope = VideoLibrary.driveOf(current.folder ?? "") || "phones";
        contentStack.push(searchPage, { scope: scope });
    }

    // The last few searches, newest first.
    readonly property var recentSearches: {
        try {
            const list = JSON.parse(settings.recentSearches);
            return Array.isArray(list) ? list : [];
        } catch (e) {
            return [];
        }
    }

    function addRecentSearch(text) {
        const others = recentSearches.filter(s => s.toLowerCase() !== text.toLowerCase());
        settings.recentSearches = JSON.stringify([text].concat(others).slice(0, 8));
    }

    function removeRecentSearch(text) {
        settings.recentSearches = JSON.stringify(recentSearches.filter(s => s !== text));
    }

    // 0 Home, 1 Library, 2 External, 3 Smartphone, 4 Settings, as in the rail.
    function showTab(index) {
        rail.currentIndex = index;
        contentStack.replace(null, [homePage, libraryPage, externalPage, phonePage, settingsPage][index]);
    }

    function showLibrary() {
        showTab(1);
    }

    // A drive's (or phone's) videos, from the "connected" notice.
    function openDrive(path) {
        if (watch.mode === "watch" || watch.mode === "full")
            watch.minimize();
        showTab(VideoLibrary.isPhone(path) ? 3 : 2);
        openFolder(path);
    }

    // A short notice at the bottom left. options: { icon, actionText, action, busy, duration }.
    function toast(message, options) {
        notice.show(message, options);
    }

    function rescanLibrary() {
        toast(qsTr("Looking for videos…"), { busy: true });
        VideoLibrary.rescan();
    }

    // Removing a library folder can be undone from the notice.
    function removeLibraryFolder(path) {
        const name = VideoLibrary.folderName(path);
        const position = VideoLibrary.removeLibraryFolder(path);
        toast(qsTr("Removed %1 from your library").arg(name),
              { icon: "folder", actionText: qsTr("Undo"), action: () => VideoLibrary.addLibraryFolder(path, position), duration: 8000 });
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
        property bool autoplay: true
        // A JSON list (QSettings would turn a one-item list into a plain string).
        property string recentSearches: "[]"
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

    Shortcut {
        sequences: [StandardKey.Find]
        onActivated: window.openSearch()
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
        id: phonePage
        PhonePage {}
    }
    Component {
        id: settingsPage
        SettingsPage {}
    }
    Component {
        id: folderPage
        FolderPage {}
    }
    Component {
        id: searchPage
        SearchPage {}
    }

    DropArea {
        anchors.fill: parent
        onDropped: drop => {
            if (drop.hasUrls)
                window.play(VideoLibrary.localPath(drop.urls[0]));
        }
    }

    // The player, over everything else.
    WatchView {
        id: watch
        anchors.fill: parent
    }

    // A short message at the bottom left, above everything (the player too): what an
    // action did, with an optional button (Open, Undo) and a spinner for work in progress.
    Rectangle {
        id: notice

        property string iconName: "check"
        property string actionText
        property var action: null
        property bool busy: false

        function show(message, options) {
            const o = options ?? {};
            noticeText.text = message;
            iconName = o.icon ?? "check";
            actionText = o.actionText ?? "";
            action = o.action ?? null;
            busy = o.busy ?? false;
            opacity = 1;
            if (busy) {
                noticeTimer.stop();
            } else {
                noticeTimer.interval = o.duration ?? 5000;
                noticeTimer.restart();
            }
        }

        x: rail.width + 20
        y: window.height - height - 20
        width: Math.min(noticeRow.implicitWidth + 32, window.width - x - 20)
        height: 52
        radius: Theme.radiusCard
        color: Theme.surfaceHigh
        border.color: "#26FFFFFF"
        opacity: 0
        visible: opacity > 0
        Behavior on opacity { NumberAnimation { duration: 200 } }

        // Clicks on the notice shouldn't reach the page or video underneath.
        MouseArea {
            anchors.fill: parent
        }

        RowLayout {
            id: noticeRow
            anchors.verticalCenter: parent.verticalCenter
            x: 16
            width: parent.width - 32
            spacing: 12

            Icon {
                name: notice.busy ? "refresh" : notice.iconName
                color: Theme.accent
                RotationAnimation on rotation {
                    running: notice.busy && notice.visible
                    loops: Animation.Infinite
                    from: 0
                    to: 360
                    duration: 900
                    onStopped: parent.rotation = 0
                }
            }
            Text {
                id: noticeText
                Layout.fillWidth: true
                color: Theme.textPrimary
                font.pixelSize: 14
                elide: Text.ElideRight
            }
            TextAction {
                visible: notice.actionText !== ""
                text: notice.actionText
                onClicked: {
                    const action = notice.action;
                    notice.opacity = 0;
                    if (action)
                        action();
                }
            }
        }

        Timer {
            id: noticeTimer
            interval: 5000
            onTriggered: notice.opacity = 0
        }
    }

    Connections {
        target: VideoLibrary

        function onDriveConnected(name, path, count) {
            window.toast(count === 0 ? qsTr("%1 connected  •  no videos found").arg(name)
                       : count === 1 ? qsTr("%1 connected  •  1 video").arg(name)
                       : qsTr("%1 connected  •  %2 videos").arg(name).arg(count),
                         { icon: VideoLibrary.isPhone(path) ? "smartphone" : "usb", actionText: qsTr("Open"),
                           action: () => window.openDrive(path), duration: 7000 });
        }

        function onDriveRemoved(path, name) {
            // Back out of the drive's folders, which are now empty.
            while (contentStack.depth > 1 && String(contentStack.currentItem.folder ?? "").startsWith(path))
                contentStack.pop(StackView.Immediate);
            const phone = VideoLibrary.isPhone(path);
            window.toast(phone ? qsTr("%1 disconnected").arg(name) : qsTr("%1 removed").arg(name),
                         { icon: phone ? "smartphone" : "usb" });
        }

        function onRescanFinished(count, added, removed) {
            const videos = count === 1 ? qsTr("1 video") : qsTr("%1 videos").arg(count);
            const changes = [added > 0 ? qsTr("%1 new").arg(added) : "",
                             removed > 0 ? qsTr("%1 no longer found").arg(removed) : ""].filter(part => part);
            window.toast(changes.length > 0 ? qsTr("Library updated  •  %1  •  %2").arg(videos).arg(changes.join(", "))
                                            : qsTr("Library is up to date  •  %1").arg(videos));
        }

        function onLibraryFolderAdded(name, path, count) {
            window.toast(qsTr("%1 added to your library  •  %2").arg(name)
                         .arg(count === 1 ? qsTr("1 video") : qsTr("%1 videos").arg(count)), { icon: "folder" });
        }
    }

    // "Close Better Video Player?" while a video is playing.
    Popup {
        id: closeDialog

        anchors.centerIn: Overlay.overlay
        width: Math.min(420, window.width - 48)
        modal: true
        padding: 24

        Overlay.modal: Rectangle { color: "#99000000" }

        background: Rectangle {
            radius: 24
            color: "#141414"
            border.color: "#26FFFFFF"
        }

        component DialogButton: AbstractButton {
            id: dialogButton
            property bool primary: false
            hoverEnabled: true
            focusPolicy: Qt.NoFocus
            leftPadding: 20
            rightPadding: 20
            topPadding: 10
            bottomPadding: 10
            HoverHandler { cursorShape: Qt.PointingHandCursor }
            background: Rectangle {
                radius: height / 2
                color: dialogButton.primary ? (dialogButton.hovered ? Qt.lighter(Theme.accent, 1.1) : Theme.accent)
                                            : (dialogButton.hovered ? "#3A3A3A" : "#2A2A2A")
            }
            contentItem: Text {
                text: dialogButton.text
                color: dialogButton.primary ? Theme.accentText : Theme.textPrimary
                font.pixelSize: 14
                font.weight: Font.Bold
            }
        }

        contentItem: ColumnLayout {
            spacing: 10

            Text {
                text: qsTr("Close Better Video Player?")
                color: Theme.textPrimary
                font.pixelSize: 20
                font.weight: Font.Bold
            }
            Text {
                Layout.fillWidth: true
                text: qsTr("A video is still playing. Your place will be saved, so you can pick up where you left off.")
                color: Theme.textSecondary
                font.pixelSize: 14
                wrapMode: Text.Wrap
            }

            RowLayout {
                Layout.alignment: Qt.AlignRight
                Layout.topMargin: 12
                spacing: 8

                DialogButton {
                    text: qsTr("Keep watching")
                    onClicked: closeDialog.close()
                }
                DialogButton {
                    primary: true
                    text: qsTr("Close")
                    onClicked: {
                        window.closeConfirmed = true;
                        window.close();
                    }
                }
            }
        }
    }

    Component.onCompleted: {
        if (startFile)
            play(startFile);
    }
}
