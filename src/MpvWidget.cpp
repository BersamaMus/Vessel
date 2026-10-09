#include "MpvWidget.h"

#include <QDebug>
#include <QMouseEvent>
#include <QOpenGLContext>
#include <QOpenGLFunctions>

#include <mpv/client.h>
#include <mpv/render_gl.h>

#include <vector>

namespace {

void *getProcAddress(void * /*ctx*/, const char *name)
{
    QOpenGLContext *glctx = QOpenGLContext::currentContext();
    return glctx ? reinterpret_cast<void *>(glctx->getProcAddress(name)) : nullptr;
}

} // namespace

MpvWidget::MpvWidget(QWidget *parent)
    : QOpenGLWidget(parent)
{
    m_mpv = mpv_create();
    if (!m_mpv)
        qFatal("Could not create mpv instance");

    // We render through the render API and handle all input ourselves.
    mpv_set_option_string(m_mpv, "vo", "libmpv");
    mpv_set_option_string(m_mpv, "hwdec", "auto-safe");
    mpv_set_option_string(m_mpv, "keep-open", "yes");   // stay on last frame at EOF
    mpv_set_option_string(m_mpv, "ytdl", "no");         // offline player
    mpv_set_option_string(m_mpv, "input-default-bindings", "no");
    mpv_set_option_string(m_mpv, "input-vo-keyboard", "no");
    mpv_request_log_messages(m_mpv, "warn");

    if (mpv_initialize(m_mpv) < 0)
        qFatal("Could not initialize mpv");

    mpv_observe_property(m_mpv, 0, "time-pos", MPV_FORMAT_DOUBLE);
    mpv_observe_property(m_mpv, 0, "duration", MPV_FORMAT_DOUBLE);
    mpv_observe_property(m_mpv, 0, "pause", MPV_FORMAT_FLAG);
    mpv_observe_property(m_mpv, 0, "volume", MPV_FORMAT_DOUBLE);
    mpv_observe_property(m_mpv, 0, "mute", MPV_FORMAT_FLAG);
    mpv_observe_property(m_mpv, 0, "media-title", MPV_FORMAT_STRING);

    // Called from mpv's threads: bounce onto the GUI thread.
    mpv_set_wakeup_callback(m_mpv, [](void *ctx) {
        QMetaObject::invokeMethod(static_cast<MpvWidget *>(ctx), &MpvWidget::processMpvEvents,
                                  Qt::QueuedConnection);
    }, this);
}

MpvWidget::~MpvWidget()
{
    destroyRenderContext();
    mpv_terminate_destroy(m_mpv);
    m_mpv = nullptr;
}

void MpvWidget::initializeGL()
{
    // The GL context is recreated if the widget moves to another top-level window.
    connect(context(), &QOpenGLContext::aboutToBeDestroyed, this,
            &MpvWidget::destroyRenderContext, Qt::UniqueConnection);

    mpv_opengl_init_params glInit{&getProcAddress, nullptr};
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_API_TYPE, const_cast<char *>(MPV_RENDER_API_TYPE_OPENGL)},
        {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &glInit},
        {MPV_RENDER_PARAM_INVALID, nullptr},
    };
    if (mpv_render_context_create(&m_renderCtx, m_mpv, params) < 0) {
        emit errorOccurred(tr("Failed to initialize the mpv OpenGL renderer"));
        return;
    }

    mpv_render_context_set_update_callback(m_renderCtx, [](void *ctx) {
        QMetaObject::invokeMethod(static_cast<MpvWidget *>(ctx), qOverload<>(&QWidget::update),
                                  Qt::QueuedConnection);
    }, this);
}

