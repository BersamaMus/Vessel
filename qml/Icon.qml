import QtQuick
import QtQuick.Shapes

import "Icons.js" as Icons

// A Material icon drawn as a vector shape, so it can take any colour and size.
Item {
    id: root

    property string name
    property color color: Theme.textPrimary
    property real size: 24

    implicitWidth: size
    implicitHeight: size

    Shape {
        anchors.centerIn: parent
        width: 24
        height: 24
        scale: root.size / 24
        preferredRendererType: Shape.CurveRenderer

        ShapePath {
            fillColor: root.color
            fillRule: ShapePath.WindingFill
            strokeColor: "transparent"
            PathSvg { path: Icons.paths[root.name] ?? "" }
        }
    }
}
