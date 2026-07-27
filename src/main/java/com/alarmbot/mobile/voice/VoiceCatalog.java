package com.alarmbot.mobile.voice;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class VoiceCatalog {
    public static final String VOICE_IU_DAEGUN = "iu_daegun";
    public static final String DISPLAY_IU_DAEGUN = "21세기대군부인아이유";

    private static final Map<String, VoicePack> PACKS;

    static {
        Map<String, VoicePack> map = new LinkedHashMap<>();
        map.put(VOICE_IU_DAEGUN, new VoicePack(
                VOICE_IU_DAEGUN,
                DISPLAY_IU_DAEGUN,
                "voices/iu_daegun/morningcall",
                3,
                6
        ));
        PACKS = Collections.unmodifiableMap(map);
    }

    private VoiceCatalog() {
    }

    public static VoicePack get(String id) {
        VoicePack pack = PACKS.get(id);
        return pack != null ? pack : PACKS.get(VOICE_IU_DAEGUN);
    }

    public static Map<String, VoicePack> all() {
        return PACKS;
    }

    public static final class VoicePack {
        public final String id;
        public final String displayName;
        public final String assetRoot;
        public final int setCount;
        public final int trackCount;

        public VoicePack(String id, String displayName, String assetRoot, int setCount, int trackCount) {
            this.id = id;
            this.displayName = displayName;
            this.assetRoot = assetRoot;
            this.setCount = setCount;
            this.trackCount = trackCount;
        }

        public String trackAssetPath(int setNumber, int trackNumber) {
            return assetRoot + "/" + setNumber + "/" + trackNumber + ".wav";
        }
    }
}
