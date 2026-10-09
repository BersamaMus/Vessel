import QtQuick
import QtQuick.Controls.Basic

// The tabs down the left side of the window, as in Blimp.
Rectangle {
    id: rail

    property int currentIndex: 0
    signal tabClicked(int index)

    implicitWidth: 80
    color: Theme.surfaceHigh

    Column {
        anchors.top: parent.top
        anchors.topMargin: 8
        width: parent.width
        spacing: 2

        Repeater {
            model: [
                { icon: "home", label: qsTr("Home") },
                { icon: "library", label: qsTr("Library") },
            ]

            delegate: AbstractButton {
                id: tab

                required property var modelData
                required property int index
                readonly property bool selected: rail.currentIndex === index

                width: rail.width
                height: 64
                hoverEnabled: true
                focusPolicy: Qt.NoFocus
                onClicked: rail.tabClicked(index)

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
                            name: tab.modelData.icon
                            color: tab.selected ? Theme.accent : Theme.textSecondary
                        }
                    }

                    Text {
                        anchors.horizontalCenter: parent.horizontalCenter
                        anchors.top: pill.bottom
                        anchors.topMargin: 4
                        text: tab.modelData.label
                        color: tab.selected ? Theme.accent : Theme.textSecondary
                        font.pixelSize: 12
                        font.weight: Font.DemiBold
                    }
                }
            }
        }
    }
}
