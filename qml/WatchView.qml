import QtQuick
import QtQuick.Controls.Basic
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
        mpv.open(newPath, VideoLibrary.resumePosition(newPath));
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

    function openFolder() {
        minimize();
        app.openFolder(details.folder);
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
            if (!mpv.paused && !seekBar.pressed && !controlsHover.hovered)
                watch.controlsShown = false;
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

    component Chip: AbstractButton {
        id: chip
        property string iconName
        hoverEnabled: true
        focusPolicy: Qt.NoFocus
        leftPadding: 12
        rightPadding: 16
        topPadding: 8
        bottomPadding: 8
        HoverHandler { cursorShape: Qt.PointingHandCursor }
        background: Rectangle {
            radius: height / 2
            color: chip.pressed ? "#3A3A3A" : chip.hovered ? "#2E2E2E" : Theme.surfaceHigh
        }
        contentItem: Row {
            spacing: 8
            Icon {
                anchors.verticalCenter: parent.verticalCenter
                name: chip.iconName
                size: 20
            }
            Text {
                anchors.verticalCenter: parent.verticalCenter
                text: chip.text
                color: Theme.textPrimary
                font.pixelSize: 14
                font.weight: Font.DemiBold
            }
        }
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

            Flow {
                Layout.fillWidth: true
                Layout.topMargin: 6
                spacing: 8

                Chip {
                    visible: watch.details.inLibrary ?? false
                    iconName: "folder"
                    text: qsTr("Open folder")
                    onClicked: watch.openFolder()
                }
                Chip {
                    visible: watch.nextPath !== ""
                    iconName: "skip_next"
                    text: qsTr("Next")
                    onClicked: watch.playNext()
                }
                Chip {
                    iconName: "pip"
                    text: qsTr("Mini player")
                    onClicked: watch.minimize()
                }
                Chip {
                    iconName: "fullscreen"
                    text: qsTr("Full screen")
                    onClicked: watch.enterFull()
                }
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

            TapHandler {
                onTapped: watch.mode === "mini" ? watch.expand() : clickTimer.restart()
                onDoubleTapped: {
                    if (watch.mode === "mini")
                        return;
                    clickTimer.stop();
                    watch.toggleFull();
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
        }

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
                    iconName: "rewind"
                    iconSize: 28
                    width: 48
                    height: 48
                    tip: qsTr("Back 10 seconds (J)")
                    onClicked: mpv.seekRelative(-10)
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
                    iconName: "forward"
                    iconSize: 28
                    width: 48
                    height: 48
                    tip: qsTr("Forward 10 seconds (L)")
                    onClicked: mpv.seekRelative(10)
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
                            visible: watch.nextPath !== ""
                            iconName: "skip_next"
                            tip: qsTr("Next (Shift+N)")
                            onClicked: watch.playNext()
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

    // --- Keyboard ------------------------------------------------------------
    component Key: Shortcut {
        enabled: watch.mode === "watch" || watch.mode === "full"
    }
    Key { sequences: ["Space", "K"]; onActivated: { mpv.togglePause(); watch.wake(); } }
    Key { sequence: "Left"; onActivated: { mpv.seekRelative(-5); watch.wake(); } }
    Key { sequence: "Right"; onActivated: { mpv.seekRelative(5); watch.wake(); } }
    Key { sequence: "J"; onActivated: { mpv.seekRelative(-10); watch.wake(); } }
    Key { sequence: "L"; onActivated: { mpv.seekRelative(10); watch.wake(); } }
    Key { sequence: "Up"; onActivated: watch.setVolume(Math.min(100, mpv.volume + 5)) }
    Key { sequence: "Down"; onActivated: watch.setVolume(Math.max(0, mpv.volume - 5)) }
    Key { sequence: "M"; onActivated: { mpv.muted = !mpv.muted; watch.wake(); } }
    Key { sequence: "F"; onActivated: watch.toggleFull() }
    Key { sequence: "I"; onActivated: watch.minimize() }
    Key { sequence: "Shift+N"; onActivated: watch.playNext() }
    Key { sequence: "Esc"; onActivated: watch.mode === "full" ? watch.exitFull() : watch.minimize() }
    Shortcut {
        // In the mini player, I brings the watch page back.
        enabled: watch.mode === "mini"
        sequences: ["I"]
        onActivated: watch.expand()
    }
}
