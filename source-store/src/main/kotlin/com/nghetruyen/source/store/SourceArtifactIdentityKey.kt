package com.nghetruyen.source.store

import com.nghetruyen.source.platform.SourceArtifactIdentity

 
fun SourceArtifactIdentity.canonicalKey(): String = buildString {
    append(ecosystem.name)
    append('\n').append(repositoryId.trim())
    append('\n').append(remoteIdentity.trim())
}
