package com.Salverrs.DialogueAssistant;

import lombok.Getter;
import net.runelite.api.NPC;
import java.util.HashMap;
import java.util.Map;

public class DialogueConfig {
    @Getter
    private int targetId;

    private Map<String, OptionStatus> optionMap = new HashMap<>();

    public DialogueConfig(int id)
    {
        this.targetId = id;
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
