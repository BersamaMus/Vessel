#include "MpvItem.h"

#include <QCoreApplication>
#include <QDebug>
#include <QOpenGLContext>
#include <QOpenGLFramebufferObject>
#include <QPointer>
#include <QQuickOpenGLUtils>

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

// Lives on Qt Quick's render thread. It holds its own client handle so the mpv
// core outlives the item until the render context has been freed here.
class MpvRenderer : public QQuickFramebufferObject::Renderer
{
public:
    explicit MpvRenderer(const MpvItem *item)
        : m_item(const_cast<MpvItem *>(item))
    {
        m_client = mpv_create_client(item->m_mpv, "renderer");

        mpv_opengl_init_params glInit{&getProcAddress, nullptr};
        mpv_render_param params[] = {
            {MPV_RENDER_PARAM_API_TYPE, const_cast<char *>(MPV_RENDER_API_TYPE_OPENGL)},
            {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &glInit},
            {MPV_RENDER_PARAM_INVALID, nullptr},
        };
        if (mpv_render_context_create(&m_ctx, m_client, params) < 0) {
            qWarning() << "Failed to create the mpv render context";
            m_ctx = nullptr;
            return;
        }

        mpv_render_context_set_update_callback(m_ctx, [](void *ctx) {
            // Called from an mpv thread: ask the GUI thread to schedule a frame.
            QPointer<MpvItem> item = static_cast<MpvRenderer *>(ctx)->m_item;
            QMetaObject::invokeMethod(qApp, [item] {
                if (item)
                    item->update();
            }, Qt::QueuedConnection);
        }, this);

        QMetaObject::invokeMethod(qApp, [item = m_item] {
            if (item)
                item->onRendererReady();
        }, Qt::QueuedConnection);
    }

    ~MpvRenderer() override
    {
        if (m_ctx)
            mpv_render_context_free(m_ctx);
        mpv_destroy(m_client);
    }

    void render() override
    {
        if (!m_ctx)
            return;
        QOpenGLFramebufferObject *fbo = framebufferObject();
        mpv_opengl_fbo mpfbo{static_cast<int>(fbo->handle()), fbo->width(), fbo->height(), 0};
        int flipY = 0;
        mpv_render_param params[] = {
            {MPV_RENDER_PARAM_OPENGL_FBO, &mpfbo},
            {MPV_RENDER_PARAM_FLIP_Y, &flipY},
            {MPV_RENDER_PARAM_INVALID, nullptr},
        };
        mpv_render_context_render(m_ctx, params);
        QQuickOpenGLUtils::resetOpenGLState();
    }

private:
    QPointer<MpvItem> m_item;
    mpv_handle *m_client = nullptr;
    mpv_render_context *m_ctx = nullptr;
};

MpvItem::MpvItem(QQuickItem *parent)
    : QQuickFramebufferObject(parent)
{
    m_mpv = mpv_create();
    if (!m_mpv)
        qFatal("Could not create mpv instance");

    mpv_set_option_string(m_mpv, "vo", "libmpv");
    mpv_set_option_string(m_mpv, "hwdec", "auto-safe");
    mpv_set_option_string(m_mpv, "keep-open", "yes");   // stay on the last frame at the end
    mpv_set_option_string(m_mpv, "ytdl", "no");         // offline player
    mpv_set_option_string(m_mpv, "input-default-bindings", "no");
    mpv_set_option_string(m_mpv, "input-vo-keyboard", "no");
    mpv_request_log_messages(m_mpv, "warn");

    if (mpv_initialize(m_mpv) < 0)
        qFatal("Could not initialize mpv");

    mpv_observe_property(m_mpv, 0, "time-pos", MPV_FORMAT_DOUBLE);
    mpv_observe_property(m_mpv, 0, "duration", MPV_FORMAT_DOUBLE);
    mpv_observe_property(m_mpv, 0, "pause", MPV_FORMAT_FLAG);
    mpv_observe_property(m_mpv, 0, "eof-reached", MPV_FORMAT_FLAG);
    mpv_observe_property(m_mpv, 0, "volume", MPV_FORMAT_DOUBLE);
    mpv_observe_property(m_mpv, 0, "mute", MPV_FORMAT_FLAG);
    mpv_observe_property(m_mpv, 0, "media-title", MPV_FORMAT_STRING);

    mpv_set_wakeup_callback(m_mpv, [](void *ctx) {
        QMetaObject::invokeMethod(static_cast<MpvItem *>(ctx), &MpvItem::processEvents,
                                  Qt::QueuedConnection);
    }, this);
}

