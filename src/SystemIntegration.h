#pragma once

// Vessel's place in the system: offered to open videos ("Open with", and as a choice in
// Settings > Default apps on Windows). Registered for the current user only (no admin
// rights needed), pointing at the exe that's running. Elsewhere it does nothing (yet).
namespace SystemIntegration {

// Writes the registration if it's missing or points at another copy of the exe.
void registerFileTypes();
// Takes all of it out again: run by the uninstaller ("VesselPlayer.exe --unregister").
void unregisterFileTypes();

} // namespace SystemIntegration
