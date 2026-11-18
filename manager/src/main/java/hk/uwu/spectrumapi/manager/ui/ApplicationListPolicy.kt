package hk.uwu.spectrumapi.manager.ui

/** Pure policy used at both automatic and explicit permission-request entry points. */
internal object ApplicationListPermissionPolicy {
    const val MIUI_PROVIDER = "com.lbe.security.miui"

    fun directoryGranted(provider: String?, runtimeGranted: Boolean): Boolean =
        provider == null || (provider == MIUI_PROVIDER && runtimeGranted)

    fun shouldRequest(
        provider: String?,
        granted: Boolean,
        resumed: Boolean,
        pending: Boolean,
        attempted: Boolean,
        userInitiated: Boolean,
    ): Boolean = provider == MIUI_PROVIDER && !granted && resumed && !pending &&
        (userInitiated || !attempted)
}

internal interface ApplicationListItem {
    val name: String
    val pkg: String
    val system: Boolean
}

internal enum class ApplicationCatalogStatus {
    UNLOADED, LOADING, PERMISSION_DENIED, FAILED, EMPTY, INCOMPLETE, READY,
}

internal data class ApplicationListFilter(
    val query: String = "",
    val showSystem: Boolean = false,
    val selectedOnly: Boolean = false,
    val packageSort: Boolean = false,
    val selectedFirst: Boolean = true,
)

/** Only whitelist values cross this UI save boundary; no whole-settings draft is produced. */
internal data class WhitelistSubmission(val packages: Set<String>, val expected: Set<String>)

/** Filtering/loading are views of the catalog, never transformations of the selected packages. */
internal object AppSelectionPolicy {
    fun catalogStatus(
        permissionGranted: Boolean,
        loadSucceeded: Boolean,
        packages: Collection<String>,
        requiredPackage: String,
    ): ApplicationCatalogStatus = when {
        !permissionGranted -> ApplicationCatalogStatus.PERMISSION_DENIED
        !loadSucceeded -> ApplicationCatalogStatus.FAILED
        packages.isEmpty() -> ApplicationCatalogStatus.EMPTY
        requiredPackage !in packages -> ApplicationCatalogStatus.INCOMPLETE
        else -> ApplicationCatalogStatus.READY
    }

    fun hasConflict(baseline: Set<String>, remote: Set<String>): Boolean = baseline != remote

    fun canSave(
        remoteWritable: Boolean,
        permissionGranted: Boolean,
        catalogStatus: ApplicationCatalogStatus,
        baseline: Set<String>,
        remote: Set<String>,
    ): Boolean = remoteWritable && permissionGranted && catalogStatus == ApplicationCatalogStatus.READY &&
        !hasConflict(baseline, remote)

    /** Snapshot guard only, not a CAS. RemoteSettings repeats its check against the provider. */
    fun prepareSubmission(
        selection: Set<String>,
        baseline: Set<String>,
        remote: Set<String>,
        remoteWritable: Boolean,
        permissionGranted: Boolean,
        catalogStatus: ApplicationCatalogStatus,
    ): WhitelistSubmission? = if (canSave(remoteWritable, permissionGranted, catalogStatus, baseline, remote)) {
        WhitelistSubmission(selection.toSet(), baseline.toSet())
    } else null

    fun toggle(selection: Set<String>, pkg: String, checked: Boolean): Set<String> =
        if (checked) selection + pkg else selection - pkg

    fun retained(selection: Set<String>, catalogPackages: Collection<String>): List<String> =
        (selection - catalogPackages.toSet()).sorted()

    fun <T : ApplicationListItem> visible(
        catalog: List<T>,
        selection: Set<String>,
        filter: ApplicationListFilter,
    ): List<T> {
        val term = filter.query.trim()
        return catalog.filter { app ->
            (filter.showSystem || !app.system || app.pkg in selection) &&
                (!filter.selectedOnly || app.pkg in selection) &&
                (term.isEmpty() || app.name.contains(term, true) || app.pkg.contains(term, true))
        }.sortedWith(compareBy<T> { if (filter.selectedFirst && it.pkg in selection) 0 else 1 }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { if (filter.packageSort) it.pkg else it.name }
            .thenBy { it.pkg })
    }
}
