package com.slopus.happy.ui.connection

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.slopus.happy.AppGraph
import com.slopus.happy.R
import com.slopus.happy.agent.AgentLinkStatus
import com.slopus.happy.agent.AgentProfileState
import com.slopus.happy.ui.components.Avatar
import com.slopus.happy.ui.components.Ionicon
import com.slopus.happy.ui.components.ItemList
import com.slopus.happy.ui.components.Screen
import com.slopus.happy.ui.connect.LocalAddFlow
import com.slopus.happy.ui.conversation.openChiefOfStaff
import com.slopus.happy.ui.home.AccountMenu
import com.slopus.happy.ui.home.rememberAccountMenu
import com.slopus.happy.ui.settings.AboutActions
import com.slopus.happy.ui.settings.AboutInfo
import com.slopus.happy.ui.settings.AccountsCard
import com.slopus.happy.ui.settings.AccountsCardActions
import com.slopus.happy.ui.settings.RemoveCard
import com.slopus.happy.ui.settings.SettingsEditItem
import com.slopus.happy.ui.settings.SettingsHeader
import com.slopus.happy.ui.settings.SettingsPageSpec
import com.slopus.happy.ui.settings.SettingsPageTags
import com.slopus.happy.ui.settings.SettingsProfile
import com.slopus.happy.ui.settings.SettingsCard
import com.slopus.happy.ui.settings.SettingsEntry
import com.slopus.happy.ui.settings.SettingsSearchBar
import com.slopus.happy.ui.settings.SettingsSearchButton
import com.slopus.happy.ui.settings.SettingsSearchResults
import com.slopus.happy.ui.settings.SettingsSection
import com.slopus.happy.ui.settings.SettingsTile
import com.slopus.happy.ui.settings.aboutSection
import com.slopus.happy.ui.settings.addAgentEntry
import com.slopus.happy.ui.settings.rememberSettingsSearch
import com.slopus.happy.ui.settings.settingsProfileEntries
import com.slopus.happy.ui.settings.settingsSearchResults
import com.slopus.happy.ui.settings.supportEntry
import com.slopus.happy.ui.settings.SettingsTiles
import com.slopus.happy.ui.settings.rememberAboutActions
import com.slopus.happy.ui.settings.rememberAboutInfo
import com.slopus.happy.ui.theme.HappyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** An agent connection's settings. */
@Serializable data class AgentConnectionSettingsRoute(val connectionId: String)

/** The agent connection screens beside home. HappyApp's NavHost calls this once. */
fun NavGraphBuilder.agentHomeRoutes(graph: AppGraph, nav: NavHostController) {
    composable<AgentConnectionSettingsRoute> { entry ->
        AgentConnectionSettings(graph, nav, entry.toRoute<AgentConnectionSettingsRoute>().connectionId)
    }
    agentSettingsPages(graph, nav)
}

object AgentSettingsTags {
    const val BAR = "agentSettings.bar"
    const val PROFILE = "agentSettings.profile"
    const val APPEARANCE = "agentSettings.appearance"
}

/** Everything an agent connection's settings ask of the app. */
@Immutable
class AgentSettingsActions(
    val onBack: () -> Unit = {},
    /** The person's own name and picture on this agent. */
    val onProfile: () -> Unit = {},
    val onAppearance: () -> Unit = {},
    /** Disconnects this connection; asked for only after the person confirmed. */
    val onRemove: () -> Unit = {},
    /** Opens this connection's Chief of Staff with [prefix] typed, unsent. */
    val onAskChief: (prefix: String) -> Unit = {},
)

/** The Profile row: the person's own name and picture on the agent. */
@Immutable
data class AgentPerson(val name: String = "", val photo: ImageBitmap? = null)

/**
 * An agent connection's settings, as iOS's SettingsViewController lays out
 * a connection's page: the agent's own picture, large and centred, with a
 * pencil badge whose menu asks its Chief of Staff to change its picture or
 * name (nothing is sent until the person sends it), its name and link
 * status under it; every account on the phone with Add Agent; the person's
 * own Profile here; Appearance (the theme and the desktop's Hide projects);
 * Support us; About; Disconnect, after a confirm. A device token can't reach
 * the agent's device list (API.md, "Credentials on the connector's
 * socket"), so disconnecting forgets the agent on this phone only.
 *
 * Search, as on a Happy account's page ([SettingsSearchResults]), finds the
 * accounts by name, the picture's two asks (which run), this page's rows
 * and [appearance]'s, which change in place.
 */
