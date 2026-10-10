#include "Thumbnailer.h"

#include "PhoneDevices.h"

#include <QCoreApplication>
#include <QDebug>
#include <QDeadlineTimer>
#include <QDir>
#include <QFile>
#include <QProcess>
#include <QTemporaryDir>
#include <QThread>

#include <mpv/client.h>

#include <cstdio>

#ifdef Q_OS_WIN
#include <windows.h>
#endif

namespace {

// A worker that takes longer than this over one file is stuck: it's killed and the file
// counts as unreadable. (A grab gives up by itself after 20 s; this is the backstop.)
constexpr int kJobTimeoutMs = 45'000;

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
        // One frame needs neither read-ahead nor a decoder thread per CPU core (each
        // with its own frame buffers); several grabs run at once anyway.
        {"cache", "no"},
        {"demuxer-readahead-secs", "0"},
        {"demuxer-max-bytes", "4MiB"},
        {"demuxer-max-back-bytes", "0"},
        {"vd-lavc-threads", "2"},
    };
    for (const auto &[name, value] : options)
        mpv_set_option_string(mpv, name, value);

    if (mpv_initialize(mpv) >= 0) {
        Phones::registerStream(mpv);   // for mtp:// (videos on a phone)
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

// ---------------------------------------------------------------------------
// The worker process
// ---------------------------------------------------------------------------

// Reads "video<TAB>output" lines; for each, grabs the frame and writes
// "video<TAB>ok<TAB>duration<TAB>width<TAB>height<TAB>year". Ends when stdin closes,
// which also happens if the app goes away.
int Thumbnailer::runWorker()
{
#ifdef Q_OS_WIN
    // Thumbnails can wait; the app (and video playback) can't.
    SetPriorityClass(GetCurrentProcess(), BELOW_NORMAL_PRIORITY_CLASS);
#endif
    QFile in;
    QFile out;
    if (!in.open(stdin, QIODevice::ReadOnly) || !out.open(stdout, QIODevice::WriteOnly))
        return 1;
    const std::atomic_bool never = false;
    while (true) {
        const QByteArray line = in.readLine();
        if (line.isEmpty())
            return 0;
        // "video<TAB>output", or with a third field, what to open instead of the video's path.
        const QList<QByteArray> fields = line.trimmed().split('\t');
        if (fields.size() < 2)
            continue;
        const QString source = QString::fromUtf8(fields.size() > 2 ? fields[2] : fields[0]);
        const GrabResult r = grabFrame(source, QString::fromUtf8(fields[1]), never);
        const QByteArray answer = fields[0] + '\t' + (r.ok ? "1" : "0") + '\t' + QByteArray::number(r.duration)
            + '\t' + QByteArray::number(r.width) + '\t' + QByteArray::number(r.height)
            + '\t' + QByteArray::number(r.year) + '\n';
        out.write(answer);
        out.flush();
    }
}

// ---------------------------------------------------------------------------
// Thumbnailer
// ---------------------------------------------------------------------------

Thumbnailer::Thumbnailer(QObject *parent)
    : QObject(parent)
{
    // Each grab decodes one frame, mostly waiting on the disk; a few at once keep a big drive moving.
    m_maxWorkers = qBound(2, QThread::idealThreadCount() / 2, 4);
    m_pool.setMaxThreadCount(m_maxWorkers);

    m_watchdog.setInterval(2000);
    connect(&m_watchdog, &QTimer::timeout, this, &Thumbnailer::checkDeadlines);
}

Thumbnailer::~Thumbnailer()
{
    m_stopping = true;
    for (Worker *worker : std::as_const(m_workers)) {
        worker->process->disconnect(this);
        worker->process->kill();
        worker->process->waitForFinished(2000);
        delete worker;
    }
    m_pool.clear();
    m_pool.waitForDone();
}

void Thumbnailer::request(const QString &videoPath, const QString &outputFile, const QString &source)
{
    if (m_queued.contains(videoPath))
        return;
    for (const Worker *worker : std::as_const(m_workers))
        if (worker->job.video == videoPath)
            return;
    m_queue.append({videoPath, outputFile, source});
    m_queued.insert(videoPath);
    // Once for a whole library's worth of requests, made in one go.
    if (!std::exchange(m_dispatchPending, true)) {
        QMetaObject::invokeMethod(this, [this] {
            m_dispatchPending = false;
            dispatch();
        }, Qt::QueuedConnection);
    }
}

void Thumbnailer::clear()
{
    m_queue.clear();
    m_queued.clear();
}

bool Thumbnailer::isPhoneJob(const Job &job)
{
    return job.source.startsWith(QLatin1String("mtp://"));
}

bool Thumbnailer::phoneJobRunning() const
{
    return m_phoneInProcess
        || std::any_of(m_workers.begin(), m_workers.end(), [](const Worker *w) { return isPhoneJob(w->job); });
}

int Thumbnailer::takeableCount() const
{
    // A phone answers one request at a time: several grabs at once only get in each
    // other's way, so its videos go one after another (alongside other jobs).
    const bool phoneBusy = phoneJobRunning();
    bool phoneWaiting = false;
    int others = 0;
    for (const Job &job : m_queue) {
        if (isPhoneJob(job))
            phoneWaiting = true;
        else
            ++others;
    }
    return others + (phoneWaiting && !phoneBusy ? 1 : 0);
}

bool Thumbnailer::takeNext(Job &job)
{
    const bool phoneBusy = phoneJobRunning();
    for (qsizetype i = 0; i < m_queue.size(); ++i) {
        if (phoneBusy && isPhoneJob(m_queue[i]))
            continue;
        job = m_queue.takeAt(i);
        m_queued.remove(job.video);
        return true;
    }
    return false;
}

void Thumbnailer::dispatch()
{
    Job job;
    if (m_workersFailed) {
        while (m_pool.activeThreadCount() < m_maxWorkers && takeNext(job))
            grabInProcess(job);
        return;
    }
    for (Worker *worker : std::as_const(m_workers)) {
        if (worker->job.video.isEmpty() && !worker->closing && worker->process->state() == QProcess::Running) {
            if (!takeNext(job))
                break;
            send(worker, job);
        }
    }
    // More workers while there's more that can be done than the ones starting up will take.
    const auto starting = [this] {
        return std::count_if(m_workers.begin(), m_workers.end(),
                             [](const Worker *w) { return w->process->state() != QProcess::Running; });
    };
    while (m_workers.size() < m_maxWorkers && !m_workersFailed && takeableCount() > starting())
        startWorker();
}

void Thumbnailer::startWorker()
{
    auto *worker = new Worker;
    worker->process = new QProcess(this);
    // Its warnings ("Thumbnail failed for …") go where this process's do.
    worker->process->setProcessChannelMode(QProcess::ForwardedErrorChannel);
    m_workers.append(worker);

    connect(worker->process, &QProcess::started, this, [this, worker] {
        Job job;
        if (takeNext(job)) {
            send(worker, job);
        } else {
            worker->closing = true;   // the others got there first
            worker->process->closeWriteChannel();
        }
    });
    connect(worker->process, &QProcess::readyReadStandardOutput, this, [this, worker] { onOutput(worker); });
    connect(worker->process, &QProcess::finished, this, [this, worker] { onExited(worker); });
    connect(worker->process, &QProcess::errorOccurred, this, [this, worker](QProcess::ProcessError error) {
        if (error != QProcess::FailedToStart)
            return;   // a crash also ends in finished()
        qWarning() << "Could not start a thumbnail worker; making thumbnails in the app instead";
        m_workersFailed = true;
        onExited(worker);
    });

    worker->process->start(QCoreApplication::applicationFilePath(), {QStringLiteral("--thumbnail-worker")});
}

void Thumbnailer::send(Worker *worker, const Job &job)
{
    worker->job = job;
    worker->started.start();
    QByteArray line = job.video.toUtf8() + '\t' + job.output.toUtf8();
    if (!job.source.isEmpty())
        line += '\t' + job.source.toUtf8();
    worker->process->write(line + '\n');
    m_watchdog.start();
}

void Thumbnailer::onOutput(Worker *worker)
{
    worker->buffer += worker->process->readAllStandardOutput();
    qsizetype end;
    while ((end = worker->buffer.indexOf('\n')) >= 0) {
        const QList<QByteArray> fields = worker->buffer.left(end).trimmed().split('\t');
        worker->buffer.remove(0, end + 1);
        if (fields.size() != 6)
            continue;
        const QString video = QString::fromUtf8(fields[0]);
        if (video == worker->job.video)
            worker->job = {};
        emit finished(video, fields[1] == "1", fields[2].toDouble(), fields[3].toInt(), fields[4].toInt(),
                      fields[5].toInt());
    }

    if (!worker->job.video.isEmpty())
        return;
    Job job;
    if (takeNext(job)) {
        send(worker, job);
    } else {
        // Nothing (it can do) left: it ends at end of input, taking its memory with it.
        worker->closing = true;
        worker->process->closeWriteChannel();
    }
    dispatch();   // a phone job may have just finished, letting the next one go
}

void Thumbnailer::onExited(Worker *worker)
{
    m_workers.removeOne(worker);
    worker->process->deleteLater();
    const Job job = worker->job;
    delete worker;

    if (!job.video.isEmpty()) {
        if (m_workersFailed) {
            // Not the file's fault: try it again in this process.
            m_queue.prepend(job);
            m_queued.insert(job.video);
        } else {
            // It crashed (or was killed for taking too long) on this file: don't try it again.
            qWarning().noquote() << "Thumbnail worker stopped on" << job.video;
            emit finished(job.video, false, 0, 0, 0, 0);
        }
    }
    if (!m_stopping)
        dispatch();
}

void Thumbnailer::checkDeadlines()
{
    bool busy = false;
    for (Worker *worker : std::as_const(m_workers)) {
        if (worker->job.video.isEmpty())
            continue;
        busy = true;
        if (worker->started.elapsed() > kJobTimeoutMs)
            worker->process->kill();   // onExited reports the file
    }
    if (!busy)
        m_watchdog.stop();
}

void Thumbnailer::grabInProcess(const Job &job)
{
    if (isPhoneJob(job))
        m_phoneInProcess = true;
    m_pool.start([this, job] {
        if (m_stopping)
            return;
        const GrabResult r = grabFrame(job.source.isEmpty() ? job.video : job.source, job.output, m_stopping);
        if (m_stopping)
            return;
        QMetaObject::invokeMethod(this, [this, job, r] {
            if (isPhoneJob(job))
                m_phoneInProcess = false;
            emit finished(job.video, r.ok, r.duration, r.width, r.height, r.year);
            dispatch();
        });
    });
}
