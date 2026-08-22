package vn.nghetruyen.app.freesound

import vn.nghetruyen.app.ai.XpkVoiceCastSplitter

/**
 * Compatibility shim for the short-lived story-context experiment.
 *
 * Mode 3 no longer derives a local description from story text inside the app. The narration AI now
 * returns the dedicated Vietnamese `local_hint` beside the unchanged English Freesound query. This
 * method intentionally leaves every requirement untouched so it cannot reinterpret the scene or
 * alter any AI-owned timeline/playback decision.
 */
internal object Mode3LocalContextBinder {
    @Suppress("UNUSED_PARAMETER")
    fun attach(
        requirements: List<FreesoundAutoRequirement>,
        units: List<XpkVoiceCastSplitter.Unit>,
    ): List<FreesoundAutoRequirement> = requirements
}
