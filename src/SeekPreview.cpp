#include "SeekPreview.h"

#include <QMutex>

#include <mpv/client.h>
#include <mpv/render.h>

#include <algorithm>
#include <cmath>
#include <cstring>

namespace {

QMutex g_imageMutex;
QImage g_image;   // the latest frame

constexpr int kWidth = 320;   // pixels; QML shows it at about half that, for high-DPI screens

} // namespace

SeekPreview::SeekPreview(QObject *parent)
    : QObject(parent)
{
    m_thread = std::thread([this] { run(); });
}

SeekPreview::~SeekPreview()
{
    {
        std::lock_guard lock(m_mutex);
        m_quit = true;
    }
    m_wake.notify_one();
    m_thread.join();
}

void SeekPreview::request(const QString &source, double seconds)
{
    if (source != m_shownSource) {
        // Nothing until the new one's first frame.
        m_shownSource = source;
        ++m_generation;
        if (std::exchange(m_frame, 0) != 0)
            emit frameChanged();
    }
    {
        std::lock_guard lock(m_mutex);
        m_hasRequest = true;
        m_requestSource = source;
        m_requestSeconds = std::max(0.0, seconds);
        m_requestGeneration = m_generation;
    }
    m_wake.notify_one();
}

void SeekPreview::close()
{
    request(QString(), 0);
}

QImage SeekPreview::latest()
{
    QMutexLocker lock(&g_imageMutex);
    return g_image;
}

