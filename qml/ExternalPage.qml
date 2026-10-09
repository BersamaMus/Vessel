import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// Connected USB drives, external hard drives and memory cards.
ScrollView {
    id: page

    readonly property var app: ApplicationWindow.window

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
                    text: card.modelData.scanning ? qsTr("Looking for videos…")
                        : card.modelData.count === 0 ? qsTr("No videos found")
                        : card.modelData.count === 1 ? qsTr("1 video")
                        : qsTr("%1 videos").arg(card.modelData.count)
                    color: card.modelData.count > 0 ? Theme.accent : Theme.textSecondary
                    font.pixelSize: 14
                    font.weight: Font.DemiBold
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

        ColumnLayout {
            Layout.fillWidth: true
            Layout.leftMargin: Theme.pagePadding
            Layout.rightMargin: Theme.pagePadding
            Layout.topMargin: 14
            spacing: 2

            Text {
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

        EmptyNote {
            visible: VideoLibrary.drives.length === 0
            Layout.topMargin: 24
            iconName: "usb"
            text: qsTr("Plug in a USB drive, an external hard drive or a memory card, and its videos will show up here.")
        }

        Flow {
            Layout.fillWidth: true
            Layout.leftMargin: Theme.pagePadding
            Layout.rightMargin: Theme.pagePadding
            Layout.topMargin: 24
            spacing: 16

            Repeater {
                model: VideoLibrary.drives
                delegate: DriveCard {}
            }
        }

        Item { implicitHeight: 32 }
    }
}
