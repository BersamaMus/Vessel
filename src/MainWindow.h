#pragma once

#include <QMainWindow>

class MpvWidget;
class QLabel;
class QSlider;
class QToolButton;
class QWidget;

class MainWindow : public QMainWindow
{
    Q_OBJECT

public:
    explicit MainWindow(QWidget *parent = nullptr);

    void openFile(const QString &path);

protected:
    void dragEnterEvent(QDragEnterEvent *event) override;
    void dropEvent(QDropEvent *event) override;

private:
    void buildUi();
    void setupShortcuts();
    void showOpenDialog();
    void toggleFullScreen();
    void updateTimeLabel();

    MpvWidget *m_video = nullptr;
    QWidget *m_controls = nullptr;
    QToolButton *m_playButton = nullptr;
    QToolButton *m_muteButton = nullptr;
    QSlider *m_seekSlider = nullptr;
    QSlider *m_volumeSlider = nullptr;
    QLabel *m_timeLabel = nullptr;

    double m_position = 0.0;
    double m_duration = 0.0;
};
