#include "Thumbnailer.h"

#include <QDebug>
#include <QDeadlineTimer>
#include <QDir>
#include <QFile>
#include <QTemporaryDir>

#include <mpv/client.h>

namespace {

struct GrabResult
{
    bool ok = false;
    double duration = 0;
    int width = 0;
    int height = 0;
    int year = 0;
};

// The year of a "date", "year" or "date_released" tag ("1994", "1994-03-12"); 0 if none.
int taggedYear(mpv_handle *mpv)
{
    for (const char *key : {"metadata/by-key/date", "metadata/by-key/year", "metadata/by-key/date_released"}) {
        char *value = mpv_get_property_string(mpv, key);
        if (!value)
            continue;
        const int year = QString::fromUtf8(value).left(4).toInt();
        mpv_free(value);
        if (year >= 1900 && year <= 2100)
            return year;
    }
    return 0;
}

GrabResult grabFrame(const QString &videoPath, const QString &outputFile, const std::atomic_bool &stopping)
{
    GrabResult result;
    QTemporaryDir tempDir;
    if (!tempDir.isValid())
        return result;

    mpv_handle *mpv = mpv_create();
    if (!mpv)
        return result;

    const QByteArray outDir = QDir::toNativeSeparators(tempDir.path()).toUtf8();
    const std::pair<const char *, const char *> options[] = {
        {"config", "no"},
        {"terminal", "no"},
        {"load-scripts", "no"},
        {"ytdl", "no"},
        {"vo", "image"},
        {"vo-image-format", "jpg"},
        {"vo-image-jpeg-quality", "85"},
        {"vo-image-outdir", outDir.constData()},
        {"vf", "scale=480:-2"},
        {"aid", "no"},
        {"sid", "no"},
        {"hwdec", "no"},
        {"hr-seek", "no"},
        {"start", "20%"},   // past intros and black frames
        {"frames", "1"},
    };
    for (const auto &[name, value] : options)
        mpv_set_option_string(mpv, name, value);

    if (mpv_initialize(mpv) >= 0) {
        const QByteArray path = videoPath.toUtf8();
        const char *cmd[] = {"loadfile", path.constData(), nullptr};
        mpv_command(mpv, cmd);

        QDeadlineTimer deadline(20'000);
        bool done = false;
        while (!done && !deadline.hasExpired() && !stopping) {
            mpv_event *event = mpv_wait_event(mpv, 0.25);
            switch (event->event_id) {
            case MPV_EVENT_FILE_LOADED: {
                mpv_get_property(mpv, "duration", MPV_FORMAT_DOUBLE, &result.duration);
                // The size the file says it is (before the scale filter).
                int64_t width = 0, height = 0;
                mpv_get_property(mpv, "width", MPV_FORMAT_INT64, &width);
                mpv_get_property(mpv, "height", MPV_FORMAT_INT64, &height);
                result.width = int(width);
                result.height = int(height);
                result.year = taggedYear(mpv);
                break;
            }
            case MPV_EVENT_END_FILE: {
                auto *end = static_cast<mpv_event_end_file *>(event->data);
                if (end->reason == MPV_END_FILE_REASON_ERROR)
                    qWarning().noquote() << "Thumbnail failed for" << videoPath << '-' << mpv_error_string(end->error);
                done = true;
                break;
            }
            case MPV_EVENT_SHUTDOWN:
                done = true;
                break;
            default:
                break;
            }
        }
    }
    mpv_terminate_destroy(mpv);

    const QString frame = tempDir.filePath(QStringLiteral("00000001.jpg"));
    if (QFile::exists(frame)) {
        QFile::remove(outputFile);
        result.ok = QFile::rename(frame, outputFile) || QFile::copy(frame, outputFile);
    }
    return result;
}

} // namespace

Thumbnailer::Thumbnailer(QObject *parent)
    : QObject(parent)
{
    m_pool.setMaxThreadCount(2);
}

Thumbnailer::~Thumbnailer()
{
    m_stopping = true;
    m_pool.clear();
    m_pool.waitForDone();
}

void Thumbnailer::request(const QString &videoPath, const QString &outputFile)
{
    m_pool.start([this, videoPath, outputFile] {
        if (m_stopping)
            return;
        const GrabResult result = grabFrame(videoPath, outputFile, m_stopping);
        if (!m_stopping)
            emit finished(videoPath, result.ok, result.duration, result.width, result.height, result.year);
    });
}

void Thumbnailer::clear()
{
    m_pool.clear();
}