void SeekPreview::run()
{
    mpv_handle *mpv = mpv_create();
    if (!mpv)
        return;
    for (const auto &[name, value] : std::initializer_list<std::pair<const char *, const char *>>{
             {"vo", "libmpv"}, {"ao", "null"}, {"aid", "no"}, {"sid", "no"}, {"hwdec", "no"},
             {"hr-seek", "no"},   // the keyframe before: quick
             {"pause", "yes"}, {"keep-open", "yes"}, {"idle", "yes"}, {"ytdl", "no"},
             {"load-scripts", "no"}, {"input-default-bindings", "no"}, {"cache", "no"},
             {"demuxer-readahead-secs", "0"}, {"terminal", "no"}}) {
        mpv_set_option_string(mpv, name, value);
    }
    if (mpv_initialize(mpv) < 0) {
        mpv_destroy(mpv);
        return;
    }

    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_API_TYPE, const_cast<char *>(MPV_RENDER_API_TYPE_SW)},
        {MPV_RENDER_PARAM_INVALID, nullptr},
    };
    mpv_render_context *render = nullptr;
    if (mpv_render_context_create(&render, mpv, params) < 0) {
        mpv_terminate_destroy(mpv);
        return;
    }
    // Both called on mpv's threads: just wake this one.
    mpv_render_context_set_update_callback(render, [](void *self) {
        auto *preview = static_cast<SeekPreview *>(self);
        {
            std::lock_guard lock(preview->m_mutex);
            preview->m_frameReady = true;
        }
        preview->m_wake.notify_one();
    }, this);
    mpv_set_wakeup_callback(mpv, [](void *self) {
        auto *preview = static_cast<SeekPreview *>(self);
        {
            std::lock_guard lock(preview->m_mutex);
            preview->m_events = true;
        }
        preview->m_wake.notify_one();
    }, this);

    QString openSource;   // what's loaded ("" for nothing)
    int generation = 0;   // its generation (see m_generation)
    bool loaded = false;
    double wanted = -1;   // a seek waiting for the file to load
    // Which playlist entry openSource is (from loadfile's reply), and which one mpv last
    // started: a file replaced while it was loading still reports being loaded, after.
    uint64_t loadRequest = 0;
    int64_t openEntry = -1;
    int64_t startedEntry = -1;

    while (true) {
        bool hasRequest = false, frameReady = false, events = false;
        QString source;
        double seconds = 0;
        int requestGeneration = 0;
        {
            std::unique_lock lock(m_mutex);
            m_wake.wait(lock, [this] { return m_quit || m_hasRequest || m_frameReady || m_events; });
            if (m_quit)
                break;
            hasRequest = std::exchange(m_hasRequest, false);
            source = m_requestSource;
            seconds = m_requestSeconds;
            requestGeneration = m_requestGeneration;
            frameReady = std::exchange(m_frameReady, false);
            events = std::exchange(m_events, false);
        }

        if (events) {
            while (true) {
                mpv_event *event = mpv_wait_event(mpv, 0);
                if (event->event_id == MPV_EVENT_NONE)
                    break;
                if (event->event_id == MPV_EVENT_COMMAND_REPLY && event->reply_userdata == loadRequest
                    && loadRequest != 0 && event->error >= 0) {
                    const mpv_node &result = static_cast<mpv_event_command *>(event->data)->result;
                    if (result.format == MPV_FORMAT_NODE_MAP)
                        for (int i = 0; i < result.u.list->num; ++i)
                            if (std::strcmp(result.u.list->keys[i], "playlist_entry_id") == 0
                                && result.u.list->values[i].format == MPV_FORMAT_INT64)
                                openEntry = result.u.list->values[i].u.int64;
                } else if (event->event_id == MPV_EVENT_START_FILE) {
                    startedEntry = static_cast<mpv_event_start_file *>(event->data)->playlist_entry_id;
                } else if (event->event_id == MPV_EVENT_FILE_LOADED) {
                    if (openSource.isEmpty() || openEntry < 0 || startedEntry != openEntry)
                        continue;   // the one before
                    loaded = true;
                    if (wanted >= 0) {
                        const QByteArray at = QByteArray::number(std::exchange(wanted, -1.0), 'f', 3);
                        const char *seek[] = {"seek", at.constData(), "absolute+keyframes", nullptr};
                        mpv_command_async(mpv, 0, seek);
                    }
                }
            }
        }

        if (hasRequest) {
            if (source.isEmpty()) {
                if (!openSource.isEmpty()) {
                    const char *stop[] = {"stop", nullptr};
                    mpv_command(mpv, stop);
                    openSource.clear();
                    loaded = false;
                }
            } else if (source != openSource) {
                openSource = source;
                generation = requestGeneration;
                loaded = false;
                wanted = seconds;
                openEntry = -1;
                const QByteArray path = source.toUtf8();
                const char *load[] = {"loadfile", path.constData(), nullptr};
                mpv_command_async(mpv, ++loadRequest, load);
            } else if (loaded) {
                const QByteArray at = QByteArray::number(seconds, 'f', 3);
                const char *seek[] = {"seek", at.constData(), "absolute+keyframes", nullptr};
                mpv_command_async(mpv, 0, seek);
            } else {
                wanted = seconds;   // still loading
            }
        }

        if (frameReady && (mpv_render_context_update(render) & MPV_RENDER_UPDATE_FRAME) && loaded) {
            int64_t width = 0, height = 0;
            mpv_get_property(mpv, "dwidth", MPV_FORMAT_INT64, &width);
            mpv_get_property(mpv, "dheight", MPV_FORMAT_INT64, &height);
            if (width <= 0 || height <= 0)
                continue;
            const int h = std::clamp(int(std::lround(double(kWidth) * height / width)), 60, 480);
            QImage image(kWidth, h, QImage::Format_RGBX8888);
            int size[2] = {kWidth, h};
            size_t stride = size_t(image.bytesPerLine());
            const char *format = "rgb0";
            mpv_render_param draw[] = {
                {MPV_RENDER_PARAM_SW_SIZE, size},
                {MPV_RENDER_PARAM_SW_FORMAT, const_cast<char *>(format)},
                {MPV_RENDER_PARAM_SW_STRIDE, &stride},
                {MPV_RENDER_PARAM_SW_POINTER, image.bits()},
                {MPV_RENDER_PARAM_INVALID, nullptr},
            };
            if (mpv_render_context_render(render, draw) < 0)
                continue;
            {
                QMutexLocker lock(&g_imageMutex);
                g_image = image;
            }
            QMetaObject::invokeMethod(this, [this, generation] {
                if (generation != m_generation)
                    return;   // drawn from the video before
                ++m_frame;
                emit frameChanged();
            }, Qt::QueuedConnection);
        }
    }

    mpv_render_context_free(render);
    mpv_terminate_destroy(mpv);
}

QImage SeekPreviewImages::requestImage(const QString &, QSize *size, const QSize &)
{
    const QImage image = SeekPreview::latest();
    if (size)
        *size = image.size();
    return image;
}
