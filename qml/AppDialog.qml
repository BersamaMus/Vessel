import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// A small modal dialog: a title, a message, anything else in between (a text field), and a
// Cancel button beside the main one. Escape or clicking outside cancels.
Popup {
    id: dialog

    property string title
    property string text
    property string confirmText: qsTr("OK")
    property string cancelText: qsTr("Cancel")
    property real preferredWidth: 420
    // Extra content, under the message.
    default property alias content: extra.data

    signal confirmed()

    // Closes it, then confirmed().
    function confirm() {
        close();
        confirmed();
    }

    parent: Overlay.overlay
    anchors.centerIn: parent
    width: Math.min(preferredWidth, (parent ? parent.width : preferredWidth + 48) - 48)
    modal: true
    padding: 24

    Overlay.modal: Rectangle { color: Theme.scrim }

    background: Rectangle {
        radius: Theme.radiusDialog
        color: Theme.dialog
        border.color: Theme.border
    }

    contentItem: ColumnLayout {
        spacing: 10

        Text {
            Layout.fillWidth: true
            text: dialog.title
            color: Theme.textPrimary
            font.pixelSize: Theme.sectionSize
            font.weight: Font.Bold
            wrapMode: Text.Wrap
        }
        Text {
            Layout.fillWidth: true
            visible: dialog.text !== ""
            text: dialog.text
            color: Theme.textSecondary
            font.pixelSize: 14
            wrapMode: Text.Wrap
        }
        ColumnLayout {
            id: extra
            Layout.fillWidth: true
            spacing: 10
            visible: children.length > 0
        }
        RowLayout {
            Layout.alignment: Qt.AlignRight
            Layout.topMargin: 12
            spacing: 8

            DialogButton {
                text: dialog.cancelText
                onClicked: dialog.close()
            }
            DialogButton {
                primary: true
                text: dialog.confirmText
                onClicked: dialog.confirm()
            }
        }
    }
}
