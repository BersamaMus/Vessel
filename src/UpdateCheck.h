#pragma once

#include <QNetworkAccessManager>
#include <QObject>
#include <QPointer>
#include <QUrl>
#include <QtQml/qqmlregistration.h>

class QNetworkReply;

// Asks GitHub which version of Vessel is the newest, once each time the app opens (unless
// it's turned off in Settings). Only the question is sent: nothing about the user or their
// videos. Blimp and Glide do the same.
class UpdateCheck : public QObject
{
    Q_OBJECT
    QML_ELEMENT

    // Where a newer version can be downloaded: the latest release's page, which has the
    // Windows installer, the Android app and what's changed.
    Q_PROPERTY(QUrl releasesPage READ releasesPage CONSTANT)

public:
    explicit UpdateCheck(QObject *parent = nullptr);

    QUrl releasesPage() const;

    // Asks; newerVersionFound() answers if there is one. Nothing is said when this is the
    // newest, or GitHub couldn't be reached: it's asked again the next time Vessel opens.
    Q_INVOKABLE void check();

    // Whether version `latest` ("1.7.0") comes after `current` ("1.6.0"), comparing each
    // number in turn, so 1.10.0 is newer than 1.9.0. A missing number counts as 0.
    static bool isNewer(const QString &latest, const QString &current);

signals:
    // The newer version's number, like "1.7.0".
    void newerVersionFound(const QString &version);

private:
    QNetworkAccessManager m_network;
    QPointer<QNetworkReply> m_reply;
};