@Composable
fun AgentConnectionSettingsScreen(
    profile: SettingsProfile,
    accounts: AccountMenu,
    actions: AgentSettingsActions,
    about: AboutInfo,
    accountActions: AccountsCardActions = AccountsCardActions({}, {}),
    aboutActions: AboutActions = AboutActions(),
    person: AgentPerson = AgentPerson(),
    /** Appearance's rows, for search: found, they change here, as on their page. */
    appearance: List<SettingsEntry> = emptyList(),
) {
    val namePrefix = stringResource(R.string.agent_settings_edit_name_prefix)
    val avatarPrefix = stringResource(R.string.agent_settings_edit_avatar_prefix)
    val changeAvatar = stringResource(R.string.settings_change_avatar)
    val editName = stringResource(R.string.settings_edit_name)
    val edit = listOf(
        SettingsEditItem(changeAvatar, Ionicon.ImageOutline, SettingsPageTags.EDIT_AVATAR) { actions.onAskChief(avatarPrefix) },
        SettingsEditItem(editName, Ionicon.Pencil, SettingsPageTags.EDIT_NAME) { actions.onAskChief(namePrefix) },
    )
    val search = rememberSettingsSearch()
    val sections = listOf(
        SettingsSection(
            listOf(
                SettingsEntry(
                    id = AgentSettingsTags.PROFILE,
                    title = stringResource(R.string.settings_account_profile),
                    icon = { PersonPicture(person.photo, profile.id, SettingsPageSpec.tile) },
                    detail = person.name.takeIf { it.isNotEmpty() },
                    onClick = actions.onProfile,
                ),
            ),
        ),
        SettingsSection(
            listOf(
                SettingsEntry(
                    id = AgentSettingsTags.APPEARANCE,
                    title = stringResource(R.string.settings_appearance),
                    icon = { SettingsTile(Ionicon.ColorPaletteOutline, SettingsTiles.purple) },
                    onClick = actions.onAppearance,
                ),
            ),
        ),
        SettingsSection(listOf(supportEntry(about, aboutActions))),
        aboutSection(about, aboutActions),
    )
    // The bar on the page's own grey (black in dark), the groups on it: one surface, as the chat's details.
    Screen(
        header = {
            if (search.searching) {
                SettingsSearchBar(search, AgentSettingsTags.BAR)
            } else {
                AgentTopBar(
                    tag = AgentSettingsTags.BAR,
                    container = HappyTheme.colors.grouped.background,
                    navigationIcon = { BarIconButton(Ionicon.ArrowBack, stringResource(R.string.common_back), actions.onBack) },
                    title = { Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge, maxLines = 1) },
                    actions = { SettingsSearchButton(search) },
                )
            }
        },
        avoidKeyboard = search.searching,
    ) {
        if (search.searching) {
            // The picture's two asks, found here, run: the Chief of Staff's chat opens with the words typed in.
            val asks = listOf(
                SettingsEntry(ASK_NAME, editName, icon = { SettingsTile(Ionicon.CreateOutline, SettingsTiles.blue) }, chevron = false, onClick = { actions.onAskChief(namePrefix) }, closesSearch = true),
                SettingsEntry(ASK_AVATAR, changeAvatar, icon = { SettingsTile(Ionicon.ImageOutline, SettingsTiles.orange) }, chevron = false, onClick = { actions.onAskChief(avatarPrefix) }, closesSearch = true),
            )
            val profiles = settingsProfileEntries(accounts, accountActions.onSwitch)
            val rows = listOf(addAgentEntry(accountActions)) + sections.flatMap { it.rows } + appearance
            SettingsSearchResults(search, settingsSearchResults(search.query, profiles, asks, rows))
        } else {
            ItemList {
                SettingsHeader(profile, edit)
                AccountsCard(accounts, accountActions)
                for (section in sections) SettingsCard(section)
                RemoveCard(agent = true, name = profile.name, onRemove = actions.onRemove)
            }
        }
    }
}

private const val ASK_NAME = "agentSettings.ask.name"
private const val ASK_AVATAR = "agentSettings.ask.avatar"

/** The person's picture round at [size], else the one [seed] picks (as their Profile page shows it). */
@Composable
internal fun PersonPicture(photo: ImageBitmap?, seed: String, size: Dp) {
    if (photo != null) {
        Image(photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape))
    } else {
        Avatar(seed, size)
    }
}

/** [bytes] decoded once per array; null for none or bytes that aren't an image. */
@Composable
internal fun rememberPhoto(bytes: ByteArray?): ImageBitmap? = remember(bytes) {
    bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
}

