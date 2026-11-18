#!/system/bin/sh
# Sourced by the root manager's module installer. No ROM overlay or service restart.
SKIPMOUNT=true
PROPFILE=false
POSTFSDATA=false
LATESTARTSERVICE=false

ui_print "- SpectrumAPI: arm64 Android 17 / API 37 only"
ABI="$(getprop ro.product.cpu.abi)"
[ "${ARCH:-}" = "arm64" ] || [ "$ABI" = "arm64-v8a" ] || abort "SpectrumAPI requires arm64-v8a."
SDK_API="${API:-$(getprop ro.build.version.sdk)}"
[ "$SDK_API" = "37" ] || abort "SpectrumAPI requires Android 17 / API 37; this device reports API $SDK_API."

ZN_FOUND=false
for ZN_DIR in /data/adb/modules/zygisksu /data/adb/modules/zygisknext; do
    if [ -f "$ZN_DIR/module.prop" ] && [ ! -e "$ZN_DIR/disable" ] && [ ! -e "$ZN_DIR/remove" ]; then
        ZN_FOUND=true
        break
    fi
done
[ "$ZN_FOUND" = "true" ] || abort "Install and enable Zygisk Next before installing SpectrumAPI."

[ -f "$MODPATH/lib/libspectrum_payload.so" ] || abort "Incomplete SpectrumAPI archive: lib/libspectrum_payload.so is missing."
set_perm_recursive "$MODPATH" 0 0 0755 0644
ui_print "- Target scope: /system/bin/audioserver only (single-FD static C++ runtime)"
ui_print "- No ROM library is replaced; no process is restarted by this installer."
