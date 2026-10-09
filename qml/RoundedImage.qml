import QtQuick
import QtQuick.Effects

// A thumbnail with rounded corners; shows a placeholder until it has loaded.
Item {
    id: root

    property url source
    property real radius: Theme.radiusCard
    property string placeholderIcon: "movie"

    Rectangle {
        anchors.fill: parent
        radius: root.radius
        color: Theme.surfaceHigh

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