@Composable
internal fun statusText(status: AgentLinkStatus): String = stringResource(
    when (status) {
        AgentLinkStatus.Connected -> R.string.agent_settings_connected
        AgentLinkStatus.Connecting -> R.string.agent_settings_connecting
        AgentLinkStatus.Offline -> R.string.agent_settings_offline
        AgentLinkStatus.Revoked -> R.string.agent_settings_revoked
        AgentLinkStatus.UpdateRequired -> R.string.agent_settings_update
    },
)

/**
 * Forgets the agent connection [id] on this phone. When it was open, the
 * next account opens: a Happy account first, else another agent; none, the
 * first screen.
 */
internal suspend fun removeAgentConnection(graph: AppGraph, id: String) {
    val open = graph.openAgentId.value == id
    val next = graph.accountStore.registry.value.ordered.firstOrNull()?.id
        ?: graph.agentConnections.accounts.value.filter { it.id != id }.minByOrNull { it.addedAt }?.id
    graph.agentConnections.remove(id)
    if (!open) return
    if (next != null) {
        graph.open(next)
    } else {
        withContext(Dispatchers.IO) { graph.accountStore.select(null) }
        graph.auth.reload()
    }
}

/**
 * The accounts card's actions, the same on both kinds of page: a tap opens
 * another account, Add scans, holding an agent connection disconnects it and
 * holding the open Happy account signs it out (another Happy account isn't
 * signed out from here: its push token and data belong to its own session).
 */
@Composable
internal fun rememberAccountsCardActions(graph: AppGraph): AccountsCardActions {
    val scope = rememberCoroutineScope()
    val add = LocalAddFlow.current
    return remember(graph, add) {
        AccountsCardActions(
            onSwitch = { graph.open(it) },
            onAdd = { add?.scan() },
            onRemove = { entry -> if (entry.agent) scope.launch { removeAgentConnection(graph, entry.id) } else graph.logout() },
            canRemove = { it.agent || (it.active && graph.openAgentId.value == null) },
        )
    }
}

@Composable
private fun AgentConnectionSettings(graph: AppGraph, nav: NavHostController, connectionId: String) {
    val agents by graph.agentConnections.accounts.collectAsStateWithLifecycle()
    val agent = agents.firstOrNull { it.id == connectionId } ?: return
    val session = remember(connectionId) { graph.agentSessions.session(connectionId) }
    val status = session?.status?.collectAsStateWithLifecycle()?.value ?: AgentLinkStatus.Offline
    val personState = session?.profile?.state?.collectAsStateWithLifecycle()?.value ?: AgentProfileState()
    val personPhoto = rememberPhoto(session?.profile?.photo?.collectAsStateWithLifecycle()?.value)
    val theme by graph.localSettings.themePreference.collectAsStateWithLifecycle()
    val projectsHidden = session?.projectsHidden?.collectAsStateWithLifecycle()?.value ?: false
    // Read every time the page shows, as iOS's Profile row is.
    LaunchedEffect(session) { session?.profile?.refresh() }
    val scope = rememberCoroutineScope()
    val accounts = rememberAccountMenu(graph)
    val actions = remember(graph, nav, connectionId) {
        AgentSettingsActions(
            onBack = { nav.popBackStack() },
            onProfile = { nav.navigate(AgentProfileRoute(connectionId)) },
            onAppearance = { nav.navigate(AgentAppearanceRoute(connectionId)) },
            onAskChief = { prefix -> nav.openChiefOfStaff(connectionId, prefix) },
            onRemove = { scope.launch { removeAgentConnection(graph, connectionId) } },
        )
    }
    AgentConnectionSettingsScreen(
        profile = SettingsProfile(
            id = connectionId,
            seed = accounts.entries.firstOrNull { it.id == connectionId }?.seed ?: connectionId,
            name = agent.name,
            subtitle = statusText(status),
            imageUri = agent.avatarFile?.let { "file://$it" },
            thumbhash = agent.thumbhash,
        ),
        accounts = accounts,
        actions = actions,
        about = rememberAboutInfo(graph),
        accountActions = rememberAccountsCardActions(graph),
        aboutActions = rememberAboutActions(graph, nav),
        person = AgentPerson(personState.name, personPhoto),
        appearance = agentAppearanceSections(theme, graph.localSettings::setThemePreference, projectsHidden, { session?.setProjectsHidden(it) }).flatMap { it.rows },
    )
}
