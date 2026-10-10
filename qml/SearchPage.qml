import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// Search the library and connected drives by title or folder name, with the
// Library's sort and filters. Recent searches show until something is typed.
ScrollView {
    id: page

    readonly property var app: ApplicationWindow.window
    readonly property string query: field.text.trim()
    // Results show once there's something to search for, or a filter to list by.
    readonly property bool showResults: query !== "" || filterBar.filtered
    readonly property int columns: availableWidth > 1100 ? 2 : 1
    readonly property real rowWidth: Math.floor((availableWidth - 2 * Theme.pagePadding - (columns - 1) * 16) / columns)

    function focusField() {
        field.forceActiveFocus();
        field.selectAll();
    }

    // Remembered once the user acts on what they found.
    function commit() {
        if (query !== "")
            app.addRecentSearch(query);
    }

    contentWidth: availableWidth
    clip: true
    Component.onCompleted: field.forceActiveFocus()

    VideoQuery {
        id: results
        everywhere: true
        sort: "relevance"
        text: page.query
    }

    ColumnLayout {
        width: page.availableWidth
        spacing: 0

        RowLayout {
            Layout.fillWidth: true
            Layout.leftMargin: page.StackView.index > 0 ? 8 : Theme.pagePadding
            Layout.rightMargin: Theme.pagePadding
            Layout.topMargin: 14
            spacing: 8

            IconButton {
                visible: page.StackView.index > 0
                iconName: "back"
                tip: qsTr("Back")
                onClicked: page.StackView.view.pop()
            }

            // The search box.
            Rectangle {
                Layout.fillWidth: true
                Layout.maximumWidth: 720
                implicitHeight: 48
                radius: height / 2
                color: Theme.surfaceHigh
                border.color: field.activeFocus ? "#80FACC15" : "transparent"

                RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 16
                    anchors.rightMargin: 4
                    spacing: 10

                    Icon {
                        name: "search"
                        color: Theme.textSecondary
                    }
                    TextField {
                        id: field
                        Layout.fillWidth: true
                        placeholderText: qsTr("Search videos and folders")
                        placeholderTextColor: Theme.textTertiary
                        color: Theme.textPrimary
                        selectionColor: Theme.accent
                        selectedTextColor: Theme.accentText
                        font.pixelSize: 17
                        background: null
                        leftPadding: 0
                        onAccepted: page.commit()
                        Keys.onEscapePressed: {
                            if (text !== "")
                                clear();
                            else if (page.StackView.index > 0)
                                page.StackView.view.pop();
                        }
                    }
                    IconButton {
                        visible: field.text !== ""
                        iconName: "close"
                        iconColor: Theme.textSecondary
                        tip: qsTr("Clear")
                        onClicked: {
                            field.clear();
                            field.forceActiveFocus();
                        }
                    }
                }
            }
        }

        FilterBar {
            id: filterBar
            Layout.fillWidth: true
            Layout.leftMargin: Theme.pagePadding
            Layout.rightMargin: Theme.pagePadding
            Layout.topMargin: 16
            query: results
            searching: true
        }

        // --- Recent searches ---------------------------------------------
        EmptyNote {
            visible: !page.showResults && page.app.recentSearches.length === 0
            Layout.topMargin: 24
            iconName: "search"
            text: qsTr("Find videos in your library and on connected drives by title or folder name, or list them by a filter.")
        }

        Text {
            visible: !page.showResults && page.app.recentSearches.length > 0
            Layout.leftMargin: Theme.pagePadding
            Layout.topMargin: 24
            Layout.bottomMargin: 8
            text: qsTr("Recent searches")
            color: Theme.textPrimary
            font.pixelSize: 20
            font.weight: Font.Bold
        }

        Repeater {
            model: page.showResults ? [] : page.app.recentSearches
            delegate: AbstractButton {
                id: recent
                required property string modelData
                Layout.fillWidth: true
                Layout.maximumWidth: 720 + Theme.pagePadding
                Layout.leftMargin: Theme.pagePadding - 8
                implicitHeight: 48
                hoverEnabled: true
                focusPolicy: Qt.NoFocus
                onClicked: {
                    field.text = modelData;
                    field.forceActiveFocus();
                }
                HoverHandler { cursorShape: Qt.PointingHandCursor }

                background: Rectangle {
                    radius: Theme.radiusArt
                    color: recent.pressed ? Theme.pressed : recent.hovered ? Theme.hover : "transparent"
                }
                contentItem: RowLayout {
                    spacing: 14
                    Item { implicitWidth: 0 }
                    Icon {
                        name: "history"
                        color: Theme.textSecondary
                    }
                    Text {
                        Layout.fillWidth: true
                        text: recent.modelData
                        color: Theme.textPrimary
                        font.pixelSize: 15
                        elide: Text.ElideRight
                    }
                    IconButton {
                        iconName: "close"
                        iconColor: Theme.textSecondary
                        iconSize: 20
                        tip: qsTr("Remove")
                        onClicked: page.app.removeRecentSearch(recent.modelData)
                    }
                }
            }
        }

        // --- Results -------------------------------------------------------
        Text {
            visible: page.showResults && results.count > 0
            Layout.leftMargin: Theme.pagePadding
            Layout.topMargin: 20
            Layout.bottomMargin: 6
            text: results.count === 1 ? qsTr("1 video") : qsTr("%1 videos").arg(results.count)
            color: Theme.textSecondary
            font.pixelSize: 14
        }

        EmptyNote {
            visible: page.showResults && results.count === 0
            Layout.topMargin: 24
            iconName: "search"
            text: page.query !== "" ? qsTr("No videos match \"%1\".").arg(page.query)
                                    : qsTr("No videos match these filters.")
        }

        Flow {
            Layout.fillWidth: true
            Layout.leftMargin: Theme.pagePadding - 8
            Layout.rightMargin: Theme.pagePadding - 8
            visible: page.showResults
            spacing: 0

            Repeater {
                model: page.showResults ? results : null
                delegate: PickRow {
                    required property var model
                    width: page.rowWidth + 16
                    thumbWidth: 176
                    titleLines: 2
                    title: model.title
                    subtitle: [model.subtitle, model.detail, model.remainingText].filter(part => part).join("  •  ")
                    thumbnail: model.thumbnail
                    durationText: model.durationText
                    progress: model.progress
                    onClicked: {
                        page.commit();
                        page.app.play(model.path);
                    }
                }
            }
        }

        Item { implicitHeight: 32 }
    }
}
