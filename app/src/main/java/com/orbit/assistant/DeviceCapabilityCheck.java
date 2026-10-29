package com.orbit.assistant;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;

/**
 * Honest, evidence-based assessment of whether this device can run Orbit Local.
 *
 * <p>Only facts Android actually exposes are used: CPU architecture, OS version, total memory,
 * and free storage. NPU/DSP details are not reliably visible to apps, so nothing here pretends
 * to know them; the tiers reflect what those observable facts genuinely predict for on-device
 * language-model inference.
 */
public final class DeviceCapabilityCheck {

    public enum Tier { EXCELLENT, SUPPORTED, LIMITED, NOT_RECOMMENDED, UNSUPPORTED }

    public static final class Assessment {
        public final Tier tier;
        /** One plain sentence explaining the tier to the user (set once a Context has resolved it). */
        public final String summary;
        private final int summaryRes;
        private final String summaryArg;
        private final String label;

        Assessment(Tier tier, int summaryRes, String summaryArg) {
            this(tier, summaryRes, summaryArg, "", "");
        }

        private Assessment(Tier tier, int summaryRes, String summaryArg, String summary,
                           String label) {
            this.tier = tier;
            this.summaryRes = summaryRes;
            this.summaryArg = summaryArg;
            this.summary = summary;
            this.label = label;
        }

        /** The same assessment with its wording resolved in the app language. */
        Assessment resolve(Context c) {
            return new Assessment(tier, summaryRes, summaryArg, summary(c), tierLabel(c));
        }

        public String summary(Context c) {
            return summaryArg == null ? c.getString(summaryRes) : c.getString(summaryRes, summaryArg);
        }


        public String tierLabel() {
            return label;
        }

        public String tierLabel(Context c) {
            switch (tier) {
                case EXCELLENT: return c.getString(R.string.devcap_tier_excellent);
                case SUPPORTED: return c.getString(R.string.devcap_tier_supported);
                case LIMITED: return c.getString(R.string.devcap_tier_limited);
                case NOT_RECOMMENDED: return c.getString(R.string.devcap_tier_not_recommended);
                default: return c.getString(R.string.devcap_tier_unsupported);
            }
        }
    }

    private DeviceCapabilityCheck() {}

    public static Assessment assess(Context c) {
        long totalMem = 0L;
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(info);
                totalMem = info.totalMem;
            }
        } catch (Exception ignored) {}
        // A model already present anywhere — the component's, or one an older Orbit downloaded and
        // has not migrated yet — means the storage for it has already been found once, so the
        // assessment must not warn about needing room it does not need.
        OrbitLocalStatus status = OrbitLocalProvider.cachedStatus(c);
        boolean modelPresent = (status != null && status.modelReady())
                || LocalModelStore.hasLegacyModel(c);
        return assess(Build.SUPPORTED_ABIS, Build.VERSION.SDK_INT, totalMem,
                LocalModelStore.freeStorageBytes(c), modelPresent).resolve(c);
    }

    /** Pure decision logic, separated so it can be tested without a device. */
    static Assessment assess(String[] abis, int sdkInt, long totalMemBytes, long freeStorageBytes,
                             boolean modelAlreadyInstalled) {
        boolean arm64 = false;
        if (abis != null) {
            for (String abi : abis) if ("arm64-v8a".equals(abi)) { arm64 = true; break; }
        }
        if (!arm64) {
            return new Assessment(Tier.UNSUPPORTED,
                    R.string.devcap_unsupported_cpu, null);
        }
        if (sdkInt < 31) {
            return new Assessment(Tier.NOT_RECOMMENDED,
                    R.string.devcap_old_android, null);
        }

        long needed = LocalModelStore.MODEL_SIZE_BYTES + LocalModelStore.STORAGE_MARGIN_BYTES;
        boolean storageKnown = freeStorageBytes >= 0;
        boolean storageTight = storageKnown && !modelAlreadyInstalled && freeStorageBytes < needed;

        final long GB = 1024L * 1024 * 1024;
        boolean memKnown = totalMemBytes > 0;
        if (memKnown && totalMemBytes < 4L * GB) {
            return new Assessment(Tier.NOT_RECOMMENDED,
                    R.string.devcap_low_memory, null);
        }
        if (storageTight) {
            return new Assessment(Tier.LIMITED,
                    R.string.devcap_need_storage, LocalModelStore.formatBytes(needed));
        }
        if (memKnown && totalMemBytes < 6L * GB) {
            return new Assessment(Tier.LIMITED,
                    R.string.devcap_tight_memory, null);
        }
        if (memKnown && totalMemBytes >= 10L * GB) {
            return new Assessment(Tier.EXCELLENT,
                    R.string.devcap_excellent, null);
        }
        return new Assessment(Tier.SUPPORTED,
                R.string.devcap_supported, null);
    }

    /** True when the tier allows installing and running the local model at all. */
    public static boolean allowsLocalAi(Assessment a) {
        return a != null && a.tier != Tier.UNSUPPORTED;
    }
}
