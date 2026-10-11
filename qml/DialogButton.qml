import QtQuick
import QtQuick.Controls.Basic

// A dialog's button: purple for the main action, grey otherwise.
AbstractButton {
    id: button

    property bool primary: false

    hoverEnabled: true
    focusPolicy: Qt.NoFocus
    leftPadding: 20
    rightPadding: 20
    topPadding: 10
    bottomPadding: 10

    HoverHandler { cursorShape: Qt.PointingHandCursor }

    background: Rectangle {
        radius: height / 2
        color: button.primary ? (button.hovered ? Qt.lighter(Theme.accent, 1.1) : Theme.accent)
                              : (button.hovered ? Theme.controlHover : Theme.control)
    }
    contentItem: Text {
        text: button.text
        horizontalAlignment: Text.AlignHCenter
        color: button.primary ? Theme.accentText : Theme.textPrimary
        font.pixelSize: 14
        font.weight: Font.Bold
    }
}
