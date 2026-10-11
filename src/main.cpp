#include <QCommandLineParser>
#include <QCryptographicHash>
#include <QDebug>
#include <QDir>
#include <QFileInfo>
#include <QGuiApplication>
#include <QIcon>
#include <QLocalServer>
#include <QLocalSocket>
#include <QQmlApplicationEngine>
#include <QQuickWindow>
#include <QStandardPaths>

#include <clocale>
#include <cstring>

#include "SeekPreview.h"
#include "SystemIntegration.h"
#include "Thumbnailer.h"

#ifdef Q_OS_WIN
#include <windows.h>
#endif

namespace {

// The app was called Better Video Player and kept its history, settings and thumbnails in
// %LOCALAPPDATA%\BetterVideoPlayer: the first time Vessel runs, they move to its folder.
// That folder may already exist with only Qt's caches in it (they're kept by app name),
// so what's in the old one is moved in, item by item, leaving the old caches behind. If
// anything can't be moved (the old app still has a file open, say), what was moved goes
// back and the old folder is used where it is this time, to be moved next time: the
// history is never split between the two.
void moveOldData()
{
    if (qEnvironmentVariableIsSet("VESSEL_DATA_DIR"))
        return;   // a test copy's own folder
    const QString current = QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation);
    const QDir old(QFileInfo(current).dir().filePath(QStringLiteral("BetterVideoPlayer")));
    const QDir now(current);
    const bool started = now.exists(QStringLiteral("settings.ini")) || now.exists(QStringLiteral("history.json"))
        || now.exists(QStringLiteral("metadata.json"));
    if (started || !old.exists())
        return;

    QDir().mkpath(current);
    QStringList moved;
    bool ok = true;
    const QStringList entries = old.entryList(QDir::AllEntries | QDir::Hidden | QDir::System | QDir::NoDotAndDotDot);
    for (const QString &name : entries) {
        if (name == QLatin1String("cache") || now.exists(name))
            continue;   // Qt's caches: rebuilt as needed
        if (!QDir().rename(old.filePath(name), now.filePath(name))) {
            ok = false;
            break;
        }
        moved.append(name);
    }
    if (ok) {
        // The old caches go, then the folder if that leaves it empty.
        QDir(old.filePath(QStringLiteral("cache"))).removeRecursively();
        QDir().rmdir(old.path());
        return;
    }
    for (const QString &name : std::as_const(moved))
        QDir().rename(now.filePath(name), old.filePath(name));
    qWarning() << "Couldn't move" << old.path() << "to" << current << "- using it where it is for now";
    qputenv("VESSEL_DATA_DIR", QDir::toNativeSeparators(old.path()).toUtf8());
}

} // namespace

int main(int argc, char *argv[])
{
    // A thumbnail worker (see Thumbnailer): no window, just frames grabbed on request.
    if (argc > 1 && std::strcmp(argv[1], "--thumbnail-worker") == 0) {
        std::setlocale(LC_NUMERIC, "C");   // as libmpv requires
        return Thumbnailer::runWorker();
    }
    // The uninstaller: no longer offered for videos ("Open with", Default apps).
    if (argc > 1 && std::strcmp(argv[1], "--unregister") == 0) {
        QCoreApplication app(argc, argv);
        SystemIntegration::unregisterFileTypes();
        return 0;
    }

    // libmpv renders with OpenGL, so Qt Quick must use it too (not Direct3D).
    QQuickWindow::setGraphicsApi(QSGRendererInterface::OpenGL);

#ifdef Q_OS_WIN
    // Looking at an empty card reader must not pop up "Insert a disk" dialogs.
    SetErrorMode(SEM_FAILCRITICALERRORS);
#endif

    QGuiApplication app(argc, argv);
    QGuiApplication::setApplicationName(QStringLiteral("Vessel"));
    QGuiApplication::setApplicationVersion(QStringLiteral(PROJECT_VERSION));
#ifndef Q_OS_WIN
    // On Windows the windows take the exe's own icon (app.rc).
    QGuiApplication::setWindowIcon(QIcon(QStringLiteral(":/icon/vessel.png")));
#endif

    // libmpv requires the C numeric locale (Qt may change it on some platforms).
    std::setlocale(LC_NUMERIC, "C");

    QCommandLineParser parser;
    parser.addHelpOption();
    parser.addVersionOption();
    parser.addPositionalArgument(QStringLiteral("file"), QStringLiteral("Video file to play."));
    parser.process(app);

    QString startFile;
    if (!parser.positionalArguments().isEmpty())
        startFile = QFileInfo(parser.positionalArguments().first()).absoluteFilePath();

    // One window: a video opened (from Explorer, say) while Vessel is open plays there.
    // Each data folder has its own window, so a test copy (VESSEL_DATA_DIR) stays apart.
    // Named before the old data is moved (which can point VESSEL_DATA_DIR at the old
    // folder for now), so every launch finds the same window whether that worked or not.
    const bool testCopy = qEnvironmentVariableIsSet("VESSEL_DATA_DIR");
    const QString dataDir = qEnvironmentVariable("VESSEL_DATA_DIR",
                                                 QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation));
    const QString serverName = QStringLiteral("Vessel-")
        + QString::fromLatin1(QCryptographicHash::hash(QDir::cleanPath(dataDir).toLower().toUtf8(),
                                                       QCryptographicHash::Sha1).toHex().left(16));
    {
        QLocalSocket running;
        running.connectToServer(serverName);
        if (running.waitForConnected(500)) {
#ifdef Q_OS_WIN
            AllowSetForegroundWindow(ASFW_ANY);   // so it can come to the front
#endif
            running.write(startFile.toUtf8() + '\n');
            running.waitForBytesWritten(1000);
            running.disconnectFromServer();
            return 0;
        }
    }
    QLocalServer::removeServer(serverName);   // left over from a crash
    QLocalServer server;
    server.listen(serverName);

    // Only once this is the one window, never from under one already using the data.
    moveOldData();

    // "Open with" and Default apps; a test copy leaves the real one's registration alone.
    if (!testCopy)
        SystemIntegration::registerFileTypes();

    QQmlApplicationEngine engine;
    engine.addImageProvider(QStringLiteral("seekpreview"), new SeekPreviewImages);
    engine.setInitialProperties({{QStringLiteral("startFile"), startFile}});
    QObject::connect(&engine, &QQmlApplicationEngine::objectCreationFailed, &app,
                     [] { QCoreApplication::exit(-1); }, Qt::QueuedConnection);
    engine.loadFromModule("Vessel", "Main");

    // Files sent by another start of the app (see above).
    QObject::connect(&server, &QLocalServer::newConnection, &app, [&server, &engine] {
        while (QLocalSocket *client = server.nextPendingConnection()) {
            QObject::connect(client, &QLocalSocket::disconnected, client, &QObject::deleteLater);
            QObject::connect(client, &QLocalSocket::readyRead, client, [client, &engine] {
                while (client->canReadLine()) {
                    const QString path = QString::fromUtf8(client->readLine()).trimmed();
                    if (QObject *window = engine.rootObjects().value(0))
                        QMetaObject::invokeMethod(window, "openFromOutside", Q_ARG(QVariant, path));
                }
            });
        }
    });

    return app.exec();
}
