#include "MainWindow.h"

#include "MpvWidget.h"

#include <QDragEnterEvent>
#include <QDropEvent>
#include <QFileDialog>
#include <QFileInfo>
#include <QHBoxLayout>
#include <QLabel>
#include <QMenuBar>
#include <QMessageBox>
#include <QMimeData>
#include <QProxyStyle>
#include <QShortcut>
#include <QSlider>
#include <QStandardPaths>
#include <QStatusBar>
#include <QStyle>
#include <QToolButton>
#include <QVBoxLayout>

namespace {

// Makes a left click jump the slider straight to the clicked position
// instead of paging towards it.
class JumpClickStyle : public QProxyStyle
{
public:
    using QProxyStyle::QProxyStyle;

    int styleHint(StyleHint hint, const QStyleOption *option, const QWidget *widget,
                  QStyleHintReturn *returnData) const override
    {
        if (hint == SH_Slider_AbsoluteSetButtons)
            return Qt::LeftButton;
        return QProxyStyle::styleHint(hint, option, widget, returnData);
    }
};

QString formatTime(double seconds)
{
    const int total = qMax(0, static_cast<int>(seconds));
    const int h = total / 3600, m = (total / 60) % 60, s = total % 60;
    return h > 0 ? QStringLiteral("%1:%2:%3").arg(h).arg(m, 2, 10, QLatin1Char('0')).arg(s, 2, 10, QLatin1Char('0'))
                 : QStringLiteral("%1:%2").arg(m).arg(s, 2, 10, QLatin1Char('0'));
}

const QString kAppName = QStringLiteral("Better Video Player");

} // namespace

MainWindow::MainWindow(QWidget *parent)
    : QMainWindow(parent)
{
    setWindowTitle(kAppName);
    setAcceptDrops(true);
    resize(1100, 680);

    buildUi();
    setupShortcuts();
}

void MainWindow::buildUi()
{
    m_video = new MpvWidget(this);

    auto *sliderStyle = new JumpClickStyle;
    sliderStyle->setParent(this);

    m_playButton = new QToolButton;
    m_playButton->setIcon(style()->standardIcon(QStyle::SP_MediaPlay));
    m_playButton->setToolTip(tr("Play/Pause (Space)"));

    m_seekSlider = new QSlider(Qt::Horizontal);
    m_seekSlider->setStyle(sliderStyle);
    m_seekSlider->setRange(0, 0);

    m_timeLabel = new QLabel(formatTime(0) + QStringLiteral(" / ") + formatTime(0));
    m_timeLabel->setMinimumWidth(m_timeLabel->fontMetrics().horizontalAdvance(QStringLiteral("00:00:00 / 00:00:00")));
    m_timeLabel->setAlignment(Qt::AlignCenter);

    m_muteButton = new QToolButton;
    m_muteButton->setIcon(style()->standardIcon(QStyle::SP_MediaVolume));
    m_muteButton->setToolTip(tr("Mute (M)"));

    m_volumeSlider = new QSlider(Qt::Horizontal);
    m_volumeSlider->setStyle(sliderStyle);
    m_volumeSlider->setRange(0, 100);
    m_volumeSlider->setValue(100);
    m_volumeSlider->setFixedWidth(110);

    // Keep keyboard focus off the controls so arrows/space always reach our shortcuts.
    for (QWidget *w : {static_cast<QWidget *>(m_playButton), static_cast<QWidget *>(m_seekSlider),
                       static_cast<QWidget *>(m_muteButton), static_cast<QWidget *>(m_volumeSlider)})
        w->setFocusPolicy(Qt::NoFocus);

    m_controls = new QWidget;
    auto *controlsLayout = new QHBoxLayout(m_controls);
    controlsLayout->setContentsMargins(8, 4, 8, 6);
    controlsLayout->addWidget(m_playButton);
    controlsLayout->addWidget(m_seekSlider, 1);
    controlsLayout->addWidget(m_timeLabel);
    controlsLayout->addSpacing(8);
    controlsLayout->addWidget(m_muteButton);
    controlsLayout->addWidget(m_volumeSlider);

    auto *central = new QWidget;
    auto *layout = new QVBoxLayout(central);
    layout->setContentsMargins(0, 0, 0, 0);
    layout->setSpacing(0);
    layout->addWidget(m_video, 1);
    layout->addWidget(m_controls);
    setCentralWidget(central);

    QMenu *fileMenu = menuBar()->addMenu(tr("&File"));
    fileMenu->addAction(tr("&Open..."), QKeySequence::Open, this, &MainWindow::showOpenDialog);
    fileMenu->addSeparator();
    fileMenu->addAction(tr("E&xit"), QKeySequence::Quit, this, &QWidget::close);

    // UI -> player
    connect(m_playButton, &QToolButton::clicked, m_video, &MpvWidget::togglePause);
    connect(m_muteButton, &QToolButton::clicked, m_video, &MpvWidget::toggleMute);
    connect(m_volumeSlider, &QSlider::valueChanged, m_video, [this](int v) { m_video->setVolume(v); });
    // Fast keyframe seeks while scrubbing, one exact seek on release.
    connect(m_seekSlider, &QSlider::sliderMoved, this, [this](int ms) {
        m_video->seekAbsolute(ms / 1000.0, false);
        m_position = ms / 1000.0;
        updateTimeLabel();
    });
    connect(m_seekSlider, &QSlider::sliderReleased, this, [this] {
        m_video->seekAbsolute(m_seekSlider->value() / 1000.0, true);
    });
    connect(m_video, &MpvWidget::doubleClicked, this, &MainWindow::toggleFullScreen);

    // Player -> UI
    connect(m_video, &MpvWidget::positionChanged, this, [this](double s) {
        if (m_seekSlider->isSliderDown())
            return;
        m_position = s;
        m_seekSlider->setValue(static_cast<int>(s * 1000));
        updateTimeLabel();
    });
    connect(m_video, &MpvWidget::durationChanged, this, [this](double s) {
        m_duration = s;
        m_seekSlider->setRange(0, static_cast<int>(s * 1000));
        m_seekSlider->setPageStep(qMax(1000, static_cast<int>(s * 1000) / 20));
        updateTimeLabel();
    });
    connect(m_video, &MpvWidget::pausedChanged, this, [this](bool paused) {
        m_playButton->setIcon(style()->standardIcon(paused ? QStyle::SP_MediaPlay : QStyle::SP_MediaPause));
    });
    connect(m_video, &MpvWidget::mutedChanged, this, [this](bool muted) {
        m_muteButton->setIcon(style()->standardIcon(muted ? QStyle::SP_MediaVolumeMuted : QStyle::SP_MediaVolume));
    });
    connect(m_video, &MpvWidget::volumeChanged, this, [this](double v) {
        const QSignalBlocker block(m_volumeSlider);
        m_volumeSlider->setValue(qRound(v));
    });
    connect(m_video, &MpvWidget::titleChanged, this, [this](const QString &title) {
        setWindowTitle(title.isEmpty() ? kAppName : title + QStringLiteral(" - ") + kAppName);
    });
    connect(m_video, &MpvWidget::errorOccurred, this, [this](const QString &message) {
        statusBar()->showMessage(message, 8000);
    });
}

