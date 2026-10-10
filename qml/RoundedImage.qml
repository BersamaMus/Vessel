import QtQuick
import QtQuick.Shapes

// A thumbnail with rounded corners; shows a placeholder until it has loaded,
// pulsing while the thumbnail is still being made.
//
// The image fills a rounded Shape directly (ShapePath.fillItem), cropped to cover
// it. Unlike a mask effect, that needs no offscreen layers per card, which matters
// with a screenful of them being made and dropped while scrolling.
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

    // Laid out to cover the box (scaled up, centred, overflowing it); the Shape below
    // shows the part inside the rounded corners.
    Image {
        id: image

        readonly property real coverScale: implicitWidth > 0 ? Math.max(root.width / implicitWidth, root.height / implicitHeight) : 1

        source: root.source
        asynchronous: true
        // Decoded about as big as it's shown (in 80 px steps, so a resize rarely reloads it),
        // rather than at the thumbnail's full 480 px.
        sourceSize.width: Math.min(480, Math.max(80, Math.ceil(root.width * Screen.devicePixelRatio / 80) * 80))
        width: implicitWidth * coverScale
        height: implicitHeight * coverScale
        x: (root.width - width) / 2
        y: (root.height - height) / 2
        visible: false
    }

    Shape {
        anchors.fill: parent
        preferredRendererType: Shape.CurveRenderer
        opacity: image.status === Image.Ready ? 1 : 0
        Behavior on opacity { NumberAnimation { duration: 200 } }

        ShapePath {
            strokeWidth: -1
            fillItem: image
            // From the decoded image's pixels to where the image is laid out.
            fillTransform: PlanarTransform.fromTranslate(image.x, image.y).times(
                               PlanarTransform.fromScale(image.coverScale, image.coverScale))
            PathRectangle { width: root.width; height: root.height; radius: root.radius }
        }
    }
}
