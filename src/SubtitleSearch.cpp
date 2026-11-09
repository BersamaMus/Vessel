#include "SubtitleSearch.h"

#include "VideoFormats.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QFutureWatcher>
#include <QHash>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QLocale>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QSaveFile>
#include <QtConcurrent/QtConcurrentRun>
#include <QtEndian>

#include <algorithm>

// Set by CMakeLists.txt from opensubtitles.key, which is kept out of git.
#ifndef OPENSUBTITLES_API_KEY
#define OPENSUBTITLES_API_KEY ""
#endif

namespace {

// The languages offered, by OpenSubtitles' codes (ISO 639-1, plus the Portuguese and
// Chinese variants it tells apart).
struct Language
{
    const char *code;
    const char *name;
};
constexpr Language kLanguages[] = {
    {"ar", "Arabic"}, {"bg", "Bulgarian"}, {"zh-cn", "Chinese (simplified)"}, {"zh-tw", "Chinese (traditional)"},
    {"hr", "Croatian"}, {"cs", "Czech"}, {"da", "Danish"}, {"nl", "Dutch"}, {"en", "English"},
    {"et", "Estonian"}, {"fi", "Finnish"}, {"fr", "French"}, {"de", "German"}, {"el", "Greek"},
    {"he", "Hebrew"}, {"hi", "Hindi"}, {"hu", "Hungarian"}, {"id", "Indonesian"}, {"it", "Italian"},
    {"ja", "Japanese"}, {"ko", "Korean"}, {"ms", "Malay"}, {"no", "Norwegian"}, {"fa", "Persian"},
    {"pl", "Polish"}, {"pt-pt", "Portuguese"}, {"pt-br", "Portuguese (Brazil)"}, {"ro", "Romanian"},
    {"ru", "Russian"}, {"sr", "Serbian"}, {"sk", "Slovak"}, {"sl", "Slovenian"}, {"es", "Spanish"},
    {"sv", "Swedish"}, {"th", "Thai"}, {"tr", "Turkish"}, {"uk", "Ukrainian"}, {"vi", "Vietnamese"},
};

QString languageName(const QString &code)
{
    for (const Language &language : kLanguages)
        if (code.compare(QLatin1String(language.code), Qt::CaseInsensitive) == 0)
            return QString::fromLatin1(language.name);
    return code;
}

// OpenSubtitles' hash of a video: its size plus its first and last 64 KB, read as 64-bit
// numbers and added up (letting it overflow). Quick even for a huge file, and the same for
// every copy of the same release, whatever it's called. "" if the file can't be read or is
// under 128 KB.
QString movieHash(const QString &path)
{
    constexpr qint64 chunk = 64 * 1024;
    QFile file(path);
    if (!file.open(QIODevice::ReadOnly) || file.size() < 2 * chunk)
        return {};
    quint64 hash = quint64(file.size());
    const auto add = [&](qint64 offset) {
        if (!file.seek(offset))
            return false;
        const QByteArray data = file.read(chunk);
        if (data.size() != chunk)
            return false;
        for (qint64 i = 0; i < chunk; i += 8)
            hash += qFromLittleEndian<quint64>(data.constData() + i);
        return true;
    };
    if (!add(0) || !add(file.size() - chunk))
        return {};
    return QStringLiteral("%1").arg(hash, 16, 16, QLatin1Char('0'));
}

// OpenSubtitles asks every app to say what it is, as the name its key was registered under,
// on everything it asks for: the API and the files it links to.
const QString kUserAgent = QStringLiteral("Vessel v" PROJECT_VERSION);

QNetworkRequest apiRequest(const QString &endpoint, const QString &query = {})
{
    QUrl url(QStringLiteral("https://api.opensubtitles.com/api/v1/") + endpoint);
    url.setQuery(query, QUrl::StrictMode);
    QNetworkRequest request(url);
    request.setRawHeader("Api-Key", OPENSUBTITLES_API_KEY);
    request.setHeader(QNetworkRequest::UserAgentHeader, kUserAgent);
    request.setRawHeader("Accept", "application/json");
    request.setTransferTimeout(20000);
    return request;
}

// What to tell the user when a request failed, from how it failed.
QString failureText(QNetworkReply *reply, const QJsonObject &answer)
{
    const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    if (status == 0)
        return SubtitleSearch::tr("Couldn't reach OpenSubtitles. Check your internet connection.");
    if (status == 401 || status == 403)
        return SubtitleSearch::tr("OpenSubtitles didn't accept this copy of Vessel. Try updating Vessel.");
    if (status == 429)
        return SubtitleSearch::tr("OpenSubtitles is busy. Try again in a moment.");
    const QString message = answer.value(QStringLiteral("message")).toString();
    return message.isEmpty() ? SubtitleSearch::tr("OpenSubtitles couldn't do that just now (error %1).").arg(status)
                             : message;
}

} // namespace

