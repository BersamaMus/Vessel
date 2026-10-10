#pragma once

#include <QQuickFramebufferObject>
#include <QStringList>
#include <QVariantList>
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
    Q_PROPERTY(int videoWidth READ videoWidth NOTIFY videoInfoChanged)
    Q_PROPERTY(int videoHeight READ videoHeight NOTIFY videoInfoChanged)
    // The video codec as mpv names it: "h264", "hevc", "av1".
    Q_PROPERTY(QString videoFormat READ videoFormat NOTIFY videoInfoChanged)
    // Frames per second, as the file states it; 0 if unknown.
    Q_PROPERTY(double frameRate READ frameRate NOTIFY videoInfoChanged)
    // When the video was recorded or made, from the file's "creation_time" tag ("29 Jan 2022, 10:23 AM");
    // "" if it has none.
    Q_PROPERTY(QString mediaCreated READ mediaCreated NOTIFY videoInfoChanged)
    // Subtitle tracks: {id, label, external, file, selected}; subtitleId is the shown one, 0 for none.
    Q_PROPERTY(QVariantList subtitleTracks READ subtitleTracks NOTIFY tracksChanged)
    Q_PROPERTY(int subtitleId READ subtitleId NOTIFY tracksChanged)

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
    int videoWidth() const { return m_videoWidth; }
    int videoHeight() const { return m_videoHeight; }
    QString videoFormat() const { return m_videoFormat; }
    double frameRate() const { return m_frameRate; }
    QString mediaCreated() const { return m_mediaCreated; }
    QVariantList subtitleTracks() const { return m_subtitleTracks; }
    int subtitleId() const { return m_subtitleId; }

    void setPaused(bool paused);
    void setVolume(double volume);
    void setMuted(bool muted);

    // Opens a local file, optionally starting part-way through (resume). The first
    // of `subtitles` (subtitle files to load alongside it) is shown.
    Q_INVOKABLE void open(const QString &path, double startSeconds = 0, const QStringList &subtitles = {});
    Q_INVOKABLE void togglePause();
    // Exact seek, for clicks and when scrubbing ends.
    Q_INVOKABLE void seek(double seconds);
    // Fast keyframe seek, for while the seek bar is being dragged.
    Q_INVOKABLE void scrub(double seconds);
    Q_INVOKABLE void seekRelative(double seconds);
    // Stops playback and unloads the file.
    Q_INVOKABLE void stop();
    // Shows subtitle track `id`, or none for 0.
    Q_INVOKABLE void setSubtitleTrack(int id);
    // Loads a subtitle file, and shows it unless `select` is false.
    Q_INVOKABLE void addSubtitleFile(const QString &path, bool select = true);
    // Sets any mpv property, e.g. "sub-pos".
    Q_INVOKABLE void setMpvProperty(const QString &name, const QVariant &value);

signals:
    void positionChanged();
    void durationChanged();
    void pausedChanged();
    void endedChanged();
    void volumeChanged();
    void mutedChanged();
    void titleChanged();
    void loadedChanged();
    void videoInfoChanged();
    void tracksChanged();
    void errorOccurred(const QString &message);

private:
    friend class MpvRenderer;

    void processEvents();
    void command(const QStringList &args);
    void onRendererReady();
    void onRendererGone();
    void load(const QString &path, double startSeconds, const QStringList &subtitles);
    void updateTracks(const QVariantList &tracks);

    mpv_handle *m_mpv = nullptr;
    // mpv can't start video output until the render context exists, so a file
    // opened before the first frame waits here.
    bool m_rendererReady = false;
    QString m_pendingPath;
    double m_pendingStart = 0;
    QStringList m_pendingSubtitles;
    // Subtitle files to add once the current file has loaded.
    QStringList m_subtitlesToAdd;
    double m_position = 0;
    double m_duration = 0;
    bool m_paused = false;
    bool m_ended = false;
    double m_volume = 100;
    bool m_muted = false;
    QString m_title;
    bool m_loaded = false;
    int m_videoWidth = 0;
    int m_videoHeight = 0;
    QString m_videoFormat;
    double m_frameRate = 0;
    QString m_mediaCreated;
    QVariantList m_subtitleTracks;
    int m_subtitleId = 0;
};
