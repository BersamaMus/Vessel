#include "MainWindow.h"

#include <QApplication>
#include <QCommandLineParser>

#include <clocale>

int main(int argc, char *argv[])
{
    QApplication app(argc, argv);
    QApplication::setApplicationName(QStringLiteral("Better Video Player"));
    QApplication::setApplicationVersion(QStringLiteral(PROJECT_VERSION));

    // libmpv requires the C numeric locale (Qt may change it on some platforms).
    std::setlocale(LC_NUMERIC, "C");

    QCommandLineParser parser;
    parser.addHelpOption();
    parser.addVersionOption();
    parser.addPositionalArgument(QStringLiteral("file"), QStringLiteral("Video file to open."));
    parser.process(app);

    MainWindow window;
    window.show();

    if (!parser.positionalArguments().isEmpty())
        window.openFile(parser.positionalArguments().first());

    return app.exec();
}
