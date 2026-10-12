import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// The buttons at the top right of each tab: History, Search, Open a file, and More.
RowLayout {
    id: actions

    property string searchTip: qsTr("Search (Ctrl+F)")
    property bool searchVisible: true

    readonly property var app: ApplicationWindow.window

    Layout.alignment: Qt.AlignTop
    spacing: 4

    IconButton {
        iconName: "history"
        tip: qsTr("History")
        onClicked: actions.app.openHistory()
    }
    IconButton {
        visible: actions.searchVisible
        iconName: "search"
        tip: actions.searchTip
        onClicked: actions.app.openSearch()
    }
    IconButton {
        iconName: "folder_open"
        tip: qsTr("Open a file (Ctrl+O)")
        onClicked: actions.app.openFileDialog()
    }
    IconButton {
        iconName: "more"
        tip: qsTr("More")
        onClicked: menu.popup()

        AppMenu {
            id: menu
            AppMenuItem {
                text: VideoLibrary.scanning ? qsTr("Scanning…") : qsTr("Rescan library")
                enabled: !VideoLibrary.scanning
                onTriggered: actions.app.rescanLibrary()
            }
            AppMenuItem {
                text: qsTr("Settings")
                onTriggered: actions.app.showTab(5)
            }
        }
    }
}
