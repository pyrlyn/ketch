package ketch.core.trust

import ketch.core.error.KetchError
import ketch.core.model.TrustPolicy
import ketch.core.model.Verifier
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PolicyTest {
    private val actions = "https://token.actions.githubusercontent.com"

    private fun err(policy: TrustPolicy): String = assertFailsWith<KetchError> { checkPolicy(policy) }.message

    @Test
    fun aPolicyKeepsToTheKeysItsVerifierReads() {
        val stray = TrustPolicy(Verifier.Minisign, publicKey = "key", fingerprint = "0".repeat(40))
        assertTrue("does not apply" in err(stray))

        val issuerOnly = TrustPolicy(Verifier.Sigstore, issuer = actions)
        assertTrue("admits anyone" in err(issuerOnly))

        val noIssuer = TrustPolicy(Verifier.Sigstore, identity = "a@tny.town")
        assertTrue("trust.issuer" in err(noIssuer))

        val prefixed = TrustPolicy(Verifier.Sigstore, issuer = actions, repository = "github:o/r")
        assertTrue("`o/r`" in err(prefixed))
    }

    @Test
    fun keysAVerifierIgnoresAreStillRefused() {
        assertFailsWith<KetchError> { checkPolicy(TrustPolicy(Verifier.Minisign, publicKey = "RWQ", issuer = actions)) }
    }

    @Test
    fun aSigstorePolicyPinningAWorkflowRepositoryPasses() {
        checkPolicy(TrustPolicy(Verifier.Sigstore, issuer = actions, repository = "o/r"))
    }
}
