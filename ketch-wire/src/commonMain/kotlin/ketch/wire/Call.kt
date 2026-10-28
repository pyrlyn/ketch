@file:OptIn(ExperimentalSerializationApi::class)

package ketch.wire

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * One operation the app asks `ketch serve` for, with its arguments: a method of `ketch-ffi`'s `KetchCore`. The
 * operation names and fields are the ones the contract scenarios use; the calls the scenarios never make follow the
 * Rust method names the same way.
 */
@Serializable
@JsonClassDiscriminator("operation")
sealed interface Call {
    /** The install tree the calls act on. Returns a string. */
    @Serializable
    @SerialName("root")
    data object Root : Call

    /** Returns a list of [Package]. */
    @Serializable
    @SerialName("installed")
    data object InstalledPackages : Call

    /** An empty [query] browses every known package. Returns [SearchResults]. */
    @Serializable
    @SerialName("search")
    data class Search(
        val query: String,
        val limit: Int,
    ) : Call

    /** Returns a list of [Upgrade]. */
    @Serializable
    @SerialName("outdated")
    data object Outdated : Call

    /** Returns a list of [Installed]. */
    @Serializable
    @SerialName("install")
    data class Install(
        val specs: List<String>,
        val options: InstallOptions = InstallOptions(),
    ) : Call

    /** Empty [names] upgrades everything. Returns a list of [Installed]. */
    @Serializable
    @SerialName("upgrade")
    data class Upgrade(
        val names: List<String>,
    ) : Call

    /** Returns the removed [Package]s. */
    @Serializable
    @SerialName("uninstall")
    data class Uninstall(
        val names: List<String>,
    ) : Call

    /** A null [version] is the installed one. Returns a [Changelog]. */
    @Serializable
    @SerialName("changelog")
    data class ChangelogOf(
        @SerialName("package") val pkg: String,
        val version: String?,
    ) : Call

    /** Every release after [from] up to [to], newest first. Returns a list of [Changelog]. */
    @Serializable
    @SerialName("changelog_range")
    data class ChangelogRange(
        @SerialName("package") val pkg: String,
        val from: String?,
        val to: String?,
    ) : Call

    /** Returns a list of [Check]. */
    @Serializable
    @SerialName("doctor")
    data object Doctor : Call

    /** Returns a list of [PathChange]. */
    @Serializable
    @SerialName("doctor_fix")
    data object DoctorFix : Call

    /** Returns a list of [HistoryEvent], newest first. */
    @Serializable
    @SerialName("history")
    data class History(
        @SerialName("package") val pkg: String?,
        val limit: Int,
    ) : Call

    /** Returns [PackageInfo]. */
    @Serializable
    @SerialName("info")
    data class Info(
        @SerialName("package") val pkg: String,
    ) : Call

    /** Empty [names] pins everything. Returns the pinned [Package]s. */
    @Serializable
    @SerialName("pin")
    data class Pin(
        val names: List<String>,
    ) : Call

    /** Empty [names] unpins everything. Returns the unpinned [Package]s. */
    @Serializable
    @SerialName("unpin")
    data class Unpin(
        val names: List<String>,
    ) : Call

    /** A null [to] is the newest retained version. Returns [Installed]. */
    @Serializable
    @SerialName("rollback")
    data class Rollback(
        @SerialName("package") val pkg: String,
        val to: String?,
    ) : Call

    /** [keep], when given, becomes the retention setting. Returns a list of [Pruned]. */
    @Serializable
    @SerialName("prune")
    data class Prune(
        val names: List<String>,
        val keep: Int?,
    ) : Call

    /** Returns how many packages the registry now has. */
    @Serializable
    @SerialName("registry_refresh")
    data object RegistryRefresh : Call

    /** Returns [PathStatus]. */
    @Serializable
    @SerialName("path_status")
    data object PathStatusOf : Call

    /** Returns a list of [PathChange]. */
    @Serializable
    @SerialName("path_install")
    data class PathInstall(
        @SerialName("dry_run") val dryRun: Boolean,
    ) : Call

    /** Returns [Settings]. */
    @Serializable
    @SerialName("config")
    data object Config : Call

    /** The package a `ketch://package/<name>` link names. Returns a string. */
    @Serializable
    @SerialName("package_for_link")
    data class PackageForLink(
        val url: String,
    ) : Call
}

/** How to read the value a successful [call] returns. */
@Suppress("CyclomaticComplexMethod")
fun resultSerializer(call: Call): KSerializer<*> =
    when (call) {
        Call.Root, is Call.PackageForLink -> String.serializer()
        Call.InstalledPackages, is Call.Uninstall, is Call.Pin, is Call.Unpin -> ListSerializer(Package.serializer())
        is Call.Search -> SearchResults.serializer()
        Call.Outdated -> ListSerializer(Upgrade.serializer())
        is Call.Install, is Call.Upgrade -> ListSerializer(Installed.serializer())
        is Call.ChangelogOf -> Changelog.serializer()
        is Call.ChangelogRange -> ListSerializer(Changelog.serializer())
        Call.Doctor -> ListSerializer(Check.serializer())
        Call.DoctorFix, is Call.PathInstall -> ListSerializer(PathChange.serializer())
        is Call.History -> ListSerializer(HistoryEvent.serializer())
        is Call.Info -> PackageInfo.serializer()
        is Call.Rollback -> Installed.serializer()
        is Call.Prune -> ListSerializer(Pruned.serializer())
        Call.RegistryRefresh -> Long.serializer()
        Call.PathStatusOf -> PathStatus.serializer()
        Call.Config -> Settings.serializer()
    }
