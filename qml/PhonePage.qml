import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// Phones connected by USB, under a bar with the title and search that stays put
// while the page scrolls. A phone only shows its videos once it's unlocked and (on
// Android) set to File transfer; until then its card says so. The pills under the
// title switch between the phones' cards and every video on them in one grid.
DevicePage {
    id: phones

    source: "phones"
    title: qsTr("Phone")
    subtitle: qsTr("Phones connected with a USB cable")
    searchTip: qsTr("Search your phones (Ctrl+F)")
    cardsTab: ({ value: "phones", label: qsTr("Phones") })
    view: app !== null ? app.phoneView : ""
    onViewChosen: value => app.phoneView = value
    available: VideoLibrary.phones.some(phone => phone.ready)
    emptyIcon: "smartphone"
    emptyText: qsTr("No videos found on your phones.")
    noMatchText: qsTr("No videos on your phones match these filters.")
    cardsScrolled: page.contentY > 1

    PageScroll {
        id: page

        readonly property var app: ApplicationWindow.window

        anchors.fill: parent

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
            TapHandler {
                acceptedButtons: Qt.RightButton
                enabled: card.ready
                onTapped: page.app.showFolderMenu(card.modelData.path)
            }

            background: Rectangle {
                radius: Theme.radiusCard
                color: card.pressed ? Theme.surfacePressed : card.hovered && card.ready ? Theme.surfaceHigh : Theme.surface
                border.color: Theme.borderSoft
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
                        text: qsTr("Unlock your phone, then:
On Android, choose File transfer in the USB notification.
On an iPhone, tap Allow (or Trust).")
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
                        color: Theme.borderStrong
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
                text: qsTr("Connect your phone with a USB cable and unlock it, and its videos will show up here.\nOn Android, choose File transfer in the USB notification.\nOn an iPhone, tap Allow (or Trust).")
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
