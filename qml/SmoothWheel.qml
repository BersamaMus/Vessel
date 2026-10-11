import QtQuick

// Mouse-wheel scrolling that glides a fixed distance per notch and stops, as in a web
// browser, instead of Flickable's own wheel flick (which coasts on for about a second and
// feels laggy). Declare it inside the Flickable (or ScrollView) it scrolls. Touchpads keep
// Flickable's own handling, which follows the fingers.
WheelHandler {
    id: handler

    required property Flickable flickable
    // Scroll sideways (contentX) instead of up and down; the wheel still turns the usual way.
    property bool horizontal: false
    // Pixels per notch: three lines, as Windows scrolls by default.
    property real step: 120
    // Where the glide is heading; notches in quick succession add up.
    property real destination: 0

    readonly property real minPos: horizontal ? flickable.originX - flickable.leftMargin
                                              : flickable.originY - flickable.topMargin
    readonly property real maxPos: horizontal
        ? Math.max(minPos, flickable.originX + flickable.contentWidth + flickable.rightMargin - flickable.width)
        : Math.max(minPos, flickable.originY + flickable.contentHeight + flickable.bottomMargin - flickable.height)

    // On the Flickable itself: declared inside one, a handler lands on its contentItem, and
    // the Flickable takes wheel events before its children see them.
    parent: flickable
    target: null
    acceptedDevices: PointerDevice.Mouse
    orientation: Qt.Vertical

    onWheel: event => {
        const position = horizontal ? flickable.contentX : flickable.contentY;
        if (!glide.running)
            destination = position;
        flickable.cancelFlick();
        destination = Math.max(minPos, Math.min(maxPos, destination - event.angleDelta.y / 120 * step));
        glide.to = destination;
        glide.restart();
    }

    property NumberAnimation glide: NumberAnimation {
        target: handler.flickable
        property: handler.horizontal ? "contentX" : "contentY"
        duration: 160
        easing.type: Easing.OutCubic
    }
}
