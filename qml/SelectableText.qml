import QtQuick

// Text that can be selected and copied: drag across it (or double-click a word, triple-click
// a line), then Ctrl+C. Wraps rather than eliding, so all of it can be copied.
TextEdit {
    readOnly: true
    selectByMouse: true
    activeFocusOnTab: false
    textFormat: TextEdit.PlainText
    wrapMode: TextEdit.Wrap
    selectionColor: Theme.accent
    selectedTextColor: Theme.accentText

    HoverHandler { cursorShape: Qt.IBeamCursor }
}
