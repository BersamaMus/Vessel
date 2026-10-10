#pragma once

#include <QObject>
#include <QThreadPool>

#include <atomic>

// Grabs a still frame and reads the duration, size and year of video files in
// the background, using headless libmpv instances (vo=image).
class Thumbnailer : public QObject
{
    Q_OBJECT

public:
    explicit Thumbnailer(QObject *parent = nullptr);
    ~Thumbnailer() override;

    // Saves a JPEG of `videoPath` to `outputFile`; emits finished() when done.
    void request(const QString &videoPath, const QString &outputFile);
    // Drops queued requests (running ones still finish).
    void clear();

signals:
    // `ok` is false if no frame could be grabbed. The rest are 0 if unknown; `year`
    // comes from the file's tags (the release date some files carry).
    void finished(const QString &videoPath, bool ok, double duration, int width, int height, int year);

private:
    QThreadPool m_pool;
    std::atomic_bool m_stopping = false;
};
