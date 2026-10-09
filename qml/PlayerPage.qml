import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// Full-window playback. Controls fade out while the video plays and come
// back when the mouse moves. Progress is saved so the video can be resumed.
Item {
    id: player

    property string path
    readonly property var app: ApplicationWindow.window
    readonly property string title: VideoLibrary.titleOf(path)
    property bool controlsShown: true
    property double lastPosition: 0
    property double lastDuration: 0

    function saveProgress() {
        if (lastDuration > 0)
            VideoLibrary.saveProgress(path, lastPosition, lastDuration);
    }

    function goBack() {
        saveProgress();
        if (app.isFullScreen)
            app.toggleFullScreen();
        StackView.view.pop();
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

    function formatTime(seconds) {
        const total = Math.max(0, Math.floor(seconds));
        const h = Math.floor(total / 3600), m = Math.floor(total / 60) % 60, s = total % 60;
        const pad = n => (n < 10 ? "0" : "") + n;
        return h > 0 ? h + ":" + pad(m) + ":" + pad(s) : m + ":" + pad(s);
    }

    Rectangle {
        anchors.fill: parent
        color: "black"
    }

    MpvItem {
        id: mpv
        anchors.fill: parent

        Component.onCompleted: {
            volume = player.app.volume;
            open(player.path, VideoLibrary.resumePosition(player.path));
        }
        onPositionChanged: {
            if (loaded && duration > 0) {
                player.lastPosition = position;
                player.lastDuration = duration;
            }
        }
        onPausedChanged: {
            player.saveProgress();
            player.wake();
        }
        onEndedChanged: if (ended) {
            player.saveProgress();
            player.wake();
        }
        onErrorOccurred: message => errorText.text = message

        // Click to pause, double-click for full screen.
        TapHandler {
            onTapped: clickTimer.restart()
            onDoubleTapped: {
                clickTimer.stop();
                player.app.toggleFullScreen();
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
        running: true
        onTriggered: {
            if (!mpv.paused && !seekBar.pressed && !controlsHover.hovered)
                player.controlsShown = false;
        }
    }

    // Saves now and then, in case the app is closed unexpectedly.
    Timer {
        interval: 5000
        repeat: true
        running: mpv.loaded && !mpv.paused
        onTriggered: player.saveProgress()
    }

    Connections {
        target: player.app
        function onClosing() { player.saveProgress() }
    }

    HoverHandler {
        cursorShape: player.controlsShown ? Qt.ArrowCursor : Qt.BlankCursor
        onPointChanged: player.wake()
    }

    Text {
        id: errorText
        anchors.centerIn: parent
        width: parent.width * 0.6
        visible: text !== ""
        horizontalAlignment: Text.AlignHCenter
        wrapMode: Text.Wrap
        color: Theme.textSecondary
        font.pixelSize: 16
    }

    // --- Top bar: back and title -------------------------------------------
    Item {
        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        height: 96
        opacity: player.controlsShown ? 1 : 0
        visible: opacity > 0
        Behavior on opacity { NumberAnimation { duration: 250 } }

        Rectangle {
            anchors.fill: parent
            gradient: Gradient {
                GradientStop { position: 0; color: "#B3000000" }
                GradientStop { position: 1; color: "transparent" }
            }
        }

        RowLayout {
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
            anchors.margins: 12
            spacing: 8

            IconButton {
                iconName: "back"
                tip: qsTr("Back (Esc)")
                onClicked: player.goBack()
            }
            Text {
                Layout.fillWidth: true
                text: player.title
                color: Theme.textPrimary
                font.pixelSize: 18
                font.weight: Font.Bold
                elide: Text.ElideRight
            }
        }
    }

    // --- Bottom bar: seek bar and buttons ----------------------------------
    Item {
        id: bottomBar
        anchors.bottom: parent.bottom
        anchors.left: parent.left
        anchors.right: parent.right
        height: 150
        opacity: player.controlsShown ? 1 : 0
        visible: opacity > 0
        Behavior on opacity { NumberAnimation { duration: 250 } }

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
            anchors.leftMargin: 24
            anchors.rightMargin: 24
            anchors.bottomMargin: 16
            spacing: 6

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

            RowLayout {
                Layout.fillWidth: true
                spacing: 8

                // Equal-width sides keep the play button centred.
                Item {
                    Layout.fillWidth: true
                    Layout.preferredWidth: 1
                    implicitHeight: 56

                    Text {
                        anchors.verticalCenter: parent.verticalCenter
                        text: player.formatTime(seekBar.value) + " / " + player.formatTime(mpv.duration)
                        color: Theme.textPrimary
                        font.pixelSize: 14
                        font.features: { "tnum": 1 }
                    }
                }

                IconButton {
                    iconName: "rewind"
                    tip: qsTr("Back 10 seconds (J)")
                    onClicked: mpv.seekRelative(-10)
                }

                AbstractButton {
                    id: playButton
                    implicitWidth: 60
                    implicitHeight: 60
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
                            size: 32
                            color: Theme.accentText
                        }
                    }
                }

                IconButton {
                    iconName: "forward"
                    tip: qsTr("Forward 10 seconds (L)")
                    onClicked: mpv.seekRelative(10)
                }

                Item {
                    Layout.fillWidth: true
                    Layout.preferredWidth: 1
                    implicitHeight: 56

                    Row {
                        anchors.right: parent.right
                        anchors.verticalCenter: parent.verticalCenter
                        spacing: 4

                        IconButton {
                            anchors.verticalCenter: parent.verticalCenter
                            iconName: mpv.muted || mpv.volume === 0 ? "volume_off" : "volume"
                            tip: qsTr("Mute (M)")
                            onClicked: mpv.muted = !mpv.muted
                        }
                        AppSlider {
                            id: volumeSlider
                            anchors.verticalCenter: parent.verticalCenter
                            width: 110
                            from: 0
                            to: 100
                            onMoved: player.setVolume(value)

                            Binding {
                                target: volumeSlider
                                property: "value"
                                when: !volumeSlider.pressed
                                value: mpv.volume
                                restoreMode: Binding.RestoreNone
                            }
                        }
                        Item { width: 8; height: 1 }
                        IconButton {
                            anchors.verticalCenter: parent.verticalCenter
                            iconName: player.app.isFullScreen ? "fullscreen_exit" : "fullscreen"
                            tip: qsTr("Full screen (F)")
                            onClicked: player.app.toggleFullScreen()
                        }
                    }
                }
            }
        }
    }

    // --- Keyboard ----------------------------------------------------------
    Shortcut { sequences: ["Space", "K"]; onActivated: { mpv.togglePause(); player.wake(); } }
    Shortcut { sequence: "Left"; onActivated: { mpv.seekRelative(-5); player.wake(); } }
    Shortcut { sequence: "Right"; onActivated: { mpv.seekRelative(5); player.wake(); } }
    Shortcut { sequence: "J"; onActivated: { mpv.seekRelative(-10); player.wake(); } }
    Shortcut { sequence: "L"; onActivated: { mpv.seekRelative(10); player.wake(); } }
    Shortcut { sequence: "Up"; onActivated: player.setVolume(Math.min(100, mpv.volume + 5)) }
    Shortcut { sequence: "Down"; onActivated: player.setVolume(Math.max(0, mpv.volume - 5)) }
    Shortcut { sequence: "M"; onActivated: { mpv.muted = !mpv.muted; player.wake(); } }
    Shortcut { sequence: "F"; onActivated: player.app.toggleFullScreen() }
    Shortcut {
        sequence: "Esc"
        onActivated: player.app.isFullScreen ? player.app.toggleFullScreen() : player.goBack()
    }
}
