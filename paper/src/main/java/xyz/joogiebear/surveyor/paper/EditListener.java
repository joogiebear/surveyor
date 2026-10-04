package xyz.joogiebear.surveyor.paper;

import static net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed;

import java.util.List;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import xyz.joogiebear.surveyor.core.edit.BlockPos;

/** The selection wand, and per-player cleanup and notices for editing. */
final class EditListener implements Listener {

    static final String SELECT = "surveyor.edit.select";

    private static final Material WAND_MATERIAL = Material.WOODEN_AXE;

    private final SurveyorPlugin plugin;
    private final NamespacedKey wandKey;

    EditListener(SurveyorPlugin plugin) {
        this.plugin = plugin;
        this.wandKey = wandKey(plugin);
    }

    private static NamespacedKey wandKey(SurveyorPlugin plugin) {
        return new NamespacedKey(plugin, "wand");
    }

    static ItemStack createWand(SurveyorPlugin plugin) {
        ItemStack item = new ItemStack(WAND_MATERIAL);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MiniMessage.miniMessage().deserialize(plugin.messages().raw("selection.wand-name"))
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                MiniMessage.miniMessage().deserialize(plugin.messages().raw("selection.wand-lore"))
                        .decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(wandKey(plugin), PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isWand(ItemStack item) {
        return item != null && item.getType() == WAND_MATERIAL && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    // Left click is position 1 and right click is position 2. The click is cancelled so the wand
    // neither breaks nor opens anything. Without the permission it is an ordinary axe.
    @EventHandler(priority = EventPriority.NORMAL)
    public void onWandClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null
                || !isWand(event.getItem()) || !event.getPlayer().hasPermission(SELECT)) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        boolean first = action == Action.LEFT_CLICK_BLOCK;
        event.setCancelled(true);
        var block = event.getClickedBlock();
        plugin.edits().select(event.getPlayer(), block.getWorld().getName(), first,
                new BlockPos(block.getX(), block.getY(), block.getZ()));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        var interrupted = plugin.edits().interrupted();
        if (!interrupted.isEmpty() && player.hasPermission("surveyor.admin")) {
            plugin.reply(player, plugin.messages().get("edit.interrupted-notice",
                    unparsed("count", String.valueOf(interrupted.size())), unparsed("ids", String.join(", ", interrupted))));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.edits().forget(event.getPlayer().getUniqueId());
    }
}
