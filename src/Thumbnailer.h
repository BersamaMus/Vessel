#pragma once

#include <QElapsedTimer>
#include <QList>
#include <QObject>
#include <QSet>
#include <QThreadPool>
#include <QTimer>

#include <atomic>

class QProcess;

// Grabs a still frame and reads the duration, size and year of video files in the
// background, with headless libmpv (vo=image).
//
// The work happens in worker processes: this program started with
// --thumbnail-worker, taking one file at a time over stdin and answering on stdout.
// A file that crashes the decoder then only takes its worker down (the file is
// reported as unreadable and the rest carry on), a stuck one is killed after a
// while, and the decoders' memory goes away with the workers once the queue is
// empty. If workers can't be started, frames are grabbed in this process instead.
class Thumbnailer : public QObject
{
    Q_OBJECT

public:
    explicit Thumbnailer(QObject *parent = nullptr);
    ~Thumbnailer() override;

    // Saves a JPEG of `videoPath` to `outputFile`; emits finished() when done. Requests
    // run in the order made; one for a video that's already queued or being done is ignored.
    // `source` is what to open instead of the path, if anything (a phone's mtp:// stream).
    void request(const QString &videoPath, const QString &outputFile, const QString &source = {});
    // Drops queued requests (running ones still finish).
    void clear();
    // While a phone's video is playing, its thumbnails wait: the phone answers one request
    // at a time, and playback shouldn't have to queue behind them. A grab already under way
    // finishes.
    void setPhoneHeld(bool held);

    // The worker process's main loop (see main.cpp); returns its exit code.
    static int runWorker();

signals:
    // `ok` is false if no frame could be grabbed. The rest are 0 if unknown; `year`
    // comes from the file's tags (the release date some files carry).
    void finished(const QString &videoPath, bool ok, double duration, int width, int height, int year);

private:
    struct Job
    {
        QString video;
        QString output;
        QString source;   // what libmpv opens, if not the video's path
    };
    struct Worker
    {
        QProcess *process = nullptr;
        Job job;                // the one it's on; job.video is empty while idle
        QElapsedTimer started;  // when that job was sent
        QByteArray buffer;      // output not yet ending in a newline
        bool closing = false;   // told to finish (its input is closed): no more jobs
    };

    void dispatch();
    // The next job a free worker can take: the first in the queue, except that a phone's
    // videos go one at a time. False if there's none it can take now.
    bool takeNext(Job &job);
    int takeableCount() const;
    bool phoneJobRunning() const;
    static bool isPhoneJob(const Job &job);
    void startWorker();
    void send(Worker *worker, const Job &job);
    void onOutput(Worker *worker);
    void onExited(Worker *worker);
    void checkDeadlines();
    void grabInProcess(const Job &job);

    QList<Job> m_queue;
    QSet<QString> m_queued;    // videos in m_queue
    QList<Worker *> m_workers;
    int m_maxWorkers = 2;
    bool m_workersFailed = false;   // couldn't start one: grab in this process instead
    bool m_dispatchPending = false;
    bool m_phoneInProcess = false;   // the fallback is grabbing from a phone
    bool m_phoneHeld = false;        // see setPhoneHeld
    QTimer m_watchdog;

    // The in-process fallback.
    QThreadPool m_pool;
    std::atomic_bool m_stopping = false;
};
