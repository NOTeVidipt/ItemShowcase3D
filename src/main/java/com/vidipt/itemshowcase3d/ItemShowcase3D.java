package com.vidipt.itemshowcase3d;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.util.*;

public final class ItemShowcase3D extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {
    private final Map<String, Showcase> showcases = new LinkedHashMap<>();
    private final Map<UUID, List<ItemStack>> pendingReturns = new HashMap<>();

    @Override public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("ishowcase")).setExecutor(this);
        Objects.requireNonNull(getCommand("ishowcase")).setTabCompleter(this);
        loadShowcases();
        loadPendingReturns();
        startDisplayAnimation();
        getLogger().info("ItemShowcase3D enabled. Use /ishowcase help");
    }
    @Override public void onDisable() { saveShowcases(); savePendingReturns(); }

    private String key(String s) { return s.toLowerCase(Locale.ROOT); }
    private boolean admin(CommandSender s) {
        if (s.hasPermission("itemshowcase3d.admin")) return true;
        s.sendMessage(color("&cYou need itemshowcase3d.admin permission.")); return false;
    }
    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
    private void msg(CommandSender s, String text) { s.sendMessage(color(getConfig().getString("messages.prefix", "&8[&bShowcase&8] ") + text)); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            msg(sender, "&f/ishowcase create <name> &7- Create case around held item");
            msg(sender, "&f/ishowcase remove <name> &7- Remove showcase");
            msg(sender, "&f/ishowcase list &7- List showcases");
            msg(sender, "&f/ishowcase reload &7- Reload config");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (!(sender instanceof Player p)) { msg(sender, "&cPlayers only."); return true; }
                if (!admin(sender)) return true;
                if (args.length < 2) { msg(sender, "&cUsage: /ishowcase create <name>"); return true; }
                String id = key(args[1]);
                if (showcases.containsKey(id)) { msg(sender, "&cThat name already exists."); return true; }
                ItemStack held = p.getInventory().getItemInMainHand();
                if (held.getType().isAir()) { msg(sender, "&cHold the item you want to display."); return true; }
                long owned = showcases.values().stream().filter(x -> x.owner.equals(p.getUniqueId().toString())).count();
                if (owned >= getConfig().getInt("max-showcases-per-player", 20)) { msg(sender, "&cShowcase limit reached."); return true; }
                Location base = p.getLocation().getBlock().getLocation();
                if (!base.getBlock().isPassable() || !base.clone().add(0,1,0).getBlock().isPassable()) {
                    base = p.getLocation().getBlock().getRelative(0,0,1).getLocation();
                }
                try {
                    ItemStack displayedItem = held.clone();
                    displayedItem.setAmount(1); // One showcase consumes exactly one item.
                    Showcase sh = createShowcase(id, p, base, displayedItem);
                    showcases.put(id, sh);
                    if (held.getAmount() <= 1) {
                        p.getInventory().setItemInMainHand(null);
                    } else {
                        held.setAmount(held.getAmount() - 1);
                        p.getInventory().setItemInMainHand(held);
                    }
                    saveShowcases();
                    msg(sender, "&aCreated showcase '&f" + id + "&a'. &7One item was taken from your inventory.");
                } catch (Exception ex) { getLogger().warning("Could not create showcase: " + ex.getMessage()); msg(sender, "&cCould not create showcase; check console."); }
            }
            case "remove" -> {
                if (!admin(sender)) return true;
                if (args.length < 2) { msg(sender, "&cUsage: /ishowcase remove <name>"); return true; }
                Showcase sh = showcases.remove(key(args[1]));
                if (sh == null) { msg(sender, "&cShowcase not found."); return true; }
                destroy(sh, true);
                returnItemToOwner(sh);
                saveShowcases();
                savePendingReturns();
                msg(sender, "&aShowcase removed. &7The displayed item will be returned to its creator.");
            }
            case "list" -> {
                if (showcases.isEmpty()) msg(sender, "&7No showcases created.");
                else msg(sender, "&fShowcases: &b" + String.join(", ", showcases.keySet()));
            }
            case "reload" -> {
                if (!admin(sender)) return true;
                reloadConfig(); msg(sender, "&aConfiguration reloaded.");
            }
            default -> msg(sender, "&cUnknown subcommand. Use /ishowcase help.");
        }
        return true;
    }


    private void startDisplayAnimation() {
        // Update display transformations on the main server thread. ItemDisplay entities
        // are persistent, so this also resumes animation after a server restart.
        getServer().getScheduler().runTaskTimer(this, new Runnable() {
            private final Map<UUID, Float> angles = new HashMap<>();

            @Override public void run() {
                if (!getConfig().getBoolean("animate", true)) return;
                float degreesPerSecond = (float) getConfig().getDouble("rotation-degrees-per-second", 12.0);
                float step = (float) (Math.toRadians(degreesPerSecond) / 20.0);
                float scale = (float) getConfig().getDouble("item-scale", 0.8);
                for (Showcase sh : showcases.values()) {
                    Entity entity = Bukkit.getEntity(sh.entity);
                    if (!(entity instanceof ItemDisplay display) || !display.isValid()) continue;
                    float angle = (angles.getOrDefault(sh.entity, 0.0f) + step) % ((float) (Math.PI * 2));
                    angles.put(sh.entity, angle);
                    display.setTransformation(new Transformation(
                        new Vector3f(0, 0, 0),
                        new AxisAngle4f(angle, 0, 1, 0),
                        new Vector3f(scale, scale, scale),
                        new AxisAngle4f(0, 0, 1, 0)
                    ));
                }
            }
        }, 1L, 1L);
    }

    private Showcase createShowcase(String id, Player owner, Location base, ItemStack item) {
        World w = Objects.requireNonNull(base.getWorld());
        Material pedestal = material(getConfig().getString("pedestal-material"), Material.SPRUCE_PLANKS);
        Material glass = material(getConfig().getString("glass-material"), Material.GLASS);
        List<BlockState> originals = new ArrayList<>();
        // Build only in air/passable space and remember original block states for safe removal.
        for (int y=0; y<=2; y++) {
            Block b = base.clone().add(0,y,0).getBlock();
            if (!b.isPassable() && !b.isLiquid()) throw new IllegalStateException("Space is blocked at showcase location");
            originals.add(b.getState());
        }
        base.getBlock().setType(pedestal, false);
        base.clone().add(0,1,0).getBlock().setType(glass, false);
        base.clone().add(0,2,0).getBlock().setType(glass, false);
        Location itemLoc = base.clone().add(0.5, 1.65, 0.5);
        ItemDisplay display = (ItemDisplay) w.spawnEntity(itemLoc, EntityType.ITEM_DISPLAY);
        display.setItemStack(item);
        display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
        float scale = (float)getConfig().getDouble("item-scale", 0.8);
        display.setTransformation(new Transformation(new Vector3f(0,0,0), new AxisAngle4f(0,0,1,0), new Vector3f(scale,scale,scale), new AxisAngle4f(0,0,1,0)));
        display.setPersistent(true);
        display.setInvulnerable(true);
        display.setSilent(true);
        return new Showcase(id, owner.getUniqueId().toString(), w.getName(), base.getBlockX(), base.getBlockY(), base.getBlockZ(), display.getUniqueId(), item, originals);
    }
    private Material material(String name, Material fallback) {
        Material m = Material.matchMaterial(name == null ? "" : name);
        return m == null || !m.isBlock() ? fallback : m;
    }
    private void destroy(Showcase sh, boolean restore) {
        World w = Bukkit.getWorld(sh.world);
        if (w != null) {
            Entity e = Bukkit.getEntity(sh.entity);
            if (e != null) e.remove();
            if (restore) {
                Location base = new Location(w, sh.x, sh.y, sh.z);
                // Restore only blocks that still match blocks created by this plugin.
                Material ped = material(getConfig().getString("pedestal-material"), Material.SPRUCE_PLANKS);
                Material glass = material(getConfig().getString("glass-material"), Material.GLASS);
                if (base.getBlock().getType() == ped) base.getBlock().setType(Material.AIR, false);
                for (int y=1; y<=2; y++) {
                    Block b = base.clone().add(0,y,0).getBlock();
                    if (b.getType() == glass) b.setType(Material.AIR, false);
                }
            }
        }
    }
    @EventHandler public void onPlayerJoin(PlayerJoinEvent e) {
        List<ItemStack> items = pendingReturns.remove(e.getPlayer().getUniqueId());
        if (items == null || items.isEmpty()) return;
        for (ItemStack item : items) {
            Map<Integer, ItemStack> leftover = e.getPlayer().getInventory().addItem(item.clone());
            for (ItemStack stack : leftover.values()) {
                e.getPlayer().getWorld().dropItemNaturally(e.getPlayer().getLocation(), stack);
            }
        }
        savePendingReturns();
        e.getPlayer().sendMessage(color("&8[&bShowcase&8] &aYour removed showcase item(s) have been returned."));
    }

    private void returnItemToOwner(Showcase sh) {
        UUID ownerId;
        try { ownerId = UUID.fromString(sh.owner); }
        catch (IllegalArgumentException ex) {
            getLogger().warning("Could not return item for showcase " + sh.id + ": invalid owner UUID.");
            return;
        }
        Player owner = Bukkit.getPlayer(ownerId);
        if (owner != null && owner.isOnline()) {
            Map<Integer, ItemStack> leftover = owner.getInventory().addItem(sh.item.clone());
            for (ItemStack stack : leftover.values()) {
                owner.getWorld().dropItemNaturally(owner.getLocation(), stack);
            }
            owner.sendMessage(color("&8[&bShowcase&8] &aYour item from showcase '&f" + sh.id + "&a' was returned."));
        } else {
            pendingReturns.computeIfAbsent(ownerId, k -> new ArrayList<>()).add(sh.item.clone());
            getLogger().info("Queued returned showcase item for offline owner " + ownerId + " (showcase " + sh.id + ").");
        }
    }

    private void savePendingReturns() {
        var root = new org.bukkit.configuration.file.YamlConfiguration();
        for (Map.Entry<UUID, List<ItemStack>> entry : pendingReturns.entrySet()) {
            root.set("pending." + entry.getKey().toString(), entry.getValue());
        }
        try { root.save(new File(getDataFolder(), "pending-returns.yml")); }
        catch (Exception e) { getLogger().severe("Could not save pending-returns.yml: " + e.getMessage()); }
    }

    private void loadPendingReturns() {
        File f = new File(getDataFolder(), "pending-returns.yml");
        if (!f.exists()) return;
        var root = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
        var section = root.getConfigurationSection("pending");
        if (section == null) return;
        for (String rawUuid : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(rawUuid);
                List<?> rawItems = root.getList("pending." + rawUuid, Collections.emptyList());
                List<ItemStack> items = new ArrayList<>();
                for (Object obj : rawItems) if (obj instanceof ItemStack stack) items.add(stack.clone());
                if (!items.isEmpty()) pendingReturns.put(uuid, items);
            } catch (IllegalArgumentException ex) {
                getLogger().warning("Skipping invalid UUID in pending-returns.yml: " + rawUuid);
            }
        }
    }

    private boolean isShowcaseBlock(Block block, Showcase sh) {
        if (!sh.world.equals(block.getWorld().getName())) return false;
        int x = block.getX() - sh.x;
        int y = block.getY() - sh.y;
        int z = block.getZ() - sh.z;
        // Protect the pedestal and the glass case blocks created by this plugin.
        return x == 0 && z == 0 && (y == 0 || y == 1 || y == 2);
    }

    @EventHandler public void onBreak(BlockBreakEvent e) {
        for (Showcase sh : showcases.values()) {
            if (isShowcaseBlock(e.getBlock(), sh) && !e.getPlayer().isOp()) {
                e.setCancelled(true);
                e.getPlayer().sendMessage(color("&cOnly server operators can break a showcase."));
                return;
            }
        }
    }

    @EventHandler public void onShowcaseEntityDamage(EntityDamageEvent e) {
        for (Showcase sh : showcases.values()) {
            if (sh.entity.equals(e.getEntity().getUniqueId())) {
                if (!(e instanceof org.bukkit.event.entity.EntityDamageByEntityEvent byEntity
                        && byEntity.getDamager() instanceof Player player && player.isOp())) {
                    e.setCancelled(true);
                }
                return;
            }
        }
    }

    @EventHandler public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(block -> {
            for (Showcase sh : showcases.values()) {
                if (isShowcaseBlock(block, sh)) return true;
            }
            return false;
        });
    }

    @EventHandler public void onInteract(PlayerInteractEntityEvent e) {
        if (e.getRightClicked() instanceof ItemDisplay) {
            for (Showcase sh: showcases.values()) if (sh.entity.equals(e.getRightClicked().getUniqueId())) e.setCancelled(true);
        }
    }
    @Override public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        if (args.length==1) return List.of("create","remove","list","reload","help").stream().filter(x->x.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length==2 && (args[0].equalsIgnoreCase("remove"))) return showcases.keySet().stream().filter(x->x.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        return Collections.emptyList();
    }

    private void saveShowcases() {
        var root = new org.bukkit.configuration.file.YamlConfiguration();
        for (Showcase s: showcases.values()) {
            String p = "showcases." + s.id + ".";
            root.set(p+"owner", s.owner); root.set(p+"world", s.world); root.set(p+"x",s.x); root.set(p+"y",s.y); root.set(p+"z",s.z);
            root.set(p+"entity",s.entity.toString()); root.set(p+"item",s.item);
        }
        try { root.save(new File(getDataFolder(),"showcases.yml")); } catch (Exception e) { getLogger().severe("Could not save showcases.yml: "+e.getMessage()); }
    }
    private void loadShowcases() {
        File f = new File(getDataFolder(),"showcases.yml");
        if (!f.exists()) return;
        var root = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
        var section = root.getConfigurationSection("showcases"); if (section==null) return;
        for (String id: section.getKeys(false)) {
            String p="showcases."+id+".";
            World w=Bukkit.getWorld(root.getString(p+"world",""));
            if (w==null) continue;
            UUID entity;
            try { entity=UUID.fromString(root.getString(p+"entity","")); } catch(Exception ex) { continue; }
            ItemStack item=root.getItemStack(p+"item"); if (item==null) continue;
            showcases.put(id,new Showcase(id,root.getString(p+"owner",""),w.getName(),root.getInt(p+"x"),root.getInt(p+"y"),root.getInt(p+"z"),entity,item,new ArrayList<>()));
        }
    }
    private record Showcase(String id, String owner, String world, int x, int y, int z, UUID entity, ItemStack item, List<BlockState> originals) {}
}