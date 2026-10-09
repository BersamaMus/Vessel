#pragma once

#include <QOpenGLWidget>
#include <QStringList>

struct mpv_handle;
struct mpv_render_context;

// Video surface backed by libmpv's render API. Owns the mpv instance and
// exposes the handful of playback controls/properties the UI needs.
class MpvWidget : public QOpenGLWidget
{
    Q_OBJECT

public:
    explicit MpvWidget(QWidget *parent = nullptr);
    ~MpvWidget() override;

    void open(const QString &path);
    void togglePause();
    void seekRelative(double seconds);
    void seekAbsolute(double seconds, bool exact);
    void setVolume(double percent);
    void toggleMute();

signals:
    void positionChanged(double seconds);
    void durationChanged(double seconds);
    void pausedChanged(bool paused);
    void volumeChanged(double percent);
    void mutedChanged(bool muted);
    void titleChanged(const QString &title);
    void fileLoaded();
    void errorOccurred(const QString &message);
    void doubleClicked();

protected:
    void initializeGL() override;
    void paintGL() override;
    void mouseDoubleClickEvent(QMouseEvent *event) override;

private slots:
    void processMpvEvents();

private:
    void command(const QStringList &args);
    void destroyRenderContext();

    mpv_handle *m_mpv = nullptr;
    mpv_render_context *m_renderCtx = nullptr;
};
