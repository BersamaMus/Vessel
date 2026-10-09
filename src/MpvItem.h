#pragma once

#include <QQuickFramebufferObject>
#include <QtQml/qqmlregistration.h>

struct mpv_handle;

// QML video surface backed by libmpv's OpenGL render API. Each item owns its
// own mpv instance; properties mirror the mpv properties the UI needs.
class MpvItem : public QQuickFramebufferObject
{
    Q_OBJECT
    QML_ELEMENT

    Q_PROPERTY(double position READ position NOTIFY positionChanged)
    Q_PROPERTY(double duration READ duration NOTIFY durationChanged)
    Q_PROPERTY(bool paused READ paused WRITE setPaused NOTIFY pausedChanged)
    Q_PROPERTY(bool ended READ ended NOTIFY endedChanged)
    Q_PROPERTY(double volume READ volume WRITE setVolume NOTIFY volumeChanged)
    Q_PROPERTY(bool muted READ muted WRITE setMuted NOTIFY mutedChanged)
    Q_PROPERTY(QString title READ title NOTIFY titleChanged)
    Q_PROPERTY(bool loaded READ loaded NOTIFY loadedChanged)

public:
    explicit MpvItem(QQuickItem *parent = nullptr);
    ~MpvItem() override;

    Renderer *createRenderer() const override;

    double position() const { return m_position; }
    double duration() const { return m_duration; }
    bool paused() const { return m_paused; }
    bool ended() const { return m_ended; }
    double volume() const { return m_volume; }
    bool muted() const { return m_muted; }
    QString title() const { return m_title; }
    bool loaded() const { return m_loaded; }

    void setPaused(bool paused);
    void setVolume(double volume);
    void setMuted(bool muted);

    // Opens a local file, optionally starting part-way through (resume).
    Q_INVOKABLE void open(const QString &path, double startSeconds = 0);
    Q_INVOKABLE void togglePause();
    // Exact seek, for clicks and when scrubbing ends.
    Q_INVOKABLE void seek(double seconds);
    // Fast keyframe seek, for while the seek bar is being dragged.
    Q_INVOKABLE void scrub(double seconds);
    Q_INVOKABLE void seekRelative(double seconds);

signals:
    void positionChanged();
    void durationChanged();
    void pausedChanged();
    void endedChanged();
    void volumeChanged();
    void mutedChanged();
    void titleChanged();
    void loadedChanged();
    void errorOccurred(const QString &message);

private:
    friend class MpvRenderer;

    void processEvents();
    void command(const QStringList &args);
    void onRendererReady();
    void load(const QString &path, double startSeconds);

    mpv_handle *m_mpv = nullptr;
    // mpv can't start video output until the render context exists, so a file
    // opened before the first frame waits here.
    bool m_rendererReady = false;
    QString m_pendingPath;
    double m_pendingStart = 0;
    double m_position = 0;
    double m_duration = 0;
    bool m_paused = false;
    bool m_ended = false;
    double m_volume = 100;
    bool m_muted = false;
    QString m_title;
    bool m_loaded = false;
};
