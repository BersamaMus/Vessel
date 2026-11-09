#pragma once

#include <QDateTime>
#include <QNetworkAccessManager>
#include <QObject>
#include <QPointer>
#include <QTimer>
#include <QVariantList>
#include <QtQml/qqmlregistration.h>

class QNetworkReply;

// "Search online…" in the subtitles menu: finds subtitles for a video on OpenSubtitles
// (opensubtitles.com's REST API) and downloads the one picked, next to the video. Nothing
// goes online until the user searches. Each search asks two ways at once: by the file's
// hash, which only finds subtitles made for this exact release (so they're in sync), and
// by its name. Without an account, OpenSubtitles allows a few downloads a day; once they're
// used up, the dialog sends the user to the subtitle's page on the website instead.
class SubtitleSearch : public QObject
{
    Q_OBJECT
    QML_ELEMENT

    // Vessel was built with an API key (see CMakeLists.txt); without one the feature is hidden.
    Q_PROPERTY(bool available READ available CONSTANT)
    Q_PROPERTY(bool searching READ searching NOTIFY searchingChanged)
    // A search has finished (so no results means none were found, not none looked for yet).
    Q_PROPERTY(bool searched READ searched NOTIFY searchingChanged)
    // [{fileId, name, title, language, downloads, hashMatch, hearingImpaired, machine, url}],
    // those that match the file's hash first.
    Q_PROPERTY(QVariantList results READ results NOTIFY resultsChanged)
    // The subtitle being downloaded (its fileId), or 0.
    Q_PROPERTY(int downloading READ downloading NOTIFY downloadingChanged)
    // What went wrong, for the dialog; "" if nothing.
    Q_PROPERTY(QString error READ error NOTIFY errorChanged)
    // Today's downloads are used up; resetText says when more are allowed ("in 6 hours").
    Q_PROPERTY(bool limitReached READ limitReached NOTIFY limitChanged)
    Q_PROPERTY(QString resetText READ resetText NOTIFY limitChanged)
    // When they come back (ISO date and time, UTC; "" if they haven't run out), kept in the
    // settings so a restart doesn't forget the limit and spend a request finding it again.
    Q_PROPERTY(QString resetAt READ resetAt WRITE setResetAt NOTIFY limitChanged)
    // Where subtitles go when the video's own folder can't take them (a phone, a read-only
    // drive): set from VideoLibrary.subtitleDownloadFolder().
    Q_PROPERTY(QString fallbackFolder MEMBER m_fallbackFolder)

public:
    explicit SubtitleSearch(QObject *parent = nullptr);

    bool available() const;
    bool searching() const { return m_pending > 0; }
    bool searched() const { return m_searched; }
    QVariantList results() const { return m_results; }
    int downloading() const { return m_downloading; }
    QString error() const { return m_error; }
    bool limitReached() const;
    QString resetText() const;
    QString resetAt() const;
    void setResetAt(const QString &resetAt);

    // The languages that can be searched, for a menu: [{value: "en", label: "English"}].
    Q_INVOKABLE QVariantList languages() const;
    // The one of those that Windows is set to, or English.
    Q_INVOKABLE QString systemLanguage() const;

    // Looks for subtitles for the video at `path` in `language`, by its hash and by `query`
    // (its title, which the user can change). Replaces any search still going.
    Q_INVOKABLE void search(const QString &path, const QString &query, const QString &language);
    // Downloads a result of the last search and saves it for the video. downloaded() says
    // where it went; on failure, error (or limitReached) says why.
    Q_INVOKABLE void download(int fileId);
    // Stops whatever is going on (the dialog was closed).
    Q_INVOKABLE void cancel();

signals:
    void searchingChanged();
    void resultsChanged();
    void downloadingChanged();
    void errorChanged();
    void limitChanged();
    // A subtitle (OpenSubtitles' fileId) for the video at `path` was saved as `file`.
    void downloaded(const QString &path, int fileId, const QString &file);

private:
    void searchBy(const QList<std::pair<QString, QString>> &query);
    void finishSearch();
    void saveDownload(QNetworkReply *reply, int fileId, const QString &fileName, const QString &language);
    void setError(const QString &error);
    void setLimit(const QDateTime &resetAt);
    void setDownloading(int fileId);
    void abortAll();
    void track(QNetworkReply *reply);

    QNetworkAccessManager m_network;
    QList<QPointer<QNetworkReply>> m_replies;
    // Goes up with each search, so answers to an older one are ignored.
    int m_generation = 0;
    int m_pending = 0;
    bool m_searched = false;
    QString m_path;
    QString m_language;
    QList<QVariantMap> m_found;   // from the searches so far, until they're all in
    QVariantList m_results;
    int m_downloading = 0;
    QString m_error;
    QDateTime m_resetAt;   // when the daily downloads come back; invalid if they haven't run out
    // While they're out: once a minute, so "in 6 hours" counts down and the limit lifts on time.
    QTimer m_limitTimer;
    QString m_fallbackFolder;
};
