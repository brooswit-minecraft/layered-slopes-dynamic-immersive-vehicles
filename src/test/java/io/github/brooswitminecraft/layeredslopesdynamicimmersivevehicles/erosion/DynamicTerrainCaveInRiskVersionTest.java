package io.github.brooswitminecraft.layeredslopesdynamicimmersivevehicles.erosion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.junit.jupiter.api.Test;

/**
 * {@link DynamicTerrainCaveInRisk#isVerifiedVersion} is a pure function over
 * {@code org.apache.maven.artifact.versioning} types (no Minecraft/NeoForge/{@code ModList}
 * environment needed), so the version-boundary logic review 54916/54917/54919 required is
 * directly testable here, deterministically, without Dynamic Terrain or any mod environment
 * actually loaded.
 */
class DynamicTerrainCaveInRiskVersionTest {

    @Test
    void theVerifiedRangeLowerBoundIsIncluded() {
        assertTrue(DynamicTerrainCaveInRisk.isVerifiedVersion(new DefaultArtifactVersion("0.28.2")));
    }

    @Test
    void theVerifiedRangeUpperBoundIsIncluded() {
        assertTrue(DynamicTerrainCaveInRisk.isVerifiedVersion(new DefaultArtifactVersion("5.0.1")));
    }

    @Test
    void aVersionWellInsideTheVerifiedRangeIsVerified() {
        assertTrue(DynamicTerrainCaveInRisk.isVerifiedVersion(new DefaultArtifactVersion("2.0.0")));
    }

    @Test
    void aVersionOlderThanTheVerifiedRangeIsNotVerified() {
        assertFalse(DynamicTerrainCaveInRisk.isVerifiedVersion(new DefaultArtifactVersion("0.28.1")));
    }

    @Test
    void aVersionOlderThanTheVerifiedRangeByAMajorStepIsNotVerified() {
        assertFalse(DynamicTerrainCaveInRisk.isVerifiedVersion(new DefaultArtifactVersion("0.1.0")));
    }

    @Test
    void aVersionNewerThanTheVerifiedRangeIsNotVerified() {
        assertFalse(DynamicTerrainCaveInRisk.isVerifiedVersion(new DefaultArtifactVersion("5.0.2")));
    }

    @Test
    void aVersionNewerThanTheVerifiedRangeByAMajorStepIsNotVerified() {
        assertFalse(DynamicTerrainCaveInRisk.isVerifiedVersion(new DefaultArtifactVersion("6.0.0")));
    }
}
