#pragma once

struct ZygiskNextAPI;

namespace spectrum {

bool initializePayload(const ZygiskNextAPI* api) noexcept;

// Installs pre-publication ServiceManager interception across loaded system images
// that import defaultServiceManager(), attaching the Spectrum NDK Binder extension
// when local "media.audio_flinger" is registered via IServiceManager::addService.
bool installPlatformBridge(const ZygiskNextAPI* api) noexcept;

} // namespace spectrum
