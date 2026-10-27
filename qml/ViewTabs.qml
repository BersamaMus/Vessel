import QtQuick
import QtQuick.Controls.Basic

// Two or three pills under a page's title that switch what the page lists: "Folders" or
// "Videos" on a folder, "Drives" or "Videos" on External. The chosen one is filled in.
Row {
    id: tabs

    // [{ value, label }]
    property var options: []
    property string current

    signal chosen(string value)

    spacing: 8

    Repeater {
        model: tabs.options
        delegate: AbstractButton {
            id: tab
            required property var modelData
            readonly property bool selected: modelData.value === tabs.current

            text: modelData.label
            hoverEnabled: true
            focusPolicy: Qt.NoFocus
            leftPadding: 16
            rightPadding: 16
            topPadding: 7
            bottomPadding: 7
            onClicked: tabs.chosen(modelData.value)

            HoverHandler { cursorShape: Qt.PointingHandCursor }

            background: Rectangle {
                radius: height / 2
                color: tab.selected ? Theme.textPrimary
                     : tab.pressed ? Theme.pressed : tab.hovered ? "#29FFFFFF" : Theme.surfaceHigh
            }

            contentItem: Text {
                text: tab.text
                color: tab.selected ? Theme.background : Theme.textPrimary
                font.pixelSize: 14
                font.weight: Font.DemiBold
            }
        }
    }
}
