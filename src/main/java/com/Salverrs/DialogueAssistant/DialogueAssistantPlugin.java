package com.Salverrs.DialogueAssistant;

import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetID;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import java.awt.*;
import java.lang.reflect.Type;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

import static net.runelite.http.api.RuneLiteAPI.GSON;

@Slf4j
@PluginDescriptor(
	name = "Dialogue Assistant",
	description = "Highlight and lock NPC dialogue options.",
	tags = "dialogue, dialog, assistant, npc, chat, options, lock, highlight"
)
public class DialogueAssistantPlugin extends Plugin
{
	public static final String CONFIG_GROUP = "DIALOGUE_ASSISTANT";
	private final String MAP_KEY = "DIALOGUE_CONFIG";
	private NPC lastInteractionNPC;
	private boolean initialised = false;
	private int optionParentId;
	private Map<Integer, NPCDialogueConfig> dialogMap = new HashMap<>();
	private List<Widget> recentWidgets = new ArrayList<>();

	@Inject
	private Client client;
	@Inject
	private DialogueAssistantConfig config;
	@Inject
	private ClientThread clientThread;
	@Inject
	private ConfigManager configManager;

	@Override
	protected void startUp()
	{
		loadConfig();
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == WidgetID.DIALOG_OPTION_GROUP_ID)
		{
			updateGroupWidgetId();
			checkDialogOptions();
		}
	}

	@Subscribe
	protected void onClientTick(ClientTick clientTick)
	{
		if (!initialised || client.getGameState() != GameState.LOGGED_IN || client.isMenuOpen())
			return;

		if (lastInteractionNPC == null)
			return;

		final List<MenuEntry> entries = new ArrayList<>(Arrays.asList(client.getMenuEntries()));

		for (MenuEntry entry : entries)
		{
			final Widget widget = entry.getWidget();
			final Widget parent = widget != null ? widget.getParent() : null;
			final int parentId = parent != null ? parent.getId() : -1;

			if (parentId != optionParentId)
				continue;

			final String option = entry.getWidget().getText();
			final NPCDialogueConfig dConfig = getNPCDConfig(lastInteractionNPC);

			final boolean isHighlighted = dConfig != null && dConfig.isHighlighted(option);
			final boolean isLocked = dConfig != null && dConfig.isLocked(option);

			if (isHighlighted || isLocked)
			{
				client.createMenuEntry(-1)
						.setOption("Reset Option")
						.setTarget("")
						.setType(MenuAction.RUNELITE)
						.onClick((e) -> resetOption(dConfig, option, widget));
			}

			if (!isLocked)
			{
				client.createMenuEntry(-1)
						.setOption("Lock Option")
						.setTarget("")
						.setType(MenuAction.RUNELITE)
						.onClick((e) -> setAsLockedOption(lastInteractionNPC, option, widget));
			}

			if (!isHighlighted)
			{
				client.createMenuEntry(-1)
						.setOption("Highlight Option")
						.setTarget("")
						.setType(MenuAction.RUNELITE)
						.onClick((e) -> setAsHighlightedOption(lastInteractionNPC, option, widget));
			}
		}
	}

	@Subscribe
	private void onInteractingChanged(InteractingChanged event)
	{
		if (event.getSource() != client.getLocalPlayer())
			return;

		final Actor target = event.getTarget();

		if (target instanceof NPC)
			lastInteractionNPC = (NPC)event.getTarget();
	}

	@Subscribe
	private void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!initialised || lastInteractionNPC == null)
			return;

		final NPCDialogueConfig dConfig = getNPCDConfig(lastInteractionNPC);

		if (dConfig == null)
			return;

		final Widget widget = event.getWidget();
		final String option = widget != null ? widget.getText() : null;

		if (option == null)
			return;

		if (dConfig.isLocked(option))
			event.consume();
	}

	private void updateGroupWidgetId()
	{
		if (initialised)
			return;

		final Widget optionGroup = client.getWidget(WidgetInfo.DIALOG_OPTION_OPTIONS);
		optionParentId = optionGroup.getId();
		initialised = true;
	}

	private void checkDialogOptions()
	{
		if (!initialised)
			return;

		final NPCDialogueConfig dConfig = getNPCDConfig(lastInteractionNPC);
		if (dConfig == null)
			return;

		setAllOptionsLocked(true);

		clientThread.invokeLater(() ->
		{
			if (lastInteractionNPC == null)
			{
				setAllOptionsLocked(false);
				return;
			}

			final Widget optionGroup = client.getWidget(WidgetInfo.DIALOG_OPTION_OPTIONS);
			final Widget[] children = optionGroup != null ? optionGroup.getChildren() : null;

			if (children == null || children.length == 0)
			{
				setAllOptionsLocked(false);
				return;
			}

			final List<Widget> options = Arrays.stream(children).filter(Widget::hasListener).collect(Collectors.toList());

			recentWidgets = options;

			for (Widget optionWidget : options)
			{
				final String option = optionWidget.getText();

				if (dConfig.isHighlighted(option))
				{
					highlightOptionWidget(optionWidget, true, false);
				}
				else if (dConfig.isLocked(option))
				{
					lockOptionWidget(optionWidget, true, false);
				}
			}

			setAllOptionsLocked(false);
		});
	}

	private void highlightOptionWidget(Widget optionWidget, boolean highlighted, boolean reset)
	{
		if (highlighted)
		{
			optionWidget.setTextColor(config.optionHighlightColor().getRGB());
			optionWidget.setOnMouseLeaveListener((JavaScriptCallback) ev -> optionWidget.setTextColor(config.optionHighlightColor().getRGB()));
		}
		else if (reset)
		{
			optionWidget.setTextColor(Color.BLACK.getRGB());
			optionWidget.setOnMouseLeaveListener((JavaScriptCallback) ev -> optionWidget.setTextColor(Color.BLACK.getRGB()));
		}
	}

	private void lockOptionWidget(Widget optionWidget, boolean locked, boolean reset)
	{
		if (locked)
		{
			optionWidget.setHasListener(false);
			optionWidget.setTextColor(config.optionLockedColor().getRGB());
			optionWidget.setOnMouseLeaveListener((JavaScriptCallback) ev -> optionWidget.setTextColor(config.optionLockedColor().getRGB()));
		}
		else if (reset)
		{
			optionWidget.setHasListener(true);
			optionWidget.setTextColor(Color.BLACK.getRGB());
			optionWidget.setOnMouseLeaveListener((JavaScriptCallback) ev -> optionWidget.setTextColor(Color.BLACK.getRGB()));
		}
	}

	private void setAllOptionsLocked(boolean locked) // Prevents user from spamming option key before widget lock is applied
	{
		final Widget optionGroup = client.getWidget(WidgetInfo.DIALOG_OPTION_OPTIONS);
		if (optionGroup == null)
			return;

		optionGroup.setHidden(locked);
	}

	private void setAsHighlightedOption(NPC npc, String optionTarget, Widget widget)
	{
		clientThread.invokeLater(() ->
		{
			final NPCDialogueConfig dConfig = getNPCDConfig(npc, true);
			dConfig.setHighlighted(optionTarget);
			refreshOptionState(dConfig, optionTarget, widget);
			saveConfig();
		});
	}

	private void setAsLockedOption(NPC npc, String optionTarget, Widget widget)
	{
		clientThread.invokeLater(() ->
		{
			final NPCDialogueConfig dConfig = getNPCDConfig(npc, true);
			dConfig.setLocked(optionTarget);
			refreshOptionState(dConfig, optionTarget, widget);
			saveConfig();
		});
	}

	private void resetOption(NPCDialogueConfig dConfig, String optionTarget, Widget widget)
	{
		clientThread.invokeLater(() ->
		{
			dConfig.resetOption(optionTarget);
			refreshOptionState(dConfig, optionTarget, widget);
			saveConfig();
		});
	}

	private void refreshOptionState(NPCDialogueConfig dConfig, String optionTarget, Widget widget)
	{
		highlightOptionWidget(widget, false, true);
		lockOptionWidget(widget, false, true);

		highlightOptionWidget(widget, dConfig.isHighlighted(optionTarget), false);
		lockOptionWidget(widget, dConfig.isLocked(optionTarget), false);
	}

	private NPCDialogueConfig getNPCDConfig(NPC npc)
	{
		return getNPCDConfig(npc, false);
	}

	private NPCDialogueConfig getNPCDConfig(NPC npc, boolean forceCreate)
	{
		final int npcId = npc.getId();
		final NPCDialogueConfig dConfig = dialogMap.getOrDefault(npcId, null);

		if (forceCreate && dConfig == null)
		{
			return addNPCDialogueConfig(npc);
		}
		else
		{
			return dialogMap.getOrDefault(npcId, null);
		}
	}

	private NPCDialogueConfig addNPCDialogueConfig(NPC npc)
	{
		final NPCDialogueConfig dialogueOptions = new NPCDialogueConfig(npc);
		dialogMap.put(npc.getId(), dialogueOptions);
		return dialogueOptions;
	}

	private void resetAllRecentWidgets()
	{
		clientThread.invokeLater(() ->
		{
			setAllOptionsLocked(false);

			for (Widget widget : recentWidgets)
			{
				if (widget == null)
					continue;

				highlightOptionWidget(widget, false, true);
				lockOptionWidget(widget, false, true);
			}

			recentWidgets.clear();
		});
	}

	private void saveConfig()
	{
		final String json = GSON.toJson(dialogMap);
		configManager.setConfiguration(CONFIG_GROUP, MAP_KEY, json);
	}

	private void loadConfig()
	{
		final String json = configManager.getConfiguration(CONFIG_GROUP, MAP_KEY);
		if (json == null || json.equals(""))
		{
			dialogMap = new HashMap<>();
		}
		else
		{
			final Type mapType = new TypeToken<Map<Integer, NPCDialogueConfig>>(){}.getType();
			dialogMap = GSON.fromJson(json, mapType);
		}
	}

	@Override
	protected void shutDown()
	{
		initialised = false;
		resetAllRecentWidgets();
	}

	@Provides
	DialogueAssistantConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(DialogueAssistantConfig.class);
	}
}
