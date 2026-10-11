import QtQuick
import QtQuick.Shapes

// The app's mark: a sailboat (a vessel) whose sail is a strip of film, with sprocket holes
// along the mast and a play symbol in the middle, on a rounded square. Also drawn into the
// exe's icon (icon/make-icon.ps1), so it takes its colours rather than reading Theme.
Rectangle {
    id: mark

    property real size: 30
    property color tileColor: "#C9A7FF"
    property color inkColor: "#1E0B3D"

    width: size
    height: size
    radius: size * 0.3
    color: tileColor

    // Drawn on a 24-unit grid, scaled to fit.
    Shape {
        anchors.centerIn: parent
        width: 24
        height: 24
        scale: mark.size / 28
        preferredRendererType: Shape.CurveRenderer

        // The sail, its corners softened by the stroke.
        ShapePath {
            fillColor: mark.inkColor
            strokeColor: mark.inkColor
            strokeWidth: 1.2
            joinStyle: ShapePath.RoundJoin
            startX: 7; startY: 2.5
            PathLine { x: 19; y: 15 }
            PathLine { x: 7; y: 15 }
            PathLine { x: 7; y: 2.5 }
        }

        // Cut out of it: the film's sprocket holes along the mast, and a play symbol.
        ShapePath {
            fillColor: mark.tileColor
            strokeColor: "transparent"
            PathRectangle { x: 8.2; y: 6.3; width: 1.5; height: 1.5; radius: 0.35 }
            PathRectangle { x: 8.2; y: 9.3; width: 1.5; height: 1.5; radius: 0.35 }
            PathRectangle { x: 8.2; y: 12.3; width: 1.5; height: 1.5; radius: 0.35 }
        }
        ShapePath {
            fillColor: mark.tileColor
            strokeColor: mark.tileColor
            strokeWidth: 0.6
            joinStyle: ShapePath.RoundJoin
            startX: 11.4; startY: 10
            PathLine { x: 15; y: 12 }
            PathLine { x: 11.4; y: 14 }
            PathLine { x: 11.4; y: 10 }
        }

        // The hull: a shallow bowl under it, a little apart, a shade lighter.
        ShapePath {
            fillColor: Qt.rgba(mark.inkColor.r, mark.inkColor.g, mark.inkColor.b, 0.72)
            strokeColor: "transparent"
            startX: 3; startY: 16.5
            PathLine { x: 21; y: 16.5 }
            PathQuad { x: 15; y: 20.5; controlX: 19.5; controlY: 20.5 }
            PathLine { x: 9; y: 20.5 }
            PathQuad { x: 3; y: 16.5; controlX: 4.5; controlY: 20.5 }
        }
    }
}
