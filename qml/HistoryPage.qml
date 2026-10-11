import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// Everything watched (finished or not) that's here now, newest first, under the day it
// was watched. Each can be taken out of the history; all of it can be cleared.
Item {
    id: page

    readonly property var app: ApplicationWindow.window
    readonly property var history: VideoLibrary.history

    clip: true

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        PageBar {
            Layout.fillWidth: true
            scrolled: !list.atYBeginning

            RowLayout {
                Layout.fillWidth: true
                Layout.leftMargin: 8
                Layout.rightMargin: Theme.pagePadding
                Layout.topMargin: 14
                spacing: 8

                IconButton {
                    Layout.alignment: Qt.AlignTop
                    iconName: "back"
                    tip: qsTr("Back (Esc)")
                    onClicked: page.StackView.view.pop()
                }

                PageTitle {
                    title: qsTr("History")
                    subtitle: page.history.count === 1 ? qsTr("1 video") : qsTr("%1 videos").arg(page.history.count)
                }

                TextAction {
                    Layout.alignment: Qt.AlignTop
                    visible: page.history.count > 0
                    text: qsTr("Clear history")
                    onClicked: clearDialog.open()
                }
            }
        }

        EmptyNote {
            visible: page.history.count === 0
            Layout.topMargin: 24
            iconName: "history"
            text: qsTr("Videos you watch show up here.")
        }

        ListView {
            id: list
            SmoothWheel { flickable: list }

            Layout.fillWidth: true
            Layout.fillHeight: true
            visible: page.history.count > 0
            model: page.history
            boundsBehavior: Flickable.StopAtBounds
            leftMargin: Theme.pagePadding - 8
            rightMargin: Theme.pagePadding - 8
            bottomMargin: 24
            reuseItems: true
            ScrollBar.vertical: ScrollBar {}

            section.property: "detail"
            section.delegate: Text {
                required property string section
                width: list.width
                leftPadding: 8
                topPadding: 20
                bottomPadding: 8
                text: section
                color: Theme.textPrimary
                font.pixelSize: Theme.sectionSize
                font.weight: Font.Bold
            }

            delegate: PickRow {
                id: row
                required property var model
                width: Math.min(list.width - list.leftMargin - list.rightMargin, 900)
                thumbWidth: 160
                titleLines: 2
                path: model.path
                title: model.title
                subtitle: [model.subtitle, model.finished ? qsTr("Watched") : model.remainingText]
                          .filter(part => part).join("  •  ")
                thumbnail: model.thumbnail
                loading: model.thumbnailPending
                durationText: model.durationText
                progress: model.progress
                watched: model.finished
                onClicked: page.app.play(model.path)

                IconButton {
                    anchors.right: parent.right
                    anchors.rightMargin: 8
                    anchors.verticalCenter: parent.verticalCenter
                    visible: row.hovered || hovered
                    iconName: "close"
                    iconColor: Theme.textSecondary
                    tip: qsTr("Remove from history")
                    onClicked: page.app.removeFromHistory(row.model.path)
                }
            }
        }
    }

    AppDialog {
        id: clearDialog
        title: qsTr("Clear history?")
        text: qsTr("Every video's progress is forgotten, and Continue watching is emptied. Your videos stay where they are.")
        confirmText: qsTr("Clear")
        onConfirmed: {
            VideoLibrary.clearHistory();
            page.app.toast(qsTr("History cleared"), { icon: "history" });
        }
    }
}
