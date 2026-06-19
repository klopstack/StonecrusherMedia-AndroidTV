package org.moonfin.server.emby.feature

import org.moonfin.server.core.feature.ServerFeatureSupport

object EmbyFeatureSupport : ServerFeatureSupport {
	override val supportedFeatures = emptySet<org.moonfin.server.core.feature.ServerFeature>()
}
