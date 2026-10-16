import QtQuick
import QtQuick.Controls.Basic

// A text field for dialogs (a new name), outlined in the accent while typing.
TextField {
    id: field

    color: Theme.textPrimary
    placeholderTextColor: Theme.textTertiary
    selectionColor: Theme.accent
    selectedTextColor: Theme.accentText
    font.pixelSize: 15
    padding: 12
    background: Rectangle {
        radius: 12
        color: Theme.surfaceHigh
        border.color: field.activeFocus ? Theme.accent : Theme.border
    }
}
