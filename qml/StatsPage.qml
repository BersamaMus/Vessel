import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// How much is watched, and when: today, this week and all time, the last 30 days, the
// hours and days of the week most of it happens in, and the most watched videos.
// Counted from when this page first came to be (the history doesn't say how long anything
// was watched for), by the player as videos play (see VideoLibrary::addWatchTime).
Item {
    id: page

    readonly property var app: ApplicationWindow.window
    // Read afresh as watching goes on (a video can play in the mini player meanwhile).
    readonly property var stats: VideoLibrary.statsRevision >= 0 && VideoLibrary.count >= 0 ? VideoLibrary.watchStats() : ({})
    readonly property bool hasStats: (stats.total ?? 0) > 0

    // "2 h 5 min", "12 min", "Under a minute".
    function span(seconds) {
        const minutes = Math.floor(seconds / 60);
        if (minutes < 1)
            return seconds > 0 ? qsTr("Under a minute") : qsTr("0 min");
        const hours = Math.floor(minutes / 60);
        if (hours === 0)
            return qsTr("%1 min").arg(minutes);
        return minutes % 60 === 0 ? qsTr("%1 h").arg(hours) : qsTr("%1 h %2 min").arg(hours).arg(minutes % 60);
    }

    // A short scale label for a chart's top line: "2 h", "45 min".
    function shortSpan(seconds) {
        return seconds >= 3600 ? qsTr("%1 h").arg(Math.round(seconds / 360) / 10) : qsTr("%1 min").arg(Math.max(1, Math.round(seconds / 60)));
    }

    function hourName(hour) {
        return Qt.formatTime(new Date(2000, 0, 1, hour), "h AP");
    }

    readonly property var weekdayNames: [qsTr("Monday"), qsTr("Tuesday"), qsTr("Wednesday"), qsTr("Thursday"),
                                         qsTr("Friday"), qsTr("Saturday"), qsTr("Sunday")]

    function busiest(values) {
        let best = -1;
        (values ?? []).forEach((value, i) => { if (value > 0 && (best < 0 || value > values[best])) best = i; });
        return best;
    }

    // What sort of watcher the hours make you, once there's enough to go on (10 minutes).
    readonly property var kind: {
        const hour = busiest(stats.hours);
        if ((stats.total ?? 0) < 600 || hour < 0)
            return null;
        if (hour >= 22 || hour < 5)
            return { title: qsTr("Night owl"), text: qsTr("Most of your watching happens late at night.") };
        if (hour < 11)
            return { title: qsTr("Early bird"), text: qsTr("You like a video with your morning.") };
        if (hour < 15)
            return { title: qsTr("Lunch-break watcher"), text: qsTr("The middle of the day is your time for videos.") };
        if (hour < 19)
            return { title: qsTr("Afternoon unwinder"), text: qsTr("You wind down with a video in the late afternoon.") };
        return { title: qsTr("Prime-time viewer"), text: qsTr("Evenings are when you sit down to watch.") };
    }

    PageBar {
        id: bar
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        scrolled: scroll.contentY > 1

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
                title: qsTr("Your stats")
                subtitle: page.stats.since ? qsTr("Since %1").arg(page.stats.since) : qsTr("Time spent watching, from now on")
            }

            TextAction {
                Layout.alignment: Qt.AlignTop
                visible: page.hasStats
                text: qsTr("Reset stats")
                onClicked: resetDialog.open()
            }
        }
    }

    // --- Pieces --------------------------------------------------------------------
    // A headline number with what it is under it.
    component Tile: Rectangle {
        id: tile
        property string value
        property string label
        property string note

        Layout.fillWidth: true
        Layout.preferredWidth: 160
        implicitHeight: tileColumn.implicitHeight + 32
        radius: Theme.radiusCard
        color: Theme.surface
        border.color: Theme.borderSoft

        Column {
            id: tileColumn
            x: 16
            y: 16
            width: parent.width - 32
            spacing: 2

            Text {
                width: parent.width
                text: tile.value
                color: Theme.textPrimary
                font.pixelSize: 26
                font.weight: Font.ExtraBold
                font.letterSpacing: -0.5
                elide: Text.ElideRight
            }
            Text {
                width: parent.width
                text: tile.label
                color: Theme.textSecondary
                font.pixelSize: 14
                elide: Text.ElideRight
            }
            Text {
                width: parent.width
                visible: tile.note !== ""
                text: tile.note
                color: Theme.textTertiary
                font.pixelSize: 12
                elide: Text.ElideRight
            }
        }
    }

    // Bars from a baseline, one series in the accent colour. Each bar says what it is when
    // hovered (its whole column can be, not just the bar). `labels` are under some of the bars
    // ({index: text}); `tips` give each bar's name for its tooltip.
    component BarChart: Item {
        id: chart

        property var values: []
        property var tips: []
        property var labels: ({})
        property int marked: -1   // drawn bolder in the labels (today)
        readonly property real peak: Math.max(1, ...values)
        readonly property real gap: 2

        Layout.fillWidth: true
        implicitHeight: 180

        // The scale: the top value, faintly, with its line.
        Text {
            id: scaleText
            text: page.shortSpan(chart.peak)
            color: Theme.textTertiary
            font.pixelSize: 12
        }
        Rectangle {
            anchors.left: parent.left
            anchors.right: parent.right
            y: plot.y
            height: 1
            color: Theme.borderSoft
        }

        Item {
            id: plot
            anchors.left: parent.left
            anchors.right: parent.right
            y: scaleText.height + 6
            height: parent.height - y - 24

            Repeater {
                model: chart.values.length
                delegate: Item {
                    id: column
                    required property int index
                    readonly property real value: chart.values[index] ?? 0
                    readonly property real slot: plot.width / chart.values.length

                    x: index * slot
                    width: slot
                    height: plot.height

                    HoverHandler { id: barHover }
                    ToolTip.visible: barHover.hovered
                    ToolTip.delay: 0
                    ToolTip.text: (chart.tips[index] ?? "") + "  •  " + page.span(value)

                    // Rounded at the top, square on the baseline.
                    Rectangle {
                        id: barTop
                        x: chart.gap / 2
                        width: Math.max(1, column.width - chart.gap)
                        height: column.value > 0 ? Math.max(3, column.height * column.value / chart.peak) : 0
                        y: column.height - height
                        radius: Math.min(4, width / 2)
                        color: barHover.hovered ? Qt.lighter(Theme.accent, 1.15) : Theme.accent
                    }
                    Rectangle {
                        x: barTop.x
                        width: barTop.width
                        height: Math.min(barTop.height, barTop.radius)
                        y: column.height - height
                        color: barTop.color
                    }
                    Rectangle {
                        anchors.fill: parent
                        z: -1
                        radius: 4
                        color: barHover.hovered ? Theme.hover : "transparent"
                    }
                }
            }
        }

        // The baseline.
        Rectangle {
            anchors.left: parent.left
            anchors.right: parent.right
            y: plot.y + plot.height
            height: 1
            color: Theme.border
        }

        Repeater {
            model: Object.keys(chart.labels)
            delegate: Text {
                required property string modelData
                readonly property int at: Number(modelData)
                x: Math.max(0, Math.min(chart.width - width, (at + 0.5) * plot.width / chart.values.length - width / 2))
                y: plot.y + plot.height + 6
                text: chart.labels[modelData]
                color: at === chart.marked ? Theme.textPrimary : Theme.textTertiary
                font.pixelSize: 12
                font.weight: at === chart.marked ? Font.DemiBold : Font.Normal
            }
        }
    }

    component SectionTitle: Text {
        Layout.topMargin: 32
        Layout.bottomMargin: 12
        color: Theme.textPrimary
        font.pixelSize: Theme.sectionSize
        font.weight: Font.Bold
    }

    component Panel: Rectangle {
        default property alias content: panelColumn.data
        property string title

        Layout.fillWidth: true
        implicitHeight: panelColumn.implicitHeight + 32
        radius: Theme.radiusCard
        color: Theme.surface
        border.color: Theme.borderSoft

        ColumnLayout {
            id: panelColumn
            x: 16
            y: 16
            width: parent.width - 32
            spacing: 12
        }
    }

    // --- The page ------------------------------------------------------------------
    PageScroll {
        id: scroll
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: bar.bottom
        anchors.bottom: parent.bottom

        ColumnLayout {
            width: scroll.availableWidth
            spacing: 0

            EmptyNote {
                visible: !page.hasStats
                Layout.topMargin: 12
                iconName: "bar_chart"
                text: qsTr("Watch something, and how much you watch, when, and what you come back to most shows up here.")
            }

            ColumnLayout {
                visible: page.hasStats
                Layout.fillWidth: true
                Layout.maximumWidth: 1100
                Layout.leftMargin: Theme.pagePadding
                Layout.rightMargin: Theme.pagePadding
                Layout.topMargin: 12
                spacing: 0

                // "Night owl: most of your watching happens late at night."
                Column {
                    Layout.fillWidth: true
                    Layout.bottomMargin: 20
                    visible: page.kind !== null
                    spacing: 2

                    Text {
                        width: parent.width
                        text: page.kind?.title ?? ""
                        color: Theme.accent
                        font.pixelSize: 30
                        font.weight: Font.ExtraBold
                        font.letterSpacing: -0.5
                    }
                    Text {
                        width: parent.width
                        text: {
                            const day = page.busiest(page.stats.weekdays);
                            return (page.kind?.text ?? "") + (day >= 0 ? " " + qsTr("Your big day is %1.").arg(page.weekdayNames[day]) : "");
                        }
                        color: Theme.textSecondary
                        font.pixelSize: 15
                        wrapMode: Text.Wrap
                    }
                }

                GridLayout {
                    Layout.fillWidth: true
                    columns: Math.max(2, Math.min(4, Math.floor(width / 200)))
                    columnSpacing: 12
                    rowSpacing: 12

                    Tile {
                        value: page.span(page.stats.today ?? 0)
                        label: qsTr("Today")
                    }
                    Tile {
                        value: page.span(page.stats.week ?? 0)
                        label: qsTr("Last 7 days")
                    }
                    Tile {
                        value: page.span(page.stats.total ?? 0)
                        label: qsTr("All time")
                        note: (page.stats.daysWatched ?? 0) === 1 ? qsTr("On 1 day") : qsTr("On %1 days").arg(page.stats.daysWatched ?? 0)
                    }
                    Tile {
                        value: (page.stats.streak ?? 0) === 1 ? qsTr("1 day") : qsTr("%1 days").arg(page.stats.streak ?? 0)
                        label: qsTr("Streak")
                        note: qsTr("Longest: %1").arg((page.stats.longestStreak ?? 0) === 1 ? qsTr("1 day") : qsTr("%1 days").arg(page.stats.longestStreak ?? 0))
                    }
                    Tile {
                        value: page.stats.plays ?? 0
                        label: qsTr("Videos played")
                    }
                    Tile {
                        value: page.stats.finished ?? 0
                        label: qsTr("Watched to the end")
                    }
                    Tile {
                        value: page.span(page.stats.bestDay?.seconds ?? 0)
                        label: qsTr("Biggest day")
                        note: page.stats.bestDay?.label ?? ""
                    }
                    Tile {
                        readonly property int hour: page.busiest(page.stats.hours)
                        value: hour >= 0 ? page.hourName(hour) : "–"
                        label: qsTr("Favourite hour")
                    }
                }

                SectionTitle { text: qsTr("Last 30 days") }
                Panel {
                    BarChart {
                        values: (page.stats.days ?? []).map(day => day.seconds)
                        tips: (page.stats.days ?? []).map(day => day.label)
                        // Every 7th day back from today, and today.
                        labels: {
                            const result = {};
                            (page.stats.days ?? []).forEach((day, i, all) => {
                                if ((all.length - 1 - i) % 7 === 0)
                                    result[i] = day.today ? qsTr("Today") : day.label;
                            });
                            return result;
                        }
                        marked: (page.stats.days ?? []).length - 1
                    }
                }

                SectionTitle { text: qsTr("When you watch") }
                GridLayout {
                    Layout.fillWidth: true
                    columns: width >= 760 ? 2 : 1
                    columnSpacing: 12
                    rowSpacing: 12

                    Panel {
                        Text {
                            text: qsTr("Time of day")
                            color: Theme.textPrimary
                            font.pixelSize: Theme.subsectionSize
                            font.weight: Font.Bold
                        }
                        BarChart {
                            values: page.stats.hours ?? []
                            tips: Array.from({ length: 24 }, (_, h) => qsTr("%1 to %2").arg(page.hourName(h)).arg(page.hourName((h + 1) % 24)))
                            labels: ({ 0: page.hourName(0), 6: page.hourName(6), 12: page.hourName(12), 18: page.hourName(18) })
                        }
                    }
                    Panel {
                        Text {
                            text: qsTr("Day of the week")
                            color: Theme.textPrimary
                            font.pixelSize: Theme.subsectionSize
                            font.weight: Font.Bold
                        }
                        BarChart {
                            values: page.stats.weekdays ?? []
                            tips: page.weekdayNames
                            labels: {
                                const result = {};
                                page.weekdayNames.forEach((name, i) => result[i] = name.slice(0, 3));
                                return result;
                            }
                        }
                    }
                }

                SectionTitle {
                    visible: (page.stats.top ?? []).length > 0
                    text: qsTr("Most watched")
                }
                Repeater {
                    model: page.stats.top ?? []
                    delegate: PickRow {
                        required property var modelData
                        required property int index
                        Layout.fillWidth: true
                        Layout.maximumWidth: 900
                        thumbWidth: 160
                        titleLines: 2
                        enabled: modelData.path !== ""
                        path: modelData.path
                        title: (index + 1) + ".  " + modelData.title
                        subtitle: [qsTr("%1 watched").arg(page.span(modelData.seconds)),
                                   modelData.plays === 1 ? qsTr("played once") : modelData.plays > 1 ? qsTr("played %1 times").arg(modelData.plays) : "",
                                   modelData.path === "" ? qsTr("not here now") : ""]
                                  .filter(part => part).join("  •  ")
                        thumbnail: modelData.thumbnail
                        onClicked: page.app.play(modelData.path)
                    }
                }
            }

            Item { implicitHeight: 32 }
        }
    }

    AppDialog {
        id: resetDialog
        title: qsTr("Reset stats?")
        text: qsTr("All the watch time counted so far is forgotten, and counting starts again. Your history stays as it is.")
        confirmText: qsTr("Reset")
        onConfirmed: {
            VideoLibrary.clearStats();
            page.app.toast(qsTr("Stats reset"), { icon: "bar_chart" });
        }
    }
}
