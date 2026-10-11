#include "VideoFormats.h"

#include <QFile>
#include <QSet>
#include <QStringList>

namespace VideoFormats {

namespace {

const QStringList &extensions()
{
    // The ones people's videos come in: phones, cameras, downloads, DVDs. Editing and
    // broadcast formats (MXF, DV), raw streams and rarities are left out; they still play
    // when opened or dropped on the window.
    static const QStringList list = {
        // MPEG-4 / QuickTime family, Matroska, WebM
        "mp4", "m4v", "mov", "3gp", "3g2", "mkv", "webm",
        // Windows and Flash
        "avi", "divx", "wmv", "asf", "flv",
        // MPEG: DVDs, camcorders (AVCHD), TV recordings
        "mpg", "mpeg", "vob", "ts", "m2ts", "mts",
        // Others
        "ogv", "rm", "rmvb",
    };
    return list;
}

} // namespace

bool isVideoSuffix(const QString &suffix)
{
    static const QSet<QString> set(extensions().cbegin(), extensions().cend());
    return set.contains(suffix.toLower());
}

bool looksLikeVideo(const QString &path)
{
    if (!path.endsWith(QLatin1String(".ts"), Qt::CaseInsensitive))
        return true;
    // MPEG-TS packets are 188 bytes (192 in .m2ts-style files), each starting with 0x47.
    QFile file(path);
    if (!file.open(QIODevice::ReadOnly))
        return false;
    const QByteArray head = file.read(193);
    const auto syncAt = [&](int offset) { return head.size() > offset && head[offset] == char(0x47); };
    return (syncAt(0) && syncAt(188)) || (syncAt(4) && syncAt(192));
}

QString nameFilter()
{
    QStringList patterns;
    for (const QString &extension : extensions())
        patterns.append(QStringLiteral("*.") + extension);
    return patterns.join(QLatin1Char(' '));
}

namespace {

const QStringList &subtitleExtensions()
{
    // .sub is MicroDVD text, or VobSub pictures next to a .idx (which mpv also reads).
    static const QStringList list = {"srt", "ass", "ssa", "vtt", "sub"};
    return list;
}

} // namespace

bool isSubtitleSuffix(const QString &suffix)
{
    return subtitleExtensions().contains(suffix.toLower());
}

QStringList subtitlePatterns()
{
    QStringList patterns;
    for (const QString &extension : subtitleExtensions())
        patterns.append(QStringLiteral("*.") + extension);
    return patterns;
}

} // namespace VideoFormats
