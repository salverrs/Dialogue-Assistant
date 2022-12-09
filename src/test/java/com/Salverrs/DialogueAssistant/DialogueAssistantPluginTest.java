package com.Salverrs.DialogueAssistant;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class DialogueAssistantPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(DialogueAssistantPlugin.class);
		RuneLite.main(args);
	}
}