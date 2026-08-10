package com.nghetruyen.source.store

import com.nghetruyen.source.platform.SourceArtifactIdentity

/** Stable persistence key independent of display names and mutable source hosts. */
fun SourceArtifactIdentity.canonicalKey(): String = buildString {
    append(ecosystem.name)
    append('\n').append(repositoryId.trim())
    append('\n').append(remoteIdentity.trim())
}
