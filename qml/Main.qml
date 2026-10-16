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
    // How far J and L skip, in seconds.
    property alias skipSeconds: settings.skipSeconds
    // Play the next video in the folder when one ends.
    property alias autoplay: settings.autoplay
    // Subtitles: size "small" / "medium" / "large" / "xlarge", colour "white" / "yellow",
    // background "outline" / "box" / "none" (see WatchView.applySubtitleStyle).
    property alias subtitleSize: settings.subtitleSize
    property alias subtitleColor: settings.subtitleColor
    property alias subtitleBackground: settings.subtitleBackground
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

    // One video, on its own: next and previous are its neighbours in its folder.
    function play(path) {
        watch.playlist = [];
        watch.open(path);
    }

    // A list of videos, played in turn (Play all), or in a random order (Shuffle). It
    // starts from `start`, if given, and otherwise from the first.
    function playAll(paths, name, shuffle, start) {
        if (paths.length === 0)
            return;
        const list = paths.slice();
        if (shuffle) {
            for (let i = list.length - 1; i > 0; --i) {
                const j = Math.floor(Math.random() * (i + 1));
                [list[i], list[j]] = [list[j], list[i]];
            }
        }
        watch.openList(list, name, shuffle, start);
    }

    // Everything in a folder (and the folders inside it).
    function playFolder(folder, shuffle) {
        playAll(VideoLibrary.videoPathsIn(folder), VideoLibrary.folderName(folder), shuffle);
    }

    // Esc, Alt+Left or the mouse's back button: out of the watch page (or full screen), then
    // back a page. A page can use it first (Search clears what's typed).
    function goBack() {
        if (watch.mode === "full") {
            watch.exitFull();
            return;
        }
        if (watch.mode === "watch") {
            watch.minimize();
            return;
        }
        const page = contentStack.currentItem;
        if (page && typeof page.handleBack === "function" && page.handleBack())
            return;
        if (contentStack.depth > 1)
            contentStack.pop();
    }

    // A video opened from Explorer while Vessel was already open (see main.cpp): it plays
    // here, and the window comes to the front.
    function openFromOutside(path) {
        if (visibility === Window.Minimized)
            showNormal();
        raise();
        requestActivate();
        if (path)
            play(path);
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
        if (rail.currentIndex === 4)
            scope = VideoLibrary.driveOf(current.folder ?? "") || "drives";
        else if (rail.currentIndex === 5)
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

    // 0 Home, 1 Videos, 2 Library, 3 Playlists, 4 External, 5 Phone, 6 Settings, as in the rail.
    function showTab(index) {
        rail.currentIndex = index;
        contentStack.replace(null, [homePage, videosPage, libraryPage, playlistsPage, externalPage, phonePage,
                                    settingsPage][index]);
    }

    function showLibrary() {
        showTab(2);
    }

    // A drive's (or phone's) videos, from the "connected" notice, or one of its folders.
    function openDrive(path) {
        if (watch.mode === "watch" || watch.mode === "full")
            watch.minimize();
        showTab(VideoLibrary.isPhone(path) ? 5 : 4);
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

    function openFileDialog() {
        fileDialog.open();
    }


    function openHistory() {
        if (watch.mode === "watch" || watch.mode === "full")
            watch.minimize();
        contentStack.push(historyPage);
    }

    // --- A video's menu (right-click), and what's in it ------------------------------
    function showVideoMenu(path) {
        videoMenu.path = "";   // read afresh, even for the same video (it may have been marked since)
        videoMenu.path = path;
        videoMenu.popup();
    }

    // Changes to the history say what they did, and can be undone from the notice.
    function changeHistory(path, watched, message) {
        const before = VideoLibrary.historyEntry(path);
        VideoLibrary.setWatched(path, watched);
        toast(message, { icon: watched ? "check" : "history", actionText: qsTr("Undo"),
                         action: () => VideoLibrary.restoreHistory(path, before), duration: 6000 });
    }

    function setWatched(path, watched) {
        changeHistory(path, watched, watched ? qsTr("Marked as watched") : qsTr("Marked as unwatched"));
    }

    // Forgets how far it got: it leaves the history and Continue watching.
    function removeFromHistory(path) {
        changeHistory(path, false, qsTr("Removed from history"));
    }

    // --- Playlists ---------------------------------------------------------------------
    // "Save to playlist", for a video anywhere: its menu, or the watch page.
    function saveToPlaylist(path) {
        saveDialog.path = path;
        saveDialog.open();
    }

    function newPlaylist() {
        playlistNameDialog.playlistId = "";
        playlistNameDialog.open();
    }

    function renamePlaylist(id) {
        playlistNameDialog.playlistId = id;
        playlistNameDialog.open();
    }

    // Asks first (deletePlaylistDialog).
    function deletePlaylist(id) {
        deletePlaylistDialog.playlistId = id;
        deletePlaylistDialog.open();
    }

    function openPlaylist(id) {
        if (watch.mode === "watch" || watch.mode === "full")
            watch.minimize();
        contentStack.push(playlistPage, { playlistId: id });
    }

    // From the top, shuffled, or (`start`) from one of its videos on.
    function playPlaylist(id, shuffle, start) {
        playAll(VideoLibrary.playlistPaths(id), VideoLibrary.playlistName(id), shuffle, start);
    }

    function removeFromPlaylist(id, path) {
        const at = VideoLibrary.removeFromPlaylist(id, path);
        if (at < 0)
            return;
        toast(qsTr("Removed from %1").arg(VideoLibrary.playlistName(id)),
              { icon: "playlist", actionText: qsTr("Undo"), action: () => VideoLibrary.addToPlaylist(id, path, at),
                duration: 6000 });
    }

    function showPlaylistMenu(id) {
        playlistMenu.playlistId = id;
        playlistMenu.popup();
    }

    // --- A folder's (or drive's) menu (right-click) --------------------------------------
    function showFolderMenu(folder) {
        folderMenu.folder = "";   // read afresh
        folderMenu.folder = folder;
        folderMenu.popup();
    }

    // "Eject": the player lets go of a video on it first, or Windows would refuse.
    function ejectDrive(path) {
        const name = VideoLibrary.folderName(path);
        const eject = () => {
            toast(qsTr("Ejecting %1…").arg(name), { busy: true });
            VideoLibrary.ejectDrive(path);
        };
        if (watch.mode !== "closed" && VideoLibrary.driveOf(watch.path) === path) {
            watch.close();
            releaseTimer.action = eject;
            releaseTimer.restart();
        } else {
            eject();
        }
    }

    // Runs action(wasOpen) once the player has let go of `path`, if it's the video that's
    // open: the file can't be renamed, moved or deleted while it's being read.
    function withVideoReleased(path, action) {
        if (watch.mode === "closed" || watch.path !== path) {
            action(false);
            return;
        }
        watch.close();   // saves its place first
        releaseTimer.action = action;
        releaseTimer.restart();
    }

    Timer {
        id: releaseTimer
        property var action: null
        interval: 500   // the player closes the file just after stopping
        onTriggered: action(true)
    }

    function renameVideo(path) {
        renameDialog.path = path;
        renameDialog.open();
    }

    function finishRename(path, name) {
        withVideoReleased(path, wasOpen => {
            const result = VideoLibrary.renameVideo(path, name);
            if (result.error) {
                toast(result.error, { icon: "edit" });
                if (wasOpen)
                    play(path);
                return;
            }
            toast(qsTr("Renamed to %1").arg(result.path.split("/").pop()), { icon: "edit" });
            if (wasOpen)
                play(result.path);   // carries on where it was
        });
    }

    // The video being moved, while it was open in the player: it reopens from its new place.
    property string reopenAfterMove

    function moveVideo(path) {
        moveDialog.path = path;
        moveDialog.currentFolder = VideoLibrary.fileUrl(VideoLibrary.folderOf(path));
        moveDialog.open();
    }

    // Asks first (deleteDialog), then finishDelete.
    function deleteVideo(path) {
        deleteDialog.path = path;
        deleteDialog.open();
    }

    function finishDelete(path) {
        const name = path.split("/").pop();
        withVideoReleased(path, wasOpen => {
            const error = VideoLibrary.deleteVideo(path);
            if (error) {
                toast(error, { icon: "delete" });
                if (wasOpen)
                    play(path);
                return;
            }
            toast(qsTr("Moved %1 to the Recycle Bin").arg(name), { icon: "delete" });
        });
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
    title: watch.mode !== "closed" && watch.title ? watch.title + " - Vessel" : "Vessel"
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
        toolTipBase: Theme.control
        toolTipText: Theme.textPrimary
        light: Theme.controlHover
        midlight: Theme.control
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
        property string subtitleSize: "medium"
        property string subtitleColor: "white"
        property string subtitleBackground: "outline"
        // A JSON list (QSettings would turn a one-item list into a plain string).
        property string recentSearches: "[]"
    }

    FileDialog {
        id: fileDialog
        title: qsTr("Open a video")
        currentFolder: StandardPaths.writableLocation(StandardPaths.MoviesLocation)
        nameFilters: [
            qsTr("Videos (%1)").arg(VideoLibrary.videoNameFilter()),
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

    // Back. Esc on the watch page and in full screen is the player's own (see WatchView).
    Shortcut {
        sequences: ["Esc"]
        enabled: watch.mode === "mini" || watch.mode === "closed"
        onActivated: window.goBack()
    }
    Shortcut {
        sequences: ["Alt+Left"]
        onActivated: window.goBack()
    }
    // The mouse's back button, anywhere a click isn't taken (the player has its own).
    TapHandler {
        acceptedButtons: Qt.BackButton
        onTapped: window.goBack()
    }

    // The library: tabs on the left, pages on the right.
    RowLayout {
        anchors.fill: parent
        spacing: 0

        NavRail {
            id: rail
            Layout.fillHeight: true
            onTabClicked: index => {
                // From the watch page: the video carries on in the mini player.
                if (watch.mode === "watch")
                    watch.minimize();
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
        id: videosPage
        FolderPage {
            tab: "videos"
            folder: VideoLibrary.videosFolder
        }
    }
    Component {
        id: libraryPage
        FolderPage {
            tab: "library"
        }
    }
    Component {
        id: playlistsPage
        PlaylistsPage {}
    }
    Component {
        id: playlistPage
        PlaylistPage {}
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
    Component {
        id: historyPage
        HistoryPage {}
    }

    // Files dropped on the window: a video plays; subtitle files go with the video that's
    // open (shown on it while they're dragged over).
    DropArea {
        function subtitleFiles(urls) {
            return Array.from(urls).map(url => VideoLibrary.localPath(url))
                        .filter(file => VideoLibrary.isSubtitleFile(file));
        }

        anchors.fill: parent
        onEntered: drag => watch.subtitleDragging = watch.mode !== "closed" && drag.hasUrls
                                                    && subtitleFiles(drag.urls).length > 0
        onExited: watch.subtitleDragging = false
        onDropped: drop => {
            watch.subtitleDragging = false;
            if (!drop.hasUrls)
                return;
            const subtitles = subtitleFiles(drop.urls);
            if (subtitles.length > 0) {
                if (watch.mode === "closed")
                    window.toast(qsTr("Play a video first, then drop its subtitles on it"), { icon: "subtitles" });
                else
                    watch.addSubtitleFiles(subtitles);
                return;
            }
            window.play(VideoLibrary.localPath(drop.urls[0]));
        }
    }

    // The keyboard's media keys (even with another app in front) and Windows' media flyout.
    MediaControls {
        appWindow: window
        active: watch.mode !== "closed"
        playing: watch.playing
        title: watch.title
        subtitle: watch.details.folderLabel ?? ""
        thumbnail: watch.path !== "" && VideoLibrary.count >= 0 ? VideoLibrary.thumbnailFor(watch.path) : ""
        canGoNext: watch.nextPath !== ""
        canGoPrevious: watch.previousPath !== ""
        onPlayRequested: watch.play()
        onPauseRequested: watch.pause()
        onNextRequested: watch.playNext()
        onPreviousRequested: watch.playPrevious()
    }

    // The player, over the pages; beside the tabs, which stay in reach (full screen covers them).
    WatchView {
        id: watch
        anchors.fill: parent
        anchors.leftMargin: mode === "full" ? 0 : rail.width
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
        border.color: Theme.border
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

        function onDriveRemoved(path, name, ejected) {
            // Back out of the drive's folders, which are now empty.
            while (contentStack.depth > 1 && String(contentStack.currentItem.folder ?? "").startsWith(path))
                contentStack.pop(StackView.Immediate);
            const phone = VideoLibrary.isPhone(path);
            window.toast(ejected ? qsTr("%1 can be unplugged now").arg(name)
                         : phone ? qsTr("%1 disconnected").arg(name) : qsTr("%1 removed").arg(name),
                         { icon: phone ? "smartphone" : ejected ? "eject" : "usb" });
        }

        function onDriveEjectFailed(path, name, error) {
            window.toast(qsTr("Couldn't eject %1. %2").arg(name).arg(error), { icon: "eject", duration: 8000 });
        }

        function onRescanFinished(count, added, removed) {
            const videos = count === 1 ? qsTr("1 video") : qsTr("%1 videos").arg(count);
            const changes = [added > 0 ? qsTr("%1 new").arg(added) : "",
                             removed > 0 ? qsTr("%1 no longer found").arg(removed) : ""].filter(part => part);
            window.toast(changes.length > 0 ? qsTr("Library updated  •  %1  •  %2").arg(videos).arg(changes.join(", "))
                                            : qsTr("Library is up to date  •  %1").arg(videos));
        }

        function onPhoneFileShown(path, shown) {
            if (shown)
                window.toast(qsTr("Opened in File Explorer"), { icon: "folder_open", duration: 2500 });
            else
                window.toast(qsTr("Couldn't find this video in File Explorer. Is the phone still unlocked?"),
                             { icon: "smartphone" });
        }

    }

    // "Close Vessel?" while a video is playing.
    AppDialog {
        id: closeDialog
        title: qsTr("Close Vessel?")
        text: qsTr("A video is still playing. Your place will be saved, so you can pick up where you left off.")
        cancelText: qsTr("Keep watching")
        confirmText: qsTr("Close")
        onConfirmed: {
            window.closeConfirmed = true;
            window.close();
        }
    }

    component MenuLine: MenuSeparator {
        contentItem: Rectangle {
            implicitHeight: 1
            color: Theme.border
        }
    }

    // --- A video's right-click menu ---------------------------------------------------
    AppMenu {
        id: videoMenu

        property string path
        readonly property var details: path !== "" ? VideoLibrary.detailsOf(path) : ({})
        readonly property bool manageable: details.canManage ?? false

        width: 230

        AppMenuItem {
            text: qsTr("Play")
            onTriggered: window.play(videoMenu.path)
        }
        AppMenuItem {
            text: qsTr("Save to playlist…")
            onTriggered: window.saveToPlaylist(videoMenu.path)
        }
        AppMenuItem {
            text: Qt.platform.os === "windows" ? qsTr("Show in File Explorer") : qsTr("Show in folder")
            onTriggered: {
                if (videoMenu.details.phone)
                    window.toast(qsTr("Finding %1 in File Explorer…").arg(videoMenu.details.fileName), { busy: true });
                VideoLibrary.showInFileManager(videoMenu.path);
            }
        }
        MenuLine {}
        AppMenuItem {
            text: videoMenu.details.watched ? qsTr("Mark as unwatched") : qsTr("Mark as watched")
            onTriggered: window.setWatched(videoMenu.path, !videoMenu.details.watched)
        }
        AppMenuItem {
            // A started video can be taken out of the history (and Continue watching).
            visible: (videoMenu.details.inHistory ?? false) && !videoMenu.details.watched
            height: visible ? implicitHeight : 0
            text: qsTr("Remove from history")
            onTriggered: window.removeFromHistory(videoMenu.path)
        }
        MenuLine {
            visible: videoMenu.manageable
            height: visible ? implicitHeight : 0
        }
        AppMenuItem {
            visible: videoMenu.manageable
            height: visible ? implicitHeight : 0
            text: qsTr("Rename…")
            onTriggered: window.renameVideo(videoMenu.path)
        }
        AppMenuItem {
            visible: videoMenu.manageable
            height: visible ? implicitHeight : 0
            text: qsTr("Move to…")
            onTriggered: window.moveVideo(videoMenu.path)
        }
        AppMenuItem {
            visible: videoMenu.manageable
            height: visible ? implicitHeight : 0
            text: qsTr("Delete…")
            onTriggered: window.deleteVideo(videoMenu.path)
        }
    }

    // --- A folder's (or drive's, or phone's) right-click menu -------------------------
    AppMenu {
        id: folderMenu

        property string folder
        readonly property bool phone: VideoLibrary.isPhone(folder)
        readonly property bool hasVideos: folder !== "" && VideoLibrary.count >= 0 && VideoLibrary.videoCountIn(folder) > 0
        // A connected drive's own card (not a phone): it can be ejected.
        readonly property bool drive: Qt.platform.os === "windows" && VideoLibrary.drives.some(d => d.path === folder)

        width: 230

        component FolderMenuItem: AppMenuItem {
            height: visible ? implicitHeight : 0
        }

        FolderMenuItem {
            text: qsTr("Open")
            onTriggered: window.openFolder(folderMenu.folder)
        }
        FolderMenuItem {
            enabled: folderMenu.hasVideos
            text: qsTr("Play all")
            onTriggered: window.playFolder(folderMenu.folder, false)
        }
        FolderMenuItem {
            enabled: folderMenu.hasVideos
            text: qsTr("Shuffle")
            onTriggered: window.playFolder(folderMenu.folder, true)
        }
        FolderMenuItem {
            visible: !folderMenu.phone
            text: Qt.platform.os === "windows" ? qsTr("Show in File Explorer") : qsTr("Show in folder")
            onTriggered: VideoLibrary.showInFileManager(folderMenu.folder)
        }
        MenuLine {
            visible: folderMenu.drive
            height: visible ? implicitHeight : 0
        }
        FolderMenuItem {
            visible: folderMenu.drive
            text: qsTr("Eject")
            onTriggered: window.ejectDrive(folderMenu.folder)
        }
    }

    // --- A playlist's menu (right-click on its card, or More on its page) ---------------
    AppMenu {
        id: playlistMenu

        property string playlistId
        readonly property bool hasVideos: playlistId !== "" && VideoLibrary.playlistRevision >= 0 && VideoLibrary.count >= 0
                                          && VideoLibrary.playlistPaths(playlistId).length > 0

        width: 230

        AppMenuItem {
            // Not on its own page, where it's open already.
            visible: contentStack.currentItem?.playlistId !== playlistMenu.playlistId
            height: visible ? implicitHeight : 0
            text: qsTr("Open")
            onTriggered: window.openPlaylist(playlistMenu.playlistId)
        }
        AppMenuItem {
            enabled: playlistMenu.hasVideos
            text: qsTr("Play all")
            onTriggered: window.playPlaylist(playlistMenu.playlistId, false)
        }
        AppMenuItem {
            enabled: playlistMenu.hasVideos
            text: qsTr("Shuffle")
            onTriggered: window.playPlaylist(playlistMenu.playlistId, true)
        }
        MenuLine {}
        AppMenuItem {
            text: qsTr("Rename…")
            onTriggered: window.renamePlaylist(playlistMenu.playlistId)
        }
        AppMenuItem {
            text: qsTr("Delete playlist…")
            onTriggered: window.deletePlaylist(playlistMenu.playlistId)
        }
    }

    SaveToPlaylist {
        id: saveDialog
        app: window
    }

    // A new playlist's name (playlistId ""), or a new name for one.
    AppDialog {
        id: playlistNameDialog

        property string playlistId

        title: playlistId === "" ? qsTr("New playlist") : qsTr("Rename playlist")
        confirmText: playlistId === "" ? qsTr("Create") : qsTr("Rename")
        confirmEnabled: playlistNameField.text.trim() !== ""
        onOpened: {
            playlistNameField.text = playlistId === "" ? "" : VideoLibrary.playlistName(playlistId);
            playlistNameField.selectAll();
            playlistNameField.forceActiveFocus();
        }
        onConfirmed: {
            const name = playlistNameField.text.trim();
            if (name === "")
                return;
            if (playlistId === "")
                window.openPlaylist(VideoLibrary.createPlaylist(name, []));
            else
                VideoLibrary.renamePlaylist(playlistId, name);
        }

        AppTextField {
            id: playlistNameField
            Layout.fillWidth: true
            placeholderText: qsTr("Playlist name")
            onAccepted: if (playlistNameDialog.confirmEnabled) playlistNameDialog.confirm()
        }
    }

    AppDialog {
        id: deletePlaylistDialog

        property string playlistId

        title: qsTr("Delete this playlist?")
        text: qsTr("%1 will be deleted. The videos in it stay where they are.")
              .arg(playlistId !== "" ? VideoLibrary.playlistName(playlistId) : "")
        confirmText: qsTr("Delete")
        onConfirmed: {
            const name = VideoLibrary.playlistName(playlistId);
            // Out of its page first, if it's showing.
            while (contentStack.depth > 1 && contentStack.currentItem.playlistId === playlistId)
                contentStack.pop(StackView.Immediate);
            VideoLibrary.deletePlaylist(playlistId);
            window.toast(qsTr("Deleted %1").arg(name), { icon: "delete" });
        }
    }

    // --- Rename ---------------------------------------------------------------------------
    AppDialog {
        id: renameDialog

        property string path

        title: qsTr("Rename")
        confirmText: qsTr("Rename")
        preferredWidth: 480
        onOpened: {
            nameField.text = path.split("/").pop();
            // The name without its extension is selected, ready to type over.
            const dot = nameField.text.lastIndexOf(".");
            nameField.select(0, dot > 0 ? dot : nameField.text.length);
            nameField.forceActiveFocus();
        }
        onConfirmed: {
            if (nameField.text.trim() !== "" && nameField.text !== path.split("/").pop())
                window.finishRename(path, nameField.text);
        }

        AppTextField {
            id: nameField
            Layout.fillWidth: true
            onAccepted: renameDialog.confirm()
        }
        Text {
            Layout.fillWidth: true
            text: qsTr("Subtitle files named after it are renamed too, and it keeps its place in your history.")
            color: Theme.textSecondary
            font.pixelSize: 13
            wrapMode: Text.Wrap
        }
    }

    // --- Delete ---------------------------------------------------------------------------
    AppDialog {
        id: deleteDialog

        property string path

        title: qsTr("Delete this video?")
        text: qsTr("%1 will be moved to the Recycle Bin, so you can still restore it from there.")
              .arg(path.split("/").pop())
        confirmText: qsTr("Delete")
        onConfirmed: window.finishDelete(path)
    }

    // --- Move ---------------------------------------------------------------------------
    FolderDialog {
        id: moveDialog
        property string path
        title: qsTr("Move to…")
        onAccepted: {
            const path = moveDialog.path;
            const folder = VideoLibrary.localPath(selectedFolder);
            window.withVideoReleased(path, wasOpen => {
                window.reopenAfterMove = wasOpen ? path : "";
                window.toast(qsTr("Moving %1…").arg(path.split("/").pop()), { busy: true });
                VideoLibrary.moveVideo(path, folder);
            });
        }
    }

    Connections {
        target: VideoLibrary
        function onVideoMoved(oldPath, newPath, error) {
            if (error)
                window.toast(error, { icon: "move" });
            else
                window.toast(qsTr("Moved to %1").arg(VideoLibrary.folderName(newPath.substring(0, newPath.lastIndexOf("/")))),
                             { icon: "move" });
            if (window.reopenAfterMove === oldPath) {
                window.reopenAfterMove = "";
                window.play(error ? oldPath : newPath);
            }
        }
    }

    Component.onCompleted: {
        if (startFile)
            play(startFile);
    }
}
