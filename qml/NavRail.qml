import QtQuick
import QtQuick.Controls.Basic

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

    Tab {
        anchors.bottom: parent.bottom
        anchors.bottomMargin: 8
        tabIndex: 3
        iconName: "settings"
        label: qsTr("Settings")
    }
}