void MainWindow::setupShortcuts()
{
    const auto bind = [this](const QKeySequence &key, auto &&fn) {
        connect(new QShortcut(key, this), &QShortcut::activated, this, std::forward<decltype(fn)>(fn));
    };
    bind(Qt::Key_Space, [this] { m_video->togglePause(); });
    bind(Qt::Key_K, [this] { m_video->togglePause(); });
    bind(Qt::Key_Left, [this] { m_video->seekRelative(-5); });
    bind(Qt::Key_Right, [this] { m_video->seekRelative(5); });
    bind(Qt::Key_J, [this] { m_video->seekRelative(-10); });
    bind(Qt::Key_L, [this] { m_video->seekRelative(10); });
    bind(Qt::Key_Up, [this] { m_volumeSlider->setValue(m_volumeSlider->value() + 5); });
    bind(Qt::Key_Down, [this] { m_volumeSlider->setValue(m_volumeSlider->value() - 5); });
    bind(Qt::Key_M, [this] { m_video->toggleMute(); });
    bind(Qt::Key_F, [this] { toggleFullScreen(); });
    bind(Qt::Key_Escape, [this] { if (isFullScreen()) toggleFullScreen(); });
}

void MainWindow::openFile(const QString &path)
{
    m_video->open(path);
    statusBar()->clearMessage();
}

void MainWindow::showOpenDialog()
{
    const QString dir = QStandardPaths::writableLocation(QStandardPaths::MoviesLocation);
    const QString path = QFileDialog::getOpenFileName(
        this, tr("Open Video"), dir,
        tr("Videos (*.mp4 *.m4v *.mkv *.mov *.webm *.avi *.wmv *.ts *.flv);;All files (*)"));
    if (!path.isEmpty())
        openFile(path);
}

void MainWindow::toggleFullScreen()
{
    const bool goFull = !isFullScreen();
    menuBar()->setVisible(!goFull);
    statusBar()->setVisible(!goFull);
    m_controls->setVisible(!goFull);
    goFull ? showFullScreen() : showNormal();
}

void MainWindow::updateTimeLabel()
{
    m_timeLabel->setText(formatTime(m_position) + QStringLiteral(" / ") + formatTime(m_duration));
}

void MainWindow::dragEnterEvent(QDragEnterEvent *event)
{
    if (event->mimeData()->hasUrls())
        event->acceptProposedAction();
}

void MainWindow::dropEvent(QDropEvent *event)
{
    const QList<QUrl> urls = event->mimeData()->urls();
    if (!urls.isEmpty() && urls.first().isLocalFile())
        openFile(urls.first().toLocalFile());
}
