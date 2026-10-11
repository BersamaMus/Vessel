#include "PhoneDevices.h"
#include "VideoFormats.h"

#include <QDebug>
#include <QHash>
#include <QMutex>
#include <QRegularExpression>
#include <QSet>
#include <QThread>
#include <QUrl>

#include <mpv/client.h>
#include <mpv/stream_cb.h>

#include <algorithm>
#include <atomic>
#include <memory>

#ifdef Q_OS_WIN
#include <windows.h>
#include <PortableDevice.h>
#include <PortableDeviceApi.h>
#include <WpdMtpExtensions.h>
#include <shlobj.h>
#include <shlwapi.h>
#include <wrl/client.h>
#endif

namespace Phones {

namespace {

// The library's video extensions (see VideoFormats). A phone's files can't be peeked into
// as cheaply as a drive's, so TypeScript files ("index.ts") are told from MPEG-TS videos
// by what the phone says they are, or else by size: code is rarely a megabyte, a video
// nearly always more.
bool isVideo(const QString &name, const GUID &type, qint64 size)
{
    const qsizetype dot = name.lastIndexOf(QLatin1Char('.'));
    if (dot <= 0 || !VideoFormats::isVideoSuffix(name.mid(dot + 1)))
        return false;
    if (name.mid(dot + 1).compare(QLatin1String("ts"), Qt::CaseInsensitive) != 0)
        return true;
    return type == WPD_CONTENT_TYPE_VIDEO
        || (size >= 1024 * 1024 && !name.endsWith(QLatin1String(".d.ts"), Qt::CaseInsensitive));
}

// Folders not worth looking in. Hidden ones (trash, caches, thumbnails) and apps' private
// data hold no videos worth listing, and can be huge. So can WhatsApp's photos, stickers
// and voice notes, and the phone is slow to list them the first time after it's plugged
// in (17 s for 2,700 sent photos): WhatsApp keeps them out of the phone's media index.
// Its videos are in "WhatsApp Video" (and "WhatsApp Documents"), which are still looked in.
bool skipFolder(const QString &name, const QString &path)
{
    static const QRegularExpression whatsApp(
        QStringLiteral("^WhatsApp (Business )?(Images|Stickers|Voice Notes|Audio|Animated Gifs|Profile Photos)$"));
    return name.startsWith(QLatin1Char('.')) || path.endsWith(QLatin1String("Android/data"))
        || path.endsWith(QLatin1String("Android/obb")) || whatsApp.match(name).hasMatch();
}

// Which of a storage's top folders are looked in first: the camera's, then the ones
// people put videos in, then the rest, and apps' media last.
constexpr int kFolderRanks = 4;
int folderRank(const QString &topFolder)
{
    if (topFolder.compare(QLatin1String("DCIM"), Qt::CaseInsensitive) == 0)
        return 0;
    for (const char *name : {"Movies", "Download", "Videos", "Pictures"})
        if (topFolder.compare(QLatin1String(name), Qt::CaseInsensitive) == 0)
            return 1;
    if (topFolder.compare(QLatin1String("Android"), Qt::CaseInsensitive) == 0)
        return 3;
    return 2;
}

} // namespace

#ifdef Q_OS_WIN

namespace {

using Microsoft::WRL::ComPtr;

// WPD's objects live in COM's multithreaded apartment. Keeping that apartment alive for
// the whole process lets any thread that hasn't joined one (Qt's worker threads, mpv's
// stream thread) use them.
void ensureCom()
{
    static const bool ready = [] {
        CO_MTA_USAGE_COOKIE cookie = nullptr;
        return SUCCEEDED(CoIncrementMTAUsage(&cookie));
    }();
    Q_UNUSED(ready);
}

template<typename T>
ComPtr<T> create(REFCLSID clsid)
{
    ComPtr<T> object;
    CoCreateInstance(clsid, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&object));
    return object;
}

// The phone answers one request at a time, across all programs (and this app's thumbnail
// workers), and turns others away as busy meanwhile.
bool isBusy(HRESULT hr)
{
    return hr == HRESULT_FROM_WIN32(ERROR_BUSY);
}

// Makes a request, waiting a moment and asking again while the phone is busy (about 5 s in all).
template<typename Call>
HRESULT retrying(Call &&call)
{
    HRESULT hr = call();
    for (int attempt = 1; isBusy(hr) && attempt <= 100; ++attempt) {
        QThread::msleep(20 + 10 * qMin(attempt, 8));
        hr = call();
    }
    return hr;
}

QString stringValue(IPortableDeviceValues *values, REFPROPERTYKEY key)
{
    LPWSTR text = nullptr;
    if (FAILED(values->GetStringValue(key, &text)) || !text)
        return {};
    const QString result = QString::fromWCharArray(text);
    CoTaskMemFree(text);
    return result;
}

QDateTime dateValue(IPortableDeviceValues *values, REFPROPERTYKEY key)
{
    PROPVARIANT value;
    PropVariantInit(&value);
    QDateTime result;
    SYSTEMTIME time{};
    if (SUCCEEDED(values->GetValue(key, &value)) && value.vt == VT_DATE && VariantTimeToSystemTime(value.date, &time))
        result = QDateTime(QDate(time.wYear, time.wMonth, time.wDay), QTime(time.wHour, time.wMinute, time.wSecond));
    PropVariantClear(&value);
    return result;
}

// One connected phone, opened once and shared. MTP does one thing at a time, so its
// commands take turns.
struct Device
{
    QString id;       // WPD's (Plug and Play) id
    QString serial;
    QString name;
    ComPtr<IPortableDevice> device;
    ComPtr<IPortableDeviceContent> content;
    ComPtr<IPortableDeviceProperties> properties;
    QMutex mutex;
    bool partial64 = true;   // GetPartialObject64 works (else the 32-bit GetPartialObject)
    // What was last learned about its storage, kept for when it can't be asked (busy).
    bool ready = false;
    qint64 bytesTotal = 0;
    qint64 bytesFree = 0;
};

QMutex g_mutex;
QHash<QString, std::shared_ptr<Device>> g_devices;   // by WPD id
QSet<QString> g_notPhones;                           // USB drives and the like, by WPD id

// Opens a device, or returns null for something that isn't a phone (or can't be opened now).
std::shared_ptr<Device> open(IPortableDeviceManager *manager, const QString &id)
{
    auto client = create<IPortableDeviceValues>(CLSID_PortableDeviceValues);
    client->SetStringValue(WPD_CLIENT_NAME, L"Vessel");
    // Write access is what lets MTP commands be sent (for reading parts of files); nothing is written.
    client->SetUnsignedIntegerValue(WPD_CLIENT_DESIRED_ACCESS, GENERIC_READ | GENERIC_WRITE);
    auto device = std::make_shared<Device>();
    device->id = id;
    device->device = create<IPortableDevice>(CLSID_PortableDeviceFTM);
    const std::wstring wid = id.toStdWString();
    if (!device->device || FAILED(device->device->Open(wid.c_str(), client.Get())))
        return nullptr;
    device->device->Content(&device->content);
    if (!device->content || FAILED(device->content->Properties(&device->properties)))
        return nullptr;

    ComPtr<IPortableDeviceValues> info;
    retrying([&] {
        info.Reset();
        return device->properties->GetValues(WPD_DEVICE_OBJECT_ID, nullptr, &info);
    });
    if (!info)
        return nullptr;
    const QString protocol = stringValue(info.Get(), WPD_DEVICE_PROTOCOL);
    if (!protocol.startsWith(QLatin1String("MTP")) && !protocol.startsWith(QLatin1String("PTP"))) {
        QMutexLocker lock(&g_mutex);
        g_notPhones.insert(id);   // a USB drive: it has a drive letter, and its own tab
        return nullptr;
    }
    device->serial = stringValue(info.Get(), WPD_DEVICE_SERIAL_NUMBER);
    if (device->serial.isEmpty())
        device->serial = id;
    WCHAR name[256] = L"";
    DWORD length = 256;
    manager->GetDeviceFriendlyName(wid.c_str(), name, &length);
    device->name = QString::fromWCharArray(name);
    if (device->name.isEmpty())
        device->name = stringValue(info.Get(), WPD_DEVICE_MODEL);
    return device;
}

std::shared_ptr<Device> bySerial(const QString &serial)
{
    {
        QMutexLocker lock(&g_mutex);
        for (const auto &device : std::as_const(g_devices))
            if (device->serial == serial)
                return device;
    }
    list();   // not opened yet (in a thumbnail worker, say)
    QMutexLocker lock(&g_mutex);
    for (const auto &device : std::as_const(g_devices))
        if (device->serial == serial)
            return device;
    return nullptr;
}

// The objects in a folder (or storage, or the device); false if the phone couldn't say.
bool childIds(Device &device, const QString &parent, QStringList &ids)
{
    ids.clear();
    ComPtr<IEnumPortableDeviceObjectIDs> children;
    const std::wstring wparent = parent.toStdWString();
    if (FAILED(retrying([&] {
            children.Reset();
            return device.content->EnumObjects(0, wparent.c_str(), nullptr, &children);
        })))
        return false;
    LPWSTR batch[256];
    while (true) {
        ULONG got = 0;
        if (FAILED(retrying([&] { return children->Next(256, batch, &got); })))
            return false;
        if (got == 0)
            return true;
        for (ULONG i = 0; i < got; ++i) {
            ids.append(QString::fromWCharArray(batch[i]));
            CoTaskMemFree(batch[i]);
        }
    }
}

// Collects the results of a bulk property query. WPD holds on to it from its own threads
// and may let go after the query has ended, so it's reference counted properly and
// deletes itself when the last holder lets go (never on the stack).
class BulkCallback : public IPortableDevicePropertiesBulkCallback
{
public:
    QList<ComPtr<IPortableDeviceValues>> results;
    HANDLE done = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    HRESULT result = E_PENDING;

