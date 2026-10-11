#pragma once

#include <QImage>
#include <QObject>
#include <QQuickImageProvider>
#include <QString>
#include <QtQml/qqmlregistration.h>

#include <condition_variable>
#include <mutex>
#include <thread>

// The frame shown above the seek bar while it's hovered: a second, hidden libmpv on its
// own thread that seeks (to the nearest keyframe, which is quick) and draws small frames
// with mpv's software renderer. Hovering faster than frames come skips straight to the
// latest position. QML shows the frame as "image://seekpreview/<frame>".
class SeekPreview : public QObject
{
    Q_OBJECT
    QML_ELEMENT
    QML_SINGLETON

    // Goes up with each new frame (a fresh image URL for QML).
    Q_PROPERTY(int frame READ frame NOTIFY frameChanged)

public:
    explicit SeekPreview(QObject *parent = nullptr);
    ~SeekPreview() override;

    int frame() const { return m_frame; }

    // Shows the frame at `seconds` into `source` (a file path), opening it if need be.
    Q_INVOKABLE void request(const QString &source, double seconds);
    // Lets go of the file, so it can be renamed or deleted.
    Q_INVOKABLE void close();

    // The latest frame, for the image provider.
    static QImage latest();

signals:
    void frameChanged();

private:
    void run();

    std::thread m_thread;
    std::mutex m_mutex;
    std::condition_variable m_wake;
    // Guarded by m_mutex.
    bool m_quit = false;
    bool m_hasRequest = false;
    QString m_requestSource;   // "" with a request: close the file
    double m_requestSeconds = 0;
    int m_requestGeneration = 0;
    bool m_frameReady = false;
    bool m_events = false;

    // GUI thread. Each source shown gets a generation: frames still on their way from the
    // last one are dropped, so it's never shown above another video's seek bar.
    int m_frame = 0;
    int m_generation = 0;
    QString m_shownSource;
};

// Serves SeekPreview's frames to QML.
class SeekPreviewImages : public QQuickImageProvider
{
public:
    SeekPreviewImages() : QQuickImageProvider(QQuickImageProvider::Image) {}
    QImage requestImage(const QString &id, QSize *size, const QSize &requestedSize) override;
};
