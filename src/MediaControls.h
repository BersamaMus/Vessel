#pragma once

#include <QObject>
#include <QUrl>
#include <QWindow>
#include <QtQml/qqmlregistration.h>

#include <memory>

// The system's media controls for the video that's open: on Windows, the keyboard's media
// keys (play/pause, next, previous, even while another app is in front) and the media
// flyout (title, thumbnail and buttons) by the volume indicator. Windows calls these the
// System Media Transport Controls. Elsewhere it does nothing (yet).
class MediaControls : public QObject
{
    Q_OBJECT
    QML_ELEMENT

    // The window the controls belong to (the app's).
    Q_PROPERTY(QWindow *appWindow MEMBER m_window NOTIFY changed)
    // A video is open; otherwise the controls are taken away.
    Q_PROPERTY(bool active MEMBER m_active NOTIFY changed)
    Q_PROPERTY(bool playing MEMBER m_playing NOTIFY changed)
    Q_PROPERTY(QString title MEMBER m_title NOTIFY changed)
    // The line under the title: where the video is.
    Q_PROPERTY(QString subtitle MEMBER m_subtitle NOTIFY changed)
    Q_PROPERTY(QUrl thumbnail MEMBER m_thumbnail NOTIFY changed)
    Q_PROPERTY(bool canGoNext MEMBER m_canGoNext NOTIFY changed)
    Q_PROPERTY(bool canGoPrevious MEMBER m_canGoPrevious NOTIFY changed)

public:
    explicit MediaControls(QObject *parent = nullptr);
    ~MediaControls() override;

signals:
    void changed();
    // A media key or flyout button was pressed. (The play/pause key asks for whichever
    // the video isn't doing, going by `playing`.)
    void playRequested();
    void pauseRequested();
    void nextRequested();
    void previousRequested();

private:
    void update();

    struct Platform;
    std::unique_ptr<Platform> m_platform;
    QWindow *m_window = nullptr;
    bool m_active = false;
    bool m_playing = false;
    QString m_title;
    QString m_subtitle;
    QUrl m_thumbnail;
    bool m_canGoNext = false;
    bool m_canGoPrevious = false;
    bool m_updatePending = false;
};
