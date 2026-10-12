package com.testersfree.app

import android.app.Activity
import android.widget.LinearLayout
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewarded.ServerSideVerificationOptions
import com.google.android.ump.ConsentForm
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/** Test ads only. Never credit coins from an Android callback; server-side verification required. */
class AdsManager(private val activity: Activity) {
    private val consent = UserMessagingPlatform.getConsentInformation(activity)
    private var initialized = false
    private var rewarded: RewardedAd? = null
    private var activeBanner: AdView? = null
    // During development use Google's official test units, never live ads.
    // Production IDs are retained here for the reviewed release configuration.
    private val productionBannerId = "ca-app-pub-8854680295966508/7017750709"
    private val productionRewardedId = "ca-app-pub-8854680295966508/3570380150"
    private val bannerId = "ca-app-pub-3940256099942544/6300978111" // Test unit in all builds until production approval
    private val rewardedId = "ca-app-pub-3940256099942544/5224354917" // Test unit in all builds until production approval

    fun initialize(onReady: () -> Unit) {
        consent.requestConsentInfoUpdate(activity, ConsentRequestParameters.Builder().build(), {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                if (consent.canRequestAds()) startAds(onReady)
            }
            if (consent.canRequestAds()) startAds(onReady)
        }, {
            if (consent.canRequestAds()) startAds(onReady)
        })
    }

    private fun startAds(onReady: () -> Unit) {
        if (initialized) return
        initialized = true
        MobileAds.initialize(activity) { activity.runOnUiThread { onReady(); loadRewarded() } }
    }

    fun attachBanner(container: LinearLayout) {
        if (!initialized || !consent.canRequestAds()) return
        activeBanner?.destroy()
        val banner = AdView(activity)
        activeBanner = banner
        banner.setAdSize(AdSize.BANNER)
        banner.adUnitId = bannerId
        container.addView(banner)
        banner.loadAd(AdRequest.Builder().build())
    }

    private fun loadRewarded() {
        if (!consent.canRequestAds()) return
        RewardedAd.load(activity, rewardedId, AdRequest.Builder().build(), object : RewardedAdLoadCallback() {
            override fun onAdLoaded(ad: RewardedAd) { rewarded = ad }
            override fun onAdFailedToLoad(error: LoadAdError) { rewarded = null }
        })
    }

    fun privacyOptions() {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { /* UMP reports errors via callback; ads remain gated by consent. */ }
    }

    fun destroy() { activeBanner?.destroy(); activeBanner = null }

    fun showRewarded(userId: String, onViewed: () -> Unit, onUnavailable: () -> Unit) {
        val ad = rewarded ?: return onUnavailable()
        rewarded = null
        ad.setServerSideVerificationOptions(ServerSideVerificationOptions.Builder().setCustomData(userId).build())
        ad.show(activity) { _ ->
            // Local event is informative only: reward must come from verified AdMob SSV.
            onViewed()
        }
        loadRewarded()
    }
}
