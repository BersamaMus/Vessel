#include "MpvItem.h"

#include <QCoreApplication>
#include <QDebug>
#include <QFileInfo>
#include <QHash>
#include <QLocale>
#include <QOpenGLContext>
#include <QOpenGLFramebufferObject>
#include <QPointer>
#include <QQuickOpenGLUtils>
#include <QTimeZone>

#include "PhoneDevices.h"

// "2 Jan 2022, 2:16 PM" (VideoModels.cpp; its header needs the whole library).
QString formatDateTime(const QDateTime &time);

#include <mpv/client.h>
#include <mpv/render_gl.h>

#include <algorithm>
#include <vector>

namespace {

void *getProcAddress(void * /*ctx*/, const char *name)
{
    QOpenGLContext *glctx = QOpenGLContext::currentContext();
    return glctx ? reinterpret_cast<void *>(glctx->getProcAddress(name)) : nullptr;
}

QVariant toVariant(const mpv_node &node)
{
    switch (node.format) {
    case MPV_FORMAT_STRING:
        return QString::fromUtf8(node.u.string);
    case MPV_FORMAT_FLAG:
        return node.u.flag != 0;
    case MPV_FORMAT_INT64:
        return qint64(node.u.int64);
    case MPV_FORMAT_DOUBLE:
        return node.u.double_;
    case MPV_FORMAT_NODE_ARRAY: {
        QVariantList list;
        for (int i = 0; i < node.u.list->num; ++i)
            list.append(toVariant(node.u.list->values[i]));
        return list;
    }
    case MPV_FORMAT_NODE_MAP: {
        QVariantMap map;
        for (int i = 0; i < node.u.list->num; ++i)
            map.insert(QString::fromUtf8(node.u.list->keys[i]), toVariant(node.u.list->values[i]));
        return map;
    }
    default:
        return {};
    }
}

// "English", from a track's "eng" / "en" tag.
QString languageName(const QString &code)
{
    if (code.isEmpty())
        return {};
    const QLocale::Language language = QLocale::codeToLanguage(code, QLocale::AnyLanguageCode);
    return language == QLocale::AnyLanguage ? code : QLocale::languageToString(language);
}

// "English · 5.1 · AC-3", "Director's commentary (English) · Stereo": what tells a file's
// audio tracks apart.
QString audioLabel(const QVariantMap &track)
{
    const int id = track.value(QStringLiteral("id")).toInt();
    const QString language = languageName(track.value(QStringLiteral("lang")).toString());
    QString name = track.value(QStringLiteral("title")).toString();
    if (name.isEmpty())
        name = language;
    else if (!language.isEmpty() && !name.contains(language, Qt::CaseInsensitive))
        name += QStringLiteral(" (%1)").arg(language);
    if (name.isEmpty())
        name = MpvItem::tr("Track %1").arg(id);

    QStringList parts{name};
    const int channels = track.value(QStringLiteral("demux-channel-count")).toInt();
    if (channels == 1)
        parts.append(MpvItem::tr("Mono"));
    else if (channels == 2)
        parts.append(MpvItem::tr("Stereo"));
    else if (channels == 6)
        parts.append(QStringLiteral("5.1"));
    else if (channels == 8)
        parts.append(QStringLiteral("7.1"));
    else if (channels > 0)
        parts.append(MpvItem::tr("%1 channels").arg(channels));
    static const QHash<QString, QString> codecs = {
        {"aac", "AAC"}, {"ac3", "AC-3"}, {"eac3", "E-AC-3"}, {"dts", "DTS"}, {"truehd", "TrueHD"},
        {"flac", "FLAC"}, {"opus", "Opus"}, {"vorbis", "Vorbis"}, {"mp3", "MP3"}, {"mp2", "MP2"},
        {"pcm_s16le", "PCM"}, {"pcm_s24le", "PCM"}, {"alac", "ALAC"},
    };
    const QString codec = track.value(QStringLiteral("codec")).toString();
    if (!codec.isEmpty())
        parts.append(codecs.value(codec, codec.toUpper()));
    return parts.join(QStringLiteral("  ·  "));
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

        // The item can live on (e.g. hidden and shown again); files must then
        // wait for the next renderer.
        QMetaObject::invokeMethod(qApp, [item = m_item] {
            if (item)
                item->onRendererGone();
        }, Qt::QueuedConnection);
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
    // Volume goes up to 200% (as in VLC); past 100% mpv amplifies the sound.
    mpv_set_option_string(m_mpv, "volume-max", "200");
    mpv_set_option_string(m_mpv, "ytdl", "no");         // offline player
    mpv_set_option_string(m_mpv, "input-default-bindings", "no");
    mpv_set_option_string(m_mpv, "input-vo-keyboard", "no");
    // The library picks subtitle files itself (see VideoLibrary::subtitlesFor).
    mpv_set_option_string(m_mpv, "sub-auto", "no");
    mpv_request_log_messages(m_mpv, "warn");

    if (mpv_initialize(m_mpv) < 0)
        qFatal("Could not initialize mpv");
    // mtp:// streams videos straight from a phone (see VideoLibrary::playbackUrl).
    Phones::registerStream(m_mpv);

    mpv_observe_property(m_mpv, 0, "time-pos", MPV_FORMAT_DOUBLE);
    mpv_observe_property(m_mpv, 0, "duration", MPV_FORMAT_DOUBLE);
    mpv_observe_property(m_mpv, 0, "pause", MPV_FORMAT_FLAG);
    mpv_observe_property(m_mpv, 0, "eof-reached", MPV_FORMAT_FLAG);
    mpv_observe_property(m_mpv, 0, "volume", MPV_FORMAT_DOUBLE);
    mpv_observe_property(m_mpv, 0, "mute", MPV_FORMAT_FLAG);
    mpv_observe_property(m_mpv, 0, "speed", MPV_FORMAT_DOUBLE);
    mpv_observe_property(m_mpv, 0, "media-title", MPV_FORMAT_STRING);
    mpv_observe_property(m_mpv, 0, "width", MPV_FORMAT_INT64);
    mpv_observe_property(m_mpv, 0, "height", MPV_FORMAT_INT64);
    mpv_observe_property(m_mpv, 0, "video-format", MPV_FORMAT_STRING);
    mpv_observe_property(m_mpv, 0, "container-fps", MPV_FORMAT_DOUBLE);
    mpv_observe_property(m_mpv, 0, "track-list", MPV_FORMAT_NODE);

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
            } else if (name == "speed" && has) {
                m_speed = asDouble();
                emit speedChanged();
            } else if (name == "media-title") {
                m_title = has ? QString::fromUtf8(*static_cast<char **>(prop->data)) : QString();
                emit titleChanged();
            } else if (name == "width" || name == "height") {
                const int value = has ? int(*static_cast<int64_t *>(prop->data)) : 0;
                (name == "width" ? m_videoWidth : m_videoHeight) = value;
                emit videoInfoChanged();
            } else if (name == "video-format") {
                m_videoFormat = has ? QString::fromUtf8(*static_cast<char **>(prop->data)) : QString();
                emit videoInfoChanged();
            } else if (name == "container-fps") {
                m_frameRate = asDouble();
                emit videoInfoChanged();
            } else if (name == "track-list") {
                updateTracks(has ? toVariant(*static_cast<mpv_node *>(prop->data)).toList() : QVariantList());
            }
            break;
        }
        case MPV_EVENT_FILE_LOADED: {
            // "2022-01-29T10:23:57.000000Z". Files without one sometimes carry 1904 or
            // 1970 (an unset date counted from zero): those don't count.
            QString created;
            if (char *value = mpv_get_property_string(m_mpv, "metadata/by-key/creation_time")) {
                const QDateTime time = QDateTime::fromString(QString::fromUtf8(value).left(19), Qt::ISODate);
                mpv_free(value);
                if (time.isValid() && time.date().year() > 1971)
                    created = formatDateTime(QDateTime(time.date(), time.time(), QTimeZone::UTC));
            }
            m_mediaCreated = created;
            emit videoInfoChanged();
            m_loaded = true;
            emit loadedChanged();
            // The first matching subtitle file is shown; the rest are just listed.
            for (int i = 0; i < m_subtitlesToAdd.size(); ++i)
                command({"sub-add", m_subtitlesToAdd[i], i == 0 ? QStringLiteral("select") : QStringLiteral("auto")});
            m_subtitlesToAdd.clear();
            break;
        }
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

