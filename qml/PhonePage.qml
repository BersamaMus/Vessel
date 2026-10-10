import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// Phones connected by USB, under a bar with the title and search that stays put
// while the page scrolls. A phone only shows its videos once it's unlocked and (on
// Android) set to File transfer; until then its card says so.
Item {
    id: phones

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
                    text: qsTr("Smartphone")
                    color: Theme.textPrimary
                    font.pixelSize: 26
                    font.weight: Font.ExtraBold
                    font.letterSpacing: -0.5
                }
                Text {
                    text: qsTr("Phones connected with a USB cable")
                    color: Theme.textSecondary
                    font.pixelSize: 14
                }
            }

            // Searches every connected phone.
            IconButton {
                Layout.alignment: Qt.AlignTop
                visible: VideoLibrary.phones.some(phone => phone.ready)
                iconName: "search"
                tip: qsTr("Search your phones (Ctrl+F)")
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

        component PhoneCard: AbstractButton {
            id: card

            required property var modelData
            readonly property bool ready: modelData.ready

            width: 340
            enabled: ready
            hoverEnabled: true
            focusPolicy: Qt.NoFocus
            padding: 18
            onClicked: page.app.openFolder(modelData.path)

            HoverHandler { cursorShape: card.ready ? Qt.PointingHandCursor : Qt.ArrowCursor }

            background: Rectangle {
                radius: Theme.radiusCard
                color: card.pressed ? "#262626" : card.hovered && card.ready ? Theme.surfaceHigh : Theme.surface
                border.color: "#1FFFFFFF"
            }

            contentItem: RowLayout {
                spacing: 16

                Rectangle {
                    Layout.alignment: Qt.AlignTop
                    implicitWidth: 52
                    implicitHeight: 52
                    radius: 26
                    color: card.ready ? Theme.accentSoft : Theme.surfaceHigh
                    Icon {
                        anchors.centerIn: parent
                        name: card.ready ? "smartphone" : "lock"
                        color: card.ready ? Theme.accent : Theme.textSecondary
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

                    // Connected, but its storage can't be seen yet.
                    Text {
                        visible: !card.ready
                        Layout.fillWidth: true
                        text: qsTr("Unlock your phone, then choose File transfer in the USB notification (Android) or tap Trust (iPhone).")
                        color: Theme.textSecondary
                        font.pixelSize: 13
                        wrapMode: Text.Wrap
                    }

                    Text {
                        visible: card.ready
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
                        visible: card.ready && active
                        Layout.fillWidth: true
                        Layout.topMargin: 4
                        folder: card.modelData.path
                    }
                    Rectangle {
                        visible: card.ready
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
                        visible: card.ready
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
                visible: VideoLibrary.phones.length === 0
                Layout.topMargin: 12
                iconName: "smartphone"
                text: qsTr("Connect your phone with a USB cable and unlock it. On Android, choose File transfer in the USB notification; on an iPhone, tap Trust. Its videos will show up here.")
            }

            Flow {
                Layout.fillWidth: true
                Layout.leftMargin: Theme.pagePadding
                Layout.rightMargin: Theme.pagePadding
                Layout.topMargin: 12
                spacing: 16

                Repeater {
                    model: VideoLibrary.phones
                    delegate: PhoneCard {}
                }
            }

            Item { implicitHeight: 32 }
        }
    }
}