    ULONG STDMETHODCALLTYPE AddRef() override { return ++m_refs; }
    ULONG STDMETHODCALLTYPE Release() override
    {
        const ULONG left = --m_refs;
        if (left == 0)
            delete this;
        return left;
    }
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void **out) override
    {
        if (riid == IID_IUnknown || riid == IID_IPortableDevicePropertiesBulkCallback) {
            *out = this;
            AddRef();
            return S_OK;
        }
        *out = nullptr;
        return E_NOINTERFACE;
    }
    HRESULT STDMETHODCALLTYPE OnStart(REFGUID) override { return S_OK; }
    HRESULT STDMETHODCALLTYPE OnProgress(REFGUID, IPortableDeviceValuesCollection *values) override
    {
        DWORD count = 0;
        values->GetCount(&count);
        for (DWORD i = 0; i < count; ++i) {
            ComPtr<IPortableDeviceValues> v;
            if (SUCCEEDED(values->GetAt(i, &v)))
                results.append(v);
        }
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE OnEnd(REFGUID, HRESULT hr) override
    {
        result = hr;
        SetEvent(done);
        return S_OK;
    }

private:
    ~BulkCallback() { CloseHandle(done); }   // through Release() only

    std::atomic<ULONG> m_refs = 1;
};

// The name, kind, size and date of each object: in one round of requests where the
// phone allows (a few hundred milliseconds for thousands of files), else one by one.
QList<ComPtr<IPortableDeviceValues>> describe(Device &device, const QStringList &ids)
{
    auto keys = create<IPortableDeviceKeyCollection>(CLSID_PortableDeviceKeyCollection);
    for (const PROPERTYKEY &key : {WPD_OBJECT_ID, WPD_OBJECT_ORIGINAL_FILE_NAME, WPD_OBJECT_NAME, WPD_OBJECT_CONTENT_TYPE,
                                   WPD_OBJECT_SIZE, WPD_OBJECT_DATE_MODIFIED})
        keys->Add(key);

    ComPtr<IPortableDevicePropertiesBulk> bulk;
    if (SUCCEEDED(device.properties.As(&bulk))) {
        auto list = create<IPortableDevicePropVariantCollection>(CLSID_PortableDevicePropVariantCollection);
        std::vector<std::wstring> wide;
        wide.reserve(ids.size());
        for (const QString &id : ids) {
            wide.push_back(id.toStdWString());
            PROPVARIANT value;
            PropVariantInit(&value);
            value.vt = VT_LPWSTR;
            value.pwszVal = wide.back().data();
            list->Add(&value);   // copies it
        }
        ComPtr<BulkCallback> callback;
        callback.Attach(new BulkCallback);   // starts with our one reference
        GUID context{};
        if (SUCCEEDED(bulk->QueueGetValuesByObjectList(list.Get(), keys.Get(), callback.Get(), &context))
            && SUCCEEDED(bulk->Start(context))) {
            if (WaitForSingleObject(callback->done, 120'000) == WAIT_OBJECT_0 && SUCCEEDED(callback->result))
                return callback->results;
            bulk->Cancel(context);
            WaitForSingleObject(callback->done, 5000);
        }
    }

    QList<ComPtr<IPortableDeviceValues>> results;
    for (const QString &id : ids) {
        ComPtr<IPortableDeviceValues> values;
        const std::wstring wid = id.toStdWString();
        if (SUCCEEDED(retrying([&] {
                values.Reset();
                return device.properties->GetValues(wid.c_str(), keys.Get(), &values);
            })))
            results.append(values);
    }
    return results;
}

// The object at a path inside the phone ("DCIM/Camera/VID_….mp4", as findVideos names
// them), looked for a folder at a time; "" if it isn't there.
QString objectIdAt(Device &device, const QString &path)
{
    QMutexLocker lock(&device.mutex);
    QStringList parts = path.split(QLatin1Char('/'), Qt::SkipEmptyParts);
    QStringList storages;
    if (parts.isEmpty() || !childIds(device, QString::fromWCharArray(WPD_DEVICE_OBJECT_ID), storages) || storages.isEmpty())
        return {};
    QString current = storages.first();
    if (storages.size() > 1) {   // the path starts with the storage's name
        current.clear();
        const QString storage = parts.takeFirst();
        for (const auto &values : describe(device, storages))
            if (stringValue(values.Get(), WPD_OBJECT_NAME) == storage)
                current = stringValue(values.Get(), WPD_OBJECT_ID);
    }
    for (const QString &part : std::as_const(parts)) {
        if (current.isEmpty())
            return {};
        QStringList ids;
        if (!childIds(device, current, ids))
            return {};
        current.clear();
        for (const auto &values : describe(device, ids)) {
            QString name = stringValue(values.Get(), WPD_OBJECT_ORIGINAL_FILE_NAME);
            if (name.isEmpty())
                name = stringValue(values.Get(), WPD_OBJECT_NAME);
            if (name == part) {
                current = stringValue(values.Get(), WPD_OBJECT_ID);
                break;
            }
        }
    }
    return current;
}

ComPtr<IPortableDeviceValues> command(REFPROPERTYKEY key)
{
    auto values = create<IPortableDeviceValues>(CLSID_PortableDeviceValues);
    values->SetGuidValue(WPD_PROPERTY_COMMON_COMMAND_CATEGORY, key.fmtid);
    values->SetUnsignedIntegerValue(WPD_PROPERTY_COMMON_COMMAND_ID, key.pid);
    return values;
}

// Reads `length` bytes at `offset` of an object with MTP's GetPartialObject64 (or
// GetPartialObject, for phones without it); the bytes read, or -1.
qint64 readPart(Device &device, const QString &objectId, qint64 offset, char *buffer, qint64 length)
{
    // Windows' MTP driver names objects "o" + the MTP object handle in hex.
    bool ok = false;
    const ULONG handle = objectId.startsWith(QLatin1Char('o')) ? objectId.mid(1).toULong(&ok, 16) : 0;
    if (!ok)
        return -1;

    QMutexLocker lock(&device.mutex);
    // The phone answers one request at a time, across all programs (and this app's thumbnail
    // workers): while another's is under way it says it's busy, so wait a moment and ask again.
    int busyRetries = 0;
    for (int attempt = 0; attempt < 2; ++attempt) {
        const bool wide = device.partial64;
        if (!wide && offset > 0xFFFFFFFFll)
            return -1;
        auto params = create<IPortableDevicePropVariantCollection>(CLSID_PortableDevicePropVariantCollection);
        const auto add = [&](ULONG value) {
            PROPVARIANT pv;
            PropVariantInit(&pv);
            pv.vt = VT_UI4;
            pv.ulVal = value;
            params->Add(&pv);
        };
        add(handle);
        add(ULONG(offset & 0xFFFFFFFF));
        if (wide)
            add(ULONG(quint64(offset) >> 32));
        add(ULONG(length));

        auto start = command(WPD_COMMAND_MTP_EXT_EXECUTE_COMMAND_WITH_DATA_TO_READ);
        start->SetUnsignedIntegerValue(WPD_PROPERTY_MTP_EXT_OPERATION_CODE, wide ? 0x95C1 : 0x101B);
        start->SetIPortableDevicePropVariantCollectionValue(WPD_PROPERTY_MTP_EXT_OPERATION_PARAMS, params.Get());
        ComPtr<IPortableDeviceValues> started;
        if (FAILED(device.device->SendCommand(0, start.Get(), &started)))
            return -1;
        HRESULT hr = S_OK;
        started->GetErrorValue(WPD_PROPERTY_COMMON_HRESULT, &hr);
        LPWSTR context = nullptr;
        started->GetStringValue(WPD_PROPERTY_MTP_EXT_TRANSFER_CONTEXT, &context);
        ULONGLONG total = 0;
        started->GetUnsignedLargeIntegerValue(WPD_PROPERTY_MTP_EXT_TRANSFER_TOTAL_DATA_SIZE, &total);
        ULONG chunk = 0;
        started->GetUnsignedIntegerValue(WPD_PROPERTY_MTP_EXT_OPTIMAL_TRANSFER_BUFFER_SIZE, &chunk);

        qint64 read = 0;
        if (SUCCEEDED(hr) && context) {
            total = qMin<ULONGLONG>(total, ULONGLONG(length));
            while (ULONGLONG(read) < total) {
                const ULONG want = ULONG(qMin<ULONGLONG>(total - read, chunk ? chunk : 1u << 20));
                auto next = command(WPD_COMMAND_MTP_EXT_READ_DATA);
                next->SetStringValue(WPD_PROPERTY_MTP_EXT_TRANSFER_CONTEXT, context);
                next->SetBufferValue(WPD_PROPERTY_MTP_EXT_TRANSFER_DATA, reinterpret_cast<BYTE *>(buffer + read), want);
                next->SetUnsignedLargeIntegerValue(WPD_PROPERTY_MTP_EXT_TRANSFER_NUM_BYTES_TO_READ, want);
                ComPtr<IPortableDeviceValues> got;
                if (FAILED(device.device->SendCommand(0, next.Get(), &got)))
                    break;
                BYTE *data = nullptr;
                DWORD size = 0;
                if (FAILED(got->GetBufferValue(WPD_PROPERTY_MTP_EXT_TRANSFER_DATA, &data, &size)) || size == 0) {
                    CoTaskMemFree(data);
                    break;
                }
                memcpy(buffer + read, data, qMin<DWORD>(size, want));
                read += qMin<DWORD>(size, want);
                CoTaskMemFree(data);
            }
        }
        DWORD response = 0;
        if (context) {
            auto end = command(WPD_COMMAND_MTP_EXT_END_DATA_TRANSFER);
            end->SetStringValue(WPD_PROPERTY_MTP_EXT_TRANSFER_CONTEXT, context);
            ComPtr<IPortableDeviceValues> ended;
            if (SUCCEEDED(device.device->SendCommand(0, end.Get(), &ended)))
                ended->GetUnsignedIntegerValue(WPD_PROPERTY_MTP_EXT_RESPONSE_CODE, &response);
            CoTaskMemFree(context);
        }
        if (response == 0x2001)   // OK
            return read;
        if ((hr == HRESULT_FROM_WIN32(ERROR_BUSY) || response == 0x2019 /* DeviceBusy */) && busyRetries++ < 100) {
            QThread::msleep(20 + 10 * qMin(busyRetries, 8));   // about 5 seconds in all
            --attempt;
            continue;
        }
        qWarning("Phone read failed: object %s, offset %lld: hr 0x%08lx, MTP response 0x%04lx",
                 qPrintable(objectId), offset, ulong(hr), ulong(response));
        if (wide && response == 0x2005) {   // OperationNotSupported: try the 32-bit one
            device.partial64 = false;
            continue;
        }
        return -1;
    }
    return -1;
}

// ---------------------------------------------------------------------------
// The mtp:// protocol for libmpv
// ---------------------------------------------------------------------------

// A video on a phone as a stream libmpv can read and seek in, a block at a time. The last
// few blocks are kept: a file whose audio and video aren't interleaved (all the video,
// then all the audio) is read in two places at once, and with a single block each switch
// fetched 4 MB again; such a video played at a sixth of real time.
struct Stream
{
    static constexpr qint64 kBlock = 4 << 20;   // a few round trips' worth: ~35 MB/s over USB
    static constexpr int kKeptBlocks = 8;

    struct Block
    {
        qint64 start = 0;
        QByteArray data;
        quint64 lastUsed = 0;
    };

    std::shared_ptr<Device> device;
    QString objectId;
    qint64 size = 0;
    qint64 position = 0;
    QList<Block> blocks;   // the least recently used goes when another is needed
    quint64 reads = 0;
    std::atomic_bool cancelled = false;
};

int64_t streamRead(void *cookie, char *buffer, uint64_t bytes)
{
    auto *s = static_cast<Stream *>(cookie);
    if (s->cancelled)
        return -1;
    if (s->position >= s->size)
        return 0;
    const qint64 start = s->position - s->position % Stream::kBlock;
    auto block = std::find_if(s->blocks.begin(), s->blocks.end(), [&](const Stream::Block &b) { return b.start == start; });
    if (block == s->blocks.end()) {
        if (s->blocks.size() >= Stream::kKeptBlocks)
            s->blocks.erase(std::min_element(s->blocks.begin(), s->blocks.end(),
                                             [](const Stream::Block &a, const Stream::Block &b) { return a.lastUsed < b.lastUsed; }));
        Stream::Block fetched;
        fetched.start = start;
        fetched.data.resize(qMin(Stream::kBlock, s->size - start));
        const qint64 got = readPart(*s->device, s->objectId, start, fetched.data.data(), fetched.data.size());
        if (got <= 0)
            return -1;
        fetched.data.resize(got);
        s->blocks.append(std::move(fetched));
        block = s->blocks.end() - 1;
    }
    block->lastUsed = ++s->reads;
    const qint64 inside = s->position - block->start;
    const qint64 count = qMin<qint64>(qint64(bytes), block->data.size() - inside);
    if (count <= 0)
        return 0;
    memcpy(buffer, block->data.constData() + inside, count);
    s->position += count;
    return count;
}

int64_t streamSeek(void *cookie, int64_t offset)
{
    auto *s = static_cast<Stream *>(cookie);
    if (offset < 0 || offset > s->size)
        return MPV_ERROR_GENERIC;
    s->position = offset;
    return offset;
}

int64_t streamSize(void *cookie)
{
    return static_cast<Stream *>(cookie)->size;
}

void streamClose(void *cookie)
{
    delete static_cast<Stream *>(cookie);
}

void streamCancel(void *cookie)
{
    static_cast<Stream *>(cookie)->cancelled = true;
}

// "mtp://<serial>/<path>?id=<object id>&size=<bytes>"
int streamOpen(void *, char *uri, mpv_stream_cb_info *info)
{
    ensureCom();
    const QString text = QString::fromUtf8(uri);
    const QString rest = text.mid(6);   // after "mtp://"
    const QString serial = rest.section(QLatin1Char('/'), 0, 0);
    const QString query = rest.section(QLatin1Char('?'), -1);
    QString objectId;
    qint64 size = 0;
    for (const QString &pair : query.split(QLatin1Char('&'))) {
        if (pair.startsWith(QLatin1String("id=")))
            objectId = pair.mid(3);
        else if (pair.startsWith(QLatin1String("size=")))
            size = pair.mid(5).toLongLong();
    }
    auto device = bySerial(serial);
    if (!device || size <= 0)
        return MPV_ERROR_LOADING_FAILED;
    if (objectId.isEmpty()) {   // remembered from when it was last connected: find it again
        const QString path = QUrl::fromPercentEncoding(rest.section(QLatin1Char('?'), 0, 0).section(QLatin1Char('/'), 1).toUtf8());
        objectId = objectIdAt(*device, path);
        if (objectId.isEmpty())
            return MPV_ERROR_LOADING_FAILED;
    }

    auto *stream = new Stream;
    stream->device = device;
    stream->objectId = objectId;
    stream->size = size;
    info->cookie = stream;
    info->read_fn = streamRead;
    info->seek_fn = streamSeek;
    info->size_fn = streamSize;
    info->close_fn = streamClose;
    info->cancel_fn = streamCancel;
    return 0;
}

} // namespace

QList<Phone> list()
{
    ensureCom();
    QList<Phone> phones;
    auto manager = create<IPortableDeviceManager>(CLSID_PortableDeviceManager);
    if (!manager)
        return phones;
    manager->RefreshDeviceList();
    DWORD count = 0;
    manager->GetDevices(nullptr, &count);
    std::vector<LPWSTR> ids(count);
    if (count > 0)
        manager->GetDevices(ids.data(), &count);

    QSet<QString> present;
    for (DWORD i = 0; i < count; ++i) {
        const QString id = QString::fromWCharArray(ids[i]);
        CoTaskMemFree(ids[i]);
        present.insert(id);
        std::shared_ptr<Device> device;
        {
            QMutexLocker lock(&g_mutex);
            if (g_notPhones.contains(id))
                continue;
            device = g_devices.value(id);
        }
        if (!device) {
            device = open(manager.Get(), id);
            if (!device)
                continue;
            QMutexLocker lock(&g_mutex);
            g_devices.insert(id, device);
        }

        Phone phone;
        phone.serial = device->serial;
        phone.name = device->name;
        QMutexLocker lock(&device->mutex);
        // A locked phone, or one only charging, shows no storage.
        auto keys = create<IPortableDeviceKeyCollection>(CLSID_PortableDeviceKeyCollection);
        keys->Add(WPD_STORAGE_CAPACITY);
        keys->Add(WPD_STORAGE_FREE_SPACE_IN_BYTES);
        QStringList storages;
        if (childIds(*device, QString::fromWCharArray(WPD_DEVICE_OBJECT_ID), storages)) {
            bool ready = false;
            qint64 total = 0, free = 0;
            for (const QString &storage : std::as_const(storages)) {
                ComPtr<IPortableDeviceValues> values;
                const std::wstring wid = storage.toStdWString();
                if (FAILED(retrying([&] {
                        values.Reset();
                        return device->properties->GetValues(wid.c_str(), keys.Get(), &values);
                    })))
                    continue;
                ULONGLONG capacity = 0, available = 0;
                values->GetUnsignedLargeIntegerValue(WPD_STORAGE_CAPACITY, &capacity);
                values->GetUnsignedLargeIntegerValue(WPD_STORAGE_FREE_SPACE_IN_BYTES, &available);
                ready = true;
                total += qint64(capacity);
                free += qint64(available);
            }
            device->ready = ready;
            device->bytesTotal = total;
            device->bytesFree = free;
        }
        // Couldn't ask (busy all along): as it was last time, rather than as if it had been locked.
        phone.ready = device->ready;
        phone.bytesTotal = device->bytesTotal;
        phone.bytesFree = device->bytesFree;
        phones.append(phone);
    }

    // Forget what's been unplugged.
    QMutexLocker lock(&g_mutex);
    for (auto it = g_devices.begin(); it != g_devices.end();)
        it = present.contains(it.key()) ? std::next(it) : g_devices.erase(it);
    for (auto it = g_notPhones.begin(); it != g_notPhones.end();)
        it = present.contains(*it) ? std::next(it) : g_notPhones.erase(it);
    return phones;
}

QList<File> findVideos(const QString &serial, const std::function<bool(const QList<File> &)> &found)
{
    ensureCom();
    QList<File> videos;
    const auto device = bySerial(serial);
    if (!device)
        return videos;

    struct Folder
    {
        QString id;
        QString path;   // "" for a storage's top
        int rank = 0;   // see folderRank
    };
    // The folders still to look in, by rank: the ones most likely to hold the user's own
    // videos are looked in first, so they show up while the rest is still being searched.
    QList<Folder> queues[kFolderRanks];
    const auto next = [&](Folder &folder) {
        for (QList<Folder> &queue : queues)
            if (!queue.isEmpty()) {
                folder = queue.takeFirst();
                return true;
            }
        return false;
    };
    bool storageFolders = false;   // a phone with a memory card: each storage is a top folder
    {
        QMutexLocker lock(&device->mutex);
        QStringList storages;
        childIds(*device, QString::fromWCharArray(WPD_DEVICE_OBJECT_ID), storages);
        const auto described = describe(*device, storages);
        storageFolders = storages.size() > 1;
        for (const auto &values : described) {
            // "SD card/DCIM/…"
            const QString name = stringValue(values.Get(), WPD_OBJECT_NAME);
            queues[0].append({stringValue(values.Get(), WPD_OBJECT_ID), storageFolders ? name : QString(), 0});
        }
    }

    Folder folder;
    while (next(folder)) {
        QList<ComPtr<IPortableDeviceValues>> children;
        {
            // A folder at a time, so playing a video from the phone isn't held up for long.
            QMutexLocker lock(&device->mutex);
            QStringList ids;
            if (childIds(*device, folder.id, ids) && !ids.isEmpty())
                children = describe(*device, ids);
        }
        // A storage's own folders get their rank from their name; deeper ones inherit theirs.
        const bool top = folder.path.isEmpty() || (storageFolders && !folder.path.contains(QLatin1Char('/')));
        QList<File> inFolder;
        for (const auto &values : std::as_const(children)) {
            QString name = stringValue(values.Get(), WPD_OBJECT_ORIGINAL_FILE_NAME);
            if (name.isEmpty())
                name = stringValue(values.Get(), WPD_OBJECT_NAME);
            if (name.isEmpty())
                continue;
            const QString path = folder.path.isEmpty() ? name : folder.path + QLatin1Char('/') + name;
            GUID type{};
            values->GetGuidValue(WPD_OBJECT_CONTENT_TYPE, &type);
            if (type == WPD_CONTENT_TYPE_FOLDER || type == WPD_CONTENT_TYPE_FUNCTIONAL_OBJECT) {
                if (!skipFolder(name, path)) {
                    const int rank = top ? folderRank(name) : folder.rank;
                    queues[rank].append({stringValue(values.Get(), WPD_OBJECT_ID), path, rank});
                }
                continue;
            }
            // Hidden files too: ".trashed-…" ones are in the phone's bin.
            ULONGLONG size = 0;
            values->GetUnsignedLargeIntegerValue(WPD_OBJECT_SIZE, &size);
            if (!isVideo(name, type, qint64(size)) || name.startsWith(QLatin1Char('.')))
                continue;
            File file;
            file.objectId = stringValue(values.Get(), WPD_OBJECT_ID);
            file.path = path;
            file.size = qint64(size);
            file.modified = dateValue(values.Get(), WPD_OBJECT_DATE_MODIFIED);
            if (file.size <= 0)
                continue;   // still being written, or empty
            inFolder.append(file);
        }
        videos += inFolder;
        if (found && !found(inFolder))
            break;
    }
    return videos;
}

void registerStream(mpv_handle *mpv)
{
    mpv_stream_cb_add_ro(mpv, "mtp", nullptr, streamOpen);
}

namespace {

// Explorer's names for a phone's file: its device (as WPD knows it), then, within it, the
// persistent unique id of its storage, of each folder it's in, and its own; empty if it
// can't be found. A request per folder, climbing from the file.
QStringList explorerIds(const QString &serial, const QString &objectId, const QString &path, QString &deviceId)
{
    ensureCom();
    const auto device = bySerial(serial);
    if (!device)
        return {};
    QString id = objectId.isEmpty() ? objectIdAt(*device, path) : objectId;
    if (id.isEmpty())
        return {};
    deviceId = device->id;

    QMutexLocker lock(&device->mutex);
    auto keys = create<IPortableDeviceKeyCollection>(CLSID_PortableDeviceKeyCollection);
    keys->Add(WPD_OBJECT_PARENT_ID);
    keys->Add(WPD_OBJECT_PERSISTENT_UNIQUE_ID);
    keys->Add(WPD_OBJECT_CONTENT_TYPE);
    QStringList chain;
    for (int depth = 0; depth < 64; ++depth) {
        ComPtr<IPortableDeviceValues> values;
        const std::wstring wid = id.toStdWString();
        if (FAILED(retrying([&] {
                values.Reset();
                return device->properties->GetValues(wid.c_str(), keys.Get(), &values);
            })))
            return {};
        const QString unique = stringValue(values.Get(), WPD_OBJECT_PERSISTENT_UNIQUE_ID);
        if (unique.isEmpty())
            return {};
        chain.prepend(unique);
        GUID type{};
        values->GetGuidValue(WPD_OBJECT_CONTENT_TYPE, &type);
        if (type == WPD_CONTENT_TYPE_FUNCTIONAL_OBJECT)   // the storage: the top
            return chain;
        id = stringValue(values.Get(), WPD_OBJECT_PARENT_ID);
        if (id.isEmpty() || id == QString::fromWCharArray(WPD_DEVICE_OBJECT_ID))
            return {};
    }
    return {};
}

// The child of `folder` that Explorer calls `name` (its in-folder parsing name), or null.
// Explorer's phone folders don't parse these names back, so the folder is listed: about
// a second per thousand files.
PITEMID_CHILD shellChild(IShellFolder *folder, const QString &name)
{
    ComPtr<IEnumIDList> children;
    if (folder->EnumObjects(nullptr, SHCONTF_FOLDERS | SHCONTF_NONFOLDERS, &children) != S_OK)
        return nullptr;
    const std::wstring wanted = name.toStdWString();
    PITEMID_CHILD child = nullptr;
    while (children->Next(1, &child, nullptr) == S_OK) {
        STRRET text;
        wchar_t parsing[MAX_PATH] = L"";
        if (SUCCEEDED(folder->GetDisplayNameOf(child, SHGDN_INFOLDER | SHGDN_FORPARSING, &text)))
            StrRetToBufW(&text, child, parsing, MAX_PATH);
        if (_wcsicmp(parsing, wanted.c_str()) == 0)
            return child;
        CoTaskMemFree(child);
    }
    return nullptr;
}

} // namespace

bool showInExplorer(const QString &serial, const QString &objectId, const QString &path)
{
    QString deviceId;
    const QStringList ids = explorerIds(serial, objectId, path, deviceId);
    if (ids.isEmpty())
        return false;

    // Shell calls want a single-threaded apartment.
    const HRESULT init = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED | COINIT_DISABLE_OLE1DDE);
    bool shown = false;
    // The phone itself parses ("This PC\<device>"); what's in it is found a level at a time.
    const std::wstring device = (QStringLiteral("::{20D04FE0-3AEA-1069-A2D8-08002B30309D}\\") + deviceId).toStdWString();
    PIDLIST_ABSOLUTE item = nullptr;
    ComPtr<IShellFolder> folder;
    if (SUCCEEDED(SHParseDisplayName(device.c_str(), nullptr, &item, 0, nullptr))
        && SUCCEEDED(SHBindToObject(nullptr, item, nullptr, IID_PPV_ARGS(&folder)))) {
        for (qsizetype i = 0; i < ids.size() && item; ++i) {
            PITEMID_CHILD child = shellChild(folder.Get(), ids[i]);
            if (!child) {
                ILFree(item);
                item = nullptr;
                break;
            }
            PIDLIST_ABSOLUTE deeper = ILCombine(item, child);
            ILFree(item);
            item = deeper;
            ComPtr<IShellFolder> sub;
            if (i + 1 < ids.size() && FAILED(folder->BindToObject(child, nullptr, IID_PPV_ARGS(&sub)))) {
                ILFree(item);
                item = nullptr;
            }
            CoTaskMemFree(child);
            folder = sub;
        }
        // Opens the folder it's in, with it selected.
        if (item)
            shown = SUCCEEDED(SHOpenFolderAndSelectItems(item, 0, nullptr, 0));
    }
    if (item)
        ILFree(item);
    folder.Reset();
    if (SUCCEEDED(init))
        CoUninitialize();
    return shown;
}

#else // not Windows

QList<Phone> list()
{
    return {};
}

QList<File> findVideos(const QString &, const std::function<bool(const QList<File> &)> &)
{
    return {};
}

void registerStream(mpv_handle *)
{
}

bool showInExplorer(const QString &, const QString &, const QString &)
{
    return false;
}

#endif

QString streamUrl(const QString &serial, const File &file)
{
    // In one go: the encoded path is full of "%2C" and the like, which a second arg() would fill in.
    return QStringLiteral("mtp://%1/%2?id=%3&size=%4")
        .arg(serial, QString::fromUtf8(QUrl::toPercentEncoding(file.path, "/")), file.objectId, QString::number(file.size));
}

} // namespace Phones
