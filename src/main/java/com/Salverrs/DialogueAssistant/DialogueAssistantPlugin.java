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
	private int lastNPCInteractionId = -1;
	private int lastInteractionId = -1;
	private int optionParentId = -1;
	private int viewportBoxId = -1;
	private Map<Integer, DialogueConfig> dialogMap = new HashMap<>();
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
			checkDialogOptions();
	}

	@Subscribe
	protected void onClientTick(ClientTick clientTick)
	{
		if (client.getGameState() != GameState.LOGGED_IN || client.isMenuOpen())
			return;

		if (!hasTarget())
			return;

		final List<MenuEntry> entries = new ArrayList<>(Arrays.asList(client.getMenuEntries()));
		final DialogueConfig dConfig = getDConfig(lastInteractionId);

		for (MenuEntry entry : entries)
		{
			final Widget widget = entry.getWidget();
			if (!isDialogueOption(widget))
				continue;

			final String option = widget.getText();
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
						.onClick((e) -> setAsLockedOption(lastInteractionId, option, widget));
			}

			if (!isHighlighted)
			{
				client.createMenuEntry(-1)
						.setOption("Highlight Option")
						.setTarget("")
						.setType(MenuAction.RUNELITE)
						.onClick((e) -> setAsHighlightedOption(lastInteractionId, option, widget));
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
		{
			lastInteractionId = ((NPC)event.getTarget()).getId();
			lastNPCInteractionId = lastInteractionId;
			//log.info("[interact] last id: " + lastInteractionId);
		}
		else if (target == null)
		{
			lastNPCInteractionId = -1;
			//log.info("[interact - no set] last id: null");
		}
	}

	@Subscribe
	private void onMenuOptionClicked(MenuOptionClicked event)
	{
		final MenuEntry menuEntry = event.getMenuEntry();
		final Widget widget = menuEntry.getWidget();

		if (lastNPCInteractionId == -1 && (isWorldMenuOption(menuEntry) || isMenuException(menuEntry)))
		{
			final String menuOption = event.getMenuOption();
			final String menuTarget = event.getMenuTarget();

			lastInteractionId = getMenuHashId(menuOption, menuTarget);
			//log.info("[menu] last id: " + lastInteractionId);
		}

		final DialogueConfig dConfig = getDConfig(lastInteractionId);
		if (dConfig == null || widget == null)
			return;

		final String dialogueOption = widget.getText();
		if (dialogueOption == null || dialogueOption.equals(""))
			return;

		if (dConfig.isLocked(dialogueOption))
			event.consume();
	}

	private boolean isWorldMenuOption(MenuEntry menuEntry)
	{
		final NPC npc = menuEntry.getNpc();
		final Actor actor = menuEntry.getActor();
		final Player player = menuEntry.getPlayer();
		final MenuAction type = menuEntry.getType();

		return isWorldMenuActionType(type) || (player == null && (npc != null || actor != null));
	}

	private boolean isMenuException(MenuEntry menuEntry) // Allow menu entries from NPC Contact viewport box
	{
		final int maxSearch = 5;
		Widget widget = menuEntry.getWidget();

		if (menuEntry.getTarget().contains("NPC Contact"))
			return true;

		if (viewportBoxId == -1)
		{
			final Widget viewportBox = client.getWidget(WidgetInfo.RESIZABLE_VIEWPORT_OLD_SCHOOL_BOX);
			if (viewportBox == null)
				return false;

			viewportBoxId = viewportBox.getId();
		}

		for (int i = 0; i < maxSearch; i++)
		{
			if (widget == null)
				return false;

			if (widget.getId() == viewportBoxId)
				return true;

			widget = widget.getParent();
		}

		return false;
	}

	private boolean isWorldMenuActionType(MenuAction type)
	{
		switch(type)
		{
			case GAME_OBJECT_FIRST_OPTION:
			case GAME_OBJECT_SECOND_OPTION:
			case GAME_OBJECT_THIRD_OPTION:
			case GAME_OBJECT_FOURTH_OPTION:
			case GAME_OBJECT_FIFTH_OPTION:
			case NPC_FIRST_OPTION:
			case NPC_SECOND_OPTION:
			case NPC_THIRD_OPTION:
			case NPC_FOURTH_OPTION:
			case NPC_FIFTH_OPTION:
				return true;
			default:
				return false;
		}
	}

	private boolean isDialogueOption(Widget widget)
	{
		if (optionParentId == -1)
		{
			final Widget optionGroup = client.getWidget(WidgetInfo.DIALOG_OPTION_OPTIONS);
			if (optionGroup == null)
				return false;

			optionParentId = optionGroup.getId();
		}

		return widget != null && widget.getParent() != null && widget.getParent().getId() == optionParentId;
	}

	private int getMenuHashId(String option, String target)
	{
		if (target.contains("NPC Contact")) // So that quick chat (i.e. right-click -> NPC) hashes to the same ID as the normal menu option
			target = "";

		return (option + target).hashCode();
	}

	private void checkDialogOptions()
	{
		final DialogueConfig dConfig = getDConfig(lastInteractionId);
		if (dConfig == null)
			return;

		clientThread.invokeAtTickEnd(() ->
		{
			final Widget optionGroup = client.getWidget(WidgetInfo.DIALOG_OPTION_OPTIONS);
			final Widget[] children = optionGroup != null ? optionGroup.getChildren() : null;

			if (children == null || children.length == 0)
				return;

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

	private void setAsHighlightedOption(int targetId, String optionTarget, Widget widget)
	{
		clientThread.invokeLater(() ->
		{
			final DialogueConfig dConfig = getDConfig(targetId, true);
			dConfig.setHighlighted(optionTarget);
			refreshOptionState(dConfig, optionTarget, widget);
			saveConfig();
		});
	}

	private void setAsLockedOption(int targetId, String optionTarget, Widget widget)
	{
		clientThread.invokeLater(() ->
		{
			final DialogueConfig dConfig = getDConfig(targetId, true);
			dConfig.setLocked(optionTarget);
			refreshOptionState(dConfig, optionTarget, widget);
			saveConfig();
		});
	}

	private void resetOption(DialogueConfig dConfig, String optionTarget, Widget widget)
	{
		clientThread.invokeLater(() ->
		{
			dConfig.resetOption(optionTarget);
			refreshOptionState(dConfig, optionTarget, widget);
			saveConfig();
		});
	}

	private void refreshOptionState(DialogueConfig dConfig, String optionTarget, Widget widget)
	{
		highlightOptionWidget(widget, false, true);
		lockOptionWidget(widget, false, true);

		highlightOptionWidget(widget, dConfig.isHighlighted(optionTarget), false);
		lockOptionWidget(widget, dConfig.isLocked(optionTarget), false);
	}

	private DialogueConfig getDConfig(int id)
	{
		if (id == -1)
			return null;

		return getDConfig(id, false);
	}

	private DialogueConfig getDConfig(int id, boolean forceCreate)
	{
		if (id == -1)
			return null;

		final DialogueConfig dConfig = dialogMap.getOrDefault(id, null);

		if (forceCreate && dConfig == null)
		{
			return addDialogueConfig(id);
		}
		else
		{
			return dialogMap.getOrDefault(id, null);
		}
	}

	private DialogueConfig addDialogueConfig(int id)
	{
		final DialogueConfig dialogueOptions = new DialogueConfig(id);
		dialogMap.put(id, dialogueOptions);
		return dialogueOptions;
	}

	private void resetAllRecentWidgets()
	{
		clientThread.invokeLater(() ->
		{
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
			final Type mapType = new TypeToken<Map<Integer, DialogueConfig>>(){}.getType();
			dialogMap = GSON.fromJson(json, mapType);
		}
	}

	private boolean hasTarget()
	{
		return lastInteractionId != -1;
	}

	@Override
	protected void shutDown()
	{
		lastInteractionId = -1;
		resetAllRecentWidgets();
	}

	@Provides
	DialogueAssistantConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(DialogueAssistantConfig.class);
	}
}
