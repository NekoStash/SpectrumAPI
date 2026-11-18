#pragma once
#include <android/binder_auto_utils.h>
#include <cstddef>
#include <cstdint>

namespace spectrum::capture {
// Called only during playback Track creation/destruction, never for RecordTrack.
void registerTrack(void* object, uint32_t uid, int session, int usage,
                   uint32_t sampleRate, uint32_t format, uint32_t channels) noexcept;
void retireTrack(void* object) noexcept;
void setTrackActive(void* object, bool active) noexcept;
// Realtime entry: bounded, nonblocking, no Binder calls or allocation.
void pcm(void* object, const void* data, size_t frames) noexcept;
// Mode bits: 1<<0 APP_UID, 1<<1 TRACK, 1<<2 ALL_MEDIA, 1<<3 OUTPUT_MIX.
void setReady(uint32_t supportedModes, const char* diagnostic) noexcept;
ndk::SpAIBinder createService();
}
