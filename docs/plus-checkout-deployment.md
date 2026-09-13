# Direct Plus checkout

The GitHub/direct APK is prepared for a hosted, one-time supporter checkout, but the repository does not contain a payment account or server secret. The default build therefore keeps the CTA in preview mode and cannot charge anyone.

## Release configuration

Build the direct release with the HTTPS checkout origin supplied by the deployed service:

```powershell
.\gradlew.bat :app:assembleRelease -PplusCheckoutUrl=https://pay.example.com/pixelplayer-plus
```

PixelPlayer appends `amount` (USD, with `15.00` as the floor) and `currency=USD`. The hosted page owns the custom-amount field, payment provider integration, receipt/webhook verification, and supporter confirmation. Never put a provider secret, webhook signing key, or cloud model credential in the APK.

## Entitlement contract

After a successful payment, the service should issue a signed permanent entitlement containing an opaque entitlement id, the app package id, the supporter tier (`PLUS`), issue time, and a key id. The app may cache that signed result for offline use, but the service must verify the payment before issuing it. A hosted processing job must also require a short-lived job token, a per-supporter quota, explicit upload consent, deletion/retention rules, and a local fallback when the service is unavailable.

The $15 payment is a minimum contribution, not a subscription. Larger contributions grant the same Plus tier. Existing playback, downloads, lyrics, instrumentalization, offline mode, and library management stay free; Plus capacity and supporter features are additive.

## Before enabling the live CTA

1. Deploy an HTTPS checkout and webhook verifier.
2. Publish privacy, upload retention, refund, and support contact information on that page.
3. Add signed entitlement verification to the app/server pair.
4. Build with `-PplusCheckoutUrl=...` and exercise a test payment in the provider's sandbox.
5. Only then distribute the APK with the live URL.