SubtitleSearch::SubtitleSearch(QObject *parent)
    : QObject(parent)
{
    m_limitTimer.setInterval(60 * 1000);
    connect(&m_limitTimer, &QTimer::timeout, this, [this] {
        if (!limitReached())
            m_limitTimer.stop();
        emit limitChanged();
    });
}

bool SubtitleSearch::available() const
{
    return OPENSUBTITLES_API_KEY[0] != '\0';
}

bool SubtitleSearch::limitReached() const
{
    return m_resetAt.isValid() && QDateTime::currentDateTimeUtc() < m_resetAt;
}

QString SubtitleSearch::resetText() const
{
    if (!limitReached())
        return {};
    const qint64 minutes = QDateTime::currentDateTimeUtc().secsTo(m_resetAt) / 60 + 1;
    if (minutes < 60)
        return tr("More downloads in %n minute(s).", nullptr, int(minutes));
    return tr("More downloads in %n hour(s).", nullptr, int((minutes + 30) / 60));
}

QString SubtitleSearch::resetAt() const
{
    return limitReached() ? m_resetAt.toString(Qt::ISODate) : QString();
}

void SubtitleSearch::setResetAt(const QString &resetAt)
{
    const QDateTime time = QDateTime::fromString(resetAt, Qt::ISODate);
    if (time == m_resetAt || (!time.isValid() && !m_resetAt.isValid()))
        return;
    setLimit(time);
}

void SubtitleSearch::setLimit(const QDateTime &resetAt)
{
    m_resetAt = resetAt.toUTC();
    if (limitReached())
        m_limitTimer.start();
    else
        m_limitTimer.stop();
    emit limitChanged();
}

QVariantList SubtitleSearch::languages() const
{
    QVariantList list;
    for (const Language &language : kLanguages)
        list.append(QVariantMap{{QStringLiteral("value"), QString::fromLatin1(language.code)},
                                {QStringLiteral("label"), QString::fromLatin1(language.name)}});
    return list;
}

QString SubtitleSearch::systemLanguage() const
{
    const QLocale locale = QLocale::system();
    const QString name = locale.name().toLower();   // "pt_br"
    QString code = name.section(QLatin1Char('_'), 0, 0);
    if (code == QLatin1String("pt"))
        code = name == QLatin1String("pt_br") ? QStringLiteral("pt-br") : QStringLiteral("pt-pt");
    else if (code == QLatin1String("zh"))
        code = locale.script() == QLocale::TraditionalChineseScript ? QStringLiteral("zh-tw") : QStringLiteral("zh-cn");
    else if (code == QLatin1String("nb") || code == QLatin1String("nn"))
        code = QStringLiteral("no");
    for (const Language &language : kLanguages)
        if (code == QLatin1String(language.code))
            return code;
    return QStringLiteral("en");
}

void SubtitleSearch::search(const QString &path, const QString &query, const QString &language)
{
    // Answers to the last search are ignored from here on (aborting one answers it at once).
    const int generation = ++m_generation;
    abortAll();
    m_path = path;
    m_language = language;
    m_found.clear();
    m_searched = false;
    m_results.clear();
    emit resultsChanged();
    setError({});
    setDownloading(0);
    emit limitChanged();   // it may have run out since

    // By name. OpenSubtitles makes sense of a file name ("House.S01E02.720p") on its own.
    if (!query.trimmed().isEmpty())
        searchBy({{QStringLiteral("languages"), language}, {QStringLiteral("query"), query.trimmed().toLower()}});

    // By hash, worked out off the UI thread: the file may be on a slow drive. Not for a phone's
    // video, which is read through the phone (see PhoneDevices), or one that's too small.
    if (!path.startsWith(QLatin1String("mtp://"))) {
        ++m_pending;
        auto *watcher = new QFutureWatcher<QString>(this);
        connect(watcher, &QFutureWatcherBase::finished, this, [this, watcher, generation, language] {
            watcher->deleteLater();
            if (generation != m_generation)
                return;
            --m_pending;
            const QString hash = watcher->result();
            if (!hash.isEmpty())
                searchBy({{QStringLiteral("languages"), language}, {QStringLiteral("moviehash"), hash}});
            else if (m_pending == 0)
                finishSearch();
        });
        watcher->setFuture(QtConcurrent::run(movieHash, path));
    }
    if (m_pending == 0)
        finishSearch();
    else
        emit searchingChanged();
}

