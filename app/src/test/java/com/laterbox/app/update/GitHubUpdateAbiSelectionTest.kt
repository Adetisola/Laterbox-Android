package com.laterbox.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubUpdateAbiSelectionTest {

    private val sampleAssets = listOf(
        GitHubUpdateChecker.ReleaseAsset(
            name = "Laterbox-ARM32.apk",
            downloadUrl = "https://github.com/Adetisola/Laterbox/releases/download/v1.0.19/Laterbox-ARM32.apk",
            size = 52_000_000L
        ),
        GitHubUpdateChecker.ReleaseAsset(
            name = "Laterbox-ARM64.apk",
            downloadUrl = "https://github.com/Adetisola/Laterbox/releases/download/v1.0.19/Laterbox-ARM64.apk",
            size = 59_000_000L
        ),
        GitHubUpdateChecker.ReleaseAsset(
            name = "Laterbox-Universal.apk",
            downloadUrl = "https://github.com/Adetisola/Laterbox/releases/download/v1.0.19/Laterbox-Universal.apk",
            size = 103_000_000L
        )
    )

    @Test
    fun `64-bit device selects ARM64 APK`() {
        val supportedAbis = arrayOf("arm64-v8a", "armeabi-v7a", "armeabi")
        val selected = GitHubUpdateChecker.selectBestApkAsset(sampleAssets, supportedAbis)

        assertNotNull(selected)
        assertEquals("Laterbox-ARM64.apk", selected!!.asset.name)
        assertEquals("ARM64 (arm64-v8a)", selected.targetAbiLabel)
        assertEquals(59_000_000L, selected.asset.size)
    }

    @Test
    fun `Samsung Galaxy A02s (32-bit ARM) selects ARM32 APK`() {
        // Physical Galaxy A02s reports only 32-bit ARM ABIs
        val supportedAbis = arrayOf("armeabi-v7a", "armeabi")
        val selected = GitHubUpdateChecker.selectBestApkAsset(sampleAssets, supportedAbis)

        assertNotNull(selected)
        assertEquals("Laterbox-ARM32.apk", selected!!.asset.name)
        assertEquals("ARM32 (armeabi-v7a)", selected.targetAbiLabel)
        assertEquals(52_000_000L, selected.asset.size)
    }

    @Test
    fun `falls back to Universal APK when ABI split is not available`() {
        val universalOnlyAssets = listOf(
            GitHubUpdateChecker.ReleaseAsset(
                name = "Laterbox-Universal.apk",
                downloadUrl = "https://example.com/Laterbox-Universal.apk",
                size = 103_000_000L
            )
        )

        val selected = GitHubUpdateChecker.selectBestApkAsset(
            universalOnlyAssets,
            arrayOf("armeabi-v7a", "armeabi")
        )

        assertNotNull(selected)
        assertEquals("Laterbox-Universal.apk", selected!!.asset.name)
        assertEquals("Universal", selected.targetAbiLabel)
    }

    @Test
    fun `legacy release with app-release apk is selected as Universal`() {
        val legacyAssets = listOf(
            GitHubUpdateChecker.ReleaseAsset(
                name = "app-release.apk",
                downloadUrl = "https://github.com/Adetisola/Laterbox/releases/download/v1.0.18/app-release.apk",
                size = 103_131_088L
            )
        )

        val selected = GitHubUpdateChecker.selectBestApkAsset(
            legacyAssets,
            arrayOf("arm64-v8a")
        )

        assertNotNull(selected)
        assertEquals("app-release.apk", selected!!.asset.name)
        assertEquals("Universal", selected.targetAbiLabel)
    }

    @Test
    fun `returns null when no APK assets exist`() {
        val nonApkAssets = listOf(
            GitHubUpdateChecker.ReleaseAsset(
                name = "source-code.zip",
                downloadUrl = "https://example.com/source.zip",
                size = 1000L
            )
        )

        val selected = GitHubUpdateChecker.selectBestApkAsset(nonApkAssets, arrayOf("arm64-v8a"))
        assertNull(selected)
    }

    @Test
    fun `32-bit device rejects ARM64-only release and returns null`() {
        val arm64OnlyAssets = listOf(
            GitHubUpdateChecker.ReleaseAsset(
                name = "Laterbox-ARM64.apk",
                downloadUrl = "https://example.com/Laterbox-ARM64.apk",
                size = 59_000_000L
            )
        )

        val selected = GitHubUpdateChecker.selectBestApkAsset(
            arm64OnlyAssets,
            arrayOf("armeabi-v7a", "armeabi")
        )
        assertNull(selected)
    }

    private fun buildZipApkWithAbis(vararg abis: String): java.io.File {
        val f = java.io.File.createTempFile("laterbox_abi_test_", ".apk")
        java.util.zip.ZipOutputStream(f.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("AndroidManifest.xml"))
            zos.write(byteArrayOf(1, 2, 3, 4))
            zos.closeEntry()
            for (abi in abis) {
                zos.putNextEntry(java.util.zip.ZipEntry("lib/$abi/libtest.so"))
                zos.write(abi.toByteArray())
                zos.closeEntry()
            }
        }
        return f
    }

    private fun rollingManifest1050To1055(): DeltaManifest {
        val patches = mutableListOf<DeltaPatchInfo>()
        for (minor in 50..54) {
            val ver = "1.0.$minor"
            val code = 160L + minor
            patches.add(
                DeltaPatchInfo(
                    architecture = "arm64-v8a",
                    baseArchitecture = "arm64-v8a",
                    architectureLabel = "ARM64 (arm64-v8a)",
                    baseVersion = ver,
                    baseVersionCode = code,
                    baseApkSha256 = "64${minor}".padEnd(64, 'a'),
                    targetApkFilename = "Laterbox-ARM64.apk",
                    targetApkSha256 = "t64".padEnd(64, 'b'),
                    targetApkSize = 61_658_218L,
                    patchFilename = "Laterbox-ARM64-$ver-to-1.0.55.patch",
                    patchSha256 = "p64${minor}".padEnd(64, 'c'),
                    patchSize = 4_200_000L + minor,
                    patchAlgorithm = "BSDIFF40"
                )
            )
            patches.add(
                DeltaPatchInfo(
                    architecture = "armeabi-v7a",
                    baseArchitecture = "armeabi-v7a",
                    architectureLabel = "ARM32 (armeabi-v7a)",
                    baseVersion = ver,
                    baseVersionCode = code,
                    baseApkSha256 = "32${minor}".padEnd(64, 'd'),
                    targetApkFilename = "Laterbox-ARM32.apk",
                    targetApkSha256 = "t32".padEnd(64, 'e'),
                    targetApkSize = 55_004_494L,
                    patchFilename = "Laterbox-ARM32-$ver-to-1.0.55.patch",
                    patchSha256 = "p32${minor}".padEnd(64, 'f'),
                    patchSize = 3_900_000L + minor,
                    patchAlgorithm = "BSDIFF40"
                )
            )
            patches.add(
                DeltaPatchInfo(
                    architecture = "universal",
                    baseArchitecture = "universal",
                    architectureLabel = "Universal",
                    baseVersion = ver,
                    baseVersionCode = code,
                    baseApkSha256 = "00${minor}".padEnd(64, '0'),
                    targetApkFilename = "Laterbox-Universal.apk",
                    targetApkSha256 = "tu0".padEnd(64, '1'),
                    targetApkSize = 105_159_854L,
                    patchFilename = "Laterbox-Universal-$ver-to-1.0.55.patch",
                    patchSha256 = "pu0${minor}".padEnd(64, '2'),
                    patchSize = 7_500_000L + minor,
                    patchAlgorithm = "BSDIFF40"
                )
            )
        }
        return DeltaManifest(
            schemaVersion = 1,
            targetVersion = "1.0.55",
            targetVersionCode = 168L,
            generatedAt = 1795045554000L,
            patches = patches
        )
    }

    @Test
    fun `extractApkNativeAbis and inferVariantFromAbis identify ARM32, ARM64, and Universal APKs`() {
        val arm32Apk = buildZipApkWithAbis("armeabi-v7a")
        val arm64Apk = buildZipApkWithAbis("arm64-v8a")
        val universalApk = buildZipApkWithAbis("armeabi-v7a", "arm64-v8a")
        try {
            val arm32Abis = InstalledApkHelper.extractApkNativeAbis(arm32Apk)
            assertEquals(setOf("armeabi-v7a"), arm32Abis)
            assertEquals("armeabi-v7a", InstalledApkHelper.inferVariantFromAbis(arm32Abis))

            val arm64Abis = InstalledApkHelper.extractApkNativeAbis(arm64Apk)
            assertEquals(setOf("arm64-v8a"), arm64Abis)
            assertEquals("arm64-v8a", InstalledApkHelper.inferVariantFromAbis(arm64Abis))

            val uniAbis = InstalledApkHelper.extractApkNativeAbis(universalApk)
            assertEquals(setOf("armeabi-v7a", "arm64-v8a"), uniAbis)
            assertEquals("universal", InstalledApkHelper.inferVariantFromAbis(uniAbis))
        } finally {
            arm32Apk.delete()
            arm64Apk.delete()
            universalApk.delete()
        }
    }

    @Test
    fun `rolling manifest resolves v1_0_53 and v1_0_54 across ARM64, ARM32 on 64-bit device, and Universal`() {
        val manifest = rollingManifest1050To1055()
        val device64And32 = arrayOf("arm64-v8a", "armeabi-v7a", "armeabi")

        // v1.0.53 ARM32 installed on a 64-bit capable device selects ARM32 delta patch
        val res53Arm32 = manifest.findCompatiblePatchDetailed(
            installedApkSha256 = "3253".padEnd(64, 'd'),
            deviceSupportedAbis = device64And32,
            installedApkAbis = setOf("armeabi-v7a")
        )
        assertEquals(DeltaDiagnosticReason.SELECTED, res53Arm32.reason)
        assertEquals("Laterbox-ARM32-1.0.53-to-1.0.55.patch", res53Arm32.patch?.patchFilename)
        assertEquals("armeabi-v7a", res53Arm32.installedVariant)

        // v1.0.53 ARM64 installed on a 64-bit device selects ARM64 delta patch
        val res53Arm64 = manifest.findCompatiblePatchDetailed(
            installedApkSha256 = "6453".padEnd(64, 'a'),
            deviceSupportedAbis = device64And32,
            installedApkAbis = setOf("arm64-v8a")
        )
        assertEquals(DeltaDiagnosticReason.SELECTED, res53Arm64.reason)
        assertEquals("Laterbox-ARM64-1.0.53-to-1.0.55.patch", res53Arm64.patch?.patchFilename)

        // v1.0.54 ARM32 and ARM64
        val res54Arm32 = manifest.findCompatiblePatchDetailed(
            installedApkSha256 = "3254".padEnd(64, 'd'),
            deviceSupportedAbis = device64And32,
            installedApkAbis = setOf("armeabi-v7a")
        )
        assertEquals(DeltaDiagnosticReason.SELECTED, res54Arm32.reason)
        assertEquals("Laterbox-ARM32-1.0.54-to-1.0.55.patch", res54Arm32.patch?.patchFilename)

        // v1.0.50 Universal
        val res50Uni = manifest.findCompatiblePatchDetailed(
            installedApkSha256 = "0050".padEnd(64, '0'),
            deviceSupportedAbis = device64And32,
            installedApkAbis = setOf("arm64-v8a", "armeabi-v7a")
        )
        assertEquals(DeltaDiagnosticReason.SELECTED, res50Uni.reason)
        assertEquals("Laterbox-Universal-1.0.50-to-1.0.55.patch", res50Uni.patch?.patchFilename)
    }

    @Test
    fun `32-bit-only device rejects ARM64 patch even when SHA matches and unknown SHA returns NO_MATCHING_BASE_HASH`() {
        val manifest = rollingManifest1050To1055()
        val arm32OnlyDevice = arrayOf("armeabi-v7a", "armeabi")

        val incompatible = manifest.findCompatiblePatchDetailed(
            installedApkSha256 = "6453".padEnd(64, 'a'),
            deviceSupportedAbis = arm32OnlyDevice,
            installedApkAbis = setOf("arm64-v8a")
        )
        assertNull(incompatible.patch)
        assertEquals(DeltaDiagnosticReason.NO_ARCHITECTURE_COMPATIBLE_PATCH, incompatible.reason)

        val noHash = manifest.findCompatiblePatchDetailed(
            installedApkSha256 = "9".repeat(64),
            deviceSupportedAbis = arm32OnlyDevice,
            installedApkAbis = setOf("armeabi-v7a")
        )
        assertNull(noHash.patch)
        assertEquals(DeltaDiagnosticReason.NO_MATCHING_BASE_HASH, noHash.reason)
    }

    @Test
    fun `deterministic selection across multiple matching candidates is order-independent`() {
        val sharedSha = "deadbeef".repeat(8)
        val smallerArm32 = DeltaPatchInfo(
            architecture = "armeabi-v7a",
            baseArchitecture = "armeabi-v7a",
            architectureLabel = "ARM32 (armeabi-v7a)",
            baseVersion = "1.0.53",
            baseVersionCode = 163L,
            baseApkSha256 = sharedSha,
            targetApkFilename = "Laterbox-ARM32.apk",
            targetApkSha256 = "b".repeat(64),
            targetApkSize = 55_000_000L,
            patchFilename = "Laterbox-ARM32-small.patch",
            patchSha256 = "c".repeat(64),
            patchSize = 2_000_000L
        )
        val largerArm32 = smallerArm32.copy(
            patchFilename = "Laterbox-ARM32-large.patch",
            patchSize = 4_000_000L
        )

        val forward = DeltaManifest(targetVersion = "1.0.55", targetVersionCode = 168L, patches = listOf(largerArm32, smallerArm32))
        val reversed = DeltaManifest(targetVersion = "1.0.55", targetVersionCode = 168L, patches = listOf(smallerArm32, largerArm32))

        val r1 = forward.findCompatiblePatchDetailed(sharedSha, arrayOf("arm64-v8a", "armeabi-v7a"), setOf("armeabi-v7a"))
        val r2 = reversed.findCompatiblePatchDetailed(sharedSha, arrayOf("arm64-v8a", "armeabi-v7a"), setOf("armeabi-v7a"))
        assertEquals("Laterbox-ARM32-small.patch", r1.patch?.patchFilename)
        assertEquals(r1.patch?.patchFilename, r2.patch?.patchFilename)
    }
}

