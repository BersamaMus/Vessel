import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// "Save to playlist": every playlist, ticked if the video is in it. Ticking one adds the
// video and unticking takes it out, there and then. A new playlist can be made here too,
// with the video in it.
AppDialog {
    id: dialog

    // The window (Main), for its notices.
    property var app
    property string path
    // The playlists it's in, read afresh as it's added and taken out.
    readonly property var savedIn: path !== "" && VideoLibrary.playlistRevision >= 0 ? VideoLibrary.playlistsWith(path) : []
    // The new playlist's name is being typed.
    property bool creating: false

    function toggle(id, name) {
        if (savedIn.includes(id)) {
            VideoLibrary.removeFromPlaylist(id, path);
            app.toast(qsTr("Removed from %1").arg(name), { icon: "playlist" });
        } else {
            VideoLibrary.addToPlaylist(id, path);
            app.toast(qsTr("Saved to %1").arg(name), { icon: "playlist_added" });
        }
    }

    function create() {
        const name = nameField.text.trim();
        if (name === "")
            return;
        VideoLibrary.createPlaylist(name, [path]);
        app.toast(qsTr("Saved to %1").arg(name), { icon: "playlist_added" });
        close();
    }

    title: qsTr("Save to playlist")
    confirmText: qsTr("Done")
    cancelVisible: false
    preferredWidth: 400
    onOpened: {
        nameField.text = "";
        // With no playlists yet, straight to naming the first.
        creating = VideoLibrary.playlists.count === 0;
        if (creating)
            nameField.forceActiveFocus();
    }

    ListView {
        id: list
        Layout.fillWidth: true
        Layout.preferredHeight: Math.min(contentHeight, 288)
        Layout.leftMargin: -8
        Layout.rightMargin: -8
        visible: count > 0
        clip: true
        model: VideoLibrary.playlists
        boundsBehavior: Flickable.StopAtBounds
        ScrollBar.vertical: ScrollBar {}

        delegate: AbstractButton {
            id: option

            required property string playlistId
            required property string name
            required property string countText
            readonly property bool saved: dialog.savedIn.includes(playlistId)

            width: list.width
            height: 48
            hoverEnabled: true
            focusPolicy: Qt.NoFocus
            onClicked: dialog.toggle(playlistId, name)

            HoverHandler { cursorShape: Qt.PointingHandCursor }

            background: Rectangle {
                radius: Theme.radiusArt - 4
                color: option.pressed ? Theme.pressed : option.hovered ? Theme.hover : "transparent"
            }
            contentItem: RowLayout {
                spacing: 12

                Icon {
                    Layout.leftMargin: 8
                    name: option.saved ? "checkbox" : "checkbox_blank"
                    color: option.saved ? Theme.accent : Theme.textSecondary
                }
                Text {
                    Layout.fillWidth: true
                    text: option.name
                    color: Theme.textPrimary
                    font.pixelSize: 15
                    elide: Text.ElideRight
                }
                Text {
                    Layout.rightMargin: 8
                    text: option.countText
                    color: Theme.textTertiary
                    font.pixelSize: 13
                }
            }
        }
    }

    TextAction {
        visible: !dialog.creating
        Layout.leftMargin: -12   // the icon lines up with the ticks
        text: qsTr("New playlist")
        iconName: "add"
        onClicked: {
            dialog.creating = true;
            nameField.forceActiveFocus();
        }
    }

    RowLayout {
        visible: dialog.creating
        Layout.fillWidth: true
        Layout.topMargin: list.visible ? 6 : 0
        spacing: 8

        AppTextField {
            id: nameField
            Layout.fillWidth: true
            placeholderText: qsTr("Playlist name")
            onAccepted: dialog.create()
        }
        DialogButton {
            primary: true
            enabled: nameField.text.trim() !== ""
            opacity: enabled ? 1 : 0.4
            text: qsTr("Create")
            onClicked: dialog.create()
        }
    }
}