MpvItem::~MpvItem()
{
    mpv_set_wakeup_callback(m_mpv, nullptr, nullptr);
    // Stop right away; the core itself goes once the renderer drops its handle.
    const char *stop[] = {"stop", nullptr};
    mpv_command(m_mpv, stop);
    mpv_destroy(m_mpv);
}

QQuickFramebufferObject::Renderer *MpvItem::createRenderer() const
{
    return new MpvRenderer(this);
}

void MpvItem::processEvents()
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
            const auto asDouble = [&] { return has ? *static_cast<double *>(prop->data) : 0.0; };
            const auto asFlag = [&] { return has && *static_cast<int *>(prop->data) != 0; };

            if (name == "time-pos") {
                m_position = asDouble();
                emit positionChanged();
            } else if (name == "duration") {
                m_duration = asDouble();
                emit durationChanged();
            } else if (name == "pause" && has) {
                m_paused = asFlag();
                emit pausedChanged();
            } else if (name == "eof-reached") {
                m_ended = asFlag();
                emit endedChanged();
            } else if (name == "volume" && has) {
                m_volume = asDouble();
                emit volumeChanged();
            } else if (name == "mute" && has) {
                m_muted = asFlag();
                emit mutedChanged();
            } else if (name == "media-title") {
                m_title = has ? QString::fromUtf8(*static_cast<char **>(prop->data)) : QString();
                emit titleChanged();
            }
            break;
        }
        case MPV_EVENT_FILE_LOADED:
            m_loaded = true;
            emit loadedChanged();
            break;
        case MPV_EVENT_END_FILE: {
            auto *end = static_cast<mpv_event_end_file *>(event->data);
            if (end->reason == MPV_END_FILE_REASON_ERROR)
                emit errorOccurred(tr("Could not play this file (%1)").arg(mpv_error_string(end->error)));
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

void MpvItem::command(const QStringList &args)
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

void MpvItem::open(const QString &path, double startSeconds)
{
    if (!m_rendererReady) {
        m_pendingPath = path;
        m_pendingStart = startSeconds;
        return;
    }
    load(path, startSeconds);
}

void MpvItem::onRendererReady()
{
    m_rendererReady = true;
    if (!m_pendingPath.isEmpty())
        load(std::exchange(m_pendingPath, QString()), m_pendingStart);
}

void MpvItem::load(const QString &path, double startSeconds)
{
    // "start" is an option that sticks to later files, so always set it.
    const QByteArray start = startSeconds > 0 ? QByteArray::number(startSeconds, 'f', 3) : QByteArray("none");
    mpv_set_option_string(m_mpv, "start", start.constData());
    command({"loadfile", path});
    setPaused(false);
}

void MpvItem::setPaused(bool paused)
{
    command({"set", "pause", paused ? "yes" : "no"});
}

void MpvItem::togglePause()
{
    // At the end, "play" means start over.
    if (m_ended)
        command({"seek", "0", "absolute"});
    command({"cycle", "pause"});
}

void MpvItem::seek(double seconds)
{
    command({"seek", QString::number(seconds), "absolute+exact"});
}

void MpvItem::scrub(double seconds)
{
    command({"seek", QString::number(seconds), "absolute+keyframes"});
}

void MpvItem::seekRelative(double seconds)
{
    command({"seek", QString::number(seconds), "relative+exact"});
}

void MpvItem::setVolume(double volume)
{
    command({"set", "volume", QString::number(qBound(0.0, volume, 100.0))});
}

void MpvItem::setMuted(bool muted)
{
    command({"set", "mute", muted ? "yes" : "no"});
}
