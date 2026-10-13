# Install the iPhone app

There is no App Store or TestFlight build. You install Cappuccino with a free
Apple ID using one of two routes:

- **Xcode direct:** build and run from Xcode over a USB cable. You repeat this
  every 7 days.
- **SideStore:** install an unsigned `.ipa` on the phone. SideStore re-signs it
  on the phone, so refreshes do not need Xcode.

## Before you start

- Tailscale must be running on the iPhone, on the same tailnet as your Herdr
  machines.
- Each machine's bridge must be exposed on the tailnet, as in
  [step 3 of the README](../README.md#3-expose-the-bridge-to-your-tailnet). You
  need its `https://<machine>.<tailnet>.ts.net` address.

## Route 1: Xcode direct (free Apple ID)

1. On the Mac, run `make setup` and then `make generate`.
2. Open `Cappuccino.xcodeproj`. Select the `Cappuccino` target, then open
   Signing & Capabilities. Keep "Automatically manage signing" on and choose
   your Personal Team.
3. Connect the iPhone with a USB cable and tap Trust on the phone.
4. On the iPhone, turn on Developer Mode in Settings → Privacy & Security.
5. In Xcode, choose the iPhone as the run destination and press Run.
6. On the first launch, trust your Apple ID on the phone: Settings → General →
   VPN & Device Management, then trust your Apple ID.

Notes:

- The app stops launching 7 days after it was installed. Connect the phone and
  press Run in Xcode again before then.
- `make generate` recreates the project, and the team setting goes with it.
  Choose your Personal Team again after each regenerate.
- Reinstalling keeps your saved machines, because the bundle ID stays
  `com.azusachino.cappuccino`.

## Route 2: SideStore (refreshes on the phone)

1. On the Mac, run `make ipa`. It writes `.build/ipa/Cappuccino.ipa`.
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

## Limits (both routes)

- A free Apple ID allows 3 active sideloaded apps. SideStore counts as one of
  them.
- A free Apple ID allows 10 app IDs per week.

## Add a machine

In the app, open Machines and add the machine's bridge address:
`https://<machine>.<tailnet>.ts.net`. See
[step 6 of the README](../README.md#6-add-a-machine).
