package org.jellyfin.androidtv.ui.shared.toolbar

import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.model.AggregatedLibrary
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.CollectionType
import java.util.UUID

val PRIMARY_LIBRARY_TYPES = listOf(
	CollectionType.BOXSETS,
	CollectionType.MOVIES,
	CollectionType.TVSHOWS,
)

fun isPrimaryLibraryType(collectionType: CollectionType?): Boolean =
	collectionType in PRIMARY_LIBRARY_TYPES

fun primaryLibraryLabelRes(collectionType: CollectionType?): Int = when (collectionType) {
	CollectionType.BOXSETS -> R.string.lbl_collections
	CollectionType.MOVIES -> R.string.lbl_movies
	CollectionType.TVSHOWS -> R.string.lbl_series
	else -> R.string.pref_libraries
}

fun primaryLibraryIconRes(collectionType: CollectionType?): Int = when (collectionType) {
	CollectionType.BOXSETS -> R.drawable.ic_clapperboard
	CollectionType.MOVIES -> R.drawable.ic_movie
	CollectionType.TVSHOWS -> R.drawable.ic_tv
	else -> R.drawable.ic_clapperboard
}

fun partitionUserViews(views: List<BaseItemDto>): Pair<List<BaseItemDto>, List<BaseItemDto>> {
	val primary = PRIMARY_LIBRARY_TYPES.mapNotNull { type ->
		views.firstOrNull { it.collectionType == type }
	}
	val primaryIds = primary.mapNotNull { it.id }.toSet()
	val additional = views.filter { it.id !in primaryIds }
	return primary to additional
}

fun partitionAggregatedLibraries(
	libraries: List<AggregatedLibrary>,
): Pair<List<AggregatedLibrary>, List<AggregatedLibrary>> {
	val primary = PRIMARY_LIBRARY_TYPES.flatMap { type ->
		libraries.filter { it.library.collectionType == type }
	}
	val additional = libraries.filter { !isPrimaryLibraryType(it.library.collectionType) }
	return primary to additional
}

fun navigateToLibrary(
	library: BaseItemDto,
	navigationRepository: NavigationRepository,
	itemLauncher: ItemLauncher,
	serverId: UUID? = null,
	userId: UUID? = null,
) {
	val destination = if (serverId != null && userId != null) {
		when (library.collectionType) {
			CollectionType.LIVETV, CollectionType.MUSIC -> itemLauncher.getUserViewDestination(library)
			else -> Destinations.libraryBrowser(library, serverId, userId)
		}
	} else {
		itemLauncher.getUserViewDestination(library)
	}
	navigationRepository.navigate(destination)
}