void MpvWidget::paintGL()
{
    QOpenGLFunctions *gl = context()->functions();
    gl->glClearColor(0.f, 0.f, 0.f, 1.f);
    gl->glClear(GL_COLOR_BUFFER_BIT);

    if (!m_renderCtx)
        return;

    const qreal dpr = devicePixelRatioF();
    mpv_opengl_fbo fbo{static_cast<int>(defaultFramebufferObject()),
                       qRound(width() * dpr), qRound(height() * dpr), 0};
    int flipY = 1;
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_OPENGL_FBO, &fbo},
        {MPV_RENDER_PARAM_FLIP_Y, &flipY},
        {MPV_RENDER_PARAM_INVALID, nullptr},
    };
    mpv_render_context_render(m_renderCtx, params);
}

void MpvWidget::destroyRenderContext()
{
    if (!m_renderCtx)
        return;
    makeCurrent();
    mpv_render_context_free(m_renderCtx);
    m_renderCtx = nullptr;
    doneCurrent();
}

void MpvWidget::mouseDoubleClickEvent(QMouseEvent *event)
{
    if (event->button() == Qt::LeftButton)
        emit doubleClicked();
}

void MpvWidget::processMpvEvents()
{
    while (m_mpv) {
        mpv_event *event = mpv_wait_event(m_mpv, 0);
        if (event->event_id == MPV_EVENT_NONE)
            break;

        switch (event->event_id) {
        case MPV_EVENT_PROPERTY_CHANGE: {
            auto *prop = static_cast<mpv_event_property *>(event->data);
            const QByteArray name(prop->name);
            const bool has = prop->format != MPV_FORMAT_NONE && prop->data;

            if (name == "time-pos")
                emit positionChanged(has ? *static_cast<double *>(prop->data) : 0.0);
            else if (name == "duration")
                emit durationChanged(has ? *static_cast<double *>(prop->data) : 0.0);
            else if (name == "pause" && has)
                emit pausedChanged(*static_cast<int *>(prop->data) != 0);
            else if (name == "volume" && has)
                emit volumeChanged(*static_cast<double *>(prop->data));
            else if (name == "mute" && has)
                emit mutedChanged(*static_cast<int *>(prop->data) != 0);
            else if (name == "media-title")
                emit titleChanged(has ? QString::fromUtf8(*static_cast<char **>(prop->data)) : QString());
            break;
        }
        case MPV_EVENT_FILE_LOADED:
            emit fileLoaded();
            break;
        case MPV_EVENT_END_FILE: {
            auto *end = static_cast<mpv_event_end_file *>(event->data);
            if (end->reason == MPV_END_FILE_REASON_ERROR)
                emit errorOccurred(tr("Could not play file: %1").arg(mpv_error_string(end->error)));
            break;
        }
        case MPV_EVENT_COMMAND_REPLY:
            if (event->error < 0)
                qWarning() << "mpv command failed:" << mpv_error_string(event->error);
            break;
        case MPV_EVENT_LOG_MESSAGE: {
            auto *msg = static_cast<mpv_event_log_message *>(event->data);
            qWarning().noquote() << "[mpv]" << msg->prefix << QString::fromUtf8(msg->text).trimmed();
            break;
        }
        default:
            break;
        }
    }
}

void MpvWidget::command(const QStringList &args)
{
    std::vector<QByteArray> utf8;
    utf8.reserve(args.size());
    std::vector<const char *> argv;
    for (const QString &arg : args) {
        utf8.push_back(arg.toUtf8());
        argv.push_back(utf8.back().constData());
    }
    argv.push_back(nullptr);
    mpv_command_async(m_mpv, 0, argv.data());
}

void MpvWidget::open(const QString &path)
{
    command({"loadfile", path});
    command({"set", "pause", "no"});
}

void MpvWidget::togglePause()
{
    command({"cycle", "pause"});
}

void MpvWidget::seekRelative(double seconds)
{
    command({"seek", QString::number(seconds), "relative+exact"});
}

void MpvWidget::seekAbsolute(double seconds, bool exact)
{
    command({"seek", QString::number(seconds), exact ? "absolute+exact" : "absolute+keyframes"});
}

void MpvWidget::setVolume(double percent)
{
    command({"set", "volume", QString::number(percent)});
}

void MpvWidget::toggleMute()
{
    command({"cycle", "mute"});
}
