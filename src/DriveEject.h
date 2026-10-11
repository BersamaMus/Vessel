#pragma once

#include <QString>

// Safely removes a drive, as the taskbar's "Safely Remove Hardware" does: Windows asks every
// program to let go of it first, and refuses if one won't. A memory card has just its card
// ejected, so the reader stays usable. Blocks for a moment; call it off the UI thread.
// `rootPath` is "E:/". Returns an error message, or "" once it's safe to unplug.
QString ejectDrive(const QString &rootPath, const QString &kind);
