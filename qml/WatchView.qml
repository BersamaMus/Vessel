import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Dialogs
import QtQuick.Effects
import QtQuick.Layouts

// The player, laid over the whole app. One video moves smoothly between:
//  - the watch page: video, details below, the folder playlist and more videos beside it
//  - the mini player in the corner, while the rest of the app stays usable
//  - full screen
// Swipe the video sideways for full screen, down for the mini player, and the
// mini player up to bring the watch page back.
Item {
    id: watch

    property string path
    property string mode: "closed"   // "watch", "mini", "full" or "closed"
    // 0 = watch page, 1 = mini player; follows the finger while swiping.
    property real miniProgress: 0
    // 0 = watch page, 1 = full screen.
    property real fullProgress: 0
    property bool controlsShown: true
    property double lastPosition: 0
    property double lastDuration: 0

    readonly property var app: ApplicationWindow.window
    readonly property var details: path !== "" && VideoLibrary.count >= 0 ? VideoLibrary.detailsOf(path) : ({})
    readonly property string title: details.title ?? ""
    readonly property string nextPath: path !== "" && VideoLibrary.count >= 0 ? VideoLibrary.nextInFolder(path) : ""
    readonly property string previousPath: path !== "" && VideoLibrary.count >= 0 ? VideoLibrary.previousInFolder(path) : ""
    // The double-tap skip in progress: which side, and how far so far.
    property int seekSide: 0
    property int seekTotal: 0
    // Subtitle files found for this video, and the one being offered ("" for no prompt).
    property var subtitleCandidates: []
    property string subtitleOffer: ""
    // Controls showing on the video: subtitles move up out of their way.
    readonly property bool controlsOnVideo: mode !== "closed" && controlsShown && miniProgress < 0.2
    readonly property int subtitlePosition: controlsOnVideo ? Math.round(100 - 9000 / Math.max(frame.height, 300)) : 100

    onSubtitlePositionChanged: mpv.setMpvProperty("sub-pos", subtitlePosition)
    // Rebuilt when the library finishes scanning (a file can be opened before that).
    readonly property var queue: path !== "" && VideoLibrary.count >= 0 ? VideoLibrary.videosIn(VideoLibrary.folderOf(path)) : null
    readonly property var suggestions: path !== "" && VideoLibrary.count >= 0 ? VideoLibrary.suggestionsFor(path) : null

    // --- Layout ------------------------------------------------------------
    readonly property bool wide: width >= 1000
    readonly property real pad: 24
    readonly property real sideWidth: wide ? Math.min(420, Math.max(340, width * 0.3)) : 0
    readonly property real watchWidth: Math.min(width - 2 * pad - (wide ? sideWidth + pad : 0),
                                                height * (wide ? 0.66 : 0.5) * 16 / 9)
    readonly property rect watchRect: Qt.rect(pad, 16, watchWidth, watchWidth * 9 / 16)
    readonly property real miniWidth: Math.max(300, Math.min(400, width * 0.28))
    readonly property rect miniRect: Qt.rect(width - miniWidth - 20, height - miniWidth * 9 / 16 - 20,
                                             miniWidth, miniWidth * 9 / 16)
    readonly property real dragRange: Math.max(1, miniRect.y - watchRect.y)
    readonly property real pageOpacity: Math.max(0, 1 - miniProgress * 1.6) * (1 - fullProgress)

    function mix(a, b, t) { return a + (b - a) * t; }

    onQueueChanged: Qt.callLater(scrollQueueToCurrent)

    // --- Actions -----------------------------------------------------------
    function open(newPath) {
        if (!newPath)
            return;
        if (newPath === path && mode !== "closed") {
            if (mode === "mini")
                expand();
            return;
        }
        saveProgress();
        lastPosition = 0;
        lastDuration = 0;
        errorText.text = "";
        path = newPath;
        mpv.volume = app.volume;

        // Subtitle files that seem to belong to the video: offered the first time,
        // then loaded (or not) as the user chose.
        const found = VideoLibrary.subtitlesFor(newPath);
        const choice = VideoLibrary.subtitleChoice(newPath);
        subtitleCandidates = found;
        subtitleOffer = choice === undefined && found.length > 0 ? found[0] : "";
        const subtitles = choice ? [choice].concat(found.filter(file => file !== choice)) : [];
        mpv.open(newPath, VideoLibrary.resumePosition(newPath), subtitles);
        if (mode === "closed") {
            miniProgress = 0;
            fullProgress = 0;
        }
        if (mode !== "full")
            expand();
        wake();
        Qt.callLater(scrollQueueToCurrent);
    }

    function playNext() {
        if (nextPath)
            open(nextPath);
    }

    function playPrevious() {
        if (previousPath)
            open(previousPath);
    }

    // "Turn on" in the subtitle prompt: show the best match, list the others.
    function acceptSubtitles() {
        if (subtitleCandidates.length === 0)
            return;
        subtitleCandidates.forEach((file, i) => mpv.addSubtitleFile(file, i === 0));
        VideoLibrary.setSubtitleChoice(path, subtitleCandidates[0]);
        subtitleOffer = "";
    }

    function declineSubtitles() {
        VideoLibrary.setSubtitleChoice(path, "");
        subtitleOffer = "";
    }

    // C: subtitles off, or back on (the first track, or the one on offer).
    function toggleSubtitles() {
        if (mpv.subtitleId > 0)
            chooseSubtitle(null);
        else if (mpv.subtitleTracks.length > 0)
            chooseSubtitle(mpv.subtitleTracks[0]);
        else
            acceptSubtitles();   // load what was found, even if declined before
        wake();
    }

    // From the subtitles menu: null for off. Remembered for next time.
    function chooseSubtitle(track) {
        subtitleOffer = "";
        if (!track) {
            mpv.setSubtitleTrack(0);
            VideoLibrary.setSubtitleChoice(path, "");
            return;
        }
        mpv.setSubtitleTrack(track.id);
        if (track.file)
            VideoLibrary.setSubtitleChoice(path, track.file);
    }

    // -1 for the left third of the video, 1 for the right third, 0 for the middle.
    function sideAt(x) {
        return x < frame.width / 3 ? -1 : x > frame.width * 2 / 3 ? 1 : 0;
    }

    function tapSeek(side) {
        if (side !== seekSide)
            seekTotal = 0;
        seekSide = side;
        seekTotal += app.skipSeconds;
        mpv.seekRelative(side * app.skipSeconds);
        seekBurst.restart();
    }

    function minimize() {
        if (mode === "full")
            exitFull();
        mode = "mini";
        animate(miniAnim, 1);
    }

    function expand() {
        mode = "watch";
        animate(miniAnim, 0);
        wake();
    }

    function enterFull() {
        mode = "full";
        miniProgress = 0;
        animate(fullAnim, 1);
        if (!app.isFullScreen)
            app.toggleFullScreen();
        wake();
    }

    function exitFull() {
        mode = "watch";
        animate(fullAnim, 0);
        if (app.isFullScreen)
            app.toggleFullScreen();
        wake();
    }

    function toggleFull() {
        mode === "full" ? exitFull() : enterFull();
    }

    function close() {
        saveProgress();
        if (app.isFullScreen)
            app.toggleFullScreen();
        mpv.stop();
        mode = "closed";
    }

    function animate(anim, to) {
        anim.stop();
        anim.to = to;
        anim.start();
    }

    function saveProgress() {
        if (path !== "" && lastDuration > 0)
            VideoLibrary.saveProgress(path, lastPosition, lastDuration);
    }

    function wake() {
        controlsShown = true;
        hideTimer.restart();
    }

    function setVolume(value) {
        mpv.volume = value;
        app.volume = value;
        wake();
    }

    function scrollQueueToCurrent() {
        const index = queue ? queue.indexOf(path) : -1;
        if (index >= 0)
            queueList.positionViewAtIndex(index, ListView.Center);
    }

    // Called when a swipe on the video ends.
    function finishSwipe(axis, delta, velocity, startMini) {
        if (axis === "h") {
            if (Math.abs(delta.x) < 60)
                return;
            mode === "mini" ? close() : toggleFull();
        } else if (axis === "v") {
            if (mode === "full") {
                if (delta.y > 60)
                    exitFull();
            } else if (startMini < 0.5) {
                (miniProgress > 0.3 || velocity.y > 800) ? minimize() : expand();
            } else if (delta.y > 50) {
                close();
            } else {
                (miniProgress < 0.8 || velocity.y < -800) ? expand() : minimize();
            }
        }
    }

    function formatTime(seconds) {
        const total = Math.max(0, Math.floor(seconds));
        const h = Math.floor(total / 3600), m = Math.floor(total / 60) % 60, s = total % 60;
        const pad = n => (n < 10 ? "0" : "") + n;
        return h > 0 ? h + ":" + pad(m) + ":" + pad(s) : m + ":" + pad(s);
    }

    opacity: mode === "closed" ? 0 : 1
    visible: opacity > 0
    Behavior on opacity { NumberAnimation { duration: 200 } }

    NumberAnimation { id: miniAnim; target: watch; property: "miniProgress"; duration: 320; easing.type: Easing.OutCubic }
    NumberAnimation { id: fullAnim; target: watch; property: "fullProgress"; duration: 250; easing.type: Easing.OutCubic }

    Timer {
        id: clickTimer
        interval: 220
        onTriggered: mpv.togglePause()
    }

    Timer {
        id: hideTimer
        interval: 2500
        onTriggered: {
            if (!mpv.paused && !seekBar.pressed && !controlsHover.hovered && !subtitleMenu.opened)
                watch.controlsShown = false;
        }
    }

    // The mouse left the video: hide the controls almost at once.
    Timer {
        id: leaveTimer
        interval: 300
        onTriggered: {
            if (!mpv.paused && !seekBar.pressed && !subtitleMenu.opened)
                watch.controlsShown = false;
        }
    }

    // Double-tap seeking, as on YouTube: double-tap a side to skip (10 seconds by default),
    // and keep tapping there to skip more.
    Timer {
        id: seekBurst
        interval: 700
        onTriggered: {
            watch.seekSide = 0;
            watch.seekTotal = 0;
        }
    }

    // Saves now and then, in case the app is closed unexpectedly.
    Timer {
        interval: 5000
        repeat: true
        running: mpv.loaded && !mpv.paused
        onTriggered: watch.saveProgress()
    }

    Connections {
        target: watch.app
        function onClosing() { watch.saveProgress() }
        // Left full screen some other way (e.g. a window manager key).
        function onVisibilityChanged() {
            if (!watch.app.isFullScreen && watch.mode === "full") {
                watch.mode = "watch";
                watch.animate(fullAnim, 0);
            }
        }
    }

    // Unplugged mid-video: keep the place (the drive's videos are still known
    // at this point), then stop instead of stalling on a missing file.
    Connections {
        target: VideoLibrary
        function onDriveRemoved(drivePath) {
            if (watch.mode === "closed" || !watch.path.startsWith(drivePath))
                return;
            watch.saveProgress();
            watch.lastDuration = 0;   // saved; later saves would miss the drive's key
            mpv.stop();
            if (watch.app.isFullScreen)
                watch.exitFull();
            errorText.text = qsTr("The drive was removed. Plug it back in to carry on from here.");
        }
    }

    // --- The watch page ----------------------------------------------------
    Rectangle {
        anchors.fill: parent
        color: Theme.background
        opacity: 1 - watch.miniProgress
        visible: opacity > 0
    }

    // Keeps clicks and scrolling from reaching the library underneath.
    MouseArea {
        anchors.fill: parent
        visible: watch.miniProgress < 0.5
        hoverEnabled: true
        acceptedButtons: Qt.AllButtons
        onWheel: wheel => wheel.accepted = true
    }

    Item {
        id: page
        anchors.fill: parent
        opacity: watch.pageOpacity
        visible: opacity > 0.01
        transform: Translate { y: watch.miniProgress * 80 }

        ColumnLayout {
            id: info
            x: watch.watchRect.x
            y: watch.watchRect.y + watch.watchRect.height + 16
            width: watch.watchRect.width
            spacing: 6

            Text {
                Layout.fillWidth: true
                text: watch.title
                color: Theme.textPrimary
                font.pixelSize: 22
                font.weight: Font.Bold
                font.letterSpacing: -0.3
                wrapMode: Text.Wrap
                maximumLineCount: 2
                elide: Text.ElideRight
            }

            Text {
                Layout.fillWidth: true
                text: [watch.details.folderLabel, watch.formatTime(mpv.duration),
                       watch.details.addedText ? qsTr("Added %1").arg(watch.details.addedText) : ""]
                      .filter(part => part).join("  •  ")
                color: Theme.textSecondary
                font.pixelSize: 14
                elide: Text.ElideRight
            }

            Rectangle {
                Layout.fillWidth: true
                Layout.topMargin: 8
                implicitHeight: fileInfo.implicitHeight + 24
                radius: Theme.radiusArt
                color: Theme.surfaceHigh

                Column {
                    id: fileInfo
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.top: parent.top
                    anchors.margins: 12
                    spacing: 4

                    Text {
                        width: parent.width
                        text: watch.details.fileName ?? ""
                        color: Theme.textPrimary
                        font.pixelSize: 14
                        font.weight: Font.DemiBold
                        elide: Text.ElideMiddle
                    }
                    Text {
                        width: parent.width
                        text: [mpv.videoWidth > 0 ? mpv.videoWidth + " × " + mpv.videoHeight : "",
                               mpv.videoFormat.toUpperCase(), watch.details.sizeText]
                              .filter(part => part).join("  •  ")
                        color: Theme.textSecondary
                        font.pixelSize: 13
                    }

                    // Opens the folder with this file selected.
                    AbstractButton {
                        id: locationLink
                        width: parent.width
                        topPadding: 4
                        hoverEnabled: true
                        focusPolicy: Qt.NoFocus
                        visible: watch.path !== ""
                        onClicked: VideoLibrary.showInFileManager(watch.path)

                        ToolTip.visible: hovered
                        ToolTip.delay: 600
                        ToolTip.text: Qt.platform.os === "windows" ? qsTr("Show in File Explorer") : qsTr("Show in folder")
                        HoverHandler { cursorShape: Qt.PointingHandCursor }

                        background: null
                        contentItem: RowLayout {
                            spacing: 6
                            Icon {
                                name: "folder_open"
                                size: 16
                                color: Theme.accent
                            }
                            Text {
                                text: qsTr("File location:")
                                color: Theme.textSecondary
                                font.pixelSize: 13
                            }
                            Text {
                                Layout.fillWidth: true
                                text: Qt.platform.os === "windows" ? (watch.details.folder ?? "").replace(/\//g, "\\")
                                                                   : (watch.details.folder ?? "")
                                color: Theme.accent
                                font.pixelSize: 13
                                font.underline: locationLink.hovered
                                elide: Text.ElideMiddle
                            }
                        }
                    }
                }
            }
        }

        // Up next: the rest of the folder, then other videos.
        ScrollView {
            id: side
            x: watch.wide ? watch.watchRect.x + watch.watchRect.width + watch.pad : watch.pad
            y: watch.wide ? watch.watchRect.y : info.y + info.height + 16
            width: watch.wide ? watch.sideWidth : watch.width - 2 * watch.pad
            height: watch.height - y
            contentWidth: availableWidth
            clip: true

            ColumnLayout {
                width: side.availableWidth
                spacing: 0

                Rectangle {
                    Layout.fillWidth: true
                    visible: watch.queue !== null && watch.queue.count > 1
                    implicitHeight: queueHeader.implicitHeight + queueList.height + 20
                    radius: Theme.radiusArt
                    color: Theme.surface
                    border.color: "#26FFFFFF"

                    Column {
                        id: queueHeader
                        x: 14
                        y: 12
                        width: parent.width - 28
                        spacing: 2

                        Text {
                            width: parent.width
                            text: VideoLibrary.folderName(watch.details.folder ?? "")
                            color: Theme.textPrimary
                            font.pixelSize: 16
                            font.weight: Font.Bold
                            elide: Text.ElideRight
                        }
                        Text {
                            width: parent.width
                            text: watch.queue ? qsTr("%1 / %2").arg(watch.queue.indexOf(watch.path) + 1).arg(watch.queue.count) : ""
                            color: Theme.textSecondary
                            font.pixelSize: 13
                        }
                    }

                    ListView {
                        id: queueList
                        anchors.top: queueHeader.bottom
                        anchors.topMargin: 6
                        x: 4
                        width: parent.width - 8
                        height: Math.min(contentHeight, 380)
                        clip: true
                        model: watch.queue
                        boundsBehavior: Flickable.StopAtBounds
                        delegate: PickRow {
                            required property var model
                            width: queueList.width
                            thumbWidth: 112
                            titleLines: 2
                            current: model.path === watch.path
                            title: model.title
                            subtitle: model.durationText
                            thumbnail: model.thumbnail
                            durationText: model.durationText
                            progress: current ? 0 : model.progress
                            onClicked: watch.open(model.path)
                        }
                    }
                }

                Text {
                    Layout.topMargin: watch.queue !== null && watch.queue.count > 1 ? 20 : 0
                    Layout.bottomMargin: 6
                    Layout.leftMargin: 8
                    visible: watch.suggestions !== null && watch.suggestions.count > 0
                    text: watch.queue === null || watch.queue.count <= 1 ? qsTr("Up next")
                        : watch.details.external ? qsTr("More videos from this drive") : qsTr("More videos")
                    color: Theme.textPrimary
                    font.pixelSize: 16
                    font.weight: Font.Bold
                }

                Repeater {
                    model: watch.suggestions
                    delegate: PickRow {
                        required property var model
                        Layout.fillWidth: true
                        thumbWidth: 160
                        titleLines: 2
                        title: model.title
                        subtitle: model.subtitle
                        thumbnail: model.thumbnail
                        durationText: model.durationText
                        progress: model.progress
                        onClicked: watch.open(model.path)
                    }
                }

                Item { implicitHeight: 24 }
            }
        }
    }

    // --- The video -----------------------------------------------------------
    RectangularShadow {
        x: frame.x
        y: frame.y
        width: frame.width
        height: frame.height
        radius: 10
        blur: 32
        offset: Qt.vector2d(0, 8)
        color: "#E6000000"
        opacity: watch.miniProgress
        visible: opacity > 0
    }

    Item {
        id: frame
        x: watch.mix(watch.mix(watch.watchRect.x, 0, watch.fullProgress), watch.miniRect.x, watch.miniProgress)
        y: watch.mix(watch.mix(watch.watchRect.y, 0, watch.fullProgress), watch.miniRect.y, watch.miniProgress)
        width: watch.mix(watch.mix(watch.watchRect.width, watch.width, watch.fullProgress), watch.miniRect.width, watch.miniProgress)
        height: watch.mix(watch.mix(watch.watchRect.height, watch.height, watch.fullProgress), watch.miniRect.height, watch.miniProgress)
        clip: true

        Rectangle {
            anchors.fill: parent
            color: "black"
        }

        MpvItem {
            id: mpv
            anchors.fill: parent

            onPositionChanged: {
                if (loaded && duration > 0) {
                    watch.lastPosition = position;
                    watch.lastDuration = duration;
                }
            }
            onPausedChanged: {
                watch.saveProgress();
                watch.wake();
            }
            onEndedChanged: if (ended) {
                watch.saveProgress();
                watch.wake();
            }
            onErrorOccurred: message => errorText.text = message
        }

        Text {
            id: errorText
            anchors.centerIn: parent
            width: parent.width * 0.8
            visible: text !== ""
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.Wrap
            color: Theme.textSecondary
            font.pixelSize: 15
        }

        // Taps and swipes. Sits under the buttons so they keep their clicks.
        Item {
            anchors.fill: parent

            // Click: pause. Double-click the middle: full screen. Double-click a side: skip 10 seconds.
            TapHandler {
                onTapped: eventPoint => {
                    if (watch.mode === "mini") {
                        watch.expand();
                        return;
                    }
                    // More taps on the same side, right after a double-tap, keep skipping.
                    const side = watch.sideAt(eventPoint.position.x);
                    if (seekBurst.running && side !== 0 && side === watch.seekSide) {
                        clickTimer.stop();
                        watch.tapSeek(side);
                        return;
                    }
                    clickTimer.restart();
                }
                onDoubleTapped: eventPoint => {
                    if (watch.mode === "mini")
                        return;
                    clickTimer.stop();
                    const side = watch.sideAt(eventPoint.position.x);
                    if (side === 0)
                        watch.toggleFull();
                    else if (!seekBurst.running)   // otherwise the tap already skipped
                        watch.tapSeek(side);
                }
            }

            DragHandler {
                id: swipe

                property string axis: ""
                property real startMini: 0
                property point delta: Qt.point(0, 0)

                target: null

                onActiveChanged: {
                    if (active) {
                        axis = "";
                        startMini = watch.miniProgress;
                        miniAnim.stop();
                        return;
                    }
                    watch.finishSwipe(axis, delta, centroid.velocity, startMini);
                    axis = "";
                }
                onCentroidChanged: {
                    if (!active)
                        return;
                    // Scene coordinates: the video moves under the finger while swiping.
                    delta = Qt.point(centroid.scenePosition.x - centroid.scenePressPosition.x,
                                     centroid.scenePosition.y - centroid.scenePressPosition.y);
                    if (axis === "" && Math.abs(delta.x) + Math.abs(delta.y) > 14)
                        axis = Math.abs(delta.x) > Math.abs(delta.y) ? "h" : "v";
                    if (axis === "v" && watch.mode !== "full")
                        watch.miniProgress = Math.max(0, Math.min(1, startMini + delta.y / watch.dragRange));
                }
            }
        }

        HoverHandler {
            id: frameHover
            cursorShape: watch.mode !== "mini" && !watch.controlsShown ? Qt.BlankCursor : Qt.ArrowCursor
            onPointChanged: watch.wake()
            onHoveredChanged: hovered ? leaveTimer.stop() : leaveTimer.restart()
        }

        // The "10 seconds" bubbles for double-tap seeking: a circle's edge
        // coming in from the side, with the running total.
        component SeekBubble: Item {
            id: bubble
            property int side: -1
            readonly property real diameter: frame.height * 1.6

            anchors.fill: parent
            opacity: watch.seekSide === side ? 1 : 0
            visible: opacity > 0
            Behavior on opacity { NumberAnimation { duration: 150 } }

            Rectangle {
                width: bubble.diameter
                height: bubble.diameter
                radius: bubble.diameter / 2
                x: bubble.side < 0 ? frame.width * 0.36 - bubble.diameter : frame.width * 0.64
                y: (frame.height - bubble.diameter) / 2
                color: "#26FFFFFF"
            }
            Column {
                x: (bubble.side < 0 ? frame.width * 0.18 : frame.width * 0.82) - width / 2
                anchors.verticalCenter: parent.verticalCenter
                spacing: 4
                Icon {
                    anchors.horizontalCenter: parent.horizontalCenter
                    name: bubble.side < 0 ? "rewind" : "forward"
                    size: 32
                }
                Text {
                    anchors.horizontalCenter: parent.horizontalCenter
                    text: qsTr("%1 seconds").arg(watch.seekTotal)
                    color: Theme.textPrimary
                    font.pixelSize: 14
                    font.weight: Font.DemiBold
                }
            }
        }

        SeekBubble { side: -1 }
        SeekBubble { side: 1 }

        // --- Watch page / full screen controls ---------------------------------
        Item {
            id: controls
            anchors.fill: parent
            opacity: watch.controlsShown && watch.miniProgress < 0.2 ? 1 : 0
            visible: opacity > 0
            Behavior on opacity { NumberAnimation { duration: 200 } }

            Rectangle {
                anchors.top: parent.top
                width: parent.width
                height: 80
                gradient: Gradient {
                    GradientStop { position: 0; color: "#A6000000" }
                    GradientStop { position: 1; color: "transparent" }
                }
            }

            RowLayout {
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.top: parent.top
                anchors.margins: 8
                spacing: 4

                IconButton {
                    iconName: "expand_more"
                    iconSize: 28
                    tip: qsTr("Mini player (I)")
                    onClicked: watch.minimize()
                }
                Text {
                    Layout.fillWidth: true
                    visible: watch.mode === "full"
                    text: watch.title
                    color: Theme.textPrimary
                    font.pixelSize: 18
                    font.weight: Font.Bold
                    elide: Text.ElideRight
                }
            }

            Row {
                anchors.centerIn: parent
                spacing: 20

                IconButton {
                    anchors.verticalCenter: parent.verticalCenter
                    iconName: "skip_previous"
                    iconSize: 30
                    width: 48
                    height: 48
                    enabled: watch.previousPath !== ""
                    tip: qsTr("Previous video (Shift+P)")
                    onClicked: watch.playPrevious()
                }

                AbstractButton {
                    id: playButton
                    anchors.verticalCenter: parent.verticalCenter
                    implicitWidth: 64
                    implicitHeight: 64
                    hoverEnabled: true
                    focusPolicy: Qt.NoFocus
                    onClicked: mpv.togglePause()

                    background: Rectangle {
                        radius: width / 2
                        color: Theme.accent
                        scale: playButton.pressed ? 0.94 : playButton.hovered ? 1.05 : 1
                        Behavior on scale { NumberAnimation { duration: 100 } }
                    }
                    contentItem: Item {
                        Icon {
                            anchors.centerIn: parent
                            name: mpv.ended ? "replay" : mpv.paused ? "play" : "pause"
                            size: 34
                            color: Theme.accentText
                        }
                    }
                }

                IconButton {
                    anchors.verticalCenter: parent.verticalCenter
                    iconName: "skip_next"
                    iconSize: 30
                    width: 48
                    height: 48
                    enabled: watch.nextPath !== ""
                    tip: qsTr("Next video (Shift+N)")
                    onClicked: watch.playNext()
                }
            }

            Item {
                id: bottomBar
                anchors.bottom: parent.bottom
                width: parent.width
                height: 96

                HoverHandler { id: controlsHover }

                Rectangle {
                    anchors.fill: parent
                    gradient: Gradient {
                        GradientStop { position: 0; color: "transparent" }
                        GradientStop { position: 1; color: "#D9000000" }
                    }
                }

                ColumnLayout {
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.bottom: parent.bottom
                    anchors.leftMargin: 16
                    anchors.rightMargin: 12
                    anchors.bottomMargin: 6
                    spacing: 0

                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 2

                        Text {
                            text: watch.formatTime(seekBar.value) + " / " + watch.formatTime(mpv.duration)
                            color: Theme.textPrimary
                            font.pixelSize: 13
                            font.features: { "tnum": 1 }
                        }
                        Item { Layout.fillWidth: true }
                        IconButton {
                            id: subtitleButton
                            iconName: "subtitles"
                            iconColor: mpv.subtitleId > 0 ? Theme.accent : Theme.textPrimary
                            tip: qsTr("Subtitles (C)")
                            onClicked: subtitleMenu.opened ? subtitleMenu.close() : subtitleMenu.open()

                            AppMenu {
                                id: subtitleMenu
                                // Opens upwards from the button, right-aligned with it.
                                x: subtitleButton.width - width
                                y: -height - 6

                                AppMenuItem {
                                    text: qsTr("Off")
                                    checkable: true
                                    checked: mpv.subtitleId === 0
                                    onTriggered: watch.chooseSubtitle(null)
                                }

                                // One item per subtitle track, inserted after "Off".
                                Instantiator {
                                    model: mpv.subtitleTracks
                                    delegate: AppMenuItem {
                                        required property var modelData
                                        text: modelData.label
                                        checkable: true
                                        checked: modelData.selected
                                        onTriggered: watch.chooseSubtitle(modelData)
                                    }
                                    onObjectAdded: (index, object) => subtitleMenu.insertItem(index + 1, object)
                                    onObjectRemoved: (index, object) => subtitleMenu.removeItem(object)
                                }

                                MenuSeparator {
                                    contentItem: Rectangle {
                                        implicitHeight: 1
                                        color: "#26FFFFFF"
                                    }
                                }
                                AppMenuItem {
                                    text: qsTr("Add subtitle file…")
                                    onTriggered: subtitleDialog.open()
                                }
                            }
                        }
                        IconButton {
                            iconName: mpv.muted || mpv.volume === 0 ? "volume_off" : "volume"
                            tip: qsTr("Mute (M)")
                            onClicked: mpv.muted = !mpv.muted
                        }
                        AppSlider {
                            id: volumeSlider
                            Layout.preferredWidth: 90
                            from: 0
                            to: 100
                            onMoved: watch.setVolume(value)

                            Binding {
                                target: volumeSlider
                                property: "value"
                                when: !volumeSlider.pressed
                                value: mpv.volume
                                restoreMode: Binding.RestoreNone
                            }
                        }
                        IconButton {
                            iconName: watch.mode === "full" ? "fullscreen_exit" : "fullscreen"
                            tip: qsTr("Full screen (F)")
                            onClicked: watch.toggleFull()
                        }
                    }

                    AppSlider {
                        id: seekBar
                        Layout.fillWidth: true
                        from: 0
                        to: Math.max(mpv.duration, 0.1)
                        onMoved: mpv.scrub(value)
                        onPressedChanged: if (!pressed) mpv.seek(value)

                        Binding {
                            target: seekBar
                            property: "value"
                            when: !seekBar.pressed
                            value: mpv.position
                            restoreMode: Binding.RestoreNone
                        }
                    }
                }
            }
        }

        // --- "Subtitles found" prompt ---------------------------------------------
        Rectangle {
            id: subtitlePrompt

            readonly property bool wanted: watch.subtitleOffer !== "" && mpv.loaded && watch.miniProgress < 0.5

            anchors.horizontalCenter: parent.horizontalCenter
            y: 56
            width: Math.min(parent.width - 32, promptRow.implicitWidth + 32)
            height: promptRow.implicitHeight + 24
            radius: Theme.radiusCard
            color: "#F21C1C1C"
            border.color: "#26FFFFFF"
            opacity: wanted ? 1 : 0
            visible: opacity > 0
            Behavior on opacity { NumberAnimation { duration: 200 } }

            // Unanswered, it goes away (and asks again next time).
            onWantedChanged: wanted ? promptTimer.restart() : promptTimer.stop()
            Timer {
                id: promptTimer
                interval: 15000
                onTriggered: watch.subtitleOffer = ""
            }

            // Clicks on the card itself shouldn't pause the video.
            MouseArea {
                anchors.fill: parent
            }

            RowLayout {
                id: promptRow
                anchors.centerIn: parent
                width: parent.width - 32
                spacing: 12

                Icon {
                    name: "subtitles"
                    color: Theme.accent
                }
                ColumnLayout {
                    Layout.fillWidth: true
                    Layout.maximumWidth: 360
                    spacing: 1
                    Text {
                        text: qsTr("Subtitles found")
                        color: Theme.textPrimary
                        font.pixelSize: 14
                        font.weight: Font.Bold
                    }
                    Text {
                        Layout.fillWidth: true
                        text: watch.subtitleOffer.split("/").pop()
                        color: Theme.textSecondary
                        font.pixelSize: 13
                        elide: Text.ElideMiddle
                    }
                }
                TextAction {
                    text: qsTr("Turn on")
                    onClicked: watch.acceptSubtitles()
                }
                AbstractButton {
                    id: declineButton
                    text: qsTr("No thanks")
                    hoverEnabled: true
                    focusPolicy: Qt.NoFocus
                    padding: 8
                    onClicked: watch.declineSubtitles()
                    HoverHandler { cursorShape: Qt.PointingHandCursor }
                    background: Rectangle {
                        radius: height / 2
                        color: declineButton.hovered ? Theme.hover : "transparent"
                    }
                    contentItem: Text {
                        text: declineButton.text
                        color: Theme.textSecondary
                        font.pixelSize: 15
                        font.weight: Font.DemiBold
                    }
                }
            }
        }

        // --- Mini player controls ---------------------------------------------
        Item {
            anchors.fill: parent
            opacity: watch.mode === "mini" && watch.miniProgress > 0.9 && frameHover.hovered ? 1 : 0
            visible: opacity > 0
            Behavior on opacity { NumberAnimation { duration: 150 } }

            Rectangle {
                anchors.fill: parent
                color: "#73000000"
            }
            IconButton {
                anchors.left: parent.left
                anchors.top: parent.top
                anchors.margins: 4
                iconName: "open_in_full"
                iconSize: 20
                tip: qsTr("Expand")
                onClicked: watch.expand()
            }
            IconButton {
                anchors.right: parent.right
                anchors.top: parent.top
                anchors.margins: 4
                iconName: "close"
                tip: qsTr("Close")
                onClicked: watch.close()
            }
            IconButton {
                anchors.centerIn: parent
                width: 56
                height: 56
                iconSize: 40
                iconName: mpv.ended ? "replay" : mpv.paused ? "play" : "pause"
                onClicked: mpv.togglePause()
            }
        }

        // The mini player's progress line.
        Rectangle {
            anchors.bottom: parent.bottom
            width: parent.width * (mpv.duration > 0 ? mpv.position / mpv.duration : 0)
            height: 3
            color: Theme.accent
            visible: watch.miniProgress > 0.9
        }
    }

    FileDialog {
        id: subtitleDialog
        title: qsTr("Add a subtitle file")
        currentFolder: watch.details.folder ? "file:///" + watch.details.folder : ""
        nameFilters: [qsTr("Subtitles (*.srt *.ass *.ssa *.vtt *.sub)"), qsTr("All files (*)")]
        onAccepted: {
            const file = VideoLibrary.localPath(selectedFile);
            mpv.addSubtitleFile(file);
            VideoLibrary.setSubtitleChoice(watch.path, file);
            watch.subtitleOffer = "";
        }
    }

    // --- Keyboard ------------------------------------------------------------
    component Key: Shortcut {
        enabled: watch.mode === "watch" || watch.mode === "full"
    }
    Key { sequences: ["Space", "K"]; onActivated: { mpv.togglePause(); watch.wake(); } }
    Key { sequence: "Left"; onActivated: { mpv.seekRelative(-5); watch.wake(); } }
    Key { sequence: "Right"; onActivated: { mpv.seekRelative(5); watch.wake(); } }
    Key { sequence: "J"; onActivated: { mpv.seekRelative(-watch.app.skipSeconds); watch.wake(); } }
    Key { sequence: "L"; onActivated: { mpv.seekRelative(watch.app.skipSeconds); watch.wake(); } }
    Key { sequence: "Up"; onActivated: watch.setVolume(Math.min(100, mpv.volume + 5)) }
    Key { sequence: "Down"; onActivated: watch.setVolume(Math.max(0, mpv.volume - 5)) }
    Key { sequence: "M"; onActivated: { mpv.muted = !mpv.muted; watch.wake(); } }
    Key { sequence: "F"; onActivated: watch.toggleFull() }
    Key { sequence: "I"; onActivated: watch.minimize() }
    Key { sequence: "Shift+N"; onActivated: watch.playNext() }
    Key { sequence: "Shift+P"; onActivated: watch.playPrevious() }
    Key { sequence: "C"; onActivated: watch.toggleSubtitles() }
    Key { sequence: "Esc"; onActivated: watch.mode === "full" ? watch.exitFull() : watch.minimize() }
    Shortcut {
        // In the mini player, I brings the watch page back.
        enabled: watch.mode === "mini"
        sequences: ["I"]
        onActivated: watch.expand()
    }
}
