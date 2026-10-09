import QtQuick
import QtQuick.Layouts

// A quiet message box, for empty sections.
Rectangle {
    id: note

    property string iconName: "history"
    property string text

    Layout.fillWidth: true
    Layout.leftMargin: Theme.pagePadding
    Layout.rightMargin: Theme.pagePadding
    implicitHeight: row.implicitHeight + 40
    radius: Theme.radiusCard
    color: Theme.surface

    RowLayout {
        id: row
        anchors.fill: parent
        anchors.margins: 20
        spacing: 14

        Icon {
            name: note.iconName
            color: Theme.accent
        }
        Text {
            Layout.fillWidth: true
            text: note.text
            color: Theme.textSecondary
            font.pixelSize: 14
            wrapMode: Text.Wrap
        }
    }
}
