package dev.newyear.countdown.ai.wish;

/** HUD 에 보이는 능력·저주·축복 같은 지속 상태 표시. 실제 효과는 스크립트가 낸다. */
public final class StatusEntry {
    public String id;
    public String owner;
    public String label = "";
    public String description = "";
    /** ability / curse / blessing / info */
    public String kind = "ability";
    /** 픽셀 아이콘 (선택). 각 줄이 한 행이며 palette 의 문자로 색을 고른다. */
    public String[] iconPixels;
    public String iconPalette;
    /** 0 = 무기한. 엔진 틱 기준. */
    public long expiresTick;
    public long startTick;
    public String scriptId;
    public String wishId;
    /** 카운터 표시 (예: 점프 3/7). 비우면 없음. */
    public String counter = "";
}
