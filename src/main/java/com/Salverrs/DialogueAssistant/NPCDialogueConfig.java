package com.Salverrs.DialogueAssistant;

import lombok.Getter;
import net.runelite.api.NPC;
import java.util.HashMap;
import java.util.Map;

public class NPCDialogueConfig {
    @Getter
    private int npcId;
    @Getter
    private String npcName;

    private Map<String, OptionStatus> optionMap = new HashMap<>();

    public NPCDialogueConfig(NPC npc)
    {
        this.npcId = npc.getId();
        this.npcName = npc.getName();
    }

    public boolean isHighlighted(String option)
    {
        return optionMap.containsKey(option) && optionMap.get(option) == OptionStatus.HIGHLIGHTED;
    }

    public boolean isLocked(String option)
    {
        return optionMap.containsKey(option) && optionMap.get(option) == OptionStatus.LOCKED;
    }

    public void setHighlighted(String option)
    {
        optionMap.put(option, OptionStatus.HIGHLIGHTED);
    }

    public void setLocked(String option)
    {
        optionMap.put(option, OptionStatus.LOCKED);
    }

    public void resetOption(String option)
    {
        optionMap.remove(option);
    }

}
