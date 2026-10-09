import QtQuick
import QtQuick.Layouts

// A section title with optional extra buttons and a yellow action on the right.
RowLayout {
    id: header

    property string title
    property string actionLabel
    property string actionIcon
    default property alias extras: extraButtons.data

    signal actionTriggered()

    Layout.fillWidth: true
    Layout.leftMargin: Theme.pagePadding
    Layout.rightMargin: Theme.pagePadding - 8
    Layout.topMargin: 28
    Layout.bottomMargin: 12
    spacing: 4

    Text {
        Layout.fillWidth: true
        text: header.title
        color: Theme.textPrimary
        font.pixelSize: 22
        font.weight: Font.Bold
        font.letterSpacing: -0.2
        elide: Text.ElideRight
    }

    Row {
        id: extraButtons
        spacing: 4
    }

    TextAction {
        visible: header.actionLabel !== ""
        text: header.actionLabel
        iconName: header.actionIcon
        onClicked: header.actionTriggered()
    }
}
