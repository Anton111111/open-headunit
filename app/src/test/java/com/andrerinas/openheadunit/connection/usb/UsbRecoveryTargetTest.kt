package com.andrerinas.openheadunit.connection.usb

import org.junit.Assert.*
import org.junit.Test

class UsbRecoveryTargetTest {
    private val old = UsbRecoveryDevice("old", 0x18d1, 0x2d00, "Maker", "Box", "serial-a")
    private fun target(before: List<UsbRecoveryDevice> = listOf(old)) = UsbRecoveryTarget(old, before)

    @Test fun followsOneMatchingEnumerationAtANewAddress() {
        val fresh = old.copy(path = "new")
        assertEquals(fresh, target().select(listOf(fresh)) { true })
    }
    @Test fun neverSelectsAnUnrelatedAccessoryEvenAtTheOldAddress() {
        assertNull(target().select(listOf(old.copy(serial = "other"))) { true })
        assertNull(target().select(listOf(old.copy(path = "new", model = "Other"))) { true })
    }
    @Test fun duplicateFixedSerialsAreAmbiguousEvenIfOneDisappears() {
        val other = old.copy(path = "other")
        assertNull(target(listOf(old, other)).select(listOf(other)) { true })
        assertNull(target().select(listOf(old.copy(path = "new"), other)) { true })
    }
    @Test fun preexistingCompanionPathCannotBecomeTheRecoveryTarget() {
        val before = old.copy(path = "other", serial = "serial-b")
        assertNull(target(listOf(old, before)).select(listOf(old.copy(path = "other"))) { true })
    }
    @Test fun withheldSerialIsProvisionalAndMustMatchAfterPermission() {
        val provisional = old.copy(path = "new", serial = null)
        assertEquals(provisional, target().select(listOf(provisional)) { false })
        assertNull(target().select(listOf(provisional)) { true })
        assertNull(target().select(listOf(provisional.copy(serial = "serial-b"))) { true })
    }
    @Test fun missingSerialNeedsBothStringsAndAUniqueMatch() {
        val noSerial = old.copy(serial = null)
        val policy = UsbRecoveryTarget(noSerial, listOf(noSerial))
        assertEquals(noSerial.copy(path = "new"), policy.select(listOf(noSerial.copy(path = "new"))) { true })
        assertNull(policy.select(listOf(noSerial, noSerial.copy(path = "other"))) { true })
        val bare = noSerial.copy(model = null, manufacturer = null)
        assertFalse(UsbRecoveryTarget(bare, listOf(bare)).canFollowEnumeration)
        assertNull(UsbRecoveryTarget(bare, listOf(bare)).select(listOf(bare.copy(path = "new"))) { true })
    }
    @Test fun accessoryAdbVariantStillNeedsTheSameIdentity() {
        val fresh = old.copy(path = "new", product = 0x2d01)
        assertEquals(fresh, target().select(listOf(fresh)) { true })
        assertNull(target().select(listOf(fresh.copy(serial = "other"))) { true })
    }
    @Test fun normalModeIsNotGuessedFromAccessoryIds() {
        assertNull(target().select(listOf(old.copy(path = "new", vendor = 0x2717, product = 0xff40))) { true })
    }
    @Test fun ambiguitySeenDuringPollingSurvivesACompanionsDisappearance() {
        val policy = target()
        val fresh = old.copy(path = "new")
        policy.observe(listOf(fresh, old.copy(path = "other")))
        assertNull(policy.select(listOf(fresh)) { true })
    }
    @Test fun anotherAccessoryDisappearingDoesNotBecomeANewUniqueCandidate() {
        val companion = old.copy(path = "other", serial = "serial-b")
        val policy = target(listOf(old, companion))
        policy.observe(emptyList())
        assertNull(policy.select(listOf(old.copy(path = "new"))) { true })
    }
    @Test fun stationaryDistinguishableCompanionDoesNotPreventRecovery() {
        val companion = old.copy(path = "other", serial = "serial-b")
        val fresh = old.copy(path = "new")
        assertEquals(fresh, target(listOf(old, companion)).select(listOf(companion, fresh)) { true })
    }
    @Test fun candidateEvidenceCanGrowButCannotChangeAfterPermission() {
        val unknown = old.copy(serial = null)
        assertTrue(unknown.agreesWith(old))
        assertFalse(old.agreesWith(unknown))
        assertFalse(old.agreesWith(old.copy(serial = "other")))
    }

    @Test fun companionDetachBetweenPollsRemainsAmbiguous() {
        val companion = old.copy(path = "other", serial = "serial-b")
        val policy = target(listOf(old, companion))
        policy.noteDetach(companion.path, companion.vendor, companion.product)
        assertNull(policy.select(listOf(companion, old.copy(path = "new"))) { true })
        assertFalse(policy.canRetryUnchangedWithoutIdentity)
    }
    @Test fun insufficientIdentityOnlyAllowsTheSeparateUnchangedEnumerationPath() {
        val bare = old.copy(serial = null, model = null, manufacturer = null)
        val policy = UsbRecoveryTarget(bare, listOf(bare))
        assertTrue(policy.canRetryUnchangedWithoutIdentity)
        policy.observe(listOf(bare, bare.copy(path = "other")))
        assertFalse(policy.canRetryUnchangedWithoutIdentity)
    }

    @Test fun learnedSerialSurvivesPermissionLossAndRejectsAConflictingGrant() {
        val unknown = old.copy(serial = null)
        val policy = UsbRecoveryTarget(unknown, listOf(unknown))
        val first = old.copy(path = "first")
        assertEquals(first, policy.select(listOf(first)) { true })
        policy.retainKnownFields(first)
        val next = unknown.copy(path = "next")
        assertEquals(next, policy.select(listOf(next)) { false })
        assertNull(policy.select(listOf(next.copy(serial = "other"))) { true })
        assertEquals(next.copy(serial = old.serial), policy.select(listOf(next.copy(serial = old.serial))) { true })
        assertFalse(first.conflictsWith(first.copy(serial = null)))
        assertTrue(first.conflictsWith(first.copy(serial = "other")))
    }

    @Test fun aConnectableNormalPhoneCannotDisappearAndReturnAsOurAccessory() {
        val phone = old.copy(path = "phone", vendor = 0x2717, product = 0xff40,
            manufacturer = "Phone vendor", model = "Phone model", serial = "phone-serial")
        val fresh = old.copy(path = "new")
        val policy = UsbRecoveryTarget(old, listOf(old, phone), setOf(phone.path))
        assertEquals(fresh, policy.select(listOf(fresh, phone)) { true })
        policy.observe(emptyList())
        assertNull(policy.select(listOf(fresh)) { true })
    }

}
