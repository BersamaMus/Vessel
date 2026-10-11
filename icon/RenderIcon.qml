import QtQuick
import QtQuick.Window
import "../qml"

// Draws the app's mark (qml/LogoMark.qml) at each of the icon's sizes and saves them as
// PNGs, named by size, into the folder given as the last argument. Run by make-icon.ps1.
Window {
    id: window

    readonly property var sizes: [16, 20, 24, 32, 40, 48, 64, 96, 128, 256]
    readonly property string outDir: Qt.application.arguments[Qt.application.arguments.length - 1]

    width: 900
    height: 300
    visible: true
    color: "transparent"

    Row {
        spacing: 8
        Repeater {
            id: marks
            model: window.sizes
            // Each drawn at its own size, so the small ones stay sharp.
            delegate: LogoMark {
                required property int modelData
                size: modelData
            }
        }
    }

    // One after another, then quit.
    function save(index) {
        if (index >= sizes.length) {
            Qt.quit();
            return;
        }
        marks.itemAt(index).grabToImage(result => {
            result.saveToFile(outDir + "/" + sizes[index] + ".png");
            save(index + 1);
        });
    }

    Timer {
        interval: 600
        running: true
        onTriggered: window.save(0)
    }
}
