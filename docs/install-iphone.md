# Install the iPhone app

There is no App Store or TestFlight build. You install Cappuccino with a free
Apple ID using one of two routes:

- **xtool (USB):** build, sign and install over a USB cable with `make dev`. You
  repeat this every 7 days.
- **SideStore:** install an unsigned `.ipa` on the phone. SideStore re-signs it
  on the phone, so refreshes do not need Xcode.

Xcode is the fallback if xtool does not work for you; see
[Fallback: Xcode](#fallback-xcode).

## Before you start

- Tailscale must be running on the iPhone, on the same tailnet as your Herdr
  machines.
- Each machine's bridge must be exposed on the tailnet, as in
  [step 3 of the README](../README.md#3-expose-the-bridge-to-your-tailnet). You
  need its `https://<machine>.<tailnet>.ts.net` address.

## Route 1: xtool (USB, free Apple ID)

xtool builds the app, signs it with your Apple ID and installs it on the phone.
It needs a one-time setup on the Mac.

xtool uses Xcode's iOS SDK. If `xcode-select -p` prints the Command Line Tools,
prefix each command below, `make ipa` included, with
`env DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`.

1. On the Mac, run `make setup`. It installs xtool through mise, which pins it
   for macOS only.
2. Run `mise exec -- xtool setup` once. Choose a login method:
   - An API key needs a paid Apple Developer membership.
   - A password works with a free Apple ID, but xtool then uses private Apple
     APIs. The xtool docs suggest a throwaway Apple ID.
3. Connect the iPhone with a USB cable and tap Trust on the phone. On the iPhone,
   turn on Developer Mode in Settings → Privacy & Security.
4. Run `make dev`. It builds, signs and installs Cappuccino on the phone.
5. On the first launch, trust the developer certificate: Settings → General →
   VPN & Device Management.

Notes:

- The app stops launching 7 days after it was installed. Run `make dev` again
  before then.
- Reinstalling keeps your saved machines, because the bundle ID stays
  `com.azusachino.cappuccino`.
- Known issues with a free Apple ID: if every run asks to revoke a certificate,
  see [xtool issue #305](https://github.com/xtool-org/xtool/issues/305). If
  `make dev` ends with `opTimeout` after a fresh login, see
  [xtool issue #308](https://github.com/xtool-org/xtool/issues/308).

## Route 2: SideStore (refreshes on the phone)

1. On the Mac, run `make ipa`. It writes `apps/ios/xtool/Cappuccino.ipa`.
2. Install SideStore once by following the official guide at
   [sidestore.io](https://sidestore.io). That guide uses this Mac one time to
   sign SideStore and create the pairing file for the phone. SideStore's tools
   change, so its own guide is authoritative. If it disagrees with these steps,
   follow SideStore's guide.
3. Install the VPN companion app that the SideStore guide names.
4. AirDrop the `.ipa` to the phone, or copy it there. Open it in SideStore and
   choose My Apps → +.
5. Refresh the app within 7 days. SideStore can refresh in the background, and
   it needs Tailscale running on the phone for its own refresh.

To update, run `make ipa` again and install the new `.ipa` over the old copy in
SideStore. Your data is kept.

## Fallback: Xcode

If xtool does not work, run the app from Xcode. Run `make setup` and then
`make generate`, open `Cappuccino.xcodeproj`, select the `Cappuccino` target under
Signing & Capabilities, and choose your Personal Team. Connect the iPhone, choose
it as the run destination and press Run. Trust the developer certificate as in
step 5 of Route 1. `make generate` recreates the project and resets the team, so
choose your Personal Team again after it. The 7-day limit applies here too.

## Limits (all routes)

- A free Apple ID allows 3 active sideloaded apps. SideStore counts as one of
  them.
- A free Apple ID allows 10 app IDs per week.

## Add a machine

In the app, open Machines and add the machine's bridge address:
`https://<machine>.<tailnet>.ts.net`. See
[step 6 of the README](../README.md#6-add-a-machine).