void MpvItem::open(const QString &path, double startSeconds, const QStringList &subtitles, int audioTrack)
{
    if (!m_rendererReady) {
        m_pendingPath = path;
        m_pendingStart = startSeconds;
        m_pendingSubtitles = subtitles;
        m_pendingAudio = audioTrack;
        return;
    }
    load(path, startSeconds, subtitles, audioTrack);
}

void MpvItem::onRendererReady()
{
    m_rendererReady = true;
    if (!m_pendingPath.isEmpty())
        load(std::exchange(m_pendingPath, QString()), m_pendingStart, std::exchange(m_pendingSubtitles, {}),
             m_pendingAudio);
}

void MpvItem::onRendererGone()
{
    m_rendererReady = false;
}

void MpvItem::updateTracks(const QVariantList &tracks)
{
    QVariantList subtitles;
    int selected = 0;
    QVariantList audio;
    int audioSelected = 0;
    for (const QVariant &entry : tracks) {
        const QVariantMap track = entry.toMap();
        const QString type = track.value(QStringLiteral("type")).toString();
        if (type == QLatin1String("audio")) {
            const int id = track.value(QStringLiteral("id")).toInt();
            const bool isSelected = track.value(QStringLiteral("selected")).toBool();
            if (isSelected)
                audioSelected = id;
            audio.append(QVariantMap{{"id", id}, {"label", audioLabel(track)}, {"selected", isSelected}});
            continue;
        }
        if (type != QLatin1String("sub"))
            continue;
        const int id = track.value(QStringLiteral("id")).toInt();
        const bool external = track.value(QStringLiteral("external")).toBool();
        const QString language = languageName(track.value(QStringLiteral("lang")).toString());
        // Files are best known by name; built-in tracks by title or language.
        QString label = external ? QFileInfo(track.value(QStringLiteral("external-filename")).toString()).fileName()
                                 : track.value(QStringLiteral("title")).toString();
        if (label.isEmpty())
            label = language.isEmpty() ? tr("Track %1").arg(id) : language;
        else if (!external && !language.isEmpty() && !label.contains(language, Qt::CaseInsensitive))
            label += QStringLiteral(" (%1)").arg(language);

        const bool isSelected = track.value(QStringLiteral("selected")).toBool();
        if (isSelected)
            selected = id;
        subtitles.append(QVariantMap{
            {"id", id},
            {"label", label},
            {"external", external},
            {"file", external ? track.value(QStringLiteral("external-filename")).toString() : QString()},
            {"selected", isSelected},
        });
    }
    m_subtitleTracks = subtitles;
    m_subtitleId = selected;
    m_audioTracks = audio;
    m_audioId = audioSelected;
    emit tracksChanged();

    // The track chosen for this video last time, if the file still has it.
    if (m_wantedAudio > 0 && !audio.isEmpty()) {
        const int wanted = std::exchange(m_wantedAudio, 0);
        const bool there = std::any_of(audio.cbegin(), audio.cend(),
                                       [&](const QVariant &t) { return t.toMap().value(QStringLiteral("id")).toInt() == wanted; });
        if (there && wanted != audioSelected)
            setAudioTrack(wanted);
    }
}

