package ketch.desktop.store

import ketch.desktop.core.FakeCore
import ketch.desktop.core.scenario
import ketch.wire.Call
import ketch.wire.Outcome
import ketch.wire.Scenario
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A `ketch://` link only shows a package; it never installs one on its own. */
class LinkTest {
    private fun linkTo(name: String) = Scenario("link", "a ketch:// link", Call.PackageForLink("ketch://install/$name"), emptyList(), Outcome.Ok(JsonPrimitive(name)))

    @Test
    fun aLinkToAKnownPackageOpensItsPageWithoutInstalling() =
        runTest {
            val core = FakeCore().script(scenario("search")).script(linkTo("ripgrep"))
            val store = KetchStore(core)
            store.open("ketch://install/ripgrep")
            assertEquals("ripgrep", store.state.value.linked)
            assertTrue(core.calls.none { it is Call.Install }, core.calls.toString())
            store.clearLink()
            assertNull(store.state.value.linked)
        }

    @Test
    fun aLinkToAnInstalledPackageNeedsNoSearch() =
        runTest {
            val core = FakeCore().script(scenario("installed")).script(linkTo("fd"))
            val store = KetchStore(core)
            store.refresh()
            store.open("ketch://install/fd")
            assertEquals("fd", store.state.value.linked)
            assertTrue(core.calls.none { it is Call.Search })
        }

    @Test
    fun aLinkToAPackageNobodyKnowsIsReported() =
        runTest {
            val store = KetchStore(FakeCore().script(scenario("search")).script(linkTo("nope")))
            store.open("ketch://install/nope")
            assertNull(store.state.value.linked)
            assertEquals("No package named nope.", store.state.value.error)
        }

    @Test
    fun aLinkTheCoreRefusesIsReported() =
        runTest {
            // Nothing scripted for package_for_link: the fake refuses it, as the core refuses a malformed link.
            val store = KetchStore(FakeCore())
            store.open("ketch://nonsense")
            assertNull(store.state.value.linked)
            assertTrue(store.state.value.error != null)
        }
}
