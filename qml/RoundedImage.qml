import QtQuick
import QtQuick.Effects

// A thumbnail with rounded corners; shows a placeholder until it has loaded,
// pulsing while the thumbnail is still being made.
Item {
    id: root

    property url source
    property real radius: Theme.radiusCard
    property string placeholderIcon: "movie"
    property bool loading: false

    Rectangle {
        anchors.fill: parent
        radius: root.radius
        color: Theme.surfaceHigh

        SequentialAnimation on opacity {
            running: root.loading && image.status !== Image.Ready
            loops: Animation.Infinite
            onRunningChanged: if (!running) parent.opacity = 1
            NumberAnimation { to: 0.45; duration: 700; easing.type: Easing.InOutSine }
            NumberAnimation { to: 1; duration: 700; easing.type: Easing.InOutSine }
        }

        Icon {
            anchors.centerIn: parent
            visible: image.status !== Image.Ready
            name: root.placeholderIcon
            size: Math.min(parent.height * 0.35, 40)
            color: Theme.textTertiary
        }
    }

    Image {
        id: image
        anchors.fill: parent
        source: root.source
        fillMode: Image.PreserveAspectCrop
        asynchronous: true
        sourceSize.width: 480
        visible: false
    }

    MultiEffect {
        anchors.fill: parent
        source: image
        maskEnabled: true
        maskSource: mask
        maskThresholdMin: 0.5
        maskSpreadAtMin: 1.0
        opacity: image.status === Image.Ready ? 1 : 0
        Behavior on opacity { NumberAnimation { duration: 200 } }
    }

    Item {
        id: mask
        anchors.fill: parent
        layer.enabled: true
        layer.smooth: true
        visible: false
        Rectangle {
            anchors.fill: parent
            radius: root.radius
        }
    }
}
