import QtQuick
import QtQuick.Layouts

// The top of a page (its title and buttons), pinned while the rest scrolls under it.
// A hairline shows once something has scrolled beneath.
Rectangle {
    id: bar

    default property alias content: column.data
    // Set when the page below isn't at its top.
    property bool scrolled: false

    implicitHeight: column.implicitHeight + 12
    color: Theme.background
    z: 1

    ColumnLayout {
        id: column
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        spacing: 0
    }

    Rectangle {
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        height: 1
        color: Theme.borderSoft
        opacity: bar.scrolled ? 1 : 0
        Behavior on opacity { NumberAnimation { duration: 150 } }
    }
}
