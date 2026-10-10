import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// Connected USB drives, external hard drives and memory cards, under a bar with
// the title and search that stays put while the page scrolls.
Item {
    id: external

    PageBar {
        id: bar
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        scrolled: page.contentItem.contentY > 1

        RowLayout {
            Layout.fillWidth: true
            Layout.leftMargin: Theme.pagePadding
            Layout.rightMargin: Theme.pagePadding
            Layout.topMargin: 14
            spacing: 8

            ColumnLayout {
                Layout.fillWidth: true
                spacing: 2

                Text {
                    Layout.fillWidth: true   // lets the column grow, putting search at the far right
                    text: qsTr("External")
                    color: Theme.textPrimary
                    font.pixelSize: 26
                    font.weight: Font.ExtraBold
                    font.letterSpacing: -0.5
                }
                Text {
                    text: qsTr("USB drives, external hard drives and memory cards")
                    color: Theme.textSecondary
                    font.pixelSize: 14
                }
            }

            // Searches every connected drive.
            IconButton {
                Layout.alignment: Qt.AlignTop
                visible: VideoLibrary.drives.length > 0
                iconName: "search"
                tip: qsTr("Search your drives (Ctrl+F)")
                onClicked: page.app.openSearch()
            }
        }
    }

    ScrollView {
        id: page

        readonly property var app: ApplicationWindow.window

        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: bar.bottom
        anchors.bottom: parent.bottom
        contentWidth: availableWidth
        clip: true

        component DriveCard: AbstractButton {
            id: card

            required property var modelData

            width: 340
            hoverEnabled: true
            focusPolicy: Qt.NoFocus
            padding: 18
            onClicked: page.app.openFolder(modelData.path)

            HoverHandler { cursorShape: Qt.PointingHandCursor }

            background: Rectangle {
                radius: Theme.radiusCard
                color: card.pressed ? "#262626" : card.hovered ? Theme.surfaceHigh : Theme.surface
                border.color: "#1FFFFFFF"
            }

            contentItem: RowLayout {
                spacing: 16

                Rectangle {
                    Layout.alignment: Qt.AlignTop
                    implicitWidth: 52
                    implicitHeight: 52
                    radius: 26
                    color: Theme.accentSoft
                    Icon {
                        anchors.centerIn: parent
                        name: card.modelData.kind === "card" ? "sd_card" : "usb"
                        color: Theme.accent
                        size: 26
                    }
                }

                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 4

                    Text {
                        Layout.fillWidth: true
                        text: card.modelData.name
                        color: Theme.textPrimary
                        font.pixelSize: 17
                        font.weight: Font.Bold
                        elide: Text.ElideRight
                    }
                    Text {
                        text: card.modelData.scanning
                              ? (card.modelData.found > 0 ? qsTr("Looking for videos…  %1 found").arg(card.modelData.found)
                                                          : qsTr("Looking for videos…"))
                            : card.modelData.count === 0 ? qsTr("No videos found")
                            : card.modelData.count === 1 ? qsTr("1 video")
                            : qsTr("%1 videos").arg(card.modelData.count)
                        color: card.modelData.count > 0 ? Theme.accent : Theme.textSecondary
                        font.pixelSize: 14
                        font.weight: Font.DemiBold
                    }
                    WorkProgress {
                        Layout.fillWidth: true
                        Layout.topMargin: 4
                        folder: card.modelData.path
                    }
                    Rectangle {
                        Layout.fillWidth: true
                        Layout.topMargin: 6
                        implicitHeight: 6
                        radius: 3
                        color: "#33FFFFFF"
                        Rectangle {
                            width: parent.width * card.modelData.usedFraction
                            height: parent.height
                            radius: 3
                            color: Theme.textSecondary
                        }
                    }
                    Text {
                        text: qsTr("%1 free of %2").arg(card.modelData.freeText).arg(card.modelData.sizeText)
                        color: Theme.textTertiary
                        font.pixelSize: 12
                    }
                }
            }
        }

        ColumnLayout {
            width: page.availableWidth
            spacing: 0

            EmptyNote {
                visible: VideoLibrary.drives.length === 0
                Layout.topMargin: 12
                iconName: "usb"
                text: qsTr("Plug in a USB drive, an external hard drive or a memory card, and its videos will show up here.")
            }

            Flow {
                Layout.fillWidth: true
                Layout.leftMargin: Theme.pagePadding
                Layout.rightMargin: Theme.pagePadding
                Layout.topMargin: 12
                spacing: 16

                Repeater {
                    model: VideoLibrary.drives
                    delegate: DriveCard {}
                }
            }

            Item { implicitHeight: 32 }
        }
    }
}
