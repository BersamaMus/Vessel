#include "MediaControls.h"

#include <QCoreApplication>
#include <QDebug>
#include <QDir>
#include <QPointer>
#include <QTimer>

#ifdef Q_OS_WIN
#include <windows.h>
#include <SystemMediaTransportControlsInterop.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Media.h>
#include <winrt/Windows.Storage.h>
#include <winrt/Windows.Storage.Streams.h>

#include <atomic>

using namespace winrt::Windows::Media;
using winrt::Windows::Foundation::AsyncStatus;
using winrt::Windows::Foundation::IAsyncOperation;
using winrt::Windows::Storage::StorageFile;
using winrt::Windows::Storage::Streams::RandomAccessStreamReference;

struct MediaControls::Platform
{
    HWND window = nullptr;
    SystemMediaTransportControls controls{nullptr};
    winrt::event_token buttonToken;
    QString thumbnailShown;
    // Counts thumbnail changes, so one loaded too late (another video's) is dropped.
    std::shared_ptr<std::atomic_int> thumbnailGeneration = std::make_shared<std::atomic_int>(0);

    ~Platform()
    {
        if (controls) {
            try {
                controls.ButtonPressed(buttonToken);
                controls.IsEnabled(false);
            } catch (const winrt::hresult_error &) {
            }
        }
    }
};
#else
struct MediaControls::Platform
{
};
#endif

MediaControls::MediaControls(QObject *parent)
    : QObject(parent)
    , m_platform(std::make_unique<Platform>())
{
    // Several properties usually change together (a new video): tell Windows once.
    connect(this, &MediaControls::changed, this, [this] {
        if (!std::exchange(m_updatePending, true))
            QTimer::singleShot(0, this, [this] {
                m_updatePending = false;
                update();
            });
    });
}

MediaControls::~MediaControls() = default;

#ifdef Q_OS_WIN

void MediaControls::update()
{
    Platform &p = *m_platform;
    try {
        if (!p.controls) {
            if (!m_window || !m_active)
                return;   // nothing to show yet; set up with the first video
            p.window = reinterpret_cast<HWND>(m_window->winId());
            // A desktop app's controls belong to its window.
            auto interop = winrt::get_activation_factory<SystemMediaTransportControls, ISystemMediaTransportControlsInterop>();
            winrt::check_hresult(interop->GetForWindow(p.window, winrt::guid_of<SystemMediaTransportControls>(),
                                                       winrt::put_abi(p.controls)));
            p.controls.IsPlayEnabled(true);
            p.controls.IsPauseEnabled(true);
            // Pressed on Windows' threads: handled on the app's.
            QPointer<MediaControls> self = this;
            p.buttonToken = p.controls.ButtonPressed([self](const SystemMediaTransportControls &,
                                                            const SystemMediaTransportControlsButtonPressedEventArgs &args) {
                const SystemMediaTransportControlsButton button = args.Button();
                QMetaObject::invokeMethod(qApp, [self, button] {
                    if (!self)
                        return;
                    switch (button) {
                    case SystemMediaTransportControlsButton::Play: emit self->playRequested(); break;
                    case SystemMediaTransportControlsButton::Pause: emit self->pauseRequested(); break;
                    case SystemMediaTransportControlsButton::Next: emit self->nextRequested(); break;
                    case SystemMediaTransportControlsButton::Previous: emit self->previousRequested(); break;
                    default: break;
                    }
                }, Qt::QueuedConnection);
            });
        }

        SystemMediaTransportControls &controls = p.controls;
        controls.IsEnabled(m_active);
        controls.IsNextEnabled(m_canGoNext);
        controls.IsPreviousEnabled(m_canGoPrevious);
        controls.PlaybackStatus(!m_active ? MediaPlaybackStatus::Closed
                                : m_playing ? MediaPlaybackStatus::Playing : MediaPlaybackStatus::Paused);

        SystemMediaTransportControlsDisplayUpdater display = controls.DisplayUpdater();
        display.Type(MediaPlaybackType::Video);
        display.VideoProperties().Title(winrt::hstring(m_title.toStdWString()));
        display.VideoProperties().Subtitle(winrt::hstring(m_subtitle.toStdWString()));

        // The thumbnail is read from its file in the background, then shown.
        const QString thumbnail = m_active && m_thumbnail.isLocalFile()
            ? QDir::toNativeSeparators(m_thumbnail.toLocalFile()) : QString();
        if (thumbnail != p.thumbnailShown) {
            p.thumbnailShown = thumbnail;
            const int generation = ++*p.thumbnailGeneration;
            display.Thumbnail(nullptr);
            if (!thumbnail.isEmpty()) {
                auto current = p.thumbnailGeneration;
                StorageFile::GetFileFromPathAsync(winrt::hstring(thumbnail.toStdWString()))
                    .Completed([display, current, generation](const IAsyncOperation<StorageFile> &file, AsyncStatus status) {
                        if (status != AsyncStatus::Completed || *current != generation)
                            return;
                        try {
                            auto updater = display;
                            updater.Thumbnail(RandomAccessStreamReference::CreateFromFile(file.GetResults()));
                            updater.Update();
                        } catch (const winrt::hresult_error &) {
                        }
                    });
            }
        }
        display.Update();
    } catch (const winrt::hresult_error &error) {
        qWarning() << "Media controls:" << QString::fromWCharArray(error.message().c_str());
    }
}

#else

void MediaControls::update()
{
}

#endif
