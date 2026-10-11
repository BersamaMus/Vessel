import QtQuick

// The app's mark (see LogoMark), then the name.
Row {
    spacing: 10

    LogoMark {
        anchors.verticalCenter: parent.verticalCenter
        size: 30
        tileColor: Theme.accent
        inkColor: Theme.accentText
    }

    Text {
        anchors.verticalCenter: parent.verticalCenter
        text: qsTr("Vessel")
        color: Theme.textPrimary
        font.pixelSize: 22
        font.weight: Font.ExtraBold
        font.letterSpacing: -0.5
    }
}