void MpvItem::setSubtitleTrack(int id)
{
    command({"set", "sid", id > 0 ? QString::number(id) : QStringLiteral("no")});
}

void MpvItem::setAudioTrack(int id)
{
    command({"set", "aid", QString::number(id)});
}

void MpvItem::addSubtitleFile(const QString &path, bool select)
{
    command({"sub-add", path, select ? QStringLiteral("select") : QStringLiteral("auto")});
}

void MpvItem::setMpvProperty(const QString &name, const QVariant &value)
{
    command({"set", name, value.toString()});
}

void MpvItem::stop()
{
    m_pendingPath.clear();
    m_pendingSubtitles.clear();
    m_subtitlesToAdd.clear();
    command({"stop"});
    if (m_loaded) {
        m_loaded = false;
        emit loadedChanged();
    }
}

void MpvItem::load(const QString &path, double startSeconds, const QStringList &subtitles, int audioTrack)
{
    m_subtitlesToAdd = subtitles;
    if (!m_mediaCreated.isEmpty()) {   // not the last file's, while this one loads
        m_mediaCreated.clear();
        emit videoInfoChanged();
    }
    if (m_loaded) {
        m_loaded = false;
        emit loadedChanged();
    }
    // "start" is an option that sticks to later files, so always set it.
    const QByteArray start = startSeconds > 0 ? QByteArray::number(startSeconds, 'f', 3) : QByteArray("none");
    mpv_set_option_string(m_mpv, "start", start.constData());
    // So is "aid" (once a track has been chosen): the file's default, until its tracks are
    // known and the one asked for turns out to be there (see updateTracks).
    mpv_set_option_string(m_mpv, "aid", "auto");
    m_wantedAudio = audioTrack;
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
    command({"set", "volume", QString::number(qBound(0.0, volume, 200.0))});
}

void MpvItem::setMuted(bool muted)
{
    command({"set", "mute", muted ? "yes" : "no"});
}

void MpvItem::setSpeed(double speed)
{
    command({"set", "speed", QString::number(qBound(0.25, speed, 4.0))});
}
