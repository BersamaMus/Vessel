import QtQuick
import QtQuick.Controls.Basic

// Sort and filter menus for a VideoQuery: the Library's folders, and Search.
Flow {
    id: bar

    required property VideoQuery query
    // Search adds "Relevance" (best match first) to the sort menu.
    property bool searching: false
    readonly property bool filtered: query.length !== "" || query.progress !== ""
                                     || query.type !== "" || query.resolution !== ""

    function clearFilters() {
        query.length = "";
        query.progress = "";
        query.type = "";
        query.resolution = "";
    }

    spacing: 8

    DropButton {
        iconName: "sort"
        current: bar.query.sort
        options: (bar.searching ? [{ value: "relevance", label: qsTr("Relevance") }] : []).concat([
            { value: "name", label: qsTr("Name") },
            { value: "added", label: qsTr("Recently added") },
            { value: "watched", label: qsTr("Recently watched") },
            { value: "year", label: qsTr("Release year") },
            { value: "duration", label: qsTr("Longest first") },
            { value: "size", label: qsTr("Largest first") },
        ])
        onChosen: value => bar.query.sort = value
    }

    // A gap between the sort and the filters.
    Item { width: 4; height: 1 }

    DropButton {
        placeholder: qsTr("Length")
        current: bar.query.length
        options: [
            { value: "", label: qsTr("Any length") },
            { value: "short", label: qsTr("Under 5 minutes") },
            { value: "medium", label: qsTr("5–30 minutes") },
            { value: "long", label: qsTr("30–90 minutes") },
            { value: "feature", label: qsTr("Over 90 minutes") },
        ]
        onChosen: value => bar.query.length = value
    }

    DropButton {
        placeholder: qsTr("Progress")
        current: bar.query.progress
        options: [
            { value: "", label: qsTr("Any progress") },
            { value: "unwatched", label: qsTr("Not watched") },
            { value: "started", label: qsTr("In progress") },
            { value: "watched", label: qsTr("Watched") },
        ]
        onChosen: value => bar.query.progress = value
    }

    DropButton {
        placeholder: qsTr("Type")
        current: bar.query.type
        options: [{ value: "", label: qsTr("Any type") }]
                 .concat(bar.query.types.map(type => ({ value: type, label: type.toUpperCase() })))
        onChosen: value => bar.query.type = value
    }

    DropButton {
        placeholder: qsTr("Resolution")
        current: bar.query.resolution
        options: [
            { value: "", label: qsTr("Any resolution") },
            { value: "4k", label: qsTr("4K") },
            { value: "1440", label: qsTr("1440p") },
            { value: "1080", label: qsTr("1080p") },
            { value: "720", label: qsTr("720p") },
            { value: "sd", label: qsTr("Below 720p") },
        ]
        onChosen: value => bar.query.resolution = value
    }

    TextAction {
        visible: bar.filtered
        text: qsTr("Clear filters")
        iconName: "close"
        onClicked: bar.clearFilters()
    }
}
