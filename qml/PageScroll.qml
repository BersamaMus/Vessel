import QtQuick
import QtQuick.Controls.Basic

// A page that scrolls up and down. Used instead of ScrollView, which ignores mouse drags on
// the desktop: here the page can also be clicked and swiped, like a list on a phone. Give it
// one child that is availableWidth wide; that child's height sets how far the page scrolls.
Flickable {
    id: flick

    readonly property real availableWidth: width

    contentWidth: width
    contentHeight: contentItem.childrenRect.height
    flickableDirection: Flickable.VerticalFlick
    boundsBehavior: Flickable.StopAtBounds
    clip: true

    ScrollBar.vertical: ScrollBar {}
    SmoothWheel { flickable: flick }
}
