import QtQuick
import QtQuick.Layouts

// "Making thumbnails • 124 of 830" with a bar, for one folder or drive ("" for
// the library). Hidden when there's nothing left to make there.
ColumnLayout {
    id: root

    property string folder
    // Re-read whenever any thumbnail is made.
    readonly property var progress: VideoLibrary.thumbnailsDone >= 0 ? VideoLibrary.thumbnailProgressIn(folder)
                                                                     : ({ done: 0, total: 0 })
    readonly property bool active: progress.total > 0

    visible: active
    spacing: 6

    Text {
        text: qsTr("Making thumbnails  •  %1 of %2").arg(root.progress.done).arg(root.progress.total)
        color: Theme.textSecondary
        font.pixelSize: 13
    }

    Rectangle {
        Layout.fillWidth: true
        Layout.maximumWidth: 360
        implicitHeight: 4
        radius: 2
        color: "#33FFFFFF"
        Rectangle {
            width: parent.width * (root.active ? root.progress.done / root.progress.total : 0)
            height: parent.height
            radius: 2
            color: Theme.accent
            Behavior on width { NumberAnimation { duration: 250 } }
        }
    }
}
