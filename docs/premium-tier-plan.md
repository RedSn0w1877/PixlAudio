# PixelPlayer Plus plan

PixelPlayer stays ad free and the existing player, offline playback, lyrics, instrumental tools, and library management remain free. Plus is a one time supporter entitlement with a minimum contribution of $15 USD; a supporter may choose a larger amount without changing the entitlement level.

The app should unlock Plus only from a verified entitlement. For the direct/GitHub distribution, a hosted HTTPS checkout and server verifier own payment validation; the app can cache a signed entitlement for offline use. The `data.premium` registry intentionally has no billing dependency so the checkout provider can be swapped without changing feature code. Until that service is deployed, the Plus CTA remains a non-charging preview.

The first Plus features should be additive and capacity based. The local engines and export boundary are implemented in `data.premium`; hosted acceleration remains opt-in and endpoint-driven:

* **Cloud Studio:** hosted high capacity models for stem separation and richer lyric processing when the user opts in.
* **Deep Discovery:** larger recommendation candidate pools and more varied adaptive mixes.
* **Smart Playlist Tools:** mood, energy, era, and context based playlist generation.
* **Pro Background Studio:** a larger quiet processing budget while charging and idle. Free background processing remains available.
* **Advanced Audio Exports:** higher quality stem exports and detailed metadata.
* **Insight Lab:** detailed playback, recommendation, and processing diagnostics.
* **Supporter Themes:** animated player themes and supporter accents.

`PremiumSmartPlaylistEngine` provides explainable local presets for favorites, discovery, recently added, short/long listening, and artist radio. `PremiumInsightEngine` produces a privacy-preserving local summary, and `AdvancedAudioExporter` creates a portable stem ZIP with metadata. `ConfiguredCloudStudioClient` reuses the existing Gradio/direct POST clients only after HTTPS validation and explicit upload consent, then returns a local-fallback outcome on failure.

Do not gate bug fixes, playback reliability, offline behavior, the existing lyrics workflow, library management, or the basic instrumentalizer. Cloud work must be clearly labeled as optional, and must fail back to local processing when unavailable.

Suggested upgrade copy:

> Hey! My name is Hoa, and I'm the developer. I love building great things for people, but sometimes it gets kind of hard to keep up with updates and burnout. And I thought of subscriptions, but I immediately rejected that thought. I hate subscriptions. But I did want a way to reward people who support me. The app is staying completely ad free and all of the current features will be permanently free. But for a small $15 one time payment, you support me through my journey by funding features and motivating me to make even better things for everyone. And if you're even more willing to support me even more, you could set your own price to be higher than $15, but you really don't have to. By purchasing Plus, you empower me to push myself further, and you'll get access to a suite of new and exclusive features. Thanks! :)
