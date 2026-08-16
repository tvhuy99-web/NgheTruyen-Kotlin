package vn.nghetruyen.app.audio

 
object ReferenceSonicRuntime {
    @Volatile
    var accurateMode: Boolean = false

    @Volatile
    var outputGain: Float = 1f
}
