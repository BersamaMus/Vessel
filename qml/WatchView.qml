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
    // Typing goes to the player's shortcuts, not a search box left focused underneath.
    onModeChanged: {
        if (mode === "watch" || mode === "full")
            forceActiveFocus();
    }
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
    readonly property bool playing: mode !== "closed" && mpv.loaded && !mpv.paused && !mpv.ended
    readonly property string nextPath: path !== "" && VideoLibrary.count >= 0 ? VideoLibrary.nextInFolder(path) : ""
    readonly property string previousPath: path !== "" && VideoLibrary.count >= 0 ? VideoLibrary.previousInFolder(path) : ""
    // The double-tap skip in progress: which side, and how far so far.
    property int seekSide: 0
    property int seekTotal: 0
    // Subtitle files found for this video, and the one being offered ("" for no prompt).
    // Seconds until the next video autoplays; 0 when no countdown is running.
    property int autoplayCountdown: 0
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
        cancelAutoplay();
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

    // Autoplay: count down on the "Up next" card, then play the next video in the folder.
    function startAutoplay() {
        autoplayCountdown = 5;
        autoplayTimer.restart();
    }

    function cancelAutoplay() {
        autoplayTimer.stop();
        autoplayCountdown = 0;
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
        flash("subtitles", qsTr("Subtitles: %1").arg(subtitleCandidates[0].split("/").pop()));
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
        else if (subtitleCandidates.length > 0)
            acceptSubtitles();   // load what was found, even if declined before
        else
            flash("subtitles", qsTr("No subtitles for this video"));
        wake();
    }

    // From the subtitles menu: null for off. Remembered for next time.
    function chooseSubtitle(track) {
        subtitleOffer = "";
        if (!track) {
            mpv.setSubtitleTrack(0);
            VideoLibrary.setSubtitleChoice(path, "");
            flash("subtitles", qsTr("Subtitles off"));
            return;
        }
        mpv.setSubtitleTrack(track.id);
        flash("subtitles", qsTr("Subtitles: %1").arg(track.label));
        if (track.file)
            VideoLibrary.setSubtitleChoice(path, track.file);
    }

    // -1 for the left third of the video, 1 for the right third, 0 for the middle.
    function sideAt(x) {
        return x < frame.width / 3 ? -1 : x > frame.width * 2 / 3 ? 1 : 0;
    }

    // Skips back (-1) or forward (1), adding up on the side bubble: double-taps and keys.
    function tapSeek(side, seconds) {
        const amount = seconds ?? app.skipSeconds;
        if (side !== seekSide)
            seekTotal = 0;
        seekSide = side;
        seekTotal += amount;
        mpv.seekRelative(side * amount);
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
        cancelAutoplay();
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

    // Up / Down keys.
    function stepVolume(delta) {
        const value = Math.max(0, Math.min(100, Math.round(mpv.volume) + delta));
        setVolume(value);
        flash(value === 0 ? "volume_off" : "volume", value + "%");
    }

    function toggleMute() {
        const muting = !mpv.muted;
        mpv.muted = muting;
        flash(muting ? "volume_off" : "volume", muting ? qsTr("Muted") : qsTr("Sound on"));
        wake();
    }

    // A brief pill at the top of the video saying what just changed.
    function flash(iconName, text) {
        indicator.iconName = iconName;
        indicatorText.text = text;
        indicator.opacity = 1;
        indicatorTimer.restart();
    }

    // The compression standard for mpv's codec name: "h264" is "H.264 (AVC)".
    function codecName(format) {
        const names = {
            h264: "H.264 (AVC)", hevc: "H.265 (HEVC)", av1: "AV1", vp9: "VP9", vp8: "VP8",
            mpeg4: "MPEG-4 Part 2 (DivX/Xvid)", mpeg2video: "MPEG-2", mpeg1video: "MPEG-1",
            msmpeg4v3: "MS MPEG-4 v3", msmpeg4v2: "MS MPEG-4 v2", h263: "H.263",
            wmv3: "WMV 9", wmv2: "WMV 8", wmv1: "WMV 7", vc1: "VC-1",
            prores: "ProRes", theora: "Theora", flv1: "Sorenson Spark", mjpeg: "Motion JPEG",
            rv40: "RealVideo 4", rv30: "RealVideo 3",
        };
        return format ? (names[format] ?? format.toUpperCase()) : "";
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
        id: autoplayTimer
        interval: 1000
        repeat: true
        onTriggered: {
            if (--watch.autoplayCountdown <= 0) {
                stop();
                watch.playNext();
            }
        }
    }

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
                // Created: when it was recorded or made (its media-created tag, or else the
                // modified date, which copying keeps). Added: when the file arrived here.
                text: {
                    const created = mpv.loaded ? (mpv.mediaCreated || watch.details.modifiedText || "") : "";
                    return [watch.details.folderLabel, watch.formatTime(mpv.duration),
                            created ? qsTr("Created %1").arg(created) : "",
                            watch.details.addedText ? qsTr("Added %1").arg(watch.details.addedText) : ""]
                           .filter(part => part).join("  •  ");
                }
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
                    // "MP4  •  H.264 (AVC)  •  1920 × 1080  •  23.98 fps  •  1.4 GB"
                    Text {
                        width: parent.width
                        text: {
                            const name = watch.details.fileName ?? "";
                            const type = name.includes(".") ? name.split(".").pop().toUpperCase() : "";
                            const fps = Math.round(mpv.frameRate * 100) / 100;
                            return [type, watch.codecName(mpv.videoFormat),
                                    mpv.videoWidth > 0 ? mpv.videoWidth + " × " + mpv.videoHeight : "",
                                    fps > 0 ? qsTr("%1 fps").arg(Number.isInteger(fps) ? fps : fps.toFixed(2).replace(/0$/, "")) : "",
                                    watch.details.sizeText]
                                   .filter(part => part).join("  •  ");
                        }
                        color: Theme.textSecondary
                        font.pixelSize: 13
                        elide: Text.ElideRight
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
                            loading: model.thumbnailPending
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
                        loading: model.thumbnailPending
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
            onEndedChanged: {
                if (ended) {
                    watch.saveProgress();
                    watch.wake();
                    if (watch.app.autoplay && watch.nextPath !== "")
                        watch.startAutoplay();
                } else {
                    watch.cancelAutoplay();   // e.g. seeked back from the end
                }
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

            // Where the mouse last really moved. Qt re-sends hover events whenever the
            // (constantly redrawing) video changes under a still mouse; those aren't movement.
            property point lastMove: Qt.point(-1, -1)

            cursorShape: watch.mode !== "mini" && !watch.controlsShown ? Qt.BlankCursor : Qt.ArrowCursor
            onPointChanged: {
                const p = point.scenePosition;
                if (Math.abs(p.x - lastMove.x) + Math.abs(p.y - lastMove.y) < 3)
                    return;
                lastMove = p;
                watch.wake();
            }
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
                visible: watch.autoplayCountdown === 0   // the "Up next" card takes the middle

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
                        AppSwitch {
                            Layout.leftMargin: 6
                            Layout.rightMargin: 6
                            implicitWidth: 36
                            implicitHeight: 20
                            knobIcon: "play"
                            checked: watch.app.autoplay
                            tip: checked ? qsTr("Autoplay is on") : qsTr("Autoplay is off")
                            onClicked: {
                                watch.app.autoplay = !watch.app.autoplay;
                                watch.flash("play", watch.app.autoplay ? qsTr("Autoplay on") : qsTr("Autoplay off"));
                                if (!watch.app.autoplay)
                                    watch.cancelAutoplay();
                                else if (mpv.ended && watch.nextPath !== "")
                                    watch.startAutoplay();
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

        // --- What just changed: "45%", "Subtitles off", "Autoplay on" -------------
        Rectangle {
            id: indicator

            property string iconName

            anchors.horizontalCenter: parent.horizontalCenter
            y: watch.miniProgress > 0.5 ? 10 : 20
            width: indicatorRow.implicitWidth + 28
            height: 40
            radius: 20
            color: "#D9000000"
            opacity: 0
            visible: opacity > 0
            Behavior on opacity { NumberAnimation { duration: 180 } }

            Row {
                id: indicatorRow
                anchors.centerIn: parent
                spacing: 8
                Icon {
                    anchors.verticalCenter: parent.verticalCenter
                    name: indicator.iconName
                    size: 20
                    color: Theme.accent
                }
                Text {
                    id: indicatorText
                    anchors.verticalCenter: parent.verticalCenter
                    color: Theme.textPrimary
                    font.pixelSize: 14
                    font.weight: Font.DemiBold
                }
            }

            Timer {
                id: indicatorTimer
                interval: 1200
                onTriggered: indicator.opacity = 0
            }
        }

        // --- Autoplay: "Up next" card ----------------------------------------------
        Rectangle {
            id: upNext

            readonly property bool large: watch.miniProgress < 0.5

            anchors.centerIn: parent
            width: large ? Math.min(parent.width - 48, 300) : parent.width
            height: large ? upNextColumn.implicitHeight + 32 : 34
            anchors.verticalCenterOffset: large ? 0 : (parent.height - height) / 2
            radius: large ? Theme.radiusCard : 0
            color: large ? "#F21C1C1C" : "#CC000000"
            border.color: large ? "#26FFFFFF" : "transparent"
            opacity: watch.autoplayCountdown > 0 ? 1 : 0
            visible: opacity > 0
            Behavior on opacity { NumberAnimation { duration: 200 } }

            // Clicks on the card itself shouldn't pause the video.
            MouseArea {
                anchors.fill: parent
            }

            ColumnLayout {
                id: upNextColumn
                visible: upNext.large
                anchors.fill: parent
                anchors.margins: 16
                spacing: 10

                Text {
                    text: qsTr("Up next in %1").arg(watch.autoplayCountdown)
                    color: Theme.textSecondary
                    font.pixelSize: 13
                    font.weight: Font.DemiBold
                }

                Item {
                    Layout.fillWidth: true
                    implicitHeight: width * 9 / 16
                    RoundedImage {
                        anchors.fill: parent
                        radius: Theme.radiusArt
                        source: watch.nextPath !== "" ? VideoLibrary.thumbnailFor(watch.nextPath) : ""
                    }
                    // Drains over the countdown.
                    Rectangle {
                        anchors.left: parent.left
                        anchors.bottom: parent.bottom
                        anchors.margins: 8
                        height: 4
                        radius: 2
                        color: Theme.accent
                        width: (parent.width - 16) * watch.autoplayCountdown / 5
                        Behavior on width { NumberAnimation { duration: 1000 } }
                    }
                }

                Text {
                    Layout.fillWidth: true
                    text: watch.nextPath !== "" ? VideoLibrary.titleOf(watch.nextPath) : ""
                    color: Theme.textPrimary
                    font.pixelSize: 15
                    font.weight: Font.Bold
                    wrapMode: Text.Wrap
                    maximumLineCount: 2
                    elide: Text.ElideRight
                }

                RowLayout {
                    Layout.fillWidth: true
                    spacing: 8

                    AbstractButton {
                        id: cancelButton
                        Layout.fillWidth: true
                        text: qsTr("Cancel")
                        hoverEnabled: true
                        focusPolicy: Qt.NoFocus
                        padding: 10
                        onClicked: watch.cancelAutoplay()
                        HoverHandler { cursorShape: Qt.PointingHandCursor }
                        background: Rectangle {
                            radius: height / 2
                            color: cancelButton.hovered ? "#3A3A3A" : "#2A2A2A"
                        }
                        contentItem: Text {
                            text: cancelButton.text
                            horizontalAlignment: Text.AlignHCenter
                            color: Theme.textPrimary
                            font.pixelSize: 14
                            font.weight: Font.DemiBold
                        }
                    }
                    AbstractButton {
                        id: playNowButton
                        Layout.fillWidth: true
                        text: qsTr("Play now")
                        hoverEnabled: true
                        focusPolicy: Qt.NoFocus
                        padding: 10
                        onClicked: watch.playNext()
                        HoverHandler { cursorShape: Qt.PointingHandCursor }
                        background: Rectangle {
                            radius: height / 2
                            color: playNowButton.hovered ? Qt.lighter(Theme.accent, 1.1) : Theme.accent
                        }
                        contentItem: Text {
                            text: playNowButton.text
                            horizontalAlignment: Text.AlignHCenter
                            color: Theme.accentText
                            font.pixelSize: 14
                            font.weight: Font.Bold
                        }
                    }
                }
            }

            // In the mini player, just a line along the bottom.
            Text {
                visible: !upNext.large
                anchors.centerIn: parent
                text: qsTr("Up next in %1").arg(watch.autoplayCountdown)
                color: Theme.textPrimary
                font.pixelSize: 13
                font.weight: Font.DemiBold
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
            watch.flash("subtitles", qsTr("Subtitles: %1").arg(file.split("/").pop()));
            VideoLibrary.setSubtitleChoice(watch.path, file);
            watch.subtitleOffer = "";
        }
    }

    // --- Keyboard ------------------------------------------------------------
    component Key: Shortcut {
        enabled: watch.mode === "watch" || watch.mode === "full"
    }
    Key { sequences: ["Space", "K"]; onActivated: { mpv.togglePause(); watch.wake(); } }
    Key { sequence: "Left"; onActivated: watch.tapSeek(-1, 5) }
    Key { sequence: "Right"; onActivated: watch.tapSeek(1, 5) }
    Key { sequence: "J"; onActivated: watch.tapSeek(-1) }
    Key { sequence: "L"; onActivated: watch.tapSeek(1) }
    Key { sequence: "Up"; onActivated: watch.stepVolume(5) }
    Key { sequence: "Down"; onActivated: watch.stepVolume(-5) }
    Key { sequence: "M"; onActivated: watch.toggleMute() }
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
