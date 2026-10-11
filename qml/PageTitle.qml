import QtQuick
import QtQuick.Layouts

// A page's big title, with a line under it ("12 videos").
ColumnLayout {
    id: heading

    property string title
    property string subtitle

    Layout.fillWidth: true
    spacing: 2

    Text {
        Layout.fillWidth: true
        text: heading.title
        color: Theme.textPrimary
        font.pixelSize: Theme.pageTitleSize
        font.weight: Font.ExtraBold
        font.letterSpacing: -0.5
        elide: Text.ElideRight
    }
    Text {
        Layout.fillWidth: true
        visible: heading.subtitle !== ""
        text: heading.subtitle
        color: Theme.textSecondary
        font.pixelSize: 14
        elide: Text.ElideRight
    }
}
