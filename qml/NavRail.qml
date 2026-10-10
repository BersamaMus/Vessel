import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Shapes

// The tabs down the left side of the window, as in Blimp: Home, Library and
// External at the top, Settings at the bottom.
Rectangle {
    id: rail

    // 0 Home, 1 Library, 2 External, 3 Settings
    property int currentIndex: 0
    signal tabClicked(int index)

    implicitWidth: 80
    color: Theme.surfaceHigh

    component Tab: AbstractButton {
        id: tab

        property int tabIndex
        property string iconName
        property string label
        property int badge: 0
        readonly property bool selected: rail.currentIndex === tabIndex

        width: rail.width
        height: 64
        hoverEnabled: true
        focusPolicy: Qt.NoFocus
        onClicked: rail.tabClicked(tabIndex)

        HoverHandler { cursorShape: Qt.PointingHandCursor }

        background: null
        contentItem: Item {
            Rectangle {
                id: pill
                anchors.horizontalCenter: parent.horizontalCenter
                y: 6
                width: 56
                height: 32
                radius: 16
                color: tab.selected ? Theme.accentSoft : tab.hovered ? Theme.hover : "transparent"
                Behavior on color { ColorAnimation { duration: 150 } }

                Icon {
                    anchors.centerIn: parent
                    name: tab.iconName
                    color: tab.selected ? Theme.accent : Theme.textSecondary
                }

                // How many drives are plugged in.
                Rectangle {
                    visible: tab.badge > 0
                    x: parent.width - width - 6
                    y: 1
                    width: Math.max(16, badgeText.implicitWidth + 8)
                    height: 16
                    radius: 8
                    color: Theme.accent
                    Text {
                        id: badgeText
                        anchors.centerIn: parent
                        text: tab.badge
                        color: Theme.accentText
                        font.pixelSize: 10
                        font.weight: Font.Bold
                    }
                }
            }

            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                anchors.top: pill.bottom
                anchors.topMargin: 4
                text: tab.label
                color: tab.selected ? Theme.accent : Theme.textSecondary
                font.pixelSize: 12
                font.weight: Font.DemiBold
            }
        }
    }

    Column {
        anchors.top: parent.top
        anchors.topMargin: 8
        width: parent.width
        spacing: 2

        Tab { tabIndex: 0; iconName: "home"; label: qsTr("Home") }
        Tab { tabIndex: 1; iconName: "library"; label: qsTr("Library") }
        Tab { tabIndex: 2; iconName: "usb"; label: qsTr("External"); badge: VideoLibrary.drives.length }
    }

    // Background work: a ring that fills as thumbnails are made, or spins while
    // folders and drives are being looked through. Details on hover.
    Item {
        id: activity

        readonly property int total: VideoLibrary.thumbnailsTotal
        readonly property real fraction: total > 0 ? VideoLibrary.thumbnailsDone / total : 0
        readonly property bool busy: total > 0 || VideoLibrary.scanning

        anchors.bottom: settingsTab.top
        anchors.bottomMargin: 10
        anchors.horizontalCenter: parent.horizontalCenter
        width: parent.width
        height: 56
        opacity: busy ? 1 : 0
        visible: opacity > 0
        Behavior on opacity { NumberAnimation { duration: 300 } }

        HoverHandler { id: activityHover }
        ToolTip.visible: activityHover.hovered
        ToolTip.delay: 300
        ToolTip.text: [
            activity.total > 0 ? qsTr("Making thumbnails: %1 of %2").arg(VideoLibrary.thumbnailsDone).arg(activity.total) : "",
            VideoLibrary.scanning ? (VideoLibrary.videosFound > 0 ? qsTr("Looking for videos: %1 found so far").arg(VideoLibrary.videosFound)
                                                                  : qsTr("Looking for videos…")) : "",
        ].filter(line => line).join("\n")

        Shape {
            id: ring
            anchors.horizontalCenter: parent.horizontalCenter
            width: 30
            height: 30
            preferredRendererType: Shape.CurveRenderer

            ShapePath {
                strokeColor: "#33FFFFFF"
                strokeWidth: 3
                fillColor: "transparent"
                PathAngleArc { centerX: 15; centerY: 15; radiusX: 13; radiusY: 13; startAngle: 0; sweepAngle: 360 }
            }
            ShapePath {
                strokeColor: Theme.accent
                strokeWidth: 3
                capStyle: ShapePath.RoundCap
                fillColor: "transparent"
                PathAngleArc {
                    centerX: 15; centerY: 15; radiusX: 13; radiusY: 13
                    startAngle: -90
                    // Filling up with thumbnails; a spinning quarter while only scanning.
                    sweepAngle: activity.total > 0 ? Math.max(4, 360 * activity.fraction) : 90
                    Behavior on sweepAngle { NumberAnimation { duration: 250 } }
                }
            }

            RotationAnimation on rotation {
                running: activity.visible && activity.total === 0
                loops: Animation.Infinite
                from: 0
                to: 360
                duration: 1000
                onStopped: ring.rotation = 0
            }
        }

        Text {
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.top: ring.bottom
            anchors.topMargin: 5
            text: activity.total > 0 ? Math.floor(activity.fraction * 100) + "%" : qsTr("Scanning")
            color: Theme.textSecondary
            font.pixelSize: 11
            font.weight: Font.DemiBold
        }
    }

    Tab {
        id: settingsTab
        anchors.bottom: parent.bottom
        anchors.bottomMargin: 8
        tabIndex: 3
        iconName: "settings"
        label: qsTr("Settings")
    }
}
