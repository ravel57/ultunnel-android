# Mobile core update, 2026-11-11

Libraries were rebuilt from /Users/petr/dev/GolandProjects/sing-box using Go 1.25.5
and the fork's gomobile. Build manifests include installed artifact hashes.

Protocols: AmneziaWG 3.1, TUIC v5, MASQUE, AnyTLS, ShadowTLS v3 with encrypted
Shadowsocks, TrustTunnel, Hysteria 2 with Gecko and Naive. Existing Tailscale,
OpenVPN, OpenConnect and USB/IP build options were retained.

Android: main libbox.aar supports Naive starting at API 24. The API 21 legacy
variant excludes Naive, as required by the current implementation. Both archives
include arm64-v8a, armeabi-v7a, x86 and x86_64.

iOS: device arm64 and simulator arm64/x86_64 slices were replaced in Libbox.xcframework.
The project's existing macOS/tvOS slices were preserved. Objective-C methods match
the previous iOS library. Android Libbox, PlatformInterface and CommandServerHandler
Java APIs also match the previous library.

The mobile changes preserve complete outbound/endpoint dependency graphs and isolate
invalid/cyclic dependencies per profile. WireGuard checks use the actual endpoint,
validate peer addresses and run without creating a separate system interface.
Checks use IPv4 DNS, a 10-second per-request deadline and a second IP service.
Imports distinguish AmneziaWG from plain WireGuard, recognize ShadowTLS chains and
normalize legacy XHTTP transport fields.

Backups and build logs: /private/tmp/ultunnel-mobile-20261111/previous-libraries
and /private/tmp/ultunnel-mobile-20261111/*.log. Application packages and automated
test suites were not built/run; live device traffic remains to be checked.
