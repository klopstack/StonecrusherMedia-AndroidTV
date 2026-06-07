package org.jellyfin.androidtv.ui.shared.toolbar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.data.model.AggregatedLibrary
import org.jellyfin.androidtv.ui.base.button.ButtonColors
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID

@Composable
fun PrimaryLibraryButtons(
	activeLibraryId: UUID?,
	userViews: List<BaseItemDto>,
	aggregatedLibraries: List<AggregatedLibrary>,
	enableMultiServer: Boolean,
	colors: ButtonColors,
	activeColors: ButtonColors,
	navigationRepository: NavigationRepository,
	itemLauncher: ItemLauncher,
) {
	Row(
		horizontalArrangement = Arrangement.spacedBy(4.dp),
	) {
		if (enableMultiServer && aggregatedLibraries.isNotEmpty()) {
			partitionAggregatedLibraries(aggregatedLibraries).first.forEach { aggLib ->
				val isActiveLibrary = activeLibraryId == aggLib.library.id
				ExpandableIconButton(
					icon = ImageVector.vectorResource(primaryLibraryIconRes(aggLib.library.collectionType)),
					label = stringResource(primaryLibraryLabelRes(aggLib.library.collectionType)),
					onClick = {
						if (!isActiveLibrary) {
							navigateToLibrary(
								library = aggLib.library,
								navigationRepository = navigationRepository,
								itemLauncher = itemLauncher,
								serverId = aggLib.server.id,
								userId = aggLib.userId,
							)
						}
					},
					colors = if (isActiveLibrary) activeColors else colors,
				)
			}
		} else {
			partitionUserViews(userViews).first.forEach { library ->
				val isActiveLibrary = activeLibraryId == library.id
				ExpandableIconButton(
					icon = ImageVector.vectorResource(primaryLibraryIconRes(library.collectionType)),
					label = stringResource(primaryLibraryLabelRes(library.collectionType)),
					onClick = {
						if (!isActiveLibrary) {
							navigateToLibrary(
								library = library,
								navigationRepository = navigationRepository,
								itemLauncher = itemLauncher,
							)
						}
					},
					colors = if (isActiveLibrary) activeColors else colors,
				)
			}
		}
	}
}
