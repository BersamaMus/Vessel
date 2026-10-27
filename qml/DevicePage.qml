import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// What the Phone and External pages have in common: a bar with the title and search that
// stays put while the page scrolls, and pills under the title that switch between the
// devices' cards (whatever the page puts inside this) and every video on them in one grid.
Item {
    id: devices

    // "phones" or "drives", for the VideoQuery.
    property alias source: results.source
    property string title
    property string subtitle
    property string searchTip
    // The cards view's pill, e.g. { value: "phones", label: qsTr("Phones") }.
    property var cardsTab
    // The view chosen (remembered by the app): cardsTab.value or "videos".
    property string view
    // There's something to look through (a drive, or a phone that's unlocked).
    property bool available
    // When there are no videos: the icon, and what to say with and without filters on.
    property string emptyIcon
    property string emptyText
    property string noMatchText
    // The cards view is scrolled (the bar then shows its shadow).
    property bool cardsScrolled
    // The cards.
    default property alias cards: cardsArea.data

    signal viewChosen(string value)

    readonly property var app: ApplicationWindow.window
    readonly property bool listing: view === "videos" && available

    VideoQuery {
        id: results
        active: devices.listing
    }

    PageBar {
        id: bar
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        scrolled: devices.listing ? !grid.atYBeginning : devices.cardsScrolled

        RowLayout {
            Layout.fillWidth: true
            Layout.leftMargin: Theme.pagePadding
            Layout.rightMargin: Theme.pagePadding - 8
            Layout.topMargin: 14
            spacing: 8

            ColumnLayout {
                Layout.fillWidth: true
                spacing: 0

                PageTitle {
                    title: devices.title
                    subtitle: devices.subtitle
                }
                // What's listed, in the order shown.
                Row {
                    Layout.topMargin: 6
                    Layout.leftMargin: -12   // the icons line up with the title
                    visible: devices.listing && results.count > 0
                    spacing: 4

                    TextAction {
                        text: qsTr("Play all")
                        iconName: "play"
                        onClicked: devices.app.playAll(results.paths(), devices.title, false)
                    }
                    TextAction {
                        text: qsTr("Shuffle")
                        iconName: "shuffle"
                        onClicked: devices.app.playAll(results.paths(), devices.title, true)
                    }
                }
            }

            // Search looks through every one connected.
            AppActions {
                Layout.alignment: Qt.AlignTop
                searchVisible: devices.available
                searchTip: devices.searchTip
            }
        }

        ViewTabs {
            Layout.leftMargin: Theme.pagePadding
            Layout.topMargin: 16
            visible: devices.available
            options: [devices.cardsTab, { value: "videos", label: qsTr("Videos") }]
            current: devices.listing ? "videos" : devices.cardsTab.value
            onChosen: value => devices.viewChosen(value)
        }

        FilterBar {
            Layout.fillWidth: true
            Layout.leftMargin: Theme.pagePadding
            Layout.rightMargin: Theme.pagePadding
            Layout.topMargin: 12
            visible: devices.listing && results.types.length > 0
            query: results
        }
    }

    VideoGrid {
        id: grid
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: bar.bottom
        anchors.bottom: parent.bottom
        visible: devices.listing
        model: devices.listing ? results : null
        // Which device and folder each one is in, or what it's sorted by.
        detailed: true

        header: ColumnLayout {
            width: grid.width
            spacing: 0

            Text {
                visible: results.count > 0
                Layout.leftMargin: Theme.pagePadding
                Layout.topMargin: 12
                Layout.bottomMargin: 12
                text: results.count === 1 ? qsTr("1 video") : qsTr("%1 videos").arg(results.count)
                color: Theme.textPrimary
                font.pixelSize: Theme.sectionSize
                font.weight: Font.Bold
            }

            EmptyNote {
                visible: results.count === 0
                Layout.topMargin: 12
                iconName: results.filtered ? "search" : devices.emptyIcon
                text: results.filtered ? devices.noMatchText : devices.emptyText
            }
        }
    }

    Item {
        id: cardsArea
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: bar.bottom
        anchors.bottom: parent.bottom
        visible: !devices.listing
    }
}
