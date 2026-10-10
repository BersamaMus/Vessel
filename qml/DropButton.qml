import QtQml.Models
import QtQuick
import QtQuick.Controls.Basic

// A pill that opens a menu of choices and shows the chosen one: the sort and
// filter menus. Yellow while a filter is on.
AbstractButton {
    id: button

    // [{ value, label }]; the first is usually "any" (value "").
    property var options: []
    property string current
    // Shown while `current` is "" (a filter that's off): "Length".
    property string placeholder
    property string iconName
    readonly property bool active: current !== "" && placeholder !== ""

    signal chosen(string value)

    text: {
        const option = options.find(o => o.value === current);
        return current === "" && placeholder !== "" ? placeholder : (option ? option.label : current);
    }
    hoverEnabled: true
    focusPolicy: Qt.NoFocus
    leftPadding: iconName !== "" ? 12 : 16
    rightPadding: 10
    topPadding: 7
    bottomPadding: 7
    onClicked: menu.popup(button, 0, button.height + 6)

    HoverHandler { cursorShape: Qt.PointingHandCursor }

    background: Rectangle {
        radius: height / 2
        color: button.active ? (button.hovered ? "#47FACC15" : Theme.accentSoft)
             : button.pressed ? Theme.pressed : button.hovered ? "#29FFFFFF" : Theme.surfaceHigh
        border.color: button.active ? "#66FACC15" : "transparent"
    }

    contentItem: Row {
        spacing: 6
        Icon {
            visible: button.iconName !== ""
            anchors.verticalCenter: parent.verticalCenter
            name: button.iconName
            size: 18
            color: button.active ? Theme.accent : Theme.textSecondary
        }
        Text {
            anchors.verticalCenter: parent.verticalCenter
            text: button.text
            color: button.active ? Theme.accent : Theme.textPrimary
            font.pixelSize: 14
            font.weight: Font.DemiBold
        }
        Icon {
            anchors.verticalCenter: parent.verticalCenter
            name: "expand_more"
            size: 18
            color: button.active ? Theme.accent : Theme.textSecondary
        }
    }

    AppMenu {
        id: menu

        Instantiator {
            model: button.options
            delegate: AppMenuItem {
                required property var modelData
                text: modelData.label
                checkable: true
                checked: modelData.value === button.current
                onTriggered: {
                    button.chosen(modelData.value);
                    // Clicking toggled it; the tick follows the choice instead.
                    checked = Qt.binding(() => modelData.value === button.current);
                }
            }
            onObjectAdded: (index, object) => menu.insertItem(index, object)
            onObjectRemoved: (index, object) => menu.removeItem(object)
        }
    }
}
