package com.sitta.core.vision

import android.graphics.Bitmap
import com.machinezoo.sourceafis.FingerprintImage
import com.machinezoo.sourceafis.FingerprintMatcher
import com.machinezoo.sourceafis.FingerprintTemplate
import com.sitta.core.common.MatchDecision
import com.sitta.core.domain.MatchCandidateResult
import com.sitta.core.domain.MatchResult
import com.sitta.core.domain.Matcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class SourceAfisMatcher : Matcher {
    override suspend fun match(
        probe: Bitmap,
        candidates: Map<String, Bitmap>,
        threshold: Double,
    ): MatchResult {
        return withContext(Dispatchers.Default) {
            val probeTemplate = FingerprintTemplate(FingerprintImage(bitmapToBytes(probe)))
            val matcher = FingerprintMatcher(probeTemplate)
            val results = candidates.map { (id, bitmap) ->
                val candidateTemplate = FingerprintTemplate(FingerprintImage(bitmapToBytes(bitmap)))
                val score = matcher.match(candidateTemplate)
                val decision = MatchDecision.decide(score, threshold)
                MatchCandidateResult(candidateId = id, score = score, decision = decision)
            }.sortedByDescending { it.score }

            MatchResult(thresholdUsed = threshold, candidates = results)
        }
    }

    private fun bitmapToBytes(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }
}