// One GET /subtitles. The parameters go in alphabetical order with "+" for spaces, as
// OpenSubtitles asks (otherwise it answers with a redirect to the same thing written its way).
void SubtitleSearch::searchBy(const QList<std::pair<QString, QString>> &parameters)
{
    QStringList query;
    for (const auto &[name, value] : parameters)
        query.append(name + QLatin1Char('=')
                     + QString::fromLatin1(QUrl::toPercentEncoding(value, " ")).replace(QLatin1Char(' '), QLatin1Char('+')));
    ++m_pending;
    const int generation = m_generation;
    QNetworkReply *reply = m_network.get(apiRequest(QStringLiteral("subtitles"), query.join(QLatin1Char('&'))));
    track(reply);
    connect(reply, &QNetworkReply::finished, this, [this, reply, generation] {
        reply->deleteLater();
        if (generation != m_generation)
            return;
        --m_pending;
        const QJsonObject answer = QJsonDocument::fromJson(reply->readAll()).object();
        if (reply->error() != QNetworkReply::NoError) {
            setError(failureText(reply, answer));
        } else {
            for (const QJsonValue &item : answer.value(QStringLiteral("data")).toArray()) {
                const QJsonObject attributes = item[QStringLiteral("attributes")].toObject();
                const QJsonArray files = attributes.value(QStringLiteral("files")).toArray();
                // A film split over several files (CD1, CD2) doesn't fit a single video.
                if (files.size() != 1)
                    continue;
                const QJsonObject file = files.first().toObject();
                const QJsonObject feature = attributes.value(QStringLiteral("feature_details")).toObject();
                const QString release = attributes.value(QStringLiteral("release")).toString().trimmed();
                const int year = feature.value(QStringLiteral("year")).toInt();
                const QString title = feature.value(QStringLiteral("title")).toString();
                m_found.append(QVariantMap{
                    {QStringLiteral("fileId"), file.value(QStringLiteral("file_id")).toInt()},
                    {QStringLiteral("name"), release.isEmpty() ? file.value(QStringLiteral("file_name")).toString() : release},
                    {QStringLiteral("title"), year > 0 ? QStringLiteral("%1 (%2)").arg(title).arg(year) : title},
                    {QStringLiteral("language"), languageName(attributes.value(QStringLiteral("language")).toString())},
                    {QStringLiteral("languageCode"), attributes.value(QStringLiteral("language")).toString().toLower()},
                    {QStringLiteral("downloads"), attributes.value(QStringLiteral("download_count")).toInt()},
                    {QStringLiteral("hashMatch"), attributes.value(QStringLiteral("moviehash_match")).toBool()},
                    {QStringLiteral("hearingImpaired"), attributes.value(QStringLiteral("hearing_impaired")).toBool()},
                    {QStringLiteral("machine"), attributes.value(QStringLiteral("ai_translated")).toBool()
                                                    || attributes.value(QStringLiteral("machine_translated")).toBool()},
                    {QStringLiteral("url"), attributes.value(QStringLiteral("url")).toString()},
                });
            }
        }
        if (m_pending == 0)
            finishSearch();
    });
}

// Both searches are in: one list, each subtitle once, the hash's matches first and the
// rest in OpenSubtitles' order (the best match for the name first).
void SubtitleSearch::finishSearch()
{
    QVariantList results;
    QHash<int, qsizetype> byFile;
    for (const QVariantMap &found : std::as_const(m_found)) {
        const int fileId = found.value(QStringLiteral("fileId")).toInt();
        if (fileId == 0)
            continue;
        const auto seen = byFile.constFind(fileId);
        if (seen == byFile.cend()) {
            byFile.insert(fileId, results.size());
            results.append(found);
        } else if (found.value(QStringLiteral("hashMatch")).toBool()) {
            results[*seen] = found;
        }
    }
    std::stable_partition(results.begin(), results.end(), [](const QVariant &result) {
        return result.toMap().value(QStringLiteral("hashMatch")).toBool();
    });
    m_found.clear();
    m_results = results;
    m_searched = true;
    // Results from one search are shown even if the other failed.
    if (!m_results.isEmpty())
        setError({});
    emit resultsChanged();
    emit searchingChanged();
}

