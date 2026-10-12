#pragma once

#include <QString>
#include <QStringList>

// Which files are videos, by extension: one list for the library, drives, phones and
// the Open dialog. libmpv (FFmpeg) plays far more than this; these are the extensions
// people's videos come with. Left out on purpose besides rarities: .mod (also tracker
// music), .ogg (almost always audio), .m4p (DRM-locked).
namespace VideoFormats {

// "mp4", "MKV": the extension alone, any case.
bool isVideoSuffix(const QString &suffix);

// A ".ts" file can be an MPEG transport stream or TypeScript code ("types.d.ts"):
// whether this one is a video, by its first bytes. Other extensions are taken at their word.
bool looksLikeVideo(const QString &path);

// For a file dialog: "*.mp4 *.mkv …".
QString nameFilter();

// Subtitle files, the same way: one list for finding, adding and dropping them, and for
// moving them along with their video.
bool isSubtitleSuffix(const QString &suffix);
// "*.srt *.ass …", for QDir and file dialogs.
QStringList subtitlePatterns();

} // namespace VideoFormats
