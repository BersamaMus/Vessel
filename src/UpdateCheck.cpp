#include "UpdateCheck.h"

#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QVersionNumber>

UpdateCheck::UpdateCheck(QObject *parent)
    : QObject(parent)
{
}

QUrl UpdateCheck::releasesPage() const
{
    return QUrl(QStringLiteral("https://github.com/BersamaMus/Vessel/releases/latest"));
}

bool UpdateCheck::isNewer(const QString &latest, const QString &current)
{
    // normalized() drops trailing zeros, so 1.7 and 1.7.0 are the same version.
    return QVersionNumber::compare(QVersionNumber::fromString(latest).normalized(),
                                   QVersionNumber::fromString(current).normalized()) > 0;
}

void UpdateCheck::check()
{
    if (m_reply)
        return;
    QNetworkRequest request(QUrl(QStringLiteral("https://api.github.com/repos/BersamaMus/Vessel/releases/latest")));
    request.setHeader(QNetworkRequest::UserAgentHeader, QStringLiteral("Vessel (https://github.com/BersamaMus/Vessel)"));
    request.setRawHeader("Accept", "application/vnd.github+json");
    request.setTransferTimeout(30000);
    QNetworkReply *reply = m_network.get(request);
    m_reply = reply;
    connect(reply, &QNetworkReply::finished, this, [this, reply] {
        reply->deleteLater();
        // Offline, or an answer that isn't GitHub's (a Wi-Fi sign-in page): try again next time.
        if (reply->error() != QNetworkReply::NoError
            || reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt() != 200)
            return;
        // Release tags are "v1.7.0".
        QString tag = QJsonDocument::fromJson(reply->readAll()).object().value(QStringLiteral("tag_name")).toString();
        if (tag.startsWith(QLatin1Char('v')))
            tag.remove(0, 1);
        if (!tag.isEmpty() && isNewer(tag, QStringLiteral(PROJECT_VERSION)))
            emit newerVersionFound(tag);
    });
}
