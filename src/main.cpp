#include <QCommandLineParser>
#include <QFileInfo>
#include <QGuiApplication>
#include <QQmlApplicationEngine>
#include <QQuickWindow>

#include <clocale>

#ifdef Q_OS_WIN
#include <windows.h>
#endif

int main(int argc, char *argv[])
{
    // libmpv renders with OpenGL, so Qt Quick must use it too (not Direct3D).
    QQuickWindow::setGraphicsApi(QSGRendererInterface::OpenGL);

#ifdef Q_OS_WIN
    // Looking at an empty card reader must not pop up "Insert a disk" dialogs.
    SetErrorMode(SEM_FAILCRITICALERRORS);
#endif

    QGuiApplication app(argc, argv);
    QGuiApplication::setApplicationName(QStringLiteral("BetterVideoPlayer"));
    QGuiApplication::setApplicationVersion(QStringLiteral(PROJECT_VERSION));

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

    QQmlApplicationEngine engine;
    engine.setInitialProperties({{QStringLiteral("startFile"), startFile}});
    QObject::connect(&engine, &QQmlApplicationEngine::objectCreationFailed, &app,
                     [] { QCoreApplication::exit(-1); }, Qt::QueuedConnection);
    engine.loadFromModule("BetterVideoPlayer", "Main");

    return app.exec();
}
