import QtQuick

// The app's mark: a yellow rounded square with a play symbol, then the name.
Row {
    spacing: 10

    Rectangle {
        anchors.verticalCenter: parent.verticalCenter
        width: 30
        height: 30
        radius: 9
        color: Theme.accent
        Icon {
            anchors.centerIn: parent
            name: "play"
            size: 22
            color: "#111111"
        }
    }

    Text {
        anchors.verticalCenter: parent.verticalCenter
        text: qsTr("Better Video Player")
        color: Theme.textPrimary
        font.pixelSize: 22
        font.weight: Font.ExtraBold
        font.letterSpacing: -0.5
    }
}