void SubtitleSearch::download(int fileId)
{
    if (m_downloading != 0 || fileId == 0)
        return;
    QString language = m_language;
    for (const QVariant &result : std::as_const(m_results)) {
        const QVariantMap map = result.toMap();
        if (map.value(QStringLiteral("fileId")).toInt() == fileId)
            language = map.value(QStringLiteral("languageCode")).toString();
    }
    setError({});
    setDownloading(fileId);

    // First OpenSubtitles is asked for the file, which counts as one of the day's downloads
    // and gives a link to it; then the file is fetched from there.
    QNetworkRequest request = apiRequest(QStringLiteral("download"));
    request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
    const QByteArray body = QJsonDocument(QJsonObject{{QStringLiteral("file_id"), fileId}}).toJson(QJsonDocument::Compact);
    const int generation = m_generation;
    QNetworkReply *reply = m_network.post(request, body);
    track(reply);
    connect(reply, &QNetworkReply::finished, this, [this, reply, generation, fileId, language] {
        reply->deleteLater();
        if (generation != m_generation)
            return;
        const QJsonObject answer = QJsonDocument::fromJson(reply->readAll()).object();
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        const QString link = answer.value(QStringLiteral("link")).toString();
        const QDateTime resetAt = QDateTime::fromString(answer.value(QStringLiteral("reset_time_utc")).toString(), Qt::ISODate);

        // Out of downloads for today (406), or this was the last one.
        if (status == 406 || (link.isEmpty() && answer.value(QStringLiteral("remaining")).toInt(1) <= 0)) {
            setLimit(resetAt.isValid() ? resetAt : QDateTime::currentDateTimeUtc().addSecs(3600));
            setDownloading(0);
            return;
        }
        if (reply->error() != QNetworkReply::NoError || link.isEmpty()) {
            setError(failureText(reply, answer));
            setDownloading(0);
            return;
        }
        if (answer.value(QStringLiteral("remaining")).toInt(1) <= 0 && resetAt.isValid())
            setLimit(resetAt);

        QNetworkRequest fileRequest{QUrl(link)};
        fileRequest.setHeader(QNetworkRequest::UserAgentHeader, kUserAgent);
        fileRequest.setTransferTimeout(30000);
        QNetworkReply *file = m_network.get(fileRequest);
        track(file);
        const QString fileName = answer.value(QStringLiteral("file_name")).toString();
        connect(file, &QNetworkReply::finished, this, [this, file, generation, fileId, fileName, language] {
            file->deleteLater();
            if (generation != m_generation)
                return;
            saveDownload(file, fileId, fileName, language);
        });
    });
}

// Next to the video and named after it, so it's found again next time and moves with the
// video: "Movie.en.srt", or "Movie.en.2.srt" if that's taken. When the video's folder can't
// take it (a phone, a read-only drive), in Vessel's own folder instead; the choice of
// subtitle remembers where it is either way.
void SubtitleSearch::saveDownload(QNetworkReply *reply, int fileId, const QString &fileName, const QString &language)
{
    setDownloading(0);
    const QByteArray data = reply->readAll();
    if (reply->error() != QNetworkReply::NoError || data.isEmpty()) {
        setError(failureText(reply, {}));
        return;
    }

    const QString suffix = VideoFormats::isSubtitleSuffix(QFileInfo(fileName).suffix())
        ? QFileInfo(fileName).suffix().toLower() : QStringLiteral("srt");
    const QFileInfo video(m_path);
    const QString base = video.completeBaseName() + (language.isEmpty() ? QString() : QLatin1Char('.') + language);
    QStringList folders;
    if (!m_path.startsWith(QLatin1String("mtp://")))
        folders.append(video.absolutePath());
    if (!m_fallbackFolder.isEmpty())
        folders.append(m_fallbackFolder);

    for (const QString &folder : std::as_const(folders)) {
        QDir().mkpath(folder);
        QString target = folder + QLatin1Char('/') + base + QLatin1Char('.') + suffix;
        for (int n = 2; QFileInfo::exists(target); ++n)
            target = folder + QStringLiteral("/%1.%2.%3").arg(base).arg(n).arg(suffix);
        QSaveFile file(target);
        if (file.open(QIODevice::WriteOnly) && file.write(data) == data.size() && file.commit()) {
            emit downloaded(m_path, fileId, target);
            return;
        }
    }
    setError(tr("Couldn't save the subtitle file."));
}

void SubtitleSearch::cancel()
{
    ++m_generation;
    abortAll();
    m_found.clear();
    setDownloading(0);
    emit searchingChanged();
}

void SubtitleSearch::abortAll()
{
    m_pending = 0;
    for (const QPointer<QNetworkReply> &reply : std::as_const(m_replies))
        if (reply)
            reply->abort();
    m_replies.clear();
}

// Kept so they can be aborted; finished ones have deleted themselves.
void SubtitleSearch::track(QNetworkReply *reply)
{
    m_replies.removeIf([](const QPointer<QNetworkReply> &old) { return old.isNull(); });
    m_replies.append(reply);
}

void SubtitleSearch::setError(const QString &error)
{
    if (m_error == error)
        return;
    m_error = error;
    emit errorChanged();
}

void SubtitleSearch::setDownloading(int fileId)
{
    if (m_downloading == fileId)
        return;
    m_downloading = fileId;
    emit downloadingChanged();
}
